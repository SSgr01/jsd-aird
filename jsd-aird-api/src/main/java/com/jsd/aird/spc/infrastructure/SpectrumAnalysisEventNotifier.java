package com.jsd.aird.spc.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Wakes API SSE streams when the worker commits a durable analysis event. */
@Component
@Profile("!worker")
public class SpectrumAnalysisEventNotifier {

    private static final Logger log = LoggerFactory.getLogger(SpectrumAnalysisEventNotifier.class);
    private static final String CHANNEL = "spectrum_analysis_events";

    private final DataSource dataSource;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ConcurrentHashMap<UUID, Signal> signals = new ConcurrentHashMap<>();
    private Thread listenerThread;

    public SpectrumAnalysisEventNotifier(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void start() {
        if (!running.compareAndSet(false, true)) return;
        listenerThread = new Thread(this::listen, "spectrum-analysis-event-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (listenerThread != null) listenerThread.interrupt();
    }

    public synchronized Subscription subscribe(UUID analysisRunId) {
        var signal = signals.computeIfAbsent(analysisRunId, ignored -> new Signal());
        synchronized (signal) {
            signal.subscribers++;
        }
        return new Subscription(analysisRunId, signal);
    }

    private void listen() {
        while (running.get()) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                try (var statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                var postgres = connection.unwrap(PGConnection.class);
                while (running.get()) {
                    var notifications = postgres.getNotifications(10);
                    if (notifications == null) continue;
                    for (var notification : notifications) signal(notification);
                }
            } catch (SQLException exception) {
                if (running.get()) {
                    log.warn("Spectrum analysis event listener disconnected; reconnecting", exception);
                    pause();
                }
            }
        }
    }

    private void signal(PGNotification notification) {
        try {
            var analysisRunId = UUID.fromString(notification.getParameter());
            var signal = signals.get(analysisRunId);
            if (signal == null) return;
            synchronized (signal) {
                signal.version++;
                signal.notifyAll();
            }
        } catch (IllegalArgumentException exception) {
            log.debug("Ignoring invalid spectrum event notification payload", exception);
        }
    }

    private void pause() {
        try {
            Thread.sleep(1_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Signal {
        private long version;
        private int subscribers;
    }

    public final class Subscription implements AutoCloseable {
        private final UUID analysisRunId;
        private final Signal signal;
        private boolean closed;

        private Subscription(UUID analysisRunId, Signal signal) {
            this.analysisRunId = analysisRunId;
            this.signal = signal;
        }

        public long version() {
            synchronized (signal) {
                return signal.version;
            }
        }

        public void awaitChange(long observedVersion, Duration timeout) throws InterruptedException {
            synchronized (signal) {
                if (!closed && signal.version == observedVersion) {
                    signal.wait(Math.max(1L, timeout.toMillis()));
                }
            }
        }

        @Override
        public void close() {
            synchronized (SpectrumAnalysisEventNotifier.this) {
                synchronized (signal) {
                    if (closed) return;
                    closed = true;
                    signal.subscribers--;
                    if (signal.subscribers == 0) signals.remove(analysisRunId, signal);
                }
            }
        }
    }
}
