package com.jsd.aird.data.application;

import java.util.ArrayList;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.api.DataAssetSearchFacade;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import org.springframework.stereotype.Component;

@Component
public class DataAssetIndexJobHandler implements AsyncJobHandler {

    private final DataAssetSearchFacade assets;
    private final ObjectMapper objectMapper;

    public DataAssetIndexJobHandler(DataAssetSearchFacade assets, ObjectMapper objectMapper) {
        this.assets = assets;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String jobType) {
        return "AI_INDEX_DATA_ASSETS".equals(jobType);
    }

    @Override
    public JsonNode handle(JsonNode payload) {
        var organizationId = UUID.fromString(payload.path("organizationId").asText());
        var ids = new ArrayList<UUID>();
        payload.path("assets").forEach(item -> {
            if (item.hasNonNull("assetId")) ids.add(UUID.fromString(item.path("assetId").asText()));
        });
        var count = assets.indexPublished(organizationId, ids);
        return objectMapper.createObjectNode().put("status", "INDEXED").put("entryCount", count);
    }
}
