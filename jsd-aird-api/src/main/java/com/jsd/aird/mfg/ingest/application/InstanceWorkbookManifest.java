package com.jsd.aird.mfg.ingest.application;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Hidden, deterministic metadata carried by downloadable instance workbooks. */
public record InstanceWorkbookManifest(
        UUID templateVersionId,
        String schemaHash,
        String mappingHash,
        String structureFingerprint,
        Map<String, String> bindingNames
) {
    public static final String SHEET_NAME = "_JSD_META";

    public static InstanceWorkbookManifest read(byte[] content) {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null || !"JSD_INSTANCE_TEMPLATE".equals(text(sheet, 0, 0))) return null;
            var bindings = new LinkedHashMap<String, String>();
            for (var row = 7; row <= sheet.getLastRowNum(); row++) {
                var bindingId = text(sheet, row, 0);
                var name = text(sheet, row, 1);
                if (!bindingId.isBlank() && !name.isBlank()) bindings.put(bindingId, name);
            }
            return new InstanceWorkbookManifest(
                    UUID.fromString(text(sheet, 1, 1)), text(sheet, 2, 1),
                    text(sheet, 3, 1), text(sheet, 4, 1), Map.copyOf(bindings));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(org.apache.poi.ss.usermodel.Sheet sheet, int row, int column) {
        var sourceRow = sheet.getRow(row);
        if (sourceRow == null || sourceRow.getCell(column) == null) return "";
        return sourceRow.getCell(column).toString().trim();
    }
}
