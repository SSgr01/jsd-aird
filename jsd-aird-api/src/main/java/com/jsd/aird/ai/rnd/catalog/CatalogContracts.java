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

    public record TargetSuggestion(UUID proposalId, String atomicName, String suggestedCode,
                                   String suggestedValueType, String suggestedUnit,
                                   UUID matchedTargetId, String matchedTargetName,
                                   String matchStatus, int confidence, JsonNode qualifiers,
                                   List<String> aliases, JsonNode evidence) { }

    public record TargetSuggestionPage(List<TargetSuggestion> content, int page, int size,
                                       long totalPages, long totalElements) { }

    public record ApplyTargetCommand(String mode, UUID targetId, String code, String name,
                                     String category, String valueType, String unit,
                                     List<String> classes, JsonNode definition,
                                     JsonNode observationSemantics, long expectedRevision) { }

    public record AppliedTarget(UUID targetId, UUID targetVersionId, String status,
                                boolean created, String message) { }

    public record StandardFieldRequest(UUID requestId, UUID proposalId, String status,
                                       String message) { }

    public record InputSuggestion(UUID inputFieldId, String inputFieldCode, String inputFieldName,
                                  String valueType, String availabilityStage, UUID standardFieldId,
                                  String standardFieldCode, String standardFieldName,
                                  long dataCenterCoverage, long experimentCoverage,
                                  String matchStatus, int confidence, String factor) { }

    public record InputSuggestionPage(List<InputSuggestion> content, int page, int size,
                                      long totalPages, long totalElements) { }
}
