package com.jsd.aird.ops.application.port;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/** Module-owned handler for a durable outbox event. */
public interface OutboxEventHandler {
    boolean supports(String eventType);
    void handle(UUID aggregateId, JsonNode payload);
}
