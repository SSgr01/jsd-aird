package com.jsd.aird.data.application;

import java.util.ArrayList;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.api.DataAssetSearchFacade;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import org.springframework.stereotype.Component;

@Component
public class DataProjectionJobHandler implements AsyncJobHandler {

    private final DataProjectionService service;
    private final DataAssetSearchFacade assets;
    private final ObjectMapper objectMapper;

    public DataProjectionJobHandler(DataProjectionService service, DataAssetSearchFacade assets, ObjectMapper objectMapper) {
        this.service = service;
        this.assets = assets;
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
        if (!assetIds.isEmpty()) assets.indexPublished(organizationId, assetIds);
        return objectMapper.createObjectNode().put("status", "PROJECTED");
    }
}
