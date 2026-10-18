package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.Test;

class VideoOriginalDocumentParserTest {

    private final VideoOriginalDocumentParser parser = new VideoOriginalDocumentParser();

    @Test
    void acceptsSupportedVideoOriginalsWithoutCreatingFakeTranscriptText() {
        assertThat(parser.supports("inspection.mp4", "video/mp4")).isTrue();
        assertThat(parser.supports("inspection.webm", "video/webm")).isTrue();

        var parsed = parser.parse(new ByteArrayInputStream(new byte[0]), "inspection.mp4");

        assertThat(parsed.blocks()).isEmpty();
        assertThat(parsed.metadata()).containsEntry("mediaType", "VIDEO")
                .containsEntry("textExtraction", "NOT_REQUESTED");
    }
}
