package com.jsd.aird.mfg.ingest.application;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.mfg.application.ProductionOrderService;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import com.jsd.aird.mfg.ingest.application.port.InstanceIngestRepository;
import com.jsd.aird.mfg.ingest.application.port.InstanceDocumentRecognitionClient;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.excel.WorkbookInstanceParser;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Separate instance-value ingestion boundary; it never writes template recognition tables. */
@Service
public class InstanceIngestService {

    private final InstanceIngestRepository jobs;
    private final ProductionOrderRepository orders;
    private final ProductionOrderService orderService;
    private final FileObjectRepository files;
    private final ObjectStorage storage;
    private final WorkbookInstanceParser xlsxParser;
    private final WorkbookInstanceExtractor extractor;
    private final WorkbookSnapshotValueWriter snapshotWriter;
    private final DocumentImagePreprocessor imagePreprocessor;
    private final InstanceDocumentRecognitionClient documentRecognitionClient;
    private final ObjectMapper objectMapper;
    private final JsonCanonicalizer canonicalizer;
    private final boolean enabled;
    private final boolean photoEnabled;
    private final String storageBucket;

    public InstanceIngestService(
            InstanceIngestRepository jobs,
            ProductionOrderRepository orders,
            ProductionOrderService orderService,
            FileObjectRepository files,
            ObjectStorage storage,
            WorkbookInstanceParser xlsxParser,
            WorkbookInstanceExtractor extractor,
            WorkbookSnapshotValueWriter snapshotWriter,
            DocumentImagePreprocessor imagePreprocessor,
            InstanceDocumentRecognitionClient documentRecognitionClient,
            ObjectMapper objectMapper,
            JsonCanonicalizer canonicalizer,
            @Value("${app.production-instance-ingest.enabled:false}") boolean enabled,
            @Value("${app.production-instance-photo.enabled:false}") boolean photoEnabled,
            @Value("${app.storage.bucket}") String storageBucket
    ) {
        this.jobs = jobs;
        this.orders = orders;
        this.orderService = orderService;
        this.files = files;
        this.storage = storage;
        this.xlsxParser = xlsxParser;
        this.extractor = extractor;
        this.snapshotWriter = snapshotWriter;
        this.imagePreprocessor = imagePreprocessor;
        this.documentRecognitionClient = documentRecognitionClient;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.enabled = enabled;
        this.photoEnabled = photoEnabled;
        this.storageBucket = storageBucket;
    }

