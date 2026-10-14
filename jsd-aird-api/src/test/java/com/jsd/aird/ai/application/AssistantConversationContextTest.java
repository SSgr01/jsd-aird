package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.application.port.AssistantRepository;
import org.junit.jupiter.api.Test;

class AssistantConversationContextTest {

    @Test
    void includesRecentAssistantResponseAndCitationNamesForFollowUpResolution() throws Exception {
        var citations = new ObjectMapper().readTree("""
                [{"title":"NASA 燃料材料规范","originalName":"NASA-Spec-1009.pdf","sourceType":"KNOWLEDGE"}]
                """);
        var history = List.of(
                new AssistantRepository.MessageRow(UUID.randomUUID(), "USER", "NASA 燃料材料的要求是什么？", null),
                new AssistantRepository.MessageRow(UUID.randomUUID(), "ASSISTANT", "来源说明了相关材料要求。", citations));

        var context = AssistantConversationContext.format(history);

        assertThat(context).contains("助手：来源说明了相关材料要求")
                .contains("NASA 燃料材料规范")
                .contains("NASA-Spec-1009.pdf")
                .contains("必须重新检索并校验当前授权");
    }

    @Test
    void boundsConversationContext() {
        var history = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> new AssistantRepository.MessageRow(UUID.randomUUID(), "USER", "x".repeat(3_000), null))
                .toList();

        assertThat(AssistantConversationContext.format(history)).hasSizeLessThanOrEqualTo(7_000);
    }
}
