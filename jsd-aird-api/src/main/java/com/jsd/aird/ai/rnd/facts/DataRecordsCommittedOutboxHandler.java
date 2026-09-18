package com.jsd.aird.ai.rnd.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.OutboxEventHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Projects a committed Data source after the Data transaction has published
 * its durable outbox event. This keeps the Data module independent from the
 * AI fact implementation and makes projection retryable by the worker.
 */
@Component
public class DataRecordsCommittedOutboxHandler implements OutboxEventHandler {
    private final UnifiedFactService facts;

    public DataRecordsCommittedOutboxHandler(UnifiedFactService facts) {
        this.facts = facts;
    }

    @Override
    public boolean supports(String eventType) {
        return "DATA_RECORDS_COMMITTED".equals(eventType);
    }

    @Override
    @Transactional
    public void handle(UUID aggregateId, JsonNode payload) {
        var organizationId = UUID.fromString(payload.path("organizationId").asText());
        var actorId = UUID.fromString(payload.path("actorId").asText());
        // A template upload started from the experiment notebook is already
        // owned by RND. Its committed rows are consumed by experiment
        // assembly and must not become a Data-center Submission.
        if ("EXPERIMENT".equalsIgnoreCase(payload.path("sourceOwner").asText())) return;
        facts.confirmCurrentDataJob(organizationId, aggregateId, actorId);
    }
}
