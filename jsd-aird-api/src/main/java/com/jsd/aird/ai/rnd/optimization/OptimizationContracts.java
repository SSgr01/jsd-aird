package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OptimizationContracts {
    private OptimizationContracts() { }

    public record BaselineSummary(String type, UUID id, UUID versionId, String title, String sourceLabel,
                                  double actualTotal, JsonNode materials, JsonNode inputs, JsonNode results,
                                  String contentHash, Instant updatedAt) { }
    public record BaselinePage(List<BaselineSummary> items, int page, int size) { }
    public record OptimizationAccepted(UUID runId, UUID asyncJobId, String executionStatus,
                                       Map<UUID,UUID> modelBindings, int pollAfterMs) { }
    public record OptimizationCandidate(UUID id,int candidateNo,String strategy,String title,JsonNode formula,
                                        JsonNode inputs,JsonNode results,JsonNode quality,JsonNode applicability,
                                        JsonNode ruleCheck,double score,double targetTotal,double baselineDistance,
                                        JsonNode changes,JsonNode strategyEvidence,JsonNode risks) { }
    public record OptimizationRun(UUID id,String executionStatus,String outcomeStatus,int progress,String currentStage,
                                  int requestedCandidateCount,int actualCandidateCount,List<String> missingStrategies,
                                  JsonNode missingStrategyReasons,
                                  List<OptimizationCandidate> candidates,JsonNode error,JsonNode frozenBaseline,
                                  JsonNode frozenRequest,Map<UUID,UUID> modelBindings,String requestId,Instant updatedAt,
                                  String searchEngine,String searchStrategy,JsonNode searchEvidence) { }
    public record DraftCommand(List<UUID> candidateIds, UUID categoryId, LocalDate plannedExperimentDate,
                               UUID projectId, UUID stageId, UUID taskId, String ownerName,
                               UUID templateVersionId) { }
    public record DraftLink(UUID candidateId,UUID experimentId,UUID experimentVersionId,String experimentNo,
                            String status,String href,Instant createdAt) { }
    public record DraftCreationResult(UUID runId,List<DraftLink> experiments) { }
}
