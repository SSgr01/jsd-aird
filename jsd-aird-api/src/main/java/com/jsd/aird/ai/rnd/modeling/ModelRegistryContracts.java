package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ModelRegistryContracts {
    private ModelRegistryContracts() { }

    public record ModelSummary(UUID id, UUID targetId, String targetName, String category,
                               String valueType, int version, String status, String modelType,
                               String dataNature, boolean productionEligible, String comparisonStatus,
                               JsonNode metrics, int trainableSamples, UUID workbenchRunId,
                               Instant createdAt, Instant updatedAt, long revision, JsonNode source) { }
    public record ModelDetail(ModelSummary model,JsonNode applicabilityDomain,JsonNode modelCard,List<ReleaseView> releases) { }
    public record ReleaseView(UUID id, String action, UUID modelVersionId, UUID previousModelVersionId,
                              String reason, Instant createdAt) { }
    public record ModelActionCommand(long expectedRevision, UUID expectedActiveModelVersionId,
                                     String reason) { }
    public record ModelActionResult(UUID modelVersionId, String status, UUID activeModelVersionId,
                                    long revision, Instant changedAt) { }
}
