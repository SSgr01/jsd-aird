package com.jsd.aird.tpl.application;

import java.time.LocalDate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.application.port.TemplateImportRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.jsd.aird.tpl.domain.TemplateStatus;
import com.jsd.aird.tpl.application.port.BlankWordDocumentFactory;
import com.jsd.aird.tpl.application.port.WordOoxmlPatcher;
import com.jsd.aird.tpl.application.port.WordDocumentParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

@Service
public class TemplateWorkspaceService {

    public static final String EDITOR_APP_VERSION = "univer-0.25.1";
    public static final String PLUGIN_MANIFEST = "preset-core-v1";

    private final TemplateRepository repository;
    private final TemplateImportRepository importRepository;
    private final TemplateRecognitionCompiler recognitionCompiler;
    private final TemplateRecognitionReviewService recognitionReviewService;
    private final StandardFieldService standardFieldService;
    private final JsonCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper;
    private final FileObjectRepository fileRepository;
    private final ObjectStorage objectStorage;
    private final BlankWordDocumentFactory blankWordDocumentFactory;
    private final WordOoxmlPatcher wordOoxmlPatchService;
    private final WordDocumentParser wordDocumentParser;
    private final TemplateImportContractCompiler importContractCompiler;
    private final TransactionTemplate transactionTemplate;
    private final String storageBucket;

    public TemplateWorkspaceService(
            TemplateRepository repository,
            TemplateImportRepository importRepository,
            TemplateRecognitionCompiler recognitionCompiler,
            TemplateRecognitionReviewService recognitionReviewService,
            StandardFieldService standardFieldService,
            JsonCanonicalizer canonicalizer,
            ObjectMapper objectMapper,
            FileObjectRepository fileRepository,
            ObjectStorage objectStorage,
            BlankWordDocumentFactory blankWordDocumentFactory,
            WordOoxmlPatcher wordOoxmlPatchService,
            WordDocumentParser wordDocumentParser,
            TemplateImportContractCompiler importContractCompiler,
            PlatformTransactionManager transactionManager,
            @Value("${app.storage.bucket}") String storageBucket
    ) {
        this.repository = repository;
        this.importRepository = importRepository;
        this.recognitionCompiler = recognitionCompiler;
        this.recognitionReviewService = recognitionReviewService;
        this.standardFieldService = standardFieldService;
        this.canonicalizer = canonicalizer;
        this.objectMapper = objectMapper;
        this.fileRepository = fileRepository;
        this.objectStorage = objectStorage;
        this.blankWordDocumentFactory = blankWordDocumentFactory;
        this.wordOoxmlPatchService = wordOoxmlPatchService;
        this.wordDocumentParser = wordDocumentParser;
        this.importContractCompiler = importContractCompiler;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.storageBucket = storageBucket;
    }

    public List<TemplateRepository.TemplateListItem> list(
            String keyword,
            TemplateFormat format,
            TemplateStatus status
    ) {
        return repository.findTemplates(ActorContext.required().organizationId(), keyword, format, status);
    }

    public TemplateRepository.TemplatePage list(TemplateListQuery query) {
        var actor = ActorContext.required();
        return repository.findTemplates(new TemplateRepository.TemplateQuery(
                actor.organizationId(), query.keyword(), query.categoryId(), query.uncategorized(), query.format(), query.status(),
                query.createdBy(), query.updatedFrom(), query.updatedTo(), query.sortBy(), query.sortDirection(),
                query.page(), query.size()));
    }

    public TemplateRepository.TemplateFacetSummary facets(TemplateFacetQuery query) {
        var actor = ActorContext.required();
        return repository.findTemplateFacets(new TemplateRepository.TemplateFacetQuery(
                actor.organizationId(), query.keyword(), query.format(), query.status(), query.createdBy(),
                query.updatedFrom(), query.updatedTo()));
    }

    public List<TemplateRepository.TemplateCreatorOption> filterOptions() {
        return repository.findTemplateCreators(ActorContext.required().organizationId());
    }

