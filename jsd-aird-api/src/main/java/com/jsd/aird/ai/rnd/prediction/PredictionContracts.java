package com.jsd.aird.ai.rnd.prediction;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PredictionContracts {
    private PredictionContracts() { }

    public record QualityPolicyCommand(String defaultTrustLevel, String defaultExplanation,
                                       List<QualityRule> rules, long expectedRevision) { }
    public record QualityRule(String code, int priority, JsonNode when, String trustLevel, String explanation) { }
    public record PublishPolicyCommand(long expectedRevision) { }
    public record QualityPolicyView(UUID id, UUID targetId, int version, String status, String kind,
                                    String defaultTrustLevel, String defaultExplanation,
                                    List<QualityRule> rules, String policyHash,
                                    long revision, Instant publishedAt, Instant createdAt) { }

    public record PredictionHttpResult(int status, JsonNode body) { }
}
