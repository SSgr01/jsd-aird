package com.jsd.aird.tpl.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Component;

@Component
public class TemplateImportJobHandler implements AsyncJobHandler {

    private final TemplateImportService service;

    public TemplateImportJobHandler(TemplateImportService service) {
        this.service = service;
    }

    @Override
    public boolean supports(String jobType) {
        return "XLSX_PARSE".equals(jobType) || "DOCX_PARSE".equals(jobType)
                || "XLSX_SNAPSHOT_RECOGNIZE".equals(jobType);
    }

    @Override
    public JsonNode handle(JsonNode payload) {
        if ("UNIVER_SNAPSHOT".equals(payload.path("sourceKind").asText())) {
            return service.processSnapshot(
                    java.util.UUID.fromString(payload.path("importJobId").asText()),
                    java.util.UUID.fromString(payload.path("organizationId").asText()),
                    java.util.UUID.fromString(payload.path("fileId").asText()),
                    payload.path("scope").asText("WORKBOOK"),
                    payload.path("sheetId").asText(null),
                    payload.path("address").asText(null),
                    payload.path("snapshotFragment"),
                    payload.path("parentRunId").asText("").isBlank()
                            ? null : java.util.UUID.fromString(payload.path("parentRunId").asText()),
                    payload.path("runReason").asText("INITIAL_RECOGNITION")
            );
        }
        return service.process(
                java.util.UUID.fromString(payload.path("importJobId").asText()),
                java.util.UUID.fromString(payload.path("organizationId").asText()),
                java.util.UUID.fromString(payload.path("fileId").asText()),
                TemplateFormat.valueOf(payload.path("format").asText()),
                payload.path("parentRunId").asText("").isBlank()
                        ? null : java.util.UUID.fromString(payload.path("parentRunId").asText()),
                payload.path("runReason").asText("INITIAL_RECOGNITION")
        );
    }
}
