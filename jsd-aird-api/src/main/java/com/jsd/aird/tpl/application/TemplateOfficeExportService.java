package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.application.port.WordOoxmlPatcher;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.jsd.aird.tpl.domain.TemplateStatus;
import org.springframework.stereotype.Service;

@Service
public class TemplateOfficeExportService {
    private final TemplateRepository repository;
    private final FileObjectRepository files;
    private final ObjectStorage storage;
    private final ObjectMapper objectMapper;
    private final SnapshotWorkbookExporter workbookExporter;
    private final WordOoxmlPatcher wordOoxmlPatcher;

    public TemplateOfficeExportService(
            TemplateRepository repository,
            FileObjectRepository files,
            ObjectStorage storage,
            ObjectMapper objectMapper,
            SnapshotWorkbookExporter workbookExporter,
            WordOoxmlPatcher wordOoxmlPatcher
    ) {
        this.repository = repository;
        this.files = files;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.workbookExporter = workbookExporter;
        this.wordOoxmlPatcher = wordOoxmlPatcher;
    }

    public Check check(UUID versionId, String format, String state) {
        var workspace = workspace(versionId, state);
        requireFormat(workspace.format(), format);
        var warnings = inspect(workspace.mapping(), format);
        return new Check(true, warnings);
    }

    public Download export(UUID versionId, String format, String state) {
        var workspace = workspace(versionId, state);
        requireFormat(workspace.format(), format);
        var warnings = inspect(workspace.mapping(), format);
        if ("XLSX".equalsIgnoreCase(format)) {
            var snapshot = readSnapshot(workspace);
            var result = workbookExporter.export(snapshot, objectMapper.createArrayNode(), objectMapper.createObjectNode(),
                    new SnapshotWorkbookExporter.Manifest(workspace.versionId().toString(), workspace.schemaHash(),
                            workspace.mappingHash(), state.toUpperCase(), null));
            return new Download(fileName(workspace, "xlsx", state), contentType("XLSX"), result.content(), result.warnings());
        }
        var wordId = workspace.wordDocument().path("PUBLISHED".equalsIgnoreCase(state) ? "publishedDocxFileId" : "workingDocxFileId")
                .asText(workspace.wordDocument().path("sourceDocxFileId").asText(""));
        if (wordId.isBlank()) throw new ApiException(ApiErrorCode.NOT_FOUND, "Word 原生文档不存在");
        var source = readBytes(UUID.fromString(wordId));
        var snapshot = readSnapshot(workspace);
        var exported = wordOoxmlPatcher.applySnapshot(source, snapshot);
        return new Download(fileName(workspace, "docx", state), contentType("DOCX"), exported, warnings);
    }

    private TemplateRepository.TemplateWorkspace workspace(UUID versionId, String state) {
        var requested = state == null || state.isBlank() ? "DRAFT" : state.toUpperCase();
        var workspace = repository.findWorkspace(ActorContext.required().organizationId(), versionId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "模板版本不存在"));
        if (!requested.equals(workspace.status().name())) throw new ApiException(ApiErrorCode.NOT_FOUND, "请求的模板版本状态不存在");
        return workspace;
    }

    private void requireFormat(TemplateFormat actual, String requested) {
        if (requested == null || !actual.name().equalsIgnoreCase(requested)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "导出格式必须与模板原格式一致");
        }
    }

    private List<SnapshotWorkbookExporter.Warning> inspect(JsonNode mapping, String format) {
        var warnings = new ArrayList<SnapshotWorkbookExporter.Warning>();
        if (mapping == null || !mapping.isArray()) return warnings;
        for (var binding : mapping) {
            var locator = binding.path("locator");
            var has = "DOCX".equalsIgnoreCase(format)
                    ? !binding.path("markerId").asText("").isBlank() || !locator.path("markerId").asText("").isBlank()
                    : !first(locator, "logicalInputRange", "valueRange", "address", "range", "dataRange").isBlank();
            if (!has) warnings.add(new SnapshotWorkbookExporter.Warning("BINDING_MISSING", binding.path("bindingId").asText(""), binding.path("dataPath").asText(""), "字段没有有效位置，已留空"));
        }
        return warnings;
    }

    private JsonNode readJson(UUID fileId) {
        try (var object = storage.get(file(files, fileId).objectKey())) { return objectMapper.readTree(object.stream()); }
        catch (Exception exception) { throw new ApiException(ApiErrorCode.FILE_NOT_READY, "模板工作簿快照读取失败"); }
    }

    private JsonNode readSnapshot(TemplateRepository.TemplateWorkspace workspace) {
        var inline = workspace.inlineSnapshot();
        if (inline != null && inline.isObject() && !inline.isEmpty()) return inline;
        if (workspace.snapshotFileId() == null) throw new ApiException(ApiErrorCode.FILE_NOT_READY, "模板工作簿快照不存在");
        return readJson(workspace.snapshotFileId());
    }
    private byte[] readBytes(UUID fileId) {
        try (var object = storage.get(file(files, fileId).objectKey())) { return object.stream().readAllBytes(); }
        catch (Exception exception) { throw new ApiException(ApiErrorCode.FILE_NOT_READY, "模板原生文件读取失败"); }
    }
    private FileObjectRepository.FileObject file(FileObjectRepository ignored, UUID fileId) {
        return files.find(ActorContext.required().organizationId(), fileId).orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY));
    }
    private String first(JsonNode node, String... keys) { for (var key : keys) if (!node.path(key).asText("").isBlank()) return node.path(key).asText(); return ""; }
    private String fileName(TemplateRepository.TemplateWorkspace workspace, String suffix, String state) {
        return workspace.templateCode() + "-v" + workspace.versionNo() + "-" + state.toLowerCase() + "." + suffix;
    }
    private String contentType(String format) { return "DOCX".equalsIgnoreCase(format)
            ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }

    public record Check(boolean canDownload, List<SnapshotWorkbookExporter.Warning> warnings) {}
    public record Download(String fileName, String contentType, byte[] content, List<SnapshotWorkbookExporter.Warning> warnings) {}
}
