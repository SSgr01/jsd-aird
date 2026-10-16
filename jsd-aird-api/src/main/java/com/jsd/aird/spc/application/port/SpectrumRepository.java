package com.jsd.aird.spc.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpectrumRepository {

    List<CategoryRow> listCategories(UUID organizationId);
    Optional<CategoryRow> findCategory(UUID organizationId, UUID categoryId);
    Optional<CategoryRow> findCategoryByCode(UUID organizationId, String code);
    void lockCategoryName(UUID organizationId, String name);
    boolean categoryNameExists(UUID organizationId, String name, UUID excludingCategoryId);
    void lockCategoryCode(UUID organizationId, String code);
    CategoryRow createCategory(NewCategory command);
    CategoryRow renameCategory(UUID organizationId, UUID categoryId, String name, String description, String analysisHint, String fieldsJson);
    void deleteCategory(UUID organizationId, UUID categoryId);

    Optional<ChartRow> findChart(UUID organizationId, UUID chartId);
    Optional<ChartRow> findChartByHash(UUID organizationId, String sha256);
    Optional<ChartRow> findChartByRequestId(UUID organizationId, UUID requestId);
    void lockChartHash(UUID organizationId, String sha256);
    void lockChartRequest(UUID organizationId, UUID requestId);
    List<ChartRow> listCharts(UUID organizationId, String keyword, UUID categoryId, String status, int page, int size);
    long countCharts(UUID organizationId, String keyword, UUID categoryId, String status);
    ChartRow insertChart(NewChart command);
    void updateChart(UUID organizationId, UUID chartId, String title, String sampleName, String batchNo,
                     String testConditions, String metadataJson);
    int assignChartCategory(UUID organizationId, UUID chartId, UUID categoryId);
    boolean hasProjectUsage(UUID organizationId, UUID chartId);
    void deleteChart(UUID organizationId, UUID chartId);
    void recordUploadAttempt(NewUploadAttempt attempt);
    List<UploadAttemptRow> listUploadAttempts(UUID organizationId, int limit);
    List<ChartAnalysisExportRow> listChartAnalysisExports(UUID organizationId, UUID chartId, int limit);

    UUID createSession(UUID organizationId, UUID userId, String title);
    List<SessionRow> listSessions(UUID organizationId, UUID userId, int limit);
    Optional<SessionRow> findSession(UUID organizationId, UUID userId, UUID sessionId);
    boolean sessionExists(UUID organizationId, UUID userId, UUID sessionId);
    void lockSessionForUpdate(UUID organizationId, UUID userId, UUID sessionId);
    void updateSessionChartContext(UUID organizationId, UUID userId, UUID sessionId,
                                  String chartIdsJson, String pageSelectionsJson);
    void touchSession(UUID organizationId, UUID sessionId, String title);
    void renameSession(UUID organizationId, UUID userId, UUID sessionId, String title);
    void deleteSession(UUID organizationId, UUID userId, UUID sessionId);
    List<MessageRow> listMessages(UUID organizationId, UUID sessionId, int limit);
    List<MessageRow> listRecentMessagesBeforeAnalysis(UUID organizationId, UUID analysisRunId, int limit);
    void deleteMessages(UUID organizationId, UUID sessionId, List<UUID> messageIds);
    MessageRow insertMessage(NewMessage command);

    AnalysisRow insertAnalysis(NewAnalysis command);
    Optional<AnalysisRow> findAnalysis(UUID organizationId, UUID analysisId);
    Optional<AnalysisRow> findAnalysisForUser(UUID organizationId, UUID userId, UUID analysisId);
    void updateAnalysisStarted(UUID organizationId, UUID analysisId, String stage);
    void updateAnalysisProgress(UUID organizationId, UUID analysisId, int progress, String stage);
    void updateAnalysisFinished(UUID organizationId, UUID analysisId, String status, String resultJson,
                                 String rawResponseJson, String warningJson, String errorMessage,
                                 String actualModel, String actualPromptVersion);
    long appendAnalysisEvent(UUID organizationId, UUID analysisId, String eventType, String payloadJson);
    List<AnalysisEventRow> listAnalysisEvents(UUID organizationId, UUID analysisId, long afterId, int limit);

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
                            String errorCode, String errorMessage, UUID createdBy) { }

    record UploadAttemptRow(UUID requestId, UUID categoryId, UUID chartId, String originalName,
                            long size, String status, String errorCode, String errorMessage, Instant createdAt) { }

    record ChartAnalysisExportRow(UUID id, String mode, String question, String chartIdsJson, String status,
                                  String resultJson, Instant createdAt, Instant completedAt) { }

    record SessionRow(UUID id, String title, Instant createdAt, Instant updatedAt,
                      String chartIdsJson, String pageSelectionsJson) { }

    record MessageRow(UUID id, UUID analysisRunId, String role, String content, String citationsJson,
                      String resultJson, String warningJson, Instant createdAt) { }

    record NewMessage(UUID id, UUID organizationId, UUID sessionId, UUID analysisRunId, String role,
                      String content, String citationsJson, String resultJson, String warningJson) { }

    record AnalysisRow(UUID id, UUID sessionId, String mode, String question, String chartIdsJson,
                       String pageSelectionsJson, String categoriesJson, String scenarioTemplate,
                       String status, int progress, String currentStage, String promptVersion, String model,
                       String resultJson, String rawResponseJson, String warningJson, String errorMessage,
                       Instant createdAt, Instant startedAt, Instant completedAt) { }

    record NewAnalysis(UUID id, UUID organizationId, UUID sessionId, String mode, String question,
                       String chartIdsJson, String pageSelectionsJson, String categoriesJson,
                       String scenarioTemplate, UUID createdBy, String promptVersion, String model) { }

    record AnalysisEventRow(long id, String eventType, String payloadJson, Instant createdAt) { }
}
