package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class StructurePrimitiveRecognizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StructurePrimitiveRecognizer recognizer = new StructurePrimitiveRecognizer(objectMapper);

    @Test
    void recognizesBasicInformationAsOneFormRegion() throws Exception {
        var structure = objectMapper.readTree("""
                {"sheets":[{"id":"sheet-1","usedRange":"A1:D4","semanticCells":[
                  {"sheetId":"sheet-1","address":"A1","row":1,"column":1,"value":"订单号："},
                  {"sheetId":"sheet-1","address":"B1","row":1,"column":2,"value":"","inputCandidate":true,"factType":"INPUT_CANDIDATE","hasBorder":true},
                  {"sheetId":"sheet-1","address":"A2","row":2,"column":1,"value":"客户名称："},
                  {"sheetId":"sheet-1","address":"B2","row":2,"column":2,"value":"","inputCandidate":true,"factType":"INPUT_CANDIDATE","hasBorder":true}
                ],"candidateCells":[
                  {"address":"B1","row":1,"column":2,"empty":true,"inputCandidate":true,"factType":"INPUT_CANDIDATE","hasBorder":true},
                  {"address":"B2","row":2,"column":2,"empty":true,"inputCandidate":true,"factType":"INPUT_CANDIDATE","hasBorder":true}
                ]}]}
                """);

        var result = recognizer.recognize(structure);

        assertThat(result).anyMatch(item -> "FORM_REGION".equals(item.path("blockType").asText()));
        assertThat(result).allMatch(item ->
                java.util.Set.of("FORM_REGION", "ROW_TABLE", "COLUMN_TABLE", "UNKNOWN")
                        .contains(item.path("blockType").asText()));
    }

    @Test
    void recognizesCompletedColumnRecordsFromTopAndLeftLabelBands() throws Exception {
        var sheet = sheet("A1:D4");
        addCell(sheet, "A1", 1, 1, "属性", true);
        addCell(sheet, "B1", 1, 2, "1#", true);
        addCell(sheet, "C1", 1, 3, "2#", true);
        addCell(sheet, "D1", 1, 4, "3#", true);
        for (var row = 2; row <= 4; row++) {
            addCell(sheet, "A" + row, row, 1, "指标" + row, true);
            for (var column = 2; column <= 4; column++) {
                addCell(sheet, columnName(column) + row, row, column, Integer.toString(row * column), true);
            }
        }
        var structure = objectMapper.createObjectNode();
        structure.putArray("sheets").add(sheet);

        var result = recognizer.recognize(structure);

        assertThat(result).anyMatch(item -> "COLUMN_TABLE".equals(item.path("blockType").asText())
                && "COLUMN".equals(item.path("structure").path("repeatAxis").asText()));
    }

    @Test
    void treatsExplicitLeftAttributesAndTopSampleNamesAsColumnRecords() throws Exception {
        var sheet = sheet("A1:C4");
        var values = new String[][]{
                {"属性", "样品甲", "样品乙"},
                {"外观", "透明", "微黄"},
                {"粘度", "低粘", "高粘"},
                {"状态", "合格", "合格"}
        };
        for (var row = 1; row <= values.length; row++) {
            for (var column = 1; column <= values[row - 1].length; column++) {
                addCell(sheet, columnName(column) + row, row, column, values[row - 1][column - 1], true);
            }
        }
        var structure = objectMapper.createObjectNode();
        structure.putArray("sheets").add(sheet);

        var result = recognizer.recognize(structure);

        assertThat(result).anyMatch(item -> "COLUMN_TABLE".equals(item.path("blockType").asText())
                && "COLUMN".equals(item.path("structure").path("recordAxis").asText()));
        assertThat(result).noneMatch(item -> "ROW_TABLE".equals(item.path("blockType").asText()));
    }

    @Test
    void keepsFilledMergedIdentityBandAsOneColumnRegion() throws Exception {
        var sheet = sheet("A1:H8");
        addCell(sheet, "A4", 4, 1, "引发剂", true);
        ((ObjectNode) sheet.withArray("semanticCells").get(0)).put("mergedRange", "A4:B4");
        ((ObjectNode) sheet.withArray("candidateCells").get(0)).put("mergedRange", "A4:B4");
        for (int column = 3; column <= 8; column++) {
            addCell(sheet, columnName(column) + "4", 4, column, "样品" + column, true);
        }
        for (int row = 5; row <= 8; row++) {
            addCell(sheet, "A" + row, row, 1, "分组" + row, true);
            addCell(sheet, "B" + row, row, 2, "指标" + row, true);
            for (int column = 3; column <= 8; column++) {
                addCell(sheet, columnName(column) + row, row, column, Integer.toString(row * column), true);
            }
        }
        var structure = objectMapper.createObjectNode();
        structure.putArray("sheets").add(sheet);

        var result = recognizer.recognize(structure);

        assertThat(result).anyMatch(item -> "COLUMN_TABLE".equals(item.path("blockType").asText())
                && "A4:H8".equals(item.path("range").asText())
                && "COLUMN".equals(item.path("structure").path("repeatAxis").asText()));
        assertThat(result).noneMatch(item -> "ROW_TABLE".equals(item.path("blockType").asText())
                && "A4:H8".equals(item.path("range").asText()));
    }

    private ObjectNode sheet(String usedRange) {
        var sheet = objectMapper.createObjectNode().put("id", "sheet-1").put("usedRange", usedRange);
        sheet.putArray("semanticCells");
        sheet.putArray("candidateCells");
        sheet.putArray("mergedRanges");
        return sheet;
    }

    private void addCell(ObjectNode sheet, String address, int row, int column, String value, boolean border) {
        var cell = objectMapper.createObjectNode()
                .put("sheetId", "sheet-1").put("address", address)
                .put("row", row).put("column", column).put("value", value)
                .put("hasBorder", border).put("factType", "VALUE");
        sheet.withArray("semanticCells").add(cell.deepCopy());
        sheet.withArray("candidateCells").add(cell.deepCopy());
    }

    private String columnName(int column) {
        return Character.toString((char) ('A' + column - 1));
    }
}
