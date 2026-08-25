package com.jsd.aird.rnd.application;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.AuditRecord;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Create;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Detail;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Search;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Summary;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.VersionDiff;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.VersionRecord;
import com.jsd.aird.rnd.domain.ProjectDocumentFormat;
import com.jsd.aird.rnd.domain.ProjectDocumentSource;
import com.jsd.aird.rnd.domain.ProjectDocumentStatus;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.RuleBasedRecognitionEngine;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import com.jsd.aird.tpl.application.port.WorkbookSnapshotStructureParser;
import com.jsd.aird.tpl.domain.TemplateFormat;

@Service
public class ProjectDocumentService {

    private final ProjectDocumentRepository repository;
    private final FileStorageFacade fileStorageFacade;
    private final List<OfficeStructureParser> officeParsers;
    private final WorkbookSnapshotStructureParser snapshotStructureParser;
    private final RuleBasedRecognitionEngine ruleRecognitionEngine;
    private final ObjectMapper objectMapper;

    public ProjectDocumentService(
            ProjectDocumentRepository repository,
            FileStorageFacade fileStorageFacade,
            List<OfficeStructureParser> officeParsers,
            WorkbookSnapshotStructureParser snapshotStructureParser,
            RuleBasedRecognitionEngine ruleRecognitionEngine,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.fileStorageFacade = fileStorageFacade;
        this.officeParsers = List.copyOf(officeParsers);
        this.snapshotStructureParser = snapshotStructureParser;
        this.ruleRecognitionEngine = ruleRecognitionEngine;
        this.objectMapper = objectMapper;
    }

    public List<Summary> list(UUID projectId) {
        var actor = ActorContext.required();
        return repository.search(new Search(projectId, null));
    }

    public Detail get(UUID id) {
        var actor = ActorContext.required();
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目文档不存在"));
    }

    @Transactional
    public UUID create(
            UUID projectId,
            String title,
            ProjectDocumentFormat format,
            ProjectDocumentSource source,
            UUID templateId,
            UUID templateVersionId,
            UUID fileObjectId
    ) {
        var actor = ActorContext.required();
        var cmd = new Create(
                projectId,
                title,
                format,
                source,
                templateId,
                templateVersionId,
                fileObjectId,
                ProjectDocumentStatus.DRAFT,
                actor.username()
        );
        var id = repository.create(cmd);
        if (fileObjectId != null) {
            fileStorageFacade.activate(fileObjectId);
        }
        return id;
    }

