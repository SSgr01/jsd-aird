package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ColumnTableLayoutCompilerGoldenTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ColumnTableLayoutCompiler compiler = new ColumnTableLayoutCompiler(mapper);

    @Test
    void detectsOneColumnRepeatedRegionWithSimpleGeometry() {
        var sheet = mapper.createObjectNode()
                .put("id", "sheet-1").put("sheetId", "sheet-1").put("usedRange", "A1:F8");
        var semantic = sheet.putArray("semanticCells");
        cell(semantic.addObject(), "A1", "A1:B1", "属性", false, true);
        for (var row = 2; row <= 8; row++) {
            cell(semantic.addObject(), "A" + row, "A" + row + ":B" + row, "字段" + row, false, true);
        }
        var candidates = sheet.putArray("candidateCells");
        for (var row = 1; row <= 8; row++) {
            for (var column = 3; column <= 6; column++) {
                cell(candidates.addObject(), columnName(column) + row, "", "", true, true);
            }
        }

        var region = compiler.detect(sheet, "sheet-1");

        assertThat(region).isNotNull();
        assertThat(region.path("type").asText()).isEqualTo("COLUMN_TABLE");
        assertThat(region.path("structure").path("repeatAxis").asText()).isEqualTo("COLUMN");
        assertThat(region.path("structure").path("headerRange").asText()).isNotBlank();
        assertThat(region.path("structure").path("dataRange").asText()).isNotBlank();
        assertThat(region.path("structure").has("recordProjection")).isFalse();
    }

    @Test
    void enrichesAConfirmedColumnRegionWithoutDerivedMemberModels() {
        var facts = mapper.createObjectNode();
        var sheet = facts.putArray("sheets").addObject().put("sheetId", "sheet-1");
        var cells = sheet.putArray("candidateCells");
        cell(cells.addObject(), "A1", "A1:B1", "属性", false, true);
        for (var row = 2; row <= 5; row++) {
            cell(cells.addObject(), "A" + row, "A" + row + ":B" + row, "字段" + row, false, true);
            for (var column = 3; column <= 5; column++) {
                cell(cells.addObject(), columnName(column) + row, "", "", true, true);
            }
        }
        var region = mapper.createObjectNode().put("type", "COLUMN_TABLE")
                .put("sheetId", "sheet-1").put("range", "A1:E5");

        assertThat(compiler.enrich(region, facts)).isTrue();
        assertThat(region.path("structure").path("repeatAxis").asText()).isEqualTo("COLUMN");
        assertThat(region.path("structure").fieldNames()).toIterable()
                .contains("headerRange", "dataRange", "recordHeight", "recordWidth", "recordStride")
                .doesNotContain("recordProjection", "fieldRows", "columnSlots");
    }

    @Test
    void leavesDenseHeaderAndVerticalRecordsToRowTableDetector() {
        var sheet = mapper.createObjectNode()
                .put("id", "sheet-1").put("sheetId", "sheet-1").put("usedRange", "A1:I8");
        var semantic = sheet.putArray("semanticCells");
        cell(semantic.addObject(), "A1", "A1:B1", "文件类型", false, true);
        cell(semantic.addObject(), "C1", "C1:E1", "操作规程", false, true);
        String[] headers = {"品名", "外观", "粘度", "固含", "比重", "折射率", "酸值", "备注", "状态"};
        for (int column = 1; column <= headers.length; column++) {
            cell(semantic.addObject(), columnName(column) + "4", "", headers[column - 1], false, true);
        }
        var candidates = sheet.putArray("candidateCells");
        for (int row = 4; row <= 8; row++) {
            for (int column = 1; column <= headers.length; column++) {
                cell(candidates.addObject(), columnName(column) + row, "", "", true, true);
            }
        }

        assertThat(compiler.detect(sheet, "sheet-1")).isNull();
    }

    private void cell(
            ObjectNode cell, String address, String mergedRange, String value,
            boolean inputCandidate, boolean border
    ) {
        cell.put("address", address).put("value", value)
                .put("inputCandidate", inputCandidate).put("hasBorder", border);
        if (!mergedRange.isBlank()) cell.put("mergedRange", mergedRange);
    }

    private String columnName(int column) {
        return Character.toString((char) ('A' + column - 1));
    }
}
