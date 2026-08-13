package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class GenericTabularStructureParserTest {

    private final GenericTabularStructureParser parser = new GenericTabularStructureParser();

    @Test
    void parsesUtf8BomCsvAndSuggestsHeaderAndDataRows() {
        var csv = "\uFEFF说明,,,\n物料编码,物料名称,粘度,单位\nM-001,树脂 A,12,cps\n";

        var result = parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "materials.csv");
        var sheet = result.sheets().getFirst();

        assertThat(result.format()).isEqualTo("CSV");
        assertThat(sheet.sheetName()).isEqualTo("CSV");
        assertThat(sheet.headerCandidates()).contains(2);
        assertThat(sheet.suggestedHeaderRow()).isEqualTo(2);
        assertThat(sheet.suggestedDataStartRow()).isEqualTo(3);
        assertThat(sheet.rows().get(2)).containsExactly("M-001", "树脂 A", "12", "cps");
    }

    @Test
    void preservesQuotedCsvValues() {
        var csv = "名称,备注\n树脂 A,\"含逗号,需复核\"\n";

        var result = parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "materials.csv");

        assertThat(result.sheets().getFirst().rows().get(1)).containsExactly("树脂 A", "含逗号,需复核");
    }

    @Test
    void keepsLayoutFactsAndFormulaTrustSeparateFromDisplayedValues() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("测试");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 1));
            sheet.createRow(0).createCell(0).setCellValue("合并标题");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(10);
            row.createCell(1).setCellValue(20);
            row.createCell(2).setCellFormula("A2+B2");
            sheet.getRow(1).getCell(2).getCellStyle().setLocked(true);
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(output);
            bytes = output.toByteArray();
        }

        var parsed = parser.parse(new ByteArrayInputStream(bytes), "formula.xlsx").sheets().getFirst();
        var formula = findCell(parsed.layoutIr(), "C2");

        assertThat(parsed.layoutIr().path("merges")).extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .contains("A1:B1");
        assertThat(formula.path("formulaExpression").asText()).isEqualTo("A2+B2");
        assertThat(formula.path("calculationSource").asText()).isIn("RECALCULATED", "CACHED");
        assertThat(formula.path("calculationStatus").asText()).isIn("VALID", "STALE_POSSIBLE");
        assertThat(parsed.structureFingerprint()).hasSize(64);
        assertThat(parsed.rows().get(1).get(2)).doesNotStartWith("=");
    }

    private com.fasterxml.jackson.databind.JsonNode findCell(com.fasterxml.jackson.databind.JsonNode layout, String address) {
        for (var cell : layout.path("cells")) if (address.equals(cell.path("address").asText())) return cell;
        throw new AssertionError("cell not found: " + address);
    }
}
