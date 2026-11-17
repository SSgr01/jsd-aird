package com.jsd.aird.spc.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.jsd.aird.iam.api.RecordDataScope;

public interface SpectrumRepository {
    void lockCategory(UUID organizationId, UUID categoryId);
    void lockChart(UUID organizationId, UUID chartId);

    List<CategoryRow> listCategories(UUID organizationId);
    Optional<CategoryRow> findCategory(UUID organizationId, UUID categoryId);
    Optional<CategoryRow> findCategoryByCode(UUID organizationId, String code);
    UUID ensureUncategorizedCategory(UUID organizationId, UUID createdBy);
    void lockCategoryName(UUID organizationId, String name);
    boolean categoryNameExists(UUID organizationId, String name, UUID excludingCategoryId);
    void lockCategoryCode(UUID organizationId, String code);
    CategoryRow createCategory(NewCategory command);
    CategoryRow renameCategory(UUID organizationId, UUID categoryId, String name, String description, String analysisHint, String fieldsJson);
    void deleteCategory(UUID organizationId, UUID categoryId, UUID replacementCategoryId);

    Optional<ChartRow> findChart(UUID organizationId, UUID chartId);
    Optional<ChartRow> findChartByHash(UUID organizationId, String sha256);
    Optional<ChartRow> findChartByRequestId(UUID organizationId, UUID requestId);
    void lockChartHash(UUID organizationId, String sha256);
    void lockChartRequest(UUID organizationId, UUID requestId);
    List<ChartRow> listCharts(UUID organizationId, String keyword, UUID categoryId, String status, int page, int size);
    long countCharts(UUID organizationId, String keyword, UUID categoryId, String status);
    default List<ChartRow> listCharts(UUID organizationId, String keyword, UUID categoryId, String status,
                                     int page, int size, RecordDataScope scope) {
        return listCharts(organizationId, keyword, categoryId, status, page, size);
    }
    default long countCharts(UUID organizationId, String keyword, UUID categoryId, String status,
                             RecordDataScope scope) {
        return countCharts(organizationId, keyword, categoryId, status);
    }
    ChartRow insertChart(NewChart command);
    void updateChart(UUID organizationId, UUID chartId, String title, String sampleName, String batchNo,
                     String testConditions, String metadataJson);
    int assignChartCategory(UUID organizationId, UUID chartId, UUID categoryId);
    boolean hasActiveProjectReference(UUID organizationId, UUID chartId);
    void deleteChart(UUID organizationId, UUID chartId);
    void recordUploadAttempt(NewUploadAttempt attempt);
    List<UploadAttemptRow> listUploadAttempts(UUID organizationId, int limit);
    int hideUploadAttempt(UUID organizationId, UUID requestId);

    record CategoryRow(UUID id, UUID organizationId, String code, String name, String description,
                       String analysisHint, String fieldsJson, int sortOrder, boolean systemCategory,
                       long chartCount) { }

    record NewCategory(UUID id, UUID organizationId, String code, String name, String description,
                       String analysisHint, String fieldsJson, int sortOrder, boolean systemCategory,
                       UUID createdBy) { }

    record ChartRow(UUID id, UUID organizationId, UUID categoryId, String categoryCode, String categoryName,
                    UUID fileObjectId, String title, String originalName, String contentType, long size,
                    String sha256, String sampleName, String batchNo, String testConditions, String metadataJson,
                    int pageCount, String status, Instant createdAt, Instant updatedAt) { }

    record NewChart(UUID id, UUID organizationId, UUID categoryId, UUID fileObjectId, String title,
                    String originalName, String contentType, long size, String sha256, String sampleName,
                    String batchNo, String testConditions, String metadataJson, int pageCount, UUID requestId,
                    UUID createdBy) { }

    record NewUploadAttempt(UUID organizationId, UUID requestId, UUID categoryId, UUID chartId,
                            UUID fileObjectId, String originalName, long size, String status,
                            String errorCode, String errorMessage, String uploadContextJson,
                            UUID parentRequestId, UUID createdBy) { }

    record UploadAttemptRow(UUID requestId, UUID categoryId, UUID chartId, String originalName,
                            UUID fileObjectId, long size, String status, String errorCode, String errorMessage,
                            String uploadContextJson, UUID parentRequestId, Instant createdAt) { }


}
