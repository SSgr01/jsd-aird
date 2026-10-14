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
                "high", "high", 12000, Duration.ofSeconds(10), Duration.ofMinutes(10));
        var request = new SpectrumVisionClient.VisionRequest("分析这张图", List.of(
                new SpectrumVisionClient.VisionImage(null, "UV", 1, "图谱A，第1页",
                        "data:image/png;base64,AAAA")), "OVERVIEW");

        var body = client.requestBody(request);

        assertThat(body.path("model").asText()).isEqualTo("gpt-5.6-sol");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("high");
        assertThat(body.path("text").path("verbosity").asText()).isEqualTo("high");
        assertThat(body.path("reasoning").has("summary")).isFalse();
        assertThat(body.path("instructions").asText()).contains("简体中文", "Markdown 标题、表格或列表")
                .doesNotContain("只输出一个 JSON");
        assertThat(body.path("max_output_tokens").asInt()).isEqualTo(12000);
        var content = body.path("input").path(0).path("content");
        assertThat(content.path(0).path("type").asText()).isEqualTo("input_text");
        assertThat(content.path(1).path("type").asText()).isEqualTo("input_text");
        assertThat(content.path(1).path("text").asText()).contains("图谱A");
        assertThat(content.path(2).path("type").asText()).isEqualTo("input_image");
        assertThat(content.path(2).path("detail").asText()).isEqualTo("high");
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("top_p")).isFalse();
        assertThat(body.has("tools")).isFalse();
    }

    @Test
    void wrapsTheModelMarkdownAsThePublicAnalysisWithoutAClaimsEnvelope() {
        var client = new OpenAiCompatibleSpectrumVisionClient(objectMapper,
                "https://example.test/v1", "secret", "gpt-6.1-sol", "/responses",
                "high", "high", 12000, Duration.ofSeconds(10), Duration.ofMinutes(10));

        var result = client.parseOutput("# 分析结论\n\n新旧曲线的主峰位置接近。\n\n| 曲线 | 峰高 |\n|---|---:|\n| 旧样 | 2.4 Abs |\n| 新样 | 2.2 Abs |");

        assertThat(result.path("schemaVersion").asInt()).isEqualTo(4);
        assertThat(result.path("analysisStatus").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("answerMarkdown").asText()).contains("# 分析结论", "| 旧样 | 2.4 Abs |");
        assertThat(result.has("claims")).isFalse();
    }

    @Test
    void removesAnOuterMarkdownFenceWithoutRequiringJson() {
        var client = new OpenAiCompatibleSpectrumVisionClient(objectMapper,
                "https://example.test/v1", "secret", "gpt-6.1-sol", "/responses",
                "high", "high", 12000, Duration.ofSeconds(10), Duration.ofMinutes(10));

        var result = client.parseOutput("```markdown\n## 结果\n可以确认峰位接近。\n```");

        assertThat(result.path("answerMarkdown").asText()).isEqualTo("## 结果\n可以确认峰位接近。");
    }
}
