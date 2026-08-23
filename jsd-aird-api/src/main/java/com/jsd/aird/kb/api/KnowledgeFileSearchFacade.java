package com.jsd.aird.kb.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface KnowledgeFileSearchFacade {

    List<FileMatch> searchFiles(UUID organizationId, String query, List<UUID> scopeIds,
                                List<UUID> categoryIds, int limit);

    default List<FileMatch> searchFiles(UUID organizationId, String query, List<UUID> scopeIds,
                                        List<UUID> categoryIds, Set<UUID> allowedDocumentIds, int limit) {
        if (allowedDocumentIds != null && allowedDocumentIds.isEmpty()) return List.of();
        return searchFiles(organizationId, query, scopeIds, categoryIds, limit).stream()
                .filter(file -> allowedDocumentIds == null || allowedDocumentIds.contains(file.logicalDocumentId()))
                .toList();
    }

    record FileMatch(UUID fileObjectId, UUID logicalDocumentId, UUID fileVersionId, String title,
                     String originalName, String contentType, long size, int version,
                     List<String> tags, Instant updatedAt,
                     List<Hit> hits, List<String> matchedTerms) { }

    record Hit(UUID hitId, String snippet, double score, Integer pageNo, String sheetName,
               String cellRange, String paragraphId, List<Double> bbox, Long startTimeMs,
               Long endTimeMs, String section, JsonNode anchor, List<JsonNode> anchors,
               List<UUID> reviewNodeIds, List<UUID> sourceNodeKeys) { }
}
