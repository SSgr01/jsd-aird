package com.jsd.aird.recognition.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
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
        var job = service.process(UUID.fromString(payload.path("organizationId").asText()),
                UUID.fromString(payload.path("recognitionJobId").asText()),
                payload.path("sourceOwner").asText());
        return json.createObjectNode().put("recognitionJobId", job.id().toString())
                .put("status", job.status()).put("recognitionRevision", job.recognitionRevision());
    }

    @Override public boolean isRetryable(Exception exception) {
        return !(exception instanceof com.jsd.aird.shared.error.ApiException);
    }

    @Override public void handleTerminalFailure(JsonNode payload, Exception exception) {
        // process() already mirrors parsing failure into the domain row.
    }
}
