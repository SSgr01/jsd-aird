package com.jsd.aird.data.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExperimentImportJobLifecycleTest {
    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();

    private final DataRepository repository = mock(DataRepository.class);
    private final DataImportService service = new DataImportService(repository,
            mock(FileObjectRepository.class), mock(TemplateDataImportFacade.class),
            new ObjectMapper(), mock(AuditLogFacade.class), mock(OpsAsyncFacade.class));

    @BeforeEach
    void setActor() {
        ActorContext.set(new Actor(ORGANIZATION_ID, USER_ID, "tester"));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void retriesFailedExperimentOwnedJobAndQueuesAFreshParseAttempt() {
        var failed = job("FAILED");
        var queued = job("QUEUED");
        when(repository.findJobForUpdate(ORGANIZATION_ID, JOB_ID)).thenReturn(Optional.of(failed));
        when(repository.findSourceOwner(ORGANIZATION_ID, JOB_ID)).thenReturn(Optional.of("EXPERIMENT"));
        when(repository.findJob(ORGANIZATION_ID, JOB_ID)).thenReturn(Optional.of(queued));

        var result = service.retryExperimentImport(JOB_ID, repository.findJobForUpdate(ORGANIZATION_ID, JOB_ID).orElseThrow().getRevision());

        assertThat(result.status()).isEqualTo("QUEUED");
        verify(repository).clearParsed(ORGANIZATION_ID, JOB_ID);
        verify(repository).enqueueParse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(ORGANIZATION_ID),
                org.mockito.ArgumentMatchers.eq(JOB_ID), org.mockito.ArgumentMatchers.eq(USER_ID));
    }

    @Test
    void doesNotRetryCompletedImport() {
        when(repository.findJobForUpdate(ORGANIZATION_ID, JOB_ID)).thenReturn(Optional.of(job("COMPLETED")));
        when(repository.findSourceOwner(ORGANIZATION_ID, JOB_ID)).thenReturn(Optional.of("EXPERIMENT"));

        assertThatThrownBy(() -> service.retryExperimentImport(JOB_ID, repository.findJobForUpdate(ORGANIZATION_ID, JOB_ID).orElseThrow().getRevision()))
                .isInstanceOf(ApiException.class)
                .hasMessage("仅失败或已取消的实验导入任务可以重试");
        verify(repository, never()).enqueueParse(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsRetryForNonExperimentImportJobs() {
        when(repository.findJobForUpdate(ORGANIZATION_ID, JOB_ID)).thenReturn(Optional.of(job("FAILED")));
        when(repository.findSourceOwner(ORGANIZATION_ID, JOB_ID)).thenReturn(Optional.of("DATA_CENTER"));

        assertThatThrownBy(() -> service.retryExperimentImport(JOB_ID, repository.findJobForUpdate(ORGANIZATION_ID, JOB_ID).orElseThrow().getRevision()))
                .isInstanceOf(ApiException.class)
                .hasMessage("仅实验记录本导入任务可执行此操作");
        verify(repository, never()).clearParsed(ORGANIZATION_ID, JOB_ID);
    }

    @Test
    void refusesToHideAnImportThatIsStillProcessing() {
        when(repository.findJobForUpdate(ORGANIZATION_ID, JOB_ID)).thenReturn(Optional.of(job("PARSING")));
        when(repository.findSourceOwner(ORGANIZATION_ID, JOB_ID)).thenReturn(Optional.of("EXPERIMENT"));

        assertThatThrownBy(() -> service.hideExperimentImport(JOB_ID, repository.findJobForUpdate(ORGANIZATION_ID, JOB_ID).orElseThrow().getRevision()))
                .isInstanceOf(ApiException.class)
                .hasMessage("处理中任务不可删除，请等待处理结束");
        verify(repository, never()).hideExperimentJob(ORGANIZATION_ID, JOB_ID);
    }

    @Test
    void softHidesCompletedJobWithoutDeletingItsImportedData() {
        when(repository.findJobForUpdate(ORGANIZATION_ID, JOB_ID)).thenReturn(Optional.of(job("COMPLETED")));
        when(repository.findSourceOwner(ORGANIZATION_ID, JOB_ID)).thenReturn(Optional.of("EXPERIMENT"));
        when(repository.hideExperimentJob(ORGANIZATION_ID, JOB_ID)).thenReturn(1);

        service.hideExperimentImport(JOB_ID, repository.findJobForUpdate(ORGANIZATION_ID, JOB_ID).orElseThrow().getRevision());

        verify(repository).hideExperimentJob(ORGANIZATION_ID, JOB_ID);
    }

    @Test
    void staleImportRevisionCannotDeleteRetryOrOverwriteMappedValues() {
        when(repository.findJobForUpdate(ORGANIZATION_ID, JOB_ID)).thenReturn(Optional.of(job("COMPLETED")));
        when(repository.findSourceOwner(ORGANIZATION_ID, JOB_ID)).thenReturn(Optional.of("EXPERIMENT"));
        var stale=job("FAILED").getRevision();
        assertThatThrownBy(()->service.hideExperimentImport(JOB_ID,stale)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.retryExperimentImport(JOB_ID,stale)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.saveMappingsAtRevision(JOB_ID,stale,List.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.correctValueAtRevision(JOB_ID,stale,UUID.randomUUID(),"field","",null,"reason")).isInstanceOf(ApiException.class);
        verify(repository,never()).hideExperimentJob(ORGANIZATION_ID,JOB_ID);
        verify(repository,never()).clearParsed(ORGANIZATION_ID,JOB_ID);
    }

    private DataRepository.Job job(String status) {
        var now = Instant.parse("2026-10-23T01:00:00Z");
        return new DataRepository.Job(JOB_ID, UUID.randomUUID(), "source-hash", "sample.xlsx", "XLSX",
                UUID.randomUUID(), null, status, 100, status, "parser", null, now, now);
    }
}
