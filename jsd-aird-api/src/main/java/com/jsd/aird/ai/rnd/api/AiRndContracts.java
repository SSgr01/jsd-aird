package com.jsd.aird.ai.rnd.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Frozen transport types for {@code /api/v1/ai/rnd}; R01 adds no controllers. */
public final class AiRndContracts {
    public static final String CONTRACT_VERSION = "ai-rnd.v1";
    private AiRndContracts() { }

    public enum ValueType { CONTINUOUS, ORDINAL, BINARY, CATEGORICAL }
    public enum ResourceStatus { DRAFT, ACTIVE, PAUSED, RETIRED }
    public enum VersionStatus { DRAFT, PUBLISHED, FROZEN, RETIRED }
    public enum EligibilityState { TRAINABLE, EXCLUDED, REVIEW_REQUIRED }
    public enum JobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public enum ModelStatus { CANDIDATE, ACTIVE, PAUSED, RETIRED, FAILED }
    public enum DomainStatus { IN_DOMAIN, NEAR_BOUNDARY, OUT_OF_DOMAIN }
    public enum ResultStatus { SUCCEEDED, BLOCKED, FAILED }
    public enum RunStatus { QUEUED, RUNNING, SUCCEEDED, PARTIAL, BLOCKED, FAILED, CANCELLED }

    public record ErrorDetail(String fieldCode, UUID targetId, List<String> missingFields, JsonNode evidence) { }
    public record ErrorResponse(String code, String message, String requestId, ErrorDetail detail) { }
    public record Page<T>(List<T> items, long total, int page, int size) { }
    public record VersionCommand(long expectedRevision) { }
    public record ReasonedVersionCommand(long expectedRevision, String reason) { }

    public record TargetDraft(String code, String name, String category, ValueType valueType, JsonNode definitionDraft) { }
    public record TargetSummary(UUID id, String code, String name, String category, ValueType valueType,
                                ResourceStatus status, UUID currentVersionId, UUID currentInputSchemeId,
                                UUID activeModelVersionId, long revision, Instant updatedAt) { }
    public record TargetVersion(UUID id, UUID targetId, int version, VersionStatus status, ValueType valueType, String unit,
                                List<String> classes, JsonNode definition, JsonNode observationSemantics,
                                String configHash, Instant publishedAt) { }
    public record TargetVersionCommand(JsonNode definition, JsonNode observationSemantics, String unit,
                                       List<String> classes, long expectedRevision) { }

    public record InputFieldDraft(String code, String name, String valueType, String unit, String availabilityStage,
                                  UUID standardFieldDictionaryId, JsonNode definition, JsonNode preprocessing) { }
    public record InputFieldSummary(UUID id, String code, String name, String valueType,
                                    String unit, String availabilityStage, ResourceStatus status, UUID currentVersionId,
                                    long revision) { }
    public record InputFieldVersion(UUID id, UUID inputFieldId, int version, VersionStatus status,
                                    String valueType, String unit, String availabilityStage,
                                    UUID standardFieldDictionaryId, JsonNode definition, JsonNode preprocessing, String configHash) { }
    public record SourceMappingVersion(UUID id, UUID targetId, UUID targetVersionId, String sourceType, int version,
                                       VersionStatus status, JsonNode mapping, String mappingHash) { }
    public record InputSchemeField(UUID inputFieldVersionId, boolean required, int ordinal, JsonNode override) { }
    public record InputSchemeDraft(UUID targetVersionId, UUID materialDictionaryVersionId,
                                   String code, String name, List<InputSchemeField> fields,
                                   JsonNode preprocessing, long expectedRevision) { }
    public record InputScheme(UUID id, UUID targetId, UUID targetVersionId, UUID materialDictionaryVersionId,
                              String code, String name, int version,
                              VersionStatus status, List<InputSchemeField> fields, String configHash,
                              long revision, Instant frozenAt) { }
    public record CoveragePreview(String evaluationStatus, String unavailableReason,
                                  List<JsonNode> validationIssues, String schemaHash,
                                  Long candidateSamples, Long trainable, Long excluded, Long reviewRequired,
                                  List<UUID> changedSampleIds) { }

    public record MaterialAlias(UUID id, UUID materialId, String alias, String normalizedAlias,
                                String status, long revision, Instant updatedAt) { }
    public record MaterialDictionaryItem(UUID materialId, String role, int ordinal, String token) { }
    public record MaterialDictionary(UUID id, String code, int version, VersionStatus status,
                                     List<MaterialDictionaryItem> items, JsonNode encoder,
                                     String dictionaryHash, long revision, Instant frozenAt) { }

    public record QualificationReason(String code, String message, String fieldCode, JsonNode evidence) { }
    public record Eligibility(UUID id, UUID sampleRevisionId, UUID targetVersionId, UUID inputSchemeId,
                              EligibilityState state, List<QualificationReason> reasons,
                              List<QualificationReason> warnings, long revision, Instant evaluatedAt) { }
    public record EligibilitySummary(long total, long trainable, long excluded, long reviewRequired,
                                     Map<String, Long> reasonCounts) { }
    public record ReviewDecisionCommand(String decision, String reason, UUID materialId, long expectedRevision) { }
    public record ReviewDecision(UUID reviewId, String decision, String reason, UUID recomputeJobId,
                                 long revision, Instant decidedAt) { }

