package com.jsd.aird.ops.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.WorkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class PostgresWorkerTest {

    @Test
    void sendsDeterministicDatabaseConflictDirectlyToTerminalFailure() {
        var repository = mock(WorkRepository.class);
        var files = mock(FileObjectRepository.class);
        var mapper = new ObjectMapper();
        var payload = mapper.createObjectNode().put("importJobId", UUID.randomUUID().toString());
        var job = new WorkRepository.AsyncJob(UUID.randomUUID(), "DATA_IMPORT_PARSE", payload, 1, 5);
        var handler = new AsyncJobHandler() {
            @Override public boolean supports(String jobType) { return "DATA_IMPORT_PARSE".equals(jobType); }
            @Override public JsonNode handle(JsonNode ignored) {
                throw new DataIntegrityViolationException("duplicate key violates unique constraint");
            }
            @Override public boolean isRetryable(Exception ignored) { return false; }
            @Override public void handleTerminalFailure(JsonNode ignored, Exception exception) { }
        };
        when(repository.claimOutbox(any(), any())).thenReturn(Optional.empty());
        when(repository.claimJob(any(), any())).thenReturn(Optional.of(job), Optional.empty());

        new PostgresWorker(repository, files, mapper, List.of(handler), "test-worker", Duration.ofSeconds(30)).poll();

        verify(repository).failJobTerminal(any(), any(DataIntegrityViolationException.class));
        verify(repository, never()).failJob(any(), any());
    }
}
