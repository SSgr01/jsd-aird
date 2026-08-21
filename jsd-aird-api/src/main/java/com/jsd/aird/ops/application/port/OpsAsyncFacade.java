package com.jsd.aird.ops.application.port;

import java.util.UUID;
import java.util.Optional;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/** Public submission boundary for the Postgres-backed worker. */
public interface OpsAsyncFacade {

    UUID enqueue(UUID organizationId, String jobType, JsonNode payload, String idempotencyKey, int priority);

    void appendOutbox(String aggregateType, UUID aggregateId, String eventType, JsonNode payload);

    default Optional<AsyncJobView> findJob(UUID organizationId, String idempotencyKey) {
        return Optional.empty();
    }

    default Optional<AsyncJobView> findLatestJob(UUID organizationId, String idempotencyKeyPrefix) {
        return Optional.empty();
    }

    record AsyncJobView(UUID id, String jobType, String status, int progress, String currentStage,
                        int attemptCount, int maxAttempts, Instant nextAttemptAt, String lastError,
                        Instant finishedAt) {
        public boolean terminal() { return "FAILED".equals(status) || "SUCCEEDED".equals(status) || "CANCELLED".equals(status); }
    }
}
