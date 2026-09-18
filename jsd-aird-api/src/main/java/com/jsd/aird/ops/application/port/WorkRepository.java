package com.jsd.aird.ops.application.port;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public interface WorkRepository {

    Optional<AsyncJob> claimJob(String workerId, Duration leaseDuration);

    /** Renews the lease and records a heartbeat while a handler is running. */
    void heartbeatJob(UUID jobId, String workerId, Duration leaseDuration);

    default boolean heartbeatClaimedJob(AsyncJob job, String workerId, Duration leaseDuration) {
        heartbeatJob(job.id(), workerId, leaseDuration);
        return true;
    }

    /** Reads the live stage so timeout diagnostics do not use a stale claim snapshot. */
    String currentStage(UUID jobId);

    /** Returns whether an in-flight job was cancelled by its owning request. */
    default boolean isCancelled(UUID jobId) {
        return false;
    }

    void completeJob(UUID jobId, JsonNode result);

    default boolean completeClaimedJob(AsyncJob job, JsonNode result) {
        completeJob(job.id(), result);
        return true;
    }

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
            int maxAttempts,
            String currentStage,
            UUID organizationId,
            UUID leaseToken,
            long leaseGeneration
    ) {
        public AsyncJob(UUID id, String jobType, JsonNode payload, int attemptCount, int maxAttempts) {
            this(id, jobType, payload, attemptCount, maxAttempts, "", null, null, 0);
        }
        public AsyncJob(UUID id, String jobType, JsonNode payload, int attemptCount, int maxAttempts,
                        String currentStage) {
            this(id, jobType, payload, attemptCount, maxAttempts, currentStage, null, null, 0);
        }
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
