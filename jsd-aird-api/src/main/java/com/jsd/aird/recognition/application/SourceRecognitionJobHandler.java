package com.jsd.aird.recognition.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SourceRecognitionJobHandler implements AsyncJobHandler {
    private final SourceRecognitionService service;
    private final ObjectMapper json;

    public SourceRecognitionJobHandler(SourceRecognitionService service, ObjectMapper json) {
        this.service = service; this.json = json;
    }

    @Override public boolean supports(String jobType) { return "SOURCE_RECOGNITION_PARSE".equals(jobType); }

    @Override public JsonNode handle(JsonNode payload) {
        var previous = ActorContext.current();
        var organizationId = UUID.fromString(payload.path("organizationId").asText());
        var actorId = payload.path("actorId").asText("");
        if (!actorId.isBlank()) {
            ActorContext.set(new Actor(organizationId, UUID.fromString(actorId), "source-recognition-worker"));
        }
        try {
            var job = service.process(organizationId,
                    UUID.fromString(payload.path("recognitionJobId").asText()),
                    payload.path("sourceOwner").asText());
            return json.createObjectNode().put("recognitionJobId", job.id().toString())
                    .put("status", job.status()).put("recognitionRevision", job.recognitionRevision());
        } finally {
            if (previous == null) ActorContext.clear();
            else ActorContext.set(previous);
        }
    }

    @Override public boolean isRetryable(Exception exception) {
        return !(exception instanceof com.jsd.aird.shared.error.ApiException);
    }

    @Override public void handleTerminalFailure(JsonNode payload, Exception exception) {
        // process() already mirrors parsing failure into the domain row.
    }
}
