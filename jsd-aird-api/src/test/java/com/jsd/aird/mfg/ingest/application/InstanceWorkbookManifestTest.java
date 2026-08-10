package com.jsd.aird.mfg.ingest.application;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceWorkbookManifestTest {

    @Test
    void readsStableTemplateAndBindingIdentityFromHiddenSheet() throws Exception {
        var versionId = UUID.randomUUID();
        byte[] content;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(InstanceWorkbookManifest.SHEET_NAME);
            row(sheet, 0, "JSD_INSTANCE_TEMPLATE", "1");
            row(sheet, 1, "templateVersionId", versionId.toString());
            row(sheet, 2, "schemaHash", "schema");
            row(sheet, 3, "mappingHash", "mapping");
            row(sheet, 4, "structureFingerprint", "structure");
            row(sheet, 7, "binding-1", "JSD_B_BINDING_1");
            workbook.write(output);
            content = output.toByteArray();
        }

        var manifest = InstanceWorkbookManifest.read(content);

        assertThat(manifest).isNotNull();
        assertThat(manifest.templateVersionId()).isEqualTo(versionId);
        assertThat(manifest.bindingNames()).containsEntry("binding-1", "JSD_B_BINDING_1");
    }

    private void row(org.apache.poi.ss.usermodel.Sheet sheet, int index, String key, String value) {
        var row = sheet.createRow(index);
        row.createCell(0).setCellValue(key);
        row.createCell(1).setCellValue(value);
    }
}
