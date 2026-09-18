package com.jsd.aird.ai.rnd.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The typed boundary for R08 formula design.  JSON values remain typed by the
 * selected Y definition; the API never falls back to the legacy chat payload. */
public final class FormulaDesignContracts {
    private FormulaDesignContracts() { }
    public enum ExecutionStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public enum OutcomeStatus { SUCCEEDED, PARTIAL, BLOCKED }
    public record RunAccepted(UUID runId, UUID asyncJobId, ExecutionStatus executionStatus,
                              Map<UUID, UUID> modelBindings, int pollAfterMs) { }
    public record Candidate(UUID id, int candidateNo, String title, JsonNode formula,
                            JsonNode targetResults, JsonNode quality, JsonNode applicability,
                            JsonNode ruleCheck, double score) { }
    public record ResearchRun(UUID id, String runType, ExecutionStatus executionStatus,
                              OutcomeStatus outcomeStatus, int progress, String currentStage,
                              int requestedCandidateCount, int actualCandidateCount,
                              List<Candidate> candidates, JsonNode shortfallReason,
                              JsonNode rejectionSummary, JsonNode actionHints,
                              Map<UUID, UUID> modelBindings, JsonNode frozenEvidence,
                              String requestId, Instant updatedAt) { }
    public record ResearchPage(List<ResearchRun> items, long total, int page, int size) { }
}
