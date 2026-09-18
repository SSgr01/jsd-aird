package com.jsd.aird.ai.rnd.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.ops.application.port.OutboxEventHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class TrainingOutboxHandler implements OutboxEventHandler {
    private final OpsAsyncFacade async;
    private final JdbcTemplate jdbc;

    public TrainingOutboxHandler(OpsAsyncFacade async, JdbcTemplate jdbc) { this.async=async;this.jdbc=jdbc; }
    @Override public boolean supports(String eventType) { return "AI_TRAINING_REQUESTED".equals(eventType); }

    @Override
    @Transactional
    public void handle(UUID aggregateId, JsonNode payload) {
        var org=UUID.fromString(payload.path("organizationId").asText());
        var business=payload.path("businessKey").asText();
        var ops=async.enqueue(org,"AI_MODEL_TRAIN_V2",payload,"ai-rnd-train:"+business,60,3);
        jdbc.update("""
                UPDATE ai.training_job SET ops_job_id=?,updated_at=now()
                WHERE organization_id=? AND id=? AND status='QUEUED' AND (ops_job_id IS NULL OR ops_job_id=?)
                """,ops,org,aggregateId,ops);
    }
}
