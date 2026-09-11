package com.jsd.aird.mdm.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 项目详情日志查询。日志只从通用 ops.audit_log 读取，避免项目详情维护一套
 * 与全局审计不一致的日志表。
 */
@Service
public class ProjectAuditLogService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ProjectAuditLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<ProjectAuditLog> page(UUID projectId, Query query) {
        if (projectId == null) {
            return new PageResponse<>(List.of(), 1, 0, 0, 0);
        }

        var safePage = Math.max(1, query.page());
        var safeSize = Math.min(100, Math.max(1, query.size()));
        var parts = buildWhere(projectId, query);
        var total = jdbc.queryForObject("SELECT count(*) FROM ops.audit_log al " + joins() + parts.where(),
                Long.class, parts.args().toArray());
        var totalValue = total == null ? 0L : total;
        var args = new ArrayList<>(parts.args());
        args.add(safeSize);
        args.add((safePage - 1L) * safeSize);

        var items = jdbc.query("""
                SELECT al.id, al.aggregate_type, al.aggregate_id, al.action,
                       al.detail_jsonb, al.created_at,
                       COALESCE(u.display_name, u.username,
                                al.detail_jsonb ->> 'operator', '系统') AS operator,
                       CASE al.aggregate_type
                           WHEN 'PROJECT' THEN p.name
                           WHEN 'PROJECT_STAGE' THEN s.name
                           WHEN 'PROJECT_TASK' THEN t.name
                           WHEN 'EXPERIMENT' THEN e.title
                           WHEN 'PROJECT_DOCUMENT' THEN d.title
                           ELSE COALESCE(al.detail_jsonb ->> 'objectName', al.detail_jsonb ->> 'name')
                       END AS object_name
                FROM ops.audit_log al
                """ + joins() + parts.where() + " ORDER BY al.created_at DESC, al.id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> map(rs, rowNum, projectId), args.toArray());

        var totalPages = totalValue == 0 ? 0 : (totalValue + safeSize - 1) / safeSize;
        return new PageResponse<>(items, safePage, safeSize, totalValue, totalPages);
    }

    private QueryParts buildWhere(UUID projectId, Query query) {
        var where = new StringBuilder(" WHERE (al.organization_id = ? OR al.organization_id IS NULL) AND ");
        var args = new ArrayList<Object>();
        args.add(ActorContext.required().organizationId());
        where.append(projectScope());
        var projectText = projectId.toString();
        // The JSON fallback covers audit events emitted by modules whose aggregate
        // table is not a project table but which carry projectId in their detail.
        args.add(projectId);
        args.add(projectId);
        args.add(projectId);
        args.add(projectId);
        args.add(projectId);
        args.add(projectText);
        args.add(projectText);

        if (hasText(query.objectType())) {
            where.append(" AND al.aggregate_type = ?");
            args.add(query.objectType().trim().toUpperCase());
        }
        if (hasText(query.action())) {
            where.append(" AND al.action = ?");
            args.add(query.action().trim().toUpperCase());
        }
        if (hasText(query.operator())) {
            where.append(" AND COALESCE(u.display_name, u.username, al.detail_jsonb ->> 'operator', '系统') ILIKE ?");
            args.add("%" + query.operator().trim() + "%");
        }
        if (query.createdFrom() != null) {
            where.append(" AND al.created_at >= ?");
            args.add(query.createdFrom());
        }
        if (query.createdTo() != null) {
            where.append(" AND al.created_at <= ?");
            args.add(query.createdTo());
        }
        if (hasText(query.keyword())) {
            where.append(" AND (COALESCE(al.action, '') ILIKE ? "
                    + "OR COALESCE(p.name, s.name, t.name, e.title, d.title, '') ILIKE ? "
                    + "OR COALESCE(al.detail_jsonb ->> 'objectName', '') ILIKE ? "
                    + "OR COALESCE(al.detail_jsonb ->> 'name', '') ILIKE ? "
                    + "OR COALESCE(al.detail_jsonb ->> 'detail', '') ILIKE ? "
                    + "OR COALESCE(al.detail_jsonb::text, '') ILIKE ?)");
            var keyword = "%" + query.keyword().trim() + "%";
            args.add(keyword);
            args.add(keyword);
            args.add(keyword);
            args.add(keyword);
            args.add(keyword);
            args.add(keyword);
        }
        return new QueryParts(where, args);
    }

