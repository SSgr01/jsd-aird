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

    private final JdbcTemplate jdbc;

    public JdbcDataSourceFileSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DataDetailResult queryDetails(UUID organizationId, DataDetailQuery query,
                                         List<UUID> categoryIds, AccessScope accessScope) {
        var request = query == null ? DataDetailQuery.none() : query;
        var scope = accessScope == null ? AccessScope.all() : accessScope;
        if (request.mode() == DataQueryMode.NONE || request.fileName().isBlank() || scope.deniesAll()) {
            return DataDetailResult.empty(request.mode());
        }
        var file = findLatestCompletedFile(organizationId, request.fileName(), categoryIds, scope);
        if (file == null) return new DataDetailResult(request.mode(), null, null, request.fileName(),
                0, 0, List.of(), false);
        var counts = detailCounts(organizationId, file.importJobId());
        var limit = request.mode() == DataQueryMode.FILE_OVERVIEW
                ? request.recordLimit() * request.fieldsPerRecord() : request.valueLimit();
        var hits = queryDetailHits(organizationId, file, request, limit + 1);
        var truncated = hits.size() > limit;
        if (truncated) hits = hits.subList(0, limit);
        return new DataDetailResult(request.mode(), file.fileId(), file.importJobId(), file.originalName(),
                counts.recordCount(), counts.valueCount(), hits, truncated);
    }

    private FileRow findLatestCompletedFile(UUID organizationId, String fileName,
                                            List<UUID> categoryIds, AccessScope scope) {
        var categories = categoryClause(categoryIds);
        var permission = permissionClause(scope);
        var sql = """
                SELECT j.id AS import_job_id, j.source_file_id, j.source_file_name,
                       coalesce(fo.content_type, CASE j.source_format WHEN 'CSV' THEN 'text/csv'
                           WHEN 'XLS' THEN 'application/vnd.ms-excel'
                           ELSE 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' END) AS content_type,
                       coalesce(fo.size_bytes, 0) AS size_bytes,
                       coalesce(j.completed_at, j.updated_at) AS updated_at
                FROM data.completed_source_file_projection fp
                JOIN data.import_job j ON j.id = fp.import_job_id AND j.organization_id = fp.organization_id
                LEFT JOIN ops.file_object fo ON fo.id = j.source_file_id AND fo.organization_id = j.organization_id
                WHERE j.organization_id = ?
                  AND lower(j.source_file_name) = lower(?)
                  %s
                  %s
                ORDER BY j.completed_at DESC NULLS LAST, j.updated_at DESC, j.id DESC
                LIMIT 1
                """.formatted(categories, permission.sql());
        var args = new ArrayList<Object>();
        args.add(organizationId);
        args.add(fileName);
        if (categoryIds != null) args.addAll(categoryIds);
        args.addAll(permission.parameters());
        var rows = jdbc.query(sql, (rs, ignored) -> new FileRow(
                rs.getObject("import_job_id", UUID.class), rs.getObject("source_file_id", UUID.class),
                rs.getString("source_file_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                rs.getTimestamp("updated_at").toInstant()), args.toArray());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private DetailCounts detailCounts(UUID organizationId, UUID importJobId) {
        var sql = """
                SELECT count(DISTINCT r.id) AS record_count,
                       count(v.id) FILTER (WHERE v.rag_eligible = true
                           AND (v.value_source <> 'FORMULA' OR v.calculation_status = 'VALID')
                           AND nullif(btrim(v.value_text), '') IS NOT NULL) AS value_count
                FROM data.data_record r
                LEFT JOIN data.data_value v ON v.record_id = r.id AND v.organization_id = r.organization_id
                WHERE r.organization_id = ? AND r.import_job_id = ?
                """;
        return jdbc.query(sql, rs -> rs.next()
                ? new DetailCounts(rs.getLong("record_count"), rs.getLong("value_count"))
                : new DetailCounts(0, 0), organizationId, importJobId);
    }

    private List<SourceFileHit> queryDetailHits(UUID organizationId, FileRow file,
                                                DataDetailQuery request, int sqlLimit) {
        var args = new ArrayList<Object>();
        args.add(file.importJobId());
        args.add(organizationId);
        var filters = new StringBuilder();
        for (var term : request.recordTerms()) {
            filters.append("""
                      AND (r.synthetic_key = false AND r.record_key ILIKE '%%' || ? || '%%' OR EXISTS (
                          SELECT 1 FROM data.data_value rv
                          JOIN LATERAL (
                              SELECT rm.field_name, rm.source_header, rm.mapping_jsonb
                              FROM data.import_mapping rm
                              WHERE rm.import_job_id = r.import_job_id
                                AND (rm.mapping_jsonb->>'dataPath' = rv.value_path
                                     OR coalesce(rm.mapping_jsonb->>'bindingId', rm.field_code) = rv.binding_id)
                              ORDER BY CASE WHEN rm.mapping_jsonb->>'dataPath' = rv.value_path THEN 0 ELSE 1 END,
                                       CASE WHEN rm.status IN ('CONFIRMED', 'MATCHED') THEN 0 ELSE 1 END,
                                       rm.updated_at DESC
                              LIMIT 1
                          ) record_mapping ON coalesce(record_mapping.mapping_jsonb->>'identity', 'false') = 'true'
                              OR coalesce(record_mapping.field_name, record_mapping.source_header, '') IN
                                 ('实验编号', '树脂编号', '样品编号', '试样编号', '记录编号', '产品编号', '物料编号')
                          WHERE rv.record_id = r.id
                            AND nullif(btrim(rv.value_text), '') IS NOT NULL
                            AND rv.value_text ILIKE '%%' || ? || '%%'
                      ))
                    """);
            args.add(term);
            args.add(term);
        }
        if (!request.fieldTerms().isEmpty()) {
            filters.append(" AND (");
            for (var index = 0; index < request.fieldTerms().size(); index++) {
                if (index > 0) filters.append(" OR ");
                filters.append("(coalesce(meta.field_name, meta.source_header, v.field_code) ILIKE '%%' || ? || '%%' ")
                        .append("OR ? ILIKE '%%' || coalesce(meta.field_name, meta.source_header, v.field_code) || '%%' ")
                        .append("OR (? IN ('配方比例', '比例') AND v.field_code = 'FORMULA.RATIO'))");
                args.add(request.fieldTerms().get(index));
                args.add(request.fieldTerms().get(index));
                args.add(request.fieldTerms().get(index));
            }
            filters.append(')');
        }
        var rankFilter = request.mode() == DataQueryMode.FILE_OVERVIEW ? "WHERE field_rank <= ?"
                : !request.recordTerms().isEmpty() ? "WHERE record_rank <= ?" : "";
        if (request.mode() == DataQueryMode.FILE_OVERVIEW) args.add(request.fieldsPerRecord());
        else if (!request.recordTerms().isEmpty()) args.add(request.recordLimit());
        args.add(sqlLimit);
        var sql = """
                WITH value_context AS (
                    SELECT r.id AS record_id, r.record_index,
                           CASE WHEN nullif(btrim(identity_value.value_text), '') IS NOT NULL
                                    THEN identity_value.value_text
                                WHEN r.synthetic_key = false THEN r.record_key
                                ELSE coalesce(nullif(btrim(r.sheet_name), ''), '工作表')
                                     || ' · 第 ' || r.record_index || ' 条记录' END AS record_label,
                           coalesce(a.sheet_name, r.sheet_name) AS sheet_name,
                           coalesce(a.row_number, r.source_row_number) AS source_row_number,
                           a.column_name, a.cell_address, v.id AS value_id, v.field_code,
                           coalesce(meta.field_name, meta.source_header, v.field_code) AS field_name,
                           meta.value_type, v.value_text AS field_value, v.normalized_unit,
                           row_number() OVER (PARTITION BY r.id ORDER BY
                               coalesce(a.row_number, r.source_row_number) NULLS LAST,
                               a.column_number NULLS LAST, v.value_path NULLS LAST, v.id) AS field_rank,
                           dense_rank() OVER (ORDER BY r.record_index, r.id) AS record_rank
                    FROM data.data_record r
                    JOIN data.data_value v ON v.record_id = r.id AND v.organization_id = r.organization_id
                    LEFT JOIN LATERAL (
                        SELECT candidate.sheet_name, candidate.row_number, candidate.column_number,
                               candidate.column_name, candidate.cell_address, candidate.binding_id,
                               candidate.value_path
                        FROM data.source_anchor candidate
                        WHERE candidate.record_id = r.id
                          AND (candidate.value_path = v.value_path
                               OR (candidate.binding_id = v.binding_id AND candidate.field_code = v.field_code)
                               OR candidate.id = v.source_anchor_id)
                        ORDER BY CASE WHEN candidate.value_path = v.value_path THEN 0
                                      WHEN candidate.binding_id = v.binding_id
                                           AND candidate.field_code = v.field_code THEN 1 ELSE 2 END,
                                 candidate.created_at DESC
                        LIMIT 1
                    ) a ON true
                    LEFT JOIN LATERAL (
                        SELECT m.field_name, m.source_header, m.value_type, m.mapping_jsonb
                        FROM data.import_mapping m
                        WHERE m.import_job_id = r.import_job_id
                          AND (m.mapping_jsonb->>'dataPath' = v.value_path
                               OR coalesce(m.mapping_jsonb->>'bindingId', m.field_code) = coalesce(a.binding_id, v.binding_id)
                               OR m.field_code = v.field_code)
                        ORDER BY CASE WHEN m.mapping_jsonb->>'dataPath' = v.value_path THEN 0
                                      WHEN coalesce(m.mapping_jsonb->>'bindingId', m.field_code)
                                           = coalesce(a.binding_id, v.binding_id) THEN 1 ELSE 2 END,
                                 CASE WHEN m.status IN ('CONFIRMED', 'MATCHED') THEN 0 ELSE 1 END,
                                 m.updated_at DESC
                        LIMIT 1
                    ) meta ON true
                    LEFT JOIN LATERAL (
                        SELECT iv.value_text
                        FROM data.data_value iv
                        JOIN LATERAL (
                            SELECT im.mapping_jsonb, im.field_name, im.source_header
                            FROM data.import_mapping im
                            WHERE im.import_job_id = r.import_job_id
                              AND (im.mapping_jsonb->>'dataPath' = iv.value_path
                                   OR coalesce(im.mapping_jsonb->>'bindingId', im.field_code) = iv.binding_id)
                            ORDER BY CASE WHEN im.mapping_jsonb->>'dataPath' = iv.value_path THEN 0 ELSE 1 END,
                                     CASE WHEN im.status IN ('CONFIRMED', 'MATCHED') THEN 0 ELSE 1 END,
                                     im.updated_at DESC
                            LIMIT 1
                        ) identity_mapping ON coalesce(identity_mapping.mapping_jsonb->>'identity', 'false') = 'true'
                            OR coalesce(identity_mapping.field_name, identity_mapping.source_header, '') IN
                               ('实验编号', '树脂编号', '样品编号', '试样编号', '记录编号', '产品编号', '物料编号')
                        WHERE iv.record_id = r.id AND nullif(btrim(iv.value_text), '') IS NOT NULL
                        ORDER BY CASE WHEN coalesce(identity_mapping.mapping_jsonb->>'identity', 'false') = 'true'
                                      THEN 0 ELSE 1 END,
                                 iv.value_path, iv.id
                        LIMIT 1
                    ) identity_value ON true
                    WHERE r.import_job_id = ? AND r.organization_id = ?
                      AND r.quality_status <> 'BLOCKED'
                      AND v.rag_eligible = true
                      AND (v.value_source <> 'FORMULA' OR v.calculation_status = 'VALID')
                      AND nullif(btrim(v.value_text), '') IS NOT NULL
                      %s
                )
                SELECT * FROM value_context
                %s
                ORDER BY record_index, field_rank
                LIMIT ?
                """.formatted(filters, rankFilter);
        return jdbc.query(sql, (rs, ignored) -> detailHit(rs, file), args.toArray());
    }

    private SourceFileHit detailHit(ResultSet rs, FileRow file) throws SQLException {
        var value = readable(rs.getString("field_value"), "—");
        var field = readable(rs.getString("field_name"), "未命名字段");
        var record = readable(rs.getString("record_label"), "未命名记录");
        var sheet = rs.getString("sheet_name");
        var cell = rs.getString("cell_address");
        var locator = "DATA_CENTER:" + readable(sheet, "") + ":" + readable(cell, "");
        var content = "记录：" + record + "；字段：" + field + "；值：" + value
                + (rs.getString("normalized_unit") == null || rs.getString("normalized_unit").isBlank()
                ? "" : " " + rs.getString("normalized_unit"));
        return new SourceFileHit(rs.getObject("value_id", UUID.class), file.fileId(), file.importJobId(),
                (Integer) rs.getObject("source_row_number"), rs.getString("column_name"), file.originalName(),
                content, 3.0, locator, record, null, field, value, rs.getString("normalized_unit"),
                rs.getString("value_type"), sheet, cell);
    }

    @Override
    public List<SourceFileMatch> searchSourceFiles(UUID organizationId, String query,
                                                   List<UUID> categoryIds, int limit) {
        return searchSourceFiles(organizationId, query, categoryIds, AccessScope.all(), limit);
    }

    @Override
    public List<SourceFileMatch> searchSourceFiles(UUID organizationId, String query,
                                                   List<UUID> categoryIds, Set<UUID> allowedImportJobIds,
                                                   int limit) {
        if (allowedImportJobIds != null && allowedImportJobIds.isEmpty()) return List.of();
        return searchSourceFiles(organizationId, query, categoryIds,
                allowedImportJobIds == null ? AccessScope.all() : AccessScope.selected(allowedImportJobIds), limit);
    }

    @Override
    public List<SourceFileMatch> searchSourceFiles(UUID organizationId, String query,
                                                   List<UUID> categoryIds, AccessScope accessScope,
                                                   int limit) {
        var scope = accessScope == null ? AccessScope.all() : accessScope;
        if (scope.deniesAll()) return List.of();
        var categories = categoryClause(categoryIds);
        var permission = permissionClause(scope);
        var sql = """
                SELECT j.id AS import_job_id, j.source_file_id, j.source_file_name, j.source_format,
                       coalesce(fo.content_type, CASE j.source_format WHEN 'CSV' THEN 'text/csv'
                           WHEN 'XLS' THEN 'application/vnd.ms-excel'
                           ELSE 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' END) AS content_type,
                       coalesce(fo.size_bytes, 0) AS size_bytes,
                       coalesce(j.completed_at, j.updated_at) AS updated_at,
                       r.record_key,
                       CASE WHEN r.synthetic_key = false THEN r.record_key
                            ELSE coalesce(nullif(btrim(r.sheet_name), ''), '工作表')
                                 || ' · 第 ' || r.record_index || ' 条记录' END AS record_label,
                       coalesce(a.sheet_name, r.sheet_name) AS sheet_name,
                       coalesce(a.row_number, r.source_row_number) AS source_row_number,
                       a.column_name, a.cell_address,
                       v.id AS value_id, v.field_code,
                       coalesce(meta.field_name, meta.source_header, v.field_code) AS field_name,
                       meta.value_type, v.value_text AS field_value, v.normalized_unit,
                       GREATEST(
                           CASE WHEN j.source_file_name ILIKE '%%' || ? || '%%' THEN 1.0 ELSE 0 END,
                           CASE WHEN r.record_key = ? THEN 3.0
                                WHEN r.record_key ILIKE '%%' || ? || '%%' THEN 2.0 ELSE 0 END,
                           CASE WHEN v.field_code = ? THEN 3.0
                                WHEN v.field_code ILIKE '%%' || ? || '%%' THEN 1.8 ELSE 0 END,
                           CASE WHEN coalesce(meta.field_name, meta.source_header, '') = ? THEN 3.0
                                WHEN coalesce(meta.field_name, meta.source_header, '') ILIKE '%%' || ? || '%%'
                                THEN 1.8 ELSE 0 END,
                           CASE WHEN v.value_text = ? THEN 3.0
                                WHEN v.value_text ILIKE '%%' || ? || '%%' THEN 1.5 ELSE 0 END
                       ) AS score
                FROM data.completed_source_file_projection fp
                JOIN data.import_job j ON j.id = fp.import_job_id AND j.organization_id = fp.organization_id
                JOIN data.data_record r ON r.import_job_id = j.id AND r.organization_id = j.organization_id
                JOIN data.data_value v ON v.record_id = r.id AND v.organization_id = r.organization_id
                LEFT JOIN LATERAL (
                    SELECT candidate.*
                    FROM data.source_anchor candidate
                    WHERE candidate.record_id = r.id
                      AND (candidate.value_path = v.value_path
                           OR (candidate.binding_id = v.binding_id AND candidate.field_code = v.field_code)
                           OR candidate.id = v.source_anchor_id)
                    ORDER BY CASE WHEN candidate.value_path = v.value_path THEN 0
                                  WHEN candidate.binding_id = v.binding_id
                                       AND candidate.field_code = v.field_code THEN 1 ELSE 2 END,
                             candidate.created_at DESC
                    LIMIT 1
                ) a ON true
                LEFT JOIN ops.file_object fo ON fo.id = j.source_file_id AND fo.organization_id = j.organization_id
                LEFT JOIN LATERAL (
                    SELECT m.field_name, m.source_header, m.value_type
                    FROM data.import_mapping m
                    WHERE m.import_job_id = j.id
                      AND (m.mapping_jsonb->>'dataPath' = v.value_path
                           OR coalesce(m.mapping_jsonb->>'bindingId', m.field_code) = coalesce(a.binding_id, v.binding_id)
                           OR m.field_code = v.field_code)
                    ORDER BY CASE WHEN m.mapping_jsonb->>'dataPath' = v.value_path THEN 0
                                  WHEN coalesce(m.mapping_jsonb->>'bindingId', m.field_code)
                                       = coalesce(a.binding_id, v.binding_id) THEN 1 ELSE 2 END,
                             CASE WHEN m.status IN ('CONFIRMED', 'MATCHED') THEN 0 ELSE 1 END,
                             m.updated_at DESC
                    LIMIT 1
                ) meta ON true
                WHERE j.organization_id = ?
                  %s
                  %s
                  AND r.quality_status <> 'BLOCKED'
                  AND v.rag_eligible = true
                  AND (v.value_source <> 'FORMULA' OR v.calculation_status = 'VALID')
                  AND (j.source_file_name ILIKE '%%' || ? || '%%'
                       OR r.record_key ILIKE '%%' || ? || '%%'
                       OR v.field_code ILIKE '%%' || ? || '%%'
                       OR coalesce(meta.field_name, meta.source_header, '') ILIKE '%%' || ? || '%%'
                       OR v.value_text ILIKE '%%' || ? || '%%')
                ORDER BY score DESC, j.completed_at DESC NULLS LAST, j.updated_at DESC,
                         r.record_index, v.field_code, v.value_path
                LIMIT ?
                """.formatted(categories, permission.sql());
        var args = new ArrayList<Object>();
        for (var ignored = 0; ignored < 9; ignored++) args.add(query);
        args.add(organizationId);
        if (categoryIds != null) args.addAll(categoryIds);
        args.addAll(permission.parameters());
        for (var ignored = 0; ignored < 5; ignored++) args.add(query);
        args.add(Math.min(1000, Math.max(20, Math.max(1, limit) * 25)));
        var rows = jdbc.query(sql, this::mapRow, args.toArray());
        var grouped = new LinkedHashMap<UUID, Aggregate>();
        for (var row : rows) {
            var aggregate = grouped.computeIfAbsent(row.importJobId(), ignored -> new Aggregate(row));
            if (aggregate.hits.size() < 10) aggregate.hits.add(hit(row));
        }
        return grouped.values().stream().limit(Math.max(1, limit)).map(item -> item.view(query)).toList();
    }

    private Row mapRow(ResultSet rs, int ignored) throws SQLException {
        return new Row(rs.getObject("import_job_id", UUID.class), rs.getObject("source_file_id", UUID.class),
                rs.getString("source_file_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                rs.getTimestamp("updated_at").toInstant(),
                readable(rs.getString("record_label"), rs.getString("record_key")), rs.getString("sheet_name"),
                (Integer) rs.getObject("source_row_number"), rs.getString("column_name"),
                rs.getString("cell_address"), rs.getObject("value_id", UUID.class),
                rs.getString("field_code"), rs.getString("field_name"),
                rs.getString("field_value"), rs.getString("normalized_unit"), rs.getString("value_type"),
                rs.getDouble("score"));
    }

    private Hit hit(Row row) {
        var value = row.value() == null || row.value().isBlank() ? "未填写" : row.value();
        var fieldName = readable(row.fieldName(), row.fieldCode());
        var snippet = "记录=" + readable(row.recordKey(), "未命名记录")
                + "；字段=" + fieldName
                + "；值=" + value + (row.unit() == null || row.unit().isBlank() ? "" : " " + row.unit());
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
        return "AND j.category_id IN (" + String.join(",", java.util.Collections.nCopies(categoryIds.size(), "?")) + ")";
    }

    private ScopeClause permissionClause(AccessScope scope) {
        return switch (scope.type()) {
            case "ALL" -> new ScopeClause("", List.of());
            case "SELF" -> new ScopeClause("AND j.created_by = ?", List.of(scope.actorId()));
            case "SELECTED" -> idsClause("AND j.id IN (%s)", scope.targetIds());
            case "CATEGORY" -> idsClause("AND j.category_id IN (%s)", scope.targetIds());
            case "PROJECT" -> idsClause("""
                    AND EXISTS (
                        SELECT 1 FROM core.project_resource_link prl
                        WHERE prl.organization_id = j.organization_id
                          AND prl.resource_type = 'DATA_IMPORT_JOB'
                          AND prl.resource_id = j.id
                          AND prl.project_id IN (%s)
                    )
                    """, scope.targetIds());
            default -> new ScopeClause("AND 1 = 0", List.of());
        };
    }

    private ScopeClause idsClause(String template, Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) return new ScopeClause("AND 1 = 0", List.of());
        return new ScopeClause(template.formatted(String.join(",", java.util.Collections.nCopies(ids.size(), "?"))),
                new ArrayList<>(ids));
    }

    private record Row(UUID importJobId, UUID fileId, String originalName, String contentType, long size,
                       java.time.Instant updatedAt, String recordKey, String sheetName, Integer rowNumber,
                       String column, String cellAddress, UUID valueId, String fieldCode, String fieldName,
                       String value, String unit, String valueType, double score) { }

    private record ScopeClause(String sql, List<Object> parameters) { }

    private record FileRow(UUID importJobId, UUID fileId, String originalName, String contentType,
                           long size, java.time.Instant updatedAt) { }

    private record DetailCounts(long recordCount, long valueCount) { }

    private static final class Aggregate {
        private final Row row;
        private final List<Hit> hits = new ArrayList<>();

        private Aggregate(Row row) {
            this.row = row;
        }

        private SourceFileMatch view(String query) {
            var normalizedQuery = normalizeEvidence(query);
            var matched = normalizedQuery.length() >= 2 && (normalizeEvidence(row.originalName()).contains(normalizedQuery)
                    || hits.stream().anyMatch(hit -> normalizeEvidence(hit.snippet()).contains(normalizedQuery)))
                    ? List.of(query) : List.<String>of();
            return new SourceFileMatch(row.fileId(), row.importJobId(), row.originalName(), row.contentType(),
                    row.size(), row.updatedAt(), List.copyOf(hits), matched);
        }

        private static String normalizeEvidence(String value) {
            return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9\\u4E00-\\u9FFF]", "");
        }
    }
}
