package com.jsd.aird.rnd.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jsd.aird.rnd.application.port.QuotationReviewRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuotationReviewRepository implements QuotationReviewRepository {
    private final JdbcTemplate jdbc;

    public JdbcQuotationReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Review ensure(UUID organizationId, UUID quotationId) {
        jdbc.update("""
                INSERT INTO rnd.quotation_review (quotation_id, organization_id)
                VALUES (?, ?) ON CONFLICT (quotation_id) DO NOTHING
                """, quotationId, organizationId);
        return jdbc.query("""
                SELECT review_status, submitted_by, submitted_at, reviewed_by,
                       reviewed_at, review_comment, lock_version
                FROM rnd.quotation_review
                WHERE organization_id = ? AND quotation_id = ?
                FOR UPDATE
                """, (rs, row) -> new Review(rs.getString("review_status"),
                rs.getObject("submitted_by", UUID.class), instant(rs, "submitted_at"),
                rs.getObject("reviewed_by", UUID.class), instant(rs, "reviewed_at"),
                rs.getString("review_comment"), rs.getLong("lock_version")),
                organizationId, quotationId).stream().findFirst().orElseThrow();
    }

    @Override
    public Review transition(UUID organizationId, UUID quotationId, long expectedVersion,
                             String fromStatus, String toStatus, int quotationVersion,
                             UUID actorId, String comment) {
        var updated = jdbc.update("""
                UPDATE rnd.quotation_review
                SET review_status = ?,
                    submitted_by = CASE WHEN ? = 'SUBMITTED' THEN ? WHEN ? = 'NOT_SUBMITTED' THEN NULL ELSE submitted_by END,
                    submitted_at = CASE WHEN ? = 'SUBMITTED' THEN now() WHEN ? = 'NOT_SUBMITTED' THEN NULL ELSE submitted_at END,
                    reviewed_by = CASE WHEN ? IN ('APPROVED', 'REJECTED') THEN ? WHEN ? = 'NOT_SUBMITTED' THEN NULL ELSE reviewed_by END,
                    reviewed_at = CASE WHEN ? IN ('APPROVED', 'REJECTED') THEN now() WHEN ? = 'NOT_SUBMITTED' THEN NULL ELSE reviewed_at END,
                    review_comment = ?, lock_version = lock_version + 1, updated_at = now()
                WHERE organization_id = ? AND quotation_id = ?
                  AND review_status = ? AND lock_version = ?
                """, toStatus, toStatus, actorId, toStatus, toStatus, toStatus,
                toStatus, actorId, toStatus, toStatus, toStatus,
                comment, organizationId, quotationId, fromStatus, expectedVersion);
        if (updated != 1) {
            throw new ApiException(ApiErrorCode.REVIEW_STATE_CONFLICT, "报价审核状态已变化，请刷新后重试");
        }
        jdbc.update("""
                INSERT INTO rnd.quotation_review_event
                    (id, quotation_id, organization_id, from_status, to_status,
                     quotation_version, actor_id, comment)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), quotationId, organizationId, fromStatus,
                toStatus, quotationVersion, actorId, comment);
        return ensure(organizationId, quotationId);
    }

    @Override
    public List<Event> events(UUID organizationId, UUID quotationId) {
        return jdbc.query("""
                SELECT e.id, e.from_status, e.to_status, e.quotation_version,
                       e.actor_id, COALESCE(u.display_name, u.username, '未知用户') AS actor_name,
                       e.comment, e.created_at
                FROM rnd.quotation_review_event e
                LEFT JOIN iam.app_user u ON u.id = e.actor_id AND u.organization_id = e.organization_id
                WHERE e.organization_id = ? AND e.quotation_id = ?
                ORDER BY e.created_at DESC, e.id DESC
                """, (rs, row) -> new Event(rs.getObject("id", UUID.class),
                rs.getString("from_status"), rs.getString("to_status"),
                rs.getInt("quotation_version"), rs.getObject("actor_id", UUID.class),
                rs.getString("actor_name"), rs.getString("comment"),
                rs.getTimestamp("created_at").toInstant()), organizationId, quotationId);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
