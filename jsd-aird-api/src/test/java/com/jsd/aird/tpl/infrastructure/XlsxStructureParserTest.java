package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.application.RuleBasedRecognitionEngine;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class XlsxStructureParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XlsxStructureParser parser = new XlsxStructureParser(objectMapper);

    @Test
    void preservesMergedRangesDimensionsAndCoreStylesInUniverSnapshot() throws Exception {
        byte[] source;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("测试");
            sheet.setColumnWidth(0, 18 * 256);
            var row = sheet.createRow(0);
            row.setHeightInPoints(36);
            var cell = row.createCell(0);
            cell.setCellValue("项目");
            var style = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            font.setFontHeightInPoints((short) 14);
            style.setFont(font);
            style.setFillForegroundColor((short) 42);
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setBorderBottom(BorderStyle.THIN);
            style.setWrapText(true);
            cell.setCellStyle(style);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 1, 4));
            for (int column = 1; column <= 4; column++) {
                var blank = row.createCell(column);
                blank.setCellStyle(style);
            }
            workbook.write(output);
            source = output.toByteArray();
        }

        var result = parser.parse(new ByteArrayInputStream(source));
        var sheet = result.initialEditorSnapshot().path("sheets").path("sheet-1");

        assertThat(result.structureSummary().path("structureVersion").asInt()).isEqualTo(5);
        assertThat(sheet.path("mergeData")).singleElement().satisfies(merge -> {
            assertThat(merge.path("startColumn").asInt()).isEqualTo(1);
            assertThat(merge.path("endColumn").asInt()).isEqualTo(4);
        });
        assertThat(sheet.path("rowData").path("0").path("h").asInt()).isEqualTo(48);
        assertThat(sheet.path("columnData").path("0").path("w").asInt()).isGreaterThan(100);
        assertThat(sheet.path("cellData").path("0").path("0").path("s").path("bl").asInt())
                .isEqualTo(1);
        var reparsed = new UniverSnapshotStructureParser(objectMapper).parse(new ByteArrayInputStream(
                objectMapper.writeValueAsBytes(result.initialEditorSnapshot())
        ));
        assertThat(reparsed.structureSummary().path("regions").findValuesAsText("regionId"))
                .containsExactlyElementsOf(result.structureSummary().path("regions").findValuesAsText("regionId"));
    }

    @Test
    void verifiesTheProvidedCustomerWorkbookWhenItIsAvailable() throws Exception {
        var source = Path.of(
                "C:/Users/Administrator/Downloads/阳离子单体、树脂的性能及应用测试_空数据模板.xlsx"
        );
        Assumptions.assumeTrue(Files.exists(source));
        try (var input = Files.newInputStream(source)) {
            var result = parser.parse(input);
            var summary = result.structureSummary();
            var sheet = result.initialEditorSnapshot().path("sheets").path("sheet-1");
            assertThat(summary.path("sheets").get(0).path("usedRange").asText()).isEqualTo("A1:J37");
            assertThat(summary.path("mergedRegionCount").asInt()).isEqualTo(21);
            assertThat(summary.path("regionCount").asInt()).isGreaterThan(3);
            assertThat(summary.path("sheets").get(0).path("candidateCellsTruncated").asBoolean()).isFalse();
            assertThat(sheet.path("mergeData")).hasSize(21);
            assertThat(sheet.path("rowData")).hasSizeGreaterThanOrEqualTo(35);
            assertThat(sheet.path("columnData")).hasSizeGreaterThanOrEqualTo(10);
            var reparsed = new UniverSnapshotStructureParser(objectMapper).parse(new ByteArrayInputStream(
                    objectMapper.writeValueAsBytes(result.initialEditorSnapshot())
            ));
            assertThat(reparsed.structureSummary().path("regions").findValuesAsText("regionId"))
                    .containsExactlyElementsOf(summary.path("regions").findValuesAsText("regionId"));

            var engine = new RuleBasedRecognitionEngine(
                    objectMapper, new JsonCanonicalizer(objectMapper)
            );
            var recognition = engine.recognize(
                    TemplateFormat.XLSX, source.getFileName().toString(), summary
            );
            assertThat(recognition.suggestions()).anySatisfy(suggestion -> {
                assertThat(suggestion.payload().path("fieldName").asText()).isEqualTo("项目");
                assertThat(suggestion.payload().path("locator").path("labelAddress").asText())
                        .isEqualTo("A3");
                assertThat(suggestion.payload().path("locator").path("address").asText())
                        .isEqualTo("B3:I3");
            });
            assertThat(recognition.suggestions()).anySatisfy(suggestion -> {
                assertThat(suggestion.payload().path("fieldName").asText()).isEqualTo("测试数据记录");
                assertThat(suggestion.payload().path("kind").asText()).isEqualTo("MATRIX");
                assertThat(suggestion.payload().path("locator").path("labelRange").asText())
                        .isEqualTo("A17:A35");
                assertThat(suggestion.payload().path("matrixModel").path("rowHeaderRange").asText())
                        .isNotBlank();
                assertThat(suggestion.payload().path("matrixModel").path("columnHeaderRange").asText())
                        .isNotBlank();
                assertThat(suggestion.payload().path("matrixModel").path("dataRange").asText())
                        .isNotBlank();
                assertThat(suggestion.payload().path("matrixModel").path("headerTree")).isNotEmpty();
                assertThat(suggestion.payload().path("matrixModel").path("expansion").path("columns").asBoolean())
                        .isTrue();
            });
            var matrixRegionId = recognition.suggestions().stream()
                    .filter(suggestion -> "测试数据记录".equals(
                            suggestion.payload().path("fieldName").asText()
                    ))
                    .map(suggestion -> suggestion.payload().path("regionId").asText())
                    .findFirst().orElseThrow();
            assertThat(recognition.suggestions().stream().filter(suggestion -> matrixRegionId.equals(
                    suggestion.payload().path("regionId").asText()
            ))).hasSize(1);
        }
    }

    @Test
    void keepsAllStyledCellsAndSplitsOversizedRegionsForModelAnalysis() throws Exception {
        byte[] source;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("大表");
            var style = workbook.createCellStyle();
            style.setBorderBottom(BorderStyle.THIN);
            for (int rowIndex = 0; rowIndex < 60; rowIndex++) {
                var row = sheet.createRow(rowIndex);
                for (int column = 0; column < 40; column++) row.createCell(column).setCellStyle(style);
            }
            workbook.write(output);
            source = output.toByteArray();
        }
        var summary = parser.parse(new ByteArrayInputStream(source)).structureSummary();
        assertThat(summary.path("candidateCells")).hasSize(2_400);
        assertThat(summary.path("sheets").get(0).path("candidateCellsTruncated").asBoolean()).isFalse();
        assertThat(summary.path("regions")).anySatisfy(region -> {
            assertThat(region.path("hasChildren").asBoolean()).isTrue();
            assertThat(region.path("requiresModel").asBoolean()).isFalse();
        });
        assertThat(summary.path("regions")).anySatisfy(region -> {
            assertThat(region.path("analysisChild").asBoolean()).isTrue();
            assertThat(region.path("parentRegionId").asText()).isNotBlank();
            assertThat(region.path("cellCount").asInt()).isLessThanOrEqualTo(160);
        });
    }
}
