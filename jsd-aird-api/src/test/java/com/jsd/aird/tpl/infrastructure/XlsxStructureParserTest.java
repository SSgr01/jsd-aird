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

        assertThat(result.structureSummary().path("structureVersion").asInt()).isEqualTo(6);
        assertThat(sheet.path("mergeData")).singleElement().satisfies(merge -> {
            assertThat(merge.path("startColumn").asInt()).isEqualTo(1);
            assertThat(merge.path("endColumn").asInt()).isEqualTo(4);
        });
        assertThat(sheet.path("rowData").path("0").path("h").asInt()).isEqualTo(48);
        assertThat(sheet.path("columnData").path("0").path("w").asInt()).isGreaterThan(100);
        var styleId = sheet.path("cellData").path("0").path("0").path("s").asText();
        assertThat(styleId).isNotBlank();
        assertThat(result.initialEditorSnapshot().path("styles").path(styleId).path("bl").asInt())
                .isEqualTo(1);
        assertThat(result.initialEditorSnapshot().path("styles").path(styleId).path("bd")
                .path("b").path("s").asInt()).isEqualTo(1);
        assertThat(result.structureSummary().path("candidateCells").get(0).path("hasBorder").asBoolean())
                .isTrue();
        var reparsed = new UniverSnapshotStructureParser(objectMapper).parse(new ByteArrayInputStream(
                objectMapper.writeValueAsBytes(result.initialEditorSnapshot())
        ));
        assertThat(reparsed.structureSummary().path("sheets").get(0).path("semanticCells"))
                .isEqualTo(result.structureSummary().path("sheets").get(0).path("semanticCells"));
        assertThat(reparsed.structureSummary().path("layoutSpans"))
                .isEqualTo(result.structureSummary().path("layoutSpans"));
        assertThat(reparsed.structureSummary().path("candidateCells").get(0).path("hasBorder").asBoolean())
                .isTrue();
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
            assertThat(summary.path("sheets").get(0).path("semanticCells")).isNotEmpty();
            assertThat(summary.path("layoutSpans")).isNotEmpty();
            assertThat(summary.path("sheets").get(0).path("candidateCellsTruncated").asBoolean()).isFalse();
            assertThat(sheet.path("mergeData")).hasSize(21);
            assertThat(sheet.path("rowData")).hasSizeGreaterThanOrEqualTo(35);
            assertThat(sheet.path("columnData")).hasSizeGreaterThanOrEqualTo(10);
            var reparsed = new UniverSnapshotStructureParser(objectMapper).parse(new ByteArrayInputStream(
                    objectMapper.writeValueAsBytes(result.initialEditorSnapshot())
            ));
            assertThat(reparsed.structureSummary().path("sheets").get(0).path("semanticCells"))
                    .isEqualTo(summary.path("sheets").get(0).path("semanticCells"));
        }
    }

    @Test
    void preservesBordersFromTheOptimizedWorkbookWhenItIsAvailable() throws Exception {
        var source = Path.of(
                "C:/Users/Administrator/Downloads/干净模板表_整理完成/原表优化后/光引发剂对比测试模板.xlsx"
        );
        Assumptions.assumeTrue(Files.exists(source));
        try (var input = Files.newInputStream(source)) {
            var result = parser.parse(input);
            var snapshot = result.initialEditorSnapshot();
            var styledCells = result.structureSummary().path("candidateCells").findValues("hasBorder");
            assertThat(styledCells).isNotEmpty();
            assertThat(styledCells).anyMatch(node -> node.asBoolean());
            assertThat(snapshot.toString()).contains("\"bd\"");
            var reparsed = new UniverSnapshotStructureParser(objectMapper).parse(new ByteArrayInputStream(
                    objectMapper.writeValueAsBytes(snapshot)
            ));
            assertThat(reparsed.structureSummary().path("candidateCells").findValues("hasBorder"))
                    .anyMatch(node -> node.asBoolean());
        }
    }

    @Test
    void keepsAllStyledCellsAndCompressesTheirLayoutWithoutBusinessPreclassification() throws Exception {
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
        assertThat(summary.path("regions")).isEmpty();
        assertThat(summary.path("layoutSpans")).hasSize(60);
        assertThat(summary.path("borderSegments")).hasSize(60);
        assertThat(summary.path("rowProfiles")).hasSize(60);
        assertThat(summary.path("columnProfiles")).hasSize(40);
    }

    @Test
    void parsesTheM687StandardReferenceAsPhysicalFactsWhenAvailable() throws Exception {
        var source = Path.of("../chatgpt解析的标准模板/M-687_NT_标准化业务语义模板.xlsx")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.exists(source));
        try (var input = Files.newInputStream(source)) {
            var summary = parser.parse(input).structureSummary();
            assertThat(summary.path("structureVersion").asInt()).isEqualTo(6);
            assertThat(summary.path("sheets")).hasSize(6);
            assertThat(summary.path("sheets").get(0).path("name").asText()).isEqualTo("标准化任务单");
            assertThat(summary.path("sheets").get(0).path("usedRange").asText()).isEqualTo("A1:L46");
            assertThat(summary.path("sheets").get(4).path("name").asText()).isEqualTo("原始_M-687 NT");
            assertThat(summary.path("sheets").get(4).path("hidden").asBoolean()).isTrue();
            assertThat(summary.path("structureHints")).anySatisfy(hint -> {
                assertThat(hint.path("sheetId").asText()).isEqualTo("sheet-5");
                assertThat(hint.path("hintType").asText()).isEqualTo("HIDDEN_SHEET");
            });
            assertThat(summary.path("regions")).isEmpty();
            assertThat(summary.path("sheets").get(0).path("semanticCells")).isNotEmpty();
            assertThat(summary.path("layoutSpans")).isNotEmpty();
        }
    }
}
