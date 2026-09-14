package com.jsd.aird.spc.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.spc.infrastructure.SpectrumPromptProvider;
import org.junit.jupiter.api.Test;

class SpectrumResultNormalizationTest {

    @Test
    void normalizesObjectConfidenceAndValidationExperimentWithoutChangingObservedFacts() throws Exception {
        var objectMapper = new ObjectMapper();
        var source = (ObjectNode) objectMapper.readTree("""
                {
                  "answerMarkdown":"可见约 295 nm 峰。",
                  "confidence":{"overallConfidence":"MEDIUM","reason":"图像读数为近似值"},
                  "observations":[{"description":"主峰约 295 nm","peakNm":295,"absorbance":2.2}],
                  "suggestedValidationExperiments":{"experiment":"同条件平行复测","purpose":"确认差异"}
                }
                """);
        var original = source.deepCopy();
        var service = new SpectrumChatService(null, null, objectMapper, null, null,
                new SpectrumPromptProvider(objectMapper), null, null, "gpt-5.6-sol");
        var method = SpectrumChatService.class.getDeclaredMethod("normalizeResult", JsonNode.class);
        method.setAccessible(true);

        var normalized = (ObjectNode) method.invoke(service, source);

        assertThat(normalized.path("confidence").asText()).isEqualTo("MEDIUM");
        assertThat(normalized.path("suggestedValidationExperiments")).hasSize(1);
        assertThat(normalized.path("suggestedValidationExperiments").path(0).path("experiment").asText())
                .isEqualTo("同条件平行复测");
        assertThat(normalized.path("observations").path(0).path("peakNm").asInt()).isEqualTo(295);
        assertThat(normalized.path("observations").path(0).path("absorbance").asDouble()).isEqualTo(2.2);
        assertThat(source).isEqualTo(original);
    }
}
