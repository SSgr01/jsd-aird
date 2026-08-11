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
}
