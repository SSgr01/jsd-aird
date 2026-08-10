package com.jsd.aird.mfg.ingest.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public interface InstanceIngestRepository {

    void insert(NewJob job);

    Optional<Job> find(UUID organizationId, UUID orderId, UUID jobId);

    void markProcessing(UUID jobId);

    void saveResult(UUID jobId, UUID selectedTemplateVersionId, String matchMode,
                    double score, JsonNode result, List<NewItem> items);

    void markFailed(UUID jobId, String message);

    int confirm(UUID organizationId, UUID orderId, UUID jobId, int resultVersion, JsonNode confirmedData);

    int cancel(UUID organizationId, UUID orderId, UUID jobId);

    record NewJob(
            UUID id,
            UUID organizationId,
            UUID productionOrderId,
            UUID requestedTemplateVersionId,
            String sourceType,
            List<UUID> sourceFileIds,
            UUID actorId
    ) {
    }

    record Job(
            UUID id,
            UUID organizationId,
            UUID productionOrderId,
            UUID requestedTemplateVersionId,
            UUID selectedTemplateVersionId,
            String sourceType,
            String matchMode,
            String status,
            Double templateMatchScore,
            int resultVersion,
            JsonNode result,
            String errorMessage,
            List<UUID> sourceFileIds,
            List<Item> items,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    record NewItem(
            UUID id,
            String itemKey,
            String itemKind,
            String bindingId,
            String fieldCode,
            String dataPath,
            String recordKey,
            Integer recordIndex,
            JsonNode rawValue,
            JsonNode normalizedValue,
            JsonNode sourceLocator,
            double confidence,
            String reviewStatus
    ) {
    }

    record Item(
            UUID id,
            String itemKey,
            String itemKind,
            String bindingId,
            String fieldCode,
            String dataPath,
            String recordKey,
            Integer recordIndex,
            JsonNode rawValue,
            JsonNode normalizedValue,
            JsonNode userValue,
            JsonNode sourceLocator,
            double confidence,
            String reviewStatus
    ) {
    }
}
