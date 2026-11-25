package com.jsd.aird.shared.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Phase durations only. Document content and resource identities never enter diagnostics. */
public final class OperationTiming implements AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OperationTiming.class);
    private final String operation;
    private final long started = System.nanoTime();
    private long previous = started;
    private final Map<String,Long> phases = new LinkedHashMap<>();
    public OperationTiming(String operation) { this.operation = operation; }
    public void checkpoint(String name) {
        long now = System.nanoTime();
        phases.merge(name,(now-previous)/1_000_000,Long::sum);
        previous = now;
    }
    public <T> T phase(String name, Supplier<T> work) {
        long start = System.nanoTime();
        try { return work.get(); }
        finally { phases.merge(name,(System.nanoTime()-start)/1_000_000,Long::sum); }
    }
    @Override public void close() {
        long elapsed = (System.nanoTime()-started)/1_000_000;
        if (elapsed >= 250 || log.isDebugEnabled())
            log.info("operation={} totalMs={} phasesMs={}",operation,elapsed,phases);
    }
}
