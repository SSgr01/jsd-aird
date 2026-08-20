package com.jsd.aird.spc.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpectrumRepository {

    List<CategoryRow> listCategories(UUID organizationId);
    Optional<CategoryRow> findCategory(UUID organizationId, UUID categoryId);
    Optional<CategoryRow> findCategoryByCode(UUID organizationId, String code);
    CategoryRow createCategory(NewCategory command);
    CategoryRow renameCategory(UUID organizationId, UUID categoryId, String name, String description, String analysisHint, String fieldsJson);
    void deleteCategory(UUID organizationId, UUID categoryId);

    Optional<ChartRow> findChart(UUID organizationId, UUID chartId);
    Optional<ChartRow> findChartByHash(UUID organizationId, String sha256);
    List<ChartRow> listCharts(UUID organizationId, String keyword, UUID categoryId, String status, int page, int size);
    long countCharts(UUID organizationId, String keyword, UUID categoryId, String status);
    ChartRow insertChart(NewChart command);
    void updateChart(UUID organizationId, UUID chartId, String title, String sampleName, String batchNo,
                     String testConditions, String metadataJson);
    void deleteChart(UUID organizationId, UUID chartId);

    UUID createSession(UUID organizationId, UUID userId, String title);
    List<SessionRow> listSessions(UUID organizationId, UUID userId, int limit);
    boolean sessionExists(UUID organizationId, UUID userId, UUID sessionId);
    void touchSession(UUID organizationId, UUID sessionId, String title);
    void renameSession(UUID organizationId, UUID userId, UUID sessionId, String title);
    void deleteSession(UUID organizationId, UUID userId, UUID sessionId);
    List<MessageRow> listMessages(UUID organizationId, UUID sessionId, int limit);
    MessageRow insertMessage(NewMessage command);

    AnalysisRow insertAnalysis(NewAnalysis command);
    Optional<AnalysisRow> findAnalysis(UUID organizationId, UUID analysisId);
    Optional<AnalysisRow> findAnalysisForUser(UUID organizationId, UUID userId, UUID analysisId);
    void updateAnalysisStarted(UUID organizationId, UUID analysisId, String stage);
    void updateAnalysisProgress(UUID organizationId, UUID analysisId, int progress, String stage);
    void updateAnalysisFinished(UUID organizationId, UUID analysisId, String status, String resultJson,
                                 String rawResponseJson, String warningJson, String errorMessage);
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
                    String batchNo, String testConditions, String metadataJson, int pageCount, UUID createdBy) { }

    record SessionRow(UUID id, String title, Instant createdAt, Instant updatedAt) { }

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
