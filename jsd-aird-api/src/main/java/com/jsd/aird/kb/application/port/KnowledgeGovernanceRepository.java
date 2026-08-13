package com.jsd.aird.kb.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.kb.domain.DocumentParser;

public interface KnowledgeGovernanceRepository {

    List<DuplicateMatch> exactMatches(UUID organizationId, String sha256);

    List<DuplicateMatch> possibleMatches(UUID organizationId, String normalizedStem, String documentType,
                                         List<UUID> objectRefIds);

    void updateMediaConsent(UUID organizationId, UUID versionId, boolean consent, UUID actorId);

    void updateSourceInfo(UUID organizationId, UUID documentId, UUID versionId, JsonNode sourceInfo);

    void updateDraftMetadata(UUID organizationId, UUID documentId, String title, String documentType,
                             String libraryScope, UUID categoryId);

    boolean hasMediaConsent(UUID organizationId, UUID versionId);

    ParseRunRow createParseRun(UUID organizationId, UUID documentId, UUID versionId, String status,
                               String parserVersion, String provider, String providerTaskId, String errorMessage,
                               JsonNode result, List<DocumentParser.TextBlock> blocks, List<ExtractedFieldWrite> fields);

    void updateParseRunStatus(UUID organizationId, UUID parseRunId, String status, String errorMessage);

    Optional<ReviewView> review(UUID organizationId, UUID documentId, UUID versionId);

    List<ReviewQueueItem> reviewQueue(UUID organizationId, String status, int limit);

    boolean saveReview(UUID organizationId, UUID actorId, ReviewUpdate update);

    PublicationRow publish(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                           int expectedRevision);

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

    List<ObjectRelation> publicationRelations(UUID organizationId, UUID publicationId);

    boolean updateAiUsage(UUID organizationId, UUID actorId, UUID publicationId, String action, String reason);

    List<String> tags(UUID organizationId, UUID documentId);

    List<ObjectRelation> relations(UUID organizationId, UUID documentId);

    void replaceTags(UUID organizationId, UUID actorId, UUID documentId, List<String> tags);

    void replaceRelations(UUID organizationId, UUID actorId, UUID documentId, List<UUID> objectRefIds);

    List<PageListItem> listPages(UUID organizationId);

    Optional<PageView> page(UUID organizationId, UUID pageId);

    boolean savePageDraft(UUID organizationId, UUID pageId, String title, String summary, int expectedRevision);

    PageVersionRow publishPage(UUID organizationId, UUID actorId, UUID pageId, int expectedRevision);

    record DuplicateMatch(UUID documentId, UUID versionId, int versionNo, String title, String originalName,
                          String documentType, String sha256, String normalizedStem, double similarity,
                          String lifecycleStatus, String reviewStatus) { }

    record ExtractedFieldWrite(String code, String name, String rawValue, String normalizedValue,
                               String sourceUnit, String standardUnit, double confidence,
                               boolean required, boolean conflict, JsonNode candidates) { }

    record ParseRunRow(UUID id, UUID documentId, UUID versionId, int runNo, String status,
                       String parserVersion, String provider, String providerTaskId, String errorMessage,
                       Instant createdAt) { }

    record ParseBlockView(UUID id, int blockNo, Integer pageNo, String sheetName, String cellRange,
                          String paragraphId, JsonNode bbox, Long startTimeMs, Long endTimeMs, String section,
                          String rawText, String normalizedText, String confirmedText, Double confidence,
                          String reviewStatus) { }

    record ExtractedFieldView(UUID id, String code, String name, String rawValue, String normalizedValue,
                              String confirmedValue, String sourceUnit, String standardUnit, Double confidence,
                              boolean required, boolean conflict, JsonNode candidates, String reviewStatus) { }

    record ParseIssueView(UUID id, UUID blockId, UUID fieldId, String code, String severity,
                          String message, String status, String resolution) { }

    record ObjectRelation(UUID id, String objectType, String externalId, String name, String sourceSystem) { }

    record ReviewView(UUID documentId, String title, String documentType, String libraryScope, UUID categoryId,
                      String categoryName, String lifecycleStatus, UUID versionId, int versionNo,
                      String originalName, String contentType, long size, String processingStatus,
                      String reviewStatus, int reviewRevision, boolean mediaProcessingConsent,
                      JsonNode sourceInfo,
                      ParseRunRow parseRun, List<ParseBlockView> blocks, List<ExtractedFieldView> fields,
                      List<ParseIssueView> issues,
                      List<String> tags, List<ObjectRelation> relations) { }

    record ReviewQueueItem(UUID documentId, String title, String documentType, UUID versionId, int versionNo,
                           String originalName, String processingStatus, String reviewStatus, int reviewRevision,
                           String categoryName, Instant updatedAt) { }

    record BlockUpdate(UUID id, String confirmedText, String reviewStatus) { }

    record FieldUpdate(UUID id, String confirmedValue, String reviewStatus) { }

    record ReviewUpdate(UUID documentId, UUID versionId, int expectedRevision, String title, String documentType,
                        String libraryScope, UUID categoryId, List<String> tags, List<UUID> objectRefIds,
                        List<BlockUpdate> blocks, List<FieldUpdate> fields) { }

    record PublicationRow(UUID id, UUID documentId, UUID versionId, UUID parseRunId, int publicationNo,
                          String status, String aiStatus, Instant publishedAt) { }

    record PageListItem(UUID id, UUID objectRefId, String objectType, String externalId, String objectName,
                        String title, String draftTitle, String summary, String draftSummary,
                        int draftRevision, Integer currentVersionNo,
                        long currentSourceCount, long availableSourceCount, boolean hasUpdates,
                        Instant updatedAt) { }

    record PageSource(UUID publicationId, UUID documentId, String documentTitle, UUID versionId,
                      int versionNo, boolean active, Instant publishedAt) { }

    record PageVersionRow(UUID id, int versionNo, String title, String summary, Instant publishedAt,
                          List<PageSource> sources) { }

    record PageView(PageListItem page, List<PageSource> availableSources, List<PageVersionRow> versions) { }
}
