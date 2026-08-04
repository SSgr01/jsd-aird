package com.jsd.aird.tpl.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.application.port.TemplateImportRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.jsd.aird.tpl.domain.TemplateStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TemplateWorkspaceService {

    public static final String EDITOR_APP_VERSION = "univer-0.25.1";
    public static final String PLUGIN_MANIFEST = "preset-core-v1";

    private final TemplateRepository repository;
    private final TemplateImportRepository importRepository;
    private final TemplateRecognitionCompiler recognitionCompiler;
    private final TemplateRecognitionReviewService recognitionReviewService;
    private final JsonCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper;

    public TemplateWorkspaceService(
            TemplateRepository repository,
            TemplateImportRepository importRepository,
            TemplateRecognitionCompiler recognitionCompiler,
            TemplateRecognitionReviewService recognitionReviewService,
            JsonCanonicalizer canonicalizer,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.importRepository = importRepository;
        this.recognitionCompiler = recognitionCompiler;
        this.recognitionReviewService = recognitionReviewService;
        this.canonicalizer = canonicalizer;
        this.objectMapper = objectMapper;
    }

    public List<TemplateRepository.TemplateListItem> list(
            String keyword,
            TemplateFormat format,
            TemplateStatus status
    ) {
        return repository.findTemplates(ActorContext.required().organizationId(), keyword, format, status);
    }

    @Transactional
    public TemplateRepository.TemplateWorkspace createBlank(CreateBlankCommand command) {
        var actor = ActorContext.required();
        var templateId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var code = "TPL-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + templateId.toString().substring(0, 6).toUpperCase(Locale.ROOT);
        var schema = blankSchema(code);
        var mapping = objectMapper.createArrayNode();
        var data = objectMapper.createObjectNode();
        JsonNode snapshot = blankSnapshot(command.format(), versionId, command.name());
        if (command.importJobId() != null) {
            var importJob = importRepository.find(actor.organizationId(), command.importJobId())
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
            if (!"PARSED".equals(importJob.status())) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "模板识别尚未完成，请稍后再试");
            }
            var importedSnapshot = importJob.result().path("initialEditorSnapshot");
            if (!importedSnapshot.isObject()) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "识别结果缺少可编辑文档底稿");
            }
            snapshot = importedSnapshot.deepCopy();
            var compiled = recognitionCompiler.compile(
                    schema,
                    importRepository.listSuggestions(actor.organizationId(), command.importJobId()),
                    command.format()
            );
            schema = compiled.schema();
            mapping = compiled.mapping();
        } else {
            var compiled = recognitionCompiler.compile(schema, List.of(), command.format());
            schema = compiled.schema();
        }
        var layoutSummary = objectMapper.createObjectNode().set("initialSnapshot", snapshot);
        var schemaHash = canonicalizer.hash(schema);
        var mappingHash = canonicalizer.hash(mapping);
        var dataHash = canonicalizer.hash(data);
        var pluginHash = canonicalizer.hashText(PLUGIN_MANIFEST);
        var workspaceHash = canonicalizer.workspaceHash(
                versionId.toString(),
                schema,
                mapping,
                data,
                canonicalizer.hash(snapshot),
                EDITOR_APP_VERSION,
                pluginHash
        );

        repository.insertTemplate(new TemplateRepository.NewTemplate(
                templateId,
                actor.organizationId(),
                code,
                command.name().trim(),
                trimToNull(command.purpose()),
                trimToNull(command.category()),
                command.format(),
                actor.userId()
        ));
        repository.insertVersion(new TemplateRepository.NewVersion(
                versionId,
                templateId,
                schema,
                layoutSummary,
                command.format().snapshotKind(),
                EDITOR_APP_VERSION,
                pluginHash,
                schemaHash,
                mappingHash,
                dataHash,
                workspaceHash,
                actor.userId()
        ));
        repository.replaceMappings(versionId, command.format(), mapping);
        if (command.importJobId() != null) {
            importRepository.linkGeneratedVersion(actor.organizationId(), command.importJobId(), versionId);
        }
        repository.appendAudit(
                actor.organizationId(),
                actor.userId(),
                "TEMPLATE_DRAFT_CREATED",
                "TEMPLATE_VERSION",
                versionId,
                objectMapper.createObjectNode().put("format", command.format().name())
        );
        return get(versionId);
    }

    public TemplateRepository.TemplateWorkspace get(UUID versionId) {
        return repository.findWorkspace(ActorContext.required().organizationId(), versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "模板版本不存在"));
    }

    public List<TemplateRepository.TemplateVersionHistoryItem> versionHistory(UUID templateId) {
        return repository.findVersionHistory(ActorContext.required().organizationId(), templateId);
    }

    @Transactional
    public SaveResult saveDraft(UUID versionId, SaveDraftCommand command) {
        var actor = ActorContext.required();
        var current = get(versionId);
        if (current.status() != TemplateStatus.DRAFT) {
            throw new ApiException(ApiErrorCode.TEMPLATE_VERSION_IMMUTABLE);
        }
        if (!current.workspaceHash().equals(command.baseWorkspaceHash())) {
            throw new ApiException(
                    ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                    "保存基线已变化，请先比较本地修改与服务端草稿"
            );
        }
        // Apply review decisions first (inside this transaction), so a just-confirmed item can
        // enter the same saved draft while an unconfirmed candidate cannot slip into Mapping.
        recognitionReviewService.applyActions(
                actor.organizationId(), actor.userId(), versionId, command.recognitionActions()
        );
        recognitionReviewService.applyQualityActions(
                actor.organizationId(), actor.userId(), versionId, command.qualityActions()
        );
        validateSchema(command.schema());
        var reconciliationRequired = validateMappings(current.format(), command.mapping());
        recognitionReviewService.validateAcceptedMappings(
                actor.organizationId(), versionId, command.mapping()
        );
        validateBindingValues(command.bindingValues());
        validateStructureOperations(command.structureOperations());
        validateSnapshot(command.snapshotFileId(), command.snapshotHash());

        var schemaHash = canonicalizer.hash(command.schema());
        var mappingHash = canonicalizer.hash(command.mapping());
        var dataHash = canonicalizer.hash(command.data());
        var pluginHash = canonicalizer.hashText(command.pluginManifest());
        var workspaceHash = canonicalizer.workspaceHash(
                versionId.toString(),
                command.schema(),
                command.mapping(),
                command.data(),
                command.snapshotHash(),
                command.editorAppVersion(),
                pluginHash
        );
        var layoutSummary = objectMapper.createObjectNode()
                .put("reconciliationRequired", reconciliationRequired)
                .put("lastCommandSummary", trimToEmpty(command.clientCommandSummary()));

        var updated = repository.updateDraft(new TemplateRepository.DraftUpdate(
                actor.organizationId(),
                versionId,
                command.lockVersion(),
                command.schema(),
                layoutSummary,
                command.snapshotFileId(),
                command.snapshotHash(),
                command.editorAppVersion(),
                pluginHash,
                command.snapshotFormatVersion(),
                schemaHash,
                mappingHash,
                dataHash,
                workspaceHash,
                reconciliationRequired,
                actor.userId()
        ));
        if (updated == 0) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }
        repository.replaceMappings(versionId, current.format(), command.mapping());
        repository.appendStructureChanges(
                versionId,
                current.mappingHash(),
                mappingHash,
                command.structureOperations().stream()
                        .map(operation -> new TemplateRepository.StructureChange(
                                operation.operationId(), operation.type(), operation.sheetId(),
                                objectMapper.valueToTree(operation), operation.source()
                        ))
                        .toList(),
                actor.userId()
        );
        repository.appendAudit(
                actor.organizationId(),
                actor.userId(),
                "TEMPLATE_DRAFT_SAVED",
                "TEMPLATE_VERSION",
                versionId,
                objectMapper.createObjectNode()
                        .put("workspaceHash", workspaceHash)
                        .put("idempotencyKey", command.idempotencyKey())
                        .put("reconciliationRequired", reconciliationRequired)
                        .put("structureChangeCount", command.structureOperations().size())
        );
        if (command.snapshotFileId() != null) {
            repository.appendOutbox(
                    "FILE_OBJECT",
                    command.snapshotFileId(),
                    "FILE_ACTIVATION_REQUESTED",
                    objectMapper.createObjectNode().put("fileId", command.snapshotFileId().toString())
            );
        }
        return new SaveResult(command.lockVersion() + 1, workspaceHash, reconciliationRequired);
    }

    @Transactional
    public void publish(UUID versionId) {
        var actor = ActorContext.required();
        var workspace = get(versionId);
        if (workspace.status() != TemplateStatus.DRAFT) {
            throw new ApiException(ApiErrorCode.TEMPLATE_VERSION_IMMUTABLE);
        }
        if (workspace.snapshotFileId() == null) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "发布前必须持久化 Univer 原生快照");
        }
        if (hasRequiredFieldWithoutPosition(workspace.schema(), workspace.mapping(), workspace.format())) {
            var message = workspace.format() == TemplateFormat.DOCX
                    ? "还有必填 Word 字段未插入正文位置"
                    : "还有必填字段没有有效填写位置";
            throw new ApiException(ApiErrorCode.BINDING_INVALID, message);
        }
        if (recognitionReviewService.hasOpenConflicts(actor.organizationId(), versionId)) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID, "识别结果仍有冲突，请在识别确认中处理后再发布");
        }
        if (recognitionReviewService.hasOpenBlockingIssues(actor.organizationId(), versionId)) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID, "模板仍有会影响可靠填写的问题，请按识别确认中的提示处理");
        }
        repository.publish(actor.organizationId(), versionId, actor.userId());
        repository.appendAudit(
                actor.organizationId(),
                actor.userId(),
                "TEMPLATE_VERSION_PUBLISHED",
                "TEMPLATE_VERSION",
                versionId,
                objectMapper.createObjectNode().put("workspaceHash", workspace.workspaceHash())
        );
        repository.appendOutbox(
                "TEMPLATE_VERSION",
                versionId,
                "TEMPLATE_VERSION_PUBLISHED",
                objectMapper.createObjectNode().put("versionId", versionId.toString())
        );
    }

    private boolean hasRequiredFieldWithoutPosition(JsonNode schema, JsonNode mapping, TemplateFormat format) {
        var bindings = new java.util.HashMap<String, JsonNode>();
        if (mapping != null && mapping.isArray()) {
            for (JsonNode binding : mapping) {
                var bindingId = binding.path("bindingId").asText("");
                if (!bindingId.isBlank()) bindings.put(bindingId, binding);
            }
        }
        var fields = schema.path(TemplateRecognitionCompiler.FIELD_MODEL_KEY).path("fields");
        if (!fields.isArray()) {
            return false;
        }
        for (JsonNode field : fields) {
            if (!field.path("required").asBoolean(false)) continue;
            var bindingId = field.path("bindingId").asText("");
            var binding = bindings.get(bindingId);
            if (binding == null) return true;
            if (format == TemplateFormat.DOCX) {
                if (!StringUtils.hasText(binding.path("markerId").asText())) return true;
            } else if (!validRange(binding.path("locator").path("address").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private void validateSchema(JsonNode schema) {
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())) {
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "Schema 根节点必须是 object");
        }
        if (containsRemoteReference(schema)) {
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "不允许解析任意远程 $ref");
        }
    }

    private boolean containsRemoteReference(JsonNode node) {
        if (node.isObject()) {
            if (node.has("$ref")) {
                var reference = node.path("$ref").asText();
                if (reference.startsWith("http://") || reference.startsWith("https://")) {
                    return true;
                }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                if (containsRemoteReference(fields.next().getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsRemoteReference(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean validateMappings(TemplateFormat format, JsonNode mapping) {
        if (mapping == null || !mapping.isArray()) {
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "Mapping 必须是数组");
        }
        var bindingIds = new HashSet<String>();
        var primaryPaths = new HashSet<String>();
        var reconciliationRequired = false;
        for (JsonNode binding : mapping) {
            var bindingId = binding.path("bindingId").asText();
            var path = binding.path("dataPath").asText();
            var locatorType = binding.path("locatorType").asText();
            if (!StringUtils.hasText(bindingId) || !StringUtils.hasText(path)
                    || !StringUtils.hasText(locatorType) || !binding.path("locator").isObject()) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "Mapping 缺少 bindingId、dataPath 或 locator");
            }
            var syncDirection = binding.path("syncDirection").asText("");
            if (!java.util.Set.of("TWO_WAY", "DATA_TO_EDITOR", "EDITOR_TO_DATA")
                    .contains(syncDirection)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "Mapping 同步方向无效");
            }
            var diagnostic = binding.path("diagnostic");
            if ("FORMULA".equals(diagnostic.path("valueSource").asText())
                    && !"EDITOR_TO_DATA".equals(syncDirection)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "Excel 公式只能从工作簿同步到数据");
            }
            if ("READ_ONLY".equals(diagnostic.path("editability").asText())
                    && "TWO_WAY".equals(syncDirection)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "只读位置不能配置双向同步");
            }
            if (!bindingIds.add(bindingId)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "bindingId 必须唯一：" + bindingId);
            }
            if (binding.path("primaryBinding").asBoolean(true) && !primaryPaths.add(path)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "同一路径只能有一个主绑定：" + path);
            }
            var status = binding.path("bindingStatus").asText("VALID");
            if (format == TemplateFormat.DOCX
                    && "VALID".equals(status)
                    && !StringUtils.hasText(binding.path("markerId").asText())) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "Word 绑定必须包含稳定 markerId");
            }
            if (format == TemplateFormat.XLSX) {
                var address = binding.path("locator").path("address").asText("");
                var labelAddress = binding.path("locator").path("labelAddress").asText("");
                if (StringUtils.hasText(address) && !validRange(address)) {
                    throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "填写位置格式不正确，例如 B2 或 B7:D10");
                }
                if (StringUtils.hasText(labelAddress) && !validCell(labelAddress)) {
                    throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "标签位置格式不正确，例如 A2");
                }
            }
            var hasPosition = StringUtils.hasText(binding.path("markerId").asText())
                    || StringUtils.hasText(binding.path("locator").path("address").asText())
                    || StringUtils.hasText(binding.path("locator").path("range").asText());
            reconciliationRequired |= !"VALID".equals(status) && hasPosition;
        }
        return reconciliationRequired;
    }

    private boolean validCell(String value) {
        return value != null && value.matches("(?i)^[A-Z]{1,4}[1-9][0-9]*$");
    }

    private boolean validRange(String value) {
        return value != null && value.matches(
                "(?i)^[A-Z]{1,4}[1-9][0-9]*(?::[A-Z]{1,4}[1-9][0-9]*)?$"
        );
    }

    private void validateBindingValues(List<BindingValuePair> values) {
        for (BindingValuePair pair : values) {
            var dataHash = canonicalizer.hash(pair.dataValue());
            var editorHash = canonicalizer.hash(pair.editorValue());
            if (!dataHash.equals(editorHash)) {
                throw new ApiException(
                        ApiErrorCode.WORKBOOK_DATA_DIVERGED,
                        "字段 " + pair.dataPath() + " 的编辑器值与 JSONB 值不一致"
                );
            }
        }
    }

    @Transactional
    public TemplateRepository.TemplateWorkspace createRevision(UUID sourceVersionId) {
        var actor = ActorContext.required();
        var source = get(sourceVersionId);
        if (source.status() == TemplateStatus.DRAFT) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "草稿无需创建修订版");
        }
        if (repository.hasOpenDraft(actor.organizationId(), source.templateId())) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "该模板已有未完成草稿，请先处理现有草稿");
        }
        var versionId = UUID.randomUUID();
        var data = objectMapper.createObjectNode();
        var layoutSummary = objectMapper.createObjectNode()
                .put("reconciliationRequired", source.reconciliationRequired())
                .put("derivedFromVersionId", sourceVersionId.toString());
        var snapshotHash = StringUtils.hasText(source.snapshotHash())
                ? source.snapshotHash() : canonicalizer.hash(source.inlineSnapshot());
        var workspaceHash = canonicalizer.workspaceHash(
                versionId.toString(), source.schema(), source.mapping(), data, snapshotHash,
                source.editorAppVersion(), source.pluginManifestHash()
        );
        repository.insertRevision(new TemplateRepository.NewRevision(
                versionId, source.templateId(), sourceVersionId, source.schema(), layoutSummary,
                source.snapshotFileId(), source.snapshotHash(), source.snapshotKind(),
                source.editorAppVersion(), source.pluginManifestHash(), source.snapshotFormatVersion(),
                source.schemaHash(), source.mappingHash(), canonicalizer.hash(data), workspaceHash,
                actor.userId()
        ));
        repository.copyMappings(sourceVersionId, versionId);
        repository.appendAudit(
                actor.organizationId(), actor.userId(), "TEMPLATE_REVISION_CREATED",
                "TEMPLATE_VERSION", versionId,
                objectMapper.createObjectNode().put("derivedFromVersionId", sourceVersionId.toString())
        );
        return get(versionId);
    }

    @Transactional
    public void deleteDraft(UUID versionId) {
        var actor = ActorContext.required();
        var workspace = get(versionId);
        if (workspace.status() != TemplateStatus.DRAFT) {
            throw new ApiException(ApiErrorCode.TEMPLATE_VERSION_IMMUTABLE, "仅草稿版本可以删除");
        }
        if (repository.hasProductionOrderReferences(actor.organizationId(), versionId)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "该草稿已被生产单引用，不能删除");
        }
        repository.appendAudit(
                actor.organizationId(), actor.userId(), "TEMPLATE_DRAFT_DELETED",
                "TEMPLATE_VERSION", versionId,
                objectMapper.createObjectNode().put("templateId", workspace.templateId().toString())
        );
        if (repository.deleteDraft(actor.organizationId(), versionId) == 0) {
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "草稿状态已变化，请刷新后重试");
        }
        repository.deleteTemplateIfEmpty(actor.organizationId(), workspace.templateId());
        if (workspace.snapshotFileId() != null) {
            repository.appendOutbox(
                    "FILE_OBJECT", workspace.snapshotFileId(), "FILE_REFERENCE_RECALCULATION_REQUESTED",
                    objectMapper.createObjectNode().put("fileId", workspace.snapshotFileId().toString())
            );
        }
    }

    @Transactional
    public void retire(UUID templateId) {
        var actor = ActorContext.required();
        if (repository.retireTemplate(actor.organizationId(), templateId) == 0) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "模板没有可停用的已发布版本");
        }
        repository.appendAudit(
                actor.organizationId(), actor.userId(), "TEMPLATE_RETIRED", "TEMPLATE", templateId,
                objectMapper.createObjectNode()
        );
        repository.appendOutbox(
                "TEMPLATE", templateId, "TEMPLATE_RETIRED",
                objectMapper.createObjectNode().put("templateId", templateId.toString())
        );
    }

    private void validateStructureOperations(List<StructureOperation> operations) {
        var allowed = java.util.Set.of(
                "INSERT_ROWS", "DELETE_ROWS", "INSERT_COLUMNS", "DELETE_COLUMNS", "RENAME_SHEET"
        );
        var ids = new HashSet<UUID>();
        for (var operation : operations) {
            if (operation.operationId() == null || !ids.add(operation.operationId())
                    || !allowed.contains(operation.type()) || !StringUtils.hasText(operation.sheetId())
                    || !java.util.Set.of("CUSTOMER", "AI").contains(operation.source())) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "工作簿结构操作无效");
            }
            if (!"RENAME_SHEET".equals(operation.type())
                    && (operation.index() == null || operation.index() < 1
                    || operation.count() == null || operation.count() < 1)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "行列结构操作缺少有效位置或数量");
            }
            if ("RENAME_SHEET".equals(operation.type())
                    && !StringUtils.hasText(operation.nextSheetName())) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "工作表重命名缺少新名称");
            }
        }
    }

    private void validateSnapshot(UUID fileId, String expectedHash) {
        if (fileId == null || !StringUtils.hasText(expectedHash)) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "保存草稿必须提交已暂存的原生快照");
        }
        var file = repository.findFile(ActorContext.required().organizationId(), fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY));
        if ("DELETED".equals(file.status()) || !file.sha256().equals(expectedHash)) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "快照哈希与对象存储登记不一致");
        }
    }

    private ObjectNode blankSchema(String code) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("$id", "urn:jsd:template:" + code.toLowerCase(Locale.ROOT) + ":v1");
        schema.put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        return schema;
    }

    private JsonNode blankSnapshot(TemplateFormat format, UUID versionId, String name) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("id", versionId.toString());
        if (format == TemplateFormat.XLSX) {
            snapshot.put("snapshotFormatVersion", 3);
            snapshot.put("name", name);
            snapshot.set("sheetOrder", objectMapper.createArrayNode().add("sheet-1"));
            ObjectNode sheet = objectMapper.createObjectNode();
            sheet.put("id", "sheet-1");
            sheet.put("name", "Sheet1");
            sheet.put("rowCount", 200);
            sheet.put("columnCount", 26);
            sheet.set("cellData", objectMapper.createObjectNode());
            snapshot.set("sheets", objectMapper.createObjectNode().set("sheet-1", sheet));
        } else {
            snapshot.put("title", name);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("dataStream", "\r\n");
            body.set("textRuns", objectMapper.createArrayNode());
            body.set("paragraphs", objectMapper.createArrayNode()
                    .add(objectMapper.createObjectNode().put("startIndex", 0)));
            body.set("customRanges", objectMapper.createArrayNode());
            snapshot.set("body", body);
            var documentStyle = objectMapper.createObjectNode();
            documentStyle.set("pageSize", objectMapper.createObjectNode()
                    .put("width", 595)
                    .put("height", 842));
            documentStyle.put("marginTop", 72)
                    .put("marginRight", 72)
                    .put("marginBottom", 72)
                    .put("marginLeft", 72);
            snapshot.set("documentStyle", documentStyle);
        }
        return snapshot;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record CreateBlankCommand(
            String name,
            String purpose,
            String category,
            TemplateFormat format,
            UUID importJobId
    ) {
    }

    public record BindingValuePair(String dataPath, JsonNode dataValue, JsonNode editorValue) {
    }

    public record StructureOperation(
            UUID operationId,
            String type,
            String sheetId,
            String sheetName,
            Integer index,
            Integer count,
            String previousSheetName,
            String nextSheetName,
            String source
    ) {
    }

    public record SaveDraftCommand(
            long lockVersion,
            String baseWorkspaceHash,
            JsonNode schema,
            ArrayNode mapping,
            JsonNode data,
            UUID snapshotFileId,
            String snapshotHash,
            String editorAppVersion,
            String pluginManifest,
            int snapshotFormatVersion,
            String clientCommandSummary,
            String idempotencyKey,
            List<BindingValuePair> bindingValues,
            List<TemplateRecognitionReviewService.RecognitionAction> recognitionActions,
            List<TemplateRecognitionReviewService.QualityAction> qualityActions,
            List<StructureOperation> structureOperations
    ) {
        public SaveDraftCommand {
            bindingValues = bindingValues == null ? List.of() : List.copyOf(bindingValues);
            recognitionActions = recognitionActions == null ? List.of() : List.copyOf(recognitionActions);
            qualityActions = qualityActions == null ? List.of() : List.copyOf(qualityActions);
            structureOperations = structureOperations == null ? List.of() : List.copyOf(structureOperations);
        }
    }

    public record SaveResult(long lockVersion, String workspaceHash, boolean reconciliationRequired) {
    }
}
