package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

import com.jsd.aird.ops.application.port.FileStorageFacade;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class TemplateFileNormalizationServiceTest {

    private final TemplateFileNormalizationService service =
            new TemplateFileNormalizationService(mock(FileStorageFacade.class));

    @Test
    void convertsXlsToCanonicalXlsxAndKeepsFormatMetadata() throws Exception {
        byte[] source;
        try (var workbook = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var row = workbook.createSheet("配方").createRow(0);
            row.createCell(0).setCellValue("物料名称");
            row.createCell(1).setCellValue("数量");
            workbook.write(output);
            source = output.toByteArray();
        }

        var result = service.normalize("配方.xls", "application/vnd.ms-excel", source);

        assertThat(result.originalFormat()).isEqualTo("XLS");
        assertThat(result.normalizedFormat()).isEqualTo("XLSX");
        assertThat(result.normalizationStatus()).isEqualTo("NORMALIZED");
        assertThat(result.normalizedName()).isEqualTo("配方.xlsx");
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(result.normalizedBytes()))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("物料名称");
        }
    }

    @Test
    void convertsUtf8AndGbkCsvToCanonicalXlsx() throws Exception {
        var utf8 = service.normalize("数据.csv", "text/csv", "名称,数量\r\n树脂,2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var gbk = service.normalize("数据-gbk.csv", "text/csv", "名称,数量\r\n树脂,2".getBytes(Charset.forName("GBK")));

        assertThat(utf8.normalizedFormat()).isEqualTo("XLSX");
        assertThat(gbk.normalizedFormat()).isEqualTo("XLSX");
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(gbk.normalizedBytes()))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("树脂");
        }
    }

    @Test
    void rejectsCorruptLegacyOfficeFileWithoutReturningAHalfNormalizedResult() {
        assertThatThrownBy(() -> service.normalize("broken.xls", "application/octet-stream", new byte[] {1, 2, 3}))
                .hasMessageContaining("损坏");
    }

    @Test
    void rejectsMismatchedStandardOfficeExtensionBeforeParsing() {
        assertThatThrownBy(() -> service.normalize("renamed.docx", "application/octet-stream", new byte[] {0, 1, 2, 3}))
                .hasMessageContaining("格式与扩展名不一致");
    }

    @Test
    void rejectsEmptyOfficeFileWithoutCreatingNormalizedBytes() {
        assertThatThrownBy(() -> service.normalize("empty.doc", "application/msword", new byte[0]))
                .hasMessageContaining("为空");
    }
}
