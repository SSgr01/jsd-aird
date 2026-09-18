package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ModelingContracts {
    private ModelingContracts() { }

    public record VersionCommand(long expectedRevision) { }
    public record RetireCommand(long expectedRevision, String reason) { }

    public record TargetCommand(String code, String name, String category, String valueType,
                                String unit, List<String> classes, JsonNode definition,
                                JsonNode observationSemantics, Long expectedRevision,
                                String resultStandardFieldCode, UUID catalogProposalId) {
        public TargetCommand(String code, String name, String category, String valueType,
                             String unit, List<String> classes, JsonNode definition,
                             JsonNode observationSemantics, Long expectedRevision) {
            this(code, name, category, valueType, unit, classes, definition,
                    observationSemantics, expectedRevision, null, null);
        }
    }

    public record TargetSummary(UUID id, String code, String name, String category, String valueType,
                                String status, UUID currentVersionId, Integer currentVersion,
                                UUID currentInputSchemeId, String currentInputSchemeName,
                                UUID activeModelVersionId, String evaluationStatus,
                                String trainingStatus, long revision, Instant updatedAt) { }

    public record TargetVersionView(UUID id, UUID targetId, int version, String status,
                                    String valueType, String unit, List<String> classes,
                                    JsonNode definition, JsonNode observationSemantics,
                                    String configHash, Instant publishedAt, Instant createdAt,
                                    UUID resultStandardFieldDictionaryId, UUID catalogProposalId) { }

    public record SourceMappingCommand(UUID targetVersionId, String sourceType, JsonNode mapping,
                                       Long expectedRevision) { }

    public record SourceMappingView(UUID id, UUID targetId, UUID targetVersionId, String sourceType,
                                    int version, String status, JsonNode mapping, String mappingHash,
                                    Instant publishedAt, Instant createdAt) { }

    public record SourceSamplePreview(String sourceType, String sampleName, String value,
                                      String location) { }
    public record SourceMappingSuggestion(String candidateKey, String fieldName, String unit,
                                          String testMethod, String load, String substrate, String stage,
                                          long dataCenterCount, long experimentCount, int confidence,
                                          boolean confirmationRequired, List<SourceSamplePreview> samples) { }
    public record SourceMappingSuggestions(UUID targetId, UUID targetVersionId,
                                           long dataCenterCount, long experimentCount,
                                           int pendingConfirmationCount,
                                           List<SourceMappingSuggestion> suggestions) { }
    public record ConfirmSourceMappingCommand(UUID targetVersionId, String candidateKey,
                                              long expectedRevision) { }
    public record ConfirmSourceMappingResult(List<SourceMappingView> mappings,
                                             String recomputeStatus, UUID recomputeRunId) { }

    public record InputFieldCommand(String code, String name, String valueType, String unit,
                                    String availabilityStage, UUID standardFieldDictionaryId,
                                    JsonNode definition, JsonNode preprocessing, Long expectedRevision) { }

    public record InputFieldSummary(UUID id, String code, String name, String valueType, String unit,
                                    String availabilityStage, String status, UUID currentVersionId,
                                    Integer currentVersion, String standardFieldCode,
                                    String standardFieldName, String standardFieldUnit,
                                    long revision, Instant updatedAt) { }

    public record InputFieldVersionView(UUID id, UUID inputFieldId, int version, String status,
                                        String valueType, String unit, String availabilityStage,
                                        UUID standardFieldDictionaryId, String standardFieldCode,
                                        String standardFieldName, String standardFieldUnit,
                                        JsonNode definition, JsonNode preprocessing,
                                        String configHash, Instant publishedAt, Instant createdAt) { }

    public record SchemeFieldCommand(UUID inputFieldVersionId, boolean required, int ordinal,
                                     JsonNode override) { }

    public record InputSchemeCommand(UUID targetVersionId, UUID materialDictionaryVersionId, String code, String name,
                                     List<SchemeFieldCommand> fields, JsonNode preprocessing,
                                     Long expectedRevision) { }

    public record SchemeFieldView(UUID inputFieldVersionId, String fieldCode, String fieldName,
                                  String valueType, String unit, String availabilityStage,
                                  boolean required, int ordinal, JsonNode override) { }

    public record InputSchemeView(UUID id, UUID targetId, UUID targetVersionId,
                                  UUID materialDictionaryVersionId, String materialDictionaryCode,
                                  String code, String name,
                                  int version, String status, List<SchemeFieldView> fields,
                                  JsonNode preprocessing, String configHash, long revision,
                                  Instant frozenAt, Instant createdAt) { }

    public record ValidationIssue(String code, String fieldCode, String message) { }

    public record CoveragePreview(String evaluationStatus, String unavailableReason,
                                  List<ValidationIssue> validationIssues, String schemaHash,
                                  Long candidateSamples, Long trainable, Long excluded,
                                  Long reviewRequired, List<UUID> changedSampleIds) { }

    public record FreezeResult(UUID id, String status, String recomputeStatus,
                               UUID currentInputSchemeId, long targetRevision) { }

    public record TrainingPolicyCommand(UUID targetId, JsonNode qualification, JsonNode validation,
                                        JsonNode training, Long expectedRevision) { }

    public record TrainingPolicyView(UUID id, UUID targetId, int version, String status,
                                     JsonNode qualification, JsonNode validation, JsonNode training,
                                     String policyHash, Instant publishedAt, Instant createdAt) { }

    public record ReferenceField(UUID id, String code, int version, String name,
                                 String valueType, String unit, String groupCode) { }

    public record MaterialReference(UUID id, String code, String name, String category, String status) { }

    public record MaterialAliasCommand(UUID materialId, String alias, Long expectedRevision) { }

    public record MaterialAliasView(UUID id, UUID materialId, String materialCode, String materialName,
                                    String alias, String normalizedAlias, String status,
                                    long revision, Instant updatedAt) { }

    public record MaterialDictionaryItem(UUID materialId, String role, int ordinal, String token) { }

    public record MaterialDictionaryCommand(String code, List<MaterialDictionaryItem> items,
                                            JsonNode encoder, Long expectedRevision) { }

    public record MaterialDictionaryView(UUID id, String code, int version, String status,
                                         List<MaterialDictionaryItem> items, JsonNode encoder,
                                         String dictionaryHash, long revision,
                                         Instant frozenAt, Instant createdAt) { }

}
