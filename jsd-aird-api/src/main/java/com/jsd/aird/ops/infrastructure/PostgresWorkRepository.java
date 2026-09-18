package com.jsd.aird.ops.infrastructure;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.WorkRepository;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostgresWorkRepository implements WorkRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresWorkRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @Override
    public Optional<AsyncJob> claimJob(String workerId, Duration leaseDuration) {
        var leaseUntil = Timestamp.from(Instant.now().plus(leaseDuration));
        var leaseToken = UUID.randomUUID();
        return jdbcTemplate.query("""
                        WITH candidate AS (
                            SELECT id, organization_id, job_type, priority, next_attempt_at, created_at
                            FROM ops.async_job
                            WHERE (
                                status = 'READY'
                                OR (status = 'RUNNING' AND lease_expires_at < now())
                            )
                              AND next_attempt_at <= now()
                              AND attempt_count < max_attempts
                            ORDER BY priority, next_attempt_at, created_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT 32
                        ), eligible AS (
                            SELECT c.id
                            FROM candidate c
                            WHERE c.job_type NOT IN ('AI_FORMULA_DESIGN_V2', 'AI_EXPERIMENT_OPTIMIZATION_V2')
                               OR (
                                  pg_try_advisory_xact_lock(
                                      hashtextextended('AI_RND_ORG:' || c.organization_id::text, 0)
                                  )
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM ops.async_job active
                                  WHERE active.organization_id = c.organization_id
                                    AND active.id <> c.id
                                    AND active.job_type IN ('AI_FORMULA_DESIGN_V2', 'AI_EXPERIMENT_OPTIMIZATION_V2')
                                    AND active.status = 'RUNNING'
                                    AND active.lease_expires_at >= now()
                              )
                               )
                            ORDER BY c.priority, c.next_attempt_at, c.created_at
                            LIMIT 1
                        )
                        UPDATE ops.async_job job
                        SET status = 'RUNNING',
                            progress = GREATEST(progress, 5),
                            current_stage = COALESCE(current_stage, 'PREPARING'),
                            lease_owner = ?,
                            lease_expires_at = ?,
                            lease_token = ?,
                            lease_generation = lease_generation + 1,
                            attempt_count = attempt_count + 1,
                            started_at = COALESCE(started_at, now()),
                            updated_at = now()
                        FROM eligible
                        WHERE job.id = eligible.id
                        RETURNING job.id, job.job_type, job.payload_jsonb, job.attempt_count,
                                  job.max_attempts, job.current_stage, job.organization_id,
                                  job.lease_token, job.lease_generation
                        """,
                (rs, rowNum) -> new AsyncJob(
                        rs.getObject("id", UUID.class),
                        rs.getString("job_type"),
                        parse(rs.getString("payload_jsonb")),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"),
                        rs.getString("current_stage"),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("lease_token", UUID.class),
                        rs.getLong("lease_generation")
                ),
                workerId,
                leaseUntil,
                leaseToken
        ).stream().findFirst();
    }

    @Override
    public boolean heartbeatClaimedJob(AsyncJob job, String workerId, Duration leaseDuration) {
        return jdbcTemplate.update("""
                UPDATE ops.async_job
                SET lease_expires_at = ?, updated_at = now()
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                  AND lease_token = ? AND lease_generation = ?
                """, Timestamp.from(Instant.now().plus(leaseDuration)), job.id(), workerId,
                job.leaseToken(), job.leaseGeneration()) == 1;
    }

    @Override
    public void heartbeatJob(UUID jobId, String workerId, Duration leaseDuration) {
        jdbcTemplate.update("""
                UPDATE ops.async_job
                SET lease_expires_at = ?, updated_at = now()
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                """, Timestamp.from(Instant.now().plus(leaseDuration)), jobId, workerId);
    }

    @Override
    public String currentStage(UUID jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT current_stage FROM ops.async_job WHERE id = ?", String.class, jobId);
    }

    @Override
    public boolean isCancelled(UUID jobId) {
        Integer cancelled = jdbcTemplate.queryForObject(
                "SELECT CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END FROM ops.async_job WHERE id = ?",
                Integer.class, jobId);
        return cancelled != null && cancelled == 1;
    }

    @Override
    public void completeJob(UUID jobId, JsonNode result) {
        jdbcTemplate.update("""
                        UPDATE ops.async_job
                        SET status = 'SUCCEEDED', result_jsonb = ?, progress = 100,
                            current_stage = 'COMPLETED', lease_owner = NULL,
                            lease_expires_at = NULL, last_error = NULL,
                            finished_at = now(), updated_at = now()
                        WHERE id = ?
                        """,
                pgJson(result),
                jobId
        );
    }

    @Override
    public boolean completeClaimedJob(AsyncJob job, JsonNode result) {
        return jdbcTemplate.update("""
                        UPDATE ops.async_job
                        SET status = 'SUCCEEDED', result_jsonb = ?, progress = 100,
                            current_stage = 'COMPLETED', lease_owner = NULL,
                            lease_expires_at = NULL, lease_token = NULL, last_error = NULL,
                            finished_at = now(), updated_at = now()
                        WHERE id = ? AND status = 'RUNNING'
                          AND lease_token = ? AND lease_generation = ?
                        """, pgJson(result), job.id(), job.leaseToken(), job.leaseGeneration()) == 1;
    }

    @Override
    public void failJob(AsyncJob job, Exception exception) {
        var terminal = job.attemptCount() >= job.maxAttempts();
        jdbcTemplate.update("""
                        UPDATE ops.async_job
                        SET status = ?,
                            next_attempt_at = now() + make_interval(secs => ?),
                            last_error = ?,
                            lease_owner = NULL,
                            lease_expires_at = NULL,
                            finished_at = CASE WHEN ? THEN now() ELSE NULL END,
                            updated_at = now()
                        WHERE id = ? AND lease_token = ? AND lease_generation = ?
                        """,
                terminal ? "FAILED" : "READY",
                Math.min(300, 5 * (1 << Math.min(job.attemptCount(), 6))),
                truncate(exception.getMessage(), 4000),
                terminal,
                job.id(), job.leaseToken(), job.leaseGeneration()
        );
    }

    @Override
    public void failJobTerminal(AsyncJob job, Exception exception) {
        jdbcTemplate.update("""
                UPDATE ops.async_job
                SET status = 'FAILED', last_error = ?, lease_owner = NULL,
                    lease_expires_at = NULL, finished_at = now(), updated_at = now()
                WHERE id = ? AND lease_token = ? AND lease_generation = ?
                """, truncate(exception.getMessage(), 4000), job.id(), job.leaseToken(), job.leaseGeneration());
    }

    @Transactional
    @Override
    public Optional<OutboxEvent> claimOutbox(String workerId, Duration leaseDuration) {
        var leaseUntil = Timestamp.from(Instant.now().plus(leaseDuration));
        return jdbcTemplate.query("""
                        WITH candidate AS (
                            SELECT id
                            FROM ops.outbox_event
                            WHERE (
                                status = 'PENDING'
                                OR (status = 'PROCESSING' AND lease_expires_at < now())
                            )
                              AND next_attempt_at <= now()
                            ORDER BY next_attempt_at, created_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT 1
                        )
                        UPDATE ops.outbox_event event
                        SET status = 'PROCESSING',
                            lease_owner = ?,
                            lease_expires_at = ?,
                            attempt_count = attempt_count + 1
                        FROM candidate
                        WHERE event.id = candidate.id
                        RETURNING event.id, event.aggregate_type, event.aggregate_id,
                                  event.event_type, event.payload_jsonb, event.attempt_count
                        """,
                (rs, rowNum) -> new OutboxEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        parse(rs.getString("payload_jsonb")),
                        rs.getInt("attempt_count")
                ),
                workerId,
                leaseUntil
        ).stream().findFirst();
    }

    @Override
    public void completeOutbox(UUID eventId) {
        jdbcTemplate.update("""
                UPDATE ops.outbox_event
                SET status = 'PUBLISHED', published_at = now(), lease_owner = NULL,
                    lease_expires_at = NULL
                WHERE id = ?
                """, eventId);
    }

    @Override
    public void failOutbox(OutboxEvent event, Exception exception) {
        var terminal = event.attemptCount() >= 10;
        jdbcTemplate.update("""
                        UPDATE ops.outbox_event
                        SET status = ?,
                            next_attempt_at = now() + make_interval(secs => ?),
                            last_error = ?,
                            lease_owner = NULL,
                            lease_expires_at = NULL
                        WHERE id = ?
                        """,
                terminal ? "FAILED" : "PENDING",
                Math.min(300, 5 * (1 << Math.min(event.attemptCount(), 6))),
                truncate(exception.getMessage(), 4000),
                event.id()
        );
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid JSONB payload", exception);
        }
    }

    private PGobject pgJson(JsonNode value) {
        try {
            var result = new PGobject();
            result.setType("jsonb");
            result.setValue(objectMapper.writeValueAsString(value));
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to serialize JSONB", exception);
        }
    }

    private String truncate(String value, int length) {
        if (value == null) {
            return "Unknown worker error";
        }
        return value.length() <= length ? value : value.substring(0, length);
    }

}
