package com.jsd.aird.kb.application;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeIndexJobHandler implements AsyncJobHandler {

    private final KnowledgeService service;
    private final ObjectMapper objectMapper;

    public KnowledgeIndexJobHandler(KnowledgeService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String jobType) {
        return "KB_BUILD_KNOWLEDGE_INDEX".equals(jobType)
                || "KB_BUILD_KNOWLEDGE_VECTOR".equals(jobType);
    }

    @Override
    public JsonNode handle(JsonNode payload) {
        var organizationId = uuid(payload, "organizationId");
        var documentId = uuid(payload, "documentId");
        var parseRunId = uuid(payload, "parseRunId");
        if (payload.hasNonNull("publicationId")) {
            service.buildVectors(organizationId, documentId, uuid(payload, "publicationId"), parseRunId);
            return objectMapper.createObjectNode().put("status", "VECTOR_READY");
        }
        service.buildAndPublish(organizationId, uuid(payload, "actorId"), documentId,
                uuid(payload, "versionId"), parseRunId, payload.path("reviewRevision").asInt());
        return objectMapper.createObjectNode().put("status", "PUBLISHED");
    }

    @Override
    public void handleTerminalFailure(JsonNode payload, Exception exception) {
        if (payload.hasNonNull("publicationId")) {
            service.failVector(uuid(payload, "organizationId"), uuid(payload, "documentId"),
                    uuid(payload, "publicationId"), uuid(payload, "parseRunId"), exception);
        } else if (payload.hasNonNull("versionId")) {
            service.failIndex(uuid(payload, "organizationId"), uuid(payload, "documentId"),
                    uuid(payload, "versionId"), uuid(payload, "parseRunId"), exception);
        }
    }

    private UUID uuid(JsonNode payload, String name) {
        return UUID.fromString(payload.path(name).asText());
    }
}
