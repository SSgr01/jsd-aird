package com.jsd.aird.tpl.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.tpl.domain.TemplateFormat;

public interface TemplateImportRepository {

    void enqueue(NewImportJob job);

    /** Reuses the import record while preserving every previous recognition run. */
    boolean enqueueRerun(RerunImportJob job);

    Optional<ImportJobView> find(UUID organizationId, UUID importJobId);

    Optional<ImportJobView> findLatestForVersion(UUID organizationId, UUID versionId);

    /**
     * Returns the original Office file used to create a version, if it is still available.
     * Recognition retries normally use the saved editor snapshot, but legacy snapshots
     * may need to be rebuilt from the original file.
     */
    Optional<UUID> findOriginalSourceFileId(UUID organizationId, UUID versionId);

    Optional<UUID> findGeneratedVersionId(UUID importJobId);

    int countManualReruns(UUID importJobId);

    List<ImportJobView> list(UUID organizationId);

    Optional<ImportJobView> findDuplicate(UUID organizationId, String sourceSha256, TemplateFormat format);

    void complete(UUID importJobId, OfficeStructureParser.ParseResult result);

    void saveRenderSnapshot(UUID importJobId, JsonNode snapshot);

    void saveImportResult(UUID importJobId, JsonNode result);

    void updateProgress(UUID importJobId, int progress, String stage);

    void fail(UUID importJobId, String message);

    UUID startRecognitionRun(
            UUID importJobId, String scope, int structureVersion, int snapshotFormatVersion, int regionCount,
            UUID parentRunId, String runReason
    );

    default UUID startRecognitionRun(
            UUID importJobId, String scope, int structureVersion, int snapshotFormatVersion, int regionCount
    ) {
        return startRecognitionRun(importJobId, scope, structureVersion, snapshotFormatVersion, regionCount,
                null, "INITIAL_RECOGNITION");
    }

    void updateRecognitionRunSnapshot(UUID recognitionRunId, String snapshotHash, String reason);

    void updateRecognitionRunRegionCount(UUID recognitionRunId, int regionCount);

    void saveRecognitionCall(UUID recognitionRunId, RecognitionModelClient.CallTrace trace);

    void completeRecognitionRun(UUID recognitionRunId, String status);

    int purgeExpiredRecognitionPayloads();

    void replaceModelSuggestions(UUID importJobId, UUID recognitionRunId, RecognitionModelClient.RecognitionBatch batch);

    /** Appends a reviewed recompile result without deleting the original audit trail. */
    void appendModelSuggestions(UUID importJobId, UUID recognitionRunId, RecognitionModelClient.RecognitionBatch batch);

    /**
     * Removes the previous generation from the active review projection while
     * retaining every row for audit.  The selected structure root remains
     * active; its old semantic children are superseded by the new generation.
     */
    void supersedeStructureGeneration(
            UUID organizationId,
            UUID recognitionRunId,
            List<UUID> selectedStructureSuggestionIds,
            List<String> selectedRegionIds,
            String generationId,
            UUID actorId
    );

    void markStructureResolved(UUID organizationId, UUID recognitionRunId, UUID suggestionId);

    void replacePhysicalSuggestions(UUID importJobId, UUID recognitionRunId, RecognitionModelClient.RecognitionBatch batch);

    void replaceRuleSuggestions(UUID importJobId, UUID recognitionRunId, RecognitionModelClient.RecognitionBatch batch);

    void appendRuleSuggestions(UUID importJobId, UUID recognitionRunId, RecognitionModelClient.RecognitionBatch batch);

    void replaceQualityIssues(
            UUID importJobId,
            UUID recognitionRunId,
            List<RecognitionModelClient.QualityIssueSuggestion> issues,
            String beforeSnapshotHash,
            String afterSnapshotHash
    );

    List<QualityIssueView> listQualityIssues(UUID organizationId, UUID importJobId);

    Optional<QualityIssueView> decideQualityIssue(
            UUID organizationId, UUID importJobId, UUID issueId, String action, UUID actorId
    );

    List<RecognitionSuggestionView> listSuggestions(UUID organizationId, UUID importJobId);

    List<RecognitionCallView> listRecognitionCalls(UUID organizationId, UUID importJobId);

    int delete(UUID organizationId, UUID importJobId);

    Optional<RecognitionSuggestionView> decideSuggestion(
            UUID organizationId,
            UUID importJobId,
            UUID suggestionId,
            String decision,
            UUID actorId
    );

    int acceptSuggestionsAboveConfidence(
            UUID organizationId,
            UUID importJobId,
            double confidence,
            UUID actorId
    );

    void linkGeneratedVersion(UUID organizationId, UUID importJobId, UUID versionId);

