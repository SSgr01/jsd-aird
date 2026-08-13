package com.jsd.aird.ops.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

    public PostgresWorker(
            WorkRepository workRepository,
            FileObjectRepository fileRepository,
            ObjectMapper objectMapper,
            List<AsyncJobHandler> jobHandlers,
            @Value("${app.worker.id}") String workerId,
            @Value("${app.worker.lease-duration}") Duration leaseDuration
    ) {
        this.workRepository = workRepository;
        this.fileRepository = fileRepository;
        this.objectMapper = objectMapper;
        this.jobHandlers = List.copyOf(jobHandlers);
        this.workerId = workerId;
        this.leaseDuration = leaseDuration;
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
        try {
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
            workRepository.completeJob(job.id(), result);
        } catch (Exception exception) {
            log.warn("Async job {} failed", job.id(), exception);
            var handler = jobHandlers.stream().filter(candidate -> candidate.supports(job.jobType()))
                    .findFirst().orElse(null);
            var terminal = (handler != null && !handler.isRetryable(exception))
                    || job.attemptCount() >= job.maxAttempts();
            if (terminal) {
                workRepository.failJobTerminal(job, exception);
                if (handler != null) handler.handleTerminalFailure(job.payload(), exception);
            } else workRepository.failJob(job, exception);
        }
    }
}
