package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class FormulaModelBuildJobHandler implements AsyncJobHandler {
    private final FormulaModelManagementService service;

    public FormulaModelBuildJobHandler(FormulaModelManagementService service) {
        this.service = service;
    }

    @Override public boolean supports(String jobType) { return FormulaModelManagementService.BUILD_JOB.equals(jobType); }

    @Override
    public JsonNode handle(JsonNode payload) {
        var organizationId = uuid(payload, "organizationId");
        var actor = new Actor(organizationId, uuid(payload, "actorId"), payload.path("actorName").asText(),
                payload.path("actorRole").asText("ADMIN"));
        var previous = ActorContext.current();
        ActorContext.set(actor);
        try {
            return service.execute(organizationId, uuid(payload, "snapshotId"), uuid(payload, "modelVersionId"),
                    uuid(payload, "taskProfileId"), optionalUuid(payload, "projectId"),
                    optionalUuid(payload, "categoryId"), payload.path("seed").asLong(2026));
        } finally {
            if (previous == null) ActorContext.clear(); else ActorContext.set(previous);
        }
    }

    @Override
    public boolean isRetryable(Exception exception) {
        if (!(exception instanceof ApiException api)) return true;
        return api.errorCode() == ApiErrorCode.AI_PROVIDER_UNAVAILABLE
                || api.errorCode() == ApiErrorCode.AI_MODEL_TIMEOUT
                || api.errorCode() == ApiErrorCode.FILE_NOT_READY;
    }

    @Override
    public void handleTerminalFailure(JsonNode payload, Exception exception) {
        service.fail(uuid(payload, "organizationId"), uuid(payload, "snapshotId"),
                uuid(payload, "modelVersionId"), exception);
    }

    private UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asText());
    }

    private UUID optionalUuid(JsonNode payload, String field) {
        var value = payload.path(field).asText("");
        return value.isBlank() ? null : UUID.fromString(value);
    }
}
