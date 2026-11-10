package com.jsd.aird.rnd.application;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateFormat;
import org.springframework.stereotype.Component;

/** 报价记录文件的后台解析任务；HTTP 请求只负责暂存文件并创建任务。 */
@Component
public class QuotationImportJobHandler implements AsyncJobHandler {
    private final QuotationImportService service;
    private final ObjectMapper objectMapper;

    public QuotationImportJobHandler(QuotationImportService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String jobType) {
        return "QUOTATION_IMPORT_PARSE".equals(jobType);
    }

    @Override
    public JsonNode handle(JsonNode payload, ExecutionContext context) {
        return com.jsd.aird.ops.application.port.AsyncExecutionScope.run(context, () -> handle(payload));
    }

    @Override
    public JsonNode handle(JsonNode payload) {
        var actor = new Actor(
                uuid(payload, "organizationId"),
                uuid(payload, "actorId"),
                payload.path("actorName").asText("developer"),
                payload.path("actorRole").asText("ADMIN")
        );
        var command = payload.path("command").isObject()
                ? objectMapper.convertValue(payload.path("command"), QuotationImportService.Command.class)
                : new QuotationImportService.Command(uuid(payload, "fileId"),
                payload.path("fileName").asText(), payload.path("sha256").asText(),
                TemplateFormat.valueOf(payload.path("format").asText()),
                null, null, null, null, null, null, null, "ALL");
        ActorContext.set(actor);
        try {
            var quotationId = service.processQueued(uuid(payload, "jobId"), command, actor,
                    payload.path("asyncIdempotencyKey").asText(null));
            if (quotationId == null) return objectMapper.createObjectNode().put("cancelled", true);
            return objectMapper.createObjectNode().put("quotationId", quotationId.toString());
        } finally {
            ActorContext.clear();
        }
    }

    @Override
    public boolean isRetryable(Exception exception) {
        return !(exception instanceof ApiException apiException)
                || apiException.errorCode() == ApiErrorCode.FILE_NOT_READY;
    }

    @Override
    public void handleTerminalFailure(JsonNode payload, Exception exception, ExecutionContext context) {
        com.jsd.aird.ops.application.port.AsyncExecutionScope.run(context, () -> {
            handleTerminalFailure(payload, exception);
            return null;
        });
    }

    @Override
    public void handleTerminalFailure(JsonNode payload, Exception exception) {
        var jobId = payload.path("jobId").asText();
        if (!jobId.isBlank()) service.failQueued(UUID.fromString(jobId), exception.getMessage());
    }

    private UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asText());
    }
}
