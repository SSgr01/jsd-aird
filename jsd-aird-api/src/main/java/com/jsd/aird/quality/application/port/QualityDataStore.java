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
    record DataScopeFilter(String type, UUID actorId, java.util.Set<UUID> targetIds) {
        public DataScopeFilter {
            type = type == null ? "ALL" : type;
            targetIds = targetIds == null ? java.util.Set.of() : java.util.Set.copyOf(targetIds);
        }
        public static DataScopeFilter all() { return new DataScopeFilter("ALL", null, java.util.Set.of()); }
    }
    void lockCategory(UUID organizationId,UUID categoryId);

    record Category(UUID id, String businessType, String name, String description,
                    boolean systemDefault, int sortOrder, long recordCount) {
        public String getRevision(){return com.jsd.aird.shared.json.RevisionToken.of(id,businessType,name,description,systemDefault,sortOrder);}
        public List<String> getAllowedActions() {
            return AllowedActions.category(systemDefault);
        }
    }

    record RecordView(UUID id, String businessType, UUID categoryId, String categoryName,
                      String businessNo, String displayName, JsonNode data, UUID sourceFileId, String sourceFileName,
                      UUID projectId, String projectName, UUID stageId, String stageName,
                      UUID taskId, String taskName,
                      String visibility, JsonNode workbookSnapshot, long lockVersion,
                      Instant createdAt, Instant updatedAt, String workflowStatus) {
        public String getDocumentFormat() {
            var configured = data == null ? "" : data.path("fileType").asText("").toUpperCase(java.util.Locale.ROOT);
            if ("EXCEL".equals(configured) || "XLSX".equals(configured)) return "EXCEL";
            if ("WORD".equals(configured) || "DOCX".equals(configured)) return "WORD";
            if ("CSV".equals(configured) || "PDF".equals(configured)) return configured;
            if ("IMAGE".equals(configured) || "PHOTO".equals(configured) || "图片".equals(configured)) return "IMAGE";
            var name = sourceFileName == null ? "" : sourceFileName.toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) return "EXCEL";
            if (name.endsWith(".docx") || name.endsWith(".doc")) return "WORD";
            if (name.endsWith(".csv")) return "CSV";
            if (name.endsWith(".pdf")) return "PDF";
            if (name.matches(".*\\.(jpg|jpeg|png|tif|tiff)$")) return "IMAGE";
            return null;
        }
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
                      UUID projectId, String projectName, UUID stageId, String stageName,
                      UUID taskId, String taskName,
                      String visibility, String errorMessage, Instant createdAt) {
        public List<String> getAllowedActions() {
            return "DELETED".equalsIgnoreCase(status) ? List.of() : List.of("DELETE");
        }
    }

    List<Category> categories(UUID organizationId, String type);
    default List<Category> categories(UUID organizationId, String role, String type, DataScopeFilter scope) {
        return categories(organizationId, type);
    }
    UUID ensureUncategorizedCategory(UUID organizationId, String type, UUID createdBy);
    Category category(UUID organizationId, UUID categoryId);
    Category createCategory(UUID organizationId, UUID userId, String type, String name, String description);
    Category updateCategory(UUID organizationId, UUID categoryId, String name, String description);
    void deleteCategory(UUID organizationId, UUID categoryId, UUID replacementCategoryId);
    PageResponse<RecordView> records(UUID organizationId, String role, String type, UUID categoryId, String keyword, UUID projectId, String status, String format, int page, int size);
    default PageResponse<RecordView> records(UUID organizationId, String role, String type, UUID categoryId, String keyword,
                                             UUID projectId, String status, String format, int page, int size,
                                             DataScopeFilter scope) {
        return records(organizationId, role, type, categoryId, keyword, projectId, status, format, page, size);
    }
    default PageResponse<RecordView> records(UUID organizationId, String role, String type, UUID categoryId, String keyword, UUID projectId, String status, int page, int size) {
        return records(organizationId, role, type, categoryId, keyword, projectId, status, null, page, size);
    }
    default PageResponse<RecordView> records(UUID organizationId, String role, String type, UUID categoryId, String keyword, UUID projectId, int page, int size) {
        return records(organizationId, role, type, categoryId, keyword, projectId, null, page, size);
    }
    Optional<RecordView> recordByBusinessNo(UUID organizationId, String role, String type, String businessNo);
    RecordView record(UUID organizationId, UUID recordId);
    RecordView record(UUID organizationId, String role, UUID recordId);
    List<VersionView> versions(UUID organizationId, UUID recordId);
    RecordView upsert(UUID organizationId, UUID userId, String type, UUID categoryId, UUID recordId,
                      String businessNo, JsonNode data, JsonNode workbookSnapshot,
                      UUID projectId, String projectName, UUID stageId, String stageName,
                      UUID taskId, String taskName, long lockVersion);
    RecordView rename(UUID organizationId, UUID userId, UUID recordId, String displayName,
                      String businessNo, UUID projectId, String projectName, UUID stageId, String stageName,
                      UUID taskId, String taskName, JsonNode dataPatch, long lockVersion);
    void insertVersion(UUID organizationId, UUID userId, UUID recordId, int versionNo, String businessNo,
                        JsonNode data, JsonNode workbookSnapshot, long lockVersion, String changeType);
    void lockRecord(UUID organizationId, UUID id);
    void softDeleteOne(UUID organizationId, UUID id, long expectedVersion);
    void moveOne(UUID organizationId, UUID id, long expectedVersion, UUID categoryId);
    UUID insertDraft(UUID organizationId, UUID userId, String type, UUID categoryId, String businessNo,
                     JsonNode data, QualityDataService.UploadInput input, JsonNode workbookSnapshot);
    VersionView publish(UUID organizationId, UUID userId, UUID id, long expectedVersion);
    RecordView createRevision(UUID organizationId, UUID userId, UUID id, long expectedVersion);
    UploadView insertUpload(UUID organizationId, UUID userId, UUID recordId, QualityDataService.UploadInput input);
    Optional<UploadView> findUpload(UUID organizationId, UUID uploadId);
    UploadView updateUploadStatus(UUID organizationId, UUID uploadId, String status,
                                  UUID generatedRecordId, String errorMessage);
    Optional<String> findBusinessNoByField(UUID organizationId, String businessType, String field, String value);
    PageResponse<UploadView> uploads(UUID organizationId, UUID userId, String role, String keyword, String status, UUID projectId, int page, int size);
    void deleteUpload(UUID organizationId, UUID id);
}
