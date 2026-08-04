package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void versionSixDoesNotGuessBusinessFieldsFromKeywordsOrExistingValues() throws Exception {
        var structure = objectMapper.readTree("""
                {
                  "structureVersion":6,
                  "semanticCells":[
                    {"sheetId":"sheet-1","address":"A1","value":"UV树脂"},
                    {"sheetId":"sheet-1","address":"B1","value":"M-687 NT"},
                    {"sheetId":"sheet-1","address":"A2","value":"原料名称"},
                    {"sheetId":"sheet-1","address":"B2","value":"UA-306"}
                  ]
                }
                """);

        var result = engine.recognize(TemplateFormat.XLSX, "生产单.xlsx", structure);

        assertThat(result.suggestions()).isEmpty();
        assertThat(result.model()).isEqualTo("conservative-label-value-v6");
    }

    @Test
    void createsOnlyALowConfidenceCandidateForAnExplicitColonLabelAndAdjacentValue() throws Exception {
        var structure = objectMapper.readTree("""
                {
                  "structureVersion":6,
                  "semanticCells":[
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"A2","row":2,"column":1,"value":"产品名称：","factType":"VALUE"},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"B2","row":2,"column":2,"value":"M-687 NT","factType":"VALUE"},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"A3","row":3,"column":1,"value":"UV树脂","factType":"VALUE"},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"B3","row":3,"column":2,"value":"UA-306","factType":"VALUE"}
                  ]
                }
                """);

        var result = engine.recognize(TemplateFormat.XLSX, "生产单.xlsx", structure);

        assertThat(result.suggestions()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.confidence()).isEqualTo(0.58);
            assertThat(suggestion.payload().path("fieldName").asText()).isEqualTo("产品名称");
            assertThat(suggestion.payload().path("locator").path("address").asText()).isEqualTo("B2");
        });
    }

    @Test
    void rejectsLegacyPhysicalStructures() throws Exception {
        var legacy = objectMapper.readTree("{\"structureVersion\":5}");
        assertThatThrownBy(() -> engine.recognize(TemplateFormat.XLSX, "旧模板.xlsx", legacy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须为 6");
    }
}
