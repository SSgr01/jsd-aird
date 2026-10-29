package com.jsd.aird.ops.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.timeout;

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
    @Test void resourceContentionDefersTheExistingJobInsteadOfFailingIt(){
        var repository=mock(WorkRepository.class);var mapper=new ObjectMapper();var job=new WorkRepository.AsyncJob(UUID.randomUUID(),"BUSY",mapper.createObjectNode(),1,5);var handler=mock(AsyncJobHandler.class);
        when(repository.claimOutbox(any(),any())).thenReturn(Optional.empty());when(repository.claimJob(any(),any())).thenReturn(Optional.of(job),Optional.empty());when(handler.supports("BUSY")).thenReturn(true);
        when(handler.handle(any(),any())).thenThrow(new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.SERVER_BUSY));
        var worker=new PostgresWorker(repository,mock(FileObjectRepository.class),mapper,List.of(handler),"capacity",Duration.ofSeconds(30));
        try{worker.poll();verify(repository).deferClaimedJob(job);verify(repository,never()).failJobTerminal(any(),any());verify(repository,never()).completeClaimedJob(any(),any());}finally{worker.shutdownExecutors();}
    }
    @Test
    void ignoredInterruptionRetainsSlotsAndStopsClaimingMoreJobs()throws Exception {
        var repository=mock(WorkRepository.class);var mapper=new ObjectMapper();var handler=mock(AsyncJobHandler.class);
        var release=new java.util.concurrent.CountDownLatch(1);var actualExits=new java.util.concurrent.CountDownLatch(2);
        var job=new WorkRepository.AsyncJob(UUID.randomUUID(),"STUCK",mapper.createObjectNode(),1,5);
        when(handler.supports("STUCK")).thenReturn(true);
        when(handler.handle(any(),any())).thenAnswer(invocation->{try{while(release.getCount()>0){try{release.await(10,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){ }}}finally{actualExits.countDown();}return mapper.createObjectNode();});
        when(repository.claimOutbox(any(),any())).thenReturn(Optional.empty());when(repository.claimJob(any(),any())).thenReturn(Optional.of(job));
        var worker=new PostgresWorker(repository,mock(FileObjectRepository.class),mapper,List.of(handler),"bounded",Duration.ofSeconds(30),Duration.ofMillis(30));
        try{
            worker.poll();worker.poll();verify(repository,org.mockito.Mockito.times(2)).claimJob(any(),any());
            release.countDown();org.assertj.core.api.Assertions.assertThat(actualExits.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }finally{release.countDown();worker.shutdownExecutors();}
    }

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

    @Test
    void terminatesAHandlerThatExceedsTheConfiguredJobTimeout() {
        var repository = mock(WorkRepository.class);
        var files = mock(FileObjectRepository.class);
        var mapper = new ObjectMapper();
        var payload = mapper.createObjectNode().put("importJobId", UUID.randomUUID().toString());
        var job = new WorkRepository.AsyncJob(UUID.randomUUID(), "SLOW_JOB", payload, 1, 5,
                "DISCOVERING_STRUCTURE_REGIONS");
        var handler = mock(AsyncJobHandler.class);
        var interrupted = new java.util.concurrent.CountDownLatch(1);
        when(handler.supports("SLOW_JOB")).thenReturn(true);
        when(handler.isRetryable(any())).thenReturn(true);
        when(handler.handle(any(), any())).thenAnswer(invocation -> {
            try { Thread.sleep(5_000); }
            catch (InterruptedException error) { interrupted.countDown(); throw error; }
            return mapper.createObjectNode();
        });
        when(repository.claimOutbox(any(), any())).thenReturn(Optional.empty());
        when(repository.claimJob(any(), any())).thenReturn(Optional.of(job), Optional.empty());

        var worker = new PostgresWorker(repository, files, mapper, List.of(handler),
                "test-worker", Duration.ofSeconds(30), Duration.ofMillis(50));
        try {
            worker.poll();
        } finally {
            worker.shutdownExecutors();
        }

        verify(repository, timeout(1_000)).failJobTerminal(any(), any());
        verify(repository, never()).completeJob(any(), any());
        var context=org.mockito.ArgumentCaptor.forClass(AsyncJobHandler.ExecutionContext.class);
        verify(handler).handleTerminalFailure(any(), any(), context.capture());
        org.assertj.core.api.Assertions.assertThat(context.getValue().jobId()).isEqualTo(job.id());
        org.assertj.core.api.Assertions.assertThat(context.getValue().leaseGeneration()).isEqualTo(job.leaseGeneration());
        try { org.assertj.core.api.Assertions.assertThat(interrupted.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
        catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
