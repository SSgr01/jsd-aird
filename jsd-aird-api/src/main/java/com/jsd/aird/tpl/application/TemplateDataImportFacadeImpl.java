package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import com.jsd.aird.tpl.application.port.TabularStructureParser;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.domain.TargetDataType;
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
    public List<DataTemplateOption> listPublished(UUID organizationId, String targetDataType) {
        TargetDataType target = targetDataType == null || targetDataType.isBlank()
                ? null : parseTarget(targetDataType);
        return repository.findPublishedDataTemplates(organizationId, target).stream()
                .map(item -> new DataTemplateOption(
                        item.templateId(), item.versionId(), item.templateCode(), item.name(),
                        item.category(), item.targetDataType().name(), item.versionNo(), item.format().name()))
                .toList();
    }

    @Override
    public DataTemplateDefinition getPublished(UUID organizationId, UUID templateVersionId) {
        var workspace = repository.findPublishedDataTemplate(organizationId, templateVersionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据中心模板不存在或未发布"));
        return definition(workspace);
    }

    @Override
    public ParsedTabularFile parse(UUID organizationId, UUID templateVersionId, UUID fileId) {
        getPublished(organizationId, templateVersionId);
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

    private DataTemplateDefinition definition(TemplateRepository.TemplateWorkspace workspace) {
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
        return new DataTemplateDefinition(
                workspace.templateId(), workspace.versionId(), workspace.templateCode(), workspace.name(),
                null, workspace.targetDataType().name(), workspace.versionNo(), workspace.format().name(), workspace.schema(),
                workspace.mapping(), List.copyOf(fields.values()));
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

    private TargetDataType parseTarget(String value) {
        try {
            return TargetDataType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的数据类型：" + value);
        }
    }
}
