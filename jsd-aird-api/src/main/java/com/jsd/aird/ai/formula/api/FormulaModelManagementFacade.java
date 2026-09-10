package com.jsd.aird.ai.formula.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Restricted operational boundary for production formula model builds and target activation. */
public interface FormulaModelManagementFacade {
    BuildView startBuild(BuildRequest request);
    BuildView build(UUID buildId);
    ActivationView activate(UUID versionId, String targetKey, ActivationRequest request);
    ActivationView rollback(String targetKey, ActivationRequest request);
    StatusView status();

    record BuildRequest(UUID projectId, UUID categoryId, Long seed) {
        public long effectiveSeed() { return seed == null ? 2026L : seed; }
    }

    record ActivationRequest(String reason) {
    }

    record TargetView(String targetKey, String targetCode, String valueType, String status,
                      boolean productionEligible, String scorerType, String primaryMetricName,
                      Number primaryMetricValue, Number baselineMetricValue, Number baselineImprovement,
                      JsonNode reasons) {
    }

    record BuildView(UUID buildId, UUID snapshotId, String snapshotStatus, String modelStatus,
                     String snapshotHash, String validationFoldsHash, String t06BaselineHash,
                     String modelBundleHash, int rowCount, JsonNode targetSummary,
                     List<TargetView> targets, String errorCode, String errorMessage,
                     Instant createdAt, Instant completedAt) {
    }

    record ActivationView(String targetKey, UUID modelVersionId, UUID previousModelVersionId,
                          String status, String reason, Instant activatedAt) {
    }

    record ActiveTargetView(String targetKey, UUID modelVersionId, UUID previousModelVersionId,
                            Instant activatedAt) {
    }

    record StatusView(String taskProfileCode, String taskProfileVersion, String taskProfileHash,
                      List<ActiveTargetView> activeTargets, JsonNode computeHealth) {
    }
}
