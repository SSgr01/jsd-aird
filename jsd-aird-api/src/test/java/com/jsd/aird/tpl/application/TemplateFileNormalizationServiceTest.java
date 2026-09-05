package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import com.jsd.aird.ops.application.port.FileStorageFacade;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class TemplateFileNormalizationServiceTest {

    private final TemplateFileNormalizationService service =
            new TemplateFileNormalizationService(mock(FileStorageFacade.class));

    @Test
    void convertsXlsToCanonicalXlsxAndKeepsFormatMetadata() throws Exception {
        var executable = libreOfficeExecutable();
        Assumptions.assumeTrue(libreOfficeAvailable(executable), "本机未安装 LibreOffice，跳过真实 XLS 转换");
        byte[] source;
        try (var workbook = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("配方");
            var row = sheet.createRow(0);
            row.setHeightInPoints(24);
            var style = workbook.createCellStyle();
            style.setBorderBottom(BorderStyle.THIN);
            var title = workbook.createFont();
            title.setBold(true);
            style.setFont(title);
            var name = row.createCell(0);
            name.setCellValue("物料名称");
            name.setCellStyle(style);
            row.createCell(1).setCellValue("数量");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            sheet.setColumnWidth(0, 30 * 256);
            workbook.write(output);
            source = output.toByteArray();
        }

        var result = new TemplateFileNormalizationService(
                mock(FileStorageFacade.class), executable, java.time.Duration.ofSeconds(90))
                .normalize("配方.xls", "application/vnd.ms-excel", source);

        assertThat(result.originalFormat()).isEqualTo("XLS");
        assertThat(result.normalizedFormat()).isEqualTo("XLSX");
        assertThat(result.normalizationStatus()).isEqualTo("NORMALIZED");
        assertThat(result.normalizedName()).isEqualTo("配方.xlsx");
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(result.normalizedBytes()))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("物料名称");
            assertThat(workbook.getSheetAt(0).getNumMergedRegions()).isGreaterThanOrEqualTo(1);
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getCellStyle().getBorderBottom())
                    .isNotEqualTo(BorderStyle.NONE);
            assertThat(workbook.getSheetAt(0).getRow(0).getHeightInPoints()).isGreaterThan(15);
            assertThat(workbook.getSheetAt(0).getColumnWidth(0)).isGreaterThan(20 * 256);
        }
    }

    private String libreOfficeExecutable() {
        var configured = System.getenv("JSD_AIRD_LIBREOFFICE_EXECUTABLE");
        if (configured != null && !configured.isBlank()) return configured;
        var windows = Path.of("C:/Program Files/LibreOffice/program/soffice.com");
        return Files.isRegularFile(windows) ? windows.toString() : "soffice";
    }

    private boolean libreOfficeAvailable(String executable) {
        try {
            var process = new ProcessBuilder(executable, "--headless", "--version")
                    .redirectErrorStream(true).start();
            boolean completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            return completed && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
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
