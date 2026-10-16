package com.jsd.aird.ai.infrastructure;

import com.jsd.aird.ai.application.operation.OperationManualIndexService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OperationManualIndexLifecycle implements DisposableBean {
    private final OperationManualIndexService index;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("operation-manual-index").daemon(true).factory());
    private final AtomicBoolean started = new AtomicBoolean();
    private int attempts;

    public OperationManualIndexLifecycle(OperationManualIndexService index) { this.index = index; }

    @EventListener(ApplicationReadyEvent.class)
    public void start() { if (started.compareAndSet(false, true)) executor.execute(this::attempt); }

    private void attempt() {
        if (!index.synchronize() && ++attempts < 3 && !executor.isShutdown()) {
            executor.schedule(this::attempt, 30, TimeUnit.SECONDS);
        }
    }

    @Override
    public void destroy() { executor.shutdownNow(); }
}
