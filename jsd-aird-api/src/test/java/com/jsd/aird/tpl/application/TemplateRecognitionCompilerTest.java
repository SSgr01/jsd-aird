package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.tpl.application.port.TemplateImportRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.junit.jupiter.api.Test;

class TemplateRecognitionCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TemplateRecognitionCompiler compiler = new TemplateRecognitionCompiler(objectMapper);

    @Test
    void compilesOnlyAcceptedSuggestionsIntoTheCanonicalDraft() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var accepted = suggestion("ACCEPTED", "/product/name", "产品名称", "基本信息");
        var pending = suggestion("PENDING", "/product/code", "产品编号", "基本信息");
        var ignored = suggestion("REJECTED", "/product/ignored", "忽略字段", "基本信息");

        var result = compiler.compile(schema, List.of(accepted, pending, ignored), TemplateFormat.XLSX);

        assertThat(result.mapping()).singleElement();
        assertThat(result.schema().path("properties").path("product").path("properties").path("name").path("title").asText())
                .isEqualTo("产品名称");
        assertThat(result.fieldModel().path("groups")).hasSize(1);
        assertThat(result.fieldModel().path("fields")).singleElement();
        assertThat(result.fieldModel().path("modelVersion").asInt()).isEqualTo(5);
        assertThat(result.fieldModel().path("fields").get(0).path("reviewStatus").asText())
                .isEqualTo("CONFIRMED");
    }

    @Test
    void keepsLowConfidencePendingFieldsOutOfCanonicalMapping() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var lowConfidence = suggestion("PENDING", "/test/result", "测试结果", "性能测试", 0.62);

        var result = compiler.compile(schema, List.of(lowConfidence), TemplateFormat.XLSX);

        assertThat(result.mapping()).isEmpty();
        assertThat(result.fieldModel().path("fields")).isEmpty();
    }

    @Test
    void createsStableIdsAndOneWayFormulaBindingsButNoUnknownBinding() throws Exception {
        var firstSchema = objectMapper.createObjectNode().put("type", "object");
        firstSchema.set("properties", objectMapper.createObjectNode());
        var secondSchema = firstSchema.deepCopy();
        var formula = suggestion("ACCEPTED", "/result/total", "合计", "基础信息", 0.9,
                "READ_ONLY", "FORMULA");
        ((com.fasterxml.jackson.databind.node.ObjectNode) formula.payload()).put("labelPath", "合计");
        var unknown = suggestion("PENDING", "/result/unclear", "待确认", "基础信息", 0.5,
                "UNKNOWN", "UNKNOWN");

        var first = compiler.compile(firstSchema, List.of(formula, unknown), TemplateFormat.XLSX);
        var second = compiler.compile(secondSchema, List.of(formula, unknown), TemplateFormat.XLSX);

        assertThat(first.fieldModel().path("fields")).singleElement();
        assertThat(first.mapping()).singleElement().satisfies(binding ->
                assertThat(binding.path("syncDirection").asText()).isEqualTo("EDITOR_TO_DATA"));
        assertThat(first.fieldModel().path("fields").get(0).path("id").asText())
                .isEqualTo(second.fieldModel().path("fields").get(0).path("id").asText());
        assertThat(first.mapping().get(0).path("bindingId").asText())
                .isEqualTo(second.mapping().get(0).path("bindingId").asText());
    }

    @Test
    void neverCompilesStaticInstructionsEvenIfTheyWereIncorrectlyAccepted() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var instruction = suggestion("ACCEPTED", "/instruction/step", "操作步骤", "基础信息");
        ((com.fasterxml.jackson.databind.node.ObjectNode) instruction.payload()).put("blockType", "INSTRUCTION_LIST");
        ((com.fasterxml.jackson.databind.node.ObjectNode) instruction.payload()).put("valueSource", "STATIC");

        var result = compiler.compile(schema, List.of(instruction), TemplateFormat.XLSX);

        assertThat(result.mapping()).isEmpty();
        assertThat(result.fieldModel().path("fields")).isEmpty();
    }

    @Test
    void neverCompilesAcceptedSuggestionInsideTemplateBaselineRegion() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var accepted = suggestion("ACCEPTED", "/instruction/step", "操作步骤", "基础信息");
        var semanticModel = objectMapper.createObjectNode().put("kind", "SEMANTIC_MODEL");
        semanticModel.set("semanticAnnotations", objectMapper.createArrayNode().add(
                objectMapper.createObjectNode()
                        .put("sheetId", "sheet-1")
                        .put("range", "B1:B2")
                        .put("role", "INSTRUCTION")
        ));
        semanticModel.set("businessBlocks", objectMapper.createArrayNode());
        var semanticSuggestion = new TemplateImportRepository.RecognitionSuggestionView(
                UUID.randomUUID(), UUID.randomUUID(), "MODEL", "SEMANTIC_MODEL", semanticModel,
                1.0, objectMapper.createArrayNode(), "PENDING", "model", "v2", "v2", Instant.now()
        );

        var result = compiler.compile(schema, List.of(semanticSuggestion, accepted), TemplateFormat.XLSX);

        assertThat(result.mapping()).isEmpty();
        assertThat(result.fieldModel().path("fields")).isEmpty();
        assertThat(result.fieldModel().path("staticRegions")).singleElement()
                .satisfies(region -> assertThat(region.path("address").asText()).isEqualTo("B1:B2"));
    }

    @Test
    void preservesColumnTableDirectionInCanonicalDraftAndContractMapping() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var payload = objectMapper.createObjectNode()
                .put("fieldCode", "AUTO.FIELD")
                .put("fieldName", "性能记录")
                .put("groupName", "性能测试")
                .put("dataPath", "/records/component_a")
                .put("valueType", "array")
                .put("relationId", "column-table-A4-N100")
                .put("fieldId", "11111111-1111-1111-1111-111111111112")
                .put("bindingId", "11111111-1111-1111-1111-111111111113")
                .put("kind", "COLUMN_TABLE")
                .put("role", "REPEAT_REGION")
                .put("mappingKind", "REPEAT_REGION")
                .put("repeatAxis", "COLUMN")
                .put("editability", "EDITABLE")
                .put("valueSource", "USER_INPUT")
                .put("locatorType", "TABLE_REGION")
                .put("canonicalStatus", "CONFIRMED")
                .put("structureStatus", "CONFIRMED");
        payload.set("locator", objectMapper.createObjectNode()
                .put("sheetId", "sheet-1").put("address", "A4:N100")
                .put("range", "A4:N100").put("locatorType", "TABLE_REGION"));
        payload.set("columns", objectMapper.createArrayNode().add(
                objectMapper.createObjectNode().put("code", "外观").put("name", "外观")
                        .put("dataPath", "/records/component_a/*/外观")
                        .put("fieldCode", "TABLE.COLUMN.外观").put("valueType", "string")
                        .put("labelRange", "A5:C5").put("valueRange", "E5:N5")
                        .put("editability", "EDITABLE").put("valueSource", "USER_INPUT")));
        var suggestion = new TemplateImportRepository.RecognitionSuggestionView(
                UUID.randomUUID(), UUID.randomUUID(), "MODEL", "COLUMN_TABLE", payload,
                0.95, objectMapper.createArrayNode(), "ACCEPTED", "model", "v2", "v2", Instant.now());

        var result = compiler.compile(schema, List.of(suggestion), TemplateFormat.XLSX);

        assertThat(result.fieldModel().path("fields").get(0).path("kind").asText())
                .isEqualTo("COLUMN_TABLE");
        assertThat(result.fieldModel().path("fields").get(0).path("interpretation").asText())
                .contains("每一列");
        assertThat(result.mapping().get(0).path("repeatAxis").asText()).isEqualTo("COLUMN");
        assertThat(result.schema().path("properties").path("records").path("properties")
                .path("component_a").path("x-region-kind").asText()).isEqualTo("COLUMN_TABLE");
    }

    @Test
    void emitsCanonicalLabelAndValueLocatorForLabelRangeOnlyPayload() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var payload = objectMapper.createObjectNode()
                .put("fieldCode", "TEST.VISCOSITY")
                .put("fieldName", "粘度")
                .put("groupName", "技术指标")
                .put("dataPath", "/technical/viscosity")
                .put("relationId", "viscosity-A5-D5")
                .put("kind", "SCALAR")
                .put("editability", "EDITABLE")
                .put("valueSource", "USER_INPUT")
                .put("locatorType", "CELL_RANGE")
                .set("locator", objectMapper.createObjectNode()
                        .put("sheetId", "sheet-1")
                        .put("labelRange", "A5:C5")
                        .put("address", "D5:H5"));
        var suggestion = new TemplateImportRepository.RecognitionSuggestionView(
                UUID.randomUUID(), UUID.randomUUID(), "RULE", "SCALAR_FIELD", payload,
                0.98, objectMapper.createArrayNode(), "ACCEPTED", "rule", "v2", "v2", Instant.now());

        var result = compiler.compile(schema, List.of(suggestion), TemplateFormat.XLSX);
        var field = result.fieldModel().path("fields").get(0);
        var binding = result.mapping().get(0);

        assertThat(field.path("fieldType").asText()).isEqualTo("FIELD");
        assertThat(field.path("labelStatus").asText()).isEqualTo("RESOLVED");
        assertThat(field.path("locator").path("label").path("range").asText()).isEqualTo("A5:C5");
        assertThat(field.path("locator").path("value").path("range").asText()).isEqualTo("D5:H5");
        assertThat(binding.path("locator").path("label").path("address").asText()).isEqualTo("A5");
    }

    @Test
    void ignoresStandaloneRegionSuggestionAndKeepsValueOnlyFieldUnresolved() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var valueOnly = suggestion("ACCEPTED", "/value", "值", "基础信息");
        ((com.fasterxml.jackson.databind.node.ObjectNode) valueOnly.payload()).set("locator",
                objectMapper.createObjectNode().put("sheetId", "sheet-1").put("address", "B2"));
        var regionPayload = objectMapper.createObjectNode()
                .put("fieldName", "基本信息区域")
                .put("dataPath", "/form")
                .put("relationId", "region-1")
                .put("kind", "FORM_REGION")
                .put("role", "REPEAT_REGION")
                .put("mappingKind", "REPEAT_REGION")
                .put("canonicalStatus", "CONFIRMED")
                .put("structureStatus", "CONFIRMED")
                .set("locator", objectMapper.createObjectNode().put("address", "A1:H3"));
        var region = new TemplateImportRepository.RecognitionSuggestionView(
                UUID.randomUUID(), UUID.randomUUID(), "MODEL", "FORM_REGION", regionPayload,
                0.95, objectMapper.createArrayNode(), "ACCEPTED", "model", "v2", "v2", Instant.now());

        var result = compiler.compile(schema, List.of(valueOnly, region), TemplateFormat.XLSX);
        assertThat(result.fieldModel().path("fields")).hasSize(1);
        var unresolvedField = java.util.stream.StreamSupport.stream(
                        result.fieldModel().path("fields").spliterator(), false)
                .filter(field -> "值".equals(field.path("name").asText()))
                .findFirst().orElseThrow();
        assertThat(unresolvedField.path("labelStatus").asText())
                .isEqualTo("UNRESOLVED");
        assertThat(result.mapping()).hasSize(1);
        assertThat(result.mapping().get(0).path("fieldName").asText()).isEqualTo("值");
    }

    @Test
    void compilesExplicitlyConfirmedReviewFieldButNotItsFormContainer() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var parentPayload = objectMapper.createObjectNode()
                .put("fieldName", "基本信息区域").put("dataPath", "/form")
                .put("relationId", "form-parent").put("kind", "FORM_REGION")
                .put("role", "REPEAT_REGION").put("mappingKind", "REPEAT_REGION")
                .put("canonicalStatus", "CONFIRMED").put("structureStatus", "CONFIRMED")
                .put("candidateOnly", false).put("reviewRequired", false)
                .put("physicalStructureOnly", false).put("structureConflict", false);
        parentPayload.set("locator", objectMapper.createObjectNode()
                .put("sheetId", "sheet-1").put("address", "A1:H3").put("range", "A1:H3"));
        var parent = new TemplateImportRepository.RecognitionSuggestionView(
                UUID.randomUUID(), UUID.randomUUID(), "MODEL", "FORM_REGION", parentPayload,
                0.95, objectMapper.createArrayNode(), "ACCEPTED", "model", "v2", "v2", Instant.now());

        var child = suggestion("ACCEPTED", "/fields/测试人", "测试人", "基础信息");
        var childPayload = (com.fasterxml.jackson.databind.node.ObjectNode) child.payload();
        childPayload.put("suggestionLevel", "ROOT").put("mappingKind", "SCALAR")
                .put("bindingId", "11111111-1111-1111-1111-111111111114")
                .put("reviewRequired", true).put("candidateOnly", false)
                .put("physicalStructureOnly", false).put("canonicalStatus", "CONFIRMED")
                .put("structureStatus", "CONFIRMED");

        var result = compiler.compile(schema, List.of(parent, child), TemplateFormat.XLSX);

        assertThat(result.fieldModel().path("fields")).singleElement()
                .satisfies(field -> assertThat(field.path("name").asText()).isEqualTo("测试人"));
        assertThat(result.mapping()).singleElement()
                .satisfies(binding -> assertThat(binding.path("fieldName").asText()).isEqualTo("测试人"));
    }

    @Test
    void compilesConfirmedChildWhenItsConfirmedParentWasReviewRequired() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var parentPayload = objectMapper.createObjectNode()
                .put("fieldName", "性能记录").put("dataPath", "/records/component_a")
                .put("relationId", "parent-column").put("kind", "COLUMN_TABLE")
                .put("role", "REPEAT_REGION").put("mappingKind", "REPEAT_REGION")
                .put("bindingId", "11111111-1111-1111-1111-111111111115")
                .put("editability", "EDITABLE").put("valueSource", "USER_INPUT")
                .put("candidateOnly", false).put("reviewRequired", true)
                .put("physicalStructureOnly", false).put("structureConflict", false)
                .put("canonicalStatus", "CONFIRMED").put("structureStatus", "CONFIRMED");
        parentPayload.set("locator", objectMapper.createObjectNode()
                .put("sheetId", "sheet-1").put("address", "A4:H19").put("range", "A4:H19"));
        var parent = new TemplateImportRepository.RecognitionSuggestionView(
                UUID.randomUUID(), UUID.randomUUID(), "MODEL", "COLUMN_TABLE", parentPayload,
                0.95, objectMapper.createArrayNode(), "ACCEPTED", "model", "v2", "v2", Instant.now());

        var childSeed = suggestion("ACCEPTED", "/records/component_a/*/外观", "外观", "性能测试");
        var childPayload = (com.fasterxml.jackson.databind.node.ObjectNode) childSeed.payload();
        childPayload.put("suggestionLevel", "CHILD").put("mappingKind", "REPEAT_FIELD")
                .put("bindingId", "11111111-1111-1111-1111-111111111116")
                .put("parentBindingId", "11111111-1111-1111-1111-111111111115")
                .put("parentRelationId", "parent-column").put("repeatAxis", "COLUMN")
                .put("reviewRequired", true).put("candidateOnly", false)
                .put("physicalStructureOnly", false).put("canonicalStatus", "CONFIRMED")
                .put("structureStatus", "CONFIRMED");
        var child = new TemplateImportRepository.RecognitionSuggestionView(
                childSeed.id(), childSeed.recognitionRunId(), childSeed.source(),
                "TABLE_CHILD_FIELD", childPayload, childSeed.confidence(), childSeed.evidence(),
                childSeed.decision(), childSeed.provider(), childSeed.model(), childSeed.promptVersion(),
                childSeed.createdAt());

        var result = compiler.compile(schema, List.of(parent, child), TemplateFormat.XLSX);

        assertThat(result.fieldModel().path("fields")).singleElement()
                .satisfies(field -> assertThat(field.path("name").asText()).isEqualTo("外观"));
        assertThat(result.mapping()).anyMatch(binding ->
                "REPEAT_FIELD".equals(binding.path("mappingKind").asText())
                        && "11111111-1111-1111-1111-111111111115".equals(
                        binding.path("parentBindingId").asText()));
    }

    @Test
    void doesNotCompileAnAcceptedButUnresolvedStructureCandidate() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var candidate = suggestion("ACCEPTED", "/records", "结构候选", "性能测试");
        ((com.fasterxml.jackson.databind.node.ObjectNode) candidate.payload())
                .put("kind", "UNKNOWN")
                .put("candidateOnly", true)
                .put("reviewRequired", true)
                .put("physicalStructureOnly", true)
                .put("structureConflict", true)
                .put("resolutionGroupId", "structure-conflict-1")
                .put("canonicalStatus", "PROVISIONAL")
                .put("structureStatus", "CONFLICT");

        var result = compiler.compile(schema, List.of(candidate), TemplateFormat.XLSX);

        assertThat(result.mapping()).isEmpty();
        assertThat(result.fieldModel().path("fields")).isEmpty();
    }

    @Test
    void materializesV9ExperimentConfigurationFromAcceptedBackendSemantics() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var identity = suggestion("ACCEPTED", "/records/component_a/experimentNo", "实验编号", "实验配方", 0.99);
        var identityPayload = (com.fasterxml.jackson.databind.node.ObjectNode) identity.payload();
        identityPayload.put("componentId", "component-a")
                .put("experimentItemLabel", "实验编号")
                .put("experimentSemanticConfidence", 0.99)
                .put("experimentSemanticStatus", "AUTO_CONFIRMED")
                .set("experimentField", objectMapper.createObjectNode()
                        .put("domain", "BASIC").put("field", "SOURCE_IDENTITY"));
        var testValue = suggestion("ACCEPTED", "/records/component_a/adhesion", "附着力", "性能测试", 0.96);
        var testPayload = (com.fasterxml.jackson.databind.node.ObjectNode) testValue.payload();
        testPayload.put("componentId", "component-a")
                .put("experimentItemLabel", "性能测试 > 附着力")
                .put("experimentSemanticConfidence", 0.96)
                .put("experimentSemanticStatus", "CONFIRMED")
                .put("experimentSemanticSource", "HUMAN")
                .set("experimentField", objectMapper.createObjectNode()
                        .put("domain", "TEST").put("field", "VALUE"));

        var result = compiler.compile(schema, List.of(identity, testValue), TemplateFormat.XLSX);
        var configuration = result.schema().path(TemplateImportContractCompiler.EXPERIMENT_IMPORT_SCHEMA_KEY);

        assertThat(configuration.path("templateUsage").asText()).isEqualTo("EXPERIMENT_DATA");
        assertThat(configuration.path("recordMode").asText()).isEqualTo("SINGLE_FILE");
        assertThat(configuration.path("identities")).singleElement().satisfies(rule -> {
            assertThat(rule.path("componentId").asText()).isEqualTo("component-a");
            assertThat(rule.path("identityType").asText()).isEqualTo("EXPERIMENT_NO");
        });
        assertThat(configuration.path("recognitionSummary").path("needsReviewCount").asInt()).isZero();
        assertThat(result.mapping()).allSatisfy(binding -> assertThat(binding.has("targetPath")).isFalse());
    }

    @Test
    void preservesBackendGeneratedFormulaProjectionFromHiddenRegionRoot() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var identity = suggestion("ACCEPTED", "/records/component_a/experimentNo", "实验编号", "实验配方", 0.99);
        var identityPayload = (com.fasterxml.jackson.databind.node.ObjectNode) identity.payload();
        identityPayload.put("componentId", "component-a")
                .put("experimentSemanticConfidence", 0.99)
                .put("experimentSemanticStatus", "AUTO_CONFIRMED")
                .set("experimentField", objectMapper.createObjectNode()
                        .put("domain", "BASIC").put("field", "SOURCE_IDENTITY"));

        var regionPayload = objectMapper.createObjectNode()
                .put("fieldName", "实验配方区域")
                .put("dataPath", "/records/component_a")
                .put("relationId", "formula-region")
                .put("componentId", "component-a")
                .put("kind", "COLUMN_TABLE")
                .put("role", "REPEAT_REGION")
                .put("mappingKind", "REPEAT_REGION")
                .put("repeatAxis", "COLUMN")
                .put("canonicalStatus", "CONFIRMED")
                .put("structureStatus", "CONFIRMED");
        regionPayload.set("locator", objectMapper.createObjectNode()
                .put("sheetId", "sheet-1").put("range", "C16:I26").put("address", "C16:I26"));
        regionPayload.set("listProjections", objectMapper.createArrayNode().add(
                objectMapper.createObjectNode()
                        .put("listProjectionId", "matrix-formula-sheet-1")
                        .put("domain", "FORMULA")
                        .put("componentId", "component-a")
                        .put("recordAxis", "COLUMN")
                        .put("itemAxis", "ROW")
                        .put("labelRange", "C17:C25")
                        .put("valueRange", "D17:I25")
                        .put("totalRange", "C26:I26")
                        .put("labelSemantic", "MATERIAL_NAME")
                        .put("valueSemantic", "RATIO")
                        .put("semanticStatus", "AUTO_CONFIRMED")));
        var region = new TemplateImportRepository.RecognitionSuggestionView(
                UUID.randomUUID(), UUID.randomUUID(), "RULE", "COLUMN_TABLE", regionPayload,
                0.98, objectMapper.createArrayNode(), "ACCEPTED", "rule", "v4", "v4", Instant.now());

        var result = compiler.compile(schema, List.of(region, identity), TemplateFormat.XLSX);

        assertThat(result.schema().path(TemplateImportContractCompiler.EXPERIMENT_IMPORT_SCHEMA_KEY)
                .path("listProjections")).singleElement().satisfies(projection -> {
            assertThat(projection.path("listProjectionId").asText()).isEqualTo("matrix-formula-sheet-1");
            assertThat(projection.path("labelRange").asText()).isEqualTo("C17:C25");
            assertThat(projection.path("valueRange").asText()).isEqualTo("D17:I25");
        });
    }

    private TemplateImportRepository.RecognitionSuggestionView suggestion(
            String decision, String dataPath, String name, String group
    ) throws Exception {
        return suggestion(decision, dataPath, name, group, 0.9);
    }

    private TemplateImportRepository.RecognitionSuggestionView suggestion(
            String decision, String dataPath, String name, String group, double confidence
    ) throws Exception {
        return suggestion(decision, dataPath, name, group, confidence, "EDITABLE", "USER_INPUT");
    }

    private TemplateImportRepository.RecognitionSuggestionView suggestion(
            String decision, String dataPath, String name, String group, double confidence,
            String editability, String valueSource
    ) throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "fieldCode":"PRODUCT.NAME",
                  "fieldName":"%s",
                  "groupName":"%s",
                  "dataPath":"%s",
                  "valueType":"string",
                  "required":false,
                  "relationId":"%s",
                  "editability":"%s",
                  "valueSource":"%s",
                  "kind":"SCALAR",
                  "role":"FIELD",
                  "locatorType":"CELL_RANGE",
                  "locator":{"sheetId":"sheet-1","sheetName":"生产单","address":"B1"}
                }
                """.formatted(name, group, dataPath,
                "rel-" + dataPath.replaceAll("[^A-Za-z0-9]", "-"), editability, valueSource));
        return new TemplateImportRepository.RecognitionSuggestionView(
                UUID.randomUUID(), UUID.randomUUID(), "RULE", "SCALAR_FIELD", payload,
                confidence, objectMapper.createArrayNode(), decision, "rule", "v2", "v2", Instant.now()
        );
    }
}
