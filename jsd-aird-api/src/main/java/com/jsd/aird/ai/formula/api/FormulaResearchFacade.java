package com.jsd.aird.ai.formula.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Customer-facing boundary for T06 formulation research. */
public interface FormulaResearchFacade {

    ReadinessView readiness(ReadinessQuery query);

    ParsedResearchRequest parse(ParseRequest request);

    SubmitView submitFormulaPrediction(ResearchRequest request);

    SubmitView submitExperimentOptimization(ResearchRequest request);

    ResearchRunView run(UUID runId);

    List<ExperimentDraftView> createExperimentDrafts(UUID runId, DraftRequest request);

    record ReadinessQuery(UUID projectId, UUID categoryId) {}

    record TargetReadiness(String targetKey, String name, String valueType, String unit, String direction,
                           long eligibleCaseCount, String status, String message,
                           List<String> ordinalLabels, String positiveClass, Double decisionThreshold) {
        public TargetReadiness(String targetKey, String name, String valueType, String unit, String direction,
                               long eligibleCaseCount, String status, String message) {
            this(targetKey, name, valueType, unit, direction, eligibleCaseCount, status, message,
                    List.of(), null, 0.50d);
        }
    }

    record BaselineOption(String analysisRowId, UUID experimentId, UUID experimentVersionId, String experimentNo,
                          String sourceIdentity, LocalDate experimentDate, String title, String formulaSummary) {}

    record MaterialOption(String materialCode, String role, boolean modelAllowed) {}

    record ReadinessView(String taskProfileCode, String analysisProfileVersion, String mode,
                         List<TargetReadiness> targets, List<BaselineOption> baselines,
                         List<MaterialOption> materials) {
        public ReadinessView {
            targets = targets == null ? List.of() : List.copyOf(targets);
            baselines = baselines == null ? List.of() : List.copyOf(baselines);
            materials = materials == null ? List.of() : List.copyOf(materials);
        }

        public ReadinessView(String taskProfileCode, String analysisProfileVersion, String mode,
                             List<TargetReadiness> targets, List<BaselineOption> baselines) {
            this(taskProfileCode, analysisProfileVersion, mode, targets, baselines, List.of());
        }
    }

    record ParseRequest(String text, String runType, ResearchDraft currentDraft) {
        public ParseRequest(String text) {
            this(text, "FORMULA_PREDICTION", null);
        }

        public ParseRequest {
            runType = runType == null || runType.isBlank()
                    ? "FORMULA_PREDICTION" : runType.strip().toUpperCase();
        }
    }

    record ResearchDraft(List<Goal> goals, Map<String, Object> context, Constraints constraints,
                         Integer candidateCount, String baselineAnalysisRowId) {
        public ResearchDraft {
            goals = goals == null ? List.of() : List.copyOf(goals);
            context = context == null ? Map.of() : Map.copyOf(context);
            constraints = constraints == null ? Constraints.empty() : constraints;
            candidateCount = candidateCount == null ? 4 : Math.min(4, Math.max(1, candidateCount));
        }

        public static ResearchDraft empty() {
            return new ResearchDraft(List.of(), Map.of(), Constraints.empty(), 4, null);
        }
    }

    record UnresolvedField(String field, String code, String message) {}

    record ParsedResearchRequest(String originalText, List<Goal> goals, List<String> warnings,
                                 ResearchDraft draft, List<UnresolvedField> unresolvedFields,
                                 String confirmationStatus, String interpretationMode,
                                 String interpretationModel, String interpretationPromptVersion,
                                 String fallbackReason) {
        public ParsedResearchRequest {
            goals = goals == null ? List.of() : List.copyOf(goals);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            draft = draft == null ? ResearchDraft.empty() : draft;
            unresolvedFields = unresolvedFields == null ? List.of() : List.copyOf(unresolvedFields);
            confirmationStatus = confirmationStatus == null ? "NEEDS_INPUT" : confirmationStatus;
            interpretationMode = interpretationMode == null ? "DETERMINISTIC_FALLBACK" : interpretationMode;
        }

        public ParsedResearchRequest(String originalText, List<Goal> goals, List<String> warnings) {
            this(originalText, goals, warnings,
                    new ResearchDraft(goals, Map.of(), Constraints.empty(), 4, null), List.of(),
                    goals == null || goals.isEmpty() ? "NEEDS_INPUT" : "READY",
                    "DETERMINISTIC_FALLBACK", null, null, null);
        }
    }

