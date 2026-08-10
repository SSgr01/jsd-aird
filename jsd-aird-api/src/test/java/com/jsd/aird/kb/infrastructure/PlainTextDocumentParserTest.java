package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PlainTextDocumentParserTest {

    private final PlainTextDocumentParser parser = new PlainTextDocumentParser();

    @Test
    void parsesParagraphsAndSupportsMissingContentType() {
        var content = "第一段\n第二行\n\n第二段";

        assertThat(parser.supports("研发记录.txt", null)).isTrue();
        var result = parser.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), "研发记录.txt");

        assertThat(result.parserVersion()).isEqualTo("text-v1");
        assertThat(result.blocks()).extracting(value -> value.content())
                .containsExactly("第一段\n第二行", "第二段");
    }
}
