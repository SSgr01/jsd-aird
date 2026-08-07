package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StructurePrimitiveRecognizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsCornerAndColumnRecordProjectionForBlankRuntimeHeaders() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A4", 4, 1, "引发剂", "A4:B4", false, ""));
        semanticCells.add(cell("A5", 5, 1, "测试项目", "A5:A6", false, ""));
        semanticCells.add(cell("B5", 5, 2, "结果", "", false, ""));
        for (var row : new int[]{12, 16, 19}) {
            for (var column = 3; column <= 8; column++) {
                semanticCells.add(cell(address(column, row), row, column, "", "", false, "AVERAGE"));
            }
        }

        var candidates = objectMapper.createArrayNode();
        for (var column = 3; column <= 8; column++) {
            for (var row = 5; row <= 19; row++) {
                candidates.add(cell(address(column, row), row, column, "", "", true, ""));
            }
        }
        var sheet = objectMapper.createObjectNode()
                .put("id", "sheet-1")
                .put("usedRange", "A1:H19");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper).recognize(structure);
        com.fasterxml.jackson.databind.JsonNode matrixPrimitive = null;
        for (var primitive : result) {
            if ("MATRIX".equals(primitive.path("blockType").asText())) {
                matrixPrimitive = primitive;
                break;
            }
        }
        assertThat(matrixPrimitive).isNotNull();
        var details = matrixPrimitive.path("structure");
        assertThat(details.path("cornerRange").asText()).isEqualTo("A4:B4");
        assertThat(details.path("rowHeaderRange").asText()).isEqualTo("A5:B19");
        assertThat(details.path("columnHeaderRange").asText()).isEqualTo("C4:H4");
        assertThat(details.path("crossDataRange").asText()).isEqualTo("C5:H19");
        assertThat(details.path("semanticMode").asText()).isEqualTo("CROSS_TAB");
        assertThat(details.path("recordAxis").asText()).isEqualTo("COLUMN");
        assertThat(details.path("recordHeight").asInt()).isEqualTo(16);
        assertThat(details.path("recordProjection").path("recordColumns"))
                .extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .containsExactly("C", "D", "E", "F", "G", "H");
    }

    @Test
    void detectsBlankRuntimeGridFromBordersAndKeepsPopulatedResultInsideTheGrid() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A4", 4, 1, "综合测试", "A4:D4", false, ""));
        semanticCells.add(cell("A5", 5, 1, "物性测试", "A5:A10", false, ""));
        semanticCells.add(cell("B5", 5, 2, "", "", false, ""));
        semanticCells.add(cell("C5", 5, 3, "外观", "", false, ""));
        semanticCells.add(cell("D5", 5, 4, "目测", "", false, ""));
        semanticCells.add(cell("E7", 7, 5, "120", "", true, ""));

        var candidates = objectMapper.createArrayNode();
        for (var row = 4; row <= 10; row++) {
            for (var column = 5; column <= 14; column++) {
                candidates.add(cell(address(column, row), row, column, "", "", true, ""));
            }
        }
        var sheet = objectMapper.createObjectNode()
                .put("id", "sheet-1")
                .put("usedRange", "A1:N10");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper).recognize(structure);
        com.fasterxml.jackson.databind.JsonNode matrix = null;
        for (var primitive : result) {
            if ("MATRIX".equals(primitive.path("blockType").asText())) {
                matrix = primitive;
                break;
            }
        }
        assertThat(matrix).isNotNull();
        assertThat(matrix.path("structure").path("columnHeaderRange").asText()).isEqualTo("E4:N4");
        assertThat(matrix.path("structure").path("crossDataRange").asText()).isEqualTo("E5:N10");
        assertThat(result).anySatisfy(primitive ->
                assertThat(primitive.path("blockType").asText()).isEqualTo("BLANK_GRID_INPUT_SURFACE"));
    }

    @Test
    void ignoresFullWidthTitleRowsWhenTheWholeSheetSharesBorders() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A1", 1, 1, "实验项目", "A1:N1", false, ""));
        semanticCells.add(cell("A2", 2, 1, "实验目的：", "A2:N2", false, ""));
        semanticCells.add(cell("A3", 3, 1, "实验人员", "A3:C3", false, ""));
        semanticCells.add(cell("G3", 3, 7, "实验日期", "", false, ""));
        semanticCells.add(cell("A4", 4, 1, "主轴", "A4:C4", false, ""));
        semanticCells.add(cell("D4", 4, 4, "属性", "", false, ""));
        semanticCells.add(cell("A5", 5, 1, "分组", "A5:B10", false, ""));
        semanticCells.add(cell("C5", 5, 3, "项目", "", false, ""));
        semanticCells.add(cell("D5", 5, 4, "说明", "", false, ""));

        var candidates = objectMapper.createArrayNode();
        for (var row = 1; row <= 10; row++) {
            for (var column = 1; column <= 14; column++) {
                candidates.add(cell(address(column, row), row, column, "", "", true, ""));
            }
        }
        var sheet = objectMapper.createObjectNode()
                .put("id", "sheet-1")
                .put("usedRange", "A1:N10");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper).recognize(structure);
        com.fasterxml.jackson.databind.JsonNode matrixPrimitive = null;
        for (var primitive : result) {
            if ("MATRIX".equals(primitive.path("blockType").asText())) {
                matrixPrimitive = primitive;
                break;
            }
        }
        assertThat(matrixPrimitive).isNotNull();
        var details = matrixPrimitive.path("structure");
        assertThat(matrixPrimitive.path("range").asText()).isEqualTo("A4:N10");
        assertThat(details.path("cornerRange").asText()).isEqualTo("A4:D4");
        assertThat(details.path("rowHeaderRange").asText()).isEqualTo("A5:D10");
        assertThat(details.path("columnHeaderRange").asText()).isEqualTo("E4:N4");
        assertThat(details.path("crossDataRange").asText()).isEqualTo("E5:N10");
    }

    @Test
    void detectsAdjacentFormFieldsWithoutRequiringLabelPunctuation() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A3", 3, 1, "实验人员", "A3:C3", false, ""));
        semanticCells.add(cell("D3", 3, 4, "", "D3:F3", false, "").put("inputCandidate", true));
        semanticCells.add(cell("G3", 3, 7, "实验日期", "", false, ""));
        semanticCells.add(cell("H3", 3, 8, "", "H3:N3", false, "").put("inputCandidate", true));
        semanticCells.add(cell("A4", 4, 1, "主轴", "A4:C4", false, ""));
        semanticCells.add(cell("D4", 4, 4, "属性", "", false, ""));
        semanticCells.add(cell("A5", 5, 1, "分组", "A5:A10", false, ""));
        semanticCells.add(cell("C5", 5, 3, "项目", "", false, ""));
        semanticCells.add(cell("D5", 5, 4, "说明", "", false, ""));

        var candidates = objectMapper.createArrayNode();
        for (var row = 1; row <= 10; row++) {
            for (var column = 1; column <= 14; column++) {
                candidates.add(cell(address(column, row), row, column, "", "", true, ""));
            }
        }
        var sheet = objectMapper.createObjectNode().put("id", "sheet-1").put("usedRange", "A1:N10");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper).recognize(structure);

        assertThat(result).anySatisfy(primitive -> {
            assertThat(primitive.path("blockType").asText()).isEqualTo("FORM_REGION");
            assertThat(primitive.path("range").asText()).isEqualTo("A3:F3");
        });
        assertThat(result).anySatisfy(primitive -> {
            assertThat(primitive.path("blockType").asText()).isEqualTo("FORM_REGION");
            assertThat(primitive.path("range").asText()).isEqualTo("G3:N3");
        });
        assertThat(result).noneMatch(primitive -> "FORM_REGION".equals(primitive.path("blockType").asText())
                && primitive.path("range").asText().contains("A4"));
    }

    @Test
    void extendsStyledBlankMatrixSurfaceToThePhysicalUsedRange() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A4", 4, 1, "测试树脂样品或配方", "A4:C4", false, ""));
        semanticCells.add(cell("D4", 4, 4, "测试方法", "", false, ""));
        semanticCells.add(cell("A5", 5, 1, "物性测试", "A5:A100", false, ""));
        semanticCells.add(cell("C5", 5, 3, "外观", "", false, ""));
        semanticCells.add(cell("D5", 5, 4, "目测", "", false, ""));

        var candidates = objectMapper.createArrayNode();
        for (var row = 1; row <= 100; row++) {
            for (var column = 1; column <= 14; column++) {
                var candidate = cell(address(column, row), row, column, "", "", false, "");
                candidate.putObject("style").putObject("bd").putObject("b").put("s", 1);
                candidates.add(candidate);
            }
        }
        var sheet = objectMapper.createObjectNode()
                .put("id", "sheet-1")
                .put("usedRange", "A1:N100");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper).recognize(structure);
        com.fasterxml.jackson.databind.JsonNode matrixPrimitive = null;
        for (var primitive : result) {
            if ("MATRIX".equals(primitive.path("blockType").asText())) {
                matrixPrimitive = primitive;
                break;
            }
        }
        assertThat(matrixPrimitive).isNotNull();
        assertThat(matrixPrimitive.path("range").asText()).isEqualTo("A4:N100");
        assertThat(matrixPrimitive.path("structure").path("crossDataRange").asText())
                .isEqualTo("E5:N100");
    }

    @Test
    void ignoresALeftAxisGapWhenSelectingTheContinuousRightInputSurface() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A4", 4, 1, "测试树脂样品或配方", "A4:C4", false, ""));
        semanticCells.add(cell("D4", 4, 4, "测试方法", "", false, ""));
        semanticCells.add(cell("A5", 5, 1, "物性测试", "A5:A100", false, ""));
        semanticCells.add(cell("C5", 5, 3, "外观", "", false, ""));
        semanticCells.add(cell("D5", 5, 4, "目测", "", false, ""));

        var candidates = objectMapper.createArrayNode();
        for (var row = 1; row <= 100; row++) {
            for (var column = 1; column <= 14; column++) {
                if (row == 59 && column == 3) continue;
                var candidate = cell(address(column, row), row, column, "", "", false, "");
                candidate.putObject("style").putObject("bd").putObject("b").put("s", 1);
                candidates.add(candidate);
            }
        }
        var sheet = objectMapper.createObjectNode().put("id", "sheet-1").put("usedRange", "A1:N100");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper).recognize(structure);
        com.fasterxml.jackson.databind.JsonNode matrixPrimitive = null;
        for (var primitive : result) {
            if ("MATRIX".equals(primitive.path("blockType").asText())) {
                matrixPrimitive = primitive;
                break;
            }
        }
        assertThat(matrixPrimitive).isNotNull();
        assertThat(matrixPrimitive.path("range").asText()).isEqualTo("A4:N100");
        assertThat(matrixPrimitive.path("structure").path("crossDataRange").asText())
                .isEqualTo("E5:N100");
    }

    @Test
    void doesNotPromoteAStyledSingleSurfaceWithoutRuntimeColumnsToMatrix() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A4", 4, 1, "测试树脂样品或配方", "A4:C4", false, ""));
        semanticCells.add(cell("D4", 4, 4, "测试方法", "", false, ""));
        semanticCells.add(cell("A5", 5, 1, "物性测试", "A5:A20", false, ""));
        semanticCells.add(cell("C5", 5, 3, "外观", "", false, ""));
        semanticCells.add(cell("D5", 5, 4, "目测", "", false, ""));

        var candidates = objectMapper.createArrayNode();
        for (var row = 1; row <= 20; row++) {
            for (var column = 1; column <= 4; column++) {
                var candidate = cell(address(column, row), row, column, "", "", false, "");
                candidate.putObject("style").putObject("bd").putObject("b").put("s", 1);
                candidates.add(candidate);
            }
        }
        var sheet = objectMapper.createObjectNode().put("id", "sheet-1").put("usedRange", "A1:D20");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper).recognize(structure);
        assertThat(result).noneMatch(primitive -> "MATRIX".equals(primitive.path("blockType").asText()));
    }

    @Test
    void detectsOrdinaryRepeatedRowsFromBorderFactsWhenInputsAreLowConfidence() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A4", 4, 1, "序号", "", false, ""));
        semanticCells.add(cell("B4", 4, 2, "原料编号", "", false, ""));
        semanticCells.add(cell("C4", 4, 3, "投料量", "", false, ""));
        semanticCells.add(cell("D4", 4, 4, "备注", "", false, ""));
        for (var row = 5; row <= 11; row++) {
            semanticCells.add(cell(address(1, row), row, 1,
                    row == 10 ? "小计" : Integer.toString(row - 4), "", false, ""));
        }

        var candidates = objectMapper.createArrayNode();
        for (var row = 4; row <= 11; row++) {
            for (var column = 1; column <= 4; column++) {
                candidates.add(cell(address(column, row), row, column, "", "", true, ""));
            }
        }
        var sheet = objectMapper.createObjectNode()
                .put("id", "sheet-1")
                .put("usedRange", "A1:D11");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper).recognize(structure);
        assertThat(result).anySatisfy(primitive -> {
            assertThat(primitive.path("blockType").asText()).isEqualTo("ROW_TABLE");
            assertThat(primitive.path("range").asText()).isEqualTo("A4:D10");
            assertThat(primitive.path("validationStatus").asText()).isEqualTo("VALID");
            assertThat(primitive.path("structure").path("dataRange").asText()).isEqualTo("A5:D10");
            assertThat(primitive.path("structure").path("terminationRule").path("type").asText())
                    .isEqualTo("UNTIL_TOTAL_ROW");
        });
        assertThat(result).noneMatch(primitive -> "MATRIX".equals(primitive.path("blockType").asText()));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode cell(
            String address, int row, int column, String value, String mergedRange,
            boolean border, String formula
    ) {
        var cell = objectMapper.createObjectNode()
                .put("address", address)
                .put("sheetId", "sheet-1")
                .put("row", row)
                .put("column", column)
                .put("value", value)
                .put("mergedRange", mergedRange)
                .put("hasBorder", border);
        if (!formula.isBlank()) cell.put("factType", "FORMULA").put("formula", formula);
        return cell;
    }

    private String address(int column, int row) {
        var letter = (char) ('A' + column - 1);
        return letter + Integer.toString(row);
    }
}
