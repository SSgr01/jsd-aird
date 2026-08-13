package com.jsd.aird.kb.application;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.kb.domain.MediaExtractionException;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeIngestionJobHandler implements AsyncJobHandler {

    private final KnowledgeService service;
    private final ObjectMapper objectMapper;

    public KnowledgeIngestionJobHandler(KnowledgeService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String jobType) {
        return "KB_INGEST_DOCUMENT".equals(jobType);
    }

    @Override
    public JsonNode handle(JsonNode payload) {
        var actorId = payload.path("actorId").asText("");
        service.ingest(UUID.fromString(payload.path("organizationId").asText()),
                actorId.isBlank() ? null : UUID.fromString(actorId),
                UUID.fromString(payload.path("documentId").asText()),
                UUID.fromString(payload.path("versionId").asText()),
                UUID.fromString(payload.path("fileId").asText()));
        return objectMapper.createObjectNode().put("status", "READY");
    }

    @Override
    public boolean isRetryable(Exception exception) {
        // OCR/ASR providers already perform the contracted three attempts and persist their task/error record.
        return !(exception instanceof MediaExtractionException);
    }
}
