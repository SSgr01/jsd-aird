package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MappingPathNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsStableParentAndChildPathsWithoutUsingDocumentCoordinates() {
        var parent = objectMapper.createObjectNode()
                .put("bindingId", "parent-binding")
                .put("fieldCode", "TABLE.MATERIALS")
                .put("mappingKind", "REPEAT_REGION")
                .put("locatorType", "CELL_RANGE");
        parent.set("locator", objectMapper.createObjectNode().put("address", "A6:G22"));
        var child = objectMapper.createObjectNode()
                .put("bindingId", "child-binding")
                .put("fieldCode", "TABLE.MATERIALS.CODE")
                .put("mappingKind", "REPEAT_FIELD")
                .put("parentBindingId", "parent-binding")
                .put("locatorType", "CELL_RANGE");
        child.set("locator", objectMapper.createObjectNode().put("valueRange", "B7:B21"));
        var mapping = objectMapper.createArrayNode().add(child).add(parent);

        var normalized = MappingPathNormalizer.normalize(mapping);

        assertThat(normalized.get(1).path("dataPath").asText())
                .isEqualTo("/records/TABLE_MATERIALS");
        assertThat(normalized.get(0).path("dataPath").asText())
                .isEqualTo("/records/TABLE_MATERIALS/*/TABLE_MATERIALS_CODE");
        assertThat(normalized.get(1).path("diagnostic").path("dataPathSource").asText())
                .isEqualTo("BACKEND_STABLE_FALLBACK");
    }

    @Test
    void preservesAnExistingPath() {
        var binding = objectMapper.createObjectNode()
                .put("bindingId", "binding")
                .put("fieldCode", "FIELD.NAME")
                .put("mappingKind", "SCALAR")
                .put("dataPath", "/custom/name")
                .put("locatorType", "CELL_RANGE");
        binding.set("locator", objectMapper.createObjectNode().put("address", "B2"));

        var normalized = MappingPathNormalizer.normalize(objectMapper.createArrayNode().add(binding));

        assertThat(normalized.get(0).path("dataPath").asText()).isEqualTo("/custom/name");
        assertThat(normalized.get(0).path("diagnostic").has("dataPathSource")).isFalse();
    }

    @Test
    void derivesLegacyParentPathFromExplicitChildPath() {
        var parent = objectMapper.createObjectNode()
                .put("bindingId", "formula-parent")
                .put("fieldCode", "AUTO.FIELD")
                .put("mappingKind", "REPEAT_REGION")
                .put("locatorType", "TABLE_REGION");
        parent.set("locator", objectMapper.createObjectNode().put("address", "A6:G22"));
        var child = objectMapper.createObjectNode()
                .put("bindingId", "formula-sequence")
                .put("fieldCode", "FORMULA.ITEM.SEQUENCE")
                .put("mappingKind", "REPEAT_FIELD")
                .put("parentBindingId", "formula-parent")
                .put("dataPath", "/formulaItems/*/sequence")
                .put("locatorType", "CELL_RANGE");
        child.set("locator", objectMapper.createObjectNode().put("valueRange", "A7:A21"));

        var normalized = MappingPathNormalizer.normalize(
                objectMapper.createArrayNode().add(parent).add(child));

        assertThat(normalized.get(0).path("dataPath").asText()).isEqualTo("/formulaItems");
        assertThat(normalized.get(1).path("dataPath").asText())
                .isEqualTo("/formulaItems/*/sequence");
    }
}
