package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ai.rnd.training.FormulaModelV2Client;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ModelingWorkbenchJobHandler implements AsyncJobHandler {
    private final ModelingWorkbenchService service;

    public ModelingWorkbenchJobHandler(ModelingWorkbenchService service) { this.service = service; }

    @Override public boolean supports(String jobType) { return "AI_MODELING_WORKBENCH_TRAIN".equals(jobType); }

    @Override public JsonNode handle(JsonNode payload) { throw new IllegalStateException("建模任务需要租约执行上下文"); }

    @Override
    public JsonNode handle(JsonNode payload, ExecutionContext context) {
        if (context.cancelled()) throw new ApiException(com.jsd.aird.shared.error.ApiErrorCode.STALE_TRAINING_ATTEMPT, "训练任务已取消");
        return service.execute(UUID.fromString(payload.path("organizationId").asText()),
                UUID.fromString(payload.path("runId").asText()));
    }

    @Override public boolean isRetryable(Exception exception) {
        return exception instanceof FormulaModelV2Client.ComputeTransportException
                || exception instanceof ApiException api && (api.errorCode() == com.jsd.aird.shared.error.ApiErrorCode.FILE_NOT_READY
                || api.errorCode() == com.jsd.aird.shared.error.ApiErrorCode.TRAINING_TIMEOUT
                || api.errorCode() == com.jsd.aird.shared.error.ApiErrorCode.COMPUTE_UNAVAILABLE);
    }

    @Override
    public void handleTerminalFailure(JsonNode payload, Exception exception) {
        service.fail(UUID.fromString(payload.path("organizationId").asText()),
                UUID.fromString(payload.path("runId").asText()), exception);
    }
}
