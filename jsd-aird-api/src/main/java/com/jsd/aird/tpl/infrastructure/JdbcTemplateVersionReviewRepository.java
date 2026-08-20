package com.jsd.aird.tpl.infrastructure;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.tpl.application.port.TemplateVersionReviewRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTemplateVersionReviewRepository implements TemplateVersionReviewRepository {

    private final JdbcTemplate jdbc;

    public JdbcTemplateVersionReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Review> find(UUID organizationId, UUID versionId) {
        return jdbc.query("""
                SELECT version_id, review_status, submitted_by, submitted_at, reviewed_by,
                       reviewed_at, review_comment, lock_version
                FROM tpl.template_version_review
                WHERE organization_id = ? AND version_id = ?
                """, this::review, organizationId, versionId).stream().findFirst();
    }

    @Override
    public Review ensure(UUID organizationId, UUID versionId) {
        jdbc.update("""
                INSERT INTO tpl.template_version_review (version_id, organization_id)
                VALUES (?, ?)
                ON CONFLICT (version_id) DO NOTHING
                """, versionId, organizationId);
        return find(organizationId, versionId).orElseThrow();
    }

    @Override
    public Review transition(UUID organizationId, UUID versionId, String expectedStatus, String nextStatus,
                             UUID actorId, String comment) {
        ensure(organizationId, versionId);
        var updated = jdbc.update("""
                UPDATE tpl.template_version_review
                SET review_status = ?, submitted_by = CASE WHEN ? = 'SUBMITTED' THEN ? ELSE submitted_by END,
                    submitted_at = CASE WHEN ? = 'SUBMITTED' THEN now() ELSE submitted_at END,
                    reviewed_by = CASE WHEN ? IN ('APPROVED', 'REJECTED') THEN ? ELSE reviewed_by END,
                    reviewed_at = CASE WHEN ? IN ('APPROVED', 'REJECTED') THEN now() ELSE reviewed_at END,
                    review_comment = ?, lock_version = lock_version + 1, updated_at = now()
                WHERE organization_id = ? AND version_id = ? AND review_status = ?
                """, nextStatus, nextStatus, actorId, nextStatus, nextStatus, actorId, nextStatus,
                comment, organizationId, versionId, expectedStatus);
        if (updated != 1) throw new IllegalStateException("模板审核状态已变化，请刷新后重试");
        jdbc.update("""
                INSERT INTO tpl.template_version_review_event
                    (id, version_id, organization_id, from_status, to_status, actor_id, comment)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), versionId, organizationId, expectedStatus, nextStatus, actorId, comment);
        return ensure(organizationId, versionId);
    }

    @Override
    public List<ReviewEvent> events(UUID organizationId, UUID versionId) {
        return jdbc.query("""
                SELECT id, version_id, from_status, to_status, actor_id, comment, created_at
                FROM tpl.template_version_review_event
                WHERE organization_id = ? AND version_id = ?
                ORDER BY created_at DESC
                """, (rs, row) -> new ReviewEvent(
                rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class),
                rs.getString("from_status"), rs.getString("to_status"),
                rs.getObject("actor_id", UUID.class), rs.getString("comment"),
                rs.getTimestamp("created_at").toInstant()), organizationId, versionId);
    }

    private Review review(ResultSet rs, int row) throws java.sql.SQLException {
        return new Review(rs.getObject("version_id", UUID.class), rs.getString("review_status"),
                rs.getObject("submitted_by", UUID.class), instant(rs, "submitted_at"),
                rs.getObject("reviewed_by", UUID.class), instant(rs, "reviewed_at"),
                rs.getString("review_comment"), rs.getLong("lock_version"));
    }

    private Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
