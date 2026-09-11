package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import org.junit.jupiter.api.Test;

class TemplateImportContractCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TemplateImportContractCompiler compiler = new TemplateImportContractCompiler(
            objectMapper, new JsonCanonicalizer(objectMapper));

    @Test
    void keepsLayoutAndContractVersionsSeparateAndGroupsBindingsByComponent() {
        var layout = objectMapper.createObjectNode().put("structureVersion", 6);
        var schema = objectMapper.createObjectNode();
        schema.putObject(TemplateRecognitionCompiler.FIELD_MODEL_KEY).putArray("fields")
                .add(objectMapper.createObjectNode().put("fieldCode", "MATERIAL.NAME").put("name", "物料名称"));
        var mappings = objectMapper.createArrayNode();
        mappings.add(objectMapper.createObjectNode()
                .put("componentId", "ingredients").put("bindingId", "region-binding")
                .put("mappingKind", "ROW_TABLE").put("fieldName", "配方明细")
                .set("locator", objectMapper.createObjectNode().put("sheetId", "sheet-1").put("range", "A6:G22")));
        mappings.add(objectMapper.createObjectNode()
                .put("componentId", "ingredients").put("parentBindingId", "region-binding")
                .put("bindingId", "material-name").put("fieldCode", "MATERIAL.NAME")
                .put("fieldName", "物料名称").put("labelPath", "配方明细 > 物料名称")
                .put("mappingKind", "REPEAT_FIELD")
                .set("locator", objectMapper.createObjectNode().put("sheetId", "sheet-1").put("valueRange", "B7:B21")));

        var result = compiler.compile(layout, schema, mappings);

        assertThat(result.importContractVersion()).isEqualTo(8);
        assertThat(result.layoutStructureVersion()).isEqualTo(6);
        assertThat(result.contract().has("targetDataType")).isFalse();
        assertThat(result.contract().path("components")).singleElement().satisfies(component -> {
            assertThat(component.path("componentId").asText()).isEqualTo("ingredients");
            assertThat(component.path("structureType").asText()).isEqualTo("ROW_TABLE");
            assertThat(component.path("range").asText()).isEqualTo("A6:G22");
            assertThat(component.path("bindings")).hasSize(2);
        });
    }

    @Test
    void keepsOrdinaryTemplatesOnV8WithoutAddingExperimentFields() {
        var mappings = objectMapper.createArrayNode().add(scalar(
                "name", "MATERIAL.NAME", "产品名称", "A1", "B1"));

        var result = compiler.compile(objectMapper.createObjectNode(), objectMapper.createObjectNode(), mappings);

        assertThat(result.importContractVersion()).isEqualTo(8);
        assertThat(result.contract().has("templateUsage")).isFalse();
        assertThat(result.contract().has("experimentImport")).isFalse();
        assertThat(result.contract().path("components").get(0).path("bindings").get(0)
                .has("experimentField")).isFalse();
        assertThat(result.contractHash()).isEqualTo(
                "405b70a4542a071acf3436cf91f9283c847dd955586cb64cc45817f9a600965a");
    }

    @Test
    void compilesV9WithMultipleIdentityBindingsAndDerivedTargetPaths() {
        var schema = experimentSchema("BY_IDENTITY");
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) schema.path(
                TemplateImportContractCompiler.EXPERIMENT_IMPORT_SCHEMA_KEY);
        var mappings = objectMapper.createArrayNode();
        addApplicationComponent(mappings, config, "sheet-1", "component-1", "identity-1");
        addApplicationComponent(mappings, config, "sheet-2", "component-2", "identity-2");
        ((com.fasterxml.jackson.databind.node.ObjectNode) mappings.get(1))
                .put("targetPath", "/client/must/not/win")
                .set("experimentField", objectMapper.createObjectNode()
                        .put("domain", "BASIC").put("field", "SOURCE_IDENTITY"));

        var result = compiler.compile(objectMapper.createObjectNode(), schema, mappings);

        assertThat(result.importContractVersion()).isEqualTo(9);
        assertThat(result.contract().path("templateUsage").asText()).isEqualTo("EXPERIMENT_DATA");
        assertThat(result.contract().path("recordKeyPolicy").asText()).isEqualTo("STRUCTURAL");
        assertThat(result.contract().path("experimentImport").path("identities")).hasSize(2);
        assertThat(result.contract().path("listProjections")).hasSize(2);
        var identity = result.contract().path("components").get(0).path("bindings").get(1);
        assertThat(identity.path("experimentField").path("field").asText()).isEqualTo("SOURCE_IDENTITY");
        assertThat(identity.path("targetPath").asText()).isEqualTo("/sourceIdentity");
        assertThat(identity.path("targetPath").asText()).isNotEqualTo("/client/must/not/win");
    }

    @Test
    void rejectsByIdentityExperimentTemplateWithoutIdentityBinding() {
        var schema = experimentSchema("BY_IDENTITY");

        assertThatThrownBy(() -> compiler.compile(
                objectMapper.createObjectNode(), schema, objectMapper.createArrayNode()))
                .hasMessageContaining("至少需要配置一个有效标识字段");
    }

    @Test
    void singleFileExperimentTemplateDoesNotRequireIdentity() {
        var schema = experimentSchema("SINGLE_FILE");

        var result = compiler.compile(objectMapper.createObjectNode(), schema, objectMapper.createArrayNode());

        assertThat(result.importContractVersion()).isEqualTo(9);
        assertThat(result.contract().path("experimentImport").path("identities")).isEmpty();
    }

    @Test
    void leavesMissingPhysicalLocationBlankInsteadOfInventingOne() {
        var mappings = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("bindingId", "field-only").put("mappingKind", "SCALAR"));

        var component = compiler.compile(objectMapper.createObjectNode(), objectMapper.createObjectNode(), mappings)
                .contract().path("components").get(0);

        assertThat(component.path("sheetId").asText()).isBlank();
        assertThat(component.path("range").asText()).isBlank();
    }

    @Test
    void groupsUnparentedScalarFieldsIntoOneBasicInformationComponent() {
        var mappings = objectMapper.createArrayNode();
        mappings.add(scalar("name", "MATERIAL.NAME", "产品名称", "A1", "B1"));
        mappings.add(scalar("batch", "MATERIAL.BATCH", "批号", "C1", "D1"));

        var components = compiler.compile(objectMapper.createObjectNode(), objectMapper.createObjectNode(), mappings)
                .contract().path("components");

        assertThat(components).hasSize(1);
        assertThat(components.get(0).path("name").asText()).isEqualTo("基本信息");
        assertThat(components.get(0).path("structureType").asText()).isEqualTo("FORM_REGION");
        assertThat(components.get(0).path("bindings")).hasSize(2);
    }

    @Test
    void keepsFormBlocksSeparateAndUsesSchemaNamesInsteadOfInternalCodes() {
        var schema = objectMapper.createObjectNode();
        var fields = schema.putObject(TemplateRecognitionCompiler.FIELD_MODEL_KEY).putArray("fields");
        fields.add(objectMapper.createObjectNode().put("fieldId", "field-name")
                .put("fieldCode", "TABLE.COLUMN.NAME").put("name", "产品名称"));
        fields.add(objectMapper.createObjectNode().put("fieldId", "field-pack")
                .put("fieldCode", "PACKING.VALUE").put("name", "包装规格"));
        fields.add(objectMapper.createObjectNode().put("fieldId", "field-sign")
                .put("fieldCode", "SIGN.VALUE").put("name", "制单人"));
        var mappings = objectMapper.createArrayNode();
        mappings.add(scalarInBlock("top", "field-name", "TABLE.COLUMN.NAME", "A1", "B1"));
        mappings.add(scalarInBlock("footer", "field-pack", "PACKING.VALUE", "A20", "B20"));
        mappings.add(scalarInBlock("footer", "field-sign", "SIGN.VALUE", "A21", "B21"));

        var components = compiler.compile(objectMapper.createObjectNode(), schema, mappings)
                .contract().path("components");

        assertThat(components).hasSize(2);
        assertThat(components).anySatisfy(component -> {
            assertThat(component.path("componentId").asText()).isEqualTo("top");
            assertThat(component.path("name").asText()).isEqualTo("基本信息");
            assertThat(component.path("bindings").get(0).path("labelPath").asText()).isEqualTo("产品名称");
        });
        assertThat(components).anySatisfy(component -> {
            assertThat(component.path("componentId").asText()).isEqualTo("footer");
            assertThat(component.path("name").asText()).isEqualTo("审核信息");
        });
    }

    @Test
    void keepsRepeatedChildrenWithTheirRootBinding() {
        var schema = objectMapper.createObjectNode();
        schema.putObject(TemplateRecognitionCompiler.FIELD_MODEL_KEY).putArray("fields")
                .add(objectMapper.createObjectNode().put("fieldId", "field-result")
                        .put("fieldCode", "TABLE.COLUMN.RESULT").put("name", "测试结果"));
        var mappings = objectMapper.createArrayNode();
        var root = objectMapper.createObjectNode().put("bindingId", "table-root")
                .put("mappingKind", "REPEAT_REGION");
        root.putObject("diagnostic").put("blockId", "table-block").put("kind", "COLUMN_TABLE");
        root.set("locator", objectMapper.createObjectNode().put("sheetId", "sheet-1")
                .put("range", "A4:N100"));
        mappings.add(root);
        var child = objectMapper.createObjectNode().put("bindingId", "result-binding")
                .put("parentBindingId", "table-root").put("fieldId", "field-result")
                .put("fieldCode", "TABLE.COLUMN.RESULT").put("mappingKind", "REPEAT_FIELD");
        child.set("locator", objectMapper.createObjectNode().put("sheetId", "sheet-1")
                .put("valueRange", "E5:N100"));
        mappings.add(child);

        var components = compiler.compile(objectMapper.createObjectNode(), schema, mappings)
                .contract().path("components");

        assertThat(components).singleElement().satisfies(component -> {
            assertThat(component.path("componentId").asText()).isEqualTo("table-root");
            assertThat(component.path("structureType").asText()).isEqualTo("COLUMN_TABLE");
            assertThat(component.path("name").asText()).isEqualTo("测试数据");
            assertThat(component.path("bindings")).hasSize(2);
        });
    }

    private com.fasterxml.jackson.databind.node.ObjectNode scalar(
            String bindingId, String fieldCode, String displayName, String label, String value
    ) {
        var mapping = objectMapper.createObjectNode()
                .put("bindingId", bindingId).put("fieldCode", fieldCode)
                .put("mappingKind", "SCALAR").put("fieldName", displayName);
        mapping.set("locator", objectMapper.createObjectNode().put("sheetId", "sheet-1")
                .put("labelRange", label).put("valueRange", value));
        return mapping;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode scalarInBlock(
            String blockId, String fieldId, String fieldCode, String label, String value
    ) {
        var mapping = scalar(fieldId + "-binding", fieldCode, fieldCode, label, value)
                .put("fieldId", fieldId);
        mapping.putObject("diagnostic").put("blockId", blockId).put("kind", "FORM_REGION");
        return mapping;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode experimentSchema(String recordMode) {
        var schema = objectMapper.createObjectNode();
        schema.putObject(TemplateRecognitionCompiler.FIELD_MODEL_KEY).putArray("fields");
        var config = schema.putObject(TemplateImportContractCompiler.EXPERIMENT_IMPORT_SCHEMA_KEY)
                .put("templateUsage", "EXPERIMENT_DATA")
                .put("recordMode", recordMode);
        config.putArray("identities");
        config.putArray("listProjections");
        return schema;
    }

    private void addApplicationComponent(com.fasterxml.jackson.databind.node.ArrayNode mappings,
                                         com.fasterxml.jackson.databind.node.ObjectNode config,
                                         String sheetId, String componentId, String identityBindingId) {
        var root = objectMapper.createObjectNode().put("bindingId", componentId)
                .put("componentId", componentId).put("mappingKind", "REPEAT_REGION")
                .put("repeatAxis", "COLUMN");
        root.putObject("diagnostic").put("kind", "COLUMN_TABLE").put("displayName", sheetId);
        root.set("locator", objectMapper.createObjectNode().put("sheetId", sheetId)
                .put("componentId", componentId).put("range", "C16:I36"));
        mappings.add(root);
        var identity = objectMapper.createObjectNode().put("bindingId", identityBindingId)
                .put("parentBindingId", componentId).put("fieldCode", "EXPERIMENT.NO")
                .put("dataPath", "/experimentNo").put("mappingKind", "REPEAT_FIELD")
                .put("repeatAxis", "COLUMN");
        identity.set("locator", objectMapper.createObjectNode().put("sheetId", sheetId)
                .put("componentId", componentId).put("valueRange", "D16:I16"));
        mappings.add(identity);
        config.withArray("identities").add(objectMapper.createObjectNode()
                .put("identityType", "EXPERIMENT_NO").put("sourceKind", "BINDING")
                .put("componentId", componentId).put("bindingId", identityBindingId));
        config.withArray("listProjections").add(objectMapper.createObjectNode()
                .put("listProjectionId", "formula-" + componentId)
                .put("domain", "FORMULA").put("componentId", componentId)
                .put("parentBindingId", componentId).put("recordAxis", "COLUMN")
                .put("itemAxis", "ROW").put("labelRange", "C17:C25")
                .put("valueRange", "D17:I25").put("labelSemantic", "MATERIAL_NAME")
                .put("valueSemantic", "RATIO"));
    }
}