    record NewImportJob(
            UUID importJobId,
            UUID asyncJobId,
            UUID organizationId,
            UUID fileId,
            TemplateFormat format,
            UUID actorId,
            String sourceKind,
            String scope,
            String sheetId,
            String address,
            JsonNode snapshotFragment,
            UUID categoryId,
            String sourceSha256,
            boolean duplicateOverride,
            UUID duplicateSourceJobId,
            String operationSource
    ) {
        public NewImportJob {
            sourceKind = sourceKind == null ? "OFFICE_FILE" : sourceKind;
            scope = scope == null ? "WORKBOOK" : scope;
        }

        public NewImportJob(
                UUID importJobId,
                UUID asyncJobId,
                UUID organizationId,
                UUID fileId,
                TemplateFormat format,
                UUID actorId,
                String sourceKind
        ) {
            this(importJobId, asyncJobId, organizationId, fileId, format, actorId, sourceKind,
                    "WORKBOOK", null, null, null, null, null, false, null, "UPLOAD");
        }

        public NewImportJob(
                UUID importJobId, UUID asyncJobId, UUID organizationId, UUID fileId,
                TemplateFormat format, UUID actorId, String sourceKind, String scope,
                String sheetId, String address, JsonNode snapshotFragment
        ) {
            this(importJobId, asyncJobId, organizationId, fileId, format, actorId, sourceKind,
                    scope, sheetId, address, snapshotFragment, null, null, false, null, "RERECOGNITION");
        }
    }

    record RerunImportJob(
            UUID importJobId,
            UUID asyncJobId,
            UUID organizationId,
            UUID sourceFileId,
            TemplateFormat format,
            UUID actorId,
            UUID parentRunId,
            String runReason,
            String sourceKind
    ) {
        public RerunImportJob {
            runReason = runReason == null || runReason.isBlank()
                    ? "MANUAL_RERUN_CURRENT_DRAFT" : runReason;
            sourceKind = sourceKind == null || sourceKind.isBlank()
                    ? "UNIVER_SNAPSHOT" : sourceKind;
        }

        public RerunImportJob(
                UUID importJobId,
                UUID asyncJobId,
                UUID organizationId,
                UUID sourceFileId,
                TemplateFormat format,
                UUID actorId,
                UUID parentRunId,
                String runReason
        ) {
            this(importJobId, asyncJobId, organizationId, sourceFileId, format, actorId,
                    parentRunId, runReason, "UNIVER_SNAPSHOT");
        }
    }

    record ImportJobView(
            UUID id,
            UUID sourceFileId,
            String sourceFileName,
            TemplateFormat format,
            String status,
            int progress,
            String currentStage,
            JsonNode structureSummary,
            JsonNode result,
            JsonNode recognitionSummary,
            String lastError,
            Instant createdAt,
            int retryCount,
            int suggestionCount,
            int pendingSuggestionCount,
            UUID recognitionRunId,
            String recognitionRunStatus,
            UUID generatedTemplateVersionId,
            String workspaceHash,
            List<IssueView> issues,
            UUID categoryId,
            String categoryName,
            String sourceSha256,
            boolean duplicateOverride,
            UUID duplicateSourceJobId
    ) {
    }

    record IssueView(
            String severity,
            String code,
            String message,
            JsonNode location,
            String resolution
    ) {
    }

    record RecognitionSuggestionView(
            UUID id,
            UUID importJobId,
            UUID recognitionRunId,
            String source,
            String suggestionType,
            JsonNode payload,
            double confidence,
            JsonNode evidence,
            String decision,
            String provider,
            String model,
            String promptVersion,
            String filterReasonCode,
            String filterDetail,
            Instant createdAt
    ) {
        public RecognitionSuggestionView(
                UUID id, UUID importJobId, String source, String suggestionType, JsonNode payload,
                double confidence, JsonNode evidence, String decision, String provider, String model,
                String promptVersion, Instant createdAt
        ) {
            this(id, importJobId, null, source, suggestionType, payload, confidence, evidence, decision,
                    provider, model, promptVersion, "", "", createdAt);
        }
    }

    record RecognitionCallView(
            UUID id,
            UUID recognitionRunId,
            String regionId,
            int attempt,
            String provider,
            String model,
            String promptVersion,
            String status,
            Integer httpStatus,
            Instant startedAt,
            Instant finishedAt,
            long durationMs,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            JsonNode requestPayload,
            JsonNode responsePayload,
            String errorType,
            String errorMessage,
            String finishReason,
            String outcomeCode,
            boolean responseTruncated,
            String phase,
            UUID parentCallId,
            boolean payloadAvailable
    ) {
    }

    record QualityIssueView(
            UUID id,
            UUID importJobId,
            UUID recognitionRunId,
            UUID recognitionCallId,
            String regionId,
            String issueType,
            String severity,
            double confidence,
            String sheetId,
            String sheetName,
            String address,
            String title,
            String description,
            String businessImpact,
            JsonNode evidence,
            JsonNode suggestedPatch,
            JsonNode inversePatch,
            boolean autoFixable,
            String status,
            String beforeSnapshotHash,
            String afterSnapshotHash,
            Instant createdAt
    ) {
    }
}
