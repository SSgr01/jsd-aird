package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StructurePrimitiveRecognizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void topologyV2RetainsBlankColumnRecordSurfaceAsRuntimeEvidence() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A4", 4, 1, "对象", "A4:B4", false, ""));
        semanticCells.add(cell("A5", 5, 1, "属性组", "A5:A19", false, ""));
        semanticCells.add(cell("B5", 5, 2, "属性", "", false, ""));
        for (var row = 6; row <= 19; row++) {
            semanticCells.add(cell("B" + row, row, 2, "指标" + row, "", false, ""));
        }
        for (var row : new int[]{8, 12, 16, 19}) {
            for (var column = 3; column <= 8; column++) {
                semanticCells.add(cell(address(column, row), row, column, "", "", false, "AVERAGE"));
            }
        }
        var candidates = objectMapper.createArrayNode();
        for (var row = 4; row <= 19; row++) {
            for (var column = 3; column <= 8; column++) {
                candidates.add(cell(address(column, row), row, column, "", "", true, ""));
            }
        }
        var sheet = objectMapper.createObjectNode().put("id", "sheet-1").put("usedRange", "A1:H19");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper, true).recognize(structure);

        assertThat(result).anySatisfy(primitive -> {
            assertThat(primitive.path("blockType").asText()).isEqualTo("TABLE_TOPOLOGY_UNKNOWN");
            assertThat(primitive.path("range").asText()).isEqualTo("A4:H19");
            assertThat(primitive.path("structure").path("topologyEvidence")
                    .path("runtimeColumnMemberSurface").asBoolean()).isTrue();
            assertThat(primitive.path("structure").path("topologyEvidence")
                    .path("runtimeColumnMemberRange").asText()).isEqualTo("C4:H4");
        });
        assertThat(result).noneMatch(primitive -> "MATRIX".equals(primitive.path("blockType").asText()));
    }

    @Test
    void topologyV2DoesNotCollapseAmbiguousGroupedSurfaceIntoColumnTable() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A8", 8, 1, "属性组一", "A8:B15", false, ""));
        semanticCells.add(cell("A16", 16, 1, "属性组二", "A16:B26", false, ""));
        semanticCells.add(cell("A27", 27, 1, "属性组三", "A27:B37", false, ""));
        for (var row = 8; row <= 37; row++) {
            semanticCells.add(cell("C" + row, row, 3, "指标" + row, "", false, ""));
        }
        for (var row : new int[]{15, 26, 37}) {
            for (var column = 4; column <= 9; column++) {
                semanticCells.add(cell(address(column, row), row, column, "", "", false, "AVERAGE"));
            }
        }
        semanticCells.add(cell("A38", 38, 1, "说明一", "A38:I38", false, ""));
        semanticCells.add(cell("A39", 39, 1, "说明二", "A39:I39", false, ""));

        var candidates = objectMapper.createArrayNode();
        for (var row = 1; row <= 39; row++) {
            // Row 9 deliberately omits borders from the runtime record cells.
            // The left attribute band remains and the full record surface
            // resumes on row 10, matching a common grouped-column layout.
            var endColumn = row == 9 ? 3 : 9;
            for (var column = 1; column <= endColumn; column++) {
                candidates.add(cell(address(column, row), row, column, "", "", true, ""));
            }
        }
        var sheet = objectMapper.createObjectNode().put("id", "sheet-1").put("usedRange", "A1:I39");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper, true).recognize(structure);

        assertThat(result).anySatisfy(primitive -> {
            assertThat(primitive.path("blockType").asText()).isEqualTo("TABLE_TOPOLOGY_UNKNOWN");
            assertThat(primitive.path("range").asText()).isEqualTo("A8:I37");
            assertThat(primitive.path("structure").path("topologyEvidence")
                    .path("runtimeColumnMemberSurface").asBoolean()).isTrue();
        });
        assertThat(result).noneMatch(primitive -> "MATRIX".equals(primitive.path("blockType").asText()));
    }

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
            assertThat(primitive.path("range").asText()).isEqualTo("A1:N3");
            assertThat(primitive.path("structure").path("fieldSurfaces"))
                    .extracting(surface -> surface.path("range").asText())
                    .containsExactly("A3:F3", "G3:N3");
        });
        assertThat(result).noneMatch(primitive -> "FORM_REGION".equals(primitive.path("blockType").asText())
                && primitive.path("range").asText().contains("A4"));
    }

    @Test
    void treatsParserFactTypeInputCandidateAsAFormInputSurface() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A4", 4, 1, "产品名称", "", false, ""));
        semanticCells.add(cell("B4", 4, 2, "", "B4:C4", false, "")
                .put("factType", "INPUT_CANDIDATE"));
        semanticCells.add(cell("D4", 4, 4, "生产批号", "", false, ""));
        semanticCells.add(cell("E4", 4, 5, "", "E4:F4", false, "")
                .put("factType", "INPUT_CANDIDATE"));
        semanticCells.add(cell("A5", 5, 1, "生产日期", "", false, ""));
        semanticCells.add(cell("B5", 5, 2, "", "B5:C5", false, "")
                .put("factType", "INPUT_CANDIDATE"));
        semanticCells.add(cell("D5", 5, 4, "生产数量", "", false, ""));
        semanticCells.add(cell("E5", 5, 5, "", "E5:F5", false, "")
                .put("factType", "INPUT_CANDIDATE"));
        var candidates = semanticCells.deepCopy();
        for (var cell : candidates) ((com.fasterxml.jackson.databind.node.ObjectNode) cell)
                .put("hasBorder", true);
        var sheet = objectMapper.createObjectNode().put("id", "sheet-1").put("usedRange", "A4:F5");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper).recognize(structure);

        var form = java.util.stream.StreamSupport.stream(result.spliterator(), false)
                .filter(item -> "FORM_REGION".equals(item.path("blockType").asText()))
                .findFirst().orElseThrow(() -> new AssertionError(result.toPrettyString()));
        assertThat(form.path("structure").path("fieldSurfaces"))
                .extracting(item -> item.path("structure").path("valueRange").asText())
                .containsExactly("B4:C4", "E4:F4", "B5:C5", "E5:F5");
    }

    @Test
    void borderedMultiSectionFormIsNotMisclassifiedAsOneRepeatedRowTable() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A4", 4, 1, "产品名称", "", true, ""));
        semanticCells.add(cell("B4", 4, 2, "", "B4:C4", true, "")
                .put("factType", "INPUT_CANDIDATE"));
        semanticCells.add(cell("D4", 4, 4, "生产批号", "", true, ""));
        semanticCells.add(cell("E4", 4, 5, "", "E4:F4", true, "")
                .put("factType", "INPUT_CANDIDATE"));
        semanticCells.add(cell("A5", 5, 1, "生产日期", "", true, ""));
        semanticCells.add(cell("B5", 5, 2, "", "B5:C5", true, "")
                .put("factType", "INPUT_CANDIDATE"));
        semanticCells.add(cell("D5", 5, 4, "生产数量", "", true, ""));
        semanticCells.add(cell("E5", 5, 5, "", "E5:F5", true, "")
                .put("factType", "INPUT_CANDIDATE"));
        for (var section : new Object[][]{
                {"A6", 6, "不合格描述", "A6:A8", "B6:F8"},
                {"A9", 9, "原因分析", "A9:A13", "B9:F13"},
                {"A14", 14, "总经理批示", "A14:A15", "B14:F15"},
                {"A16", 16, "后续改进", "A16:A18", "B16:F18"},
                {"A19", 19, "跟踪效果", "A19:A21", "B19:F21"}
        }) {
            var address = (String) section[0];
            var row = (int) section[1];
            semanticCells.add(cell(address, row, 1, (String) section[2], (String) section[3], true, ""));
            semanticCells.add(cell("B" + row, row, 2, "", (String) section[4], true, "")
                    .put("factType", "INPUT_CANDIDATE"));
        }
        var candidates = semanticCells.deepCopy();
        for (var row = 4; row <= 21; row++) {
            for (var column = 1; column <= 6; column++) {
                var address = address(column, row);
                var exists = java.util.stream.StreamSupport.stream(candidates.spliterator(), false)
                        .anyMatch(cell -> address.equals(cell.path("address").asText()));
                if (!exists) candidates.add(cell(address, row, column, "", "", true, ""));
            }
        }
        var sheet = objectMapper.createObjectNode().put("id", "sheet-1").put("usedRange", "A1:F21");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper).recognize(structure);

        var form = java.util.stream.StreamSupport.stream(result.spliterator(), false)
                .filter(item -> "FORM_REGION".equals(item.path("blockType").asText()))
                .findFirst().orElseThrow(() -> new AssertionError(result.toPrettyString()));
        assertThat(form.path("range").asText()).isEqualTo("A4:F21");
        assertThat(form.path("structure").path("fieldSurfaces")).hasSize(9);
        assertThat(result).noneMatch(item -> "ROW_TABLE".equals(item.path("blockType").asText())
                && "A4:F21".equals(item.path("range").asText()));
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
            assertThat(primitive.path("structure").path("dataRange").asText()).isEqualTo("A5:D9");
            assertThat(primitive.path("structure").path("totalRange").asText()).isEqualTo("A10:D10");
            assertThat(primitive.path("structure").path("terminationRule").path("type").asText())
                    .isEqualTo("UNTIL_TOTAL_ROW");
        });
        assertThat(result).noneMatch(primitive -> "MATRIX".equals(primitive.path("blockType").asText()));
    }

    @Test
    void detectsCompletedStaticCrossTableBeforeGenericRowTable() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A2", 2, 1, "温度 / 配方", "", false, ""));
        for (var header : new String[]{"配方A", "配方B", "配方C", "配方D"}) {
            var column = header.charAt(header.length() - 1) - 'A' + 2;
            semanticCells.add(cell(address(column, 2), 2, column, header, "", false, ""));
        }
        var labels = new String[]{"25℃", "40℃", "60℃", "80℃"};
        var values = new int[][]{{1200, 1300, 1450, 1500}, {980, 1100, 1250, 1380},
                {760, 850, 960, 1050}, {620, 700, 810, 900}};
        for (int index = 0; index < labels.length; index++) {
            int row = index + 3;
            semanticCells.add(cell("A" + row, row, 1, labels[index], "", false, ""));
            for (int offset = 0; offset < values[index].length; offset++) {
                var numeric = cell(address(offset + 2, row), row, offset + 2,
                        Integer.toString(values[index][offset]), "", false, "");
                numeric.put("physicalValueType", "NUMERIC");
                semanticCells.add(numeric);
            }
        }
        var candidates = objectMapper.createArrayNode();
        for (int row = 2; row <= 6; row++) {
            for (int column = 1; column <= 5; column++) {
                candidates.add(cell(address(column, row), row, column, "", "", true, ""));
            }
        }
        var sheet = objectMapper.createObjectNode().put("id", "sheet-1").put("usedRange", "A1:E6");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper).recognize(structure);

        assertThat(result).anySatisfy(primitive -> {
            assertThat(primitive.path("blockType").asText()).isEqualTo("MATRIX");
            assertThat(primitive.path("range").asText()).isEqualTo("A2:E6");
            assertThat(primitive.path("structure").path("cornerRange").asText()).isEqualTo("A2");
            assertThat(primitive.path("structure").path("rowHeaderRange").asText()).isEqualTo("A3:A6");
            assertThat(primitive.path("structure").path("columnHeaderRange").asText()).isEqualTo("B2:E2");
            assertThat(primitive.path("structure").path("crossDataRange").asText()).isEqualTo("B3:E6");
        });
        assertThat(result).noneMatch(primitive -> "ROW_TABLE".equals(primitive.path("blockType").asText()));
    }

    @Test
    void detectsCompletedStaticColumnTableAndBuildsColumnProjection() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        var rows = new String[][]{
                {"属性", "样品A", "样品B", "样品C"},
                {"物料名称", "树脂A", "树脂B", "树脂C"},
                {"供应商", "供应商1", "供应商2", "供应商3"},
                {"粘度", "1200", "900", "1100"}
        };
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length; column++) {
                semanticCells.add(cell(address(column + 1, row + 1), row + 1, column + 1,
                        rows[row][column], "", false, ""));
            }
        }
        var candidates = objectMapper.createArrayNode();
        for (int row = 1; row <= rows.length; row++) {
            for (int column = 1; column <= rows[row - 1].length; column++) {
                candidates.add(cell(address(column, row), row, column, "", "", true, ""));
            }
        }
        var sheet = objectMapper.createObjectNode().put("id", "sheet-1").put("usedRange", "A1:D4");
        sheet.set("semanticCells", semanticCells);
        sheet.set("candidateCells", candidates);
        var structure = objectMapper.createObjectNode();
        structure.set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper).recognize(structure);

        assertThat(result).anySatisfy(primitive -> {
            assertThat(primitive.path("blockType").asText()).isEqualTo("COLUMN_TABLE");
            assertThat(primitive.path("range").asText()).isEqualTo("A1:D4");
            assertThat(primitive.path("structure").path("rowHeaderRange").asText()).isEqualTo("A2:A4");
            assertThat(primitive.path("structure").path("columnHeaderRange").asText()).isEqualTo("B1:D1");
            assertThat(primitive.path("structure").path("crossDataRange").asText()).isEqualTo("B2:D4");
            assertThat(primitive.path("structure").path("recordProjection").path("recordColumns"))
                    .extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                    .containsExactly("B", "C", "D");
        });
    }

    @Test
    void partitionsFormBandsIngredientsAndVariableHeightStepSlotsByTopology() throws Exception {
        var semanticCells = objectMapper.createArrayNode();
        semanticCells.add(cell("A1", 1, 1, "公司标题", "A1:J1", false, ""));
        semanticCells.add(cell("A2", 2, 1, "表单标题", "A2:J2", false, ""));
        semanticCells.add(cell("H3", 3, 8, "表单编号:CODE-001", "H3:J3", true, ""));
        for (var item : new Object[][]{
                {"A4", 4, 1, "类别"}, {"D4", 4, 4, "品名"}, {"H4", 4, 8, "订单号"},
                {"A5", 5, 1, "设备"}, {"D5", 5, 4, "批号"}, {"H5", 5, 8, "日期"},
                {"A6", 6, 1, "序号"}, {"B6", 6, 2, "编号"}, {"C6", 6, 3, "比例"},
                {"D6", 6, 4, "数量"}, {"E6", 6, 5, "批次"}, {"H6", 6, 8, "步骤"}
        }) semanticCells.add(cell((String) item[0], (int) item[1], (int) item[2],
                (String) item[3], "", true, ""));
        for (int row = 7; row <= 21; row++) {
            semanticCells.add(cell("A" + row, row, 1, Integer.toString(row - 6), "", true, ""));
        }
        semanticCells.add(cell("A22", 22, 1, "小计", "", true, ""));
        for (var item : new Object[][]{
                {"A23", 23, 1, "包装物料："}, {"F23", 23, 6, "包装规格："},
                {"A24", 24, 1, "实际产量："}, {"F24", 24, 6, "包装数量："},
                {"A25", 25, 1, "注：这里是一段不会变成字段的静态说明文字。"},
                {"A27", 27, 1, "制单人："}, {"D27", 27, 4, "完成人："}, {"I27", 27, 9, "监管人："}
        }) semanticCells.add(cell((String) item[0], (int) item[1], (int) item[2],
                (String) item[3], "", true, ""));

        var candidates = objectMapper.createArrayNode();
        for (int row = 3; row <= 25; row++) {
            for (int column = 1; column <= 10; column++) {
                candidates.add(cell(address(column, row), row, column, "", "", true, "").put("empty", true));
            }
        }
        // Univer may preserve styled cells outside the actual business area.
        // One adjacent signature row belongs to the form; remote noise does not.
        candidates.add(cell("A28", 28, 1, "", "", false, "").put("empty", true));
        candidates.add(cell("C28", 28, 3, "", "", false, "").put("empty", true));
        candidates.add(cell("P4", 4, 16, "", "", false, "").put("empty", true));
        candidates.add(cell("D31", 31, 4, "", "", false, "").put("empty", true));
        var merged = objectMapper.createArrayNode();
        for (var slot : new String[]{"H7:J8", "H9:J11", "H12:J13", "H14:J15"}) {
            var bounds = testBounds(slot);
            merged.add(objectMapper.createObjectNode().put("range", slot)
                    .put("startRow", bounds[1]).put("endRow", bounds[3])
                    .put("startColumn", bounds[0]).put("endColumn", bounds[2]));
        }
        var sheet = objectMapper.createObjectNode().put("id", "sheet-1").put("usedRange", "A1:P31");
        sheet.set("semanticCells", semanticCells); sheet.set("candidateCells", candidates); sheet.set("mergedRanges", merged);
        var structure = objectMapper.createObjectNode().set("sheets", objectMapper.createArrayNode().add(sheet));

        var result = new StructurePrimitiveRecognizer(objectMapper, true).recognize(structure);

        assertThat(result).anySatisfy(region -> {
            assertThat(region.path("blockType").asText()).isEqualTo("FORM_REGION");
            assertThat(region.path("range").asText()).isEqualTo("A1:J5");
        });
        assertThat(result).anySatisfy(region -> {
            assertThat(region.path("blockType").asText()).isEqualTo("ROW_TABLE");
            assertThat(region.path("range").asText()).isEqualTo("A6:G22");
            assertThat(region.path("structure").path("dataRange").asText()).isEqualTo("A7:G21");
            assertThat(region.path("structure").path("totalRange").asText()).isEqualTo("A22:G22");
        });
        assertThat(result).anySatisfy(region -> {
            assertThat(region.path("blockType").asText()).isEqualTo("ROW_TABLE");
            assertThat(region.path("range").asText()).isEqualTo("H6:J15");
            assertThat(region.path("structure").path("recordSlots"))
                    .extracting(slot -> slot.path("range").asText())
                    .containsExactly("H7:J8", "H9:J11", "H12:J13", "H14:J15");
        });
        assertThat(result).anySatisfy(region -> {
            assertThat(region.path("blockType").asText()).isEqualTo("FORM_REGION");
            assertThat(region.path("range").asText()).isEqualTo("A23:J28");
            assertThat(region.path("structure").path("staticContents")).hasSize(1);
        });
        assertThat(result).noneMatch(region -> "FORM_REGION".equals(region.path("blockType").asText())
                && Set.of("H3:J3", "H4:J4", "H5:J5").contains(region.path("range").asText()));
    }

    private int[] testBounds(String range) {
        var parts = range.split(":");
        return new int[]{parts[0].charAt(0) - 'A' + 1, Integer.parseInt(parts[0].substring(1)),
                parts[1].charAt(0) - 'A' + 1, Integer.parseInt(parts[1].substring(1))};
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
