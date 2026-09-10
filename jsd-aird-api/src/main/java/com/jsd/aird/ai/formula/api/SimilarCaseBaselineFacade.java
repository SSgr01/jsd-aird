package com.jsd.aird.ai.formula.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.T06BaselineDocument;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.TaskProfile;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.ValidationFoldsDocument;

import java.util.List;
import java.util.UUID;

/** Reproducible T06_SIMILAR_CASE baseline consumed by the later production model stage. */
public interface SimilarCaseBaselineFacade {

    BaselineReport evaluate(BaselineQuery query);

    /** Build the production baseline against an already frozen target cohort and fold assignment. */
    T06BaselineDocument evaluateFolded(FoldedBaselineQuery query);

    record FoldedBaselineQuery(
            String taskProfileCode,
            UUID projectId,
            UUID categoryId,
            TaskProfile taskProfile,
            String taskProfileHash,
            String snapshotHash,
            ValidationFoldsDocument validationFolds,
            String validationFoldsArtifactHash
    ) {
    }

    record BaselineQuery(String taskProfileCode, UUID projectId, UUID categoryId, List<String> targetKeys) {
        public BaselineQuery {
            taskProfileCode = taskProfileCode == null || taskProfileCode.isBlank()
                    ? "UVPU_APPLICATION_FORMULATION" : taskProfileCode.strip();
            targetKeys = targetKeys == null ? List.of() : List.copyOf(targetKeys);
        }
    }

    record TargetBaseline(String targetKey, String valueType, String status, JsonNode metrics,
                          JsonNode predictions, String sampleHash, List<String> reasons) {
        public TargetBaseline {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    record BaselineReport(String baselineType, String baselineVersion, String taskProfileCode,
                          String analysisProfileVersion, List<TargetBaseline> targets) {
        public BaselineReport {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }
}
