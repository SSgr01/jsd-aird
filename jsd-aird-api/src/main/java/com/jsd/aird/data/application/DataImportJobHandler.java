package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.dao.DataIntegrityViolationException;
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
        var previous = ActorContext.current();
        var organizationId = java.util.UUID.fromString(payload.path("organizationId").asText());
        var actorId = payload.path("actorId").asText("");
        if (!actorId.isBlank()) ActorContext.set(new Actor(organizationId,
                java.util.UUID.fromString(actorId), "import-worker"));
        try {
            service.parseInternal(organizationId,
                    java.util.UUID.fromString(payload.path("importJobId").asText()));
        } finally {
            if (previous == null) ActorContext.clear();
            else ActorContext.set(previous);
        }
        return objectMapper.createObjectNode().put("status", "PARSED");
    }

    @Override
    public boolean isRetryable(Exception exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof DataIntegrityViolationException) return false;
            if (current instanceof IllegalArgumentException) return false;
            var message = current.getMessage();
            if (message != null && (message.contains("duplicate key") || message.contains("重复键")
                    || message.contains("unique constraint") || message.contains("唯一约束"))) return false;
        }
        return true;
    }

    @Override
    public void handleTerminalFailure(JsonNode payload, Exception exception) {
        service.markParseFailed(java.util.UUID.fromString(payload.path("organizationId").asText()),
                java.util.UUID.fromString(payload.path("importJobId").asText()), exception);
    }
}
