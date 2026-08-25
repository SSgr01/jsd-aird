package com.jsd.aird.quality.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.quality.application.port.QualityDataStore;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.shared.security.ActorContext;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.UUID;

/** Exports the current quality record in the same blob-based flow as project documents. */
@Service
public class QualityDataExportService {
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final QualityDataStore repository;
    private final SnapshotWorkbookExporter workbookExporter;

    public QualityDataExportService(QualityDataStore repository, SnapshotWorkbookExporter workbookExporter) {
        this.repository = repository;
        this.workbookExporter = workbookExporter;
    }

    public Download export(UUID id) {
        var actor = ActorContext.required();
        var record = repository.record(actor.organizationId(), actor.role(), id);
        var snapshot = record.workbookSnapshot();
        byte[] content;
        if (snapshot != null && snapshot.isObject() && !snapshot.isEmpty()) {
            content = workbookExporter.export(
                    snapshot,
                    null,
                    record.data(),
                    new SnapshotWorkbookExporter.Manifest(
                            record.id().toString(),
                            record.businessType(),
                            record.categoryId().toString(),
                            "QUALITY",
                            null
                    )
            ).content();
        } else {
            content = exportStructured(record);
        }
        return new Download(fileName(record.businessNo()), XLSX_CONTENT_TYPE, content);
    }

    private byte[] exportStructured(QualityDataStore.RecordView record) {
        var type = QualityDataDefinitions.BY_ID.get(record.businessType());
        if (type == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "未知品管数据类型");
        }
        try (Workbook workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(safeSheetName(record.categoryName(), type.name()));
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("字段");
            header.createCell(1).setCellValue("内容");
            for (var index = 0; index < type.fields().size(); index++) {
                var field = type.fields().get(index);
                var row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(field.label());
                row.createCell(1).setCellValue(text(record.data().path(field.key())));
            }
            sheet.setColumnWidth(0, 24 * 256);
            sheet.setColumnWidth(1, 48 * 256);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.INTERNAL_ERROR, "品管数据导出失败");
        }
    }

    private String text(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        return value.isTextual() ? value.asText() : value.toString();
    }

    private String safeSheetName(String categoryName, String fallback) {
        var value = categoryName == null || categoryName.isBlank() ? fallback : categoryName;
        value = value.replaceAll("[\\\\/?*\\[\\]:]", "_").trim();
        return value.isBlank() ? "品管数据" : value.substring(0, Math.min(31, value.length()));
    }

    private String fileName(String businessNo) {
        var base = businessNo == null || businessNo.isBlank() ? "quality-record" : businessNo;
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        return base.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? base : base + ".xlsx";
    }

    public record Download(String fileName, String contentType, byte[] content) {}
}
