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
    void compilesPendingSuggestionsIntoTheDraftButSkipsIgnoredSuggestions() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var accepted = suggestion("ACCEPTED", "/product/name", "产品名称", "基本信息");
        var pending = suggestion("PENDING", "/product/code", "产品编号", "基本信息");
        var ignored = suggestion("REJECTED", "/product/ignored", "忽略字段", "基本信息");

        var result = compiler.compile(schema, List.of(accepted, pending, ignored), TemplateFormat.XLSX);

        assertThat(result.mapping()).hasSize(2);
        assertThat(result.schema().path("properties").path("product").path("properties").path("name").path("title").asText())
                .isEqualTo("产品名称");
        assertThat(result.fieldModel().path("groups")).hasSize(1);
        assertThat(result.fieldModel().path("fields")).hasSize(2);
        assertThat(result.fieldModel().path("fields").get(1).path("reviewStatus").asText())
                .isEqualTo("NEEDS_CONFIRMATION");
    }

    @Test
    void marksLowConfidenceFieldsAsSuggestedReviewWithoutDroppingTheirMapping() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        var lowConfidence = suggestion("PENDING", "/test/result", "测试结果", "性能测试", 0.62);

        var result = compiler.compile(schema, List.of(lowConfidence), TemplateFormat.XLSX);

        assertThat(result.mapping()).hasSize(1);
        assertThat(result.fieldModel().path("fields").get(0).path("reviewStatus").asText())
                .isEqualTo("NEEDS_CONFIRMATION");
    }

    private TemplateImportRepository.RecognitionSuggestionView suggestion(
            String decision, String dataPath, String name, String group
    ) throws Exception {
        return suggestion(decision, dataPath, name, group, 0.9);
    }

    private TemplateImportRepository.RecognitionSuggestionView suggestion(
            String decision, String dataPath, String name, String group, double confidence
    ) throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "fieldCode":"PRODUCT.NAME",
                  "fieldName":"%s",
                  "groupName":"%s",
                  "dataPath":"%s",
                  "valueType":"string",
                  "required":false,
                  "kind":"SCALAR",
                  "role":"FIELD",
                  "locatorType":"CELL_RANGE",
                  "locator":{"sheetId":"sheet-1","sheetName":"生产单","address":"B1"}
                }
                """.formatted(name, group, dataPath));
        return new TemplateImportRepository.RecognitionSuggestionView(
                UUID.randomUUID(), UUID.randomUUID(), "RULE", "SCALAR_FIELD", payload,
                confidence, objectMapper.createArrayNode(), decision, "rule", "v2", "v2", Instant.now()
        );
    }
}
