package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.infrastructure.OfficeDocumentParseException;
import org.junit.jupiter.api.Test;

class KnowledgeIngestionJobHandlerTest {

    private final KnowledgeIngestionJobHandler handler = new KnowledgeIngestionJobHandler(
            mock(KnowledgeService.class), new ObjectMapper());

    @Test
    void doesNotRetryDeterministicOfficeFormatFailures() {
        assertThat(handler.isRetryable(new OfficeDocumentParseException("broken.doc", new IllegalArgumentException())))
                .isFalse();
    }

    @Test
    void stillRetriesUnexpectedTransientFailures() {
        assertThat(handler.isRetryable(new IllegalStateException("database temporarily unavailable")))
                .isTrue();
    }
}
