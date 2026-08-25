package com.jsd.aird.mfg.upload.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ProductionUploadRecognitionJobHandler implements AsyncJobHandler {
    private final ProductionUploadRecognitionService service;

    public ProductionUploadRecognitionJobHandler(ProductionUploadRecognitionService service) {
        this.service = service;
    }

    @Override public boolean supports(String jobType) {
        return "PRODUCTION_XLSX_INGEST".equals(jobType) || "PRODUCTION_PHOTO_INGEST".equals(jobType);
    }

    @Override public JsonNode handle(JsonNode payload) {
        var templateVersionId = payload.hasNonNull("templateVersionId")
                ? UUID.fromString(payload.path("templateVersionId").asText()) : null;
        return service.process(UUID.fromString(payload.path("organizationId").asText()),
                UUID.fromString(payload.path("uploadId").asText()),
                UUID.fromString(payload.path("fileId").asText()),
                payload.path("sourceType").asText("XLSX"), templateVersionId);
    }

    @Override public boolean isRetryable(Exception exception) {
        return !(exception instanceof IllegalArgumentException);
    }

    @Override public void handleTerminalFailure(JsonNode payload, Exception exception) {
        var id = payload.path("uploadId").asText();
        if (!id.isBlank()) service.fail(UUID.fromString(id), exception.getMessage());
    }
}
