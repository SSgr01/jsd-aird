package com.jsd.aird.ai.rnd.training;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TrainingContracts {
    private TrainingContracts() { }

    public record TrainingSettings(boolean autoLearningEnabled, Instant lastEvaluatedAt,
                                   long revision, Instant updatedAt) { }
    public record UpdateTrainingSettings(boolean autoLearningEnabled, long expectedRevision) { }
    public record EvaluateResult(int targetsChecked, int jobsCreated, int blocked,
                                 List<TargetEvaluation> targets) { }
    public record TargetEvaluation(UUID targetId, String targetName, String result, String code,
                                   UUID trainingJobId, UUID snapshotId) { }
    public record TrainingJobSummary(UUID id, UUID targetId, String targetName, String category,
                                     String valueType, String status, String stage, int progress,
                                     int attemptCount, int maxAttempts, UUID snapshotId,
                                     UUID candidateModelId, String errorCode, String errorMessage,
                                     Instant createdAt, Instant startedAt, Instant finishedAt, long revision) { }
    public record TrainingJobDetail(TrainingJobSummary job, SnapshotSummary snapshot,
                                    List<AttemptView> attempts, JsonNode policy,
                                    JsonNode frozenConfiguration) { }
    public record AttemptView(UUID id, int attemptNo, String status, String stage, int progress,
                              String workerId, Instant startedAt, Instant heartbeatAt,
                              Instant finishedAt, String errorCode, String errorMessage,
                              JsonNode artifacts) { }
    public record SnapshotSummary(UUID id, UUID targetId, UUID targetVersionId, UUID inputSchemeId,
                                  UUID eligibilityRunId, int sampleCount, String dataNature,
                                  String snapshotHash, String businessFingerprint,
                                  Instant frozenAt) { }
    public record SnapshotDetail(SnapshotSummary summary, JsonNode targetDefinition,
                                 JsonNode inputScheme, JsonNode materialDictionary,
                                 JsonNode sourceMappings, JsonNode preprocessing,
                                 JsonNode trainingPolicy, JsonNode authorizationScope,
                                 JsonNode validationGroups, JsonNode manifest) { }
    public record SnapshotItem(UUID sampleRevisionId, UUID trainingSampleId, long ordinal,
                               String splitGroup, String rowHash, JsonNode row,
                               List<String> observationIds, List<String> replicateGroupKeys,
                               JsonNode sourceReferences, JsonNode validationGroups) { }
    public record ModelSummary(UUID id, UUID targetId, String targetName, String category,
                               String valueType, int version, String status, String modelType,
                               String dataNature, boolean productionEligible, String comparisonStatus,
                               JsonNode metrics, int trainableSamples, UUID trainingJobId,
                               Instant createdAt, Instant updatedAt, long revision) { }
    public record ModelDetail(ModelSummary model, SnapshotSummary snapshot, JsonNode applicabilityDomain,
                              JsonNode modelCard, JsonNode rejectionReasons, List<ReleaseView> releases) { }
    public record ModelComparison(UUID candidateId, UUID activeModelId, String status,
                                  boolean sameComparisonSet, JsonNode candidateMetrics,
                                  JsonNode activeMetrics, JsonNode baselineMetrics,
                                  List<String> reasons) { }
    public record ReleaseView(UUID id, String action, UUID modelVersionId, UUID previousModelVersionId,
                              String reason, Instant createdAt) { }
    public record ModelActionCommand(long expectedRevision, UUID expectedActiveModelVersionId,
                                     String reason) { }
    public record ModelActionResult(UUID modelVersionId, String status, UUID activeModelVersionId,
                                    long revision, Instant changedAt) { }
    public record RetryCommand(long expectedRevision) { }
    public record CancelCommand(long expectedRevision, String reason) { }
    public record JobActionResult(UUID jobId, String status, UUID asyncJobId, int attemptCount,
                                  long revision) { }
}
