package com.jsd.aird.data.api;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Read-only boundary for searching immutable, projected data-center record values and their source files. */
public interface DataSourceFileSearchFacade {

    DataDetailResult queryDetails(UUID organizationId, DataDetailQuery query, List<UUID> categoryIds,
                                  AccessScope accessScope);

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

    enum DataQueryMode {
        NONE,
        FILE_OVERVIEW,
        RECORD_DETAIL,
        FIELD_LOOKUP
    }

    enum DataFileScope {
        EXPLICIT,
        CONTEXT,
        AUTO
    }

    enum DataResolution {
        NOT_REQUESTED,
        RESOLVED,
        AMBIGUOUS,
        NOT_FOUND,
        FILE_REQUIRED
    }

    record DataDetailQuery(DataQueryMode mode, String fileName, DataFileScope fileScope, List<String> recordTerms,
                           List<String> fieldTerms, int recordLimit, int fieldsPerRecord, int valueLimit) {
        public DataDetailQuery(DataQueryMode mode, String fileName, List<String> recordTerms,
                               List<String> fieldTerms, int recordLimit, int fieldsPerRecord, int valueLimit) {
            this(mode, fileName, fileName == null || fileName.isBlank() ? DataFileScope.AUTO : DataFileScope.EXPLICIT,
                    recordTerms, fieldTerms, recordLimit, fieldsPerRecord, valueLimit);
        }

        public DataDetailQuery {
            mode = mode == null ? DataQueryMode.NONE : mode;
            fileName = fileName == null ? "" : fileName.strip();
            fileScope = fileScope == null ? (fileName.isBlank() ? DataFileScope.AUTO : DataFileScope.EXPLICIT)
                    : fileScope;
            recordTerms = recordTerms == null ? List.of() : recordTerms.stream()
                    .filter(value -> value != null && !value.isBlank()).map(String::strip).distinct().limit(8).toList();
            fieldTerms = fieldTerms == null ? List.of() : fieldTerms.stream()
                    .filter(value -> value != null && !value.isBlank()).map(String::strip).distinct().limit(8).toList();
            recordLimit = Math.min(20, Math.max(1, recordLimit));
            fieldsPerRecord = Math.min(20, Math.max(1, fieldsPerRecord));
            valueLimit = Math.min(50, Math.max(1, valueLimit));
        }

        public static DataDetailQuery none() {
            return new DataDetailQuery(DataQueryMode.NONE, "", DataFileScope.AUTO,
                    List.of(), List.of(), 5, 5, 50);
        }
    }

    record DataFileCandidate(String originalName) {
        public DataFileCandidate {
            originalName = originalName == null ? "" : originalName.strip();
        }
    }

    record DataDetailResult(DataQueryMode mode, DataResolution resolution,
                            UUID fileObjectId, UUID importJobId, String originalName,
                            long sourceRecordCount, long searchableValueCount,
                            List<SourceFileHit> hits, boolean truncated,
                            List<DataFileCandidate> candidates, boolean candidatesTruncated) {
        public DataDetailResult(DataQueryMode mode, UUID fileObjectId, UUID importJobId, String originalName,
                                long sourceRecordCount, long searchableValueCount,
                                List<SourceFileHit> hits, boolean truncated) {
            this(mode, importJobId == null ? DataResolution.NOT_FOUND : DataResolution.RESOLVED,
                    fileObjectId, importJobId, originalName, sourceRecordCount, searchableValueCount,
                    hits, truncated, List.of(), false);
        }

        public DataDetailResult {
            mode = mode == null ? DataQueryMode.NONE : mode;
            resolution = resolution == null ? (mode == DataQueryMode.NONE
                    ? DataResolution.NOT_REQUESTED : DataResolution.NOT_FOUND) : resolution;
            hits = hits == null ? List.of() : List.copyOf(hits);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static DataDetailResult empty(DataQueryMode mode) {
            return new DataDetailResult(mode, mode == null || mode == DataQueryMode.NONE
                    ? DataResolution.NOT_REQUESTED : DataResolution.NOT_FOUND,
                    null, null, "", 0, 0, List.of(), false, List.of(), false);
        }

        public boolean found() {
            return resolution == DataResolution.RESOLVED && importJobId != null;
        }

        public boolean requiresFileSelection() {
            return resolution == DataResolution.AMBIGUOUS && !candidates.isEmpty();
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
