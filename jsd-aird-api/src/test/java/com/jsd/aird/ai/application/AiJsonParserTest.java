package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiJsonParserTest {

    private final AiJsonParser parser = new AiJsonParser(new ObjectMapper());

    @Test
    void parsesJsonWrappedInMarkdownAndPreamble() {
        var answer = parser.read("以下是结构化结果：\n```json\n{\"answer\":\"结论\",\"warnings\":[]}" 
                + "\n```", Answer.class);

        assertThat(answer).isNotNull();
        assertThat(answer.answer()).isEqualTo("结论");
    }

    @Test
    void returnsNullForEmptyOrNonObjectModelOutput() {
        assertThat(parser.read("", Answer.class)).isNull();
        assertThat(parser.read("[]", Answer.class)).isNull();
    }

    private record Answer(String answer, Object warnings) {
    }
}
