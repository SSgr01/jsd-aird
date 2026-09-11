package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/** Background parser for experiment uploads; HTTP requests only enqueue this job. */
@Component
public class ExperimentImportJobHandler implements AsyncJobHandler {
    private final ExperimentImportService service;
    private final ObjectMapper objectMapper;

    public ExperimentImportJobHandler(ExperimentImportService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String jobType) {
        return "EXPERIMENT_IMPORT_PARSE".equals(jobType);
    }

    @Override
    public JsonNode handle(JsonNode payload) {
        var actor = new Actor(
                uuid(payload, "organizationId"),
                uuid(payload, "actorId"),
                payload.path("actorName").asText("developer"),
                payload.path("actorRole").asText("ADMIN")
        );
        var command = new ExperimentImportService.Command(
                uuid(payload, "fileId"),
                payload.path("fileName").asText(),
                payload.path("sha256").asText(),
                TemplateFormat.valueOf(payload.path("format").asText()),
                optionalUuid(payload, "categoryId"),
                payload.path("categoryName").asText(null),
                optionalUuid(payload, "projectId"),
                optionalUuid(payload, "stageId"),
                optionalUuid(payload, "taskId"),
                LocalDate.parse(payload.path("experimentDate").asText(LocalDate.now().toString())),
                payload.path("visibility").asText("ALL")
        );
        ActorContext.set(actor);
        try {
            var summary = service.processQueued(
                    uuid(payload, "jobId"), command, actor,
                    payload.path("asyncIdempotencyKey").asText(null));
            if (summary == null) return objectMapper.createObjectNode().put("cancelled", true);
            return objectMapper.createObjectNode().put("experimentId", summary.id().toString());
        } finally {
            ActorContext.clear();
        }
    }

    @Override
    public boolean isRetryable(Exception exception) {
        // A staged object can be briefly unavailable immediately after the
        // upload PUT. Let the persistent worker retry that transient condition
        // instead of failing the import on the first read attempt; validation
        // and parsing errors remain terminal and are surfaced to the user.
        return !(exception instanceof ApiException apiException)
                || apiException.errorCode() == ApiErrorCode.FILE_NOT_READY;
    }

    @Override
    public void handleTerminalFailure(JsonNode payload, Exception exception) {
        var jobId = payload.path("jobId").asText();
        if (!jobId.isBlank()) service.failQueued(UUID.fromString(jobId), exception.getMessage());
    }

    private UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asText());
    }

    private UUID optionalUuid(JsonNode payload, String field) {
        return payload.hasNonNull(field) && !payload.path(field).asText().isBlank()
                ? UUID.fromString(payload.path(field).asText()) : null;
    }
}
