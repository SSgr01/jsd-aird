package com.jsd.aird.ops.application.port;

import com.fasterxml.jackson.databind.JsonNode;

public interface AsyncJobHandler {

    boolean supports(String jobType);

    JsonNode handle(JsonNode payload);

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
}
