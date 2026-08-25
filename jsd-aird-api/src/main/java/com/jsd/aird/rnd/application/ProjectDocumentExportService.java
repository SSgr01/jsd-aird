package com.jsd.aird.rnd.application;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Detail;
import com.jsd.aird.rnd.domain.ProjectDocumentFormat;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.application.port.WordOoxmlPatcher;
import org.springframework.stereotype.Service;

/** Exports the current project-document working copy, rather than the template's last saved copy. */
@Service
public class ProjectDocumentExportService {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ProjectDocumentRepository repository;
    private final FileStorageFacade fileStorage;
    private final TemplateRepository templateRepository;
    private final ObjectMapper objectMapper;
    private final SnapshotWorkbookExporter workbookExporter;
    private final WordOoxmlPatcher wordOoxmlPatcher;

    public ProjectDocumentExportService(
            ProjectDocumentRepository repository,
            FileStorageFacade fileStorage,
            TemplateRepository templateRepository,
            ObjectMapper objectMapper,
            SnapshotWorkbookExporter workbookExporter,
            WordOoxmlPatcher wordOoxmlPatcher
    ) {
        this.repository = repository;
        this.fileStorage = fileStorage;
        this.templateRepository = templateRepository;
        this.objectMapper = objectMapper;
        this.workbookExporter = workbookExporter;
        this.wordOoxmlPatcher = wordOoxmlPatcher;
    }

    public Download export(UUID projectId, UUID documentId) {
        var actor = ActorContext.required();
        var document = repository.findById(documentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "项目文档不存在"));
        if (!projectId.equals(document.projectId())) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "项目文档不存在");
        }
        var format = document.format();
        if (format == ProjectDocumentFormat.XLSX) return exportWorkbook(document, actor.organizationId());
        if (format == ProjectDocumentFormat.DOCX) return exportWord(document, actor.organizationId());
        return exportOriginal(document, actor.organizationId());
    }

    private Download exportWorkbook(Detail document, UUID organizationId) {
        var snapshot = firstSnapshot(document, organizationId);
        if ((snapshot == null || !snapshot.isObject() || snapshot.isEmpty())
                && document.source().name().equals("BLANK")) {
            snapshot = blankWorkbookSnapshot(document);
        }
        if (snapshot == null || !snapshot.isObject() || snapshot.isEmpty()) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "项目文档工作簿快照不存在，请先保存文档");
        }
        var result = workbookExporter.export(
                snapshot,
                document.contentMapping() == null ? objectMapper.createArrayNode() : document.contentMapping(),
                document.contentData() == null ? objectMapper.createObjectNode() : document.contentData(),
                new SnapshotWorkbookExporter.Manifest(document.id().toString(), null, null,
                        document.status().name(), null));
        return new Download(fileName(document.title(), "xlsx"), XLSX_CONTENT_TYPE, result.content());
    }

    private JsonNode blankWorkbookSnapshot(Detail document) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("id", document.id().toString());
        snapshot.put("snapshotFormatVersion", 3);
        snapshot.put("name", document.title());
        snapshot.putArray("sheetOrder").add("sheet-1");
        ObjectNode sheets = snapshot.putObject("sheets");
        ObjectNode sheet = sheets.putObject("sheet-1");
        sheet.put("id", "sheet-1");
        sheet.put("name", "Sheet1");
        sheet.put("rowCount", 200);
        sheet.put("columnCount", 26);
        sheet.putObject("cellData");
        snapshot.putObject("styles");
        return snapshot;
    }

    private Download exportWord(Detail document, UUID organizationId) {
        var sourceId = document.fileObjectId();
        JsonNode templateWord = null;
        if (sourceId == null && document.templateVersionId() != null) {
            templateWord = templateRepository.findWorkspace(organizationId, document.templateVersionId())
                    .map(TemplateRepository.TemplateWorkspace::wordDocument)
                    .orElse(null);
            sourceId = wordFileId(templateWord);
        }
        if (sourceId == null) throw new ApiException(ApiErrorCode.FILE_NOT_READY, "Word 原生文档不存在");
        var source = readBytes(organizationId, sourceId, "Word 原生文档读取失败");
        var snapshot = firstSnapshot(document, organizationId);
        if (snapshot == null || !snapshot.isObject() || snapshot.isEmpty()) snapshot = objectMapper.createObjectNode();
        return new Download(fileName(document.title(), "docx"), DOCX_CONTENT_TYPE,
                wordOoxmlPatcher.applySnapshot(source, snapshot));
    }

    private Download exportOriginal(Detail document, UUID organizationId) {
        if (document.fileObjectId() == null) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "该项目文档格式不支持导出");
        }
        var stored = readStored(organizationId, document.fileObjectId(), "项目文档读取失败");
        return new Download(fileName(document.title(), extension(stored.originalName())),
                stored.contentType() == null ? "application/octet-stream" : stored.contentType(), stored.content());
    }

    private JsonNode firstSnapshot(Detail document, UUID organizationId) {
        if (document.contentSnapshot() != null && document.contentSnapshot().isObject()
                && !document.contentSnapshot().isEmpty()) return document.contentSnapshot();
        if (document.templateVersionId() == null) return null;
        var workspace = templateRepository.findWorkspace(organizationId, document.templateVersionId()).orElse(null);
        if (workspace == null) return null;
        if (workspace.inlineSnapshot() != null && workspace.inlineSnapshot().isObject()
                && !workspace.inlineSnapshot().isEmpty()) return workspace.inlineSnapshot();
        if (workspace.snapshotFileId() == null) return null;
        try (var stored = fileStorage.open(organizationId, workspace.snapshotFileId())) {
            return objectMapper.readTree(stored.stream());
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "模板工作簿快照读取失败");
        }
    }

    private UUID wordFileId(JsonNode wordDocument) {
        if (wordDocument == null || !wordDocument.isObject()) return null;
        for (var field : new String[]{"publishedDocxFileId", "workingDocxFileId", "sourceDocxFileId"}) {
            var value = wordDocument.path(field).asText("");
            if (!value.isBlank()) {
                try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { }
            }
        }
        return null;
    }

    private byte[] readBytes(UUID organizationId, UUID fileId, String error) {
        return readStored(organizationId, fileId, error).content();
    }

    private StoredContent readStored(UUID organizationId, UUID fileId, String error) {
        try (var stored = fileStorage.open(organizationId, fileId)) {
            return new StoredContent(stored.originalName(), stored.contentType(), stored.stream().readAllBytes());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, error);
        }
    }

    private String fileName(String title, String extension) {
        var base = title == null || title.isBlank() ? "project-document" : title;
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        if (base.toLowerCase().endsWith("." + extension)) return base;
        return base + "." + extension;
    }

    private String extension(String originalName) {
        if (originalName == null) return "bin";
        var dot = originalName.lastIndexOf('.');
        return dot > 0 && dot < originalName.length() - 1 ? originalName.substring(dot + 1).toLowerCase() : "bin";
    }

    public record Download(String fileName, String contentType, byte[] content) {}

    private record StoredContent(String originalName, String contentType, byte[] content) {}
}
