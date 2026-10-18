package com.jsd.aird.recognition.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.recognition.application.port.SourceRecognitionRepository;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceRecognitionJobHandlerTest {

    @AfterEach
    void clearActorContext() {
        ActorContext.clear();
    }

    @Test
    void restoresOriginalActorAroundWorkerParse() {
        var organizationId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        var service = mock(SourceRecognitionService.class);
        var result = new SourceRecognitionRepository.Job(jobId, organizationId, UUID.randomUUID(),
                "test.pdf", "hash", "PDF", SourceRecognitionService.DATA_CENTER, "FREEFORM",
                null, null, "DATA_ONLY", null, "ALL", "WAITING_MAPPING", 100,
                "WAITING_REVIEW", "source-recognition-v1", null, null, 1, null, 0,
                null, null, Instant.now(), Instant.now());
        when(service.process(eq(organizationId), eq(jobId), eq(SourceRecognitionService.DATA_CENTER)))
                .thenAnswer(invocation -> {
                    assertEquals(new Actor(organizationId, actorId, "source-recognition-worker"),
                            ActorContext.required());
                    return result;
                });

        var payload = new ObjectMapper().createObjectNode()
                .put("organizationId", organizationId.toString())
                .put("recognitionJobId", jobId.toString())
                .put("sourceOwner", SourceRecognitionService.DATA_CENTER)
                .put("actorId", actorId.toString());

        var response = new SourceRecognitionJobHandler(service, new ObjectMapper()).handle(payload);

        assertEquals("WAITING_MAPPING", response.path("status").asText());
        assertNull(ActorContext.current());
    }
}
