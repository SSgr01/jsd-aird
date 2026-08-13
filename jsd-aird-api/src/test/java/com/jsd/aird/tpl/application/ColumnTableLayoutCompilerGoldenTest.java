package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** Golden coordinates belong to this fixture only; production inference remains generic. */
class ColumnTableLayoutCompilerGoldenTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ColumnTableLayoutCompiler compiler = new ColumnTableLayoutCompiler(mapper);

    @Test
    void keepsVerticalMergesAsFieldGroupsInsideOneColumnTable() {
        var facts = mapper.createObjectNode();
        var cells = facts.putArray("sheets").addObject().put("sheetId", "sheet-1").putArray("candidateCells");
        cell(cells.addObject(), "A4", "A4:C4", "测试树脂样品或配方", false);
        cell(cells.addObject(), "D4", "", "测试方法", false);
        for (char column = 'E'; column <= 'N'; column++) cell(cells.addObject(), column + "4", "", "", true);

        cell(cells.addObject(), "A5", "A5:B26", "物性测试", false);
        cell(cells.addObject(), "C5", "", "外观", false);
        cell(cells.addObject(), "D5", "", "目测", false);
        cell(cells.addObject(), "C6", "", "固含", false);
        cell(cells.addObject(), "D6", "", "120℃×1h", false);
        cell(cells.addObject(), "C7", "C7:C11", "粘度", false);
        cell(cells.addObject(), "D7", "", "25℃旋转粘度计", false);
        cell(cells.addObject(), "D8", "", "25℃岩田2#杯", false);
        cell(cells.addObject(), "A27", "A27:B32", "刮膜固化测试", false);
        cell(cells.addObject(), "C27", "", "测试平台", false);
        cell(cells.addObject(), "A33", "A33:A100", "喷板、刮板或淋涂后性能测试", false);
        cell(cells.addObject(), "B33", "B33:C33", "测试平台", false);
        for (int row : new int[]{5, 6, 7, 8, 27, 33}) {
            for (char column = 'E'; column <= 'N'; column++) cell(cells.addObject(), column + String.valueOf(row), "", "", true);
        }

        var region = mapper.createObjectNode().put("type", "COLUMN_TABLE")
                .put("sheetId", "sheet-1").put("range", "A4:N100");

        assertThat(compiler.enrich(region, facts)).isTrue();
        var structure = region.path("structure");
        assertThat(structure.path("recordAxis").asText()).isEqualTo("COLUMN");
        assertThat(structure.path("recordProjection").path("labelBandRange").asText()).isEqualTo("A4:C100");
        assertThat(structure.path("recordProjection").path("runtimeColumnMemberRange").asText()).isEqualTo("E4:N4");
        assertThat(structure.path("recordProjection").path("rowAttributeColumns").get(0).path("column").asText())
                .isEqualTo("D");
        assertThat(structure.path("fieldGroups")).hasSize(3);
        assertThat(structure.path("recordProjection").path("recordIdentity").path("valueRange").asText())
                .isEqualTo("E4:N4");
        assertThat(structure.path("fieldRows")).anyMatch(item ->
                item.path("row").asInt() == 5
                        && "物性测试 > 外观".equals(item.path("labelPath").asText())
                        && "E5:N5".equals(item.path("valueRange").asText())
                        && item.path("rowAttributes").get(0).path("value").asText().equals("目测"));
        assertThat(structure.path("fieldRows")).anyMatch(item ->
                item.path("row").asInt() == 8
                        && "物性测试 > 粘度".equals(item.path("labelPath").asText())
                        && "E8:N8".equals(item.path("valueRange").asText())
                        && item.path("rowAttributes").get(0).path("value").asText().equals("25℃岩田2#杯"));
        assertThat(structure.path("fieldRows")).noneMatch(item -> item.path("row").asInt() == 4);

        var parent = mapper.createObjectNode().put("kind", "COLUMN_TABLE")
                .put("relationId", "r1").put("fieldId", "f1").put("bindingId", "b1")
                .put("candidateRef", "r1").put("regionId", "r1").put("blockId", "r1");
        parent.putObject("locator").put("sheetId", "sheet-1").put("range", "A4:N100")
                .put("headerRange", "A4:N4").put("dataRange", "A5:N100");
        parent.set("columns", mapper.createArrayNode());
        var fieldCompiler = new PhysicalStructureFieldCompiler(mapper);
        var children = fieldCompiler.children(parent, region, facts);
        assertThat(children).anyMatch(item ->
                "物性测试 > 粘度 > 25℃旋转粘度计".equals(
                        item.payload().path("labelPath").asText())
                        && item.payload().path("rowAttributes").get(0).path("label").asText()
                        .equals("测试方法"));
    }

    @Test
    void detectsOneLargeColumnTableAndKeepsFormulaRowsAsReadOnlyDerivedFields() {
        var sheet = mapper.createObjectNode()
                .put("id", "sheet-1").put("sheetId", "sheet-1").put("usedRange", "A1:J39");
        var semantic = sheet.putArray("semanticCells");
        cell(semantic.addObject(), "A1", "A1:I1", "报告标题", false);
        cell(semantic.addObject(), "B7", "B7:I7", "前置信息", false);
        cell(semantic.addObject(), "A8", "A8:B15", "字段组一", false);
        for (int row = 8; row <= 15; row++) {
            cell(semantic.addObject(), "C" + row, "", "字段" + row, false);
        }
        cell(semantic.addObject(), "A16", "A16:B26", "字段组二", false);
        cell(semantic.addObject(), "C16", "", "记录标识", false);
        for (int row = 17; row <= 25; row++) {
            cell(semantic.addObject(), "C" + row, "", "组成" + row, false);
        }
        cell(semantic.addObject(), "C26", "", "合计", false);
        cell(semantic.addObject(), "A29", "A29:B37", "字段组三", false);
        for (int row = 29; row <= 37; row++) {
            cell(semantic.addObject(), "C" + row, "", "性能" + row, false);
        }
        cell(semantic.addObject(), "A38", "A38:I38", "结论", false);

        var candidates = sheet.putArray("candidateCells");
        for (int row = 8; row <= 37; row++) {
            for (char column = 'D'; column <= 'I'; column++) {
                var candidate = candidates.addObject();
                cell(candidate, column + String.valueOf(row), "", "", true);
                candidate.put("hasBorder", true);
                if (row == 26) {
                    candidate.put("inputCandidate", false)
                            .put("factType", "FORMULA")
                            .put("formula", "=SUM(" + column + "17:" + column + "25)")
                            .put("value", "=SUM(" + column + "17:" + column + "25)");
                }
            }
        }
        var detected = compiler.detect(sheet, "sheet-1");

        assertThat(detected).isNotNull();
        assertThat(detected.path("range").asText()).isEqualTo("A8:I37");
        var structure = detected.path("structure");
        assertThat(structure.path("recordProjection").path("recordColumns"))
                .extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .containsExactly("D", "E", "F", "G", "H", "I");
        assertThat(structure.path("fieldGroups")).hasSize(3);
        assertThat(structure.path("fieldRows")).anyMatch(item ->
                item.path("row").asInt() == 26
                        && "字段组二 > 合计".equals(item.path("labelPath").asText())
                        && item.path("formulaDerived").asBoolean()
                        && "FORMULA".equals(item.path("valueSource").asText())
                        && "READ_ONLY".equals(item.path("editability").asText())
                        && !item.path("trainingEligible").asBoolean());
        assertThat(structure.path("fieldRows")).noneMatch(item ->
                item.path("labelPath").asText().startsWith("="));
    }

    @Test
    void doesNotMaterializeOneDuplicateFieldPerRowForUnnamedVerticalRuntimeSlots() {
        var facts = mapper.createObjectNode();
        var cells = facts.putArray("sheets").addObject().put("sheetId", "sheet-1").putArray("candidateCells");
        cell(cells.addObject(), "A9", "A9:A16", "涂料配方", false);
        cell(cells.addObject(), "B9", "B9:C9", "实验编号", false);
        cell(cells.addObject(), "B10", "B10:B16", "实验配方", false);
        cell(cells.addObject(), "C16", "", "合计", false);
        for (int row = 9; row <= 16; row++) {
            for (char column = 'D'; column <= 'I'; column++) {
                var value = cells.addObject();
                cell(value, column + String.valueOf(row), "", "", true);
                value.put("hasBorder", true);
                if (row == 16) value.put("formula", "=SUM(" + column + "10:" + column + "15)");
            }
        }

        var region = mapper.createObjectNode().put("type", "COLUMN_TABLE")
                .put("sheetId", "sheet-1").put("range", "A9:I16");
        region.putObject("structure").putObject("recordProjection")
                .put("identityRow", 9).put("valueStartRow", 9).put("valueEndRow", 16)
                .put("recordStartColumn", 4).put("recordEndColumn", 9);

        assertThat(compiler.enrich(region, facts)).isTrue();
        var rows = region.path("structure").path("fieldRows");
        assertThat(rows).anyMatch(item -> item.path("row").asInt() == 9
                && "涂料配方 > 实验编号".equals(item.path("labelPath").asText()));
        assertThat(rows).noneMatch(item -> item.path("row").asInt() >= 10
                && item.path("row").asInt() <= 15);
        assertThat(rows).anyMatch(item -> item.path("row").asInt() == 16
                && "涂料配方 > 实验配方 > 合计".equals(item.path("labelPath").asText())
                && item.path("formulaDerived").asBoolean());
    }

    private void cell(ObjectNode cell, String address, String mergedRange, String value, boolean inputCandidate) {
        cell.put("address", address).put("value", value).put("inputCandidate", inputCandidate);
        if (!mergedRange.isBlank()) cell.put("mergedRange", mergedRange);
    }
}
