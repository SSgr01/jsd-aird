package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ResearchRunJobHandler implements AsyncJobHandler {
    private final FormulaResearchService service;

    public ResearchRunJobHandler(FormulaResearchService service) { this.service = service; }

    @Override public boolean supports(String jobType) { return "RESEARCH_RUN".equals(jobType); }

    @Override
    public JsonNode handle(JsonNode payload) {
        var organizationId = uuid(payload, "organizationId");
        var actor = new Actor(organizationId, uuid(payload, "actorId"), payload.path("actorName").asText(),
                payload.path("actorRole").asText("ADMIN"));
        var previous = ActorContext.current();
        ActorContext.set(actor);
        try { return service.execute(organizationId, uuid(payload, "runId")); }
        finally {
            if (previous == null) ActorContext.clear(); else ActorContext.set(previous);
        }
    }

    @Override public boolean isRetryable(Exception exception) { return !(exception instanceof ApiException); }

    @Override
    public void handleTerminalFailure(JsonNode payload, Exception exception) {
        service.fail(uuid(payload, "organizationId"), uuid(payload, "runId"), exception);
    }

    private UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asText());
    }
}
