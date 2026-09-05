package com.jsd.aird.mfg.upload.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.shared.api.AllowedActions;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductionUploadRepository {

    void insert(NewUpload upload);

    void attachAsyncJob(UUID uploadId, UUID asyncJobId);

    void queueRecognition(UUID uploadId, UUID selectedTemplateVersionId);

    void updateRecognitionProgress(UUID uploadId, String status, int progress, String stage);

    void completeRecognition(UUID uploadId, UUID selectedTemplateVersionId, String matchMode,
                             double matchScore, JsonNode structureSummary, JsonNode workbookSnapshot,
                             JsonNode recognitionResult);

    void failRecognition(UUID uploadId, String message);

    void replaceRecognitionFields(UUID uploadId, List<RecognitionField> fields);

    void updateRecognizedMetadata(UUID uploadId, String orderNo, String productName,
                                  String category, LocalDate manufactureDate);

    List<RecognitionFieldView> listRecognitionFields(UUID organizationId, UUID uploadId);

    record RecognitionFieldView(
            UUID id, String itemKey, String itemKind, String bindingId, String fieldCode,
            String dataPath, Integer recordIndex, JsonNode rawValue, JsonNode normalizedValue,
            JsonNode sourceLocator, double confidence, String reviewStatus
    ) {}

    record RecognitionField(
            UUID id, String itemKey, String itemKind, String bindingId, String fieldCode,
            String dataPath, Integer recordIndex, JsonNode rawValue, JsonNode normalizedValue,
            JsonNode sourceLocator, double confidence, String reviewStatus
    ) {}

    Optional<UploadView> find(UUID organizationId, UUID uploadId);

    /** Returns the latest active record for a file content hash. */
    Optional<UploadView> findActiveBySha256(UUID organizationId, String sha256);

    PageResult<UploadView> list(UUID organizationId, String keyword, String status, UUID projectId, int page, int size);

    int delete(UUID organizationId, UUID uploadId);

    Optional<UploadView> saveDraft(UUID organizationId, UUID actorId, UUID uploadId,
                                   JsonNode workbookSnapshot, long lockVersion);

    Optional<UploadView> updateMetadata(UUID organizationId, UUID actorId, UUID uploadId,
                                        String productionName, String orderNo, String productName,
                                        String category, LocalDate manufactureDate, long lockVersion);

    List<VersionView> versions(UUID organizationId, UUID uploadId);

    VersionView publish(UUID organizationId, UUID actorId, UUID uploadId);

    record NewUpload(
            UUID id,
            UUID organizationId,
            UUID fileId,
            String productionName,
            String orderNo,
            String productName,
            String category,
            LocalDate manufactureDate,
            UUID projectId,
            String projectName,
            UUID stageId,
            String stageName,
            UUID taskId,
            String taskName,
            String visibility,
            String sourceType,
            UUID selectedTemplateVersionId,
            UUID actorId
    ) {
    }

    record UploadView(
            UUID id,
            UUID fileId,
            String originalName,
            String contentType,
            long size,
            String sha256,
            String productionName,
            String orderNo,
            String productName,
            String category,
            LocalDate manufactureDate,
            UUID projectId,
            String projectName,
            UUID stageId,
            String stageName,
            UUID taskId,
            String taskName,
            String visibility,
            String sourceType,
            String status,
            UUID createdBy,
            Instant createdAt,
            JsonNode workbookSnapshot,
            int recognitionProgress,
            String currentStage,
            UUID selectedTemplateVersionId,
            String matchMode,
            Double templateMatchScore,
            JsonNode recognitionResult,
            JsonNode structureSummary,
            String failureMessage,
            long lockVersion,
            UUID updatedBy,
            Instant updatedAt
    ) {
        public List<String> getAllowedActions() {
            return AllowedActions.productionUpload(status);
        }
    }

    record VersionView(
            UUID id,
            UUID uploadId,
            int versionNo,
            JsonNode workbookSnapshot,
            long lockVersion,
            String changeType,
            String createdBy,
            Instant createdAt
    ) {
    }

    record PageResult<T>(List<T> items, long page, long size, long total, long totalPages) {
    }
}
