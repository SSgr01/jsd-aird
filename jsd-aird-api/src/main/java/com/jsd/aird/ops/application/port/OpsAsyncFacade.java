package com.jsd.aird.ops.application.port;

import java.util.UUID;
import java.util.Optional;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/** Public submission boundary for the Postgres-backed worker. */
public interface OpsAsyncFacade {

    UUID enqueue(UUID organizationId, String jobType, JsonNode payload, String idempotencyKey, int priority);

    /**
     * Cancels a queued or running job. Running handlers may finish their current
     * step, but terminal completion/failure must not overwrite CANCELLED.
     */
    default int cancel(UUID organizationId, UUID jobId) {
        return 0;
    }

    default void updateProgress(UUID organizationId, String idempotencyKey, int progress, String stage) {
        // Optional for non-persistent implementations used by focused tests.
    }

    void appendOutbox(String aggregateType, UUID aggregateId, String eventType, JsonNode payload);

    default Optional<AsyncJobView> findJob(UUID organizationId, String idempotencyKey) {
        return Optional.empty();
    }

    default Optional<AsyncJobView> findLatestJob(UUID organizationId, String idempotencyKeyPrefix) {
        return Optional.empty();
    }

    /**
     * Cancels queued/running jobs whose idempotency key belongs to the supplied
     * aggregate. Implementations that do not persist async jobs may no-op.
     */
    default int cancelByIdempotencyKeyPrefix(UUID organizationId, String idempotencyKeyPrefix) {
        return 0;
    }

    record AsyncJobView(UUID id, String jobType, String status, int progress, String currentStage,
                        int attemptCount, int maxAttempts, Instant nextAttemptAt, String lastError,
                        Instant finishedAt) {
        public boolean terminal() { return "FAILED".equals(status) || "SUCCEEDED".equals(status) || "CANCELLED".equals(status); }
    }
}
