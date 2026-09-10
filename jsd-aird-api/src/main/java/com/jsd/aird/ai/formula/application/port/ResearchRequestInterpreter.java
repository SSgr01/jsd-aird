package com.jsd.aird.ai.formula.application.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * Language-model boundary for interpreting a conversational research request.
 * The model can only suggest changes to registered concepts; the application
 * service remains responsible for validating and applying every suggestion.
 */
public interface ResearchRequestInterpreter {

    Result interpret(Request request);

    record Request(String text, String runType, List<TargetOption> targets,
                   List<MaterialOption> materials, DraftContext currentDraft) {
        public Request {
            targets = targets == null ? List.of() : List.copyOf(targets);
            materials = materials == null ? List.of() : List.copyOf(materials);
        }
    }

    record TargetOption(String targetRef, String targetKey, String name, String valueType, String unit,
                        String direction, List<String> aliases) {
        public TargetOption {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    record MaterialOption(String materialCode, String role, List<String> aliases) {
        public MaterialOption {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    record DraftContext(List<GoalState> goals, String substrate,
                        List<String> requiredMaterials, List<String> forbiddenMaterials,
                        int candidateCount, boolean baselineSelected) {
        public DraftContext {
            goals = goals == null ? List.of() : List.copyOf(goals);
            requiredMaterials = requiredMaterials == null ? List.of() : List.copyOf(requiredMaterials);
            forbiddenMaterials = forbiddenMaterials == null ? List.of() : List.copyOf(forbiddenMaterials);
        }
    }

    record GoalState(String targetKey, String mode, boolean mandatory,
                     BigDecimal weight, BigDecimal value) {}

    record Suggestion(List<GoalChange> goalChanges, List<MaterialChange> materialChanges,
                      SubstrateChange substrateChange, String summary) {
        public Suggestion {
            goalChanges = goalChanges == null ? List.of() : List.copyOf(goalChanges);
            materialChanges = materialChanges == null ? List.of() : List.copyOf(materialChanges);
        }
    }

    record GoalChange(String operation, String targetRef, String mode, Boolean mandatory,
                      BigDecimal weight, BigDecimal value, String evidence) {}

    record MaterialChange(String operation, String materialCode, String evidence) {}

    record SubstrateChange(String operation, String value, String evidence) {}

    record Result(Suggestion suggestion, String status, String model,
                  String promptVersion, String failureReason) {
        public static Result unavailable(String status, String model, String promptVersion) {
            return new Result(null, status, model, promptVersion, status);
        }
    }
}
