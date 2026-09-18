package com.jsd.aird.ai.formula.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/** Java mirror of the independent {@code formula-model.v2} Pydantic contract. */
public final class FormulaModelV2Contracts {
    public static final String CONTRACT_VERSION = "formula-model.v2";
    private FormulaModelV2Contracts() { }

    public enum ValueType { CONTINUOUS, ORDINAL, BINARY, CATEGORICAL }
    public enum DomainStatus { IN_DOMAIN, NEAR_BOUNDARY, OUT_OF_DOMAIN }

    public record VersionedRef(String id, int version, String sha256) { }
    public record ArtifactRef(String url, String sha256) { }
    public record TargetDefinition(String id, int version, String sha256, String code, ValueType valueType,
                                   String unit, List<String> classes, String positiveClass,
                                   Map<String, Object> observationSemantics) { }
    public record InputField(String fieldVersionId, String code, String valueType, boolean required,
                             String acquisitionTiming, Map<String, Object> encoding) { }
    public record InputScheme(String id, int version, String sha256, String targetVersionId,
                              List<InputField> fields) { }
    public record Material(String materialId, String code, String role, int encoderIndex) { }
    public record MaterialDictionary(String id, int version, String sha256, List<Material> materials) { }
    public record VersionedConfig(String id, int version, String sha256, Map<String, Object> config) { }
    public record FrozenContext(TargetDefinition targetDefinition, InputScheme inputScheme,
                                MaterialDictionary materialDictionary, VersionedConfig preprocessing,
                                VersionedConfig trainingPolicy) { }
    public record SnapshotRef(String id, int version, String sha256, ArtifactRef manifest,
                              ArtifactRef rows, ArtifactRef sourceMap) { }

    public record ValidateRequest(String contractVersion, String requestId, long seed,
                                  SnapshotRef snapshot, FrozenContext context) { }
    public record ValidationIssue(String code, String message, String rowId, String fieldCode) { }
    public record ValidateResponse(String contractVersion, String requestId, String status,
                                   String snapshotHash, List<ValidationIssue> issues) { }
    public record ValidationFoldsRequest(String contractVersion, String requestId, long seed,
                                         SnapshotRef snapshot, FrozenContext context, int foldCount,
                                         List<String> groupFields) { }
    public record FoldAssignment(String rowId, String groupKey, int fold) { }
    public record ValidationFoldsResponse(String contractVersion, String requestId, String snapshotHash,
                                          String assignmentsHash, List<FoldAssignment> assignments) { }
    public record TrainRequest(String contractVersion, String requestId, long seed, String jobId,
                               SnapshotRef snapshot, FrozenContext context, ArtifactRef validationFolds,
                               String output) { }
    public record TrainResponse(String contractVersion, String requestId, String jobId, String status,
                                ArtifactRef modelBundle, Map<String, Object> metrics,
                                Map<String, Object> applicabilityDomain, List<String> reasons) { }

    public record FormulaComponent(String materialId, Double ratio, String unit, boolean amountKnown) { }
    public record Formula(String basis, boolean compositionComplete, List<FormulaComponent> components,
                          Double recordedTotal) { }
    public record ModelBinding(TargetDefinition target, String modelVersionId, ArtifactRef modelBundle,
                               InputScheme inputScheme, MaterialDictionary materialDictionary,
                               VersionedConfig preprocessing, Map<String, Object> applicabilityDomain) { }
    public record ScoreRequest(String contractVersion, String requestId, long seed, String runId,
                               Formula formula, Map<String, Object> inputs, List<ModelBinding> modelBindings) { }
    public record Applicability(DomainStatus status, double distance, List<String> reasons) { }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "resultType")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ContinuousResult.class, name = "CONTINUOUS"),
            @JsonSubTypes.Type(value = OrdinalResult.class, name = "ORDINAL"),
            @JsonSubTypes.Type(value = BinaryResult.class, name = "BINARY"),
            @JsonSubTypes.Type(value = CategoricalResult.class, name = "CATEGORICAL")
    })
    public sealed interface TypedResult permits ContinuousResult, OrdinalResult, BinaryResult, CategoricalResult { }
    public record ContinuousResult(double value, String unit, double lower, double upper,
                                   double coverageLevel) implements TypedResult { }
    public record OrdinalResult(String label, Map<String, Double> probabilities,
                                double thresholdProbability) implements TypedResult { }
    public record BinaryResult(String label, String positiveClass, double probability,
                               double decisionThreshold) implements TypedResult { }
    public record CategoricalResult(String label, Map<String, Double> probabilities) implements TypedResult { }
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "status", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TargetSuccess.class, name = "SUCCEEDED"),
            @JsonSubTypes.Type(value = TargetBlocked.class, name = "BLOCKED"),
            @JsonSubTypes.Type(value = TargetFailed.class, name = "FAILED")
    })
    public sealed interface TargetOutcome permits TargetSuccess, TargetBlocked, TargetFailed { }
    public record TargetSuccess(String status, String targetId, String modelVersionId,
                                TypedResult result, Applicability applicability,
                                List<ValidationIssue> warnings) implements TargetOutcome { }
    /** Blocked targets deliberately contain no prediction/result field. */
    public record TargetBlocked(String status, String targetId, String modelVersionId,
                                String code, String message, Map<String, Object> detail) implements TargetOutcome { }
    public record TargetFailed(String status, String targetId, String modelVersionId,
                               String code, String message, Map<String, Object> detail) implements TargetOutcome { }
    public record ScoreResponse(String contractVersion, String requestId, String runId, String executionStatus,
                                String outcomeStatus,
                                List<TargetOutcome> results) { }

    public record TypedGoal(String targetId, String operator, Object value, boolean mandatory,
                            double weight, Double minimumProbability) { }
    public record RecommendRequest(String contractVersion, String requestId, long seed, String runId,
                                   String mode, Formula baselineFormula, Map<String, Object> fixedInputs,
                                   List<TypedGoal> goals, Map<String, Object> constraints,
                                   List<ModelBinding> modelBindings, int candidateCount) { }
    public record RecommendedCandidate(String candidateId, Formula formula, List<TargetSuccess> results,
                                       double score, Map<String, Object> ruleCheck) { }
    public record RecommendResponse(String contractVersion, String requestId, String runId, String status,
                                    List<RecommendedCandidate> candidates, List<TargetBlocked> blockedTargets,
                                    List<ValidationIssue> warnings) { }
    public record ErrorResponse(String contractVersion, String requestId, String code, String message,
                                boolean retryable, Map<String, Object> detail) { }
}
