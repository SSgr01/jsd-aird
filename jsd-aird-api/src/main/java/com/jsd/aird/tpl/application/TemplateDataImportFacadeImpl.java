package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import com.jsd.aird.tpl.application.port.TabularStructureParser;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Service;

@Service
public class TemplateDataImportFacadeImpl implements TemplateDataImportFacade {

    private final TemplateRepository repository;
    private final FileObjectRepository files;
    private final ObjectStorage storage;
    private final TabularStructureParser parser;
    private final StandardFieldService standardFieldService;
    private final SnapshotWorkbookExporter workbookExporter;
    private final ObjectMapper objectMapper;

    public TemplateDataImportFacadeImpl(
            TemplateRepository repository,
            FileObjectRepository files,
            ObjectStorage storage,
            TabularStructureParser parser,
            StandardFieldService standardFieldService,
            SnapshotWorkbookExporter workbookExporter,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.files = files;
        this.storage = storage;
        this.parser = parser;
        this.standardFieldService = standardFieldService;
        this.workbookExporter = workbookExporter;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DataTemplateOption> listPublished(UUID organizationId) {
        return repository.findPublishedDataTemplates(organizationId).stream()
                .map(item -> new DataTemplateOption(
                        item.templateId(), item.versionId(), item.templateCode(), item.name(),
                        item.category(), item.versionNo(), item.format().name()))
                .toList();
    }

    @Override
    public DataTemplateDefinition getPublished(UUID organizationId, UUID templateVersionId) {
        var workspace = repository.findPublishedDataTemplate(organizationId, templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据中心模板不存在或未发布"));
        return definition(organizationId, workspace);
    }

    @Override
    public DataTemplateDefinition getVersion(UUID organizationId, UUID templateVersionId) {
        var workspace = repository.findWorkspace(organizationId, templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务引用的模板版本不存在"));
        return definition(organizationId, workspace);
    }

    @Override
    public List<ImportBinding> getPublishedBindings(UUID organizationId, UUID templateVersionId) {
        var workspace = repository.findPublishedDataTemplate(organizationId, templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据中心模板不存在或未发布"));
        return bindings(organizationId, workspace);
    }

    @Override
    public List<ImportBinding> getBindings(UUID organizationId, UUID templateVersionId) {
        var workspace = repository.findWorkspace(organizationId, templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "导入任务引用的模板版本不存在"));
        return bindings(organizationId, workspace);
    }

    private List<ImportBinding> bindings(UUID organizationId, TemplateRepository.TemplateWorkspace workspace) {
        var result = new ArrayList<ImportBinding>();
        if (workspace.mapping() == null || !workspace.mapping().isArray()) return result;
        var componentByBinding = componentByBinding(
                repository.findImportContract(organizationId, workspace.versionId())
                        .map(TemplateRepository.ImportContract::contract).orElse(null));
        for (JsonNode item : workspace.mapping()) {
            var fieldCode = firstText(item, "fieldCode", "field_code");
            if (fieldCode.isBlank() && !item.path("mappingKind").asText("").contains("REGION")) continue;
            var valueSource = firstText(item, "valueSource", "value_source", "INPUT");
            var bindingId = firstText(item, "bindingId", "binding_id");
            var locator = importLocator(item);
            if (locator instanceof ObjectNode target && target.path("componentId").asText("").isBlank()) {
                var componentId = componentByBinding.getOrDefault(bindingId,
                        componentByBinding.getOrDefault(firstText(item, "parentBindingId", "parent_binding_id"), ""));
                if (!componentId.isBlank()) target.put("componentId", componentId);
            }
            result.add(new ImportBinding(
                    bindingId, fieldCode,
                    firstText(item, "dataPath", "data_path", fieldCode),
                    firstText(item, "mappingKind", "mapping_kind", "SCALAR"),
                    firstText(item, "parentBindingId", "parent_binding_id"),
                    firstText(item, "repeatAxis", "repeat_axis"),
                    Math.max(1, item.path("recordHeight").asInt(1)),
                    Math.max(1, item.path("recordWidth").asInt(1)),
                    Math.max(1, item.path("recordStride").asInt(1)),
                    item.path("terminationRule").isMissingNode()
                            ? item.path("termination_jsonb").isMissingNode()
                            ? item.path("termination") : item.path("termination_jsonb")
                            : item.path("terminationRule"),
                    locator,
                    item.path("required").asBoolean(false), item.path("identity").asBoolean(false),
                    item.path("trainingEligible").asBoolean(!"FORMULA".equalsIgnoreCase(valueSource)),
                    valueSource, firstText(item, "valueType", "dataType", "TEXT"),
                    firstText(item, "unit", "defaultUnit", ""),
                    labelPath(item), firstText(item, "trainingRole", "training_role",
                            item.path("trainingEligible").asBoolean(true) ? "FEATURE" : "EXCLUDE"),
                    item.path("ragEligible").asBoolean(true)));
        }
        return List.copyOf(result);
    }

    private LinkedHashMap<String, String> componentByBinding(JsonNode contract) {
        var result = new LinkedHashMap<String, String>();
        if (contract == null || !contract.path("components").isArray()) return result;
        for (var component : contract.path("components")) {
            var componentId = component.path("componentId").asText("");
            if (componentId.isBlank()) continue;
            for (var binding : component.path("bindings")) {
                var bindingId = binding.path("bindingId").asText("");
                if (!bindingId.isBlank()) result.put(bindingId, componentId);
            }
        }
        return result;
    }

    private String labelPath(JsonNode item) {
        var direct = firstText(item, "labelPath", "label_path");
        if (!direct.isBlank()) return direct;
        var diagnostic = item.path("diagnostic");
        if (diagnostic.path("labelPath").isArray()) {
            var labels = new ArrayList<String>();
            diagnostic.path("labelPath").forEach(value -> {
                if (!value.asText("").isBlank()) labels.add(value.asText());
            });
            if (!labels.isEmpty()) return String.join(" > ", labels);
        }
        return firstText(item, "fieldName", "displayName", "fieldCode");
    }

    /**
     * The published template stores geometry on the binding and the richer
     * projection contract in diagnostic metadata. Keep both available to the
     * data importer without changing the public ImportBinding shape.
     */
    private JsonNode importLocator(JsonNode item) {
        JsonNode source = item.path("locator").isObject() ? item.path("locator") : item.path("locator_jsonb");
        ObjectNode locator = source.isObject()
                ? (ObjectNode) source.deepCopy()
                : objectMapper.createObjectNode();
        JsonNode diagnostic = item.path("diagnostic");
        for (var key : List.of("matrixModel", "tableModel", "longTableModel", "recordProjection",
                "columnSlots", "rowSlots", "columns", "kind", "blockType", "valueType", "role",
                "groupName", "displayName", "title")) {
            if (!locator.has(key) && diagnostic.isObject() && diagnostic.has(key)) {
                locator.set(key, diagnostic.path(key).deepCopy());
            }
        }
        for (var key : List.of("componentId", "sheetId", "sheet", "rowHeaderRange", "columnHeaderRange", "crossDataRange",
                "cornerRange", "totalRange", "recordAxis", "semanticMode", "repeatAxis", "valueMode")) {
            if (!locator.has(key) && item.has(key)) locator.set(key, item.path(key).deepCopy());
        }
        return locator;
    }

    @Override
    public ParsedTabularFile parse(UUID organizationId, UUID templateVersionId, UUID fileId) {
        getVersion(organizationId, templateVersionId);
        var file = files.find(organizationId, fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY, "数据源文件不存在"));
        try (var stored = storage.get(file.objectKey())) {
            return parser.parse(stored.stream(), file.originalName());
        } catch (Exception exception) {
            if (exception instanceof ApiException apiException) throw apiException;
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "数据源文件解析失败：" + exception.getMessage());
        }
    }

    @Override
    public FieldRequest requestField(UUID organizationId, UUID templateVersionId, FieldRequestCommand command) {
        getPublished(organizationId, templateVersionId);
        var request = standardFieldService.request(new StandardFieldService.RequestCommand(
                templateVersionId, command.fieldId(), command.displayName(), command.valueType(),
                command.uiType(), command.groupCode(), command.description()));
        return new FieldRequest(request.id(), request.templateVersionId(), request.displayName(), request.valueType(), request.status());
    }

    @Override
    public WorkbookExport exportPublishedWorkbook(UUID organizationId, UUID templateVersionId,
                                                   JsonNode data, UUID revisionId) {
        var workspace = repository.findPublishedDataTemplate(organizationId, templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据中心模板不存在或未发布"));
        if (workspace.format() != TemplateFormat.XLSX) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "数据中心批量导出首期仅支持 XLSX 模板");
        }
        var result = workbookExporter.export(
                readSnapshot(organizationId, workspace), workspace.mapping(),
                data == null ? objectMapper.createObjectNode() : data,
                new SnapshotWorkbookExporter.Manifest(
                        workspace.versionId().toString(), workspace.schemaHash(), workspace.mappingHash(),
                        "DATA_CENTER_EXPORT", revisionId == null ? null : revisionId.toString()));
        return new WorkbookExport(result.content(), result.warnings().stream()
                .map(item -> new ExportWarning(item.code(), item.bindingId(), item.dataPath(), item.message()))
                .toList());
    }

