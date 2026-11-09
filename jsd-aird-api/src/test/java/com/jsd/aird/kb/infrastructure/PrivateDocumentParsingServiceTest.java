package com.jsd.aird.kb.infrastructure;

import com.jsd.aird.kb.application.PrivateDocumentParsingService;
import com.jsd.aird.kb.application.SystemParsingPolicy;
import com.jsd.aird.kb.application.StructuredDocumentCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.infrastructure.BasicFileSafetyScanner;
import com.jsd.aird.kb.infrastructure.PlainTextDocumentParser;
import com.jsd.aird.shared.error.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrivateDocumentParsingServiceTest {
    private PrivateDocumentParsingService parser() {
        var mapper = new ObjectMapper();
        return new PrivateDocumentParsingService(List.of(new PlainTextDocumentParser()), new BasicFileSafetyScanner(),
            new SystemParsingPolicy("AUTO", false), new StructuredDocumentCodec(mapper), mapper);
    }

    @Test void reusesExistingMarkdownParserAndPreservesOriginalUnitsAndParagraphs() {
        var bytes = "# Synthetic\n\nModulus: 12 kPa.\n\nTemperature: 25 °C.".getBytes(StandardCharsets.UTF_8);
        var result = parser().parse(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "synthetic.md", "text/markdown",
            bytes.length, new ByteArrayInputStream(bytes));
        assertThat(result.path("parser_version").asText()).isEqualTo("text-markdown-gfm-v2");
        assertThat(result.path("nodes").size()).isEqualTo(3);
        assertThat(result.path("nodes").get(1).path("text").asText()).isEqualTo("Modulus: 12 kPa.");
        assertThat(result.path("nodes").get(1).path("anchor").has("content")).isFalse();
    }

    @Test void refusesMislabeledPdfAndUnsafeContentThroughTheExistingScanner() {
        var bytes = "This is not a PDF".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser().parse(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "fake.pdf", "application/pdf",
            bytes.length, new ByteArrayInputStream(bytes))).isInstanceOf(ApiException.class);
    }
}
