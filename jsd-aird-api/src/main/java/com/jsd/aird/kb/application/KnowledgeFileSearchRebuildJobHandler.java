package com.jsd.aird.kb.application;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeFileSearchRebuildJobHandler implements AsyncJobHandler {

    private final KnowledgeRepository repository;
    private final ObjectMapper objectMapper;

    public KnowledgeFileSearchRebuildJobHandler(KnowledgeRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String jobType) {
        return "KB_REBUILD_FILE_SEARCH".equals(jobType);
    }

    @Override
    public JsonNode handle(JsonNode payload) {
        var organizationId = UUID.fromString(payload.path("organizationId").asText());
        repository.rebuildTermStats(organizationId);
        return objectMapper.createObjectNode().put("status", "REBUILT")
                .put("organizationId", organizationId.toString());
    }
}
