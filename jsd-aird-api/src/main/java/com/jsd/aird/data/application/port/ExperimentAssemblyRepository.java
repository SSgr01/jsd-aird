package com.jsd.aird.data.application.port;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExperimentAssemblyRepository {
    SourceImport load(UUID organizationId, UUID importJobId);
    List<Link> links(UUID organizationId, UUID importJobId);
    void upsertPlan(UUID organizationId, UUID importJobId, PlanRow row, UUID actorId);
    void supersedeOtherPlans(UUID organizationId, UUID importJobId, String activeAssemblyKey);
    void lockAssembly(UUID organizationId, UUID importJobId, String assemblyKey);
    Optional<Link> find(UUID organizationId, UUID importJobId, String assemblyKey);
    boolean markRunning(UUID organizationId, UUID importJobId, String assemblyKey);
    void markSynced(UUID organizationId, UUID importJobId, String assemblyKey,
                    UUID experimentId, UUID experimentVersionId, String experimentNo);
    void markFailed(UUID organizationId, UUID importJobId, String assemblyKey, String error);

    record SourceImport(UUID id, UUID sourceFileId, String sourceFileName, String sourceSha256,
                        UUID templateVersionId, int importContractVersion, String contractHash,
                        String status, String importPurpose, UUID targetExperimentCategoryId,
                        UUID importedBy, Instant importedAt, List<SourceRecord> records) {}
    record SourceRecord(UUID id, String recordKey, String sheetId, String sheetName, Integer rowNumber,
                        JsonNode effectiveData, List<SourceAnchor> anchors) {}
    record SourceAnchor(String fieldCode, String bindingId, String valuePath, String labelPath,
                        String sheetId, String sheetName, Integer rowNumber, Integer columnNumber,
                        String columnName, String address, JsonNode rawValue) {}
    record PlanRow(String assemblyKey, String parentAssemblyKey, String sourceIdentity,
                   String sourceIdentityType, JsonNode sourceRecordKeys, JsonNode sharedContextRecordKeys,
                   String planHash, String contentHash, String status, JsonNode conflicts, JsonNode warnings) {}
    record Link(String assemblyKey, String parentAssemblyKey, String status, String resolutionAction,
                String resolutionReason, UUID experimentId, UUID experimentVersionId, String experimentNo,
                String planHash, String contentHash, String errorMessage) {}
}