    record Goal(String targetKey, String mode, boolean mandatory, BigDecimal weight,
                BigDecimal value, BigDecimal minimum, BigDecimal maximum, BigDecimal tolerance,
                BigDecimal minimumProbability) {
        public Goal(String targetKey, String mode, boolean mandatory, BigDecimal weight,
                    BigDecimal value, BigDecimal minimum, BigDecimal maximum, BigDecimal tolerance) {
            this(targetKey, mode, mandatory, weight, value, minimum, maximum, tolerance, null);
        }

        public Goal {
            mode = mode == null ? "" : mode.strip().toUpperCase();
            weight = weight == null || weight.signum() <= 0 ? BigDecimal.ONE : weight;
            if (minimumProbability != null
                    && (minimumProbability.signum() <= 0 || minimumProbability.compareTo(BigDecimal.ONE) >= 0)) {
                throw new IllegalArgumentException("minimumProbability必须在0和1之间");
            }
        }
    }

    record ValueRange(BigDecimal minimum, BigDecimal maximum) {}

    record Constraints(List<String> requiredMaterials, List<String> forbiddenMaterials,
                       Map<String, BigDecimal> fixedMaterials, Map<String, ValueRange> materialRanges,
                       List<List<String>> forbiddenMaterialCombinations,
                       Map<String, ValueRange> processRanges, Integer maxMaterialCount,
                       Boolean requireCostCheck, Boolean requireInventoryCheck) {
        public Constraints {
            requiredMaterials = requiredMaterials == null ? List.of() : List.copyOf(requiredMaterials);
            forbiddenMaterials = forbiddenMaterials == null ? List.of() : List.copyOf(forbiddenMaterials);
            fixedMaterials = fixedMaterials == null ? Map.of() : Map.copyOf(fixedMaterials);
            materialRanges = materialRanges == null ? Map.of() : Map.copyOf(materialRanges);
            forbiddenMaterialCombinations = forbiddenMaterialCombinations == null ? List.of()
                    : forbiddenMaterialCombinations.stream().map(List::copyOf).toList();
            processRanges = processRanges == null ? Map.of() : Map.copyOf(processRanges);
        }
        public static Constraints empty() {
            return new Constraints(List.of(), List.of(), Map.of(), Map.of(), List.of(), Map.of(), null, false, false);
        }
    }

    record ResearchRequest(String taskProfileCode, String idempotencyKey, UUID projectId, UUID categoryId,
                           String baselineAnalysisRowId, List<Goal> goals, Map<String, Object> context,
                           Constraints constraints, Integer candidateCount) {
        public ResearchRequest {
            taskProfileCode = taskProfileCode == null || taskProfileCode.isBlank()
                    ? "UVPU_APPLICATION_FORMULATION" : taskProfileCode.strip();
            goals = goals == null ? List.of() : List.copyOf(goals);
            context = context == null ? Map.of() : Map.copyOf(context);
            constraints = constraints == null ? Constraints.empty() : constraints;
            candidateCount = candidateCount == null ? 4 : Math.min(4, Math.max(1, candidateCount));
        }
    }

    record SubmitView(UUID runId, String status) {}

    record CandidateView(UUID id, int candidateNo, String strategy, String title, JsonNode formula,
                         JsonNode process, JsonNode modelContext, JsonNode estimates, JsonNode ruleCheck, JsonNode evidence,
                         String confidence, BigDecimal score, String contentHash) {}

    record ResearchRunView(UUID id, String runType, String mode, String status, String taskProfileCode,
                           String analysisProfileVersion, JsonNode request, JsonNode result,
                           String errorCode, String errorMessage, List<CandidateView> candidates,
                           Instant createdAt, Instant startedAt, Instant finishedAt) {
        public ResearchRunView {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    record DraftRequest(List<UUID> candidateIds, UUID categoryId, UUID projectId, UUID stageId, UUID taskId,
                        String ownerName, LocalDate plannedExperimentDate, String idempotencyKey) {
        public DraftRequest {
            candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
        }
    }

    record ExperimentDraftView(UUID candidateId, UUID experimentId, UUID experimentVersionId,
                               String experimentNo, String title) {}
}
