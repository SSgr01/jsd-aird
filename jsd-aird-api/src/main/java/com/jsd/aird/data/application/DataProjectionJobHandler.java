package com.jsd.aird.data.application;

import java.util.ArrayList;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import org.springframework.stereotype.Component;

@Component
public class DataProjectionJobHandler implements AsyncJobHandler {

    private final DataProjectionService service;
    private final OpsAsyncFacade opsAsync;
    private final ObjectMapper objectMapper;

    public DataProjectionJobHandler(DataProjectionService service, OpsAsyncFacade opsAsync, ObjectMapper objectMapper) {
        this.service = service;
        this.opsAsync = opsAsync;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String jobType) {
        return "DATA_PROJECT_IMPORT".equals(jobType);
    }

    @Override
    public JsonNode handle(JsonNode payload) {
        var organizationId = UUID.fromString(payload.path("organizationId").asText());
        var actorId = payload.hasNonNull("actorId")
                ? UUID.fromString(payload.path("actorId").asText()) : null;
        var importJobId = UUID.fromString(payload.path("importJobId").asText());
        var templateVersionId = UUID.fromString(payload.path("templateVersionId").asText());
        var revisions = new ArrayList<UUID>();
        payload.path("assets").forEach(item -> {
            if (item.hasNonNull("revisionId")) revisions.add(UUID.fromString(item.path("revisionId").asText()));
        });
        service.projectInternal(organizationId, actorId, importJobId, templateVersionId, revisions);
        var assetIds = new ArrayList<UUID>();
        payload.path("assets").forEach(item -> {
            if (item.hasNonNull("assetId")) assetIds.add(UUID.fromString(item.path("assetId").asText()));
        });
        // Projection owns the publication ordering. Indexing is queued exactly
        // once after data_record/data_value are committed, never before.
        if (!assetIds.isEmpty()) {
            var revisionKey = revisions.stream().map(UUID::toString).sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            var digest = sha256(revisionKey);
            var hash = java.util.HexFormat.of().formatHex(digest);
            opsAsync.enqueue(organizationId, "AI_INDEX_DATA_ASSETS", payload.deepCopy(),
                    "ai-data-index-after-projection:" + importJobId + ":" + hash, 30);
        }
        return objectMapper.createObjectNode().put("status", "PROJECTED");
    }

    private byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }
}
