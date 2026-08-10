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

    List<TemplateCandidate> listPublishedTemplates(UUID organizationId);

    void insert(NewProductionOrder order);

    Optional<ProductionWorkspace> findWorkspace(UUID organizationId, UUID orderId);

    List<RevisionSummary> listRevisions(UUID organizationId, UUID orderId);

    Optional<RecordRevision> findRevision(UUID organizationId, UUID orderId, UUID revisionId);

    Optional<FileReference> findFile(UUID organizationId, UUID fileId);

    int updateDraft(DraftUpdate update);

    UUID submit(SubmitRevision revision);

    void insertRevisionProjection(List<CollectionProjection> collections, List<ValueProjection> values);

    void attachConfirmedIngestSources(UUID organizationId, UUID orderId, UUID revisionId, UUID actorId);

    int cancel(UUID organizationId, UUID orderId);

    int delete(UUID organizationId, UUID orderId);

    void appendOutbox(String aggregateType, UUID aggregateId, String eventType, JsonNode payload);

    record PublishedTemplate(
            UUID versionId,
            String format,
            JsonNode schema,
            JsonNode mapping,
            JsonNode wordDocument,
            JsonNode inlineSnapshot,
            UUID snapshotFileId,
            String snapshotHash,
            String snapshotKind,
            String editorAppVersion,
            String pluginManifestHash,
            int snapshotFormatVersion
    ) {
    }

    record TemplateCandidate(UUID versionId, String templateCode, String name) {
    }

    record RevisionSummary(UUID revisionId, int revisionNo, String status, Instant createdAt, String dataHash) {}

    record RecordRevision(
            UUID revisionId,
            UUID orderId,
            int revisionNo,
            String status,
            JsonNode schema,
            JsonNode mapping,
            JsonNode data,
            UUID snapshotFileId,
            String snapshotHash,
            String schemaHash,
            String mappingHash,
            String dataHash
    ) {}

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
            UUID templateVersionId,
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

    record CollectionProjection(
            UUID id,
            UUID revisionId,
            UUID productionOrderId,
            String recordKind,
            String parentFieldCode,
            String parentDataPath,
            String recordKey,
            int recordIndex,
            String memberKey,
            JsonNode data
    ) {
    }

    record ValueProjection(
            UUID id,
            UUID revisionId,
            UUID productionOrderId,
            UUID collectionItemId,
            String fieldCode,
            String dataPath,
            String valueType,
            JsonNode value
    ) {
    }
}
