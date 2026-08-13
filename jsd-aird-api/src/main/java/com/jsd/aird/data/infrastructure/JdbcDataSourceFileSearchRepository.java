package com.jsd.aird.data.infrastructure;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    public List<SourceFileMatch> searchSourceFiles(UUID organizationId, String query,
                                                   List<UUID> categoryIds, int limit) {
        var categories = categoryClause(categoryIds);
        var sql = """
                SELECT j.id AS import_job_id, j.source_file_id, j.source_file_name, j.source_format,
                       coalesce(fo.content_type, CASE j.source_format WHEN 'CSV' THEN 'text/csv'
                           WHEN 'XLS' THEN 'application/vnd.ms-excel'
                           ELSE 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' END) AS content_type,
                       coalesce(fo.size_bytes, 0) AS size_bytes, coalesce(j.completed_at, j.updated_at) AS updated_at,
                       s.sheet_name, r.source_row_number, cells.key AS column_name, cells.value AS cell_value,
                       GREATEST(ts_rank_cd(r.source_search_vector, plainto_tsquery('simple', ?)),
                           CASE WHEN j.source_file_name ILIKE '%%' || ? || '%%' THEN 1 ELSE 0 END) AS score
                FROM data.completed_source_file_projection fp
                JOIN data.import_job j ON j.id = fp.import_job_id
                LEFT JOIN ops.file_object fo ON fo.id = j.source_file_id AND fo.organization_id = j.organization_id
                LEFT JOIN data.import_sheet s ON s.import_job_id = j.id
                LEFT JOIN data.staging_row r ON r.import_job_id = j.id AND r.import_sheet_id = s.id
                LEFT JOIN LATERAL jsonb_each_text(r.raw_values_jsonb) cells ON true
                WHERE j.organization_id = ?
                  %s
                  AND (j.source_file_name ILIKE '%%' || ? || '%%'
                       OR r.source_search_vector @@ plainto_tsquery('simple', ?)
                       OR cells.value ILIKE '%%' || ? || '%%'
                       OR cells.key ILIKE '%%' || ? || '%%')
                ORDER BY score DESC, j.completed_at DESC NULLS LAST, j.updated_at DESC,
                         s.sheet_order, r.source_row_number
                LIMIT ?
                """.formatted(categories);
        var args = new ArrayList<Object>();
        args.add(query); args.add(query); args.add(organizationId);
        if (categoryIds != null) args.addAll(categoryIds);
        args.add(query); args.add(query); args.add(query); args.add(query); args.add(Math.min(1000, Math.max(20, limit * 25)));
        var rows = jdbc.query(sql, this::mapRow, args.toArray());
        var grouped = new LinkedHashMap<UUID, Aggregate>();
        for (var row : rows) {
            var aggregate = grouped.computeIfAbsent(row.importJobId(), ignored -> new Aggregate(row));
            if (aggregate.hits.size() < 10) aggregate.hits.add(hit(row));
        }
        return grouped.values().stream().limit(limit).map(Aggregate::view).toList();
    }

    private Row mapRow(ResultSet rs, int ignored) throws SQLException {
        return new Row(rs.getObject("import_job_id", UUID.class), rs.getObject("source_file_id", UUID.class),
                rs.getString("source_file_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("sheet_name"),
                (Integer) rs.getObject("source_row_number"), rs.getString("column_name"), rs.getString("cell_value"),
                rs.getDouble("score"));
    }

    private Hit hit(Row row) {
        var snippet = row.value() == null || row.value().isBlank() ? row.originalName() : row.value();
        if (snippet.length() > 800) snippet = snippet.substring(0, 800) + "…";
        var cell = cellAddress(row.column(), row.rowNumber());
        var identity = row.importJobId() + ":" + row.sheetName() + ":" + row.rowNumber() + ":" + row.column();
        return new Hit(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)), snippet,
                Math.max(0.1, row.score()), row.sheetName(), row.rowNumber(), row.column(), cell);
    }

    private String cellAddress(String column, Integer row) {
        if (column == null || row == null) return null;
        var normalized = column.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z]{1,3}") ? normalized + row : null;
    }

    private String categoryClause(List<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) return "";
        return "AND j.category_id IN (" + String.join(",", java.util.Collections.nCopies(categoryIds.size(), "?")) + ")";
    }

    private record Row(UUID importJobId, UUID fileId, String originalName, String contentType, long size,
                       java.time.Instant updatedAt, String sheetName, Integer rowNumber, String column,
                       String value, double score) { }

    private static final class Aggregate {
        private final Row row;
        private final List<Hit> hits = new ArrayList<>();
        private Aggregate(Row row) { this.row = row; }
        private SourceFileMatch view() {
            return new SourceFileMatch(row.fileId(), row.importJobId(), row.originalName(), row.contentType(),
                    row.size(), row.updatedAt(), List.copyOf(hits));
        }
    }
}
