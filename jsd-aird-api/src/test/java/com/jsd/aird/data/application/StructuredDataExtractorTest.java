package com.jsd.aird.data.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.Test;

class StructuredDataExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StructuredDataExtractor extractor = new StructuredDataExtractor(mapper);

    @Test
    void expandsRowTableAndSkipsAggregateRows() {
        var sheet = sheet("rows", List.of(
                List.of("编号", "名称", "数量"),
                List.of("M-01", "树脂", "10"),
                List.of("M-02", "助剂", "20"),
                List.of("", "合计", "30")));
        var fields = List.of(
                binding("id", "material.id", "编号", "A2:A4", "ROW", true),
                binding("name", "material.name", "名称", "B2:B4", "ROW", false),
                binding("amount", "material.amount", "数量", "C2:C4", "ROW", false));

        var result = extractor.extract(sheet, definitions("material.id", "material.name", "material.amount"), fields, 2, 4)
                .orElseThrow();

        assertThat(result.shape()).isEqualTo("ROW_TABLE");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).rawValues().path("b_id").asText()).isEqualTo("M-01");
        assertThat(result.rows().get(0).sourceMetadata().path("recordKey").asText()).isEqualTo("M-01");
        assertThat(result.rows().get(0).sourceMetadata().path("cells").path("b_amount")
                .path("cellAddress").asText()).isEqualTo("C2");
        assertThat(result.mappings()).allMatch(item -> "rows".equals(item.sheetId()));
    }

    @Test
    void skipsReservedRowsThatOnlyContainGeneratedSequenceNumbers() {
        var sheet = sheet("rows", List.of(
                List.of("序号", "原料", "数量"),
                List.of("1", "树脂", "50"),
                List.of("2", "", "")));
        var fields = List.of(
                binding("sequence", "formula.sequence", "序号", "A2:A3", "ROW", false),
                binding("material", "formula.material", "原料", "B2:B3", "ROW", false),
                binding("amount", "formula.amount", "数量", "C2:C3", "ROW", false));

        var result = extractor.extract(sheet,
                definitions("formula.sequence", "formula.material", "formula.amount"), fields, 2, 3)
                .orElseThrow();

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().rawValues().path("b_material").asText()).isEqualTo("树脂");
    }

    @Test
    void extractsValueAfterColonForInlineFormFields() {
        var sheet = sheet("form", List.of(List.of("包装规格：18 kg/桶")));
        var locator = mapper.createObjectNode().put("sheetId", "form")
                .put("valueRange", "A1").put("valueMode", "INLINE");
        var region = new TemplateDataImportFacade.ImportBinding("form", "", "", "FORM_REGION",
                "", "", 1, 1, 1, mapper.createObjectNode(), mapper.createObjectNode()
                .put("sheetId", "form").put("range", "A1"), false, false, true,
                "INPUT", "OBJECT", "");
        var field = new TemplateDataImportFacade.ImportBinding("package", "package.spec", "/package/spec",
                "SCALAR", "form", "", 1, 1, 1, mapper.createObjectNode(), locator,
                false, false, true, "INPUT", "TEXT", "");

        var result = extractor.extract(sheet, definitions("package.spec"), List.of(region, field), 1, 1)
                .orElseThrow();

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().rawValues().path("b_package").asText()).isEqualTo("18 kg/桶");
    }

    @Test
    void expandsHorizontalColumnTableIntoLogicalRecords() {
        var sheet = sheet("columns", List.of(
                List.of("指标", "产品A", "产品B"),
                List.of("名称", "树脂", "助剂"),
                List.of("数量", "10", "20")));
        var fields = List.of(
                binding("name", "material.name", "名称", "B2:C2", "COLUMN", true),
                binding("amount", "material.amount", "数量", "B3:C3", "COLUMN", false));

        var result = extractor.extract(sheet, definitions("material.name", "material.amount"), fields, 2, 3)
                .orElseThrow();

        assertThat(result.shape()).isEqualTo("COLUMN_TABLE");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).rawValues().path("b_name").asText()).isEqualTo("树脂");
        assertThat(result.rows().get(1).rawValues().path("b_amount").asText()).isEqualTo("20");
        assertThat(result.rows().get(1).sourceMetadata().path("cells").path("b_amount")
                .path("cellAddress").asText()).isEqualTo("C3");
    }

    @Test
    void keepsRowTableRowsWhenBindingsUseArrayColumnAndIgnoresParentRegion() {
        var sheet = sheet("materials", List.of(
                List.of("日期", "产品名称", "批号", "粘度"),
                List.of("2026-12-22", "树脂 A", "M-01", "1200"),
                List.of("2026-12-23", "溶剂 B", "M-02", "850")));
        var regionLocator = mapper.createObjectNode().put("sheetId", "materials")
                .put("range", "A1:D200").put("dataRange", "A2:D200")
                .put("valueMode", "ARRAY_ROW");
        var region = new TemplateDataImportFacade.ImportBinding("region", "table.records", "/records",
                "REPEAT_REGION", "", "ROW", 1, 4, 1, mapper.createObjectNode(), regionLocator,
                false, false, true, "INPUT", "ARRAY", "");
        var fields = List.of(
                rowBinding("date", "material.date", "日期", "A2:A3"),
                rowBinding("name", "material.name", "产品名称", "B2:B3"),
                rowBinding("code", "material.code", "批号", "C2:C3"),
                rowBinding("viscosity", "material.viscosity", "粘度", "D2:D3"));

        var result = extractor.extract(sheet, definitions("material.date", "material.name", "material.code", "material.viscosity"),
                java.util.stream.Stream.concat(java.util.stream.Stream.of(region), fields.stream()).toList(), 2, 3)
                .orElseThrow();

        assertThat(result.shape()).isEqualTo("ROW_TABLE");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).rawValues().path("b_date").asText()).isEqualTo("2026-12-22");
        assertThat(result.rows().get(1).rawValues().path("b_viscosity").asText()).isEqualTo("850");
        assertThat(result.mappings()).hasSize(4);
        assertThat(result.mappings()).noneMatch(item -> "table.records".equals(item.fieldCode()));
    }

    @Test
    void pivotsMatrixCellsWithRowAndColumnDimensions() {
        var sheet = sheet("matrix", List.of(
                List.of("地区", "2024", "2025", "合计"),
                List.of("华东", "10", "20", "30"),
                List.of("华南", "11", "21", "32"),
                List.of("合计", "21", "41", "62")));
        var regionLocator = mapper.createObjectNode()
                .put("sheetId", "matrix")
                .put("rowHeaderRange", "A2:A4")
                .put("columnHeaderRange", "B1:D1")
                .put("crossDataRange", "B2:D4");
        var fieldLocator = mapper.createObjectNode().put("crossDataRange", "B2:C3");
        var fields = List.of(
                new TemplateDataImportFacade.ImportBinding("region", "", "", "MATRIX_REGION", "", "",
                        1, 1, 1, mapper.createObjectNode(), regionLocator, false, false, true, "INPUT", "TEXT", ""),
                new TemplateDataImportFacade.ImportBinding("metric", "metric.value", "/metric/value", "MATRIX_FIELD",
                        "region", "", 1, 1, 1, mapper.createObjectNode(), fieldLocator, false, false, true,
                        "INPUT", "NUMBER", ""));

        var result = extractor.extract(sheet, definitions("metric.value"), fields, 2, 4).orElseThrow();

        assertThat(result.shape()).isEqualTo("MATRIX");
        assertThat(result.rows()).hasSize(4);
        assertThat(result.rows()).allMatch(row -> row.rawValues().has("__dimension_row")
                && row.rawValues().has("__dimension_column"));
        assertThat(result.rows().get(0).sourceMetadata().path("dimensions").path("row").asText())
                .isEqualTo("华东");
        assertThat(result.rows().get(0).sourceMetadata().path("dimensions").path("column").asText())
                .isEqualTo("2024");
        assertThat(result.rows().get(0).sourceMetadata().path("cells").path("b_metric")
                .path("cellAddress").asText()).isEqualTo("B2");
        assertThat(result.mappings()).extracting(DataRepository.Mapping::fieldCode)
                .contains("metric.value", "DATA.DIMENSION.ROW", "DATA.DIMENSION.COLUMN");
    }

    @Test
    void createsOneMatrixRecordPerIntersectionAndReadsDeclaredRowDimensionFromItsOwnRange() {
        var sheet = sheet("matrix", List.of(
                List.of("温度", "1 min", "3 min"),
                List.of("25℃", "12.4", "18.7"),
                List.of("50℃", "16.8", "25.1")));
        var regionLocator = mapper.createObjectNode()
                .put("sheetId", "matrix")
                .put("rowHeaderRange", "A2:A3")
                .put("columnHeaderRange", "B1:C1")
                .put("crossDataRange", "B2:C3");
        var rowLocator = regionLocator.deepCopy().put("sourceRange", "A2:A3")
                .put("logicalInputRange", "A2:A3");
        var measureLocator = regionLocator.deepCopy().put("sourceRange", "B2:C3")
                .put("logicalInputRange", "B2:C3");
        var fields = List.of(
                new TemplateDataImportFacade.ImportBinding("region", "", "", "MATRIX_REGION", "", "",
                        1, 1, 1, mapper.createObjectNode(), regionLocator, false, false, true, "INPUT", "TEXT", ""),
                new TemplateDataImportFacade.ImportBinding("row", "MATRIX.ROW_DIMENSION.temperature",
                        "/records/*/temperature", "MATRIX_FIELD", "region", "ROW", 1, 1, 1,
                        mapper.createObjectNode(), rowLocator, false, false, true, "INPUT", "TEXT", ""),
                new TemplateDataImportFacade.ImportBinding("metric", "MATRIX.MEASURE.value",
                        "/records/*/value", "MATRIX_FIELD", "region", "ROW", 1, 1, 1,
                        mapper.createObjectNode(), measureLocator, false, false, true, "INPUT", "NUMBER", ""));

        var result = extractor.extract(sheet,
                definitions("MATRIX.ROW_DIMENSION.temperature", "MATRIX.MEASURE.value"), fields, 2, 3)
                .orElseThrow();

        assertThat(result.rows()).hasSize(4);
        assertThat(result.rows().getFirst().rawValues().path("b_row").asText()).isEqualTo("25℃");
        assertThat(result.rows().getFirst().rawValues().path("b_metric").asText()).isEqualTo("12.4");
        assertThat(result.rows()).allMatch(row -> !row.rawValues().has("__dimension_row"));
        assertThat(result.rows()).allMatch(row -> row.rawValues().has("__dimension_column"));
        assertThat(result.rows()).extracting(row -> row.sourceMetadata().path("recordKey").asText())
                .doesNotHaveDuplicates();
    }

    @Test
    void readsFormRegionAndCarriesBasicInfoIntoDetailRecords() {
        var sheet = sheet("mixed", List.of(
                List.of("基本信息", "M-01", ""),
                List.of("名称", "树脂", ""),
                List.of("", "", ""),
                List.of("编号", "名称", "数量"),
                List.of("D-01", "明细一", "10"),
                List.of("D-02", "明细二", "20")));
        var formRegion = new TemplateDataImportFacade.ImportBinding("form", "", "", "FORM_REGION", "", "",
                1, 2, 1, mapper.createObjectNode(), mapper.createObjectNode()
                        .put("sheetId", "mixed").put("address", "A1:B2"), false, false, true, "INPUT", "OBJECT", "");
        var formField = new TemplateDataImportFacade.ImportBinding("form-code", "material.code", "/material/code", "SCALAR",
                "form", "", 1, 1, 1, mapper.createObjectNode(), mapper.createObjectNode()
                        .put("sheetId", "mixed").put("kind", "FORM_REGION").put("valueRange", "B1:B1"),
                false, true, true, "INPUT", "TEXT", "");
        var detailId = binding("mixed", "detail-id", "material.detailId", "编号", "A5:A6", "ROW", false);
        var detailName = binding("mixed", "detail-name", "material.detailName", "名称", "B5:B6", "ROW", false);
        var detailAmount = binding("mixed", "detail-amount", "material.detailAmount", "数量", "C5:C6", "ROW", false);

        var result = extractor.extract(sheet, definitions("material.code", "material.detailId", "material.detailName", "material.detailAmount"),
                List.of(formRegion, formField, detailId, detailName, detailAmount), 5, 6).orElseThrow();

        assertThat(result.shape()).isEqualTo("ROW_TABLE");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows()).allMatch(row -> row.rawValues().path("b_formcode").asText().equals("M-01"));
        assertThat(result.rows().get(1).sourceMetadata().path("cells").path("b_formcode")
                .path("cellAddress").asText()).isEqualTo("B1");
    }

    @Test
    void extractsMultipleComponentsOnOneSheetWithoutMappingOrRecordCollisions() {
        var sheet = sheet("mixed-components", List.of(
                List.of("产品名称", "UV 树脂", "", ""),
                List.of("", "", "", ""),
                List.of("原料", "用量", "", ""),
                List.of("树脂", "50", "", ""),
                List.of("光引发剂", "3", "", ""),
                List.of("", "", "", ""),
                List.of("温度", "1 min", "5 min", ""),
                List.of("25℃", "10", "18", ""),
                List.of("50℃", "15", "26", "")));
        var basicLocator = mapper.createObjectNode().put("sheetId", "mixed-components")
                .put("componentId", "basic").put("address", "A1:B1");
        var basic = new TemplateDataImportFacade.ImportBinding("basic", "", "", "FORM_REGION",
                "", "", 1, 1, 1, mapper.createObjectNode(), basicLocator,
                false, false, true, "INPUT", "OBJECT", "");
        var nameLocator = mapper.createObjectNode().put("sheetId", "mixed-components")
                .put("componentId", "basic").put("valueRange", "B1");
        var name = new TemplateDataImportFacade.ImportBinding("product-name", "product.name", "/product/name",
                "SCALAR", "basic", "", 1, 1, 1, mapper.createObjectNode(), nameLocator,
                false, true, true, "INPUT", "TEXT", "");

        var detailRegion = regionBinding("detail", "ROW_TABLE", "A4:B5", "ROW");
        var material = componentField("detail", "material", "formula.material", "A4:A5", "ROW");
        var amount = componentField("detail", "amount", "formula.amount", "B4:B5", "ROW");

        var matrixLocator = mapper.createObjectNode().put("sheetId", "mixed-components")
                .put("componentId", "matrix").put("rowHeaderRange", "A8:A9")
                .put("columnHeaderRange", "B7:C7").put("crossDataRange", "B8:C9");
        var matrix = new TemplateDataImportFacade.ImportBinding("matrix", "", "", "MATRIX_REGION",
                "", "", 1, 1, 1, mapper.createObjectNode(), matrixLocator,
                false, false, true, "INPUT", "OBJECT", "");
        var resultValue = new TemplateDataImportFacade.ImportBinding("result", "test.result", "/test/result",
                "MATRIX_FIELD", "matrix", "", 1, 1, 1, mapper.createObjectNode(), matrixLocator,
                false, false, true, "INPUT", "NUMBER", "");

        var result = extractor.extract(sheet, definitions("product.name", "formula.material", "formula.amount", "test.result"),
                List.of(basic, name, detailRegion, material, amount, matrix, resultValue), 1, 9).orElseThrow();

        assertThat(result.shape()).isEqualTo("MULTI_COMPONENT");
        assertThat(result.rows()).hasSize(6);
        assertThat(result.rows()).extracting(row -> row.sourceMetadata().path("componentId").asText())
                .containsOnly("detail", "matrix");
        assertThat(result.mappings()).extracting(item -> item.detail().path("componentId").asText())
                .contains("detail", "matrix").doesNotContain("basic");
        assertThat(result.rows()).extracting(DataRepository.Row::rowNumber).contains(1, 2, 3, 4);
    }

    @Test
    void keepsLongBindingKeysDistinctWithinDatabaseColumnLimit() {
        var sheet = sheet("mixed-components", List.of(List.of("A", "B"), List.of("1", "2")));
        var first = componentField("detail", "binding-with-a-very-long-common-prefix-aaaaaaaa-one",
                "field.one", "A2", "ROW");
        var second = componentField("detail", "binding-with-a-very-long-common-prefix-aaaaaaaa-two",
                "field.two", "B2", "ROW");

        var result = extractor.extract(sheet, definitions("field.one", "field.two"), List.of(first, second), 2, 2)
                .orElseThrow();

        assertThat(result.mappings()).extracting(DataRepository.Mapping::sourceColumn).doesNotHaveDuplicates();
        assertThat(result.mappings()).allMatch(item -> item.sourceColumn().length() <= 32);
    }

    private TemplateDataImportFacade.ImportBinding regionBinding(String componentId, String kind,
                                                                 String range, String axis) {
        var locator = mapper.createObjectNode().put("sheetId", "mixed-components")
                .put("componentId", componentId).put("range", range).put("dataRange", range);
        return new TemplateDataImportFacade.ImportBinding(componentId, "", "", kind, "", axis,
                1, 1, 1, mapper.createObjectNode(), locator, false, false, true,
                "INPUT", "OBJECT", "");
    }

    private TemplateDataImportFacade.ImportBinding componentField(String componentId, String bindingId,
                                                                  String fieldCode, String range, String axis) {
        var locator = mapper.createObjectNode().put("sheetId", "mixed-components")
                .put("componentId", componentId).put("valueRange", range);
        return new TemplateDataImportFacade.ImportBinding(bindingId, fieldCode, "/" + fieldCode,
                "REPEAT_FIELD", componentId, axis, 1, 1, 1, mapper.createObjectNode(), locator,
                false, false, true, "INPUT", "TEXT", "");
    }

    private TemplateDataImportFacade.ParsedSheet sheet(String id, List<List<String>> rows) {
        return new TemplateDataImportFacade.ParsedSheet(id, id, 1, 1, rows.size(), 1,
                rows.stream().mapToInt(List::size).max().orElse(0), List.of(1), 1, 2, rows);
    }

    private List<TemplateDataImportFacade.FieldDefinition> definitions(String... codes) {
        return java.util.Arrays.stream(codes)
                .map(code -> new TemplateDataImportFacade.FieldDefinition(code, code, "TEXT", "", false,
                        code.endsWith("id") || code.endsWith("name"), List.of(), "/" + code))
                .toList();
    }

    private TemplateDataImportFacade.ImportBinding binding(String id, String code, String name,
                                                            String range, String axis, boolean identity) {
        return binding(axis.equals("COLUMN") ? "columns" : "rows", id, code, name, range, axis, identity);
    }

    private TemplateDataImportFacade.ImportBinding binding(String sheetId, String id, String code, String name,
                                                            String range, String axis, boolean identity) {
        var locator = mapper.createObjectNode().put("sheetId", sheetId).put("valueRange", range);
        return new TemplateDataImportFacade.ImportBinding(id, code, "/" + code, "REPEAT_FIELD", "region",
                axis, 1, 1, 1, mapper.createObjectNode(), locator, false, identity, true, "INPUT", "TEXT", "");
    }

    private TemplateDataImportFacade.ImportBinding rowBinding(String id, String code, String name, String range) {
        var locator = mapper.createObjectNode().put("sheetId", "materials")
                .put("valueRange", range).put("valueMode", "ARRAY_COLUMN");
        return new TemplateDataImportFacade.ImportBinding(id, code, "/" + code, "REPEAT_FIELD", "region",
                "ROW", 1, 1, 1, mapper.createObjectNode(), locator, false, code.endsWith("code"), true,
                "INPUT", "TEXT", "");
    }
}
