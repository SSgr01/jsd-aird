package com.jsd.aird.tpl.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.tpl.domain.TemplateFormat;

public interface TemplateImportRepository {

    void enqueue(NewImportJob job);

    Optional<ImportJobView> find(UUID organizationId, UUID importJobId);

    Optional<ImportJobView> findLatestForVersion(UUID organizationId, UUID versionId);

    Optional<UUID> findGeneratedVersionId(UUID importJobId);

    List<ImportJobView> list(UUID organizationId);

    void complete(UUID importJobId, OfficeStructureParser.ParseResult result);

    void updateProgress(UUID importJobId, int progress, String stage);

    UUID startRecognitionRun(
            UUID importJobId, String scope, int structureVersion, int snapshotFormatVersion, int regionCount
    );

    void saveRecognitionCall(UUID recognitionRunId, RecognitionModelClient.CallTrace trace);

    void completeRecognitionRun(UUID recognitionRunId, String status);

    int purgeExpiredRecognitionPayloads();

    void replaceModelSuggestions(UUID importJobId, UUID recognitionRunId, RecognitionModelClient.RecognitionBatch batch);

    void replaceRuleSuggestions(UUID importJobId, UUID recognitionRunId, RecognitionModelClient.RecognitionBatch batch);

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
            JsonNode snapshotFragment
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
                    "WORKBOOK", null, null, null);
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
            String lastError,
            Instant createdAt,
            int suggestionCount,
            int pendingSuggestionCount,
            List<IssueView> issues
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
            String source,
            String suggestionType,
            JsonNode payload,
            double confidence,
            JsonNode evidence,
            String decision,
            String provider,
            String model,
            String promptVersion,
            Instant createdAt
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
