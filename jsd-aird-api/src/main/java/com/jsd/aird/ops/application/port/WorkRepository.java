package com.jsd.aird.ops.application.port;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public interface WorkRepository {

    Optional<AsyncJob> claimJob(String workerId, Duration leaseDuration);

    void completeJob(UUID jobId, JsonNode result);

    void failJob(AsyncJob job, Exception exception);

    void failJobTerminal(AsyncJob job, Exception exception);

    Optional<OutboxEvent> claimOutbox(String workerId, Duration leaseDuration);

    void completeOutbox(UUID eventId);

    void failOutbox(OutboxEvent event, Exception exception);

    record AsyncJob(
            UUID id,
            String jobType,
            JsonNode payload,
            int attemptCount,
            int maxAttempts
    ) {
    }

    record OutboxEvent(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            JsonNode payload,
            int attemptCount
    ) {
    }
}