    private static String projectScope() {
        return "(" +
                "(al.aggregate_type = 'PROJECT' AND al.aggregate_id = ?)" +
                " OR (al.aggregate_type = 'PROJECT_STAGE' AND EXISTS (SELECT 1 FROM mdm.project_stage ps WHERE ps.id = al.aggregate_id AND ps.project_id = ?))" +
                " OR (al.aggregate_type = 'PROJECT_TASK' AND EXISTS (SELECT 1 FROM mdm.project_task pt WHERE pt.id = al.aggregate_id AND pt.project_id = ?))" +
                " OR (al.aggregate_type = 'EXPERIMENT' AND EXISTS (SELECT 1 FROM rnd.experiment re WHERE re.id = al.aggregate_id AND re.project_id = ? AND re.deleted = false))" +
                " OR (al.aggregate_type = 'PROJECT_DOCUMENT' AND EXISTS (SELECT 1 FROM mdm.project_document pd WHERE pd.id = al.aggregate_id AND pd.project_id = ?) )" +
                " OR al.detail_jsonb ->> 'projectId' = ?" +
                " OR al.detail_jsonb ->> 'project_id' = ?" +
                ")";
    }

    private static String joins() {
        return " LEFT JOIN iam.app_user u ON u.id = al.actor_id" +
                " LEFT JOIN mdm.project p ON al.aggregate_type = 'PROJECT' AND p.id = al.aggregate_id" +
                " LEFT JOIN mdm.project_stage s ON al.aggregate_type = 'PROJECT_STAGE' AND s.id = al.aggregate_id" +
                " LEFT JOIN mdm.project_task t ON al.aggregate_type = 'PROJECT_TASK' AND t.id = al.aggregate_id" +
                " LEFT JOIN rnd.experiment e ON al.aggregate_type = 'EXPERIMENT' AND e.id = al.aggregate_id AND e.deleted = false" +
                " LEFT JOIN mdm.project_document d ON al.aggregate_type = 'PROJECT_DOCUMENT' AND d.id = al.aggregate_id";
    }

    private static ProjectAuditLog map(ResultSet rs, int ignored, UUID projectId) throws SQLException {
        var detailJson = readJson(rs.getString("detail_jsonb"));
        var detail = text(detailJson, "detail", "message");
        if (detail == null && detailJson != null && !detailJson.isEmpty() && !"{}".equals(detailJson)) {
            detail = detailJson;
        }
        var traceId = text(readJson(rs.getString("detail_jsonb")), "traceId", "trace_id");
        return new ProjectAuditLog(
                rs.getObject("id", UUID.class),
                projectId,
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("object_name"),
                rs.getString("action"),
                rs.getString("operator"),
                detail,
                traceId,
                rs.getTimestamp("created_at").toInstant());
    }

    private static String readJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        try {
            return JSON.readTree(raw).toString();
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static String text(String json, String... fields) {
        try {
            var node = JSON.readTree(json == null ? "{}" : json);
            for (var field : fields) {
                if (node.hasNonNull(field) && !node.path(field).asText().isBlank()) return node.path(field).asText();
            }
        } catch (Exception ignored) {
            // Keep the audit row available even when an old detail payload is malformed.
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record QueryParts(StringBuilder where, List<Object> args) { }

    public record Query(String keyword, String objectType, String action, String operator,
                        Instant createdFrom, Instant createdTo, int page, int size) {
        public Query {
            page = page < 1 ? 1 : page;
            size = size < 1 ? 10 : size;
        }
    }

    public record ProjectAuditLog(UUID id, UUID projectId, String objectType, UUID objectId, String objectName,
                                  String action, String operator, String detail, String traceId,
                                  Instant createdAt) { }
}
