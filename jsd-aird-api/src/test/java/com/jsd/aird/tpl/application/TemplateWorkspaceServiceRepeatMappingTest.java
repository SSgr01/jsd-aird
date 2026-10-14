package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.junit.jupiter.api.Test;

class TemplateWorkspaceServiceRepeatMappingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TemplateWorkspaceService service = mock(
            TemplateWorkspaceService.class, CALLS_REAL_METHODS);

    @Test
    void allowsColumnIdentityFieldInsideOverallRegionButOutsideDataRange() {
        var mapping = columnMapping("sheet-1", "D8:J8");

        assertThatCode(() -> validate(mapping)).doesNotThrowAnyException();
    }

    @Test
    void rejectsColumnFieldOutsideOverallRegion() {
        var mapping = columnMapping("sheet-1", "D7:J7");

        assertThatThrownBy(() -> validate(mapping))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("明细字段位置超出了父级重复区域");
    }

    @Test
    void rejectsRepeatFieldOnDifferentSheet() {
        var mapping = columnMapping("sheet-2", "D8:J8");

        assertThatThrownBy(() -> validate(mapping))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("明细字段与父级重复区域不在同一工作表");
    }

    @Test
    void acceptsAdjacentDColumnWithinExpandedRowRegion() {
        assertThatCode(() -> validate(rowMapping("sheet-1", "D2:D3", false)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRowFieldOutsideParentOrOverlappingSibling() {
        assertThatThrownBy(() -> validate(rowMapping("sheet-1", "E2:E3", false)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("明细字段位置超出了父级重复区域");
        assertThatThrownBy(() -> validate(rowMapping("sheet-1", "D2:D3", true)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("明细字段位置发生重叠");
    }

    @Test
    void wordFieldWithoutContentControlBlocksPublicationEvenWhenOptional() throws Exception {
        var schema = objectMapper.createObjectNode();
        schema.set(TemplateRecognitionCompiler.FIELD_MODEL_KEY,
                objectMapper.createObjectNode().set("fields", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("id", "word-field").put("bindingId", "word-binding")
                                .put("name", "项目名称").put("required", false))));
        var mapping = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("bindingId", "word-binding"));
        var method = TemplateWorkspaceService.class.getDeclaredMethod(
                "requiredFieldsWithoutPosition", JsonNode.class, JsonNode.class, TemplateFormat.class);
        method.setAccessible(true);
        var missing = (List<?>) method.invoke(service, schema, mapping, TemplateFormat.DOCX);
        assertThat(missing).hasSize(1);
    }

    @Test
    void wordSchemaKeepsFieldModelAndValidMarkerMapping() throws Exception {
        var schema = objectMapper.createObjectNode();
        schema.set(TemplateRecognitionCompiler.FIELD_MODEL_KEY,
                objectMapper.createObjectNode().set("fields", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode().put("id", "word-field")
                                .put("bindingId", "word-binding"))));
        var normalize = TemplateWorkspaceService.class.getDeclaredMethod("normalizeWordSchema", JsonNode.class);
        normalize.setAccessible(true);
        var normalized = (JsonNode) normalize.invoke(service, schema);
        assertThat(normalized.path(TemplateRecognitionCompiler.FIELD_MODEL_KEY).path("fields")).hasSize(1);

        var binding = objectMapper.createObjectNode()
                .put("bindingId", "word-binding")
                .put("dataPath", "/fields/projectName")
                .put("locatorType", "DOCX_CONTENT_CONTROL")
                .put("markerId", "FIELD.PROJECT")
                .put("syncDirection", "TWO_WAY")
                .put("bindingStatus", "VALID");
        binding.set("locator", objectMapper.createObjectNode().put("controlId", "FIELD.PROJECT"));
        assertThatCode(() -> service.validateMappings(TemplateFormat.DOCX,
                objectMapper.createArrayNode().add(binding))).doesNotThrowAnyException();
    }

    @Test
    void wordExportLeavesOnlyControlsWithFormalBindings() throws Exception {
        var snapshot = objectMapper.createObjectNode();
        var ranges = snapshot.putObject("body").putArray("customRanges");
        for (var marker : List.of("kept", "discarded")) {
            ranges.addObject().put("rangeId", marker).putObject("properties")
                    .put("source", "JSD_MAPPING");
        }
        ranges.addObject().put("rangeId", "imported").putObject("properties")
                .put("source", "DOCX_CONTENT_CONTROL");
        var mapping = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("markerId", "kept"));
        var method = TemplateWorkspaceService.class.getDeclaredMethod(
                "wordSnapshotWithFormalControls", JsonNode.class, JsonNode.class);
        method.setAccessible(true);
        var filtered = (JsonNode) method.invoke(service, snapshot, mapping);
        assertThat(filtered.path("body").path("customRanges")).hasSize(2);
        assertThat(snapshot.path("body").path("customRanges")).hasSize(3);
    }

    @Test
    void allowsSameSemanticPathOnDifferentWorksheets() {
        var mapping = objectMapper.createArrayNode()
                .add(scalarMapping("binding-1", "sheet-1", "A1"))
                .add(scalarMapping("binding-2", "sheet-2", "A1"));

        assertThatCode(() -> service.validateMappings(TemplateFormat.XLSX, mapping))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicatePrimaryPathOnSameWorksheet() {
        var mapping = objectMapper.createArrayNode()
                .add(scalarMapping("binding-1", "sheet-1", "A1"))
                .add(scalarMapping("binding-2", "sheet-1", "B1"));

        assertThatThrownBy(() -> service.validateMappings(TemplateFormat.XLSX, mapping))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("同一路径只能有一个主绑定");
    }

    private ObjectNode scalarMapping(String bindingId, String sheetId, String address) {
        return objectMapper.createObjectNode()
                .put("bindingId", bindingId)
                .put("dataPath", "/fields/备注")
                .put("locatorType", "CELL_RANGE")
                .put("syncDirection", "TWO_WAY")
                .put("primaryBinding", true)
                .set("locator", objectMapper.createObjectNode()
                        .put("sheetId", sheetId)
                        .put("address", address));
    }

    private void validate(ArrayNode mapping) {
        Map<String, JsonNode> bindingsById = new HashMap<>();
        mapping.forEach(binding -> bindingsById.put(binding.path("bindingId").asText(), binding));
        service.validateRepeatMappings(mapping, bindingsById);
    }

    private ArrayNode columnMapping(String childSheetId, String childRange) {
        var parent = objectMapper.createObjectNode()
                .put("bindingId", "parent-binding")
                .put("dataPath", "/records/application")
                .put("mappingKind", "REPEAT_REGION")
                .put("repeatAxis", "COLUMN")
                .put("recordHeight", 29)
                .put("recordWidth", 1)
                .put("recordStride", 1);
        parent.set("locator", objectMapper.createObjectNode()
                .put("sheetId", "sheet-1")
                .put("range", "A8:J37")
                .put("recordRange", "A8:J37")
                .put("dataRange", "A9:J37"));

        ObjectNode child = objectMapper.createObjectNode()
                .put("bindingId", "identity-binding")
                .put("parentBindingId", "parent-binding")
                .put("dataPath", "/records/application/*/resinCode")
                .put("mappingKind", "REPEAT_FIELD")
                .put("repeatAxis", "COLUMN");
        child.set("locator", objectMapper.createObjectNode()
                .put("sheetId", childSheetId)
                .put("valueRange", childRange));

        return objectMapper.createArrayNode().add(parent).add(child);
    }

    private ArrayNode rowMapping(String childSheetId, String childRange, boolean duplicate) {
        var parent = objectMapper.createObjectNode()
                .put("bindingId", "parent-row")
                .put("dataPath", "/records/items")
                .put("mappingKind", "REPEAT_REGION")
                .put("repeatAxis", "ROW")
                .put("recordHeight", 1).put("recordWidth", 4).put("recordStride", 1);
        parent.set("locator", objectMapper.createObjectNode()
                .put("sheetId", "sheet-1").put("range", "A1:D3")
                .put("dataRange", "A2:D3"));
        var child = objectMapper.createObjectNode()
                .put("bindingId", "child-row")
                .put("parentBindingId", "parent-row")
                .put("dataPath", "/records/items/*/detail")
                .put("mappingKind", "REPEAT_FIELD")
                .put("repeatAxis", "ROW");
        child.set("locator", objectMapper.createObjectNode()
                .put("sheetId", childSheetId).put("valueRange", childRange));
        var mapping = objectMapper.createArrayNode().add(parent).add(child);
        if (duplicate) {
            var overlapping = child.deepCopy();
            overlapping.put("bindingId", "overlap-row");
            overlapping.put("dataPath", "/records/items/*/overlap");
            mapping.add(overlapping);
        }
        return mapping;
    }
}
