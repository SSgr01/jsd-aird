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

    ParseRunRow createParseRun(UUID organizationId, UUID documentId, UUID versionId, String status,
                               String parserVersion, String provider, String providerTaskId, String errorMessage,
                               JsonNode result, List<DocumentParser.TextBlock> blocks);
    void updateParseRunStatus(UUID organizationId, UUID parseRunId, String status, String errorMessage);
    Optional<ReviewView> review(UUID organizationId, UUID documentId, UUID versionId);
    List<ReviewQueueItem> reviewQueue(UUID organizationId, String status, int limit);
    boolean saveReview(UUID organizationId, UUID actorId, ReviewUpdate update);
    Optional<RevisionRow> createRevision(UUID organizationId, UUID actorId, UUID documentId,
                                         UUID basePublicationId, int expectedRevision,
                                         ReviewUpdate update);
    boolean reservePublication(UUID organizationId, UUID documentId, UUID versionId, UUID parseRunId,
                               int expectedRevision);
    PublicationRow publish(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                           UUID parseRunId, int expectedRevision);
    boolean reject(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                   int expectedRevision, String reason);
    boolean reserveReparse(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                           int expectedRevision);
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
                       String errorMessage, Instant createdAt) { }
    record ParseBlockView(UUID id, int blockNo, Integer pageNo, String sheetName, String cellRange,
                          String paragraphId, JsonNode bbox, Long startTimeMs, Long endTimeMs, String section,
                          String rawText, String normalizedText, String confirmedText, Double confidence,
                          String reviewStatus) { }
    record ParseIssueView(UUID id, UUID blockId, String code, String severity,
                          String message, String status, String resolution) { }
    record ReviewView(UUID documentId, String title, String libraryScope, UUID categoryId,
                      String categoryName, String lifecycleStatus, UUID versionId, int versionNo,
                      String originalName, String contentType, long size, String processingStatus,
                      String reviewStatus, int reviewRevision, JsonNode sourceInfo,
                      ParseRunRow parseRun, List<ParseBlockView> blocks,
                      List<ParseIssueView> issues, List<String> tags) { }
    record ReviewQueueItem(UUID documentId, String title, UUID versionId, int versionNo,
                           String originalName, String processingStatus, String reviewStatus,
                           int reviewRevision, String categoryName, Instant updatedAt) { }
    record BlockUpdate(UUID id, String confirmedText, String reviewStatus) { }
    record ReviewUpdate(UUID documentId, UUID versionId, int expectedRevision, String title,
                        String libraryScope, UUID categoryId, List<String> tags,
                        List<BlockUpdate> blocks) { }
    record RevisionRow(UUID documentId, UUID versionId, UUID parseRunId, int reviewRevision) { }
    record PublicationRow(UUID id, UUID documentId, UUID versionId, UUID parseRunId, int publicationNo,
                          String status, String aiStatus, Instant publishedAt) { }
}
