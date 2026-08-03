package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.junit.jupiter.api.Test;

class RuleBasedRecognitionEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuleBasedRecognitionEngine engine = new RuleBasedRecognitionEngine(
            objectMapper, new JsonCanonicalizer(objectMapper)
    );

    @Test
    void recognizesBusinessLabelsAndKeepsAHeaderRowAsOneTableRegion() throws Exception {
        var structure = objectMapper.readTree("""
                {
                  "structureVersion": 5,
                  "candidateCells": [
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"A1","value":"产品名称："},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"B1","empty":true,"style":{"bd":{"b":{"s":1}}}},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"A5","value":"原料名称"},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"B5","value":"批次"},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"C5","value":"用量"},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"D5","value":"单位"},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"A6","empty":true,"style":{"bd":{"b":{"s":1}}}},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"B6","empty":true,"style":{"bd":{"b":{"s":1}}}},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"C6","empty":true,"style":{"bd":{"b":{"s":1}}}},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"D6","empty":true,"style":{"bd":{"b":{"s":1}}}}
                  ],
                  "regions": []
                }
                """);

        var result = engine.recognize(TemplateFormat.XLSX, "生产单.xlsx", structure);

        assertThat(result.suggestions())
                .anySatisfy(suggestion -> {
                    assertThat(suggestion.suggestionType()).isEqualTo("SCALAR_FIELD");
                    assertThat(suggestion.payload().path("fieldName").asText()).isEqualTo("产品名称");
                    assertThat(suggestion.payload().path("locator").path("address").asText()).isEqualTo("B1");
                })
                .anySatisfy(suggestion -> {
                    assertThat(suggestion.suggestionType()).isEqualTo("ROW_TABLE");
                    assertThat(suggestion.payload().path("columns")).hasSize(4);
                    assertThat(suggestion.payload().path("groupName").asText()).isEqualTo("原料信息");
                });
    }

    @Test
    void infersRequiredAndDateTimeFromValidationAndInputNumberFormat() throws Exception {
        var structure = objectMapper.readTree("""
                {
                  "structureVersion":5,
                  "candidateCells":[
                    {"sheetId":"sheet-1","sheetName":"测试","address":"A1","row":1,"column":1,"value":"检测日期"},
                    {"sheetId":"sheet-1","sheetName":"测试","address":"B1","row":1,"column":2,"empty":true,
                     "style":{"n":{"pattern":"yyyy-mm-dd hh:mm"},"bd":{"b":{"s":1}}}}
                  ],
                  "dataValidations":[
                    {"sheetId":"sheet-1","address":"B1","allowBlank":false}
                  ],
                  "regions":[]
                }
                """);

        var result = engine.recognize(TemplateFormat.XLSX, "测试.xlsx", structure);

        assertThat(result.suggestions()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.payload().path("valueType").asText()).isEqualTo("datetime");
            assertThat(suggestion.payload().path("required").asBoolean()).isTrue();
        });
    }
}
