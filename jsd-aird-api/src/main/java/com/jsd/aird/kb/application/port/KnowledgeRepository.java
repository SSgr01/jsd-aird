package com.jsd.aird.kb.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeRepository {

    void insertDocument(NewDocument document);

    void insertVersion(NewVersion version);

    Optional<DocumentRow> findDocument(UUID organizationId, UUID documentId);

    List<DocumentRow> listDocuments(UUID organizationId, String keyword, String status, String aiStatus,
                                     String scope, UUID categoryId, String lifecycleStatus, String reviewStatus,
                                     int page, int size);

    long countDocuments(UUID organizationId, String keyword, String status, String aiStatus,
                        String scope, UUID categoryId, String lifecycleStatus, String reviewStatus);

    List<CategoryRow> listCategories(UUID organizationId, String scope);

    Optional<CategoryRow> findCategory(UUID organizationId, UUID categoryId);

    Optional<CategoryRow> findDefaultCategory(UUID organizationId, String scope);

    CategoryRow createCategory(UUID organizationId, UUID actorId, String scope, String name, String description);

    CategoryRow renameCategory(UUID organizationId, UUID categoryId, String name, String description);

    void deleteCategory(UUID organizationId, UUID categoryId, UUID replacementCategoryId);

    void assignCategory(UUID organizationId, UUID documentId, UUID categoryId);

    void renameDocument(UUID organizationId, UUID documentId, String title);

    void deleteDocument(UUID organizationId, UUID documentId);

    Optional<VersionRow> findVersion(UUID organizationId, UUID versionId);

    Optional<ChunkAnchorRow> findChunkAnchor(UUID organizationId, UUID chunkId);

    List<VersionRow> listVersions(UUID organizationId, UUID documentId);

    void updateCurrentVersion(UUID organizationId, UUID documentId, int versionNo);

    void updateProcessing(UUID documentId, UUID versionId);

    void updateScanStatus(UUID documentId, String scanStatus);

    void replaceChunks(UUID documentId, UUID versionId, List<ChunkWrite> chunks);

    void replaceChunks(UUID documentId, UUID versionId, UUID parseRunId, List<ChunkWrite> chunks);

    void updateReviewedChunks(UUID parseRunId, List<ReviewedChunk> chunks);

    void rebuildTermStats(UUID organizationId);

    void startProcessingStep(UUID organizationId, UUID documentId, UUID versionId, String stepKey,
                             String provider, String model, String inputSha256);

    void finishProcessingStep(UUID organizationId, UUID versionId, String stepKey, String status,
                              String outputSha256, String errorMessage);

    void markReady(UUID documentId, UUID versionId, String parserVersion, String textSha256);

    void markFailed(UUID documentId, UUID versionId, String status, String error);

    void updateAiStatus(UUID organizationId, UUID documentId, String aiStatus);

    List<SearchRow> fullTextSearch(UUID organizationId, String query, boolean aiOnly, int limit);

    default List<SearchRow> fullTextSearch(UUID organizationId, String query, boolean aiOnly, List<UUID> scopeIds,
                                           List<UUID> categoryIds, int limit) {
        return fullTextSearch(organizationId, query, aiOnly, limit);
    }

    List<SearchRow> bm25Search(UUID organizationId, List<String> terms, boolean aiOnly, List<UUID> scopeIds,
                               List<UUID> categoryIds, int limit);

    List<SearchRow> vectorSearch(UUID organizationId, String vector, boolean aiOnly, int limit);

    default List<SearchRow> vectorSearch(UUID organizationId, String vector, boolean aiOnly, List<UUID> scopeIds,
                                         List<UUID> categoryIds, int limit) {
        return vectorSearch(organizationId, vector, aiOnly, limit);
    }

    default List<SearchRow> vectorSearch(UUID organizationId, String vector, boolean aiOnly, List<UUID> scopeIds,
                                         List<UUID> categoryIds, int limit, int dimension) {
        return vectorSearch(organizationId, vector, aiOnly, scopeIds, categoryIds, limit);
    }

    record CategoryRow(UUID id, String scope, String name, String description, int sortOrder, long documentCount) {
    }

    record NewDocument(UUID id, UUID organizationId, String title, String documentType, UUID actorId,
                       String scope, UUID categoryId) {
        public NewDocument(UUID id, UUID organizationId, String title, String documentType, UUID actorId) {
            this(id, organizationId, title, documentType, actorId, "INTERNAL", null);
        }
    }

    record NewVersion(UUID id, UUID documentId, int versionNo, UUID fileObjectId, String originalName,
                      String contentType, long size, String sha256) {
    }

    record DocumentRow(UUID id, UUID organizationId, String title, String documentType, String status,
                       String scanStatus, String aiStatus, int currentVersionNo, UUID currentVersionId,
                       String originalName, String contentType, long size, String sha256,
                       String parseError, java.time.Instant createdAt, java.time.Instant updatedAt,
                       String libraryScope, UUID categoryId, String categoryName, String lifecycleStatus,
                       String reviewStatus, int reviewRevision, UUID currentPublicationId,
                       Integer currentPublicationNo) {
        public DocumentRow(UUID id, UUID organizationId, String title, String documentType, String status,
                           String scanStatus, String aiStatus, int currentVersionNo, UUID currentVersionId,
                           String originalName, String contentType, long size, String sha256, String parseError,
                           java.time.Instant createdAt, java.time.Instant updatedAt) {
            this(id, organizationId, title, documentType, status, scanStatus, aiStatus, currentVersionNo,
                    currentVersionId, originalName, contentType, size, sha256, parseError, createdAt, updatedAt,
                    "INTERNAL", null, "未分类", "ACTIVE", "PENDING_REVIEW", 0, null, null);
        }
    }

    record VersionRow(UUID id, UUID documentId, int versionNo, UUID fileObjectId, String originalName,
                      String contentType, long size, String sha256, String status, String parserVersion,
                      String errorMessage, String reviewStatus, int reviewRevision,
                      boolean mediaProcessingConsent) {
        public VersionRow(UUID id, UUID documentId, int versionNo, UUID fileObjectId, String originalName,
                          String contentType, long size, String sha256, String status, String parserVersion,
                          String errorMessage) {
            this(id, documentId, versionNo, fileObjectId, originalName, contentType, size, sha256, status,
                    parserVersion, errorMessage, "PENDING_REVIEW", 0, false);
        }
    }

    record ChunkWrite(int chunkNo, Integer pageNo, String section, String content, String vector,
                      int tokenLength, String analyzerVersion, UUID parentChunkId, String embeddingModel,
                      List<TermFrequency> terms, String sheetName, String cellRange, String paragraphId,
                      List<Double> bbox, Long startTimeMs, Long endTimeMs) {
        public ChunkWrite(int chunkNo, Integer pageNo, String section, String content, String vector) {
            this(chunkNo, pageNo, section, content, vector, 0, "term-v1", null, null, List.of(),
                    null, null, null, List.of(), null, null);
        }
    }

    record ChunkAnchorRow(Integer pageNo, String sheetName, String cellRange, String paragraphId,
                          List<Double> bbox, Long startTimeMs, Long endTimeMs, String section) { }

    record ReviewedChunk(int chunkNo, String content, List<TermFrequency> terms) { }

    record TermFrequency(String term, int frequency) {
    }

    record SearchRow(UUID chunkId, UUID documentId, UUID versionId, String title, String originalName,
                     Integer pageNo, String section, String content, double score) {
    }
}
