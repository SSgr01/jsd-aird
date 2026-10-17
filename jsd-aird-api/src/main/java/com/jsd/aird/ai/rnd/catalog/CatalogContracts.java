package com.jsd.aird.ai.rnd.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public DTOs for the shared customer test-method reference import. */
public final class CatalogContracts {
    private CatalogContracts() { }

    public record ImportCommand(UUID fileId) { }

    public record ImportSummary(UUID id, String sourceName, String sourceSha256,
                                int catalogVersion, String parserVersion, String status,
                                int sourceRowCount, int proposalCount, JsonNode summary,
                                Instant createdAt) { }

    public record ProposalView(UUID id, UUID importId, int sourceRow, int atomicOrdinal,
                               String semanticKey, String rawItemName, String rawTestMethod,
                               String rawExampleResult, String rawInfluenceFactors,
                               String atomicName, String suggestedCode, String suggestedValueType,
                               String suggestedUnit, UUID standardFieldId, String standardFieldCode,
                               JsonNode qualifiers, List<String> aliases, List<String> factorSuggestions,
                               JsonNode evidence, int confidence, String reviewStatus,
                               JsonNode application, Instant createdAt, String changeStatus,
                               UUID previousProposalId, JsonNode diff) { }

    public record ProposalPage(List<ProposalView> content, int page, int size, long totalPages,
                               long totalElements) { }

}
