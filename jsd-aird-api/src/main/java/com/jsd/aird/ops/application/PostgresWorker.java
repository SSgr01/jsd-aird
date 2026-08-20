package com.jsd.aird.ops.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.ops.application.port.WorkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
@Profile("worker")
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class PostgresWorker {

    private static final Logger log = LoggerFactory.getLogger(PostgresWorker.class);

    private final WorkRepository workRepository;
    private final FileObjectRepository fileRepository;
    private final ObjectMapper objectMapper;
    private final List<AsyncJobHandler> jobHandlers;
    private final String workerId;
    private final Duration leaseDuration;
    private final Duration jobTimeout;
    private final java.util.concurrent.ExecutorService jobExecutor =
            Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("jsd-aird-worker-heartbeat-", 0).factory());

    @Autowired
    public PostgresWorker(
            WorkRepository workRepository,
            FileObjectRepository fileRepository,
            ObjectMapper objectMapper,
            List<AsyncJobHandler> jobHandlers,
            @Value("${app.worker.id}") String workerId,
            @Value("${app.worker.lease-duration}") Duration leaseDuration,
            @Value("${app.worker.job-timeout:15m}") Duration jobTimeout
    ) {
        this.workRepository = workRepository;
        this.fileRepository = fileRepository;
        this.objectMapper = objectMapper;
        this.jobHandlers = List.copyOf(jobHandlers);
        this.workerId = workerId;
        this.leaseDuration = leaseDuration;
        this.jobTimeout = jobTimeout;
    }

    /** Kept for focused unit tests and small embedded callers. */
    public PostgresWorker(
            WorkRepository workRepository,
            FileObjectRepository fileRepository,
            ObjectMapper objectMapper,
            List<AsyncJobHandler> jobHandlers,
            String workerId,
            Duration leaseDuration
    ) {
        this(workRepository, fileRepository, objectMapper, jobHandlers, workerId, leaseDuration,
                Duration.ofMinutes(15));
    }

    @PostConstruct
    void announceOnline() {
        log.info("worker_online workerId={} leaseDuration={} jobTimeout={} handlers={}",
                workerId, leaseDuration, jobTimeout, jobHandlers.size());
    }

    @PreDestroy
    void shutdownExecutors() {
        heartbeatExecutor.shutdownNow();
        jobExecutor.shutdownNow();
        log.info("worker_offline workerId={}", workerId);
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-delay}")
    public void poll() {
        for (int index = 0; index < 10; index++) {
            var event = workRepository.claimOutbox(workerId, leaseDuration);
            if (event.isEmpty()) {
                break;
            }
            process(event.get());
        }
        for (int index = 0; index < 5; index++) {
            var job = workRepository.claimJob(workerId, leaseDuration);
            if (job.isEmpty()) {
                break;
            }
            process(job.get());
        }
    }

    private void process(WorkRepository.OutboxEvent event) {
        try {
            if ("FILE_ACTIVATION_REQUESTED".equals(event.eventType())) {
                fileRepository.activate(event.aggregateId());
            }
            workRepository.completeOutbox(event.id());
        } catch (Exception exception) {
            log.warn("Outbox event {} failed", event.id(), exception);
            workRepository.failOutbox(event, exception);
        }
    }

    private void process(WorkRepository.AsyncJob job) {
        var heartbeat = scheduleHeartbeat(job);
        try {
            var result = executeWithTimeout(job);
            workRepository.completeJob(job.id(), result);
        } catch (Exception exception) {
            log.warn("Async job {} failed", job.id(), exception);
            var handler = jobHandlers.stream().filter(candidate -> candidate.supports(job.jobType()))
                    .findFirst().orElse(null);
            var terminal = exception instanceof JobTimeoutException
                    || (handler != null && !handler.isRetryable(exception))
                    || job.attemptCount() >= job.maxAttempts();
            if (terminal) {
                workRepository.failJobTerminal(job, exception);
                if (handler != null) {
                    try {
                        handler.handleTerminalFailure(job.payload(), exception);
                    } catch (Exception terminalFailure) {
                        log.error("Async job {} terminal handler failed", job.id(), terminalFailure);
                    }
                }
            } else workRepository.failJob(job, exception);
        } finally {
            heartbeat.cancel(false);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode executeWithTimeout(WorkRepository.AsyncJob job)
            throws Exception {
        Future<com.fasterxml.jackson.databind.JsonNode> future = jobExecutor.submit(() -> {
            try (var ignored = JobDeadline.start(jobTimeout)) {
                return execute(job);
            }
        });
        try {
            return future.get(Math.max(1, jobTimeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new JobTimeoutException(job, jobTimeout, liveStage(job));
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new JobTimeoutException(job, jobTimeout, liveStage(job) + "，Worker 线程被中断");
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof Exception nested) throw nested;
            throw new IllegalStateException("异步任务执行失败", cause);
        }
    }

    private String liveStage(WorkRepository.AsyncJob job) {
        try {
            var stage = workRepository.currentStage(job.id());
            if (stage != null && !stage.isBlank()) return stage;
        } catch (Exception exception) {
            log.debug("Unable to read live stage for timed out job {}", job.id(), exception);
        }
        return job.currentStage() == null || job.currentStage().isBlank() ? "未知" : job.currentStage();
    }

    private com.fasterxml.jackson.databind.JsonNode execute(WorkRepository.AsyncJob job) {
        var defaultResult = objectMapper.createObjectNode()
                .put("jobType", job.jobType())
                .put("processedAt", Instant.now().toString());
        com.fasterxml.jackson.databind.JsonNode result = defaultResult;
        switch (job.jobType()) {
            case "STAGED_OBJECT_CLEANUP" -> defaultResult.put(
                    "markedDeleted",
                    fileRepository.markExpiredStagedDeleted(Instant.now().minus(Duration.ofHours(24)))
            );
            default -> {
                var handler = jobHandlers.stream()
                        .filter(candidate -> candidate.supports(job.jobType()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unsupported job type: " + job.jobType()
                        ));
                result = handler.handle(job.payload());
            }
        }
        return result;
    }

    private ScheduledFuture<?> scheduleHeartbeat(WorkRepository.AsyncJob job) {
        var seconds = Math.max(1L, Math.min(15L, Math.max(1L, leaseDuration.toSeconds() / 3)));
        return heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                workRepository.heartbeatJob(job.id(), workerId, leaseDuration);
            } catch (Exception exception) {
                log.warn("Async job {} heartbeat failed", job.id(), exception);
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    private static final class JobTimeoutException extends Exception {
        private JobTimeoutException(WorkRepository.AsyncJob job, Duration timeout, String stage) {
            super("异步任务超时：" + timeout + "，当前阶段："
                    + (stage == null || stage.isBlank() ? "未知" : stage));
        }
    }
}
