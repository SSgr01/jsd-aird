package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.PhysicalStructureFieldCompiler;
import com.jsd.aird.tpl.application.ColumnTableLayoutCompiler;
import com.jsd.aird.tpl.application.StructurePrimitiveRecognizer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class OptimizedTemplateStructureRegressionTest {

    private static final Path OPTIMIZED = Path.of(
            "G:/Projects/jsd-aird/docs/干净模板表_整理完成/原表优化");
    private static final Path CONVERTED_REFERENCE = Path.of(
            "G:/Projects/jsd-aird/docs/干净模板表_整理完成/原表转表");
    private static final Set<String> SIMPLE_TYPES = Set.of(
            "FORM_REGION", "ROW_TABLE", "COLUMN_TABLE", "UNKNOWN");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XlsxStructureParser parser = new XlsxStructureParser(objectMapper);
    private final StructurePrimitiveRecognizer recognizer = new StructurePrimitiveRecognizer(objectMapper);
    private final PhysicalStructureFieldCompiler fieldCompiler = new PhysicalStructureFieldCompiler(objectMapper);
    private final ColumnTableLayoutCompiler columnCompiler = new ColumnTableLayoutCompiler(objectMapper);

    @Test
    void recognizesAllOptimizedTemplatesWithTheirBusinessRecordDirection() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(OPTIMIZED), "optimized workbook directory is unavailable");

        assertOnly("生产（配方）任务单模板.xlsx", "ROW_TABLE", 2);
        assertOnly("成品_助剂类检测标准模板.xlsx", "ROW_TABLE", 1);
        var qualityNotice = recognize("成品品质不良联络单模板.xlsx");
        assertSimpleAndNonOverlapping(qualityNotice);
        assertThat(qualityNotice).filteredOn(item -> "FORM_REGION".equals(item.path("blockType").asText()))
                .hasSize(1);
        assertThat(qualityNotice).noneMatch(this::isRowTable);
        assertThat(qualityNotice).noneMatch(this::isColumnTable);

        assertOnly("光引发剂对比测试模板.xlsx", "COLUMN_TABLE", 1);
        assertExactColumnRegion("阳离子单体_树脂性能及应用测试模板.xlsx", "A9:I35");
        assertOnly("含氟树脂合成模板.xlsx", "COLUMN_TABLE", 1);

        var application = recognize("应用测试报告模板.xlsx");
        assertSimpleAndNonOverlapping(application);
        assertThat(application).filteredOn(this::isColumnTable)
                .extracting(item -> item.path("sheetId").asText())
                .containsExactlyInAnyOrder("sheet-1", "sheet-2");
        assertThat(application).filteredOn(item -> "FORM_REGION".equals(item.path("blockType").asText()))
                .extracting(item -> item.path("sheetId").asText() + "|" + item.path("range").asText())
                .contains("sheet-1|A1:J7", "sheet-2|A1:J7");
        assertThat(application).noneMatch(this::isRowTable);

        var comprehensive = recognize("综合测试报告模板.xlsx");
        assertSimpleAndNonOverlapping(comprehensive);
        assertThat(comprehensive).filteredOn(this::isColumnTable)
                .extracting(item -> item.path("sheetId").asText())
                .containsExactly("sheet-1");
        assertThat(comprehensive).filteredOn(this::isColumnTable)
                .extracting(item -> item.path("range").asText())
                .containsExactly("A4:N100");
        assertThat(comprehensive).noneMatch(this::isRowTable);

        // The optimized input currently contains one worksheet.  The correct
        // two-sheet business reference lives in 原表转表, so keep an explicit
        // coverage test for both worksheets without mutating the input file.
        var comprehensiveReference = recognize(
                CONVERTED_REFERENCE.resolve("综合测试报告_干净模板.xlsx"), "综合测试报告_干净模板.xlsx");
        assertSimpleAndNonOverlapping(comprehensiveReference);
        assertThat(comprehensiveReference).filteredOn(item -> isColumnTable(item) || isRowTable(item))
                .extracting(item -> item.path("sheetId").asText())
                .contains("sheet-1", "sheet-2");
    }

    @Test
    void keepsFluorinatedResinIdentityInsideTheRepeatedRegion() throws Exception {
        var path = OPTIMIZED.resolve("含氟树脂合成模板.xlsx");
        var parsed = parse(path);
        var regions = recognizer.recognize(parsed.structureSummary());

        assertThat(regions).anyMatch(item -> isColumnTable(item)
                && "A4:K41".equalsIgnoreCase(item.path("range").asText()));
        assertThat(regions).filteredOn(item -> "FORM_REGION".equals(item.path("blockType").asText()))
                .noneMatch(item -> overlap(item.path("range").asText(), "A4:K4"));

        var column = (ObjectNode) java.util.stream.StreamSupport.stream(regions.spliterator(), false)
                .filter(this::isColumnTable).findFirst().orElseThrow().deepCopy();
        assertThat(columnCompiler.enrich(column, parsed.structureSummary())).isTrue();
        assertThat(column.path("structure").path("headerRange").asText()).isEqualTo("A4:K5");
        assertThat(column.path("structure").path("dataRange").asText()).isEqualTo("A6:K41");
        var repeatedFields = fieldCompiler.children(parent(column), column, parsed.structureSummary()).stream()
                .map(suggestion -> suggestion.payload())
                .filter(field -> "REPEAT_FIELD".equals(field.path("mappingKind").asText()))
                .toList();
        // The 23 metric/recipe rows are joined by the two identity rows
        // (实验编号 and 日期) in the same COLUMN_TABLE, so the physical
        // projection contains 25 repeat fields in total.
        assertThat(repeatedFields).hasSize(25);
        assertThat(repeatedFields).extracting(field -> field.path("fieldName").asText())
                .contains("实验编号", "日期", "质量合计", "丙烯酸固体质量", "合成固含", "氟含量", "引发剂含量",
                        "降温前外观", "冷却后外观", "合成工艺", "真空温度", "滴加温度", "最优方案");
    }

    @Test
    void keepsRegionAndFieldShapeStableAcrossFiveRuns() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(OPTIMIZED), "optimized workbook directory is unavailable");

        var files = List.of(
                "生产（配方）任务单模板.xlsx",
                "成品_助剂类检测标准模板.xlsx",
                "成品品质不良联络单模板.xlsx",
                "光引发剂对比测试模板.xlsx",
                "阳离子单体_树脂性能及应用测试模板.xlsx",
                "应用测试报告模板.xlsx",
                "综合测试报告模板.xlsx",
                "含氟树脂合成模板.xlsx");
        for (var name : files) {
            var path = OPTIMIZED.resolve(name);
            Assumptions.assumeTrue(Files.isRegularFile(path), name + " is unavailable");
            var parsed = parse(path);
            String baseline = null;
            for (int run = 0; run < 5; run++) {
                var regions = recognizer.recognize(parsed.structureSummary());
                assertSimpleAndNonOverlapping(regions);
                var shape = regionFieldShape(regions, parsed.structureSummary());
                if (baseline == null) baseline = shape;
                assertThat(shape)
                        .as("recognition shape must be stable for %s (run %s)", name, run + 1)
                        .isEqualTo(baseline);
            }
        }
    }

    private void assertOnly(String name, String expectedType, int minimumCount) throws Exception {
        var regions = recognize(name);
        assertSimpleAndNonOverlapping(regions);
        assertThat(regions).filteredOn(item -> expectedType.equals(item.path("blockType").asText()))
                .hasSizeGreaterThanOrEqualTo(minimumCount);
        if ("FORM_REGION".equals(expectedType)) {
            assertThat(regions).noneMatch(item -> isRowTable(item) || isColumnTable(item));
        } else if ("ROW_TABLE".equals(expectedType)) {
            assertThat(regions).noneMatch(this::isColumnTable);
        } else if ("COLUMN_TABLE".equals(expectedType)) {
            assertThat(regions).noneMatch(this::isRowTable);
        }
    }

    private void assertExactColumnRegion(String name, String range) throws Exception {
        var regions = recognize(name);
        assertSimpleAndNonOverlapping(regions);
        assertThat(regions).filteredOn(this::isColumnTable)
                .extracting(item -> item.path("range").asText())
                .containsExactly(range);
        assertThat(regions).noneMatch(this::isRowTable);
    }

    private ArrayNode recognize(String name) throws Exception {
        var path = OPTIMIZED.resolve(name);
        return recognize(path, name);
    }

    private ArrayNode recognize(Path path, String label) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(path), label + " is unavailable");
        var parsed = parse(path);
        var regions = recognizer.recognize(parsed.structureSummary());
        System.out.printf("template=%s regions=%s%n", label,
                objectMapper.writeValueAsString(regions));
        return regions;
    }

    private String regionFieldShape(ArrayNode regions, JsonNode structure) {
        var shape = new ArrayList<String>();
        for (var region : regions) {
            var fieldCount = fieldCompiler.children(parent(region), region, structure).size();
            shape.add(region.path("sheetId").asText() + "|"
                    + region.path("blockType").asText() + "|"
                    + region.path("range").asText() + "|" + fieldCount);
        }
        shape.sort(String::compareTo);
        return String.join(";", shape);
    }

    private XlsxStructureParser.ParseResult parse(Path path) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(path), path.getFileName() + " is unavailable");
        try (var input = Files.newInputStream(path)) {
            return parser.parse(input);
        }
    }

    private ObjectNode parent(JsonNode region) {
        var parent = objectMapper.createObjectNode()
                .put("kind", "COLUMN_TABLE")
                .put("blockId", "fluoride-region")
                .put("regionId", "fluoride-region")
                .put("relationId", "fluoride-relation")
                .put("fieldId", "fluoride-field")
                .put("bindingId", "fluoride-binding")
                .put("dataPath", "/regions/fluoride")
                .put("groupName", "业务数据");
        parent.set("locator", objectMapper.createObjectNode()
                .put("sheetId", region.path("sheetId").asText())
                .put("sheetName", "含氟树脂合成")
                .put("range", region.path("range").asText())
                .put("dataRange", region.path("structure").path("dataRange").asText("")));
        return parent;
    }

    private void assertSimpleAndNonOverlapping(ArrayNode regions) {
        assertThat(regions).allMatch(item -> SIMPLE_TYPES.contains(item.path("blockType").asText()));
        var active = new ArrayList<JsonNode>();
        regions.forEach(item -> {
            if (!"UNKNOWN".equals(item.path("blockType").asText())) active.add(item);
        });
        for (int left = 0; left < active.size(); left++) {
            for (int right = left + 1; right < active.size(); right++) {
                var first = active.get(left);
                var second = active.get(right);
                if (!first.path("sheetId").asText().equals(second.path("sheetId").asText())) continue;
                assertThat(overlap(first.path("range").asText(), second.path("range").asText()))
                        .as("regions must not overlap: %s and %s", first.path("range").asText(),
                                second.path("range").asText())
                        .isFalse();
            }
        }
    }

    private boolean isRowTable(JsonNode item) {
        return "ROW_TABLE".equals(item.path("blockType").asText());
    }

    private boolean isColumnTable(JsonNode item) {
        return "COLUMN_TABLE".equals(item.path("blockType").asText());
    }

    private boolean overlap(String left, String right) {
        var a = bounds(left);
        var b = bounds(right);
        return a != null && b != null && a[0] <= b[2] && b[0] <= a[2]
                && a[1] <= b[3] && b[1] <= a[3];
    }

    private int[] bounds(String range) {
        if (range == null || range.isBlank()) return null;
        var parts = range.replace("$", "").split(":");
        var first = cell(parts[0]);
        var last = cell(parts.length == 1 ? parts[0] : parts[1]);
        return first == null || last == null ? null
                : new int[]{first[0], first[1], last[0], last[1]};
    }

    private int[] cell(String address) {
        var value = address == null ? "" : address.trim().toUpperCase();
        var split = 0;
        while (split < value.length() && Character.isLetter(value.charAt(split))) split++;
        if (split == 0 || split == value.length()) return null;
        var column = 0;
        for (int index = 0; index < split; index++) {
            column = column * 26 + value.charAt(index) - 'A' + 1;
        }
        try {
            return new int[]{column, Integer.parseInt(value.substring(split))};
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
