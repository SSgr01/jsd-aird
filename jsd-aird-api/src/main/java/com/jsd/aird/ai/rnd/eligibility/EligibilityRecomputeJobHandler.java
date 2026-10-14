package com.jsd.aird.ai.rnd.eligibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@Component
public class EligibilityRecomputeJobHandler implements AsyncJobHandler {
    private static final Logger log = LoggerFactory.getLogger(EligibilityRecomputeJobHandler.class);
    private final EligibilityService service;
    private final ObjectMapper json;

    public EligibilityRecomputeJobHandler(EligibilityService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @Override public boolean supports(String jobType) { return EligibilityService.JOB_TYPE.equals(jobType); }

    @Override public JsonNode handle(JsonNode payload) {
        var organizationId = UUID.fromString(payload.path("organizationId").asText(
                "00000000-0000-0000-0000-000000000001"));
        var runId = UUID.fromString(payload.path("runId").asText());
        try {
            return service.executeRun(organizationId, runId);
        } catch (RuntimeException exception) {
            log.error("eligibility worker execution failed: organizationId={}, runId={}, message={}",
                    organizationId, runId, exception.getMessage(), exception);
            throw exception;
        }
    }

    @Override public void handleTerminalFailure(JsonNode payload, Exception exception) {
        var organization = payload.path("organizationId").asText(null);
        var run = payload.path("runId").asText(null);
        if (organization != null && run != null) service.failRun(UUID.fromString(organization), UUID.fromString(run), exception);
    }
}
