package com.jsd.aird.quality.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.MediaExtractionProvider;
import com.jsd.aird.quality.application.port.QualityDataStore;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.shared.excel.WorkbookInstanceParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class QualityDataService {
    private final QualityDataStore repository;
    private final ObjectMapper objectMapper;
    private final FileStorageFacade storage;
    private final QualitySourceParser sourceParser;
    private final WorkbookInstanceParser workbookParser;
    private final List<MediaExtractionProvider> mediaProviders;
    public QualityDataService(QualityDataStore repository, ObjectMapper objectMapper,
                              FileStorageFacade storage, QualitySourceParser sourceParser,
                              WorkbookInstanceParser workbookParser,
                              List<MediaExtractionProvider> mediaProviders) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.storage = storage;
        this.sourceParser = sourceParser;
        this.workbookParser = workbookParser;
        this.mediaProviders = List.copyOf(mediaProviders);
    }

    public List<QualityDataDefinitions.Type> definitions() { return QualityDataDefinitions.TYPES; }
    public List<QualityDataStore.Category> categories(String type) { var a=ActorContext.required(); requireType(type); return repository.categories(a.organizationId(),type); }
    public QualityDataStore.Category createCategory(String type,String name,String description) { var a=ActorContext.required(); requireType(type); return repository.createCategory(a.organizationId(),a.userId(),type,name.trim(),description.trim()); }
    public QualityDataStore.Category updateCategory(UUID id,String name,String description) { var a=ActorContext.required(); return repository.updateCategory(a.organizationId(),id,name.trim(),description.trim()); }
    public void deleteCategory(UUID id) { repository.deleteCategory(ActorContext.required().organizationId(),id); }
    public PageResponse<QualityDataStore.RecordView> records(String type,UUID categoryId,String keyword,int page,int size) { var a=ActorContext.required(); requireType(type); return repository.records(a.organizationId(),a.role(),type,categoryId,keyword,Math.max(1,page),Math.min(100,Math.max(1,size))); }
    public QualityDataStore.RecordView record(UUID id) { var a=ActorContext.required(); return repository.record(a.organizationId(), a.role(), id); }
    public QualityDataStore.RecordView rename(UUID id, RenameCommand command) {
        var actor = ActorContext.required();
        if (command.displayName() == null || command.displayName().isBlank())
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "名称不能为空");
        repository.record(actor.organizationId(), actor.role(), id);
        return repository.rename(actor.organizationId(), actor.userId(), id, command.displayName().trim(),
                command.projectId(), command.projectName(), command.stageId(), command.stageName(),
                command.taskId(), command.taskName(), command.lockVersion());
    }
    public List<QualityDataStore.VersionView> versions(UUID id) { var a=ActorContext.required(); repository.record(a.organizationId(), a.role(), id); return repository.versions(a.organizationId(), id); }
    @Transactional public QualityDataStore.VersionView publishRecord(UUID id) {
        var actor = ActorContext.required();
        var current = repository.record(actor.organizationId(), actor.role(), id);
        // 发布只生成版本快照，不阻止尚未补齐的业务字段；字段格式仍由草稿校验保证。
        validateDraft(requireType(current.businessType()), current.data());
        return repository.publish(actor.organizationId(), actor.userId(), id);
    }
    public void deleteRecord(UUID id) {
        var actor = ActorContext.required();
        var current = repository.record(actor.organizationId(), actor.role(), id);
        if (isFinalized(current.data()))
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"正式质量记录不可物理删除，请先归档或走受控作废流程");
        repository.softDeleteOne(actor.organizationId(), id);
    }

    @Transactional public void saveBatch(String type, UUID categoryId, List<RecordInput> records, List<UUID> deleteIds) {
        var a=ActorContext.required(); var def=requireType(type);
        for (var row: records) {
            var current = row.id() == null ? null : repository.record(a.organizationId(), a.role(), row.id());
            if (current != null && !current.businessNo().equals(row.businessNo()))
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"正式品管编号不可通过普通编辑修改");
            if (current != null && isFinalized(current.data()) && !current.data().equals(row.data()))
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"正式质量记录已生效，请通过修订流程修改");
            validateDraft(def,row.data());
            repository.upsert(a.organizationId(),a.userId(),type,categoryId,row.id(),row.businessNo(),row.data(),row.workbookSnapshot(),row.lockVersion());
        }
        repository.softDelete(a.organizationId(),deleteIds==null?List.of():deleteIds);
    }

    @Transactional public void move(List<UUID> ids,UUID categoryId) {
        var actor = ActorContext.required();
        repository.category(actor.organizationId(), categoryId);
        repository.move(actor.organizationId(),ids,categoryId);
    }

    @Transactional public QualityDataStore.RecordView createDefectFromRecord(UUID recordId) {
        var actor = ActorContext.required();
        var source = repository.record(actor.organizationId(), actor.role(), recordId);
        if (!"record".equals(source.businessType()) || !"不合格".equals(source.data().path("judgement").asText()))
            throw new ApiException(ApiErrorCode.BAD_REQUEST,"只有判定为不合格的测试记录才能生成不良报告");
        var category = repository.categories(actor.organizationId(), "defect").stream().findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND,"未维护不良报告单分类"));
        var data = objectMapper.createObjectNode();
        var no = "NG-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase();
        data.put("defectNo", no);
        data.put("occurDate", source.data().path("testDate").asText(LocalDate.now().toString()));
        data.put("source", "测试记录：" + source.businessNo());
        data.put("sourceRecordNo", source.businessNo());
        data.put("productName", source.data().path("productName").asText(""));
        data.put("batchNumber", source.data().path("batchNumber").asText(""));
        data.put("phenomenon", source.data().path("testResult").asText("检测结果不合格"));
        data.put("quantity", "1");
        data.put("owner", actor.username());
        data.put("status", "待处理");
        validateDraft(requireType("defect"), data);
        return repository.upsert(actor.organizationId(), actor.userId(), "defect", category.id(), null,
                no, data, null, 0);
    }

    @Transactional public QualityDataStore.UploadView upload(UploadInput input) {
        var a=ActorContext.required();
        var category=repository.category(a.organizationId(),input.categoryId()); var def=requireType(category.businessType());
        validateVisibility(input);
        var no=def.prefix()+"-"+Long.toString(System.currentTimeMillis(),36).toUpperCase();
        var data=objectMapper.createObjectNode(); data.put(QualityDataDefinitions.idKey(def.id()),no); data.put("sourceFile",input.originalName());
        var title=input.originalName().replaceFirst("\\.[^.]+$","");
        if ("standard".equals(def.id())) { data.put("fileName",title);data.put("fileType",sourceFileType(input));data.put("author",a.username());data.put("effectiveDate",LocalDate.now().toString());data.put("version","V0.1"); }
        if ("record".equals(def.id())) { data.put("testDate",LocalDate.now().toString());data.put("productName",title);data.put("batchNumber","");data.put("testItem","待解析");data.put("testResult","待解析");data.put("judgement","待判定");data.put("tester",a.username()); }
        String parseError = null;
        JsonNode workbookSnapshot = null;
        try (var stored = storage.open(a.organizationId(), input.fileId())) {
            var bytes = stored.stream().readAllBytes();
            var replay = new FileStorageFacade.StoredFile(stored.fileId(), stored.originalName(),
                    stored.contentType(), bytes.length, stored.sha256(), new ByteArrayInputStream(bytes));
            try (replay) {
                sourceParser.parse(def, data, replay);
            }
            if (isXlsx(input.originalName())) {
                workbookSnapshot = workbookParser.parseInstance(new ByteArrayInputStream(bytes)).snapshot();
            } else if (isImage(input.originalName(), input.contentType())) {
                applyImageOcr(def, data, input, bytes);
            }
        } catch (Exception e) {
            parseError = e.getMessage() == null || e.getMessage().isBlank()
                    ? "品管文件解析失败" : e.getMessage();
        }
        if (parseError != null) {
            var failedUpload = repository.insertUpload(a.organizationId(), a.userId(), null, input);
            storage.activate(input.fileId());
            return repository.updateUploadStatus(a.organizationId(), failedUpload.id(), "FAILED", null,
                    parseError.substring(0, Math.min(1000, parseError.length())));
        }
        attachKnownRelations(a.organizationId(), def, data);
        var recordId=repository.insertDraft(a.organizationId(),a.userId(),def.id(),category.id(),no,data,input,workbookSnapshot);
        var upload = repository.insertUpload(a.organizationId(),a.userId(),recordId,input);
        storage.activate(input.fileId());
        return upload;
    }

    private void attachKnownRelations(UUID organizationId, QualityDataDefinitions.Type def, ObjectNode data) {
        if ("coa".equals(def.id())) {
            repository.findBusinessNoByField(organizationId, "record", "batchNumber", data.path("batchNumber").asText(""))
                    .ifPresent(no -> data.put("internalRecordNo", no));
        } else if ("record".equals(def.id())) {
            repository.findBusinessNoByField(organizationId, "standard", "fileName", data.path("productName").asText(""))
                    .ifPresent(no -> data.put("standardNo", no));
        } else if ("msds".equals(def.id())) {
            repository.findBusinessNoByField(organizationId, "manual", "productName", data.path("chemicalName").asText(""))
                    .ifPresent(no -> data.put("relatedManualNo", no));
        } else if ("label".equals(def.id())) {
            repository.findBusinessNoByField(organizationId, "msds", "chemicalName", data.path("productName").asText(""))
                    .ifPresent(no -> data.put("relatedMsdsNo", no));
        }
    }

    public PageResponse<QualityDataStore.UploadView> uploads(String keyword,String status,UUID projectId,int page,int size) {
        var a=ActorContext.required();
        return repository.uploads(a.organizationId(),a.userId(),a.role(),keyword,status,projectId,Math.max(1,page),Math.min(100,Math.max(1,size)));
    }

    public void deleteUpload(UUID id) { repository.deleteUpload(ActorContext.required().organizationId(),id); }

    /** Re-runs source parsing for an existing upload without creating a second upload row. */
    public QualityDataStore.UploadView retryUpload(UUID id) {
        var actor = ActorContext.required();
        var current = repository.findUpload(actor.organizationId(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "上传记录不存在"));
        var category = repository.category(actor.organizationId(), current.categoryId());
        var definition = requireType(category.businessType());
        var input = new UploadInput(current.fileId(), current.categoryId(), current.originalName(),
                current.contentType(), current.size(), null, current.projectId(), current.projectName(),
                current.stageId(), current.stageName(), current.taskId(), current.taskName(), current.visibility());
        var existingRecordId = current.generatedRecordId();
        var businessNo = existingRecordId == null
                ? definition.prefix() + "-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase()
                : repository.record(actor.organizationId(), existingRecordId).businessNo();
        repository.updateUploadStatus(actor.organizationId(), id, "PARSING", existingRecordId, null);
        try {
            var data = initialUploadData(definition, input, actor.username(), businessNo);
            JsonNode workbookSnapshot = null;
            try (var stored = storage.open(actor.organizationId(), input.fileId())) {
                var bytes = stored.stream().readAllBytes();
                var replay = new FileStorageFacade.StoredFile(stored.fileId(), stored.originalName(),
                        stored.contentType(), bytes.length, stored.sha256(), new ByteArrayInputStream(bytes));
                try (replay) {
                    sourceParser.parse(definition, data, replay);
                }
                if (isXlsx(input.originalName())) {
                    workbookSnapshot = workbookParser.parseInstance(new ByteArrayInputStream(bytes)).snapshot();
                } else if (isImage(input.originalName(), input.contentType())) {
                    applyImageOcr(definition, data, input, bytes);
                }
            }
            attachKnownRelations(actor.organizationId(), definition, data);
            if (existingRecordId == null) {
                existingRecordId = repository.insertDraft(actor.organizationId(), actor.userId(), definition.id(),
                        input.categoryId(), businessNo, data, input, workbookSnapshot);
            } else {
                var currentRecord = repository.record(actor.organizationId(), existingRecordId);
                repository.upsert(actor.organizationId(), actor.userId(), definition.id(), input.categoryId(),
                        existingRecordId, businessNo, data, workbookSnapshot, currentRecord.lockVersion());
            }
            storage.activate(input.fileId());
            return repository.updateUploadStatus(actor.organizationId(), id, "DRAFT_CREATED", existingRecordId, null);
        } catch (Exception exception) {
            var message = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? "品管文件解析失败" : exception.getMessage();
            repository.updateUploadStatus(actor.organizationId(), id, "FAILED", existingRecordId,
                    message.substring(0, Math.min(1000, message.length())));
            if (exception instanceof ApiException apiException) throw apiException;
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "品管文件解析失败：" + message);
        }
    }

    private ObjectNode initialUploadData(QualityDataDefinitions.Type definition, UploadInput input,
                                         String username, String businessNo) {
        var data = objectMapper.createObjectNode();
        data.put(QualityDataDefinitions.idKey(definition.id()), businessNo);
        data.put("sourceFile", input.originalName());
        var title = input.originalName().replaceFirst("\\.[^.]+$", "");
        if ("standard".equals(definition.id())) {
            data.put("fileName", title);
            data.put("fileType", sourceFileType(input));
            data.put("author", username);
            data.put("effectiveDate", LocalDate.now().toString());
            data.put("version", "V0.1");
        }
        if ("record".equals(definition.id())) {
            data.put("testDate", LocalDate.now().toString());
            data.put("productName", title);
            data.put("batchNumber", "");
            data.put("testItem", "待解析");
            data.put("testResult", "待解析");
            data.put("judgement", "待判定");
            data.put("tester", username);
        }
        return data;
    }

    private QualityDataDefinitions.Type requireType(String type) { var d=QualityDataDefinitions.BY_ID.get(type); if(d==null) throw new ApiException(ApiErrorCode.BAD_REQUEST,"未知品管数据类型"); return d; }
    private void validateVisibility(UploadInput input) {
        if(!java.util.Set.of("ALL","RND","QUALITY","PROJECT").contains(input.visibility())) throw new ApiException(ApiErrorCode.BAD_REQUEST,"权限类型不合法");
        if("PROJECT".equals(input.visibility()) && input.projectId() == null) throw new ApiException(ApiErrorCode.BAD_REQUEST,"项目组可见必须先关联项目");
    }
    private String sourceFileType(UploadInput input) {
        var name = input.originalName() == null ? "" : input.originalName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx") || name.endsWith(".doc")) return "Word";
        if (name.endsWith(".xls") || name.endsWith(".xlsx")) return "Excel";
        if (name.endsWith(".csv")) return "CSV";
        if (name.endsWith(".pdf")) return "PDF";
        if (name.matches(".*\\.(jpg|jpeg|png|tif|tiff)$")) return "图片";
        return "文件";
    }
    private boolean isXlsx(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".xlsx");
    }
    private boolean isImage(String name, String contentType) {
        var lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        var lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return lowerType.startsWith("image/") || lowerName.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|tif|tiff)$");
    }
    private void applyImageOcr(QualityDataDefinitions.Type type, ObjectNode data,
                               UploadInput input, byte[] bytes) {
        var provider = mediaProviders.stream()
                .filter(item -> item.supports(input.originalName(), input.contentType()))
                .filter(MediaExtractionProvider::isConfigured)
                .findFirst()
                .orElseGet(() -> mediaProviders.stream()
                        .filter(item -> item.supports(input.originalName(), input.contentType()))
                        .findFirst().orElse(null));
        if (provider == null || !provider.isConfigured()) {
            data.put("ocrStatus", "REVIEW_REQUIRED");
            data.put("ocrMessage", provider == null ? "未找到可用的 OCR 服务" : provider.unavailableReason());
            data.put("parseStatus", "待人工补齐");
            return;
        }
        DocumentParser.ParsedDocument parsed;
        try {
            parsed = provider.extract(new ByteArrayInputStream(bytes), input.originalName(),
                    new MediaExtractionProvider.ExtractionContext(input.fileId(), input.contentType(), bytes.length, null));
        } catch (RuntimeException exception) {
            data.put("ocrStatus", "FAILED");
            data.put("ocrMessage", exception.getMessage() == null || exception.getMessage().isBlank()
                    ? "OCR 解析失败，请人工录入" : exception.getMessage());
            data.put("parseStatus", "待人工补齐");
            return;
        }
        var text = parsed.blocks().stream()
                .map(DocumentParser.TextBlock::content)
                .filter(item -> item != null && !item.isBlank())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        applyOcrLabeledText(type, data, text);
        var result = objectMapper.createObjectNode()
                .put("format", "OCR_IMPORT")
                .put("ocrStatus", "REVIEW_REQUIRED")
                .put("parserVersion", parsed.parserVersion() == null ? "ocr" : parsed.parserVersion())
                .put("recognizedBlockCount", parsed.blocks().size());
        result.set("textBlocks", objectMapper.valueToTree(parsed.blocks()));
        data.set("ocrResult", result);
        data.put("ocrStatus", "REVIEW_REQUIRED");
        data.put("parseStatus", hasStructuredValue(type, data) ? "已解析" : "待人工补齐");
    }
    private void applyOcrLabeledText(QualityDataDefinitions.Type type, ObjectNode data, String text) {
        if (text == null || text.isBlank()) return;
        var normalized = text.replace('\u00a0', ' ').replace('\r', '\n');
        for (var field : type.fields()) {
            for (var label : List.of(field.label(), field.key())) {
                var pattern = Pattern.compile("(?is)(?:^|[\\n;；])\\s*" + Pattern.quote(label)
                        + "\\s*[:：=]\\s*([^\\n;；]{1,300})");
                var match = pattern.matcher(normalized);
                if (match.find() && !match.group(1).trim().isBlank()) {
                    data.put(field.key(), match.group(1).trim());
                    break;
                }
            }
        }
    }
    private boolean hasStructuredValue(QualityDataDefinitions.Type type, ObjectNode data) {
        return type.fields().stream().anyMatch(field -> {
            var value = data.path(field.key()).asText("").trim();
            return !value.isBlank() && !"待解析".equals(value) && !"待判定".equals(value);
        });
    }
    private void validateDraft(QualityDataDefinitions.Type def, JsonNode data) {
        if(data==null||!data.isObject()) throw new ApiException(ApiErrorCode.BAD_REQUEST,"数据内容不能为空");
        for(var f:def.fields()) {
            var value=data.path(f.key()).asText("").trim();
            if("date".equals(f.kind())&&!value.isEmpty()) try { LocalDate.parse(value); } catch(Exception e){ throw new ApiException(ApiErrorCode.BAD_REQUEST,f.label()+"日期格式不正确"); }
            if("number".equals(f.kind())&&!value.isEmpty()) try { if(Double.parseDouble(value)<0) throw new Exception(); } catch(Exception e){ throw new ApiException(ApiErrorCode.BAD_REQUEST,f.label()+"必须为非负数"); }
            if(!f.options().isEmpty()&&!value.isEmpty()&&!f.options().contains(value)) throw new ApiException(ApiErrorCode.BAD_REQUEST,f.label()+"选项无效");
        }
        if ("defect".equals(def.id()) && "已关闭".equals(data.path("status").asText())) {
            if (data.path("cause").asText("").trim().isEmpty())
                throw new ApiException(ApiErrorCode.BAD_REQUEST,"关闭不良单前必须填写原因分析");
            if (data.path("correctiveAction").asText("").trim().isEmpty())
                throw new ApiException(ApiErrorCode.BAD_REQUEST,"关闭不良单前必须填写纠正措施");
        }
    }
    private boolean isFinalized(JsonNode data) {
        var status = data == null ? "" : data.path("status").asText("").trim();
        return Set.of("已签发", "有效", "已关闭", "已归档").contains(status);
    }
    public record RecordInput(UUID id,String businessNo,JsonNode data,JsonNode workbookSnapshot,long lockVersion) {}
    public record UploadInput(UUID fileId,UUID categoryId,String originalName,String contentType,long size,String sha256,UUID projectId,String projectName,UUID stageId,String stageName,UUID taskId,String taskName,String visibility) {}
    public record RenameCommand(long lockVersion, String displayName, UUID projectId, String projectName,
                                UUID stageId, String stageName, UUID taskId, String taskName) {}
}
