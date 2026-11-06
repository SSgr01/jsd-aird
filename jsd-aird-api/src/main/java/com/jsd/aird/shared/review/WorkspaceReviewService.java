package com.jsd.aird.shared.review;

import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceReviewService {
    private final JdbcTemplate jdbc;

    public WorkspaceReviewService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record State(String status, Instant submittedAt, Instant approvedAt, String reviewComment) { }
    public record Event(String action, String comment, UUID operatorId, String operatorName, Instant createdAt) { }

    public List<Event> history(String type, UUID id) {
        var actor = ActorContext.required();
        return jdbc.query("SELECT e.action,e.comment,e.operator_id,COALESCE(u.display_name,u.username,'未知用户'),e.created_at "
                        + "FROM ops.workspace_review_event e LEFT JOIN iam.app_user u ON u.id=e.operator_id "
                        + "WHERE e.organization_id=? AND e.resource_type=? AND e.resource_id=? ORDER BY e.created_at DESC,e.id DESC",
                (rs, row) -> new Event(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getTimestamp(5).toInstant()),
                actor.organizationId(), type, id);
    }

    public State get(String type, UUID id) {
        var actor = ActorContext.required();
        var updatedAt = resourceUpdatedAt(type, actor.organizationId(), id, false);
        var rows = jdbc.query("SELECT status, resource_updated_at, submitted_at, approved_at, review_comment FROM ops.workspace_review "
                        + "WHERE organization_id=? AND resource_type=? AND resource_id=?",
                (rs, row) -> new Stored(rs.getString(1), rs.getTimestamp(2).toInstant(),
                        instant(rs.getTimestamp(3)), instant(rs.getTimestamp(4)), rs.getString(5)),
                actor.organizationId(), type, id);
        if (rows.isEmpty() || !updatedAt.equals(rows.getFirst().updatedAt())) return new State("NOT_SUBMITTED", null, null, null);
        var row = rows.getFirst();
        return new State(row.status(), row.submittedAt(), row.approvedAt(), row.reviewComment());
    }

    @Transactional
    public State submit(String type, UUID id) {
        var actor = ActorContext.required();
        var updatedAt = resourceUpdatedAt(type, actor.organizationId(), id, true);
        var state = get(type, id);
        if (!"NOT_SUBMITTED".equals(state.status()) && !"REJECTED".equals(state.status()))
            throw new ApiException(ApiErrorCode.REVIEW_STATE_CONFLICT, "当前内容已提交审核");
        jdbc.update("INSERT INTO ops.workspace_review (organization_id,resource_type,resource_id,status,resource_updated_at,submitted_by,submitted_at,approved_by,approved_at) "
                        + "VALUES (?,?,?,'SUBMITTED',?,?,now(),null,null) ON CONFLICT (organization_id,resource_type,resource_id) "
                        + "DO UPDATE SET status='SUBMITTED',resource_updated_at=excluded.resource_updated_at,submitted_by=excluded.submitted_by,"
                        + "submitted_at=now(),approved_by=null,approved_at=null,review_comment=null,rejected_by=null,rejected_at=null",
                actor.organizationId(), type, id, Timestamp.from(updatedAt), actor.userId());
        event(type, id, "SUBMITTED", null);
        return get(type, id);
    }

    @Transactional
    public State approve(String type, UUID id) {
        var actor = ActorContext.required();
        resourceUpdatedAt(type, actor.organizationId(), id, true);
        if (!"SUBMITTED".equals(get(type, id).status()))
            throw new ApiException(ApiErrorCode.REVIEW_STATE_CONFLICT, "只有待审核内容可以通过审核");
        jdbc.update("UPDATE ops.workspace_review SET status='APPROVED',approved_by=?,approved_at=now() "
                        + "WHERE organization_id=? AND resource_type=? AND resource_id=?",
                actor.userId(), actor.organizationId(), type, id);
        event(type, id, "APPROVED", null);
        return get(type, id);
    }

    @Transactional
    public State reject(String type, UUID id, String reason) {
        if (!"QUALITY_RECORD".equals(type) && !"PRODUCTION_UPLOAD".equals(type) && !"KNOWLEDGE_VERSION".equals(type))
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "当前资源不支持驳回");
        if (reason == null || reason.isBlank())
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "请填写驳回原因");
        var actor = ActorContext.required();
        resourceUpdatedAt(type, actor.organizationId(), id, true);
        if (!"SUBMITTED".equals(get(type, id).status()))
            throw new ApiException(ApiErrorCode.REVIEW_STATE_CONFLICT, "只有待审核内容可以驳回");
        jdbc.update("UPDATE ops.workspace_review SET status='REJECTED',review_comment=?,rejected_by=?,rejected_at=now() "
                        + "WHERE organization_id=? AND resource_type=? AND resource_id=?",
                reason.trim(), actor.userId(), actor.organizationId(), type, id);
        event(type, id, "REJECTED", reason.trim());
        return get(type, id);
    }

    public void requireApproved(String type, UUID id) {
        if (!"APPROVED".equals(get(type, id).status()))
            throw new ApiException(ApiErrorCode.REVIEW_REQUIRED, "当前内容须审核通过后才能发布");
    }

    private void event(String type, UUID id, String action, String comment) {
        var actor = ActorContext.required();
        jdbc.update("INSERT INTO ops.workspace_review_event (id,organization_id,resource_type,resource_id,action,comment,operator_id) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), actor.organizationId(), type, id, action, comment, actor.userId());
    }

    private Instant resourceUpdatedAt(String type, UUID organizationId, UUID id, boolean lock) {
        String sql = switch (type) {
            case "PROJECT_DOCUMENT" -> "SELECT d.updated_at FROM mdm.project_document d JOIN mdm.project p ON p.id=d.project_id "
                    + "WHERE p.organization_id=? AND d.id=? AND d.deleted=false AND d.status<>'ARCHIVED'";
            case "QUALITY_RECORD" -> "SELECT updated_at FROM quality.data_record WHERE organization_id=? AND id=? AND deleted=false";
            case "PRODUCTION_ORDER" -> "SELECT updated_at FROM mfg.production_order WHERE organization_id=? AND id=? AND status='DRAFT'";
            case "PRODUCTION_UPLOAD" -> "SELECT updated_at FROM mfg.production_upload WHERE organization_id=? AND id=? AND status" + (lock ? "='SAVED'" : "<>'DELETED'");
            case "KNOWLEDGE_VERSION" -> "SELECT COALESCE((SELECT r.updated_at FROM kb.document_review_revision r "
                    + "WHERE r.document_version_id=v.id ORDER BY r.revision_no DESC LIMIT 1), v.updated_at) "
                    + "FROM kb.document_version v JOIN kb.document d ON d.id=v.document_id "
                    + "WHERE d.organization_id=? AND v.id=?" + (lock ? " AND v.review_status IN ('PENDING_REVIEW','REJECTED')" : "");
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST, "未知的审核资源类型");
        };
        List<Instant> rows = jdbc.query(sql + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> rs.getTimestamp(1).toInstant(), organizationId, id);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND, "审核资源不存在");
        return rows.getFirst();
    }

    private static Instant instant(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }
    private record Stored(String status, Instant updatedAt, Instant submittedAt, Instant approvedAt, String reviewComment) { }
}
