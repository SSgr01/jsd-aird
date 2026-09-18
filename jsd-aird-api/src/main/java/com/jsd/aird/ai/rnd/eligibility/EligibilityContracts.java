package com.jsd.aird.ai.rnd.eligibility;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EligibilityContracts {
    private EligibilityContracts() { }

    public enum State { TRAINABLE, EXCLUDED, REVIEW_REQUIRED }
    public enum RunStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public record FunnelStep(String code, String label, long count, long denominator,
                             Instant evaluatedAt) { }

    public record FieldCoverage(String fieldCode, String fieldName, long available,
                                 long denominator, double ratio, List<String> reasonCodes) { }

    public record EvaluationRun(UUID id, UUID targetId, UUID targetVersionId, UUID inputSchemeId,
                                UUID modelingPolicyVersionId, String ruleFingerprint, UUID asyncJobId,
                                RunStatus status, Long totalSamples, Long trainable,
                                Long excluded, Long reviewRequired, List<FunnelStep> funnel,
                                Instant createdAt, Instant startedAt, Instant finishedAt,
                                String errorCode, String errorMessage) { }

    public record Summary(String evaluationStatus, String unavailableReason, EvaluationRun latestRun,
                          Long total, Long trainable, Long excluded, Long reviewRequired,
                          Map<String, Long> reasonCounts, List<FunnelStep> funnel,
                          List<FieldCoverage> fieldCoverage, Instant evaluatedAt) { }

    public record QualificationReason(String code, String message, String fieldCode,
                                      JsonNode evidence) { }

    public record Eligibility(UUID id, UUID evaluationRunId, UUID sampleRevisionId,
                             UUID targetVersionId, UUID inputSchemeId, State state,
                             String primaryReasonCode, List<QualificationReason> reasons,
                             List<QualificationReason> warnings, JsonNode evidence,
                             long revision, Instant evaluatedAt) { }

    public record EligibilityRow(UUID id, UUID evaluationRunId, UUID sampleRevisionId,
                                 UUID targetVersionId, UUID inputSchemeId, State state,
                                 String primaryReasonCode, List<QualificationReason> reasons,
                                 List<QualificationReason> warnings, String logicalSampleKey,
                                 String authoritySourceType, long revision, Instant evaluatedAt) { }

    public record EligibilityDetail(Eligibility eligibility, JsonNode sample,
                                    List<Review> reviews) { }

    public record Review(UUID id, String reviewType, String reasonCode, String status,
                         short priority, JsonNode evidence, long revision, Instant createdAt,
                         Instant updatedAt) { }

    public record ReviewDecisionCommand(String decision, String reason, UUID materialId,
                                       long expectedRevision) { }

    public record ReviewDecision(UUID reviewId, String decision, String reason,
                                 UUID recomputeRunId, long revision, Instant decidedAt) { }

    public record RecomputeAccepted(UUID runId, UUID asyncJobId, UUID targetId,
                                    UUID targetVersionId, UUID inputSchemeId,
                                    RunStatus status, String ruleFingerprint) { }
}
