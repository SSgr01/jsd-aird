package com.jsd.aird.data.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDataSourceFileSearchRepository implements DataSourceFileSearchFacade {

    /** Assistant-only scope token for completed imports that have not been assigned a data category. */
    static final UUID UNCATEGORIZED_SCOPE_ID = new UUID(0L, 0L);

    private final JdbcTemplate jdbc;

    public JdbcDataSourceFileSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DataDetailResult queryDetails(UUID organizationId, DataDetailQuery query,
                                         List<UUID> categoryIds, AccessScope accessScope) {
        return queryDetails(organizationId, query, categoryIds,
                new RetrievalAccessScope(accessScope, accessScope));
    }

    @Override
    public DataDetailResult queryDetails(UUID organizationId, DataDetailQuery query,
                                         List<UUID> categoryIds, RetrievalAccessScope accessScope) {
        var request = query == null ? DataDetailQuery.none() : query;
        var scope = accessScope == null ? RetrievalAccessScope.all() : accessScope;
        if (request.mode() == DataQueryMode.NONE || scope.deniesAll()) {
            return DataDetailResult.empty(request.mode());
        }
        if (request.fileName().isBlank() && (request.mode() == DataQueryMode.FILE_OVERVIEW
                || request.recordTerms().isEmpty() && request.fieldTerms().isEmpty())) {
            return unresolved(request, DataResolution.FILE_REQUIRED, List.of(), false);
        }
        if (!request.fileName().isBlank()) {
            var selected = findLatestCompletedFile(organizationId, request.fileName(), categoryIds, scope);
            if (selected != null) {
                var result = resolved(organizationId, selected, request, scope);
                if (request.mode() == DataQueryMode.FILE_OVERVIEW || !result.hits().isEmpty()
                        || request.fileScope() == DataFileScope.EXPLICIT) return result;
            } else if (request.fileScope() == DataFileScope.EXPLICIT) {
                return unresolved(request, DataResolution.NOT_FOUND, List.of(), false);
            }
        }
        var matches = findCandidateFiles(organizationId, request, categoryIds, scope, 11);
        if (matches.isEmpty()) return unresolved(request, DataResolution.NOT_FOUND, List.of(), false);
        var bestConfidence = matches.getFirst().confidence();
        var best = matches.stream().filter(item -> item.confidence() == bestConfidence).toList();
        if (best.size() == 1) return resolved(organizationId, best.getFirst().file(), request, scope);
        var candidatesTruncated = best.size() > 10;
        var candidates = best.stream().limit(10)
                .map(item -> new DataFileCandidate(item.file().originalName())).toList();
        return unresolved(request, DataResolution.AMBIGUOUS, candidates, candidatesTruncated);
    }

    private DataDetailResult resolved(UUID organizationId, FileRow file, DataDetailQuery request,
                                      RetrievalAccessScope scope) {
        var counts = detailCounts(organizationId, file.importJobId(), scope);
        var limit = request.mode() == DataQueryMode.FILE_OVERVIEW
                ? request.recordLimit() * request.fieldsPerRecord() : request.valueLimit();
        var hits = queryDetailHits(organizationId, file, request, scope, limit + 1);
        var truncated = hits.size() > limit;
        if (truncated) hits = hits.subList(0, limit);
        return new DataDetailResult(request.mode(), DataResolution.RESOLVED,
                file.fileId(), file.importJobId(), file.originalName(), counts.recordCount(), counts.valueCount(),
                hits, truncated, List.of(), false);
    }

    private DataDetailResult unresolved(DataDetailQuery request, DataResolution resolution,
                                        List<DataFileCandidate> candidates, boolean candidatesTruncated) {
        var displayFileName = request.fileScope() == DataFileScope.EXPLICIT ? request.fileName() : "";
        return new DataDetailResult(request.mode(), resolution, null, null, displayFileName,
                0, 0, List.of(), false, candidates, candidatesTruncated);
    }

    private FileRow findLatestCompletedFile(UUID organizationId, String fileName,
                                            List<UUID> categoryIds, RetrievalAccessScope scope) {
        var categories = categoryClause(categoryIds);
        var permission = permissionClause(scope);
        var sql = """
                SELECT p.import_job_id, p.file_object_id, p.original_name,
                       coalesce(fo.content_type, CASE p.source_format WHEN 'CSV' THEN 'text/csv'
                           WHEN 'XLS' THEN 'application/vnd.ms-excel'
                           ELSE 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' END) AS content_type,
                       coalesce(fo.size_bytes, 0) AS size_bytes,
                       max(p.updated_at) AS updated_at,
                       max(p.source_owner) AS source_owner,
                       max(p.recognition_mode) AS recognition_mode,
                       max(p.experiment_id::text)::uuid AS experiment_id,
                       max(p.experiment_version_id::text)::uuid AS experiment_version_id
                FROM ai.structured_retrieval_projection p
                LEFT JOIN ops.file_object fo ON fo.id = p.file_object_id
                    AND fo.organization_id = p.organization_id
                WHERE p.organization_id = ?
                  AND lower(p.original_name) = lower(?)
                  %s
                  %s
                GROUP BY p.import_job_id, p.file_object_id, p.original_name,
                         fo.content_type, fo.size_bytes
                ORDER BY max(p.updated_at) DESC, p.import_job_id DESC
                LIMIT 1
                """.formatted(categories, permission.sql());
        var args = new ArrayList<Object>();
        args.add(organizationId);
        args.add(fileName);
        args.addAll(categoryParameters(categoryIds));
        args.addAll(permission.parameters());
        var rows = jdbc.query(sql, this::fileRow, args.toArray());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<FileCandidateRow> findCandidateFiles(UUID organizationId, DataDetailQuery request,
                                                       List<UUID> categoryIds, RetrievalAccessScope scope,
                                                       int limit) {
        var categories = categoryClause(categoryIds);
        var permission = permissionClause(scope);
        var filters = new StringBuilder();
        var filterArgs = new ArrayList<Object>();
        for (var term : request.recordTerms()) {
            filters.append(" AND (p.record_key ILIKE '%%' || ? || '%%' OR p.field_value ILIKE '%%' || ? || '%%')");
            filterArgs.add(term);
            filterArgs.add(term);
        }
        if (!request.fieldTerms().isEmpty()) {
            filters.append(" AND (");
            for (var index = 0; index < request.fieldTerms().size(); index++) {
                if (index > 0) filters.append(" OR ");
                filters.append("(p.field_name ILIKE '%%' || ? || '%%' OR p.field_code ILIKE '%%' || ? || '%%')");
                filterArgs.add(request.fieldTerms().get(index));
                filterArgs.add(request.fieldTerms().get(index));
            }
            filters.append(')');
        }

        var score = new StringBuilder("1");
        var scoreArgs = new ArrayList<Object>();
        for (var term : request.recordTerms()) {
            score.append(" + CASE WHEN lower(btrim(v.record_key)) = lower(btrim(?)) THEN 100 "
                    + "WHEN lower(btrim(v.field_value)) = lower(btrim(?)) THEN 80 "
                    + "WHEN v.record_key ILIKE '%%' || ? || '%%' OR v.field_value ILIKE '%%' || ? || '%%' THEN 30 ELSE 0 END");
            scoreArgs.add(term);
            scoreArgs.add(term);
            scoreArgs.add(term);
            scoreArgs.add(term);
        }
        for (var term : request.fieldTerms()) {
            score.append(" + CASE WHEN lower(btrim(v.field_name)) = lower(btrim(?)) "
                    + "OR lower(btrim(v.field_code)) = lower(btrim(?)) THEN 20 "
                    + "WHEN v.field_name ILIKE '%%' || ? || '%%' OR v.field_code ILIKE '%%' || ? || '%%' THEN 8 ELSE 0 END");
            scoreArgs.add(term);
            scoreArgs.add(term);
            scoreArgs.add(term);
            scoreArgs.add(term);
        }

        var sql = """
                WITH candidate_values AS (
                    SELECT p.organization_id, p.import_job_id, p.file_object_id, p.original_name, p.source_format,
                           p.updated_at, p.source_owner, p.recognition_mode,
                           p.experiment_id, p.experiment_version_id, p.record_key,
                           p.field_name, p.field_code, p.field_value
                    FROM ai.structured_retrieval_projection p
                    WHERE p.organization_id = ?
                      %s
                      %s
                      %s
                )
                SELECT v.import_job_id, v.file_object_id, v.original_name,
                       coalesce(fo.content_type, CASE v.source_format WHEN 'CSV' THEN 'text/csv'
                           WHEN 'XLS' THEN 'application/vnd.ms-excel'
                           ELSE 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' END) AS content_type,
                       coalesce(fo.size_bytes, 0) AS size_bytes,
                       max(v.updated_at) AS updated_at,
                       max(v.source_owner) AS source_owner,
                       max(v.recognition_mode) AS recognition_mode,
                       max(v.experiment_id::text)::uuid AS experiment_id,
                       max(v.experiment_version_id::text)::uuid AS experiment_version_id,
                       max(%s) AS confidence
                FROM candidate_values v
                LEFT JOIN ops.file_object fo ON fo.id = v.file_object_id
                    AND fo.organization_id = v.organization_id
                GROUP BY v.import_job_id, v.file_object_id, v.original_name, v.source_format,
                         fo.content_type, fo.size_bytes
                ORDER BY confidence DESC, max(v.updated_at) DESC, v.original_name
                LIMIT ?
                """.formatted(categories, permission.sql(), filters, score);
        var args = new ArrayList<Object>();
        args.add(organizationId);
        args.addAll(categoryParameters(categoryIds));
        args.addAll(permission.parameters());
        args.addAll(filterArgs);
        args.addAll(scoreArgs);
        args.add(Math.max(1, Math.min(50, limit)));
        return jdbc.query(sql, (rs, ignored) -> new FileCandidateRow(fileRow(rs, ignored), rs.getInt("confidence")), args.toArray());
    }

    private DetailCounts detailCounts(UUID organizationId, UUID importJobId, RetrievalAccessScope scope) {
        var permission = permissionClause(scope);
        var sql = """
                SELECT count(DISTINCT p.record_id) AS record_count, count(DISTINCT p.value_id) AS value_count
                FROM ai.structured_retrieval_projection p
                WHERE p.organization_id = ? AND p.import_job_id = ? %s
                """.formatted(permission.sql());
        var args = new ArrayList<Object>();
        args.add(organizationId);
        args.add(importJobId);
        args.addAll(permission.parameters());
        return jdbc.queryForObject(sql, (rs, ignored) -> new DetailCounts(
                rs.getLong("record_count"), rs.getLong("value_count")), args.toArray());
    }

    private List<SourceFileHit> queryDetailHits(UUID organizationId, FileRow file,
                                                DataDetailQuery request, RetrievalAccessScope scope,
                                                int limit) {
        var filters = new StringBuilder();
        var filterArgs = new ArrayList<Object>();
        for (var term : request.recordTerms()) {
            filters.append(" AND (p.record_key ILIKE '%%' || ? || '%%' OR p.field_value ILIKE '%%' || ? || '%%')");
            filterArgs.add(term);
            filterArgs.add(term);
        }
        if (!request.fieldTerms().isEmpty()) {
            filters.append(" AND (");
            for (var index = 0; index < request.fieldTerms().size(); index++) {
                if (index > 0) filters.append(" OR ");
                filters.append("(p.field_name ILIKE '%%' || ? || '%%' OR p.field_code ILIKE '%%' || ? || '%%')");
                filterArgs.add(request.fieldTerms().get(index));
                filterArgs.add(request.fieldTerms().get(index));
            }
            filters.append(')');
        }
        var permission = permissionClause(scope);
        var fieldRank = fieldRankExpression(request.fieldTerms());
        var rankArgs = new ArrayList<Object>();
        for (var term : request.fieldTerms()) {
            rankArgs.add(term);
            rankArgs.add(term);
        }
        var sql = """
                WITH matched AS (
                    SELECT p.*
                    FROM ai.structured_retrieval_projection p
                    WHERE p.organization_id = ? AND p.import_job_id = ?
                      %s
                      %s
                )
                SELECT matched.*, %s AS field_match_rank,
                       row_number() OVER (PARTITION BY matched.record_id ORDER BY matched.value_id) AS field_rank,
                       dense_rank() OVER (ORDER BY matched.record_index, matched.record_id) AS record_rank
                FROM matched
                ORDER BY matched.record_index, field_match_rank, field_rank, matched.value_id
                LIMIT ?
                """.formatted(permission.sql(), filters, fieldRank);
        var args = new ArrayList<Object>();
        args.add(organizationId);
        args.add(file.importJobId());
        args.addAll(permission.parameters());
        args.addAll(filterArgs);
        args.addAll(rankArgs);
        args.add(Math.max(1, limit));
        return jdbc.query(sql, (rs, ignored) -> detailHit(rs, file), args.toArray());
    }

    private String fieldRankExpression(List<String> terms) {
        if (terms == null || terms.isEmpty()) return "0";
        var expression = new StringBuilder("100");
        for (var ignored : terms) {
            expression.append(" + CASE WHEN lower(btrim(matched.field_name)) = lower(btrim(?)) "
                    + "OR lower(btrim(matched.field_code)) = lower(btrim(?)) THEN 0 ELSE 1 END");
        }
        return expression.toString();
    }

    private SourceFileHit detailHit(ResultSet rs, FileRow file) throws SQLException {
        var value = readable(rs.getString("field_value"), "—");
        var field = readable(rs.getString("field_name"), "未命名字段");
        var record = readable(rs.getString("record_key"), "未命名记录");
        var sheet = rs.getString("sheet_name");
        var cell = rs.getString("cell_address");
        var owner = "EXPERIMENT".equalsIgnoreCase(file.sourceOwner()) ? "EXPERIMENT_TEMPLATE" : "DATA_CENTER";
        var locator = owner + ":" + readable(sheet, "") + ":" + readable(cell, "");
        var content = "记录：" + record + "；字段：" + field + "；值=" + value
                + (rs.getString("normalized_unit") == null || rs.getString("normalized_unit").isBlank()
                ? "" : " " + rs.getString("normalized_unit"));
        return new SourceFileHit(rs.getObject("value_id", UUID.class), file.fileId(), file.importJobId(),
                (Integer) rs.getObject("source_row_number"), rs.getString("column_name"), file.originalName(),
                content, 3.0, locator, record, rs.getString("field_code"), field, value,
                rs.getString("normalized_unit"), rs.getString("value_type"), sheet, cell,
                file.sourceOwner(), file.recognitionMode(), file.experimentId(), file.experimentVersionId());
    }

    @Override
    public List<SourceFileMatch> searchSourceFiles(UUID organizationId, String query,
                                                   List<UUID> categoryIds, int limit) {
        return searchSourceFiles(organizationId, query, categoryIds, RetrievalAccessScope.all(), limit);
    }

    @Override
    public List<SourceFileMatch> searchSourceFiles(UUID organizationId, String query,
                                                   List<UUID> categoryIds, AccessScope accessScope,
                                                   int limit) {
        return searchSourceFiles(organizationId, query, categoryIds,
                new RetrievalAccessScope(accessScope, accessScope), limit);
    }

    @Override
    public List<SourceFileMatch> searchSourceFiles(UUID organizationId, String query,
                                                   List<UUID> categoryIds, RetrievalAccessScope accessScope,
                                                   int limit) {
        var scope = accessScope == null ? RetrievalAccessScope.all() : accessScope;
        if (scope.deniesAll()) return List.of();
        var normalizedQuery = query == null ? "" : query.strip();
        if (normalizedQuery.isBlank()) return List.of();
        var categories = categoryClause(categoryIds);
        var permission = permissionClause(scope);
        var sql = """
                WITH matched AS (
                    SELECT p.*, coalesce(fo.content_type,
                               CASE p.source_format WHEN 'CSV' THEN 'text/csv'
                               WHEN 'XLS' THEN 'application/vnd.ms-excel'
                               ELSE 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' END) AS content_type,
                           coalesce(fo.size_bytes, 0) AS size_bytes
                    FROM ai.structured_retrieval_projection p
                    LEFT JOIN ops.file_object fo ON fo.id = p.file_object_id
                        AND fo.organization_id = p.organization_id
                    WHERE p.organization_id = ?
                      %s
                      %s
                      AND (p.original_name ILIKE '%%' || ? || '%%'
                           OR p.record_key ILIKE '%%' || ? || '%%'
                           OR p.field_code ILIKE '%%' || ? || '%%'
                           OR p.field_name ILIKE '%%' || ? || '%%'
                           OR p.field_value ILIKE '%%' || ? || '%%')
                )
                SELECT matched.*,
                       GREATEST(
                           CASE WHEN matched.original_name ILIKE '%%' || ? || '%%' THEN 1.0 ELSE 0 END,
                           CASE WHEN lower(matched.record_key) = lower(?) THEN 4.0
                                WHEN matched.record_key ILIKE '%%' || ? || '%%' THEN 2.0 ELSE 0 END,
                           CASE WHEN lower(matched.field_code) = lower(?) THEN 3.5
                                WHEN matched.field_code ILIKE '%%' || ? || '%%' THEN 1.8 ELSE 0 END,
                           CASE WHEN lower(matched.field_name) = lower(?) THEN 3.0
                                WHEN matched.field_name ILIKE '%%' || ? || '%%' THEN 1.8 ELSE 0 END,
                           CASE WHEN matched.field_value = ? THEN 4.0
                                WHEN matched.field_value ILIKE '%%' || ? || '%%' THEN 1.5 ELSE 0 END
                       ) AS score
                FROM matched
                ORDER BY score DESC, matched.updated_at DESC, matched.record_index,
                         matched.field_code, matched.value_path
                LIMIT ?
                """.formatted(categories, permission.sql());
        var args = new ArrayList<Object>();
        args.add(organizationId);
        args.addAll(categoryParameters(categoryIds));
        args.addAll(permission.parameters());
        for (var ignored = 0; ignored < 5; ignored++) args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(Math.min(1000, Math.max(20, Math.max(1, limit) * 25)));
        var rows = jdbc.query(sql, this::mapRow, args.toArray());
        var grouped = new LinkedHashMap<UUID, Aggregate>();
        for (var row : rows) {
            var aggregate = grouped.computeIfAbsent(row.importJobId(), ignored -> new Aggregate(row));
            if (aggregate.hits.size() < 10) aggregate.hits.add(hit(row));
        }
        return grouped.values().stream().limit(Math.max(1, limit))
                .map(item -> item.view(normalizedQuery)).toList();
    }

    private Row mapRow(ResultSet rs, int ignored) throws SQLException {
        return new Row(rs.getObject("import_job_id", UUID.class), rs.getObject("file_object_id", UUID.class),
                rs.getString("original_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("source_owner"),
                rs.getString("recognition_mode"), rs.getObject("experiment_id", UUID.class),
                rs.getObject("experiment_version_id", UUID.class),
                readable(rs.getString("record_key"), "未命名记录"), rs.getString("sheet_name"),
                (Integer) rs.getObject("source_row_number"), rs.getString("column_name"),
                rs.getString("cell_address"), rs.getObject("value_id", UUID.class),
                rs.getString("field_code"), rs.getString("field_name"), rs.getString("field_value"),
                rs.getString("normalized_unit"), rs.getString("value_type"), rs.getDouble("score"));
    }

    private FileRow fileRow(ResultSet rs, int ignored) throws SQLException {
        return new FileRow(rs.getObject("import_job_id", UUID.class), rs.getObject("file_object_id", UUID.class),
                rs.getString("original_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("source_owner"),
                rs.getString("recognition_mode"), rs.getObject("experiment_id", UUID.class),
                rs.getObject("experiment_version_id", UUID.class));
    }

    private Hit hit(Row row) {
        var value = row.value() == null || row.value().isBlank() ? "未填写" : row.value();
        var fieldName = readable(row.fieldName(), row.fieldCode());
        var snippet = "记录=" + readable(row.recordKey(), "未命名记录")
                + "；字段=" + fieldName + "；值=" + value
                + (row.unit() == null || row.unit().isBlank() ? "" : " " + row.unit());
        if (snippet.length() > 800) snippet = snippet.substring(0, 800) + "…";
        return new Hit(row.valueId(), snippet, Math.max(0.1, row.score()), row.sheetName(), row.rowNumber(),
                row.column(), row.cellAddress(), row.recordKey(), row.fieldCode(), fieldName, value,
                row.unit(), row.valueType());
    }

    private String readable(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String categoryClause(List<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) return "";
        var categorizedIds = categoryParameters(categoryIds);
        var includesUncategorized = categoryIds.contains(UNCATEGORIZED_SCOPE_ID);
        var conditions = new ArrayList<String>();
        if (!categorizedIds.isEmpty()) {
            var placeholders = String.join(",", java.util.Collections.nCopies(categorizedIds.size(), "?"));
            conditions.add("(p.source_owner = 'DATA_CENTER' AND p.data_category_id IN (" + placeholders + ")"
                    + (includesUncategorized ? " OR p.data_category_id IS NULL" : "") + ")");
        } else if (includesUncategorized) {
            conditions.add("(p.source_owner = 'DATA_CENTER' AND p.data_category_id IS NULL)");
        }
        if (includesUncategorized) conditions.add("p.source_owner = 'EXPERIMENT'");
        return conditions.isEmpty() ? " AND 1 = 0" : " AND (" + String.join(" OR ", conditions) + ")";
    }

    private List<UUID> categoryParameters(List<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) return List.of();
        return categoryIds.stream().filter(id -> !UNCATEGORIZED_SCOPE_ID.equals(id)).toList();
    }

    private ScopeClause permissionClause(RetrievalAccessScope scopes) {
        var data = sourcePermission("DATA_CENTER", scopes.dataScope(), false);
        var experiment = sourcePermission("EXPERIMENT", scopes.experimentScope(), true);
        var sql = " AND ((p.source_owner = 'DATA_CENTER' AND (" + data.sql() + "))"
                + " OR (p.source_owner = 'EXPERIMENT' AND (" + experiment.sql() + ")))";
        var parameters = new ArrayList<Object>();
        parameters.addAll(data.parameters());
        parameters.addAll(experiment.parameters());
        return new ScopeClause(sql, parameters);
    }

    private ScopeClause sourcePermission(String owner, AccessScope scope, boolean experiment) {
        if (scope == null || scope.deniesAll()) return new ScopeClause("1 = 0", List.of());
        var idColumn = experiment ? "p.experiment_id" : "p.import_job_id";
        var selfColumn = experiment ? "p.experiment_created_by" : "p.import_created_by";
        return switch (scope.type()) {
            case "ALL" -> new ScopeClause("1 = 1", List.of());
            case "SELF" -> experiment
                    ? new ScopeClause("(p.experiment_created_by = ? OR p.experiment_owner_id = ?)",
                    List.of(scope.actorId(), scope.actorId()))
                    : new ScopeClause(selfColumn + " = ?", List.of(scope.actorId()));
            case "ASSIGNED" -> experiment
                    ? new ScopeClause("p.experiment_owner_id = ?", List.of(scope.actorId()))
                    : new ScopeClause("1 = 0", List.of());
            case "SELECTED" -> idsClause(idColumn + " IN (%s)", scope.targetIds());
            case "CATEGORY" -> idsClause((experiment ? "p.experiment_category_id" : "p.data_category_id")
                    + " IN (%s)", scope.targetIds());
            case "PROJECT" -> experiment
                    ? idsClause("p.experiment_project_id IN (%s)", scope.targetIds())
                    : idsClause("EXISTS (SELECT 1 FROM core.project_resource_link prl"
                    + " WHERE prl.organization_id = p.organization_id"
                    + " AND prl.resource_type = 'DATA_IMPORT_JOB'"
                    + " AND prl.resource_id = p.import_job_id"
                    + " AND prl.project_id IN (%s))", scope.targetIds());
            default -> new ScopeClause("1 = 0", List.of());
        };
    }

    private ScopeClause idsClause(String template, Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) return new ScopeClause("1 = 0", List.of());
        return new ScopeClause(template.formatted(String.join(",", java.util.Collections.nCopies(ids.size(), "?"))),
                new ArrayList<>(ids));
    }

    private record ScopeClause(String sql, List<Object> parameters) { }

    private record Row(UUID importJobId, UUID fileId, String originalName, String contentType, long size,
                       java.time.Instant updatedAt, String sourceOwner, String recognitionMode,
                       UUID experimentId, UUID experimentVersionId, String recordKey, String sheetName,
                       Integer rowNumber, String column, String cellAddress, UUID valueId, String fieldCode,
                       String fieldName, String value, String unit, String valueType, double score) { }

    private record FileRow(UUID importJobId, UUID fileId, String originalName, String contentType,
                           long size, java.time.Instant updatedAt, String sourceOwner,
                           String recognitionMode, UUID experimentId, UUID experimentVersionId) { }

    private record FileCandidateRow(FileRow file, int confidence) { }

    private record DetailCounts(long recordCount, long valueCount) { }

    private static final class Aggregate {
        private final Row row;
        private final List<Hit> hits = new ArrayList<>();

        private Aggregate(Row row) {
            this.row = row;
        }

        private SourceFileMatch view(String query) {
            var normalizedQuery = normalizeEvidence(query);
            var matched = normalizedQuery.length() >= 2 &&
                    (normalizeEvidence(row.originalName()).contains(normalizedQuery)
                            || hits.stream().anyMatch(hit -> normalizeEvidence(hit.snippet()).contains(normalizedQuery)))
                    ? List.of(query) : List.<String>of();
            return new SourceFileMatch(row.fileId(), row.importJobId(), row.originalName(), row.contentType(),
                    row.size(), row.updatedAt(), List.copyOf(hits), matched, row.sourceOwner(),
                    row.recognitionMode(), row.experimentId(), row.experimentVersionId());
        }

        private static String normalizeEvidence(String value) {
            return value == null ? "" : value.toUpperCase(Locale.ROOT)
                    .replaceAll("[^A-Z0-9\\u4E00-\\u9FFF]", "");
        }
    }
}
