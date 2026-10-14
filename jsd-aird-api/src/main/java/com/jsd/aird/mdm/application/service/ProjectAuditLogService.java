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
        var source = auditSource(projectId);
        var parts = buildWhere(query);
        var baseArgs = new ArrayList<>(source.args());
        baseArgs.addAll(parts.args());
        var total = jdbc.queryForObject(source.sql() + " SELECT count(*) FROM audit_source al " + joins() + parts.where(),
                Long.class, baseArgs.toArray());
        var totalValue = total == null ? 0L : total;
        var args = new ArrayList<>(baseArgs);
        args.add(safeSize);
        args.add((safePage - 1L) * safeSize);

        var items = jdbc.query(source.sql() + """
                SELECT al.id, al.aggregate_type, al.aggregate_id, al.action,
                       al.detail_jsonb, al.created_at, al.trace_id,
                       COALESCE(u.display_name, u.username,
                                al.source_operator_name, al.detail_jsonb ->> 'operator', '系统') AS operator,
                       CASE al.aggregate_type
                           WHEN 'PROJECT' THEN p.name
                           WHEN 'PROJECT_STAGE' THEN s.name
                           WHEN 'PROJECT_TASK' THEN t.name
                           WHEN 'EXPERIMENT' THEN e.title
                           WHEN 'PROJECT_DOCUMENT' THEN d.title
                           WHEN 'CUSTOMER_REQUIREMENT' THEN cr.title
                           ELSE COALESCE(al.detail_jsonb ->> 'objectName', al.detail_jsonb ->> 'name')
                       END AS object_name
                FROM audit_source al
                """ + joins() + parts.where() + " ORDER BY al.created_at DESC, al.id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> map(rs, rowNum, projectId), args.toArray());

        var totalPages = totalValue == 0 ? 0 : (totalValue + safeSize - 1) / safeSize;
        return new PageResponse<>(items, safePage, safeSize, totalValue, totalPages);
    }

    private SourceParts auditSource(UUID projectId) {
        var organizationId = ActorContext.required().organizationId();
        var args = new ArrayList<Object>();
        args.add(organizationId);
        args.add(projectId);
        args.add(projectId);
        args.add(projectId);
        args.add(projectId);
        args.add(projectId);
        args.add(projectId);
        args.add(projectId);
        args.add(projectId.toString());
        args.add(projectId.toString());
        args.add(projectId);
        args.add(organizationId);
        args.add(projectId);
        return new SourceParts("""
                WITH audit_source AS (
                    SELECT al.id, al.actor_id::text AS actor_id, al.action::text AS action,
                           al.aggregate_type::text AS aggregate_type,
                           al.aggregate_id, al.detail_jsonb, al.created_at,
                           NULL::text AS source_operator_name, NULL::text AS trace_id
                    FROM ops.audit_log al
                    WHERE (al.organization_id = ? OR al.organization_id IS NULL)
                      AND (
                           (al.aggregate_type = 'PROJECT' AND al.aggregate_id = ?)
                        OR (al.aggregate_type = 'PROJECT_STAGE' AND EXISTS (
                               SELECT 1 FROM mdm.project_stage ps
                               WHERE ps.id = al.aggregate_id AND ps.project_id = ?))
                        OR (al.aggregate_type = 'PROJECT_TASK' AND EXISTS (
                               SELECT 1 FROM mdm.project_task pt
                               WHERE pt.id = al.aggregate_id AND pt.project_id = ?))
                        OR (al.aggregate_type = 'EXPERIMENT' AND EXISTS (
                               SELECT 1 FROM rnd.experiment re
                               WHERE re.id = al.aggregate_id AND re.project_id = ? AND re.deleted = false))
                        OR (al.aggregate_type = 'PROJECT_DOCUMENT' AND EXISTS (
                               SELECT 1 FROM mdm.project_document pd
                               WHERE pd.id = al.aggregate_id AND pd.project_id = ?))
                        OR (al.aggregate_type = 'CUSTOMER_REQUIREMENT' AND EXISTS (
                               SELECT 1 FROM mdm.customer_requirement cr
                               WHERE cr.id = al.aggregate_id
                                 AND (cr.project_id = ?
                                      OR cr.assigned_project_ids @> jsonb_build_array(CAST(? AS text)))))
                        OR al.detail_jsonb ->> 'projectId' = ?
                        OR al.detail_jsonb ->> 'project_id' = ?
                      )
                    UNION ALL
                    SELECT pda.id, pda.operator_id::text, pda.action::text,
                           'PROJECT_DOCUMENT', pda.document_id,
                           COALESCE(pda.after_jsonb, pda.before_jsonb, '{}'::jsonb),
                           pda.created_at, pda.operator_name::text, pda.trace_id::text
                    FROM mdm.project_document_audit pda
                    JOIN mdm.project_document pd ON pd.id = pda.document_id
                    WHERE pd.project_id = ?
                    UNION ALL
                    SELECT ea.id, ea.operator_id::text,
                           CASE WHEN ea.action LIKE 'STATUS_%'
                                THEN 'EXPERIMENT_STATUS_CHANGED'
                                ELSE 'EXPERIMENT_' || ea.action END,
                           'EXPERIMENT', ea.experiment_id,
                           COALESCE(ea.after_jsonb, ea.before_jsonb, '{}'::jsonb),
                           ea.created_at, ea.operator_name::text, NULL::text
                    FROM rnd.experiment_audit ea
                    JOIN rnd.experiment e ON e.id = ea.experiment_id
                    WHERE ea.organization_id = ? AND e.project_id = ? AND e.deleted = false
                )
                """, args);
    }

    private QueryParts buildWhere(Query query) {
        var where = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();

        if (hasText(query.objectType())) {
            where.append(" AND al.aggregate_type = ?");
            args.add(query.objectType().trim().toUpperCase());
        }
        if (hasText(query.action())) {
            where.append(" AND al.action = ?");
            args.add(query.action().trim().toUpperCase());
        }
        if (hasText(query.operator())) {
            where.append(" AND COALESCE(u.display_name, u.username, al.source_operator_name, al.detail_jsonb ->> 'operator', '系统') ILIKE ?");
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
                    + "OR COALESCE(p.name, s.name, t.name, e.title, d.title, cr.title, '') ILIKE ? "
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

    private static String joins() {
        return " LEFT JOIN iam.app_user u ON u.id::text = al.actor_id" +
                " LEFT JOIN mdm.project p ON al.aggregate_type = 'PROJECT' AND p.id = al.aggregate_id" +
                " LEFT JOIN mdm.project_stage s ON al.aggregate_type = 'PROJECT_STAGE' AND s.id = al.aggregate_id" +
                " LEFT JOIN mdm.project_task t ON al.aggregate_type = 'PROJECT_TASK' AND t.id = al.aggregate_id" +
                " LEFT JOIN rnd.experiment e ON al.aggregate_type = 'EXPERIMENT' AND e.id = al.aggregate_id AND e.deleted = false" +
                " LEFT JOIN mdm.project_document d ON al.aggregate_type = 'PROJECT_DOCUMENT' AND d.id = al.aggregate_id" +
                " LEFT JOIN mdm.customer_requirement cr ON al.aggregate_type = 'CUSTOMER_REQUIREMENT' AND cr.id = al.aggregate_id";
    }

    private static ProjectAuditLog map(ResultSet rs, int ignored, UUID projectId) throws SQLException {
        var detailJson = readJson(rs.getString("detail_jsonb"));
        var detail = text(detailJson, "detail", "message");
        if (detail == null && detailJson != null && !detailJson.isEmpty() && !"{}".equals(detailJson)) {
            detail = detailJson;
        }
        var traceId = rs.getString("trace_id");
        if (traceId == null) traceId = text(readJson(rs.getString("detail_jsonb")), "traceId", "trace_id");
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

    private record SourceParts(String sql, List<Object> args) { }

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
