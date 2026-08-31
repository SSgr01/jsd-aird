package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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

    @Test
    void repairsLatexBackslashesAndTrailingCommasInModelJson() {
        var raw = """
                { "answer": "电荷密度分别是 $7.4 ~ \\mu \\mathrm { C } ~ \\mathrm { m } ^ { - 2}$、$-0.6 \\mu C m^{-2}$。",
                  "answerStatus": "ANSWERED",
                  "usedEvidenceRefs": ["K3"],
                }
                """;

        var answer = parser.read(raw, StructuredAnswer.class);

        assertThat(answer).isNotNull();
        assertThat(answer.answer()).contains("\\mu", "\\mathrm");
        assertThat(answer.answerStatus()).isEqualTo("ANSWERED");
        assertThat(answer.usedEvidenceRefs()).containsExactly("K3");
    }

    @Test
    void decodesMalformedJsonStringContentWithoutDroppingLatexCommands() {
        assertThat(parser.decodeStringContent("$7.4 \\mu \\mathrm { C }$"))
                .isEqualTo("$7.4 \\mu \\mathrm { C }$");
    }

    private record Answer(String answer, Object warnings) {
    }

    private record StructuredAnswer(String answer, String answerStatus, List<String> usedEvidenceRefs) {
    }
}
