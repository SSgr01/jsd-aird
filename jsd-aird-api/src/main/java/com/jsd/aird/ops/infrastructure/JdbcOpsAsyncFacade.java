package com.jsd.aird.ops.infrastructure;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
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
    public void appendOutbox(String aggregateType, UUID aggregateId, String eventType, JsonNode payload) {
        jdbc.update("""
                INSERT INTO ops.outbox_event (id, aggregate_type, aggregate_id, event_type, payload_jsonb)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), aggregateType, aggregateId, eventType, json(payload));
    }

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
