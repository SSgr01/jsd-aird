package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OptimizationJobHandler implements AsyncJobHandler {
    private final OptimizationService service;
    public OptimizationJobHandler(OptimizationService service){this.service=service;}
    @Override public boolean supports(String jobType){return "AI_EXPERIMENT_OPTIMIZATION_V2".equals(jobType);}
    @Override public JsonNode handle(JsonNode payload){throw new IllegalStateException("实验优化需要租约执行上下文");}
    @Override public JsonNode handle(JsonNode payload,ExecutionContext context){if(context.cancelled())throw new ApiException(com.jsd.aird.shared.error.ApiErrorCode.STALE_RESEARCH_RESULT,"实验优化已取消");return service.execute(UUID.fromString(payload.path("organizationId").asText()),UUID.fromString(payload.path("runId").asText()));}
    @Override public boolean isRetryable(Exception e){return !(e instanceof ApiException);}
    @Override public void handleTerminalFailure(JsonNode payload,Exception e){service.fail(UUID.fromString(payload.path("organizationId").asText()),UUID.fromString(payload.path("runId").asText()),"COMPUTE_UNAVAILABLE",String.valueOf(e.getMessage()));}
}
