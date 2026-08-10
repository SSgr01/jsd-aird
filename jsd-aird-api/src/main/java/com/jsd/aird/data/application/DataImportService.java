package com.jsd.aird.data.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataImportService {

    private static final Set<String> SUPPORTED_TARGET_DATA_TYPES = Set.of(
            "MATERIAL", "FORMULA", "PROCESS", "EQUIPMENT", "TEST_STANDARD");

    private final DataRepository repository;
    private final FileObjectRepository files;
    private final TemplateDataImportFacade templates;
    private final ObjectMapper objectMapper;
    private final AuditLogFacade auditLog;
    private final OpsAsyncFacade opsAsync;
    private final DataCategoryService categories;

    @Autowired
    public DataImportService(
            DataRepository repository,
            FileObjectRepository files,
            TemplateDataImportFacade templates,
            ObjectMapper objectMapper,
            AuditLogFacade auditLog,
            OpsAsyncFacade opsAsync,
            DataCategoryService categories
    ) {
        this.repository = repository;
        this.files = files;
        this.templates = templates;
        this.objectMapper = objectMapper;
        this.auditLog = auditLog;
        this.opsAsync = opsAsync;
        this.categories = categories;
    }

    public DataImportService(
            DataRepository repository,
            FileObjectRepository files,
            TemplateDataImportFacade templates,
            ObjectMapper objectMapper,
            AuditLogFacade auditLog,
            OpsAsyncFacade opsAsync
    ) {
        this(repository, files, templates, objectMapper, auditLog, opsAsync, null);
    }

    @Transactional
    public DataRepository.Job create(CreateCommand command) {
        var actor = ActorContext.required();
        var targetDataType = normalizeTargetDataType(command.targetDataType());
        var definition = templates.getPublished(actor.organizationId(), command.templateVersionId());
        if (!definition.targetDataType().equals(targetDataType)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "所选模板与数据类型不匹配");
        }
        var file = files.find(actor.organizationId(), command.sourceFileId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY, "数据源文件不存在"));
        var format = sourceFormat(file.originalName());
        var duplicate = repository.findCompletedDuplicate(actor.organizationId(), file.sha256(), command.templateVersionId());
        if (duplicate.isPresent() && !command.duplicateOverride()) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                    "该文件已按此模板完成导入，历史任务：" + duplicate.get().id());
        }
        files.activate(command.sourceFileId());
        var categoryId = command.categoryId();
        if (categoryId == null && categories != null) {
            var defaultCategory = categories.defaultForTargetType(actor.organizationId(), targetDataType);
            categoryId = defaultCategory == null ? null : defaultCategory.id();
        } else if (categoryId != null && categories != null) {
            var requestedCategoryId = categoryId;
            var category = categories.list().stream().filter(item -> item.id().equals(requestedCategoryId)).findFirst()
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据分类不存在"));
            if (category.targetDataType() != null && !targetDataType.equals(category.targetDataType())) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "数据分类与数据类型不匹配");
            }
        }
        var id = UUID.randomUUID();
        repository.insertJob(new DataRepository.NewJob(
                id, actor.organizationId(), command.sourceFileId(), file.sha256(), file.originalName(), format,
                command.templateVersionId(), targetDataType, categoryId, command.duplicateOverride(), actor.userId()));
        repository.enqueueParse(UUID.randomUUID(), actor.organizationId(), id);
        return get(id);
    }

    public DataRepository.Job get(UUID importJobId) {
        return repository.findJob(ActorContext.required().organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
    }

    public PageResponse<DataRepository.Job> listJobs(String targetDataType, UUID templateVersionId,
                                                      String status, String keyword, int page, int size) {
        var actor = ActorContext.required();
        var safePage = Math.max(1, page);
        var safeSize = Math.min(100, Math.max(1, size));
        return repository.listJobs(actor.organizationId(), targetDataType, templateVersionId, status,
                keyword, safePage, safeSize);
    }

    public List<TemplateDataImportFacade.DataTemplateOption> listTemplates(String targetDataType) {
        return templates.listPublished(ActorContext.required().organizationId(), targetDataType);
    }

    public TemplateDataImportFacade.FieldRequest requestField(UUID importJobId, FieldRequestCommand command) {
        var actor = ActorContext.required();
        var job = requireJob(actor.organizationId(), importJobId);
        return templates.requestField(actor.organizationId(), job.templateVersionId(), new TemplateDataImportFacade.FieldRequestCommand(
                command.fieldId(), command.displayName(), command.valueType(), command.uiType(), command.groupCode(), command.description()));
    }

    public void parse(UUID importJobId) {
        var actor = ActorContext.required();
        parseInternal(actor.organizationId(), importJobId);
    }

    public void parseInternal(UUID organizationId, UUID importJobId) {
        var job = repository.findJob(organizationId, importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
        if (List.of("WAITING_MAPPING", "VALIDATING", "WAITING_CONFIRM", "COMPLETED").contains(job.status())) return;
        repository.updateJobStatus(organizationId, importJobId, "PARSING", 10, "PARSING", null);
        try {
            var parsed = templates.parse(organizationId, job.templateVersionId(), job.sourceFileId());
            var definition = templates.getPublished(organizationId, job.templateVersionId());
            var sheets = new ArrayList<DataRepository.Sheet>();
            var mappings = new ArrayList<DataRepository.Mapping>();
            var rows = new ArrayList<DataRepository.Row>();
            for (var parsedSheet : parsed.sheets()) {
                var structure = objectMapper.createObjectNode()
                        .put("sheetId", parsedSheet.sheetId())
                        .put("sheetName", parsedSheet.sheetName())
                        .set("rows", objectMapper.valueToTree(parsedSheet.rows()));
                sheets.add(new DataRepository.Sheet(
                        UUID.randomUUID(), parsedSheet.sheetId(), parsedSheet.sheetName(), parsedSheet.sheetOrder(), true,
                        List.of(parsedSheet.suggestedHeaderRow()), parsedSheet.suggestedDataStartRow(), parsedSheet.lastRow(),
                        structure, "PENDING"));
                var header = rowAt(parsedSheet.rows(), parsedSheet.suggestedHeaderRow());
                for (int column = 0; column < header.size(); column++) {
                    var sourceColumn = columnName(column + 1);
                    var sourceHeader = header.get(column);
                    var field = matchField(definition.fields(), sourceHeader);
                    var detail = objectMapper.createObjectNode();
                    if (field != null) detail.put("identity", field.identity()).put("required", field.required());
                    mappings.add(new DataRepository.Mapping(
                            UUID.randomUUID(), parsedSheet.sheetId(), sourceColumn, sourceHeader,
                            field == null ? null : field.fieldCode(), field == null ? null : field.displayName(),
                            field == null ? "PENDING" : "MAP", field == null ? null : field.dataType(),
                            null, field == null ? null : field.defaultUnit(), detail,
                            field == null ? "PENDING" : "MATCHED"));
                }
                for (int rowIndex = Math.max(parsedSheet.suggestedDataStartRow(), 1); rowIndex <= parsedSheet.lastRow(); rowIndex++) {
                    var values = rowAt(parsedSheet.rows(), rowIndex);
                    if (values.stream().allMatch(String::isBlank)) continue;
                    var raw = objectMapper.createObjectNode();
                    for (int column = 0; column < values.size(); column++) raw.put(columnName(column + 1), values.get(column));
                    rows.add(new DataRepository.Row(UUID.randomUUID(), parsedSheet.sheetId(), rowIndex, raw, raw.deepCopy(),
                            raw.deepCopy(), "STAGED"));
                }
            }
            repository.saveParsed(importJobId, parsed.parserVersion(), sheets, mappings, rows, importJobId);
        } catch (RuntimeException exception) {
            repository.updateJobStatus(organizationId, importJobId, "FAILED", 0, "FAILED", safeMessage(exception));
            throw exception;
        }
    }

    @Transactional
    public void confirmSheets(UUID importJobId, List<DataRepository.SheetUpdate> updates) {
        var actor = ActorContext.required();
        requireJob(actor.organizationId(), importJobId);
        for (var update : updates) repository.updateSheet(update);
        repository.updateJobStatus(actor.organizationId(), importJobId, "WAITING_MAPPING", 45, "WAITING_MAPPING", null);
    }

    @Transactional
    public void saveMappings(UUID importJobId, List<MappingCommand> commands) {
        var actor = ActorContext.required();
        requireJob(actor.organizationId(), importJobId);
        repository.replaceMappings(actor.organizationId(), importJobId, commands.stream().map(item -> new DataRepository.Mapping(
                null, item.sheetId(), item.sourceColumn(), item.sourceHeader(), item.fieldCode(), item.fieldName(),
                item.action(), item.valueType(), item.sourceUnit(), item.standardUnit(),
                item.detail() == null ? objectMapper.createObjectNode() : item.detail(), mappingStatus(item.action())
        )).toList());
        validate(importJobId);
    }

    @Transactional
    public void validate(UUID importJobId) {
        var actor = ActorContext.required();
        validateInternal(actor.organizationId(), importJobId);
    }

    public void validateInternal(UUID organizationId, UUID importJobId) {
        var job = requireJob(organizationId, importJobId);
        var mappings = repository.listMappings(organizationId, importJobId);
        var rows = repository.listRows(organizationId, importJobId);
        var issues = new ArrayList<DataRepository.Issue>();
        var mappingByColumn = new HashMap<String, DataRepository.Mapping>();
        for (var mapping : mappings) mappingByColumn.put(mapping.sheetId() + "|" + mapping.sourceColumn(), mapping);
        var normalizedRows = new ArrayList<DataRepository.Row>();
        for (var row : rows) {
            var normalized = objectMapper.createObjectNode();
            var rowBlocked = false;
            var entries = row.rawValues().fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                var mapping = mappingByColumn.get(row.sheetId() + "|" + entry.getKey());
                if (mapping == null || "IGNORE".equals(mapping.action())) continue;
                if (!"MAP".equals(mapping.action()) || mapping.fieldCode() == null || mapping.fieldCode().isBlank()) {
                    issues.add(issue(row, mapping, "BLOCKER", "UNMAPPED_FIELD", "字段尚未完成映射"));
                    rowBlocked = true;
                    continue;
                }
                var raw = entry.getValue().asText();
                var normalizedValue = normalize(raw, mapping, row, issues);
                var value = objectMapper.createObjectNode().put("rawValue", raw)
                        .put("rawUnit", mapping.sourceUnit() == null ? "" : mapping.sourceUnit())
                        .put("normalizedValue", normalizedValue)
                        .put("normalizedUnit", mapping.standardUnit() == null ? "" : mapping.standardUnit());
                normalized.set(mapping.fieldCode(), value);
            }
            for (var mapping : mappings) {
                if (!row.sheetId().equals(mapping.sheetId()) || !"MAP".equals(mapping.action())
                        || mapping.fieldCode() == null || !mapping.detail().path("required").asBoolean(false)) continue;
                if (normalized.path(mapping.fieldCode()).path("normalizedValue").asText("").isBlank()) {
                    issues.add(issue(row, mapping, "BLOCKER", "REQUIRED_MISSING", "必填字段为空"));
                    rowBlocked = true;
                }
            }
            normalizedRows.add(new DataRepository.Row(row.id(), row.sheetId(), row.rowNumber(), row.rawValues(), normalized,
                    normalized.deepCopy(), rowBlocked ? "BLOCKED" : "VALID"));
        }
        var blocker = issues.stream().anyMatch(item -> "BLOCKER".equals(item.severity()));
        repository.replaceValidation(organizationId, importJobId, normalizedRows, issues,
                blocker ? "WAITING_MAPPING" : "WAITING_CONFIRM");
    }

    public Preview preview(UUID importJobId) {
        var actor = ActorContext.required();
        var job = requireJob(actor.organizationId(), importJobId);
        return new Preview(job, repository.listSheets(actor.organizationId(), importJobId),
                repository.listMappings(actor.organizationId(), importJobId), repository.listRows(actor.organizationId(), importJobId),
                repository.listIssues(actor.organizationId(), importJobId));
    }

    public List<DataRepository.Issue> issues(UUID importJobId) {
        var actor = ActorContext.required();
        requireJob(actor.organizationId(), importJobId);
        return repository.listIssues(actor.organizationId(), importJobId);
    }

    @Transactional
    public void resolveIssue(UUID issueId, String status) {
        var actor = ActorContext.required();
        repository.resolveIssue(actor.organizationId(), issueId, actor.userId(), status);
    }

    @Transactional
    public void commit(UUID importJobId) {
        var actor = ActorContext.required();
        var job = repository.findJobForUpdate(actor.organizationId(), importJobId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
        if ("COMPLETED".equals(job.status())) return;
        if (!Set.of("WAITING_CONFIRM", "COMMITTING").contains(job.status())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "导入任务当前不可提交：" + job.status());
        }
        var blockers = repository.listIssues(actor.organizationId(), importJobId).stream()
                .anyMatch(item -> "BLOCKER".equals(item.severity()) && !List.of("RESOLVED", "IGNORED").contains(item.status()));
        if (blockers) throw new ApiException(ApiErrorCode.BAD_REQUEST, "仍有阻断异常未处理");
        repository.updateJobStatus(actor.organizationId(), importJobId, "COMMITTING", 90, "COMMITTING", null);
        var rows = repository.listRows(actor.organizationId(), importJobId);
        var mappings = repository.listMappings(actor.organizationId(), importJobId);
        var sheetNames = repository.listSheets(actor.organizationId(), importJobId).stream()
                .collect(java.util.stream.Collectors.toMap(DataRepository.Sheet::sheetId, DataRepository.Sheet::sheetName));
        var committed = rows.stream().filter(row -> !"BLOCKED".equals(row.status())).map(row -> {
            var identity = firstValue(row.normalizedValues(), mappings, row, true);
            var display = firstValue(row.normalizedValues(), mappings, row, false);
            var anchors = mappings.stream().filter(item -> "MAP".equals(item.action()) && item.fieldCode() != null)
                    .map(item -> anchor(item, row, sheetNames.getOrDefault(item.sheetId(), item.sheetId()))).toList();
            return new DataRepository.CommittedRow(row.sheetId(), row.rowNumber(), row.rawValues(), row.normalizedValues(),
                    row.correctedValues(), identity == null || identity.isBlank()
                            ? job.targetDataType() + ":" + row.sheetId() + ":" + row.rowNumber() : identity,
                    display == null || display.isBlank() ? identity : display, anchors);
        }).toList();
        var result = repository.commit(actor.organizationId(), importJobId, actor.userId(), committed);
        appendCommitAuditAndOutbox(actor.organizationId(), actor.userId(), job, result);
    }

    private void appendCommitAuditAndOutbox(UUID organizationId, UUID actorId, DataRepository.Job job,
                                            DataRepository.CommitResult result) {
        var assets = objectMapper.createArrayNode();
        for (var item : result.assets()) {
            assets.add(objectMapper.createObjectNode()
                    .put("assetId", item.assetId().toString())
                    .put("revisionId", item.revisionId().toString())
                    .put("revisionNo", item.revisionNo())
                    .put("dataHash", item.dataHash()));
        }
        var detail = objectMapper.createObjectNode()
                .put("targetDataType", job.targetDataType())
                .put("templateVersionId", job.templateVersionId().toString())
                .put("sourceFileId", job.sourceFileId().toString())
                .put("sourceFileName", job.sourceFileName())
                .put("sourceSha256", job.sourceSha256())
                .put("assetCount", result.assets().stream().map(DataRepository.CommittedAsset::assetId).distinct().count())
                .put("rowCount", result.rowCount())
                .set("assets", assets);
        auditLog.append(organizationId, actorId, "DATA_IMPORT_COMMITTED", "DATA_IMPORT_JOB", job.id(), detail);

        var eventPayload = objectMapper.createObjectNode()
                .put("organizationId", organizationId.toString())
                .put("targetDataType", job.targetDataType())
                .put("templateVersionId", job.templateVersionId().toString())
                .put("importJobId", job.id().toString())
                .put("sourceSha256", job.sourceSha256())
                .put("assetCount", result.assets().stream().map(DataRepository.CommittedAsset::assetId).distinct().count())
                .put("rowCount", result.rowCount())
                .set("assets", assets.deepCopy());
        opsAsync.appendOutbox("DATA_IMPORT_JOB", job.id(), "DATA_ASSETS_COMMITTED", eventPayload);
        opsAsync.enqueue(organizationId, "AI_INDEX_DATA_ASSETS", eventPayload,
                "ai-data-index:" + job.id() + ":" + result.assets().stream().map(DataRepository.CommittedAsset::revisionId)
                        .map(UUID::toString).sorted().collect(java.util.stream.Collectors.joining(",")), 30);
    }

    public PageResponse<DataRepository.Asset> assets(String targetDataType, UUID categoryId, String status, String keyword,
                                                     int page, int size) {
        var actor = ActorContext.required();
        var safePage = Math.max(1, page);
        var safeSize = Math.min(100, Math.max(1, size));
        return repository.listAssets(actor.organizationId(), targetDataType, categoryId, status,
                targetDataType == null && keyword == null ? null : keyword, safePage, safeSize);
    }

    public DataRepository.AssetDetail asset(UUID id) {
        return repository.findAsset(ActorContext.required().organizationId(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据资产不存在"));
    }

    public List<DataRepository.Revision> revisions(UUID id) {
        return repository.listRevisions(ActorContext.required().organizationId(), id);
    }

    public List<DataRepository.SourceAnchor> sources(UUID id) {
        return repository.listSourceAnchors(ActorContext.required().organizationId(), id);
    }

    private DataRepository.Job requireJob(UUID organizationId, UUID id) {
        return repository.findJob(organizationId, id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
    }


    private DataRepository.Issue issue(DataRepository.Row row, DataRepository.Mapping mapping, String severity,
                                       String type, String message) {
        return new DataRepository.Issue(null, row.sheetId(), mapping == null ? null : mapping.fieldCode(), severity, type,
                row.rowNumber(), mapping == null ? null : mapping.sourceColumn(), mapping == null ? null : mapping.sourceColumn() + row.rowNumber(),
                message, objectMapper.createObjectNode(), "OPEN");
    }

    private String normalize(String raw, DataRepository.Mapping mapping, DataRepository.Row row,
                             List<DataRepository.Issue> issues) {
        if (raw == null || raw.isBlank()) return "";
        var type = mapping.valueType() == null ? "TEXT" : mapping.valueType().toUpperCase(Locale.ROOT);
        BigDecimal numeric = null;
        if (type.contains("NUM") || type.contains("DECIMAL")) {
            try { numeric = new BigDecimal(raw.trim().replace(",", "")); }
            catch (NumberFormatException exception) {
                issues.add(issue(row, mapping, "BLOCKER", "TYPE_ERROR", "数值字段无法解析：" + raw));
            }
        }
        if (mapping.sourceUnit() != null && mapping.standardUnit() != null
                && !mapping.sourceUnit().isBlank() && !mapping.standardUnit().isBlank()
                && !mapping.sourceUnit().equalsIgnoreCase(mapping.standardUnit())) {
            var source = mapping.sourceUnit().toLowerCase(Locale.ROOT);
            var target = mapping.standardUnit().toLowerCase(Locale.ROOT);
            var factor = conversionFactor(source, target);
            if (factor == null || numeric == null) {
                issues.add(issue(row, mapping, "BLOCKER", "UNIT_INCOMPATIBLE", "单位维度不兼容或缺少数值转换"));
            } else numeric = numeric.multiply(factor);
        }
        return numeric == null ? raw.trim() : numeric.stripTrailingZeros().toPlainString();
    }

    private BigDecimal conversionFactor(String source, String target) {
        if (source.equals(target)) return BigDecimal.ONE;
        return switch (source + "->" + target) {
            case "cps->pa·s", "cp->pa·s" -> new BigDecimal("0.001");
            case "pa·s->cps", "pa·s->cp" -> new BigDecimal("1000");
            case "cps->cp", "cp->cps" -> BigDecimal.ONE;
            case "mg->g", "g->kg", "mm->m" -> new BigDecimal("0.001");
            case "g->mg", "kg->g", "m->mm" -> new BigDecimal("1000");
            case "kg->mg" -> new BigDecimal("1000000");
            case "mg->kg" -> new BigDecimal("0.000001");
            case "cm->m" -> new BigDecimal("0.01");
            case "m->cm" -> new BigDecimal("100");
            default -> null;
        };
    }

    private String firstValue(JsonNode normalized, List<DataRepository.Mapping> mappings,
                              DataRepository.Row row, boolean identity) {
        for (var mapping : mappings) {
            if (!"MAP".equals(mapping.action()) || mapping.fieldCode() == null) continue;
            if (identity && mapping.detail().path("identity").asBoolean(false)
                    || !identity && mapping.fieldName() != null && mapping.fieldName().contains("名称")) {
                var value = normalized.path(mapping.fieldCode()).path("normalizedValue").asText("");
                if (!value.isBlank()) return value;
            }
        }
        return null;
    }

    private DataRepository.Anchor anchor(DataRepository.Mapping mapping, DataRepository.Row row, String sheetName) {
        int column = columnNumber(mapping.sourceColumn());
        return new DataRepository.Anchor(mapping.fieldCode(), mapping.sheetId(), sheetName, row.rowNumber(), column,
                mapping.sourceColumn(), mapping.sourceColumn() + row.rowNumber(), row.rawValues().path(mapping.sourceColumn()));
    }

    private TemplateDataImportFacade.FieldDefinition matchField(List<TemplateDataImportFacade.FieldDefinition> fields, String header) {
        var key = normalizeHeader(header);
        return fields.stream().filter(field -> normalizeHeader(field.fieldCode()).equals(key)
                || normalizeHeader(field.displayName()).equals(key)
                || field.aliases().stream().map(this::normalizeHeader).anyMatch(key::equals)).findFirst().orElse(null);
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-（）()：:]+", "");
    }

    private List<String> rowAt(List<List<String>> rows, int oneBased) {
        return oneBased > 0 && oneBased <= rows.size() ? rows.get(oneBased - 1) : List.of();
    }

    private String mappingStatus(String action) {
        return switch (action == null ? "PENDING" : action) {
            case "MAP" -> "CONFIRMED";
            case "IGNORE" -> "IGNORED";
            case "REQUEST_FIELD" -> "REQUESTED";
            default -> "PENDING";
        };
    }

    private String sourceFormat(String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx")) return "XLSX";
        if (lower.endsWith(".xls")) return "XLS";
        if (lower.endsWith(".csv")) return "CSV";
        throw new ApiException(ApiErrorCode.BAD_REQUEST, "数据中心仅支持 XLS、XLSX 或 CSV");
    }

    private String normalizeTargetDataType(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TARGET_DATA_TYPES.contains(normalized)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的数据类型：" + value);
        }
        return normalized;
    }

    private String columnName(int value) {
        var result = new StringBuilder();
        while (value > 0) { value--; result.insert(0, (char) ('A' + value % 26)); value /= 26; }
        return result.toString();
    }

    private int columnNumber(String value) {
        int result = 0;
        for (int i = 0; i < value.length(); i++) result = result * 26 + Character.toUpperCase(value.charAt(i)) - 'A' + 1;
        return result;
    }

    private String safeMessage(Throwable error) {
        var value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value.substring(0, Math.min(2000, value.length()));
    }

    public record CreateCommand(UUID sourceFileId, UUID templateVersionId, String targetDataType,
                                UUID categoryId, boolean duplicateOverride) {
        public CreateCommand(UUID sourceFileId, UUID templateVersionId, String targetDataType, boolean duplicateOverride) {
            this(sourceFileId, templateVersionId, targetDataType, null, duplicateOverride);
        }
    }
    public record FieldRequestCommand(String fieldId, String displayName, String valueType,
                                      String uiType, String groupCode, String description) {}
    public record MappingCommand(String sheetId, String sourceColumn, String sourceHeader, String fieldCode, String fieldName,
                                  String action, String valueType, String sourceUnit, String standardUnit, JsonNode detail) {}
    public record Preview(DataRepository.Job job, List<DataRepository.Sheet> sheets, List<DataRepository.Mapping> mappings,
                          List<DataRepository.Row> rows, List<DataRepository.Issue> issues) {}
}
