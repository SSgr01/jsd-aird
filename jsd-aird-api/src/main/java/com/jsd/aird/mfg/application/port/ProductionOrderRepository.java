package com.jsd.aird.mfg.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public interface ProductionOrderRepository {

    List<ProductionOrderListItem> list(UUID organizationId);

    Optional<PublishedTemplate> findPublishedTemplate(UUID organizationId, UUID versionId);

    void insert(NewProductionOrder order);

    Optional<ProductionWorkspace> findWorkspace(UUID organizationId, UUID orderId);

    Optional<FileReference> findFile(UUID organizationId, UUID fileId);

    int updateDraft(DraftUpdate update);

    UUID submit(SubmitRevision revision);

    int cancel(UUID organizationId, UUID orderId);

    void appendOutbox(String aggregateType, UUID aggregateId, String eventType, JsonNode payload);

    record PublishedTemplate(
            UUID versionId,
            String format,
            JsonNode schema,
            JsonNode mapping,
            UUID snapshotFileId,
            String snapshotHash,
            String snapshotKind,
            String editorAppVersion,
            String pluginManifestHash,
            int snapshotFormatVersion
    ) {
    }

    record NewProductionOrder(
            UUID id,
            UUID organizationId,
            String orderNo,
            UUID templateVersionId,
            UUID productId,
            BigDecimal quantity,
            String unitCode,
            LocalDate plannedDate,
            UUID ownerId,
            JsonNode schema,
            JsonNode mapping,
            JsonNode data,
            UUID snapshotFileId,
            String snapshotHash,
            String snapshotKind,
            String editorAppVersion,
            String pluginManifestHash,
            int snapshotFormatVersion,
            String schemaHash,
            String mappingHash,
            String dataHash,
            String workspaceHash,
            UUID actorId
    ) {
    }

    record ProductionWorkspace(
            UUID id,
            String orderNo,
            String status,
            UUID templateVersionId,
            String templateName,
            String templateCode,
            String format,
            UUID productId,
            BigDecimal quantity,
            String unitCode,
            LocalDate plannedDate,
            UUID ownerId,
            JsonNode schema,
            JsonNode mapping,
            JsonNode data,
            UUID snapshotFileId,
            String snapshotHash,
            String snapshotKind,
            String editorAppVersion,
            String pluginManifestHash,
            int snapshotFormatVersion,
            String schemaHash,
            String mappingHash,
            String dataHash,
            String workspaceHash,
            long lockVersion,
            boolean reconciliationRequired
    ) {
    }

    record ProductionOrderListItem(
            UUID id,
            String orderNo,
            String status,
            UUID templateVersionId,
            String templateName,
            String templateCode,
            String format,
            BigDecimal quantity,
            String unitCode,
            LocalDate plannedDate,
            Instant updatedAt
    ) {
    }

    record FileReference(UUID id, String status, String sha256) {
    }

    record DraftUpdate(
            UUID organizationId,
            UUID orderId,
            long expectedLockVersion,
            JsonNode schema,
            JsonNode mapping,
            JsonNode data,
            UUID snapshotFileId,
            String snapshotHash,
            String editorAppVersion,
            String pluginManifestHash,
            int snapshotFormatVersion,
            String schemaHash,
            String mappingHash,
            String dataHash,
            String workspaceHash
    ) {
    }

    record SubmitRevision(
            UUID id,
            UUID organizationId,
            UUID orderId,
            JsonNode coreSnapshot,
            JsonNode schema,
            JsonNode mapping,
            JsonNode data,
            UUID snapshotFileId,
            String snapshotHash,
            String schemaHash,
            String mappingHash,
            String dataHash,
            String workspaceHash,
            UUID actorId
    ) {
    }
}
