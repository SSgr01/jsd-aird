package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OptimizationContracts {
    private OptimizationContracts() { }

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
                                  String searchEngine,String searchStrategy,JsonNode searchEvidence,
                                  boolean simulation,String predictionMode) { }
    public record ObservationValue(UUID candidateId, UUID targetId, String fieldCode, JsonNode value) { }
    public record ObservationCommand(List<ObservationValue> observations) { }
}
