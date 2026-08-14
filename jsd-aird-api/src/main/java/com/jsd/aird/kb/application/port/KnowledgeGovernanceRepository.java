package com.jsd.aird.kb.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.kb.domain.DocumentParser;

public interface KnowledgeGovernanceRepository {

    List<DuplicateMatch> exactMatches(UUID organizationId, String sha256);
    List<DuplicateMatch> possibleMatches(UUID organizationId, String normalizedStem, UUID categoryId);
    void updateSourceInfo(UUID organizationId, UUID documentId, UUID versionId, JsonNode sourceInfo);
    void updateDraftMetadata(UUID organizationId, UUID documentId, String title,
                             String libraryScope, UUID categoryId);

    ParseRunRow createParseRun(UUID organizationId, UUID actorId, UUID documentId, UUID versionId, String status,
                               String parserVersion, String provider, String providerTaskId, String errorMessage,
                               JsonNode diagnosticResult, List<DocumentParser.TextBlock> blocks,
                               List<DocumentParser.SourceTable> sourceTables);
    void updateParseRunStatus(UUID organizationId, UUID parseRunId, String status, String errorMessage);
    Optional<ReviewView> review(UUID organizationId, UUID documentId, UUID versionId);
    Optional<PublishedContentView> publishedContent(UUID organizationId, UUID documentId, UUID publicationId);
    Optional<TableWindow> reviewTableWindow(UUID organizationId, UUID reviewRevisionId, UUID sourceTableId,
                                            int rowOffset, int rowLimit, int columnOffset, int columnLimit);
    Optional<TableWindow> publishedTableWindow(UUID organizationId, UUID publicationId, UUID sourceTableId,
                                               int rowOffset, int rowLimit, int columnOffset, int columnLimit);
    boolean saveTableReview(UUID organizationId, UUID actorId, UUID reviewRevisionId, int expectedLockVersion,
                            UUID sourceTableId, List<CellPatch> patches, List<RowState> rows);
    List<LargeTableRow> largeTableRows(UUID organizationId, UUID reviewRevisionId);
    List<ReviewQueueItem> reviewQueue(UUID organizationId, String status, int limit);
    boolean saveReview(UUID organizationId, UUID actorId, ReviewUpdate update);
    Optional<RevisionRow> createRevision(UUID organizationId, UUID actorId, UUID documentId,
                                         UUID basePublicationId);
    boolean reservePublication(UUID organizationId, UUID documentId, UUID versionId, UUID reviewRevisionId,
                               int expectedLockVersion, UUID basePublicationId, String confirmedText);
    PublicationRow publish(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                           UUID reviewRevisionId, int expectedLockVersion);
    void failRevision(UUID organizationId, UUID reviewRevisionId, String failureReason);
    boolean reject(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                   UUID reviewRevisionId, int expectedLockVersion, String reason);
    boolean reserveReparse(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                           UUID reviewRevisionId, int expectedLockVersion);
    boolean hasPublication(UUID organizationId, UUID documentId);
    boolean updateLifecycle(UUID organizationId, UUID actorId, UUID documentId, String status, String reason);

    Optional<PublicationRow> currentPublication(UUID organizationId, UUID documentId);
    Optional<PublicationRow> currentPublicationById(UUID organizationId, UUID publicationId);
    List<PublicationRow> publications(UUID organizationId, UUID documentId);
    List<String> publicationTags(UUID organizationId, UUID publicationId);
    boolean updateAiUsage(UUID organizationId, UUID actorId, UUID documentId, String action, String reason);
    List<String> tags(UUID organizationId, UUID documentId);
    void replaceTags(UUID organizationId, UUID actorId, UUID documentId, List<String> tags);

    record DuplicateMatch(UUID documentId, UUID versionId, int versionNo, String title, String originalName,
                          String sha256, String normalizedStem, double similarity,
                          String lifecycleStatus, String reviewStatus) { }
    record ParseRunRow(UUID id, UUID documentId, UUID versionId, int runNo, String status,
                       String errorMessage, Instant createdAt, JsonNode sourceDocument, int schemaVersion) { }
    record SourceNodeView(UUID sourceNodeKey, int nodeNo, String nodeType, String rawText,
                          JsonNode sourceAnchor, JsonNode confidence) { }
    record ParseIssueView(UUID id, List<UUID> sourceNodeKeys, String code, String severity,
                          String message, String status, String resolution) { }
    record ReviewRevisionView(UUID id, UUID parseRunId, int revisionNo, int lockVersion,
                              UUID basePublicationId, JsonNode confirmedDocument,
                              List<UUID> excludedReviewNodeIds, String status,
                              String failureReason, Instant updatedAt) { }
    record ReviewView(UUID documentId, String title, String libraryScope, UUID categoryId,
                      String categoryName, String lifecycleStatus, UUID versionId, int versionNo,
                      UUID fileObjectId, String originalName, String contentType, long size,
                      String processingStatus, String reviewStatus, JsonNode sourceInfo,
                      ParseRunRow parseRun, List<SourceNodeView> sourceNodes,
                      ReviewRevisionView reviewRevision, List<ParseIssueView> issues,
                      List<String> tags) { }
    record PublishedContentView(PublicationRow publication, UUID fileObjectId, String originalName,
                                String contentType, long size, JsonNode sourceDocument,
                                List<SourceNodeView> sourceNodes, JsonNode confirmedDocument,
                                List<UUID> excludedReviewNodeIds) { }
    record TableCellView(int rowNo, int columnNo, String value, boolean patched) { }
    record TableWindow(UUID sourceTableId, String sheetKey, String sheetName, int rowCount,
                       int columnCount, int nonEmptyCount, int rowOffset, int columnOffset,
                       List<TableCellView> cells, List<Integer> excludedRows, List<Integer> headerRows) { }
    record CellPatch(int rowNo, int columnNo, String value) { }
    record RowState(int rowNo, boolean excluded, boolean header) { }
    record LargeTableRow(UUID sourceTableId, String sheetName, int rowNo, String cellRange,
                         String projectedText) { }
    record ReviewQueueItem(UUID documentId, String title, UUID versionId, int versionNo,
                           String originalName, String processingStatus, String reviewStatus,
                           int reviewRevision, String categoryName, Instant updatedAt) { }
    record IssueAction(UUID issueId, String status, String resolution) { }
    record ReviewUpdate(UUID documentId, UUID versionId, UUID reviewRevisionId, int expectedLockVersion,
                        UUID basePublicationId, String title, String libraryScope, UUID categoryId,
                        List<String> tags, JsonNode confirmedDocument, List<UUID> excludedReviewNodeIds,
                        List<IssueAction> issueActions) { }
    record RevisionRow(UUID documentId, UUID versionId, UUID reviewRevisionId, int revisionNo,
                       int lockVersion) { }
    record PublicationRow(UUID id, UUID documentId, UUID versionId, UUID parseRunId,
                          UUID reviewRevisionId, int publicationNo, String status,
                          String aiStatus, Instant publishedAt) { }
}
