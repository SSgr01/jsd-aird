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
