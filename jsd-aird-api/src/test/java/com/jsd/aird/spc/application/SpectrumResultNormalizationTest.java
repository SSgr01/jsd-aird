package com.jsd.aird.spc.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.spc.infrastructure.SpectrumPromptProvider;
import org.junit.jupiter.api.Test;

class SpectrumResultNormalizationTest {

    @Test
    void normalizesV3ContainerWithoutChangingModelClaims() throws Exception {
        var objectMapper = new ObjectMapper();
        var source = (ObjectNode) objectMapper.readTree("""
                {"schemaVersion":3,"confidence":{"overallConfidence":"MEDIUM"},
                 "claims":[{"claimId":"C1","claimType":"OBSERVATION","statement":"主峰约295 nm"}],
                 "limitations":[],"assessment":{"type":"OVERVIEW"}}
                """);
        var original = source.deepCopy();
        var service = new SpectrumChatService(null, null, objectMapper, null, null,
                new SpectrumPromptProvider(objectMapper), null, null, "gpt-5.6-sol");
        var method = SpectrumChatService.class.getDeclaredMethod("normalizeResult", JsonNode.class);
        method.setAccessible(true);

        var normalized = (ObjectNode) method.invoke(service, source);

        assertThat(normalized.path("schemaVersion").asInt()).isEqualTo(3);
        assertThat(normalized.path("confidence").asText()).isEqualTo("MEDIUM");
        assertThat(normalized.path("claims").path(0).path("statement").asText()).isEqualTo("主峰约295 nm");
        assertThat(source).isEqualTo(original);
    }

    @Test
    void preservesNaturalMarkdownAsTheCompleteV4Result() throws Exception {
        var objectMapper = new ObjectMapper();
        var report = """
                ## 图上观察

                两条曲线的主峰位置接近，旧样峰高略高。这里的读数是图上估读。

                | 特征 | 旧样 | 新样 |
                |---|---:|---:|
                | 主峰 | 2.4 Abs | 2.2 Abs |

                ### 结论边界

                单张图片不能确认差异是否可重复。
                """;
        var source = objectMapper.createObjectNode()
                .put("schemaVersion", 4)
                .put("analysisStatus", "SUCCEEDED")
                .put("answerMarkdown", report);
        var service = new SpectrumChatService(null, null, objectMapper, null, null,
                new SpectrumPromptProvider(objectMapper), null, null, "gpt-5.6-sol");
        var method = SpectrumChatService.class.getDeclaredMethod("normalizeResult", JsonNode.class);
        method.setAccessible(true);

        var normalized = (ObjectNode) method.invoke(service, source);

        assertThat(normalized.path("schemaVersion").asInt()).isEqualTo(4);
        assertThat(normalized.path("analysisStatus").asText()).isEqualTo("SUCCEEDED");
        assertThat(normalized.path("answerMarkdown").asText()).isEqualTo(report);
        assertThat(normalized.has("claims")).isFalse();
    }
}
