package com.jsd.aird.mfg.upload.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
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

    public ProductionUploadService(ProductionUploadRepository repository, FileObjectRepository files,
                                   OpsAsyncFacade async, ObjectMapper objectMapper,
                                   ProductionOrderRepository orders) {
        this.repository = repository;
        this.files = files;
        this.async = async;
        this.objectMapper = objectMapper;
        this.orders = orders;
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
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "Excel 生产单只支持 XLSX 文件");
        }
        if ("PHOTO".equals(sourceType) && !isImage(file) && !isDocument(file)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "图片生产单只接受图片或 DOCX 文件");
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

    public java.util.List<ProductionUploadRepository.RecognitionFieldView> fields(UUID id) {
        var actor = ActorContext.required();
        get(id);
        return repository.listRecognitionFields(actor.organizationId(), id);
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
                && file.originalName().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx");
    }

    private boolean isImage(FileObjectRepository.FileObject file) {
        var contentType = file.contentType() == null ? "" : file.contentType().toLowerCase(java.util.Locale.ROOT);
        var name = file.originalName() == null ? "" : file.originalName().toLowerCase(java.util.Locale.ROOT);
        return contentType.startsWith("image/") || name.matches(".*\\.(png|jpe?g|gif|webp|bmp|tiff?)$");
    }

    private boolean isDocument(FileObjectRepository.FileObject file) {
        var contentType = file.contentType() == null ? "" : file.contentType().toLowerCase(java.util.Locale.ROOT);
        var name = file.originalName() == null ? "" : file.originalName().toLowerCase(java.util.Locale.ROOT);
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType)
                || name.endsWith(".docx");
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
