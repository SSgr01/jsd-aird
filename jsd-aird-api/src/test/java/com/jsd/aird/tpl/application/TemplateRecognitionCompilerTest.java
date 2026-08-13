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
        assertThat(result.fieldModel().path("modelVersion").asInt()).isEqualTo(4);
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
    void compilesOneMatrixFieldWithColumnSlotsInsteadOfSixBusinessFields() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var payload = objectMapper.createObjectNode()
                .put("fieldCode", "TEST.MATRIX")
                .put("fieldName", "光引发剂性能对比矩阵")
                .put("groupName", "性能测试")
                .put("dataPath", "/records")
                .put("valueType", "array")
                .put("required", false)
                .put("relationId", "matrix-A4-H19")
                .put("fieldId", "11111111-1111-1111-1111-111111111111")
                .put("bindingId", "matrix-binding-A4-H19")
                .put("kind", "MATRIX")
                .put("role", "REPEAT_REGION")
                .put("mappingKind", "MATRIX_REGION")
                .put("repeatAxis", "COLUMN")
                .put("recordHeight", 16)
                .put("recordWidth", 1)
                .put("recordStride", 1)
                .put("editability", "EDITABLE")
                .put("valueSource", "MIXED")
                .put("locatorType", "MATRIX_REGION");
        payload.set("locator", objectMapper.createObjectNode()
                .put("sheetId", "sheet-1")
                .put("address", "A4:H19")
                .put("rowHeaderRange", "A5:B19")
                .put("columnHeaderRange", "C4:H4")
                .put("crossDataRange", "C5:H19"));
        var slots = objectMapper.createArrayNode();
        for (var column : List.of("C", "D", "E", "F", "G", "H")) {
            slots.add(objectMapper.createObjectNode().put("slotId", "column-" + column)
                    .put("column", column).put("identityAddress", column + "4")
                    .put("recordRange", column + "4:" + column + "19"));
        }
        payload.set("matrixModel", objectMapper.createObjectNode()
                .put("semanticMode", "CROSS_TAB")
                .put("recordAxis", "COLUMN")
                .put("rowHeaderRange", "A5:B19")
                .put("columnHeaderRange", "C4:H4")
                .put("crossDataRange", "C5:H19")
                .set("columnSlots", slots));
        payload.set("columnSlots", slots.deepCopy());
        var suggestion = new TemplateImportRepository.RecognitionSuggestionView(
                UUID.randomUUID(), UUID.randomUUID(), "MODEL", "MATRIX", payload,
                0.95, objectMapper.createArrayNode(), "ACCEPTED", "model", "v2", "v2", Instant.now()
        );

        var result = compiler.compile(schema, List.of(suggestion), TemplateFormat.XLSX);

        assertThat(result.fieldModel().path("fields")).singleElement()
                .satisfies(field -> assertThat(field.path("columnSlots")).hasSize(6));
        assertThat(result.mapping()).singleElement().satisfies(binding -> {
            assertThat(binding.path("mappingKind").asText()).isEqualTo("MATRIX_REGION");
            assertThat(binding.path("repeatAxis").asText()).isEqualTo("COLUMN");
            assertThat(binding.path("recordHeight").asInt()).isEqualTo(16);
        });
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
                UUID.randomUUID(), UUID.randomUUID(), "MODEL", "TABLE_REGION", payload,
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
                UUID.randomUUID(), UUID.randomUUID(), "MODEL", "TABLE_REGION", parentPayload,
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
                UUID.randomUUID(), UUID.randomUUID(), "MODEL", "TABLE_REGION", parentPayload,
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
                .put("kind", "MATRIX")
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
