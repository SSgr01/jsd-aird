package com.jsd.aird.ai.formula.application.port;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResearchRepository {

    CreateResult createOrFind(NewRun run);

    Optional<RunRow> findRun(UUID organizationId, UUID runId);

    boolean markRunning(UUID organizationId, UUID runId);

    void complete(UUID organizationId, UUID runId, String mode, String status, String analysisProfileVersion,
                  JsonNode result, List<NewCandidate> candidates);

    void fail(UUID organizationId, UUID runId, String errorCode, String errorMessage);

    List<CandidateRow> candidates(UUID organizationId, UUID runId);

    Optional<CandidateRow> candidate(UUID organizationId, UUID runId, UUID candidateId);

    void lockCandidate(UUID organizationId, UUID runId, UUID candidateId);

    Optional<ExperimentLinkRow> experimentLink(UUID organizationId, UUID candidateId);

    ExperimentLinkRow saveExperimentLink(NewExperimentLink link);

    record NewRun(UUID id, UUID organizationId, String runType, String mode, String status,
                  String taskProfileCode, String idempotencyKey, String requestHash, JsonNode request,
                  UUID createdBy, String createdByName) {}

    record CreateResult(boolean created, RunRow run) {}

    record RunRow(UUID id, UUID organizationId, String runType, String mode, String status,
                  String taskProfileCode, String analysisProfileVersion, String idempotencyKey,
                  String requestHash, JsonNode request, JsonNode result, String errorCode,
                  String errorMessage, UUID createdBy, String createdByName, Instant createdAt,
                  Instant startedAt, Instant finishedAt) {}

    record NewCandidate(UUID id, int candidateNo, String strategy, String title, JsonNode formula,
                        JsonNode process, JsonNode modelContext, JsonNode estimates, JsonNode ruleCheck, JsonNode evidence,
                        String confidence, BigDecimal score, String contentHash) {}

    record CandidateRow(UUID id, UUID organizationId, UUID runId, int candidateNo, String strategy,
                        String title, JsonNode formula, JsonNode process, JsonNode modelContext, JsonNode estimates,
                        JsonNode ruleCheck, JsonNode evidence, String confidence, BigDecimal score,
                        String contentHash) {}

    record NewExperimentLink(UUID id, UUID organizationId, UUID runId, UUID candidateId,
                             UUID experimentId, UUID experimentVersionId, String experimentNo,
                             String idempotencyKey, UUID createdBy) {}

    record ExperimentLinkRow(UUID id, UUID runId, UUID candidateId, UUID experimentId,
                             UUID experimentVersionId, String experimentNo, String idempotencyKey) {}
}
