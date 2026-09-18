package com.jsd.aird.ops.application.port;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;
import java.util.function.BooleanSupplier;

public interface AsyncJobHandler {

    boolean supports(String jobType);

    JsonNode handle(JsonNode payload);

    /** Execution identity used by long-running handlers to reject stale workers. */
    default JsonNode handle(JsonNode payload, ExecutionContext context) {
        return handle(payload);
    }

    /** Deterministic failures (for example duplicate-key or invalid contracts) must not be retried. */
    default boolean isRetryable(Exception exception) {
        return true;
    }

    /**
     * Gives a handler a chance to synchronize its domain record when the
     * generic async job reaches its terminal failure state. Transient
     * failures stay in the generic retry queue and do not change domain state.
     */
    default void handleTerminalFailure(JsonNode payload, Exception exception) {
        // Most jobs only need the generic async_job failure record.
    }

    record ExecutionContext(UUID jobId, UUID organizationId, int attempt,
                            UUID leaseToken, long leaseGeneration,
                            BooleanSupplier cancellationRequested) {
        public boolean cancelled() {
            return cancellationRequested != null && cancellationRequested.getAsBoolean();
        }
    }
}