    private DataTemplateDefinition definition(UUID organizationId, TemplateRepository.TemplateWorkspace workspace) {
        var fields = new LinkedHashMap<String, FieldDefinition>();
        collectSchemaFields(workspace.schema(), fields);
        if (workspace.mapping().isArray()) {
            for (JsonNode mapping : workspace.mapping()) {
                var code = text(mapping, "fieldCode", "field_code");
                if (code == null || code.isBlank()) continue;
                fields.putIfAbsent(code, new FieldDefinition(
                        code,
                        firstText(mapping, "fieldName", "name", "displayName", "fieldCode"),
                        firstText(mapping, "valueType", "dataType", "type", "TEXT"),
                        firstText(mapping, "unit", "defaultUnit", ""),
                        mapping.path("required").asBoolean(false),
                        mapping.path("identity").asBoolean(false),
                        strings(mapping.path("aliases")),
                        firstText(mapping, "dataPath", "fieldCode")));
            }
        }
        var contract = repository.findImportContract(organizationId, workspace.versionId()).orElse(null);
        return new DataTemplateDefinition(
                workspace.templateId(), workspace.versionId(), workspace.templateCode(), workspace.name(),
                null, workspace.versionNo(), workspace.format().name(), workspace.schema(),
                workspace.mapping(), List.copyOf(fields.values()),
                contract == null ? 0 : contract.importContractVersion(),
                contract == null ? 0 : contract.layoutStructureVersion(),
                contract == null ? null : contract.contractHash(),
                contract == null ? null : contract.contract());
    }


