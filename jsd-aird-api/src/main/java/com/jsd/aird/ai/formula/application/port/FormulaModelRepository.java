package com.jsd.aird.ai.formula.application.port;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FormulaModelRepository {

    TaskProfileRow ensureProfile(NewTaskProfile profile);

    void createBuild(NewSnapshot snapshot, NewModelVersion version);

    Optional<SnapshotRow> snapshot(UUID organizationId, UUID snapshotId);

    Optional<ModelVersionRow> modelVersion(UUID organizationId, UUID versionId);

    void completeSnapshot(UUID organizationId, UUID snapshotId, String snapshotHash,
                          String foldsHash, String baselineHash, int rowCount,
                          JsonNode artifacts, JsonNode targetSummary);

    void completeModel(UUID organizationId, UUID versionId, String bundleHash,
                       String bundleKey, JsonNode modelCard, JsonNode trainingResult,
                       List<NewModelTarget> targets);

    void failBuild(UUID organizationId, UUID snapshotId, UUID versionId,
                   String errorCode, String errorMessage);

    List<ModelTargetRow> modelTargets(UUID organizationId, UUID versionId);

    Optional<ActivationRow> activeTarget(UUID organizationId, UUID taskProfileId, String targetKey);

    ActivationRow activate(UUID organizationId, UUID taskProfileId, String targetKey,
                           UUID modelVersionId, UUID actorId, String reason);

    ActivationRow rollback(UUID organizationId, UUID taskProfileId, String targetKey,
                           UUID actorId, String reason);

    List<ActivationRow> activeTargets(UUID organizationId, UUID taskProfileId);

    List<MonitoringScope> monitoringScopes();

    List<FeedbackRow> recentModelFeedback(UUID organizationId, UUID modelVersionId,
                                          String targetKey, int limit);

    Optional<SnapshotRow> latestReadySnapshot(UUID organizationId, UUID taskProfileId);

    void recordModelCallSuccess(UUID organizationId, UUID activationId);

    void pauseModelForArtifactFailure(UUID organizationId, UUID activationId, String reason);

    void pauseModelForQualityReview(UUID organizationId, UUID activationId, String reason);

    record NewTaskProfile(UUID id, UUID organizationId, String code, String version, String status,
                          String contractVersion, String schemaHash, String contentHash,
                          JsonNode profile, UUID createdBy) {}

    record TaskProfileRow(UUID id, UUID organizationId, String code, String version, String status,
                          String contractVersion, String schemaHash, String contentHash,
                          JsonNode profile, Instant createdAt) {}

    record NewSnapshot(UUID id, UUID organizationId, UUID taskProfileId, String status,
                       String schemaVersion, String dataNature, String purpose,
                       String objectPrefix, UUID createdBy) {}

    record NewModelVersion(UUID id, UUID organizationId, UUID taskProfileId, UUID snapshotId,
                           String status, UUID createdBy) {}

    record SnapshotRow(UUID id, UUID organizationId, UUID taskProfileId, String status,
                       String schemaVersion, String dataNature, String purpose, String snapshotHash,
                       String validationFoldsHash, String t06BaselineHash, String objectPrefix,
                       JsonNode artifacts, JsonNode targetSummary, int rowCount,
                       String errorCode, String errorMessage, Instant createdAt, Instant completedAt) {}

    record ModelVersionRow(UUID id, UUID organizationId, UUID taskProfileId, UUID snapshotId,
                           String status, String modelBundleHash, String modelBundleKey,
                           JsonNode modelCard, JsonNode trainingResult, String errorCode,
                           String errorMessage, Instant createdAt, Instant completedAt) {}

    record NewModelTarget(String targetKey, String targetCode, String valueType, String status,
                          boolean productionEligible, String scorerType, String primaryMetricName,
                          BigDecimal primaryMetricValue, BigDecimal baselineMetricValue,
                          BigDecimal baselineImprovement, JsonNode reasons, JsonNode result) {}

    record ModelTargetRow(UUID modelVersionId, String targetKey, String targetCode, String valueType,
                          String status, boolean productionEligible, String scorerType,
                          String primaryMetricName, BigDecimal primaryMetricValue,
                          BigDecimal baselineMetricValue, BigDecimal baselineImprovement,
                          JsonNode reasons, JsonNode result) {}

    record ActivationRow(UUID id, UUID organizationId, UUID taskProfileId, String targetKey,
                         UUID modelVersionId, UUID previousModelVersionId, String status,
                         String reason, UUID activatedBy, Instant activatedAt, Instant endedAt,
                         int consecutiveFailureCount, Instant lastFailureAt) {}

    record MonitoringScope(UUID organizationId, UUID taskProfileId, UUID actorId) {}

    record FeedbackRow(UUID experimentId, JsonNode estimate, Instant linkedAt) {}
}
