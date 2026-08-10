package com.jsd.aird.ops.application.port;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/** Public submission boundary for the Postgres-backed worker. */
public interface OpsAsyncFacade {

    UUID enqueue(UUID organizationId, String jobType, JsonNode payload, String idempotencyKey, int priority);

    void appendOutbox(String aggregateType, UUID aggregateId, String eventType, JsonNode payload);
}
