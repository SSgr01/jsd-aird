package com.jsd.aird.kb.application.port;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface KnowledgeRepository {

    void insertDocument(NewDocument document);
    void insertVersion(NewVersion version);
    Optional<DocumentRow> findDocument(UUID organizationId, UUID documentId);
    List<DocumentRow> listDocuments(UUID organizationId, String keyword, String status, String aiStatus,
                                    String scope, UUID categoryId, String lifecycleStatus, String reviewStatus,
                                    int page, int size);
    default List<DocumentRow> listDocuments(UUID organizationId, String keyword, String status, String aiStatus,
                                            String scope, UUID categoryId, String lifecycleStatus, String reviewStatus,
                                            Set<UUID> allowedDocumentIds, int page, int size) {
        if (allowedDocumentIds != null && allowedDocumentIds.isEmpty()) return List.of();
        return listDocuments(organizationId, keyword, status, aiStatus, scope, categoryId, lifecycleStatus,
                reviewStatus, page, size).stream()
                .filter(row -> allowedDocumentIds == null || allowedDocumentIds.contains(row.id())).toList();
    }
    long countDocuments(UUID organizationId, String keyword, String status, String aiStatus,
                        String scope, UUID categoryId, String lifecycleStatus, String reviewStatus);
    default long countDocuments(UUID organizationId, String keyword, String status, String aiStatus,
                                String scope, UUID categoryId, String lifecycleStatus, String reviewStatus,
                                Set<UUID> allowedDocumentIds) {
        if (allowedDocumentIds != null && allowedDocumentIds.isEmpty()) return 0;
        return countDocuments(organizationId, keyword, status, aiStatus, scope, categoryId,
                lifecycleStatus, reviewStatus);
    }

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
    void updateVersionParsingPolicy(UUID organizationId, UUID versionId, String ocrMode,
                                    boolean allowAgentFallback);
    void updateVersionParseOutcome(UUID organizationId, UUID versionId, Boolean effectiveOcr,
                                   String parserMode, String parserMetadataJson);
    void updateProcessing(UUID documentId, UUID versionId);
    void updateScanStatus(UUID documentId, String scanStatus);
    void replaceChunks(UUID documentId, UUID versionId, UUID parseRunId, List<ChunkWrite> chunks);
    void rebuildTermStats(UUID organizationId);

    void startProcessingStep(UUID organizationId, UUID documentId, UUID versionId, UUID parseRunId,
                             String stepKey, String provider, String model, String inputSha256);
    void finishProcessingStep(UUID organizationId, UUID versionId, UUID parseRunId, String stepKey,
                              String status, String outputSha256, String errorMessage);
    void attachProcessingSteps(UUID organizationId, UUID versionId, UUID parseRunId);
    void markReady(UUID documentId, UUID versionId, String parserVersion, String textSha256);
    void markFailed(UUID documentId, UUID versionId, String status, String error);

    boolean isAiApproved(UUID organizationId, UUID documentId);
    List<ChunkEmbeddingRow> chunksForEmbedding(UUID organizationId, UUID documentId, UUID parseRunId);
    void updateChunkEmbedding(UUID organizationId, UUID chunkId, String vector, String embeddingModel);
    void clearDocumentEmbeddings(UUID organizationId, UUID documentId);
    void cancelPendingVectorJobs(UUID organizationId, UUID documentId);

    List<SearchRow> fullTextSearch(UUID organizationId, String query, boolean aiOnly, int limit);
    default List<SearchRow> fullTextSearch(UUID organizationId, String query, boolean aiOnly,
                                           List<UUID> categoryIds, int limit) {
        return fullTextSearch(organizationId, query, aiOnly, limit);
    }
    default List<SearchRow> fullTextSearch(UUID organizationId, String query, boolean aiOnly,
                                           List<UUID> categoryIds, java.util.Set<UUID> allowedDocumentIds, int limit) {
        if (allowedDocumentIds != null && allowedDocumentIds.isEmpty()) return List.of();
        return fullTextSearch(organizationId, query, aiOnly, categoryIds, limit).stream()
                .filter(row -> allowedDocumentIds == null || allowedDocumentIds.contains(row.documentId())).toList();
    }
    List<SearchRow> bm25Search(UUID organizationId, List<AnalyzedTerm> terms, boolean aiOnly,
                               List<UUID> categoryIds, int limit);
    default List<SearchRow> bm25Search(UUID organizationId, List<AnalyzedTerm> terms, boolean aiOnly,
                                      List<UUID> categoryIds, java.util.Set<UUID> allowedDocumentIds, int limit) {
        if (allowedDocumentIds != null && allowedDocumentIds.isEmpty()) return List.of();
        return bm25Search(organizationId, terms, aiOnly, categoryIds, limit).stream()
                .filter(row -> allowedDocumentIds == null || allowedDocumentIds.contains(row.documentId())).toList();
    }
    List<SearchRow> vectorSearch(UUID organizationId, String vector, boolean aiOnly, int limit);
    default List<SearchRow> phraseSearch(UUID organizationId, List<String> phrases, boolean aiOnly,
                                        List<UUID> categoryIds, int limit) {
        if (phrases == null) return List.of();
        return phrases.stream().flatMap(phrase -> fullTextSearch(organizationId, phrase, aiOnly,
                        categoryIds, limit).stream()).distinct().limit(limit).toList();
    }
    default List<SearchRow> vectorSearch(UUID organizationId, String vector, boolean aiOnly,
                                         List<UUID> categoryIds, int limit) {
        return vectorSearch(organizationId, vector, aiOnly, limit);
    }
    default List<SearchRow> vectorSearch(UUID organizationId, String vector, boolean aiOnly,
                                         List<UUID> categoryIds, int limit, int dimension) {
        return vectorSearch(organizationId, vector, aiOnly, categoryIds, limit);
    }

    record CategoryRow(UUID id, String scope, String name, String description, int sortOrder, long documentCount) { }
    record NewDocument(UUID id, UUID organizationId, String title, UUID actorId, String scope, UUID categoryId) { }
    record NewVersion(UUID id, UUID documentId, int versionNo, UUID fileObjectId, String originalName,
                      String contentType, long size, String sha256, String ocrMode,
                      boolean allowAgentFallback) { }
    record DocumentRow(UUID id, UUID organizationId, String title, String status, String scanStatus,
                       String aiStatus, int currentVersionNo, UUID currentVersionId, String originalName,
                       String contentType, long size, String sha256, String parseError,
                       java.time.Instant createdAt, java.time.Instant updatedAt, String libraryScope,
                       UUID categoryId, String categoryName, String lifecycleStatus, String reviewStatus,
                       int reviewRevision, UUID currentPublicationId, Integer currentPublicationNo) { }
    record VersionRow(UUID id, UUID documentId, int versionNo, UUID fileObjectId, String originalName,
                      String contentType, long size, String sha256, String status, String parserVersion,
                      String errorMessage, String reviewStatus, int reviewRevision, String ocrMode,
                      boolean allowAgentFallback, Boolean effectiveOcr, String parserMode,
                      String parserMetadataJson) { }
    record ChunkWrite(String chunkKey, String parentKey, String chunkRole, int chunkNo, Integer pageNo,
                      String section, String content, String vector, int tokenLength, int modelTokenLength,
                      String analyzerVersion, String embeddingModel, List<TermFrequency> terms,
                      List<String> headingPath, List<UUID> reviewNodeIds, List<UUID> sourceNodeKeys,
                      String sourceAnchorJson, String sourceAnchorsJson, String relationsJson,
                      String sheetName, String cellRange, String paragraphId, List<Double> bbox,
                      Long startTimeMs, Long endTimeMs) { }
    record ChunkEmbeddingRow(UUID id, String content) { }
    record ChunkAnchorRow(Integer pageNo, String sheetName, String cellRange, String paragraphId,
                          List<Double> bbox, Long startTimeMs, Long endTimeMs, String section,
                          String primaryAnchorJson, String anchorsJson, List<UUID> reviewNodeIds,
                          List<UUID> sourceNodeKeys) { }
    record TermFrequency(String term, int frequency) { }
    record AnalyzedTerm(String analyzerVersion, String term) { }
    record SearchRow(UUID chunkId, UUID documentId, UUID versionId, String title, String originalName,
                     Integer pageNo, String section, String content, double score, int chunkNo) {
        public SearchRow(UUID chunkId, UUID documentId, UUID versionId, String title, String originalName,
                         Integer pageNo, String section, String content, double score) {
            this(chunkId, documentId, versionId, title, originalName, pageNo, section, content, score, -1);
        }
    }
}
