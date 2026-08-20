package com.jsd.aird.spc.application;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import org.springframework.stereotype.Component;

@Component
public class SpectrumAnalysisJobHandler implements AsyncJobHandler {

    private final SpectrumChatService service;
    private final ObjectMapper objectMapper;

    public SpectrumAnalysisJobHandler(SpectrumChatService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String jobType) {
        return "SPC_GENERATE_CHART_CHAT".equals(jobType);
    }

    @Override
    public JsonNode handle(JsonNode payload) {
        service.executeInternal(UUID.fromString(payload.path("organizationId").asText()),
                UUID.fromString(payload.path("analysisRunId").asText()));
        return objectMapper.createObjectNode().put("status", "ANALYZED");
    }

    @Override
    public boolean isRetryable(Exception exception) {
        return service.retryable(exception);
    }

    @Override
    public void handleTerminalFailure(JsonNode payload, Exception exception) {
        service.failInternal(UUID.fromString(payload.path("organizationId").asText()),
                UUID.fromString(payload.path("analysisRunId").asText()), exception);
    }
}
