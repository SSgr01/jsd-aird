package com.jsd.aird.spc.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.spc.application.port.SpectrumVisionClient;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleSpectrumVisionClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsDedicatedHighReasoningResponsesRequestWithHighDetailImages() {
        var client = new OpenAiCompatibleSpectrumVisionClient(objectMapper,
                "https://example.test/v1", "secret", "gpt-5.6-sol", "/responses",
                "high", 12000, Duration.ofSeconds(10), Duration.ofMinutes(10));
        var request = new SpectrumVisionClient.VisionRequest("分析这张图", List.of(
                new SpectrumVisionClient.VisionImage(null, "UV", 1, "data:image/png;base64,AAAA")), null);

        var body = client.requestBody(request);

        assertThat(body.path("model").asText()).isEqualTo("gpt-5.6-sol");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("high");
        assertThat(body.path("reasoning").has("summary")).isFalse();
        assertThat(body.path("instructions").asText()).contains("简体中文");
        assertThat(body.path("max_output_tokens").asInt()).isEqualTo(12000);
        var content = body.path("input").path(0).path("content");
        assertThat(content.path(0).path("type").asText()).isEqualTo("input_text");
        assertThat(content.path(1).path("type").asText()).isEqualTo("input_image");
        assertThat(content.path(1).path("detail").asText()).isEqualTo("high");
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("top_p")).isFalse();
        assertThat(body.has("tools")).isFalse();
    }
}
