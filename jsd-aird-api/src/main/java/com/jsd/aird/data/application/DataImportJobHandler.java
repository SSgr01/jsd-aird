package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import org.springframework.stereotype.Component;

@Component
public class DataImportJobHandler implements AsyncJobHandler {

    private final DataImportService service;
    private final ObjectMapper objectMapper;

    public DataImportJobHandler(DataImportService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String jobType) {
        return "DATA_IMPORT_PARSE".equals(jobType);
    }

    @Override
    public JsonNode handle(JsonNode payload) {
        service.parseInternal(java.util.UUID.fromString(payload.path("organizationId").asText()),
                java.util.UUID.fromString(payload.path("importJobId").asText()));
        return objectMapper.createObjectNode().put("status", "PARSED");
    }
}
