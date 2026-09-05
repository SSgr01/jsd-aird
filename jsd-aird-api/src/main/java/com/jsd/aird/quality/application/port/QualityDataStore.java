package com.jsd.aird.quality.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.quality.application.QualityDataService;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.api.AllowedActions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for the quality data application service. */
public interface QualityDataStore {
    record Category(UUID id, String businessType, String name, String description,
                    boolean systemDefault, int sortOrder, long recordCount) {
        public List<String> getAllowedActions() {
            return AllowedActions.category(systemDefault);
        }
    }

    record RecordView(UUID id, String businessType, UUID categoryId, String categoryName,
                      String businessNo, JsonNode data, UUID sourceFileId, String sourceFileName,
                      UUID projectId, String projectName, String stageName, String taskName,
                      String visibility, JsonNode workbookSnapshot, long lockVersion,
                      Instant createdAt, Instant updatedAt) {
        public List<String> getAllowedActions() {
            // Quality records currently expose no completed/reference marker;
            // the existing service permits soft deletion and re-checks it at command time.
            return List.of("DELETE");
        }
    }

    record VersionView(UUID id, UUID recordId, int versionNo, String businessNo, JsonNode data,
                       JsonNode workbookSnapshot, long lockVersion, String changeType,
                       String createdBy, Instant createdAt) {}

    record UploadView(UUID id, UUID fileId, UUID categoryId, String categoryName, String originalName,
                      String contentType, long size, String status, UUID generatedRecordId,
                      UUID projectId, String projectName, String stageName, String taskName,
                      String visibility, Instant createdAt) {
        public List<String> getAllowedActions() {
            return "DELETED".equalsIgnoreCase(status) ? List.of() : List.of("DELETE");
        }
    }

    List<Category> categories(UUID organizationId, String type);
    Category category(UUID organizationId, UUID categoryId);
    Category createCategory(UUID organizationId, UUID userId, String type, String name, String description);
    Category updateCategory(UUID organizationId, UUID categoryId, String name, String description);
    void deleteCategory(UUID organizationId, UUID categoryId);
    PageResponse<RecordView> records(UUID organizationId, String role, String type, UUID categoryId, String keyword, int page, int size);
    RecordView record(UUID organizationId, UUID recordId);
    RecordView record(UUID organizationId, String role, UUID recordId);
    List<VersionView> versions(UUID organizationId, UUID recordId);
    RecordView upsert(UUID organizationId, UUID userId, String type, UUID categoryId, UUID recordId,
                      String businessNo, JsonNode data, JsonNode workbookSnapshot, long lockVersion);
    void insertVersion(UUID organizationId, UUID userId, UUID recordId, int versionNo, String businessNo,
                        JsonNode data, JsonNode workbookSnapshot, long lockVersion, String changeType);
    void softDelete(UUID organizationId, List<UUID> ids);
    void softDeleteOne(UUID organizationId, UUID id);
    void move(UUID organizationId, List<UUID> ids, UUID categoryId);
    UUID insertDraft(UUID organizationId, UUID userId, String type, UUID categoryId, String businessNo,
                     JsonNode data, QualityDataService.UploadInput input);
    VersionView publish(UUID organizationId, UUID userId, UUID id);
    UploadView insertUpload(UUID organizationId, UUID userId, UUID recordId, QualityDataService.UploadInput input);
    Optional<UploadView> findUploadByHash(UUID organizationId, String sha256);
    Optional<String> findBusinessNoByField(UUID organizationId, String businessType, String field, String value);
    PageResponse<UploadView> uploads(UUID organizationId, String role, String keyword, String status, UUID projectId, int page, int size);
    void deleteUpload(UUID organizationId, UUID id);
}