    private JsonNode readSnapshot(UUID organizationId, TemplateRepository.TemplateWorkspace workspace) {
        var inline = workspace.inlineSnapshot();
        if (inline != null && inline.isObject() && !inline.isEmpty()) return inline;
        if (workspace.snapshotFileId() == null) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "模板工作簿快照不存在");
        }
        var file = files.find(organizationId, workspace.snapshotFileId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY, "模板工作簿快照文件不存在"));
        try (var stored = storage.get(file.objectKey())) {
            return objectMapper.readTree(stored.stream());
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "模板工作簿快照读取失败");
        }
    }

    private void collectSchemaFields(JsonNode schema, LinkedHashMap<String, FieldDefinition> fields) {
        if (schema == null || schema.isMissingNode()) return;
        if (schema.isArray()) {
            schema.forEach(item -> collectSchemaFields(item, fields));
            return;
        }
        if (schema.isObject()) {
            var code = text(schema, "fieldCode", "code");
            if (code != null && !code.isBlank()) {
                fields.putIfAbsent(code, new FieldDefinition(
                        code, firstText(schema, "displayName", "name", "label", code),
                        firstText(schema, "dataType", "valueType", "type", "TEXT"),
                        firstText(schema, "defaultUnit", "unit", ""),
                        schema.path("required").asBoolean(false), schema.path("identity").asBoolean(false),
                        strings(schema.path("aliases")), firstText(schema, "dataPath", code)));
            }
            var entries = schema.fields();
            while (entries.hasNext()) collectSchemaFields(entries.next().getValue(), fields);
        }
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        var result = new ArrayList<String>();
        node.forEach(item -> { if (item.isTextual()) result.add(item.asText()); });
        return List.copyOf(result);
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            var value = text(node, name);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            var value = node.path(name);
            if (value.isTextual()) return value.asText();
        }
        return null;
    }

}