    public record TrainingAttempt(UUID id, int attemptNo, String status, String workerId,
                                  String errorCode, String errorMessage, Instant startedAt, Instant finishedAt) { }
    public record TrainingJob(UUID id, UUID targetId, UUID snapshotId, JobStatus status, String currentStage,
                              int attemptCount, int maxAttempts, List<TrainingAttempt> attempts,
                              String errorCode, String errorMessage, long revision, Instant updatedAt) { }
    public record Metric(String name, BigDecimal value, BigDecimal threshold, boolean passed) { }
    public record ModelVersion(UUID id, UUID targetId, UUID targetVersionId, UUID inputSchemeId,
                               UUID snapshotId, int version, ModelStatus status, String modelType,
                               List<Metric> metrics, JsonNode applicabilityDomain, String modelHash,
                               long revision, Instant createdAt) { }
    public record ModelComparison(UUID targetId, List<ModelVersion> models, JsonNode sampleAndGroupComparison) { }
    public record PublishCommand(long expectedRevision, UUID expectedActiveModelVersionId, String reason) { }
    public record ActiveModel(UUID targetId, UUID activeModelVersionId, long targetRevision, Instant changedAt) { }

    public record FormulaComponent(UUID materialId, BigDecimal ratio, String unit, boolean amountKnown) { }
    public record Formula(String basis, boolean compositionComplete, List<FormulaComponent> components,
                          BigDecimal recordedTotal) { }
    public record PredictionTarget(UUID targetId, UUID expectedModelVersionId) { }
    public record PredictionRequest(Formula formula, Map<String, JsonNode> inputs, List<PredictionTarget> targets) { }
    public record Warning(String code, String message, JsonNode detail) { }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "resultType")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ContinuousPrediction.class, name = "CONTINUOUS"),
            @JsonSubTypes.Type(value = OrdinalPrediction.class, name = "ORDINAL"),
            @JsonSubTypes.Type(value = BinaryPrediction.class, name = "BINARY"),
            @JsonSubTypes.Type(value = CategoricalPrediction.class, name = "CATEGORICAL")
    })
    public sealed interface TypedPrediction permits ContinuousPrediction, OrdinalPrediction, BinaryPrediction, CategoricalPrediction { }
    public record Interval(BigDecimal lower, BigDecimal upper, BigDecimal coverageLevel) { }
    public record ContinuousPrediction(BigDecimal value, String unit, Interval interval) implements TypedPrediction { }
    public record OrdinalPrediction(String label, Map<String, BigDecimal> probabilities,
                                    BigDecimal thresholdProbability) implements TypedPrediction { }
    public record BinaryPrediction(String label, String positiveClass, BigDecimal probability,
                                   BigDecimal decisionThreshold) implements TypedPrediction { }
    public record CategoricalPrediction(String label, Map<String, BigDecimal> probabilities) implements TypedPrediction { }
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "status", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TargetSuccess.class, name = "SUCCEEDED"),
            @JsonSubTypes.Type(value = TargetBlocked.class, name = "BLOCKED"),
            @JsonSubTypes.Type(value = TargetFailed.class, name = "FAILED")
    })
    public sealed interface TargetResult permits TargetSuccess, TargetBlocked, TargetFailed { }
    public record TargetSuccess(UUID targetId, ResultStatus status, TypedPrediction prediction,
                                UUID modelVersionId, UUID targetVersionId, UUID inputSchemeId, UUID snapshotId,
                                DomainStatus applicability, List<Warning> warnings) implements TargetResult { }
    public record TargetBlocked(UUID targetId, ResultStatus status, String errorCode, String message,
                                ErrorDetail detail) implements TargetResult { }
    public record TargetFailed(UUID targetId, ResultStatus status, String errorCode, String message,
                               ErrorDetail detail) implements TargetResult { }
    public record PredictionResponse(String requestId, UUID predictionId, String executionStatus, String outcomeStatus,
                                     List<TargetResult> results) { }

    public record TypedGoal(UUID targetId, UUID expectedModelVersionId, String operator, JsonNode typedValue,
                            boolean mandatory, BigDecimal weight, BigDecimal minimumProbability) { }
    public record SearchSpace(List<UUID> allowedMaterialIds, List<UUID> variableMaterialIds,
                              Map<UUID, JsonNode> bounds, Map<String, JsonNode> variableInputs) { }
    public record FormulaDesignRequest(List<TypedGoal> typedGoals, JsonNode constraints,
                                       Map<String, JsonNode> fixedInputs, SearchSpace searchSpace, int candidateCount) { }
    public record BaselineRef(String type, UUID id, UUID versionId) { }
    public record OptimizationRequest(BaselineRef baselineRef, List<TypedGoal> typedGoals,
                                      Map<String, JsonNode> fixedInputs, SearchSpace searchSpace, JsonNode constraints) { }
    public record RunAccepted(UUID runId, RunStatus status, Map<UUID, UUID> modelBindings) { }
    public record Candidate(UUID id, int candidateNo, String title, Formula formula,
                            Map<UUID, TypedPrediction> targetResults, JsonNode ruleCheck,
                            JsonNode applicability, BigDecimal score, List<Warning> warnings) { }
    public record ResearchRun(UUID id, String runType, RunStatus status, List<Candidate> candidates,
                              Map<UUID, UUID> modelBindings, ErrorResponse error, Instant updatedAt) { }
    public record ExperimentDraftCommand(List<UUID> candidateIds, UUID categoryId, JsonNode plan) { }
    public record ExperimentDraftLink(UUID candidateId, UUID experimentId, UUID experimentVersionId,
                                      String experimentNo, boolean existing) { }
}
