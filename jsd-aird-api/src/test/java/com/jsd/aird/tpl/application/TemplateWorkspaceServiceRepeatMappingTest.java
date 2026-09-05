package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiException;
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
}
