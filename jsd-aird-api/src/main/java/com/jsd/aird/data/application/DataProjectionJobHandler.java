package com.jsd.aird.data.application;

import java.util.ArrayList;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import org.springframework.stereotype.Component;

@Component
public class DataProjectionJobHandler implements AsyncJobHandler {

    private final DataProjectionService service;
    private final ObjectMapper objectMapper;

    public DataProjectionJobHandler(DataProjectionService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String jobType) {
        return "DATA_PROJECT_IMPORT".equals(jobType);
    }

    @Override
    public JsonNode handle(JsonNode payload) {
        var organizationId = UUID.fromString(payload.path("organizationId").asText());
        var actorId = payload.hasNonNull("actorId")
                ? UUID.fromString(payload.path("actorId").asText()) : null;
        var importJobId = UUID.fromString(payload.path("importJobId").asText());
        var templateVersionId = UUID.fromString(payload.path("templateVersionId").asText());
        var records = new ArrayList<UUID>();
        payload.path("records").forEach(item -> {
            if (item.hasNonNull("recordId")) records.add(UUID.fromString(item.path("recordId").asText()));
        });
        service.projectInternal(organizationId, actorId, importJobId, templateVersionId, records);
        return objectMapper.createObjectNode().put("status", "PROJECTED");
    }
}
