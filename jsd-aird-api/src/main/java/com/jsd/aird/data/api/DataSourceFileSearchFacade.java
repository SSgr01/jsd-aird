package com.jsd.aird.data.api;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Read-only boundary for searching immutable, projected data-center record values and their source files. */
public interface DataSourceFileSearchFacade {

    UUID UNCATEGORIZED_SCOPE_ID = new UUID(0L, 0L);

    default DataDetailResult queryDetails(UUID organizationId, DataDetailQuery query, List<UUID> categoryIds,
                                           AccessScope accessScope) {
        return DataDetailResult.empty(query == null ? DataQueryMode.NONE : query.mode());
    }

    default DataDetailResult queryDetails(UUID organizationId, DataDetailQuery query, List<UUID> categoryIds,
                                           RetrievalAccessScope accessScope) {
        return queryDetails(organizationId, query, categoryIds,
                accessScope == null ? AccessScope.denied() : accessScope.dataScope());
    }

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

    default List<SourceFileMatch> searchSourceFiles(UUID organizationId, String query, List<UUID> categoryIds,
                                                    RetrievalAccessScope accessScope, int limit) {
        var scope = accessScope == null ? RetrievalAccessScope.all() : accessScope;
        if (scope.deniesAll()) return List.of();
        return searchSourceFiles(organizationId, query, categoryIds, scope.dataScope(), limit);
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
                        sourceLocator(file, hit),
                        hit.recordKey(), hit.fieldCode(), hit.fieldName(), hit.fieldValue(), hit.unit(),
                        hit.valueType(), hit.sheetName(), hit.cellAddress())))
                .toList();
    }

    default List<SourceFileHit> search(UUID organizationId, String query, List<UUID> categoryIds,
                                       RetrievalAccessScope accessScope, int limit) {
        return searchSourceFiles(organizationId, query, categoryIds, accessScope, limit).stream()
                .flatMap(file -> file.hits().stream().map(hit -> new SourceFileHit(
                        hit.hitId(), file.fileObjectId(), file.importJobId(), hit.rowNumber(), hit.columnName(),
                        file.originalName(), hit.snippet(), hit.score(),
                        sourceLocator(file, hit),
                        hit.recordKey(), hit.fieldCode(), hit.fieldName(), hit.fieldValue(), hit.unit(),
                        hit.valueType(), hit.sheetName(), hit.cellAddress(), file.sourceOwner(),
                        file.recognitionMode(), file.experimentId(), file.experimentVersionId())))
                .toList();
    }

    private static String sourceLocator(SourceFileMatch file, Hit hit) {
        var owner = "EXPERIMENT".equalsIgnoreCase(file.sourceOwner())
                ? "EXPERIMENT_TEMPLATE" : "DATA_CENTER";
        return owner + ":" + (hit.sheetName() == null ? "" : hit.sheetName())
                + ":" + (hit.cellAddress() == null ? "" : hit.cellAddress());
    }

    record SourceFileMatch(UUID fileObjectId, UUID importJobId, String originalName, String contentType,
                           long size, Instant updatedAt, List<Hit> hits, List<String> matchedTerms,
                           String sourceOwner, String recognitionMode, UUID experimentId,
                           UUID experimentVersionId) {
        public SourceFileMatch(UUID fileObjectId, UUID importJobId, String originalName, String contentType,
                               long size, Instant updatedAt, List<Hit> hits, List<String> matchedTerms) {
            this(fileObjectId, importJobId, originalName, contentType, size, updatedAt, hits, matchedTerms,
                    "DATA_CENTER", "TEMPLATE_GUIDED", null, null);
        }
    }

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
                         String cellAddress, String sourceOwner, String recognitionMode,
                         UUID experimentId, UUID experimentVersionId, String sourceStatus,
                         String sourceLabel, String semanticDomain, String semanticField,
                         String declaredCondition, String sourceUnit, String standardUnit,
                         String mappingKind, String contextRelation) {
        public SourceFileHit(UUID hitId, UUID fileObjectId, UUID importJobId, Integer rowNumber,
                             String columnName, String originalName, String content, double score,
                             String sourceLocator) {
            this(hitId, fileObjectId, importJobId, rowNumber, columnName, originalName, content, score,
                    sourceLocator, null, null, null, null, null, null, null, null,
                    "DATA_CENTER", "TEMPLATE_GUIDED", null, null, "UNKNOWN",
                    null, null, null, null, null, null, null, "TARGET");
        }

        public SourceFileHit(UUID hitId, UUID fileObjectId, UUID importJobId, Integer rowNumber,
                             String columnName, String originalName, String content, double score,
                             String sourceLocator, String recordKey, String fieldCode, String fieldName,
                             String fieldValue, String unit, String valueType, String sheetName,
                             String cellAddress) {
            this(hitId, fileObjectId, importJobId, rowNumber, columnName, originalName, content, score,
                    sourceLocator, recordKey, fieldCode, fieldName, fieldValue, unit, valueType, sheetName,
                    cellAddress, "DATA_CENTER", "TEMPLATE_GUIDED", null, null, "UNKNOWN",
                    null, null, null, null, null, null, null, "TARGET");
        }

        public SourceFileHit(UUID hitId, UUID fileObjectId, UUID importJobId, Integer rowNumber,
                             String columnName, String originalName, String content, double score,
                             String sourceLocator, String recordKey, String fieldCode, String fieldName,
                             String fieldValue, String unit, String valueType, String sheetName,
                             String cellAddress, String sourceOwner, String recognitionMode,
                             UUID experimentId, UUID experimentVersionId) {
            this(hitId, fileObjectId, importJobId, rowNumber, columnName, originalName, content, score,
                    sourceLocator, recordKey, fieldCode, fieldName, fieldValue, unit, valueType, sheetName,
                    cellAddress, sourceOwner, recognitionMode, experimentId, experimentVersionId, "UNKNOWN",
                    null, null, null, null, null, null, null, "TARGET");
        }
    }

    record RetrievalAccessScope(AccessScope dataScope, AccessScope experimentScope) {
        public RetrievalAccessScope {
            dataScope = dataScope == null ? AccessScope.denied() : dataScope;
            experimentScope = experimentScope == null ? AccessScope.denied() : experimentScope;
        }

        public static RetrievalAccessScope all() {
            return new RetrievalAccessScope(AccessScope.all(), AccessScope.all());
        }

        public static RetrievalAccessScope denied() {
            return new RetrievalAccessScope(AccessScope.denied(), AccessScope.denied());
        }

        public boolean deniesAll() {
            return dataScope.deniesAll() && experimentScope.deniesAll();
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

    enum RecordTermMatch {
        ALL,
        ANY
    }

    /** Restricts evidence by the lifecycle state that makes it usable as a source. */
    enum EvidenceFilter {
        ANY,
        CONFIRMED_DATA,
        COMPLETED_EXPERIMENT,
        FORMAL
    }

    enum ContextMode {
        MINIMAL,
        ANALYTIC,
        COMPARABLE
    }

    record DataDetailQuery(DataQueryMode mode, String fileName, DataFileScope fileScope, List<String> recordTerms,
                           List<String> fieldTerms, RecordTermMatch recordTermMatch, EvidenceFilter evidenceFilter,
                           int recordLimit, int fieldsPerRecord, int valueLimit, ContextMode contextMode,
                           int contextFieldLimit, int comparisonRecordLimit, String analysisGoal) {
        public DataDetailQuery(DataQueryMode mode, String fileName, List<String> recordTerms,
                               List<String> fieldTerms, int recordLimit, int fieldsPerRecord, int valueLimit) {
            this(mode, fileName, fileName == null || fileName.isBlank() ? DataFileScope.AUTO : DataFileScope.EXPLICIT,
                    recordTerms, fieldTerms, RecordTermMatch.ALL, EvidenceFilter.ANY,
                    recordLimit, fieldsPerRecord, valueLimit, ContextMode.MINIMAL, 32, 5, "VALUE");
        }

        public DataDetailQuery(DataQueryMode mode, String fileName, DataFileScope fileScope,
                               List<String> recordTerms, List<String> fieldTerms,
                               int recordLimit, int fieldsPerRecord, int valueLimit) {
            this(mode, fileName, fileScope, recordTerms, fieldTerms, RecordTermMatch.ALL, EvidenceFilter.ANY,
                    recordLimit, fieldsPerRecord, valueLimit, ContextMode.MINIMAL, 32, 5, "VALUE");
        }

        public DataDetailQuery(DataQueryMode mode, String fileName, DataFileScope fileScope,
                               List<String> recordTerms, List<String> fieldTerms, RecordTermMatch recordTermMatch,
                               int recordLimit, int fieldsPerRecord, int valueLimit) {
            this(mode, fileName, fileScope, recordTerms, fieldTerms, recordTermMatch, EvidenceFilter.ANY,
                    recordLimit, fieldsPerRecord, valueLimit, ContextMode.MINIMAL, 32, 5, "VALUE");
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
            recordTermMatch = recordTermMatch == null ? RecordTermMatch.ALL : recordTermMatch;
            evidenceFilter = evidenceFilter == null ? EvidenceFilter.ANY : evidenceFilter;
            recordLimit = Math.min(20, Math.max(1, recordLimit));
            fieldsPerRecord = Math.min(20, Math.max(1, fieldsPerRecord));
            valueLimit = Math.min(50, Math.max(1, valueLimit));
            contextMode = contextMode == null ? ContextMode.MINIMAL : contextMode;
            contextFieldLimit = Math.min(32, Math.max(4, contextFieldLimit));
            comparisonRecordLimit = Math.min(5, Math.max(1, comparisonRecordLimit));
            analysisGoal = analysisGoal == null || analysisGoal.isBlank() ? "VALUE" : analysisGoal.strip().toUpperCase(Locale.ROOT);
        }

        public static DataDetailQuery none() {
            return new DataDetailQuery(DataQueryMode.NONE, "", DataFileScope.AUTO,
                    List.of(), List.of(), RecordTermMatch.ALL, EvidenceFilter.ANY, 5, 5, 50,
                    ContextMode.MINIMAL, 32, 5, "VALUE");
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
                            List<DataFileCandidate> candidates, boolean candidatesTruncated,
                            List<RecordContext> recordContexts, String analysisGoal) {
        public DataDetailResult(DataQueryMode mode, UUID fileObjectId, UUID importJobId, String originalName,
                                long sourceRecordCount, long searchableValueCount,
                                List<SourceFileHit> hits, boolean truncated) {
            this(mode, importJobId == null ? DataResolution.NOT_FOUND : DataResolution.RESOLVED,
                    fileObjectId, importJobId, originalName, sourceRecordCount, searchableValueCount,
                    hits, truncated, List.of(), false, List.of(), "VALUE");
        }

        public DataDetailResult(DataQueryMode mode, DataResolution resolution,
                                UUID fileObjectId, UUID importJobId, String originalName,
                                long sourceRecordCount, long searchableValueCount,
                                List<SourceFileHit> hits, boolean truncated,
                                List<DataFileCandidate> candidates, boolean candidatesTruncated) {
            this(mode, resolution, fileObjectId, importJobId, originalName, sourceRecordCount, searchableValueCount,
                    hits, truncated, candidates, candidatesTruncated, List.of(), "VALUE");
        }

        public DataDetailResult {
            mode = mode == null ? DataQueryMode.NONE : mode;
            resolution = resolution == null ? (mode == DataQueryMode.NONE
                    ? DataResolution.NOT_REQUESTED : DataResolution.NOT_FOUND) : resolution;
            hits = hits == null ? List.of() : List.copyOf(hits);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            recordContexts = recordContexts == null ? List.of() : List.copyOf(recordContexts);
            analysisGoal = analysisGoal == null || analysisGoal.isBlank() ? "VALUE" : analysisGoal.strip().toUpperCase(Locale.ROOT);
        }

        public static DataDetailResult empty(DataQueryMode mode) {
            return new DataDetailResult(mode, mode == null || mode == DataQueryMode.NONE
                    ? DataResolution.NOT_REQUESTED : DataResolution.NOT_FOUND,
                    null, null, "", 0, 0, List.of(), false, List.of(), false, List.of(), "VALUE");
        }

        public boolean found() {
            return resolution == DataResolution.RESOLVED && importJobId != null;
        }

        public boolean requiresFileSelection() {
            return resolution == DataResolution.AMBIGUOUS && !candidates.isEmpty();
        }
    }

    /** Context grouped around one target observation; no database identifiers are exposed here. */
    record RecordContext(String recordKey, String sheetName, List<SourceFileHit> targetHits,
                         List<SourceFileHit> contextHits, boolean contextTruncated,
                         boolean comparable, String comparisonReason) {
        public RecordContext {
            recordKey = recordKey == null ? "" : recordKey;
            sheetName = sheetName == null ? "" : sheetName;
            targetHits = targetHits == null ? List.of() : List.copyOf(targetHits);
            contextHits = contextHits == null ? List.of() : List.copyOf(contextHits);
            comparisonReason = comparisonReason == null ? "" : comparisonReason;
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

        public static AccessScope denied() {
            return new AccessScope("DENIED", null, Set.of());
        }

        public boolean deniesAll() {
            return (Set.of("SELECTED", "CATEGORY", "PROJECT").contains(type) && targetIds.isEmpty())
                    || "SELF".equals(type) && actorId == null
                    || "ASSIGNED".equals(type)
                    || !Set.of("ALL", "SELECTED", "CATEGORY", "PROJECT", "SELF", "ASSIGNED").contains(type);
        }
    }
}
