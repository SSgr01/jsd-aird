package com.jsd.aird.data.api;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Read-only boundary for searching immutable, projected data-center record values and their source files. */
public interface DataSourceFileSearchFacade {

    List<SourceFileMatch> searchSourceFiles(UUID organizationId, String query, List<UUID> categoryIds, int limit);

    default List<SourceFileMatch> searchSourceFiles(UUID organizationId, String query, List<UUID> categoryIds,
                                                    Set<UUID> allowedImportJobIds, int limit) {
        if (allowedImportJobIds != null && allowedImportJobIds.isEmpty()) return List.of();
        return searchSourceFiles(organizationId, query, categoryIds,
                allowedImportJobIds == null ? AccessScope.all() : AccessScope.selected(allowedImportJobIds), limit);
    }

    default List<SourceFileMatch> searchSourceFiles(UUID organizationId, String query, List<UUID> categoryIds,
                                                    AccessScope accessScope, int limit) {
        var scope = accessScope == null ? AccessScope.all() : accessScope;
        if (scope.deniesAll()) return List.of();
        if (!Set.of("ALL", "SELECTED").contains(scope.type())) return List.of();
        return searchSourceFiles(organizationId, query, categoryIds, limit).stream()
                .filter(file -> !"SELECTED".equals(scope.type()) || scope.targetIds().contains(file.importJobId()))
                .toList();
    }

    default List<SourceFileHit> search(UUID organizationId, String query, List<UUID> categoryIds, int limit) {
        return search(organizationId, query, categoryIds, AccessScope.all(), limit);
    }

    default List<SourceFileHit> search(UUID organizationId, String query, List<UUID> categoryIds,
                                       AccessScope accessScope, int limit) {
        return searchSourceFiles(organizationId, query, categoryIds, accessScope, limit).stream()
                .flatMap(file -> file.hits().stream().map(hit -> new SourceFileHit(
                        hit.hitId(), file.fileObjectId(), file.importJobId(), hit.rowNumber(), hit.columnName(),
                        file.originalName(), hit.snippet(), hit.score(),
                        "DATA_CENTER:" + (hit.sheetName() == null ? "" : hit.sheetName())
                                + ":" + (hit.cellAddress() == null ? "" : hit.cellAddress()),
                        hit.recordKey(), hit.fieldCode(), hit.fieldName(), hit.fieldValue(), hit.unit(),
                        hit.valueType(), hit.sheetName(), hit.cellAddress())))
                .toList();
    }

    record SourceFileMatch(UUID fileObjectId, UUID importJobId, String originalName, String contentType,
                           long size, Instant updatedAt, List<Hit> hits, List<String> matchedTerms) { }

    record Hit(UUID hitId, String snippet, double score, String sheetName, Integer rowNumber,
               String columnName, String cellAddress, String recordKey, String fieldCode,
               String fieldName, String fieldValue, String unit, String valueType) {
        public Hit(UUID hitId, String snippet, double score, String sheetName, Integer rowNumber,
                   String columnName, String cellAddress) {
            this(hitId, snippet, score, sheetName, rowNumber, columnName, cellAddress,
                    null, null, null, null, null, null);
        }
    }

    record SourceFileHit(UUID hitId, UUID fileObjectId, UUID importJobId, Integer rowNumber,
                         String columnName, String originalName, String content, double score,
                         String sourceLocator, String recordKey, String fieldCode, String fieldName,
                         String fieldValue, String unit, String valueType, String sheetName,
                         String cellAddress) {
        public SourceFileHit(UUID hitId, UUID fileObjectId, UUID importJobId, Integer rowNumber,
                             String columnName, String originalName, String content, double score,
                             String sourceLocator) {
            this(hitId, fileObjectId, importJobId, rowNumber, columnName, originalName, content, score,
                    sourceLocator, null, null, null, null, null, null, null, null);
        }
    }

    record AccessScope(String type, UUID actorId, Set<UUID> targetIds) {
        public AccessScope {
            type = type == null || type.isBlank() ? "ALL" : type.strip().toUpperCase(Locale.ROOT);
            targetIds = targetIds == null ? Set.of() : Set.copyOf(targetIds);
        }

        public static AccessScope all() {
            return new AccessScope("ALL", null, Set.of());
        }

        public static AccessScope selected(Set<UUID> importJobIds) {
            return new AccessScope("SELECTED", null, importJobIds);
        }

        public boolean deniesAll() {
            return (Set.of("SELECTED", "CATEGORY", "PROJECT").contains(type) && targetIds.isEmpty())
                    || "SELF".equals(type) && actorId == null
                    || "ASSIGNED".equals(type)
                    || !Set.of("ALL", "SELECTED", "CATEGORY", "PROJECT", "SELF", "ASSIGNED").contains(type);
        }
    }
}