    public byte[] exportCsv(TemplateListQuery query, Set<UUID> selectedTemplateIds) {
        var rows = new ArrayList<TemplateRepository.TemplateListItem>();
        var page = 1;
        while (true) {
            var current = list(new TemplateListQuery(query.keyword(), query.categoryId(), query.uncategorized(), query.format(),
                    query.status(), query.createdBy(), query.updatedFrom(), query.updatedTo(),
                    query.sortBy(), query.sortDirection(), page, 200));
            rows.addAll(current.items());
            if (page >= current.totalPages()) break;
            page++;
        }
        var selected = selectedTemplateIds == null ? Set.<UUID>of() : selectedTemplateIds;
        var builder = new StringBuilder("\uFEFF编码,名称,分类,格式,状态,当前版本,草稿标志,创建人,更新时间\r\n");
        rows.stream().filter(item -> selected.isEmpty() || selected.contains(item.templateId())).forEach(item ->
                builder.append(csv(item.templateCode())).append(',')
                        .append(csv(item.name())).append(',')
                        .append(csv(item.category())).append(',')
                        .append(csv(item.format().name())).append(',')
                        .append(csv(item.status().name())).append(',')
                        .append(csv("V" + item.versionNo())).append(',')
                        .append(csv(item.hasDraft() ? "有草稿" : "无草稿")).append(',')
                        .append(csv(item.createdByName())).append(',')
                        .append(csv(item.updatedAt().toString())).append("\r\n"));
        return builder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String csv(String value) {
        var normalized = value == null ? "" : value;
        if (!normalized.isBlank() && "=+-@\t\r".indexOf(normalized.charAt(0)) >= 0) normalized = "'" + normalized;
        return '"' + normalized.replace("\"", "\"\"") + '"';
    }

    @Transactional
    public List<TemplateRepository.TemplateCategoryItem> listCategories() {
        return repository.findCategories(ActorContext.required().organizationId());
    }

    @Transactional
    public TemplateRepository.TemplateCategoryItem createCategory(String name, String description) {
        var actor = ActorContext.required();
        var normalized = validCategoryName(name);
        if (repository.categoryNameExists(actor.organizationId(), normalized, null)) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "分类名称已存在");
        }
        var id = UUID.randomUUID();
        var sortOrder = repository.findCategories(actor.organizationId()).stream()
                .mapToInt(TemplateRepository.TemplateCategoryItem::sortOrder).max().orElse(-1) + 1;
        repository.insertCategory(id, actor.organizationId(), normalized, normalizeDescription(description), sortOrder, actor.userId());
        repository.appendAudit(actor.organizationId(), actor.userId(), "TEMPLATE_CATEGORY_CREATED",
                "TEMPLATE_CATEGORY", id, objectMapper.createObjectNode().put("name", normalized));
        return repository.findCategory(actor.organizationId(), id).orElseThrow();
    }

    @Transactional
    public TemplateRepository.TemplateCategoryItem renameCategory(UUID categoryId, String name, String description) {
        var actor = ActorContext.required();
        var normalized = validCategoryName(name);
        if (repository.categoryNameExists(actor.organizationId(), normalized, categoryId)) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "分类名称已存在");
        }
        if (repository.renameCategory(actor.organizationId(), categoryId, normalized, normalizeDescription(description)) == 0) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "分类不存在");
        }
        repository.appendAudit(actor.organizationId(), actor.userId(), "TEMPLATE_CATEGORY_UPDATED",
                "TEMPLATE_CATEGORY", categoryId, objectMapper.createObjectNode().put("name", normalized));
        return repository.findCategory(actor.organizationId(), categoryId).orElseThrow();
    }

    private String normalizeDescription(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        var normalized = value.trim();
        if (normalized.length() > 240) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类简介不能超过 240 个字符");
        }
        return normalized;
    }

    @Transactional
    public void deleteCategory(UUID categoryId, UUID replacementCategoryId) {
        var actor = ActorContext.required();
        var organizationId = actor.organizationId();
        if (categoryId.equals(replacementCategoryId)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不能迁移到当前分类");
        }
        repository.findCategory(organizationId, categoryId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "分类不存在"));
        if (replacementCategoryId != null) repository.findCategory(organizationId, replacementCategoryId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "目标分类不存在"));
        repository.deleteCategory(organizationId, categoryId, replacementCategoryId);
        repository.appendAudit(organizationId, actor.userId(), "TEMPLATE_CATEGORY_DELETED",
                "TEMPLATE_CATEGORY", categoryId, objectMapper.createObjectNode()
                        .put("replacementCategoryId", replacementCategoryId == null ? "" : replacementCategoryId.toString()));
    }

    @Transactional
    public void assignTemplateCategory(UUID templateId, UUID categoryId) {
        var actor = ActorContext.required();
        var organizationId = actor.organizationId();
        if (categoryId != null) repository.findCategory(organizationId, categoryId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "分类不存在"));
        if (repository.assignTemplateCategory(organizationId, templateId, categoryId) == 0) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "模板不存在");
        }
        repository.appendAudit(organizationId, actor.userId(), "TEMPLATE_CATEGORY_CHANGED", "TEMPLATE", templateId,
                objectMapper.createObjectNode().put("categoryId", categoryId == null ? "" : categoryId.toString()));
    }

    private String validCategoryName(String name) {
        var normalized = trimToNull(name);
        if (normalized == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类名称不能为空");
        if (normalized.length() > 120) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分类名称不能超过 120 个字符");
        if ("未分类".equals(normalized) || "全部模板".equals(normalized)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "该名称为系统保留名称");
        }
        return normalized;
    }

    @Transactional
    public TemplateRepository.TemplateWorkspace createBlank(CreateBlankCommand command) {
        var actor = ActorContext.required();
        var normalizedCategory = trimToNull(command.category());
        if (normalizedCategory == null && command.importJobId() != null) {
            normalizedCategory = importRepository.find(actor.organizationId(), command.importJobId())
                    .map(TemplateImportRepository.ImportJobView::categoryName).map(this::trimToNull).orElse(null);
        }
        if (normalizedCategory != null) repository.ensureCategory(
                actor.organizationId(), validCategoryName(normalizedCategory), actor.userId());
        var templateId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var code = "TPL-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + templateId.toString().substring(0, 6).toUpperCase(Locale.ROOT);
        var schema = blankSchema(code);
        var mapping = objectMapper.createArrayNode();
        var data = objectMapper.createObjectNode();
        JsonNode snapshot;
        ObjectNode blankWordDocument = null;
        JsonNode blankWordStructure = null;
        UUID blankWordFileId = null;
        if (command.format() == TemplateFormat.DOCX && command.importJobId() == null) {
            var blankDocx = blankWordDocumentFactory.create(command.name());
            var parsed = wordDocumentParser.parse(new ByteArrayInputStream(blankDocx));
            snapshot = parsed.initialEditorSnapshot().deepCopy();
            blankWordStructure = parsed.structureSummary().path("documentIR").deepCopy();
            if (!snapshot.isObject() || !blankWordStructure.isObject()) {
                throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED,
                        "空白 Word 原生工件解析结果无效");
            }
            var staged = stageWordDocument(blankDocx, command.name(), actor);
            blankWordFileId = staged.id();
            blankWordDocument = wordDocument(staged.id(), staged.sha256(), blankWordStructure);
        } else {
            snapshot = blankSnapshot(command.format(), versionId, command.name());
        }
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
            attachStructureFingerprints(mapping, importJob.structureSummary());
        } else {
            var compiled = recognitionCompiler.compile(schema, List.of(), command.format());
            schema = compiled.schema();
        }
        var layoutSummary = objectMapper.createObjectNode();
        layoutSummary.set("initialSnapshot", snapshot);
        if (blankWordDocument != null) {
            layoutSummary.set("documentStructure", blankWordStructure);
            layoutSummary.set("wordDocument", blankWordDocument);
        }
        if (command.format() == TemplateFormat.DOCX && command.importJobId() != null) {
            var importJob = importRepository.find(actor.organizationId(), command.importJobId())
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务不存在"));
            var documentIr = java.util.Optional.of(importJob)
                    .map(TemplateImportRepository.ImportJobView::structureSummary)
                    .map(summary -> summary.path("documentIR"))
                    .filter(JsonNode::isObject)
                    .orElse(null);
            if (documentIr != null) {
                var sourceHash = fileRepository.find(actor.organizationId(), importJob.sourceFileId())
                        .map(FileObjectRepository.FileObject::sha256)
                        .orElse(canonicalizer.hash(documentIr));
                layoutSummary.put("sourceImportJobId", command.importJobId().toString());
                layoutSummary.set("documentStructure", documentIr.deepCopy());
                layoutSummary.set("wordDocument", objectMapper.createObjectNode()
                        .put("sourceDocxFileId", importJob.sourceFileId().toString())
                        .put("workingDocxFileId", importJob.sourceFileId().toString())
                        .put("documentHash", sourceHash)
                        .put("structureHash", documentIr.path("structureHash").asText(""))
                        .put("structureVersion", documentIr.path("structureVersion").asInt(1))
                        .put("patchSequence", 0)
                        .put("state", "WORKING"));
            }
        }
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
                normalizedCategory,
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
        if (blankWordFileId != null) {
            requestFileActivation(blankWordFileId);
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
    public void renameTemplate(UUID templateId, String name) {
        var actor = ActorContext.required();
        var normalized = trimToNull(name);
        if (normalized == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "模板名称不能为空");
        if (normalized.length() > 160) throw new ApiException(ApiErrorCode.BAD_REQUEST, "模板名称不能超过 160 个字符");
        if (repository.renameTemplate(actor.organizationId(), templateId, normalized) == 0) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "模板不存在");
        }
        repository.appendAudit(actor.organizationId(), actor.userId(), "TEMPLATE_RENAMED", "TEMPLATE", templateId,
                objectMapper.createObjectNode().put("name", normalized));
    }

    @Transactional
    public TemplateRepository.TemplateWorkspace copyVersion(UUID sourceVersionId, String requestedName, UUID categoryId) {
        var actor = ActorContext.required();
        var source = get(sourceVersionId);
        var sourceTemplate = repository.findTemplateSummary(actor.organizationId(), source.templateId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "源模板不存在"));
        var effectiveCategoryId = categoryId == null ? sourceTemplate.categoryId() : categoryId;
        var category = effectiveCategoryId == null ? null : repository.findCategory(actor.organizationId(), effectiveCategoryId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "分类不存在"));
        var templateId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var code = "TPL-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + templateId.toString().substring(0, 6).toUpperCase(Locale.ROOT);
        var name = trimToNull(requestedName);
        if (name == null) name = source.name() + " - 副本";
        if (name.length() > 160) throw new ApiException(ApiErrorCode.BAD_REQUEST, "模板名称不能超过 160 个字符");
        var data = source.data().isObject() ? source.data().deepCopy() : objectMapper.createObjectNode();
        var layout = objectMapper.createObjectNode()
                .put("copiedFromVersionId", sourceVersionId.toString())
                .put("derivedFromVersionId", sourceVersionId.toString());
        if (source.inlineSnapshot().isObject()) layout.set("initialSnapshot", source.inlineSnapshot().deepCopy());
        if (source.documentStructure().isObject()) layout.set("documentStructure", source.documentStructure().deepCopy());
        if (source.wordDocument().isObject()) layout.set("wordDocument", source.wordDocument().deepCopy());
        var workspaceHash = canonicalizer.workspaceHash(versionId.toString(), source.schema(), source.mapping(), data,
                source.snapshotHash(), source.editorAppVersion(), source.pluginManifestHash());
        repository.insertTemplate(new TemplateRepository.NewTemplate(templateId, actor.organizationId(), code, name,
                category == null ? null : category.name(), source.format(), actor.userId()));
        repository.insertCopiedVersion(new TemplateRepository.NewRevision(
                versionId, templateId, sourceVersionId, source.schema(), layout, source.snapshotFileId(),
                source.snapshotHash(), source.snapshotKind(), source.editorAppVersion(), source.pluginManifestHash(),
                source.snapshotFormatVersion(), source.schemaHash(), source.mappingHash(), canonicalizer.hash(data),
                workspaceHash, actor.userId()));
        // A published or historical version is an already validated immutable
        // snapshot. Its recognition item ids belong to the source version's
        // run and must not become a publication requirement of the copy.
        // Draft copies retain the references so copying cannot bypass review.
        repository.copyMappings(sourceVersionId, versionId, source.status() == TemplateStatus.DRAFT);
        var contract = importContractCompiler.compile(layout, source.schema(), source.mapping());
        repository.saveImportContract(actor.organizationId(), versionId,
                contract.importContractVersion(), contract.layoutStructureVersion(),
                contract.contractHash(), contract.contract(), actor.userId());
        repository.appendAudit(actor.organizationId(), actor.userId(), "TEMPLATE_COPIED", "TEMPLATE", templateId,
                objectMapper.createObjectNode().put("sourceVersionId", sourceVersionId.toString()));
        return get(versionId);
    }

    @Transactional
    public TemplateRepository.TemplateWorkspace rollback(UUID sourceVersionId) {
        var source = get(sourceVersionId);
        if (source.status() == TemplateStatus.DRAFT) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "草稿版本不能作为回退快照");
        }
        return createRevision(sourceVersionId);
    }

    public List<BatchActionResult> batch(BatchActionCommand command) {
        var results = new ArrayList<BatchActionResult>();
        for (var item : command.items()) {
            try {
                transactionTemplate.executeWithoutResult(ignored -> {
                    switch (command.action()) {
                        case "COPY" -> copyVersion(item.versionId(), item.name(), command.categoryId());
                        case "MOVE" -> assignTemplateCategory(item.templateId(), command.categoryId());
                        case "DELETE_DRAFT" -> deleteDraft(item.versionId());
                        case "RETIRE" -> retire(item.templateId());
                        default -> throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的批量操作");
                    }
                });
                results.add(new BatchActionResult(item.templateId(), item.versionId(), true, null));
            } catch (RuntimeException exception) {
                results.add(new BatchActionResult(item.templateId(), item.versionId(), false,
                        exception.getMessage() == null ? "操作失败" : exception.getMessage()));
            }
        }
        return List.copyOf(results);
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
        JsonNode normalizedSchema;
        JsonNode normalizedMapping;
        boolean reconciliationRequired;
        if (current.format() == TemplateFormat.DOCX) {
            normalizedSchema = normalizeWordSchema(command.schema());
            normalizedMapping = objectMapper.createArrayNode();
            validateSchema(normalizedSchema);
            reconciliationRequired = false;
        } else {
            normalizedSchema = normalizeFieldGroups(command.schema());
            normalizedSchema = standardFieldService.normalizeDraftFields(normalizedSchema);
            normalizedMapping = normalizeMapping(command.mapping(), normalizedSchema);
            validateSchema(normalizedSchema);
            standardFieldService.validateFormalFields(normalizedSchema);
            reconciliationRequired = validateMappings(current.format(), normalizedMapping);
            recognitionReviewService.validateAcceptedMappings(
                    actor.organizationId(), versionId, normalizedMapping
            );
        }
        validateBindingValues(command.bindingValues());
        validateStructureOperations(command.structureOperations());
        if (current.format() == TemplateFormat.XLSX
                || (current.format() == TemplateFormat.DOCX && command.snapshotFileId() != null)) {
            validateSnapshot(command.snapshotFileId(), command.snapshotHash(), current.format());
        }
        var wordDocument = current.wordDocument().isObject()
                ? (ObjectNode) current.wordDocument().deepCopy() : null;
        UUID stagedWordFileId = null;
        var documentStructure = current.documentStructure().isObject()
                ? current.documentStructure().deepCopy() : current.documentStructure();
        if (current.format() == TemplateFormat.DOCX && command.wordPatch() != null
                && !command.wordPatch().isEmpty()) {
            if (wordDocument == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "Word 原生工件不存在");
            if (!StringUtils.hasText(command.wordPatchBaseHash())
                    || !command.wordPatchBaseHash().equals(wordDocument.path("documentHash").asText())) {
                throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                        "Word 基准文件已变化，请重新加载后再保存");
            }
            var structureHash = current.documentStructure().path("structureHash").asText("");
            for (var operation : command.wordPatch()) {
                if (operation.has("baseStructureHash")
                        && !operation.path("baseStructureHash").asText().equals(structureHash)) {
                    throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                            "Word 结构基线已变化，请重新加载后再保存");
                }
            }
            var currentFileId = UUID.fromString(wordDocument.path("workingDocxFileId")
                    .asText(wordDocument.path("sourceDocxFileId").asText("")));
            var sourceFile = fileRepository.find(actor.organizationId(), currentFileId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "Word 原生工件不存在"));
            try (var stored = objectStorage.get(sourceFile.objectKey())) {
                var patched = wordOoxmlPatchService.apply(stored.stream().readAllBytes(), command.wordPatch());
                var parsed = wordDocumentParser.parse(new ByteArrayInputStream(patched));
                documentStructure = parsed.structureSummary().path("documentIR").deepCopy();
                var staged = stageWordDocument(patched, sourceFile.originalName(), actor);
                stagedWordFileId = staged.id();
                wordDocument.put("workingDocxFileId", staged.id().toString());
                wordDocument.put("documentHash", staged.sha256());
                wordDocument.put("patchSequence", wordDocument.path("patchSequence").asInt(0)
                        + command.wordPatch().size());
                wordDocument.put("lastPatchCount", command.wordPatch().size());
                wordDocument.put("lastPatchSummary", command.wordPatch().toString());
                wordDocument.put("structureVersion", documentStructure.path("structureVersion").asInt(1));
                wordDocument.put("structureHash", documentStructure.path("structureHash").asText(""));
                wordDocument.put("state", "WORKING");
            } catch (ApiException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "Word 原生工件写入失败");
            }
        }
        if (current.format() == TemplateFormat.DOCX && command.snapshotFileId() != null
                && (command.wordPatch() == null || command.wordPatch().isEmpty())) {
            if (wordDocument == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "Word 原生工件不存在");
            var currentFileId = UUID.fromString(wordDocument.path("workingDocxFileId")
                    .asText(wordDocument.path("sourceDocxFileId").asText("")));
            var currentFile = fileRepository.find(actor.organizationId(), currentFileId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "Word 原生工件不存在"));
            var snapshotFile = fileRepository.find(actor.organizationId(), command.snapshotFileId())
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "Word 编辑快照不存在"));
             try (var source = objectStorage.get(currentFile.objectKey());
                  var stagedSnapshot = objectStorage.get(snapshotFile.objectKey())) {
                 var snapshot = objectMapper.readTree(stagedSnapshot.stream().readAllBytes());
                 requireCurrentWordSnapshot(snapshot);
                 var patched = wordOoxmlPatchService.applySnapshot(source.stream().readAllBytes(), snapshot);
                 var parsed = wordDocumentParser.parse(new ByteArrayInputStream(patched));
                documentStructure = parsed.structureSummary().path("documentIR").deepCopy();
                var staged = stageWordDocument(patched, currentFile.originalName(), actor);
                stagedWordFileId = staged.id();
                wordDocument.put("workingDocxFileId", staged.id().toString());
                wordDocument.put("documentHash", staged.sha256());
                 wordDocument.put("lastPatchCount", 0);
                 wordDocument.put("lastPatchSummary", "UNIVER_DOCS_REBUILD");
                 wordDocument.put("conversionMode", "UNIVER_DOCS_REBUILD");
                 wordDocument.put("exporterVersion", "word-univer-export-v1");
                 wordDocument.put("unsupportedFeatureCount", snapshot.path("wordImport").path("unsupportedFeatureCount").asInt(0));
                 wordDocument.put("structureVersion", documentStructure.path("structureVersion").asInt(1));
                wordDocument.put("structureHash", documentStructure.path("structureHash").asText(""));
                wordDocument.put("state", "WORKING");
            } catch (ApiException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "Word 编辑快照回写失败");
            }
        }
         if (current.format() == TemplateFormat.DOCX && wordDocument != null
                 && command.snapshotFileId() != null) {
             wordDocument.put("editorMode", "UNIVER_DOCS");
             wordDocument.put("conversionMode", "UNIVER_DOCS_REBUILD");
            wordDocument.put("editorSnapshotFileId", command.snapshotFileId().toString());
            wordDocument.put("editorSnapshotHash", command.snapshotHash());
            wordDocument.put("documentRevision", wordDocument.path("documentRevision").asInt(0) + 1);
        }

        var schemaHash = canonicalizer.hash(normalizedSchema);
        var mappingHash = canonicalizer.hash(normalizedMapping);
        var dataHash = canonicalizer.hash(command.data());
        var pluginHash = canonicalizer.hashText(command.pluginManifest());
        var workspaceHash = canonicalizer.workspaceHash(
                versionId.toString(),
                normalizedSchema,
                normalizedMapping,
                command.data(),
                command.snapshotHash(),
                command.editorAppVersion(),
                pluginHash
        );
        var layoutSummary = objectMapper.createObjectNode()
                .put("reconciliationRequired", reconciliationRequired)
                .put("lastCommandSummary", trimToEmpty(command.clientCommandSummary()));
        if (documentStructure != null && documentStructure.isObject()) {
            layoutSummary.set("documentStructure", documentStructure);
        }
        if (wordDocument != null) layoutSummary.set("wordDocument", wordDocument);

        var updated = repository.updateDraft(new TemplateRepository.DraftUpdate(
                actor.organizationId(),
                versionId,
                command.lockVersion(),
                normalizedSchema,
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
        repository.replaceMappings(versionId, current.format(), normalizedMapping);
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
            requestFileActivation(command.snapshotFileId());
        }
        if (stagedWordFileId != null) requestFileActivation(stagedWordFileId);
        return new SaveResult(command.lockVersion() + 1, workspaceHash, reconciliationRequired,
                normalizedSchema.deepCopy(), normalizedMapping.deepCopy(),
                wordDocument == null ? null : wordDocument.deepCopy(),
                documentStructure == null ? null : documentStructure.deepCopy());
    }

    private ObjectNode normalizeFieldGroups(JsonNode schema) {
        var normalized = (ObjectNode) schema.deepCopy();
        var fieldModel = normalized.path(TemplateRecognitionCompiler.FIELD_MODEL_KEY);
        if (!(fieldModel instanceof ObjectNode model)) return normalized;
        var blocks = new java.util.HashMap<String, JsonNode>();
        for (var block : model.path("blocks")) {
            blocks.put(block.path("blockId").asText(""), block);
        }
        var oldNames = new java.util.HashMap<String, String>();
        for (var group : model.path("groups")) oldNames.put(group.path("id").asText(""), group.path("name").asText(""));
        var normalizedGroups = objectMapper.createArrayNode();
        var groupIds = new java.util.LinkedHashMap<String, String>();
        for (var fieldNode : model.withArray("fields")) {
            if (!(fieldNode instanceof ObjectNode field)) continue;
            var block = blocks.get(field.path("blockId").asText(""));
            var fallback = GroupNameNormalizer.infer(
                    block == null ? "" : block.path("type").asText(""),
                    block == null ? field.path("name").asText("") : block.path("businessName").asText("")
            );
            var desired = block == null
                    ? GroupNameNormalizer.normalizeCustomerDefined(oldNames.get(field.path("groupId").asText("")))
                    : GroupNameNormalizer.normalizeModelSuggestion(block.path("groupName").asText(""))
                    .orElse(fallback);
            var desiredId = groupIds.computeIfAbsent(desired, name -> "group-"
                    + GroupNameNormalizer.code(name).toLowerCase(Locale.ROOT) + "-"
                    + RecognitionIdentity.shortHash(name, 8));
            field.put("groupId", desiredId);
        }
        groupIds.forEach((name, id) -> normalizedGroups.add(objectMapper.createObjectNode()
                .put("id", id).put("name", name).put("groupCode", GroupNameNormalizer.code(name))
                .put("order", normalizedGroups.size())));
        if (!normalizedGroups.isEmpty()) model.set("groups", normalizedGroups);
        return normalized;
    }

    private JsonNode normalizeMapping(JsonNode mapping, JsonNode schema) {
        if (mapping == null || !mapping.isArray()) return mapping;
        var result = MappingPathNormalizer.normalize(mapping);
        var model = schema.path(TemplateRecognitionCompiler.FIELD_MODEL_KEY);
        var names = new java.util.HashMap<String, String>();
        var groups = new java.util.HashMap<String, String>();
        for (var group : model.path("groups")) groups.put(group.path("id").asText(""), group.path("name").asText(""));
        for (var field : model.path("fields")) names.put(field.path("fieldId").asText(""),
                groups.getOrDefault(field.path("groupId").asText(""), GroupNameNormalizer.BASIC_INFORMATION));
        for (var binding : result) {
            if (!(binding instanceof ObjectNode object)) continue;
            var groupName = names.get(object.path("fieldId").asText(""));
            if (groupName != null) object.withObject("diagnostic").put("groupName", groupName);
        }
        return result;
    }

    @Transactional
    public void publish(UUID versionId) {
        var actor = ActorContext.required();
        var workspace = get(versionId);
        if (workspace.status() != TemplateStatus.DRAFT) {
            throw new ApiException(ApiErrorCode.TEMPLATE_VERSION_IMMUTABLE);
        }
        if (workspace.format() == TemplateFormat.XLSX && workspace.snapshotFileId() == null) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "发布前必须持久化 Excel 原生快照");
        }
        if (workspace.format() == TemplateFormat.DOCX
                && (!workspace.wordDocument().isObject()
                || !StringUtils.hasText(workspace.wordDocument().path("workingDocxFileId").asText(
                        workspace.wordDocument().path("sourceDocxFileId").asText(""))))) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "发布前必须准备 Word 原生工件");
        }
        if (workspace.format() == TemplateFormat.XLSX
                && hasRequiredFieldWithoutPosition(workspace.schema(), workspace.mapping(), workspace.format())) {
            var message = workspace.format() == TemplateFormat.DOCX
                    ? "还有必填 Word 字段未插入正文位置"
                    : "还有必填字段没有有效填写位置";
            throw new ApiException(ApiErrorCode.BINDING_INVALID, message);
        }
        if (workspace.format() == TemplateFormat.XLSX
                && recognitionReviewService.hasIncompleteRecognition(actor.organizationId(), versionId)) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID,
                    "识别结果尚未完成结构确认或人工复核，暂不能发布");
        }
        if (workspace.format() == TemplateFormat.XLSX
                && recognitionReviewService.hasOpenConflicts(actor.organizationId(), versionId)) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID, "识别结果仍有冲突，请在识别确认中处理后再发布");
        }
        if (workspace.format() == TemplateFormat.XLSX
                && recognitionReviewService.hasOpenBlockingIssues(actor.organizationId(), versionId)) {
            throw new ApiException(ApiErrorCode.BINDING_INVALID, "模板仍有会影响可靠填写的问题，请按识别确认中的提示处理");
        }
        if (workspace.format() == TemplateFormat.DOCX && workspace.wordDocument().isObject()) {
            var wordDocument = (ObjectNode) workspace.wordDocument().deepCopy();
            var working = wordDocument.path("workingDocxFileId").asText(
                    wordDocument.path("sourceDocxFileId").asText(""));
            if (working.isBlank()) {
                throw new ApiException(ApiErrorCode.FILE_NOT_READY, "发布前必须准备 Word 原生工件");
            }
            wordDocument.put("publishedDocxFileId", working);
            wordDocument.put("state", "PUBLISHED");
            repository.updatePublishedWordDocument(actor.organizationId(), versionId, wordDocument);
        }
        var importContract = importContractCompiler.compile(
                publishedLayoutSummary(workspace),
                workspace.schema(), workspace.mapping());
        repository.saveImportContract(actor.organizationId(), versionId,
                importContract.importContractVersion(), importContract.layoutStructureVersion(),
                importContract.contractHash(), importContract.contract(), actor.userId());
        repository.publish(actor.organizationId(), versionId, actor.userId());
        repository.appendAudit(
                actor.organizationId(),
                actor.userId(),
                "TEMPLATE_VERSION_PUBLISHED",
                "TEMPLATE_VERSION",
                versionId,
                objectMapper.createObjectNode().put("workspaceHash", workspace.workspaceHash())
                        .put("importContractVersion", importContract.importContractVersion())
                        .put("contractHash", importContract.contractHash())
        );
        repository.appendOutbox(
                "TEMPLATE_VERSION",
                versionId,
                "TEMPLATE_VERSION_PUBLISHED",
                objectMapper.createObjectNode().put("versionId", versionId.toString())
        );
    }

    private JsonNode publishedLayoutSummary(TemplateRepository.TemplateWorkspace workspace) {
        if (workspace.documentStructure().isObject() && !workspace.documentStructure().isEmpty()) {
            return workspace.documentStructure();
        }
        var summary = objectMapper.createObjectNode().put("structureVersion", 6);
        if (workspace.inlineSnapshot().isObject()) summary.set("initialSnapshot", workspace.inlineSnapshot().deepCopy());
        return summary;
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
        var bindingsById = new HashMap<String, JsonNode>();
        var reconciliationRequired = false;
        for (JsonNode binding : mapping) {
            var bindingId = binding.path("bindingId").asText();
            var path = binding.path("dataPath").asText();
            var locatorType = binding.path("locatorType").asText();
            if (!StringUtils.hasText(bindingId)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA,
                        "Mapping 缺少 bindingId：" + mappingDescription(binding));
            }
            if (!StringUtils.hasText(path)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA,
                        "Mapping 缺少 dataPath：" + mappingDescription(binding));
            }
            if (!StringUtils.hasText(locatorType) || !binding.path("locator").isObject()) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA,
                        "Mapping 缺少 locator：" + mappingDescription(binding));
            }
            var syncDirection = binding.path("syncDirection").asText("");
            if (!java.util.Set.of("TWO_WAY", "DATA_TO_EDITOR", "EDITOR_TO_DATA")
                    .contains(syncDirection)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "Mapping 同步方向无效");
            }
            var diagnostic = binding.path("diagnostic");
            if (diagnostic.path("semanticConflict").asBoolean(false)) {
                throw new ApiException(ApiErrorCode.BINDING_INVALID, "请先解决字段的业务含义冲突，再保存模板");
            }
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
            bindingsById.put(bindingId, binding);
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
        validateRepeatMappings(mapping, bindingsById);
        return reconciliationRequired;
    }

    private ObjectNode normalizeWordSchema(JsonNode schema) {
        var normalized = schema != null && schema.isObject()
                ? (ObjectNode) schema.deepCopy() : objectMapper.createObjectNode();
        normalized.put("type", "object");
        normalized.put("documentType", "WORD");
        normalized.put("schemaVersion", 1);
        normalized.remove(TemplateRecognitionCompiler.FIELD_MODEL_KEY);
        return normalized;
    }

    private String mappingDescription(JsonNode binding) {
        var kind = binding.path("mappingKind").asText(binding.path("role").asText("UNKNOWN"));
        var bindingId = binding.path("bindingId").asText("未提供");
        var fieldCode = binding.path("fieldCode").asText("");
        var locator = binding.path("locator").path("address").asText(
                binding.path("locator").path("range").asText("未提供"));
        return "[" + kind + ", bindingId=" + bindingId
                + (fieldCode.isBlank() ? "" : ", fieldCode=" + fieldCode)
                + ", locator=" + locator + "]";
    }

    public WordDocumentDownload downloadWordDocument(UUID versionId) {
        var workspace = get(versionId);
        if (workspace.format() != TemplateFormat.DOCX || !workspace.wordDocument().isObject()) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "Word 原生文档不存在");
        }
        var key = workspace.status() == TemplateStatus.PUBLISHED
                ? "publishedDocxFileId" : "workingDocxFileId";
        var value = workspace.wordDocument().path(key).asText(
                workspace.wordDocument().path("workingDocxFileId").asText(
                        workspace.wordDocument().path("sourceDocxFileId").asText("")));
        if (value.isBlank()) throw new ApiException(ApiErrorCode.NOT_FOUND, "Word 原生文档不存在");
        var file = fileRepository.find(ActorContext.required().organizationId(), UUID.fromString(value))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "Word 原生文档不存在"));
        var stored = objectStorage.get(file.objectKey());
        return new WordDocumentDownload(file.originalName(), file.contentType(), stored);
    }

    private StagedWord stageWordDocument(byte[] bytes, String originalName, Actor actor) {
        String key = null;
        try {
            var sha = java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            var id = UUID.randomUUID();
            var safeName = safeWordFileName(originalName);
            key = actor.organizationId() + "/template-word/" + id + "/" + safeName;
            var contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            objectStorage.put(key, new ByteArrayInputStream(bytes), bytes.length, contentType);
            fileRepository.insert(new FileObjectRepository.NewFileObject(
                    id, actor.organizationId(), storageBucket, key, safeName, contentType,
                    bytes.length, sha, actor.userId()));
            registerRollbackCleanup(key);
            return new StagedWord(id, sha);
        } catch (Exception exception) {
            if (key != null) deleteWordObjectQuietly(key);
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "Word 文件暂存失败");
        }
    }

    private ObjectNode wordDocument(UUID fileId, String sha256, JsonNode documentStructure) {
        return objectMapper.createObjectNode()
                .put("sourceDocxFileId", fileId.toString())
                .put("workingDocxFileId", fileId.toString())
                .put("documentHash", sha256)
                .put("structureHash", documentStructure.path("structureHash").asText(""))
                .put("structureVersion", documentStructure.path("structureVersion").asInt(1))
                .put("patchSequence", 0)
                .put("state", "WORKING");
    }

    private void requestFileActivation(UUID fileId) {
        repository.appendOutbox(
                "FILE_OBJECT",
                fileId,
                "FILE_ACTIVATION_REQUESTED",
                objectMapper.createObjectNode().put("fileId", fileId.toString())
        );
    }

    private String safeWordFileName(String originalName) {
        var normalized = trimToEmpty(originalName)
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("[. ]+$", "")
                .trim();
        if (normalized.isBlank()) normalized = "未命名模板";
        if (normalized.length() > 120) normalized = normalized.substring(0, 120).stripTrailing();
        return normalized.toLowerCase(Locale.ROOT).endsWith(".docx")
                ? normalized : normalized + ".docx";
    }

    private void registerRollbackCleanup(String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteWordObjectQuietly(objectKey);
                }
            }
        });
    }

    private void deleteWordObjectQuietly(String objectKey) {
        try {
            objectStorage.delete(objectKey);
        } catch (RuntimeException ignored) {
            // The staged file row remains absent/rolled back; normal object-storage
            // lifecycle cleanup can remove an object that could not be deleted here.
        }
    }

    private void validateRepeatMappings(JsonNode mapping, Map<String, JsonNode> bindingsById) {
        var childrenByParent = new HashMap<String, List<JsonNode>>();
        for (var binding : mapping) {
            var kind = binding.path("mappingKind").asText("");
            var parentId = binding.path("parentBindingId").asText("");
            if (!"REPEAT_FIELD".equals(kind) && !"MATRIX_FIELD".equals(kind)
                    && parentId.isBlank()) continue;
            if (parentId.isBlank() || !bindingsById.containsKey(parentId)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "明细字段缺少有效的父级重复区域");
            }
            var parent = bindingsById.get(parentId);
            var parentKind = parent.path("mappingKind").asText("");
            if (!Set.of("REPEAT_REGION", "MATRIX_REGION").contains(parentKind)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "明细字段的父级不是重复区域");
            }
            var parentPath = parent.path("dataPath").asText("");
            var childPath = binding.path("dataPath").asText("");
            if (!childPath.startsWith(parentPath + "/*/")) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "明细字段 dataPath 必须位于父级记录下：" + childPath);
            }
            if ("REPEAT_FIELD".equals(kind)) {
                var axis = binding.path("repeatAxis").asText(parent.path("repeatAxis").asText(""));
                if (!Set.of("ROW", "COLUMN").contains(axis)) {
                    throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "重复明细必须指定按行或按列展开");
                }
            }
            if ("REPEAT_REGION".equals(parentKind) || "MATRIX_REGION".equals(parentKind)
                    || "REPEAT_FIELD".equals(kind) || "MATRIX_FIELD".equals(kind)) {
                if (parent.path("recordHeight").asInt(0) <= 0
                        || parent.path("recordWidth").asInt(0) <= 0
                        || parent.path("recordStride").asInt(0) <= 0) {
                    throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "重复区域的记录单元和步长必须为正整数");
                }
                validateTermination(parent.path("termination"));
            }
            var parentRange = "MATRIX_FIELD".equals(kind)
                    ? rangeFromLocator(parent.path("locator"), "range")
                    : rangeFromLocator(parent.path("locator"), "dataRange");
            var childRange = repeatChildRange(binding);
            if (parentRange != null && childRange != null && !contains(parentRange, childRange)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "明细字段位置超出了父级重复区域");
            }
            childrenByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(binding);
        }
        for (var children : childrenByParent.values()) {
            for (var left = 0; left < children.size(); left++) {
                var leftRange = repeatChildRange(children.get(left));
                for (var right = left + 1; right < children.size(); right++) {
                    var rightRange = repeatChildRange(children.get(right));
                    if (overlaps(leftRange, rightRange)) {
                        throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "同一重复区域的明细字段位置发生重叠");
                    }
                }
            }
        }
    }

    private void validateTermination(JsonNode termination) {
        if (termination.isMissingNode() || termination.isNull() || termination.isEmpty()) return;
        var type = termination.path("type").asText("");
        if (!Set.of("UNTIL_TOTAL_ROW", "UNTIL_EMPTY_RECORD", "UNTIL_REGION_END",
                "UNTIL_LABEL", "FIXED_COUNT").contains(type)) {
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "重复区域结束规则无效");
        }
        if ("UNTIL_LABEL".equals(type) && !StringUtils.hasText(termination.path("label").asText())) {
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "按标签结束时必须填写结束标签");
        }
        if ("FIXED_COUNT".equals(type) && termination.path("maxRecords").asInt(0) <= 0) {
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "固定条数结束规则必须填写正整数");
        }
    }

    private int[] rangeFromLocator(JsonNode locator, String preferred) {
        var value = locator.path(preferred).asText("");
        if (value.isBlank()) value = locator.path("range").asText(locator.path("address").asText(""));
        if (!validRange(value)) return null;
        var parts = value.toUpperCase(Locale.ROOT).split(":", 2);
        var start = cellBounds(parts[0]);
        var end = cellBounds(parts.length == 1 ? parts[0] : parts[1]);
        if (start == null || end == null) return null;
        return new int[]{Math.min(start[0], end[0]), Math.min(start[1], end[1]),
                Math.max(start[0], end[0]), Math.max(start[1], end[1])};
    }

    private int[] cellBounds(String cell) {
        var match = java.util.regex.Pattern.compile("^([A-Z]+)([0-9]+)$")
                .matcher(cell.toUpperCase(Locale.ROOT));
        if (!match.matches()) return null;
        var column = 0;
        for (var letter : match.group(1).toCharArray()) column = column * 26 + letter - 'A' + 1;
        return new int[]{column, Integer.parseInt(match.group(2))};
    }

    private boolean contains(int[] outer, int[] inner) {
        return outer[0] <= inner[0] && outer[1] <= inner[1]
                && outer[2] >= inner[2] && outer[3] >= inner[3];
    }

    private boolean overlaps(int[] left, int[] right) {
        return left != null && right != null
                && left[0] <= right[2] && right[0] <= left[2]
                && left[1] <= right[3] && right[1] <= left[3];
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
        if (source.documentStructure().isObject()) layoutSummary.set("documentStructure", source.documentStructure().deepCopy());
        if (source.wordDocument().isObject()) {
            var word = (ObjectNode) source.wordDocument().deepCopy();
            word.put("workingDocxFileId", word.path("publishedDocxFileId")
                    .asText(word.path("workingDocxFileId").asText("")));
            word.put("state", "WORKING");
            layoutSummary.set("wordDocument", word);
        }
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
        // Revisions and rollbacks are derived from a validated immutable
        // version. Keep field provenance, but detach source recognition-run ids.
        repository.copyMappings(sourceVersionId, versionId, false);
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

    private void validateSnapshot(UUID fileId, String expectedHash, TemplateFormat format) {
        if (fileId == null || !StringUtils.hasText(expectedHash)) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "保存草稿必须提交已暂存的原生快照");
        }
        var file = repository.findFile(ActorContext.required().organizationId(), fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY));
        if ("DELETED".equals(file.status()) || !file.sha256().equals(expectedHash)) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "快照哈希与对象存储登记不一致");
        }
        var storedFile = fileRepository.find(ActorContext.required().organizationId(), fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY));
        try (var stored = objectStorage.get(storedFile.objectKey())) {
            var snapshot = objectMapper.readTree(stored.stream());
            if (snapshot == null || !snapshot.isObject()) {
                throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "编辑器快照内容无效");
            }
            if (format == TemplateFormat.XLSX
                    && (!snapshot.path("sheets").isObject() || snapshot.path("sheets").isEmpty())) {
                throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "Excel 编辑快照缺少工作表");
            }
            if (format == TemplateFormat.DOCX) requireCurrentWordSnapshot(snapshot);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "编辑器快照读取失败");
        }
    }

    private int[] repeatChildRange(JsonNode binding) {
        var locator = binding.path("locator");
        if ("MATRIX_FIELD".equals(binding.path("mappingKind").asText())) {
            for (var key : List.of("logicalInputRange", "sourceRange", "valueRange", "address")) {
                var range = rangeFromLocator(locator, key);
                if (range != null) return range;
            }
            return null;
        }
        var range = rangeFromLocator(locator, "valueRange");
        return range == null ? rangeFromLocator(locator, "address") : range;
    }

    private void requireCurrentWordSnapshot(JsonNode snapshot) {
        if (snapshot == null || snapshot.path("snapshotFormatVersion").asInt(0) < 5
                || !"UNIVER_DOCS".equals(snapshot.path("editorMode").asText(""))) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "当前 Word 编辑快照已过期，请重新导入原始 DOCX 后再保存");
        }
        var body = snapshot.path("body");
        if (!body.isObject() || body.path("dataStream").asText("").isBlank()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "Word 编辑快照正文为空，请重新导入后再试");
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
            snapshot.put("snapshotFormatVersion", 5);
            snapshot.put("editorMode", "UNIVER_DOCS");
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
            String category,
            TemplateFormat format,
            UUID importJobId
    ) {
    }

    private void attachStructureFingerprints(JsonNode mapping, JsonNode structureSummary) {
        if (mapping == null || !mapping.isArray() || structureSummary == null) return;
        var fingerprints = new HashMap<String, String>();
        structureSummary.path("sheets").forEach(sheet -> {
            var fingerprint = sheet.path("structureFingerprint").asText("");
            if (fingerprint.isBlank()) return;
            fingerprints.put(sheet.path("id").asText(""), fingerprint);
            fingerprints.put(sheet.path("name").asText(""), fingerprint);
        });
        mapping.forEach(item -> {
            if (!(item instanceof ObjectNode target)) return;
            var locator = target.path("locator") instanceof ObjectNode object
                    ? object : target.putObject("locator");
            var sheetId = locator.path("sheetId").asText(locator.path("sheet").asText(""));
            var fingerprint = fingerprints.get(sheetId);
            if (fingerprint != null) locator.put("sheetStructureFingerprint", fingerprint);
        });
    }

    public record TemplateListQuery(
            String keyword, UUID categoryId, boolean uncategorized, TemplateFormat format, TemplateStatus status,
            UUID createdBy, Instant updatedFrom, Instant updatedTo,
            String sortBy, String sortDirection, int page, int size
    ) {}

    public record TemplateFacetQuery(
            String keyword,
            TemplateFormat format,
            TemplateStatus status,
            UUID createdBy,
            Instant updatedFrom,
            Instant updatedTo
    ) {}

    public record BatchActionItem(UUID templateId, UUID versionId, String name) {}

    public record BatchActionCommand(String action, UUID categoryId, List<BatchActionItem> items) {
        public BatchActionCommand {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record BatchActionResult(UUID templateId, UUID versionId, boolean success, String reason) {}

    public record WordDocumentDownload(
            String originalName,
            String contentType,
            ObjectStorage.StoredObject storedObject
    ) {
    }

    private record StagedWord(UUID id, String sha256) {
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
            List<StructureOperation> structureOperations,
            String wordPatchBaseHash,
            ArrayNode wordPatch
    ) {
        public SaveDraftCommand {
            bindingValues = bindingValues == null ? List.of() : List.copyOf(bindingValues);
            recognitionActions = recognitionActions == null ? List.of() : List.copyOf(recognitionActions);
            qualityActions = qualityActions == null ? List.of() : List.copyOf(qualityActions);
            structureOperations = structureOperations == null ? List.of() : List.copyOf(structureOperations);
        }
    }

    public record SaveResult(long lockVersion, String workspaceHash, boolean reconciliationRequired,
                             JsonNode schema, JsonNode mapping,
                             JsonNode wordDocument, JsonNode documentStructure) {
    }
}
