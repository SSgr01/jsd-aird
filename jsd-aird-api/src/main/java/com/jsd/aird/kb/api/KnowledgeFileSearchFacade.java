package com.jsd.aird.kb.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface KnowledgeFileSearchFacade {

    List<FileMatch> searchFiles(UUID organizationId, String query, List<UUID> scopeIds,
                                List<UUID> categoryIds, int limit);

    record FileMatch(UUID fileObjectId, UUID logicalDocumentId, UUID fileVersionId, String title,
                     String originalName, String contentType, long size, int version,
                     List<String> tags, List<RelatedObject> relatedObjects, Instant updatedAt,
                     List<Hit> hits) { }

    record RelatedObject(UUID id, String objectType, String externalId, String name) { }

    record Hit(UUID hitId, String snippet, double score, Integer pageNo, String sheetName,
               String cellRange, String paragraphId, List<Double> bbox, Long startTimeMs,
               Long endTimeMs, String section) { }
}
