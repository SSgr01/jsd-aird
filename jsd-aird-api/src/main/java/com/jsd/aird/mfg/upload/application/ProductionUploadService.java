package com.jsd.aird.mfg.upload.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.jsd.aird.mfg.upload.application.port.ProductionUploadRepository;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProductionUploadService {

    private final ProductionUploadRepository repository;
    private final FileObjectRepository files;
    private final OpsAsyncFacade async;
    private final ObjectMapper objectMapper;
    private final ProductionOrderRepository orders;
    private final ProductionUploadWorkbookBuilder workbookBuilder;
    private final ProductionUploadRecognitionReviewAssembler reviewAssembler;

    public ProductionUploadService(ProductionUploadRepository repository, FileObjectRepository files,
                                   OpsAsyncFacade async, ObjectMapper objectMapper,
                                   ProductionOrderRepository orders,
                                   ProductionUploadWorkbookBuilder workbookBuilder,
                                   ProductionUploadRecognitionReviewAssembler reviewAssembler) {
        this.repository = repository;
        this.files = files;
        this.async = async;
        this.objectMapper = objectMapper;
        this.orders = orders;
        this.workbookBuilder = workbookBuilder;
        this.reviewAssembler = reviewAssembler;
    }

    @Transactional
    public ProductionUploadRepository.UploadView create(CreateCommand command) {
        var actor = ActorContext.required();
        if (!Set.of("ALL", "QUALITY", "PROJECT").contains(command.visibility())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "权限类型不合法");
        }
        var file = files.find(actor.organizationId(), command.fileId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "上传文件不存在"));
        if ("DELETED".equals(file.status())) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "上传文件已删除");
        }
        var sourceType = normalizeSourceType(command.sourceType(), file);
        if ("XLSX".equals(sourceType) && !isXlsx(file)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "Excel 生产单只支持 XLSX 或 XLS 文件");
        }
        if ("PHOTO".equals(sourceType) && !isImage(file) && !isDocument(file)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "图片/文档生产单只接受图片、DOC 或 DOCX 文件");
        }
        if (command.templateVersionId() != null) {
            var template = orders.findPublishedTemplate(actor.organizationId(), command.templateVersionId())
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "已发布模板不存在或已失效"));
            if (!"XLSX".equalsIgnoreCase(template.format())) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "生产单识别模板必须是 XLSX 模板");
            }
        }
        // Staging the same local file twice creates two file-object ids. Use the
        // content hash as the idempotency key so the business record is still unique.
        var existing = repository.findActiveBySha256(actor.organizationId(), file.sha256());
        if (existing.isPresent()) {
            if (!command.replaceExisting()) return existing.get();
            if ("PUBLISHED".equals(existing.get().status())) {
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                        "该文件已有已发布生产单，不能覆盖，请先创建新版本");
            }
            cancelAsyncJob(actor.organizationId(), existing.get().id());
            repository.delete(actor.organizationId(), existing.get().id());
        }
        var id = UUID.randomUUID();
        try {
            repository.insert(new ProductionUploadRepository.NewUpload(
                    id,
                    actor.organizationId(),
                    command.fileId(),
                    blankToNull(command.productionName()),
                    blankToNull(command.orderNo()),
                    blankToNull(command.productName()),
                    blankToNull(command.category()),
                    command.manufactureDate(),
                    command.projectId(),
                    blankToNull(command.projectName()),
                    command.stageId(),
                    blankToNull(command.stageName()),
                    command.taskId(),
                    blankToNull(command.taskName()),
                    command.visibility(),
                    sourceType,
                    command.templateVersionId(),
                    actor.userId()
            ));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "该文件已经保存，请勿重复上传");
        }
        var payload = objectMapper.createObjectNode()
                .put("organizationId", actor.organizationId().toString())
                .put("uploadId", id.toString())
                .put("fileId", command.fileId().toString())
                .put("sourceType", sourceType);
        if (command.templateVersionId() != null) {
            payload.put("templateVersionId", command.templateVersionId().toString());
        }
        var asyncJobId = async.enqueue(actor.organizationId(), "PRODUCTION_" + sourceType + "_INGEST", payload,
                "production-upload:" + id, 50);
        repository.attachAsyncJob(id, asyncJobId);
        files.activate(command.fileId());
        return repository.find(actor.organizationId(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.INTERNAL_ERROR, "上传记录保存失败"));
    }

    @Transactional
    public ProductionUploadRepository.UploadView selectTemplate(UUID id, UUID templateVersionId) {
        var actor = ActorContext.required();
        var current = get(id);
        if (!Set.of("REVIEW_REQUIRED", "SAVED").contains(current.status())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "当前生产单不支持重新选择模板");
        }
        var template = orders.findPublishedTemplate(actor.organizationId(), templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "已发布模板不存在或已失效"));
        if (!"XLSX".equalsIgnoreCase(template.format())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "生产单识别模板必须是 XLSX 模板");
        }
        var sourceType = current.sourceType() == null || current.sourceType().isBlank()
                ? "XLSX" : current.sourceType().toUpperCase(java.util.Locale.ROOT);
        repository.queueRecognition(id, templateVersionId);
        var payload = objectMapper.createObjectNode()
                .put("organizationId", actor.organizationId().toString())
                .put("uploadId", id.toString())
                .put("fileId", current.fileId().toString())
                .put("sourceType", sourceType)
                .put("templateVersionId", templateVersionId.toString());
        var asyncJobId = async.enqueue(actor.organizationId(), "PRODUCTION_" + sourceType + "_INGEST",
                payload, "production-upload:" + id + ":template:" + templateVersionId + ":" + UUID.randomUUID(), 50);
        repository.attachAsyncJob(id, asyncJobId);
        return get(id);
    }

    public ProductionUploadRepository.PageResult<ProductionUploadRepository.UploadView> list(
            String keyword, String status, UUID projectId, boolean viewableOnly, int page, int size) {
        var safePage = Math.max(1, page);
        var safeSize = Math.min(100, Math.max(1, size));
        return repository.list(ActorContext.required().organizationId(), keyword, status, projectId,
                viewableOnly, safePage, safeSize);
    }

    public ProductionUploadRepository.UploadView retry(UUID id) {
        var actor = ActorContext.required();
        var current = get(id);
        if (Set.of("QUEUED", "PARSING", "MATCHING_TEMPLATE", "EXTRACTING").contains(current.status())) {
            // Commit cancellation before queueing the replacement job. This
            // prevents the old worker from publishing a result after a retry
            // has been requested.
            cancelAsyncJob(actor.organizationId(), current.id());
        }
        var sourceType = current.sourceType() == null || current.sourceType().isBlank()
                ? "XLSX" : current.sourceType().toUpperCase(java.util.Locale.ROOT);
        repository.queueRecognition(id, current.selectedTemplateVersionId());
        enqueueRecognition(actor.organizationId(), id, current.fileId(), sourceType,
                current.selectedTemplateVersionId(), "production-upload:" + id + ":retry:" + UUID.randomUUID());
        return get(id);
    }

    /**
     * Generates the Excel workbook for a recognized upload that has no sheet of
     * its own — a photo matched against an XLSX template — and stores it as the
     * record's editable snapshot.  Photo recognition already does this, so the
     * conversion mainly serves records recognized before it existed and records
     * whose automatic generation failed.
     */
    @Transactional
    public ProductionUploadRepository.UploadView convertToWorkbook(UUID id) {
        var actor = ActorContext.required();
        var current = getViewable(id);
        if ("XLSX".equalsIgnoreCase(current.sourceType())) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "Excel 生产单本身已包含工作簿，无需转换");
        }
        if (current.selectedTemplateVersionId() == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "请先为该生产单选择识别模板，再生成 Excel 工作簿");
        }
        var recognition = current.recognitionResult();
        var workbook = workbookBuilder.build(actor.organizationId(), current.selectedTemplateVersionId(),
                recognition == null ? objectMapper.createObjectNode() : recognition.path("data"));
        repository.saveGeneratedWorkbook(id, workbook);
        return get(id);
    }

    public java.util.List<ProductionUploadRepository.RecognitionFieldView> fields(UUID id) {
        var actor = ActorContext.required();
        get(id);
        return repository.listRecognitionFields(actor.organizationId(), id);
    }

    /** Adds the review projection to uploads parsed before field review existed.
     * Published workbook versions and edited cell contents are preserved. */
    @Transactional
    public ProductionUploadRepository.UploadView ensureFieldReview(UUID id) {
        var actor = ActorContext.required();
        var current = getViewable(id);
        if (!("XLSX".equalsIgnoreCase(current.sourceType())
                || ("PHOTO".equalsIgnoreCase(current.sourceType())
                    && current.selectedTemplateVersionId() != null))
                || current.recognitionResult() == null
                || current.recognitionResult().path("recognitionReview").isObject()) return current;
        var result = (ObjectNode) current.recognitionResult().deepCopy();
        var mapping = result.path("mapping");
        JsonNode schema = objectMapper.createObjectNode();
        if (current.selectedTemplateVersionId() != null) {
            schema = orders.findPublishedTemplate(actor.organizationId(), current.selectedTemplateVersionId())
                    .map(ProductionOrderRepository.PublishedTemplate::schema)
                    .orElse(schema);
        }
        reviewAssembler.attach(result, current.sourceType().toUpperCase(), current.originalName(), current.structureSummary(),
                schema, mapping, result.path("data"));
        if (repository.listRecognitionFields(actor.organizationId(), id).isEmpty()) {
            var fields = new java.util.ArrayList<ProductionUploadRepository.RecognitionField>();
            for (var item : result.path("recognitionReview").path("items")) {
                var payload = item.path("payload");
                var path = payload.path("dataPath").asText("");
                if (path.isBlank()) continue;
                fields.add(new ProductionUploadRepository.RecognitionField(
                        UUID.randomUUID(), item.path("id").asText(UUID.randomUUID().toString()),
                        item.path("kind").asText("SCALAR"), payload.path("bindingId").asText(""),
                        payload.path("fieldCode").asText(""), path, null,
                        null, null, payload.path("locator"),
                        item.path("confidence").asDouble(0.5d), "PENDING"));
            }
            repository.replaceRecognitionFields(id, fields);
        }
        repository.updateRecognitionResult(id, result);
        return get(id);
    }

    /**
     * Persists the reviewed recognition fields.  This is the point where a
     * recognition result becomes data: the confirmed values replace the recognized
     * ones in the field table, they are written into the record's workbook so the
     * Excel and the stored fields agree, and the business metadata (order number,
     * product name, ...) is refreshed from the reviewed values.
     */
    @Transactional
    public ProductionUploadRepository.UploadView confirmFields(UUID id, List<FieldConfirmation> confirmations) {
        var actor = ActorContext.required();
        var current = getViewable(id);
        var stored = repository.listRecognitionFields(actor.organizationId(), id);
        if (stored.isEmpty()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "该生产单没有可确认的识别字段");
        }
        var decisions = new java.util.HashMap<String, FieldConfirmation>();
        for (var confirmation : confirmations == null ? List.<FieldConfirmation>of() : confirmations) {
            decisions.put(fieldKey(confirmation.fieldCode(), confirmation.dataPath(), confirmation.recordIndex()),
                    confirmation);
        }

        var reviewed = new java.util.ArrayList<ProductionUploadRepository.RecognitionField>();
        var data = objectMapper.createObjectNode();
        for (var field : stored) {
            var decision = decisionFor(field, decisions);
            var submitted = decision == null ? null : blankToNull(decision.value());
            var value = submitted != null ? submitted : textOf(field.normalizedValue(), field.rawValue());
            // An untouched field keeps the status recognition gave it; a value the
            // reviewer submitted (or confirmed) is recorded as confirmed.
            var status = decision == null
                    ? field.reviewStatus()
                    : (decision.reviewStatus() == null || decision.reviewStatus().isBlank()
                            ? "CONFIRMED" : decision.reviewStatus());
            reviewed.add(new ProductionUploadRepository.RecognitionField(field.id(), field.itemKey(),
                    field.itemKind(), field.bindingId(), field.fieldCode(), field.dataPath(),
                    field.recordIndex(), field.rawValue(),
                    value == null ? field.normalizedValue() : objectMapper.getNodeFactory().textNode(value),
                    field.sourceLocator(), field.confidence(), status));
            if (value != null && !value.isBlank() && field.dataPath() != null && !field.dataPath().contains("*")) {
                putValue(data, field.dataPath(), value);
            }
        }

        var snapshot = current.workbookSnapshot();
        var mapping = current.recognitionResult() == null
                ? null : current.recognitionResult().path("mapping");
        if (snapshot != null && snapshot.isObject() && mapping != null && mapping.isArray()
                && !mapping.isEmpty() && !data.isEmpty()) {
            repository.saveGeneratedWorkbook(id, workbookBuilder.fill(snapshot, mapping, data));
        }
        repository.replaceRecognitionFields(id, reviewed);
        var reviewedResult = updateRecognitionReview(current.recognitionResult(), decisions);
        if (reviewedResult != current.recognitionResult()) {
            repository.updateRecognitionResult(id, reviewedResult);
        }
        repository.updateRecognizedMetadata(id,
                reviewedValue(reviewed, "PRODUCTION.ORDER_NO"),
                reviewedValue(reviewed, "PRODUCTION.PRODUCT_NAME"),
                reviewedValue(reviewed, "PRODUCTION.CATEGORY"),
                reviewedDate(reviewed, "PRODUCTION.MANUFACTURE_DATE"));
        return get(id);
    }

    /**
     * The field table is the durable source for individual decisions, while the
     * workspace status strip reads the compact review JSON. Keep both projections
     * aligned so a refresh does not reopen already-confirmed fields as pending.
     */
    private JsonNode updateRecognitionReview(JsonNode recognitionResult,
                                             Map<String, FieldConfirmation> decisions) {
        if (recognitionResult == null || !recognitionResult.isObject()) return recognitionResult;
        var result = (ObjectNode) recognitionResult.deepCopy();
        var reviewNode = result.get("recognitionReview");
        if (reviewNode == null || !reviewNode.isObject()) return recognitionResult;
        var review = (ObjectNode) reviewNode;
        var items = review.withArray("items");
        int confirmed = 0;
        int pending = 0;
        int conflict = 0;
        int ignored = 0;
        var itemStatuses = new java.util.HashMap<String, String>();
        var regionStatuses = new java.util.HashMap<String, String>();
        for (var itemNode : items) {
            if (!(itemNode instanceof ObjectNode item)) continue;
            var payload = item.path("payload");
            var recordIndex = payload.has("recordIndex") && !payload.path("recordIndex").isNull()
                    ? payload.path("recordIndex").asInt() : null;
            var key = fieldKey(payload.path("fieldCode").asText(""),
                    payload.path("dataPath").asText(""), recordIndex);
            var decision = decisions.get(key);
            if (decision == null) {
                decision = decisions.values().stream()
                        .filter(candidate -> samePath(candidate.dataPath(), payload.path("dataPath").asText(""))
                                && java.util.Objects.equals(candidate.recordIndex(), recordIndex))
                        .findFirst().orElse(null);
            }
            if (decision != null) item.put("status", reviewStatus(decision.reviewStatus()));
            itemStatuses.put(item.path("id").asText(""), item.path("status").asText("PENDING"));
            if (!"SCALAR".equals(item.path("kind").asText("SCALAR"))) {
                regionStatuses.put(payload.path("regionId").asText(""), item.path("status").asText("PENDING"));
                continue;
            }
            switch (item.path("status").asText("PENDING")) {
                case "CONFIRMED" -> confirmed++;
                case "CONFLICT" -> conflict++;
                case "IGNORED" -> ignored++;
                default -> pending++;
            }
        }
        int pendingRegions = 0;
        for (var regionNode : review.withArray("regions")) {
            if (!(regionNode instanceof ObjectNode region)) continue;
            var status = regionStatuses.getOrDefault(region.path("regionId").asText(""),
                    region.path("status").asText("PENDING"));
            region.put("status", status);
            region.put("canonicalStatus", "CONFIRMED".equals(status) ? "CONFIRMED" : "PROVISIONAL");
            region.put("structureStatus", "CONFIRMED".equals(status) ? "CONFIRMED" : "PROVISIONAL");
            if (!"CONFIRMED".equals(status) && !"IGNORED".equals(status)) pendingRegions++;
            for (var fieldNode : region.withArray("fields")) {
                if (fieldNode instanceof ObjectNode field) {
                    var fieldStatus = itemStatuses.get(field.path("id").asText(""));
                    if (fieldStatus != null) field.put("status", fieldStatus);
                }
            }
        }
        var summary = review.withObject("summary");
        summary.put("total", confirmed + pending + conflict + ignored);
        summary.put("confirmed", confirmed);
        summary.put("pending", pending);
        summary.put("conflict", conflict);
        summary.put("ignored", ignored);
        var statistics = review.withObject("statistics");
        statistics.put("fieldCount", confirmed + pending + conflict + ignored);
        statistics.put("pendingFieldCount", pending);
        var coverage = review.withObject("recognitionCoverage");
        coverage.put("status", pendingRegions == 0 ? "COMPLETE" : "REVIEW_REQUIRED");
        coverage.put("unresolvedRegionCount", pendingRegions);
        coverage.put("coverageRatio", regionStatuses.isEmpty() ? 1d
                : (double) (regionStatuses.size() - pendingRegions) / regionStatuses.size());
        review.put("recognitionStatus", pending == 0 && conflict == 0 && pendingRegions == 0
                ? "COMPLETE" : "REVIEW_REQUIRED");
        return result;
    }

    private FieldConfirmation decisionFor(ProductionUploadRepository.RecognitionFieldView field,
                                          Map<String, FieldConfirmation> decisions) {
        var exact = decisions.get(fieldKey(field.fieldCode(), field.dataPath(), field.recordIndex()));
        if (exact != null) return exact;
        return decisions.values().stream()
                .filter(candidate -> samePath(candidate.dataPath(), field.dataPath())
                        && java.util.Objects.equals(candidate.recordIndex(), field.recordIndex()))
                .findFirst().orElse(null);
    }

    private boolean samePath(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }

    private String reviewStatus(String value) {
        return switch (value == null ? "" : value.trim().toUpperCase()) {
            case "CONFIRMED" -> "CONFIRMED";
            case "IGNORED" -> "IGNORED";
            case "CONFLICT" -> "CONFLICT";
            default -> "PENDING";
        };
    }

    private String fieldKey(String fieldCode, String dataPath, Integer recordIndex) {
        return (fieldCode == null ? "" : fieldCode) + '|' + (dataPath == null ? "" : dataPath) + '|'
                + (recordIndex == null ? "" : recordIndex);
    }

    private String textOf(JsonNode normalized, JsonNode raw) {
        var value = normalized == null || normalized.isNull() ? raw : normalized;
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        return value.isTextual() ? value.asText("") : value.asText("");
    }

    private void putValue(com.fasterxml.jackson.databind.node.ObjectNode data, String dataPath, String value) {
        var segments = new java.util.ArrayList<String>();
        for (var segment : dataPath.split("/")) {
            if (!segment.isBlank()) segments.add(segment);
        }
        if (segments.isEmpty()) return;
        var node = data;
        for (var index = 0; index < segments.size() - 1; index++) {
            node = node.withObject(segments.get(index));
        }
        node.put(segments.get(segments.size() - 1), value);
    }

    private String reviewedValue(List<ProductionUploadRepository.RecognitionField> fields, String fieldCode) {
        for (var field : fields) {
            if (!fieldCode.equalsIgnoreCase(field.fieldCode())) continue;
            var value = textOf(field.normalizedValue(), field.rawValue());
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private LocalDate reviewedDate(List<ProductionUploadRepository.RecognitionField> fields, String fieldCode) {
        var value = reviewedValue(fields, fieldCode);
        if (value == null) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** One reviewed field value coming back from the workspace. */
    public record FieldConfirmation(String fieldCode, String dataPath, Integer recordIndex,
                                    String value, String reviewStatus) {
    }

    public ProductionUploadRepository.UploadView get(UUID id) {
        return repository.find(ActorContext.required().organizationId(), id)
                .filter(item -> !"DELETED".equals(item.status()))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单上传记录不存在"));
    }

    public ProductionUploadRepository.UploadView getViewable(UUID id) {
        var upload = get(id);
        // REVIEW_REQUIRED is retained only for backward compatibility with
        // rows created before recognition completion became a saved draft.
        if (!(Set.of("SAVED", "PUBLISHED", "REVIEW_REQUIRED").contains(upload.status()))) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "生产单尚未解析完成，暂不可查看");
        }
        return upload;
    }

    @Transactional
    public ProductionUploadRepository.UploadView rename(UUID id, RenameCommand command) {
        var actor = ActorContext.required();
        getViewable(id);
        if (!StringUtils.hasText(command.productionName())) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "生产单名称不能为空");
        }
        return repository.rename(actor.organizationId(), actor.userId(), id, command.productionName().trim(),
                        command.projectId(), command.projectName(), command.stageId(), command.stageName(),
                        command.taskId(), command.taskName(), command.lockVersion())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单上传记录不存在"));
    }

    @Transactional
    public ProductionUploadRepository.UploadView saveDraft(UUID id, JsonNode workbookSnapshot,
                                                            long lockVersion) {
        validateWorkbookSnapshot(get(id), workbookSnapshot);
        var actor = ActorContext.required();
        return repository.saveDraft(actor.organizationId(), actor.userId(), id,
                        workbookSnapshot, lockVersion)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单上传记录不存在"));
    }

    @Transactional
    public void saveBatch(java.util.List<SaveDraftCommand> records) {
        var actor = ActorContext.required();
        for (var record : records) {
            if (record.workbookSnapshot() != null) {
                validateWorkbookSnapshot(get(record.id()), record.workbookSnapshot());
                repository.saveDraft(actor.organizationId(), actor.userId(), record.id(),
                                record.workbookSnapshot(), record.lockVersion())
                        .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单上传记录不存在"));
            } else {
                repository.updateMetadata(actor.organizationId(), actor.userId(), record.id(),
                                blankToNull(record.productionName()), blankToNull(record.orderNo()),
                                blankToNull(record.productName()), blankToNull(record.category()),
                                record.manufactureDate(), record.lockVersion())
                        .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单上传记录不存在"));
            }
        }
    }

    private void validateWorkbookSnapshot(ProductionUploadRepository.UploadView upload,
                                          JsonNode workbookSnapshot) {
        if (workbookSnapshot == null || !workbookSnapshot.isObject()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "工作簿内容不能为空");
        }
        if (!"XLSX".equalsIgnoreCase(upload.sourceType())) {
            return;
        }
        var sheets = workbookSnapshot.get("sheets");
        var sheetOrder = workbookSnapshot.get("sheetOrder");
        if (sheets == null || !sheets.isObject() || sheets.isEmpty()
                || sheetOrder == null || !sheetOrder.isArray() || sheetOrder.isEmpty()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "工作簿尚未加载完成，为避免覆盖原数据，本次未保存");
        }
    }

    public java.util.List<ProductionUploadRepository.VersionView> versions(UUID id) {
        var actor = ActorContext.required();
        getViewable(id);
        return repository.versions(actor.organizationId(), id);
    }

    @Transactional
    public ProductionUploadRepository.VersionView publish(UUID id) {
        var actor = ActorContext.required();
        get(id);
        return repository.publish(actor.organizationId(), actor.userId(), id);
    }

    @Transactional
    public void delete(UUID id) {
        var actor = ActorContext.required();
        var current = repository.find(actor.organizationId(), id)
                .filter(item -> !"DELETED".equals(item.status()))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单上传记录不存在"));
        cancelAsyncJob(actor.organizationId(), current.id());
        if (repository.delete(actor.organizationId(), id) == 0) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "生产单上传记录不存在");
        }
    }

    private void cancelAsyncJob(UUID organizationId, UUID uploadId) {
        repository.findAsyncJobId(organizationId, uploadId)
                .ifPresent(asyncJobId -> async.cancel(organizationId, asyncJobId));
    }

    private void enqueueRecognition(UUID organizationId, UUID uploadId, UUID fileId,
                                    String sourceType, UUID templateVersionId, String idempotencyKey) {
        var payload = objectMapper.createObjectNode()
                .put("organizationId", organizationId.toString())
                .put("uploadId", uploadId.toString())
                .put("fileId", fileId.toString())
                .put("sourceType", sourceType);
        if (templateVersionId != null) payload.put("templateVersionId", templateVersionId.toString());
        var asyncJobId = async.enqueue(organizationId, "PRODUCTION_" + sourceType + "_INGEST",
                payload, idempotencyKey, 50);
        repository.attachAsyncJob(uploadId, asyncJobId);
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record CreateCommand(
            UUID fileId,
            String productionName,
            String orderNo,
            String productName,
            String category,
            LocalDate manufactureDate,
            UUID projectId,
            String projectName,
            UUID stageId,
            String stageName,
            UUID taskId,
            String taskName,
            String visibility,
            String sourceType,
            UUID templateVersionId,
            boolean replaceExisting
    ) {
    }

    private String normalizeSourceType(String requested, FileObjectRepository.FileObject file) {
        var value = requested == null || requested.isBlank()
                ? (isImage(file) ? "PHOTO" : "XLSX")
                : requested.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("XLSX", "PHOTO").contains(value)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "生产单来源必须是 XLSX 或 PHOTO");
        }
        return value;
    }

    private boolean isXlsx(FileObjectRepository.FileObject file) {
        return file.originalName() != null
                && file.originalName().toLowerCase(java.util.Locale.ROOT).matches(".*\\.xlsx?$");
    }

    private boolean isImage(FileObjectRepository.FileObject file) {
        var contentType = file.contentType() == null ? "" : file.contentType().toLowerCase(java.util.Locale.ROOT);
        var name = file.originalName() == null ? "" : file.originalName().toLowerCase(java.util.Locale.ROOT);
        return contentType.startsWith("image/") || name.matches(".*\\.(png|jpe?g|gif|webp|bmp|tiff?)$");
    }

    private boolean isDocument(FileObjectRepository.FileObject file) {
        var contentType = file.contentType() == null ? "" : file.contentType().toLowerCase(java.util.Locale.ROOT);
        var name = file.originalName() == null ? "" : file.originalName().toLowerCase(java.util.Locale.ROOT);
        // .doc (BIFF-era Word) is normalized to DOCX during recognition, so it
        // must pass the same creation-stage check as .docx.
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType)
                || "application/msword".equals(contentType)
                || name.endsWith(".docx")
                || name.endsWith(".doc");
    }

    public record SaveDraftCommand(
            UUID id,
            JsonNode workbookSnapshot,
            long lockVersion,
            String productionName,
            String orderNo,
            String productName,
            String category,
            LocalDate manufactureDate
    ) {
    }

    public record RenameCommand(long lockVersion, String productionName, UUID projectId, String projectName,
                                UUID stageId, String stageName, UUID taskId, String taskName) {
    }
}
