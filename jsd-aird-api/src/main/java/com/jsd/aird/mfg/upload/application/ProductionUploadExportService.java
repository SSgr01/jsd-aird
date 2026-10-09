package com.jsd.aird.mfg.upload.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.upload.application.port.ProductionUploadRepository;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateOfficeFacade;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

@Service
public class ProductionUploadExportService {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ProductionUploadRepository repository;
    private final FileObjectRepository files;
    private final ObjectStorage storage;
    private final SnapshotWorkbookExporter workbookExporter;
    private final TemplateOfficeFacade officeStructure;
    private final ObjectMapper objectMapper;

    public ProductionUploadExportService(ProductionUploadRepository repository,
                                         FileObjectRepository files,
                                         ObjectStorage storage,
                                         SnapshotWorkbookExporter workbookExporter,
                                         TemplateOfficeFacade officeStructure,
                                         ObjectMapper objectMapper) {
        this.repository = repository;
        this.files = files;
        this.storage = storage;
        this.workbookExporter = workbookExporter;
        this.officeStructure = officeStructure;
        this.objectMapper = objectMapper;
    }

    public Download export(UUID id) {
        var actor = ActorContext.required();
        var record = repository.find(actor.organizationId(), id)
                .filter(item -> !"DELETED".equals(item.status()))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单不存在"));
        // A workbook snapshot means the record owns a sheet: uploaded .xlsx files,
        // and photo uploads whose recognized values were written into their
        // template.  Such records always export as .xlsx.
        var snapshot = record.workbookSnapshot();
        var hasWorkbook = snapshot != null && snapshot.isObject() && !snapshot.path("sheets").isEmpty();
        if (hasWorkbook || (record.originalName() != null
                && record.originalName().toLowerCase(Locale.ROOT).endsWith(".xlsx"))) {
            if (!hasWorkbook) {
                throw new ApiException(ApiErrorCode.FILE_NOT_READY, "生产单工作簿尚未保存，暂无法导出");
            }
            var result = workbookExporter.export(
                    snapshot,
                    objectMapper.createArrayNode(),
                    objectMapper.createObjectNode(),
                    new SnapshotWorkbookExporter.Manifest(
                            record.id().toString(), null, null, record.status(), null));
            return new Download(fileName(record.productionName(), record.originalName(), ".xlsx"),
                    XLSX_CONTENT_TYPE, result.content());
        }
        // Word uploads are edited as a Univer document snapshot over a DOCX
        // source artifact (legacy .doc is normalized into one).  The export
        // rebuilds the body from the snapshot, otherwise saved edits would be
        // missing from the downloaded file.
        var workingId = record.recognitionResult() == null
                ? "" : record.recognitionResult().path("wordDocument").path("workingDocxFileId").asText("");
        if (!workingId.isBlank()) {
            var source = readBytes(actor.organizationId(), UUID.fromString(workingId), "Word 工作副本不存在");
            var content = snapshot == null || !snapshot.isObject()
                    ? source
                    : officeStructure.exportWordSnapshot(source, snapshot);
            return new Download(wordFileName(record.productionName(), record.originalName()),
                    DOCX_CONTENT_TYPE, content);
        }
        var source = files.find(actor.organizationId(), record.fileId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY, "生产单原文件不存在"));
        try (var stored = storage.get(source.objectKey())) {
            return new Download(fileName(record.productionName(), source.originalName(), extension(source.originalName())),
                    source.contentType() == null || source.contentType().isBlank()
                            ? "application/octet-stream" : source.contentType(),
                    stored.stream().readAllBytes());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "生产单文件导出失败");
        }
    }

    private String fileName(String productionName, String originalName, String extension) {
        var value = productionName == null || productionName.isBlank() ? originalName : productionName.trim();
        if (value == null || value.isBlank()) value = "生产单";
        return value.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT))
                ? value : value + extension;
    }

    private byte[] readBytes(UUID organizationId, UUID fileId, String missingMessage) {
        var file = files.find(organizationId, fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.FILE_NOT_READY, missingMessage));
        try (var stored = storage.get(file.objectKey())) {
            return stored.stream().readAllBytes();
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "生产单文件导出失败");
        }
    }

    /** The editable Word artifact is always DOCX, whatever the upload was called. */
    private String wordFileName(String productionName, String originalName) {
        var value = productionName == null || productionName.isBlank() ? originalName : productionName.trim();
        if (value == null || value.isBlank()) value = "生产单";
        var base = value.replaceAll("(?i)(-edited)*\\.docx?$", "");
        if (base.isBlank()) base = "生产单";
        return base + ".docx";
    }

    private String extension(String fileName) {
        if (fileName == null) return "";
        var index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index);
    }

    public record Download(String fileName, String contentType, byte[] content) {
    }
}
