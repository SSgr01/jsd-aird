package com.jsd.aird.tpl.application.port;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Captures the optional visual representation of an imported Excel workbook.
 * Implementations must return an unavailable result instead of failing recognition.
 */
public interface TemplateVisualRenderer {

    RenderResult render(UUID importJobId);

    record RenderResult(
            String status,
            String sourceUrl,
            String objectKey,
            String dataUri,
            long size,
            int width,
            int height,
            String detail
    ) {
        public RenderResult {
            status = status == null ? "UNAVAILABLE" : status;
            sourceUrl = sourceUrl == null ? "" : sourceUrl;
            objectKey = objectKey == null ? "" : objectKey;
            dataUri = dataUri == null ? "" : dataUri;
            detail = detail == null ? "" : detail;
        }

        public static RenderResult unavailable(String status, String detail) {
            return new RenderResult(status, "", "", "", 0, 0, 0, detail);
        }

        public boolean rendered() {
            return "RENDERED".equals(status) && !dataUri.isBlank();
        }

        public JsonNode auditNode(ObjectMapper objectMapper) {
            return objectMapper.createObjectNode()
                    .put("status", status)
                    .put("sourceUrl", sourceUrl)
                    .put("objectKey", objectKey)
                    .put("size", size)
                    .put("width", width)
                    .put("height", height)
                    .put("detail", detail);
        }

        public JsonNode modelNode(ObjectMapper objectMapper) {
            return objectMapper.createObjectNode()
                    .put("status", status)
                    .put("dataUri", dataUri)
                    .put("width", width)
                    .put("height", height);
        }
    }
}
