package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.PhysicalStructureFieldCompiler;
import com.jsd.aird.tpl.application.StructurePrimitiveRecognizer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Local optimized-template regression. CI skips this test unless the workbook
 * is provided; developers can override its location with -Dm687.optimized=....
 */
class M687OptimizedRecognitionRegressionTest {

    private static final Path DEFAULT_OPTIMIZED = Path.of(
            "G:/Projects/jsd-aird/docs/干净模板表_整理完成/原表优化/生产（配方）任务单模板.xlsx");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XlsxStructureParser parser = new XlsxStructureParser(objectMapper);
    private final StructurePrimitiveRecognizer recognizer = new StructurePrimitiveRecognizer(objectMapper);
    private final PhysicalStructureFieldCompiler fieldCompiler = new PhysicalStructureFieldCompiler(objectMapper);

    @Test
    void recognizesOptimizedWorkbookWithOnlySimpleBusinessRegions() throws Exception {
        var optimizedPath = Path.of(System.getProperty("m687.optimized", DEFAULT_OPTIMIZED.toString()));
        Assumptions.assumeTrue(Files.isRegularFile(optimizedPath), "optimized workbook is not available");

        var parsed = parse(optimizedPath);
        var optimized = recognizer.recognize(parsed.structureSummary());

        assertSimpleVocabulary(optimized);
        assertThat(optimized).filteredOn(item -> "ROW_TABLE".equals(item.path("blockType").asText()))
                .hasSize(2);
        assertThat(optimized).anyMatch(item -> "FORM_REGION".equals(item.path("blockType").asText()));
        assertThat(optimized).noneMatch(item -> "UNKNOWN".equals(item.path("blockType").asText()));

        var fields = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var ordinal = 0;
        for (var region : optimized) {
            var parent = parent(region, ordinal++);
            fieldCompiler.children(parent, region, parsed.structureSummary())
                    .forEach(suggestion -> fields.add(suggestion.payload()));
        }
        assertThat(fields).extracting(field -> field.path("mappingKind").asText())
                .containsOnly("SCALAR", "REPEAT_FIELD");
        assertThat(fields).extracting(field -> field.path("fieldName").asText())
                .contains("类别", "品名", "订单号", "反应釜", "包装批号", "制造日期",
                        "序号", "原料编号", "配方比例", "实际投料量", "批号", "操作程序",
                        "包装物料", "包装规格", "实际产量", "包装数量")
                .noneMatch(name -> name.startsWith("注：") || name.startsWith("注:"));
        assertThat(fields).anyMatch(field -> "配方比例".equals(field.path("fieldName").asText())
                && "KG".equalsIgnoreCase(field.path("unit").asText()));
        assertThat(fields).anyMatch(field -> "实际投料量".equals(field.path("fieldName").asText())
                && "KG".equalsIgnoreCase(field.path("unit").asText()));

        System.out.printf("M687 optimized regions=%s%n", objectMapper.writeValueAsString(optimized));
        System.out.printf("M687 optimized fields=%s%n", objectMapper.writeValueAsString(fields));
    }

    private XlsxStructureParser.ParseResult parse(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            return parser.parse(input);
        }
    }

    private ObjectNode parent(com.fasterxml.jackson.databind.JsonNode region, int ordinal) {
        var parent = objectMapper.createObjectNode()
                .put("kind", region.path("blockType").asText())
                .put("blockId", "region-" + ordinal)
                .put("regionId", "region-" + ordinal)
                .put("relationId", "region-relation-" + ordinal)
                .put("fieldId", "region-field-" + ordinal)
                .put("bindingId", "region-binding-" + ordinal)
                .put("dataPath", "/regions/" + ordinal)
                .put("groupName", "业务数据");
        parent.set("locator", objectMapper.createObjectNode()
                .put("sheetId", region.path("sheetId").asText())
                .put("sheetName", "生产（配方）任务单")
                .put("range", region.path("range").asText())
                .put("dataRange", region.path("structure").path("dataRange").asText("")));
        return parent;
    }

    private void assertSimpleVocabulary(com.fasterxml.jackson.databind.node.ArrayNode regions) {
        assertThat(regions).allMatch(item -> Set.of(
                "FORM_REGION", "ROW_TABLE", "COLUMN_TABLE", "UNKNOWN"
        ).contains(item.path("blockType").asText()));
    }
}
