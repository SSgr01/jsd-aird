package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.rnd.application.port.ExperimentRepository;
import com.jsd.aird.rnd.domain.ExperimentModels.Detail;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.application.port.BlankWordDocumentFactory;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.application.port.WordOoxmlPatcher;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Exports the current experiment working copy in its original office format. */
@Service
public class ExperimentExportService {
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ExperimentRepository repository;
    private final FileStorageFacade fileStorage;
    private final TemplateRepository templateRepository;
    private final ObjectMapper objectMapper;
    private final SnapshotWorkbookExporter workbookExporter;
    private final WordOoxmlPatcher wordOoxmlPatcher;
    private final BlankWordDocumentFactory blankWordDocumentFactory;

    public ExperimentExportService(
            ExperimentRepository repository,
            FileStorageFacade fileStorage,
            TemplateRepository templateRepository,
            ObjectMapper objectMapper,
            SnapshotWorkbookExporter workbookExporter,
            WordOoxmlPatcher wordOoxmlPatcher,
            BlankWordDocumentFactory blankWordDocumentFactory
    ) {
        this.repository = repository;
        this.fileStorage = fileStorage;
        this.templateRepository = templateRepository;
        this.objectMapper = objectMapper;
        this.workbookExporter = workbookExporter;
        this.wordOoxmlPatcher = wordOoxmlPatcher;
        this.blankWordDocumentFactory = blankWordDocumentFactory;
    }

    public Download export(UUID id) {
        return export(id,"RESULT");
    }

    public Download export(UUID id,String view) {
        if(!java.util.Set.of("RESULT","BLANK").contains(view))throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"导出视图必须为RESULT或BLANK");
        var actor = ActorContext.required();
        var detail = repository.detail(actor.organizationId(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "实验不存在"));
        var snapshot = currentSnapshot(detail);
        var format = detail.editModel().path("documentFormat").asText("");
        if (!isWorkbookSnapshot(snapshot)
                && ("word".equalsIgnoreCase(format) || isDocumentSnapshot(snapshot))) {
            return exportWord(detail, actor.organizationId());
        }
        return exportWorkbook(detail, actor.organizationId(),view);
    }

    private Download exportWorkbook(Detail detail, UUID organizationId,String view) {
        var snapshot = currentSnapshot(detail);
        if (snapshot == null || !snapshot.isObject() || snapshot.isEmpty()) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "实验工作簿快照不存在，请先保存实验");
        }
        if(com.jsd.aird.shared.lab.LabWorkbook.supported(snapshot))snapshot=view.equals("BLANK")
                ?com.jsd.aird.shared.lab.LabWorkbook.blank(snapshot)
                :com.jsd.aird.shared.lab.LabWorkbook.present(objectMapper,snapshot).snapshot();
        var result = workbookExporter.export(
                snapshot,
                objectMapper.createArrayNode(),
                objectMapper.createObjectNode(),
                new SnapshotWorkbookExporter.Manifest(detail.templateVersionId()==null?null:detail.templateVersionId().toString(), null, null,
                        detail.summary().status().name(), null));
        return new Download(fileName(detail, "xlsx"), XLSX_CONTENT_TYPE, result.content());
    }

    private Download exportWord(Detail detail, UUID organizationId) {
        var sourceId = uuidFrom(detail.editModel().path("sourceFileId"));
        var templateWord = detail.templateVersionId() == null ? null : templateRepository
                .findWorkspace(organizationId, detail.templateVersionId())
                .map(TemplateRepository.TemplateWorkspace::wordDocument)
                .orElse(null);
        if (sourceId == null) sourceId = wordFileId(templateWord);
        if (sourceId == null) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "Word 原生文档不存在");
        }
        var source = readBytes(organizationId, sourceId, "Word 原生文档读取失败");
        var snapshot = currentSnapshot(detail);
        if (snapshot == null || !snapshot.isObject() || snapshot.isEmpty()) {
            snapshot = objectMapper.createObjectNode();
        }
        return new Download(fileName(detail, "docx"), DOCX_CONTENT_TYPE,
                wordOoxmlPatcher.applySnapshot(source, snapshot));
    }

    private JsonNode currentSnapshot(Detail detail) {
        var modelSnapshot = detail.editModel().path("documentSnapshot");
        if (modelSnapshot.isObject() && !modelSnapshot.isEmpty()) return modelSnapshot;
        var templateSnapshot = detail.templateSnapshot();
        return templateSnapshot == null || templateSnapshot.isMissingNode() ? null : templateSnapshot;
    }

    private boolean isDocumentSnapshot(JsonNode snapshot) {
        return snapshot != null && snapshot.isObject()
                && (snapshot.has("body") || snapshot.has("documentStyle") || snapshot.has("editorMode"));
    }

    private boolean isWorkbookSnapshot(JsonNode snapshot) {
        return snapshot != null && snapshot.isObject() && snapshot.path("sheets").isObject();
    }

    private UUID wordFileId(JsonNode wordDocument) {
        if (wordDocument == null || !wordDocument.isObject()) return null;
        for (var field : new String[]{"publishedDocxFileId", "workingDocxFileId", "sourceDocxFileId"}) {
            var value = uuidFrom(wordDocument.path(field));
            if (value != null) return value;
        }
        return null;
    }

    private UUID uuidFrom(JsonNode node) {
        var value = node == null ? "" : node.asText("");
        if (value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private byte[] readBytes(UUID organizationId, UUID fileId, String error) {
        try (var stored = fileStorage.open(organizationId, fileId)) {
            return stored.stream().readAllBytes();
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, error);
        }
    }

    private String fileName(Detail detail, String extension) {
        var title = detail.summary().title();
        var base = title == null || title.isBlank() ? detail.summary().experimentNo() : title;
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        var versioned = base + "-V" + detail.summary().versionNo();
        return versioned.toLowerCase().endsWith("." + extension) ? versioned : versioned + "." + extension;
    }

    public record Download(String fileName, String contentType, byte[] content) {}
}
