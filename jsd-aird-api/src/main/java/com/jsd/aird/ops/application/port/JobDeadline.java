package com.jsd.aird.ops.application.port;

import java.time.Duration;
import java.time.Instant;

/** Public cooperative deadline boundary for long-running template operations. */
public final class JobDeadline {

    private static final ThreadLocal<Instant> CURRENT = new ThreadLocal<>();

    private JobDeadline() { }

    public static Scope start(Duration timeout) {
        var previous = CURRENT.get();
        var safeTimeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(1) : timeout;
        CURRENT.set(Instant.now().plus(safeTimeout));
        return new Scope(previous);
    }

    public static void check() {
        if (Thread.currentThread().isInterrupted()) throw new DeadlineExceededException("异步任务已被中断");
        var deadline = CURRENT.get();
        if (deadline != null && !Instant.now().isBefore(deadline)) throw new DeadlineExceededException("异步任务执行超时");
    }

    public static final class Scope implements AutoCloseable {
        private final Instant previous;
        private Scope(Instant previous) { this.previous = previous; }
        @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }

    public static class DeadlineExceededException extends RuntimeException {
        public DeadlineExceededException(String message) { super(message); }
    }
}
