package com.jsd.aird.spc.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SpectrumAnswerStreamGateTest {

    @Test
    void extractsOnlyACompleteEscapedAnswerMarkdownString() {
        var extractor = new SpectrumChatService.FirstJsonStringField(new ObjectMapper(), "answerMarkdown");

        assertThat(extractor.append("{\"answerMark")).isFalse();
        assertThat(extractor.append("down\": \"第一句含有\\\"引号\\\"，")).isFalse();
        assertThat(extractor.append("第二句。\",\"observations\":[")).isTrue();
        assertThat(extractor.value()).isEqualTo("第一句含有\"引号\"，第二句。");
    }
}