    @Transactional
    public InstanceIngestRepository.Job create(UUID orderId, CreateCommand command) {
        requireEnabled();
        var actor = ActorContext.required();
        var order = orderService.get(orderId);
        if (!"DRAFT".equals(order.status())) {
            throw new ApiException(ApiErrorCode.TEMPLATE_VERSION_IMMUTABLE, "只有草稿生产单可以导入实例数据");
        }
        var sourceType = command.sourceType().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("XLSX", "PHOTO").contains(sourceType)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "实例来源必须是 XLSX 或 PHOTO");
        }
        if (command.sourceFileIds() == null || command.sourceFileIds().isEmpty()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "至少需要一个来源文件");
        }
        if ("XLSX".equals(sourceType) && command.sourceFileIds().size() != 1) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "一次 Excel 导入只能上传一个 XLSX");
        }
        command.sourceFileIds().forEach(id -> requireSource(actor.organizationId(), id, sourceType));
        var requested = command.requestedTemplateVersionId() == null
                ? order.templateVersionId() : command.requestedTemplateVersionId();
        orders.findPublishedTemplate(actor.organizationId(), requested)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "候选模板不存在或未发布"));

        var jobId = UUID.randomUUID();
        jobs.insert(new InstanceIngestRepository.NewJob(
                jobId, actor.organizationId(), orderId, requested, sourceType,
                List.copyOf(command.sourceFileIds()), actor.userId()));
        jobs.markProcessing(jobId);
        try {
            if ("XLSX".equals(sourceType)) processXlsx(jobId, requested, command.sourceFileIds().getFirst());
            else processPhoto(jobId, requested, command.sourceFileIds());
        } catch (RuntimeException exception) {
            jobs.markFailed(jobId, safeMessage(exception));
        }
        return get(orderId, jobId);
    }

    public InstanceIngestRepository.Job get(UUID orderId, UUID jobId) {
        return jobs.find(ActorContext.required().organizationId(), orderId, jobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "实例导入任务不存在"));
    }

    @Transactional
    public ConfirmResult confirm(UUID orderId, UUID jobId, ConfirmCommand command) {
        requireEnabled();
        var actor = ActorContext.required();
        var job = get(orderId, jobId);
        if (!"REVIEW_REQUIRED".equals(job.status()) || job.resultVersion() != command.resultVersion()) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "导入结果已变化，请刷新后重试");
        }
        var selectedId = command.selectedTemplateVersionId() == null
                ? job.selectedTemplateVersionId() : command.selectedTemplateVersionId();
        if (!selectedId.equals(job.selectedTemplateVersionId())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "请使用所选模板重新创建导入任务后再确认");
        }
        var template = orders.findPublishedTemplate(actor.organizationId(), selectedId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "已发布模板不存在"));
        var result = job.result();
        var data = command.correctedData() == null ? result.path("data") : command.correctedData();
        if (!data.isObject()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "确认数据必须是 JSON 对象");
        var mapping = result.path("mapping");
        var snapshot = result.path("snapshot");
        if (!mapping.isArray() || !snapshot.isObject()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "导入结果缺少 Mapping 或工作簿快照");
        }
        var content = bytes(snapshot);
        var staged = stageSnapshot(jobId, content);
        var saved = orderService.applyIngestResult(
                orderId, command.lockVersion(), command.baseWorkspaceHash(), template,
                mapping, data, staged.fileId(), staged.sha256());
        if (jobs.confirm(actor.organizationId(), orderId, jobId, command.resultVersion(), data) == 0) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }
        return new ConfirmResult(saved.lockVersion(), saved.workspaceHash(), saved.reconciliationRequired());
    }

    @Transactional
    public void cancel(UUID orderId, UUID jobId) {
        if (jobs.cancel(ActorContext.required().organizationId(), orderId, jobId) == 0) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "当前导入任务不能取消");
        }
    }

    private void processXlsx(UUID jobId, UUID requestedTemplateId, UUID sourceFileId) {
        var actor = ActorContext.required();
        var content = read(actor.organizationId(), sourceFileId);
        var parsed = xlsxParser.parseInstance(new ByteArrayInputStream(content));
        var manifest = InstanceWorkbookManifest.read(content);
        var ranked = rankTemplates(actor.organizationId(), parsed.snapshot());
        var requestedTemplate = orders.findPublishedTemplate(actor.organizationId(), requestedTemplateId).orElseThrow();
        var exact = manifest != null
                && requestedTemplateId.equals(manifest.templateVersionId())
                && canonicalizer.hash(requestedTemplate.schema()).equals(manifest.schemaHash())
                && canonicalizer.hash(requestedTemplate.mapping()).equals(manifest.mappingHash());
        var top = ranked.stream().filter(item -> item.versionId().equals(requestedTemplateId))
                .findFirst().orElse(new Match(requestedTemplateId, "", "", 0d));
        var requestedIsTop = !ranked.isEmpty() && requestedTemplateId.equals(ranked.getFirst().versionId());
        var matchMode = exact ? "EXACT_MANIFEST"
                : requestedIsTop && top.score() >= .90d && margin(ranked) >= .10d
                        ? "SIMILAR_AUTO" : "USER_REVIEW";
        var mapping = exact ? relocateByDefinedNames(requestedTemplate.mapping(), manifest, parsed)
                : requestedTemplate.mapping().deepCopy();
        var extraction = extractor.extract(requestedTemplate.schema(), mapping, parsed.snapshot());

        var result = objectMapper.createObjectNode();
        result.put("sourceType", "XLSX");
        result.put("matchMode", matchMode);
        result.put("templateMatchScore", exact ? 1d : top.score());
        result.put("requiresTemplateSelection", !exact && !"SIMILAR_AUTO".equals(matchMode));
        result.set("templateCandidates", candidateJson(ranked));
        result.set("data", extraction.data());
        result.set("mapping", mapping);
        result.set("snapshot", parsed.snapshot());
        result.set("issues", objectMapper.valueToTree(parsed.issues()));
        jobs.saveResult(jobId, requestedTemplateId, matchMode, exact ? 1d : top.score(), result,
                extraction.items().stream().map(this::newItem).toList());
    }

    private void processPhoto(UUID jobId, UUID templateVersionId, List<UUID> sourceFileIds) {
        if (!photoEnabled) throw new ApiException(ApiErrorCode.BAD_REQUEST, "生产实例照片识别功能尚未启用");
        if (!documentRecognitionClient.isConfigured()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "实例照片识别模型尚未配置");
        }
        var actor = ActorContext.required();
        var template = orders.findPublishedTemplate(actor.organizationId(), templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "已发布模板不存在"));
        var images = sourceFileIds.stream().map(fileId -> {
            var file = files.find(actor.organizationId(), fileId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "文件不存在"));
            var processed = imagePreprocessor.process(read(actor.organizationId(), fileId), file.contentType());
            return new InstanceDocumentRecognitionClient.ImageSource(
                    processed.contentType(), processed.content());
        }).toList();
        var recognized = documentRecognitionClient.recognize(template.schema(), template.mapping(), images);
        var templateSnapshot = readJson(actor.organizationId(), template.snapshotFileId());
        var filledSnapshot = snapshotWriter.write(templateSnapshot, template.mapping(), recognized.data());
        var result = objectMapper.createObjectNode();
        result.put("sourceType", "PHOTO");
        result.put("matchMode", "USER_SELECTED_TEMPLATE");
        result.put("templateMatchScore", 1d);
        result.put("requiresTemplateSelection", false);
        result.put("model", recognized.model());
        result.set("data", recognized.data());
        result.set("mapping", template.mapping().deepCopy());
        result.set("snapshot", filledSnapshot);
        var items = recognized.items().stream().map(item -> new InstanceIngestRepository.NewItem(
                UUID.randomUUID(), item.itemKey(), item.itemKind(), item.bindingId(),
                item.fieldCode(), item.dataPath(), item.recordKey(), item.recordIndex(),
                item.rawValue(), item.normalizedValue(), item.sourceLocator(), item.confidence(),
                item.handwritten() || item.confidence() < .90d ? "NEEDS_REVIEW" : "EXTRACTED"
        )).toList();
        jobs.saveResult(jobId, templateVersionId, "INSTANCE_VALUE_EXTRACTION", 1d, result, items);
    }

    private List<Match> rankTemplates(UUID organizationId, JsonNode uploadedSnapshot) {
        var result = new ArrayList<Match>();
        for (var candidate : orders.listPublishedTemplates(organizationId)) {
            var template = orders.findPublishedTemplate(organizationId, candidate.versionId()).orElse(null);
            if (template == null || !"XLSX".equals(template.format())) continue;
            var snapshot = readJson(organizationId, template.snapshotFileId());
            result.add(new Match(candidate.versionId(), candidate.templateCode(), candidate.name(),
                    similarity(snapshot, uploadedSnapshot)));
        }
        result.sort(Comparator.comparingDouble(Match::score).reversed());
        return result.stream().limit(3).toList();
    }

    private double similarity(JsonNode expected, JsonNode actual) {
        var expectedSheets = sheetNames(expected);
        var actualSheets = sheetNames(actual);
        var sheetScore = jaccard(expectedSheets, actualSheets);
        var labelScore = jaccard(labels(expected), labels(actual));
        return Math.round((sheetScore * .35d + labelScore * .65d) * 10000d) / 10000d;
    }

    private Set<String> sheetNames(JsonNode snapshot) {
        var result = new HashSet<String>();
        snapshot.path("sheets").forEach(sheet -> {
            var name = sheet.path("name").asText("").trim().toLowerCase(java.util.Locale.ROOT);
            if (!name.isBlank() && !InstanceWorkbookManifest.SHEET_NAME.equalsIgnoreCase(name)) result.add(name);
        });
        return result;
    }

    private Set<String> labels(JsonNode snapshot) {
        var result = new HashSet<String>();
        snapshot.path("sheets").forEach(sheet -> sheet.path("cellData").forEach(row -> row.forEach(cell -> {
            var value = cell.path("v");
            if (value.isTextual()) {
                var label = value.asText().trim().toLowerCase(java.util.Locale.ROOT);
                if (label.length() >= 2 && label.length() <= 100) result.add(label);
            }
        })));
        return result;
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) return 1d;
        var intersection = new HashSet<>(left);
        intersection.retainAll(right);
        var union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0d : (double) intersection.size() / union.size();
    }

    private double margin(List<Match> matches) {
        if (matches.isEmpty()) return 0d;
        return matches.getFirst().score() - (matches.size() > 1 ? matches.get(1).score() : 0d);
    }

    private JsonNode relocateByDefinedNames(
            JsonNode sourceMapping,
            InstanceWorkbookManifest manifest,
            WorkbookInstanceParser.ParsedWorkbook parsed
    ) {
        var mapping = (ArrayNode) sourceMapping.deepCopy();
        var formulas = new HashMap<String, String>();
        for (var name : parsed.structureSummary().path("namedRanges")) {
            formulas.put(name.path("name").asText(), name.path("formula").asText());
        }
        var sheetIds = new HashMap<String, String>();
        parsed.snapshot().path("sheets").fields().forEachRemaining(entry ->
                sheetIds.put(entry.getValue().path("name").asText(), entry.getKey()));
        for (var binding : mapping) {
            var definedName = manifest.bindingNames().get(binding.path("bindingId").asText());
            var reference = formulas.get(definedName);
            if (reference == null) continue;
            var relocated = parseReference(reference);
            if (relocated == null) continue;
            var locator = (ObjectNode) binding.withObject("locator");
            locator.put("sheetName", relocated.sheetName());
            locator.put("sheetId", sheetIds.getOrDefault(relocated.sheetName(), locator.path("sheetId").asText()));
            locator.put("address", relocated.address());
            locator.put("valueRange", relocated.address());
            locator.put("logicalInputRange", relocated.address());
        }
        return mapping;
    }

    private Reference parseReference(String formula) {
        var match = java.util.regex.Pattern.compile("^'?((?:[^']|'')+)'?!([A-Z$0-9:]+)$",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(formula);
        if (!match.matches()) return null;
        return new Reference(match.group(1).replace("''", "'"), match.group(2).replace("$", ""));
    }

    private ArrayNode candidateJson(List<Match> matches) {
        var result = objectMapper.createArrayNode();
        matches.forEach(match -> result.add(objectMapper.createObjectNode()
                .put("templateVersionId", match.versionId().toString())
                .put("templateCode", match.code()).put("templateName", match.name())
                .put("score", match.score())));
        return result;
    }

    private InstanceIngestRepository.NewItem newItem(WorkbookInstanceExtractor.ExtractedItem item) {
        return new InstanceIngestRepository.NewItem(
                UUID.randomUUID(), item.itemKey(), item.itemKind(), item.bindingId(),
                item.fieldCode(), item.dataPath(), item.recordKey(), item.recordIndex(),
                item.value(), item.value(), item.sourceLocator(), item.confidence(), item.reviewStatus());
    }

    private void requireSource(UUID organizationId, UUID id, String sourceType) {
        var file = files.find(organizationId, id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "来源文件不存在"));
        if ("DELETED".equals(file.status())) throw new ApiException(ApiErrorCode.NOT_FOUND, "来源文件已删除");
        if ("XLSX".equals(sourceType) && !file.originalName().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "Excel 实例导入只支持 .xlsx");
        }
        if ("PHOTO".equals(sourceType) && (file.contentType() == null
                || !file.contentType().toLowerCase(java.util.Locale.ROOT).startsWith("image/"))) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "照片实例导入只接受图片文件");
        }
    }

    private byte[] read(UUID organizationId, UUID fileId) {
        var file = files.find(organizationId, fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "文件不存在"));
        try (var stored = storage.get(file.objectKey())) {
            return stored.stream().readAllBytes();
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "来源文件读取失败");
        }
    }

    private JsonNode readJson(UUID organizationId, UUID fileId) {
        try {
            return objectMapper.readTree(read(organizationId, fileId));
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "模板快照读取失败");
        }
    }

    private byte[] bytes(JsonNode value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED);
        }
    }

    private StagedSnapshot stageSnapshot(UUID jobId, byte[] content) {
        var actor = ActorContext.required();
        try {
            var id = UUID.randomUUID();
            var name = "production-ingest-" + jobId + ".json";
            var sha256 = java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
            var objectKey = actor.organizationId() + "/staged/" + id + "/" + name;
            storage.put(objectKey, new ByteArrayInputStream(content), content.length, "application/json");
            files.insert(new FileObjectRepository.NewFileObject(
                    id, actor.organizationId(), storageBucket, objectKey, name,
                    "application/json", content.length, sha256, actor.userId()));
            return new StagedSnapshot(id, sha256);
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "导入工作簿快照暂存失败");
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new ApiException(ApiErrorCode.BAD_REQUEST, "生产实例导入功能尚未启用");
    }

    private String safeMessage(Throwable error) {
        var message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName()
                : message.substring(0, Math.min(2000, message.length()));
    }

    public record CreateCommand(String sourceType, List<UUID> sourceFileIds, UUID requestedTemplateVersionId) {
    }

    public record ConfirmCommand(
            String baseWorkspaceHash,
            long lockVersion,
            int resultVersion,
            UUID selectedTemplateVersionId,
            JsonNode correctedData
    ) {
    }

    public record ConfirmResult(long lockVersion, String workspaceHash, boolean reconciliationRequired) {
    }

    private record Match(UUID versionId, String code, String name, double score) {
    }

    private record Reference(String sheetName, String address) {
    }

    private record StagedSnapshot(UUID fileId, String sha256) {
    }
}