    @Transactional
    public void delete(UUID id) {
        var actor = ActorContext.required();
        var detail = repository.findById(id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目文档不存在"));
        repository.delete(detail.id(), actor.username());
    }

    @Transactional
    public UUID importDocument(UUID projectId, String title, ProjectDocumentFormat format, UUID fileObjectId) {
        if (format != ProjectDocumentFormat.DOCX && format != ProjectDocumentFormat.XLSX) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "仅支持导入 DOCX 或 XLSX 文件");
        }
        var actor = ActorContext.required();
        var templateFormat = format == ProjectDocumentFormat.DOCX ? TemplateFormat.DOCX : TemplateFormat.XLSX;
        var parser = officeParsers.stream().filter(item -> item.format() == templateFormat).findFirst()
                .orElseThrow(() -> new IllegalStateException("No parser for " + templateFormat));
        try (var stored = fileStorageFacade.open(actor.organizationId(), fileObjectId)) {
            var parsed = parser.parse(stored.stream());
            var id = repository.create(new Create(projectId, title, format, ProjectDocumentSource.IMPORT,
                    null, null, fileObjectId, ProjectDocumentStatus.DRAFT, actor.username()));
            repository.saveContent(id, parsed.initialEditorSnapshot(), objectMapper.createObjectNode(),
                    objectMapper.createArrayNode(), objectMapper.createObjectNode(),
                    objectMapper.createObjectNode(), actor.username());
            fileStorageFacade.activate(fileObjectId);
            return id;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "项目文档解析失败: " + exception.getMessage());
        }
    }


    @Transactional
    public Detail saveContent(UUID id, JsonNode snapshot, JsonNode schema, JsonNode mapping, JsonNode data) {
        var actor = ActorContext.required();
        var detail = repository.findById(id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目文档不存在"));
        if (detail.status() == ProjectDocumentStatus.ARCHIVED) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "已归档文档不可编辑");
        }
        // 按模板解析逻辑：若传入编辑器快照且为 Excel，则独立解析+规则识别，结果独属于本项目文档。
        // recognition 为完整识别产物；schema/mapping 由识别结果回填，覆盖前端原样提交的值。
        // DOCX 编辑器快照为 Univer Docs 格式，需原始文件流才能解析，保存阶段仅原样存储，识别留空。
        JsonNode recognition = objectMapper.createObjectNode();
        JsonNode resolvedSchema = schema;
        JsonNode resolvedMapping = mapping;
        if (detail.format() == ProjectDocumentFormat.XLSX
                && snapshot != null && snapshot.isObject() && !snapshot.isEmpty()) {
            var result = recognizeSnapshot(snapshot, TemplateFormat.XLSX, detail.title());
            recognition = result.recognition();
            resolvedSchema = result.schema();
            resolvedMapping = result.mapping();
        }
        // 仅保存草稿内容，不记录版本（对齐实验记事本 saveDraft）。
        repository.saveContent(id, snapshot, resolvedSchema, resolvedMapping, data, recognition, actor.username());
        var saved = repository.findById(id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目文档不存在"));
        var mergedSnapshot = mergeContentSnapshot(saved);
        repository.appendAudit(id, saved.currentVersionId(), "DRAFT_SAVED", null, mergedSnapshot,
                actor.userId(), actor.username());
        return saved;
    }

    /**
     * 发布：仅发布时才记录版本（新建已发布版本行 + 回写文档状态 + 审批记录 + 审计）。
     * 对齐实验记事本 ExperimentService.transition 到 COMPLETED 的逻辑。
     */
    @Transactional
    public Detail publish(UUID id, JsonNode snapshot, JsonNode schema, JsonNode mapping, JsonNode data) {
        var actor = ActorContext.required();
        var detail = repository.findById(id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目文档不存在"));
        if (detail.status() == ProjectDocumentStatus.ARCHIVED) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "已归档文档不可发布");
        }
        saveContent(id, snapshot, schema, mapping, data);
        detail = repository.findById(id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目文档不存在"));
        var mergedSnapshot = mergeContentSnapshot(detail);
        var versionId = repository.publish(id, mergedSnapshot, "文档发布", detail.templateVersionId(), actor.username());
        repository.appendReview(id, versionId, "PUBLISHED", null, actor.userId(), actor.username());
        repository.appendAudit(id, versionId, "PUBLISHED", null, mergedSnapshot, actor.userId(), actor.username());
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目文档不存在"));
    }

    public List<VersionRecord> listVersions(UUID id) {
        ActorContext.required();
        repository.findById(id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目文档不存在"));
        return repository.versions(id);
    }

    public VersionDiff compareVersions(UUID id, long from, long to) {
        ActorContext.required();
        repository.findById(id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目文档不存在"));
        return repository.diff(id, from, to);
    }

    public List<AuditRecord> listAudits(UUID id) {
        ActorContext.required();
        repository.findById(id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目文档不存在"));
        return repository.audits(id);
    }

    /**
     * 把文档当前的多字段内容合并成单一快照，写入 project_document_version.content_jsonb，
     * 与文件对象解耦（对齐实验记事本的独立快照方案）。
     */
    private JsonNode mergeContentSnapshot(Detail detail) {
        var node = objectMapper.createObjectNode();
        node.set("contentSnapshot", detail.contentSnapshot());
        node.set("contentSchema", detail.contentSchema());
        node.set("contentMapping", detail.contentMapping());
        node.set("contentData", detail.contentData());
        node.set("contentRecognition", detail.contentRecognition());
        return node;
    }

    /**
     * 复刻模板解析链路（OfficeStructureParser + RuleBasedRecognitionEngine），但产物
     * 完全独立于模板中心，仅服务于当前项目文档。
     */
    private RecognitionResult recognizeSnapshot(JsonNode snapshot, TemplateFormat format, String sourceFileName) {
        try {
            var parsed = snapshotStructureParser.parse(
                    new java.io.ByteArrayInputStream(objectMapper.writeValueAsBytes(snapshot)));
            var structureSummary = parsed.structureSummary();
            var recognition = objectMapper.createObjectNode();
            recognition.set("structureSummary", structureSummary.deepCopy());
            recognition.set("initialEditorSnapshot", parsed.initialEditorSnapshot().deepCopy());
            // 规则识别：XLSX 走显式标签-值候选；DOCX 暂无规则候选。
            var batch = ruleRecognitionEngine.recognize(format, sourceFileName, structureSummary);
            var suggestions = objectMapper.createArrayNode();
            for (var suggestion : batch.suggestions()) {
                var node = objectMapper.createObjectNode()
                        .put("suggestionType", suggestion.suggestionType())
                        .put("confidence", suggestion.confidence());
                node.set("payload", suggestion.payload().deepCopy());
                node.set("evidence", suggestion.evidence().deepCopy());
                suggestions.add(node);
            }
            recognition.set("ruleSuggestions", suggestions);
            // 将规则候选落成项目文档自身的内容模型（schema/mapping）。
            var compiled = compileRuleSuggestions(suggestions, format);
            return new RecognitionResult(recognition, compiled.schema(), compiled.mapping());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR,
                    "项目文档识别失败: " + exception.getMessage());
        }
    }

    /**
     * 把规则识别候选转换为项目文档的内容模型，结构对齐模板中心的
     * x-jsd-field-model + mapping 约定，但不依赖 TemplateRecognitionCompiler。
     */
    private CompiledContent compileRuleSuggestions(ArrayNode suggestions, TemplateFormat format) {
        var schema = objectMapper.createObjectNode();
        var fieldModel = objectMapper.createObjectNode();
        var fields = objectMapper.createArrayNode();
        var groups = objectMapper.createArrayNode();
        var mapping = objectMapper.createArrayNode();
        var groupIds = new java.util.LinkedHashMap<String, String>();
        for (var suggestion : suggestions) {
            var payload = suggestion.path("payload");
            var fieldName = payload.path("fieldName").asText("");
            if (fieldName.isBlank()) continue;
            var groupName = payload.path("groupName").asText("基础信息").strip();
            var groupId = groupIds.computeIfAbsent(groupName, name -> "group-"
                    + name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                    + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
            if (groupIds.size() == groups.size() + 1) {
                groups.add(objectMapper.createObjectNode().put("id", groupId).put("name", groupName));
            }
            var fieldId = payload.path("fieldId").asText("field-"
                    + java.util.UUID.randomUUID().toString().substring(0, 8));
            fields.add(objectMapper.createObjectNode()
                    .put("id", fieldId).put("fieldId", fieldId)
                    .put("name", fieldName)
                    .put("groupId", groupId)
                    .put("kind", payload.path("kind").asText("SCALAR"))
                    .put("valueType", payload.path("valueType").asText("string"))
                    .put("confidence", suggestion.path("confidence").asDouble(0.0))
                    .put("source", payload.path("source").asText("RULE")));
            var locator = payload.path("locator");
            mapping.add(objectMapper.createObjectNode()
                    .put("fieldId", fieldId)
                    .put("dataPath", payload.path("dataPath").asText("/recognized/" + fieldId))
                    .put("sheetId", locator.path("sheetId").asText(locator.path("nodeId").asText("")))
                    .put("address", locator.path("address").asText(locator.path("range").asText("")))
                    .put("locatorType", payload.path("locatorType").asText("CELL_RANGE")));
        }
        fieldModel.set("fields", fields);
        fieldModel.set("groups", groups);
        fieldModel.put("modelVersion", 4);
        fieldModel.put("source", "PROJECT_DOCUMENT_RULE");
        schema.set("x-jsd-field-model", fieldModel);
        return new CompiledContent(schema, mapping);
    }

    private record RecognitionResult(JsonNode recognition, JsonNode schema, JsonNode mapping) {
    }

    private record CompiledContent(JsonNode schema, JsonNode mapping) {
    }
}
