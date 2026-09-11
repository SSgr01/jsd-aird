package com.jsd.aird.mfg.upload.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.upload.application.port.ProductionUploadRepository;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

@Service
public class ProductionUploadExportService {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ProductionUploadRepository repository;
    private final FileObjectRepository files;
    private final ObjectStorage storage;
    private final SnapshotWorkbookExporter workbookExporter;
    private final ObjectMapper objectMapper;

    public ProductionUploadExportService(ProductionUploadRepository repository,
                                         FileObjectRepository files,
                                         ObjectStorage storage,
                                         SnapshotWorkbookExporter workbookExporter,
                                         ObjectMapper objectMapper) {
        this.repository = repository;
        this.files = files;
        this.storage = storage;
        this.workbookExporter = workbookExporter;
        this.objectMapper = objectMapper;
    }

    public Download export(UUID id) {
        var actor = ActorContext.required();
        var record = repository.find(actor.organizationId(), id)
                .filter(item -> !"DELETED".equals(item.status()))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单不存在"));
        if (record.originalName() != null
                && record.originalName().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            var snapshot = record.workbookSnapshot();
            if (snapshot == null || !snapshot.isObject() || snapshot.path("sheets").isEmpty()) {
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

    private String extension(String fileName) {
        if (fileName == null) return "";
        var index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index);
    }

    public record Download(String fileName, String contentType, byte[] content) {
    }
}
