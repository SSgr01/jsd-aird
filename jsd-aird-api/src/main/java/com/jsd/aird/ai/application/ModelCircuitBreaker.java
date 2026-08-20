package com.jsd.aird.ai.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Small in-process breaker for model calls; failures remain visible in audit/trace. */
@Component
public class ModelCircuitBreaker {

    private final int failureThreshold;
    private final Duration openDuration;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public ModelCircuitBreaker(
            @Value("${app.ai.circuit-breaker.failure-threshold:3}") int failureThreshold,
            @Value("${app.ai.circuit-breaker.open-seconds:30}") long openSeconds) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = Duration.ofSeconds(Math.max(1, openSeconds));
    }

    public boolean allow(String name) {
        var state = states.computeIfAbsent(name, ignored -> new State());
        synchronized (state) {
            if (!state.open) return true;
            if (Duration.between(state.openedAt, Instant.now()).compareTo(openDuration) < 0) return false;
            state.open = false;
            state.probe = true;
            return true;
        }
    }

    public void success(String name) {
        var state = states.computeIfAbsent(name, ignored -> new State());
        synchronized (state) {
            state.failures = 0;
            state.open = false;
            state.probe = false;
        }
    }

    public void failure(String name) {
        var state = states.computeIfAbsent(name, ignored -> new State());
        synchronized (state) {
            if (state.probe || ++state.failures >= failureThreshold) {
                state.open = true;
                state.openedAt = Instant.now();
                state.probe = false;
            }
        }
    }

    public String status(String name) {
        var state = states.get(name);
        if (state == null) return "CLOSED";
        synchronized (state) {
            return state.open ? "OPEN" : "CLOSED";
        }
    }

    private static final class State {
        private int failures;
        private boolean open;
        private boolean probe;
        private Instant openedAt = Instant.EPOCH;
    }
}
