package com.jsd.aird.recognition.application.port;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shared persistence boundary for Data and RND source-recognition workspaces. */
public interface SourceRecognitionRepository {
    void create(NewJob job);
    Optional<Job> find(UUID organizationId, UUID id, String sourceOwner);
    Optional<Job> findBySourceHash(UUID organizationId, String sourceOwner, String sourceSha256);
    List<Job> list(UUID organizationId, String sourceOwner);
    void saveParsed(UUID organizationId, UUID id, String parserVersion, JsonNode workspace,
                    JsonNode boundaries);
    Job updateWorkspace(UUID organizationId, UUID id, String sourceOwner, long expectedRevision,
                        JsonNode workspace, JsonNode boundaries, UUID profileId);
    int markParsing(UUID organizationId, UUID id, String sourceOwner);
    int claimFinalization(UUID organizationId, UUID id, String sourceOwner, long expectedRevision);
    int cancel(UUID organizationId, UUID id, String sourceOwner);
    void fail(UUID organizationId, UUID id, String message);
    void markFinalized(UUID organizationId, UUID id);
    FinalizedData finalizeData(UUID organizationId, UUID recognitionJobId, UUID actorId,
                               List<RecognizedSample> samples, JsonNode mappingContract, String submissionHash);
    void linkExperiment(UUID organizationId, UUID recognitionJobId, UUID confirmedSubmissionId,
                        String experimentBoundaryId, UUID experimentId, UUID experimentVersionId,
                        String experimentNo, JsonNode sourceCoordinates, JsonNode recognitionSnapshot,
                        List<RecognizedSample> samples, String contentHash, UUID actorId);
    Optional<UUID> currentSubmissionId(UUID organizationId, UUID recognitionJobId);
    RecognitionProfile saveProfile(UUID organizationId, String name, String sourceFormat,
                                   JsonNode rules, String hash, UUID actorId);

    record NewJob(UUID id, UUID organizationId, UUID sourceFileId, String sourceFileName,
                  String sourceSha256, String sourceFormat, String sourceOwner,
                  String recognitionMode, UUID templateVersionId, UUID categoryId,
                  String importPurpose, UUID targetExperimentCategoryId, String visibility,
                  JsonNode initialWorkspace, JsonNode initialBoundaries, UUID actorId) {}

    record Job(UUID id, UUID organizationId, UUID sourceFileId, String sourceFileName,
               String sourceSha256, String sourceFormat, String sourceOwner,
               String recognitionMode, UUID templateVersionId, UUID categoryId,
               String importPurpose, UUID targetExperimentCategoryId, String visibility,
               String status, int progress, String currentStage, String parserVersion,
               JsonNode workspace, JsonNode boundaries, long recognitionRevision,
               UUID recognitionProfileId, long sourceSequence, Instant finalizedAt,
               String errorMessage, Instant createdAt, Instant updatedAt) {}

    record RecognitionProfile(UUID id, String profileCode, String name, String sourceFormat,
                              String status, JsonNode rules, String profileHash,
                              long revision, Instant updatedAt) {}

    /**
     * A user-confirmed logical sample. sourceGroupKeys are physical source
     * regions and may contain several regions for the same sample.
     */
    record RecognizedSample(String experimentBoundaryId, String sampleBoundaryId,
                            String logicalSampleKey, List<String> sourceGroupKeys, String title, JsonNode rawFact,
                            JsonNode correctedFact, JsonNode effectiveFact,
                            JsonNode sourceCoordinates, String contentHash) {
        public RecognizedSample {
            sourceGroupKeys = sourceGroupKeys == null ? List.of() : List.copyOf(sourceGroupKeys);
        }
    }
    record RecognizedExperiment(String experimentBoundaryId, String title,
                                List<RecognizedSample> samples, JsonNode sourceCoordinates,
                                String contentHash) {
        public RecognizedExperiment {
            samples = samples == null ? List.of() : List.copyOf(samples);
        }
    }
    record FinalizedData(UUID submissionId, int revisionNo, List<UUID> itemIds) {}
}
