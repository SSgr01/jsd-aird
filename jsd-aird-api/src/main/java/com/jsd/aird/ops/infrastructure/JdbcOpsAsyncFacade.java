package com.jsd.aird.ops.infrastructure;

import java.util.UUID;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOpsAsyncFacade implements OpsAsyncFacade {

    private final JdbcTemplate jdbc;

    public JdbcOpsAsyncFacade(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public UUID enqueue(UUID organizationId, String jobType, JsonNode payload, String idempotencyKey, int priority) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ops.async_job (id, organization_id, job_type, status, payload_jsonb, priority, idempotency_key)
                VALUES (?, ?, ?, 'READY', ?, ?, ?)
                ON CONFLICT (organization_id, idempotency_key) DO NOTHING
                """, id, organizationId, jobType, json(payload), priority, idempotencyKey);
        return id;
    }

    @Override
    @Transactional
    public int cancel(UUID organizationId, UUID jobId) {
        if (jobId == null) return 0;
        return jdbc.update("""
                UPDATE ops.async_job
                SET status = 'CANCELLED', last_error = '用户取消解析',
                    lease_owner = NULL, lease_expires_at = NULL,
                    finished_at = now(), updated_at = now()
                WHERE organization_id = ? AND id = ? AND status IN ('READY', 'RUNNING')
                """, organizationId, jobId);
    }

    /**
     * Progress is deliberately committed independently from the business
     * transaction executed by a worker.  Image/OCR parsing can take minutes;
     * keeping these writes in that outer transaction leaves the UI stuck at
     * the claim-time 5% value until parsing finishes (or rolls back).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(UUID organizationId, String idempotencyKey, int progress, String stage) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        jdbc.update("""
                UPDATE ops.async_job
                SET progress = GREATEST(progress, ?), current_stage = ?, updated_at = now()
                WHERE organization_id = ? AND idempotency_key = ?
                  AND status IN ('READY', 'RUNNING')
                """, Math.max(0, Math.min(100, progress)), stage, organizationId, idempotencyKey);
    }

    @Override
    public void appendOutbox(String aggregateType, UUID aggregateId, String eventType, JsonNode payload) {
        jdbc.update("""
                INSERT INTO ops.outbox_event (id, aggregate_type, aggregate_id, event_type, payload_jsonb)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), aggregateType, aggregateId, eventType, json(payload));
    }

    @Override
    public Optional<AsyncJobView> findJob(UUID organizationId, String idempotencyKey) {
        return jdbc.query("""
                SELECT id, job_type, status, progress, current_stage, attempt_count, max_attempts,
                       next_attempt_at, last_error, finished_at
                FROM ops.async_job
                WHERE organization_id = ? AND idempotency_key = ?
                """, (rs, ignored) -> new AsyncJobView(
                rs.getObject("id", UUID.class), rs.getString("job_type"), rs.getString("status"),
                rs.getInt("progress"), rs.getString("current_stage"), rs.getInt("attempt_count"),
                rs.getInt("max_attempts"), instant(rs.getTimestamp("next_attempt_at")),
                rs.getString("last_error"), instant(rs.getTimestamp("finished_at"))), organizationId, idempotencyKey)
                .stream().findFirst();
    }

    @Override
    public Optional<AsyncJobView> findLatestJob(UUID organizationId, String idempotencyKeyPrefix) {
        return jdbc.query("""
                SELECT id, job_type, status, progress, current_stage, attempt_count, max_attempts,
                       next_attempt_at, last_error, finished_at
                FROM ops.async_job
                WHERE organization_id = ? AND idempotency_key LIKE ?
                ORDER BY created_at DESC
                LIMIT 1
                """, (rs, ignored) -> new AsyncJobView(
                rs.getObject("id", UUID.class), rs.getString("job_type"), rs.getString("status"),
                rs.getInt("progress"), rs.getString("current_stage"), rs.getInt("attempt_count"),
                rs.getInt("max_attempts"), instant(rs.getTimestamp("next_attempt_at")),
                rs.getString("last_error"), instant(rs.getTimestamp("finished_at"))),
                organizationId, idempotencyKeyPrefix + "%").stream().findFirst();
    }

    @Override
    @Transactional
    public int cancelByIdempotencyKeyPrefix(UUID organizationId, String idempotencyKeyPrefix) {
        if (organizationId == null || idempotencyKeyPrefix == null || idempotencyKeyPrefix.isBlank()) {
            return 0;
        }
        return jdbc.update("""
                UPDATE ops.async_job
                SET status = 'CANCELLED',
                    last_error = '用户取消解析',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    finished_at = COALESCE(finished_at, now()),
                    updated_at = now()
                WHERE organization_id = ?
                  AND idempotency_key LIKE ?
                  AND status IN ('READY', 'RUNNING')
                """, organizationId, idempotencyKeyPrefix + "%");
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private PGobject json(JsonNode value) {
        try {
            var json = new PGobject();
            json.setType("jsonb");
            json.setValue(value == null ? "{}" : value.toString());
            return json;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法序列化异步任务数据", exception);
        }
    }
}
