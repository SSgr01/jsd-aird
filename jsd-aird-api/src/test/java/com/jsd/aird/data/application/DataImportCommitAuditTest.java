package com.jsd.aird.data.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DataImportCommitAuditTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final DataRepository repository = mock(DataRepository.class);
    private final FileObjectRepository files = mock(FileObjectRepository.class);
    private final TemplateDataImportFacade templates = mock(TemplateDataImportFacade.class);
    private final AuditLogFacade auditLog = mock(AuditLogFacade.class);
    private final OpsAsyncFacade opsAsync = mock(OpsAsyncFacade.class);
    private final DataImportService service = new DataImportService(
            repository, files, templates, new ObjectMapper(), auditLog, opsAsync);
    private final UUID importJobId = UUID.randomUUID();
    private final UUID templateVersionId = UUID.randomUUID();
    private final DataRepository.Job job = new DataRepository.Job(
            importJobId, UUID.randomUUID(), "source-sha", "source.xlsx", "XLSX", templateVersionId,
             null, "WAITING_CONFIRM", 85, "WAITING_CONFIRM", "parser", null, Instant.now(), Instant.now());

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void summaryNeverLoadsRowsMappingsTemplateOrExecutionContract() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID,USER_ID,"developer"));
        when(repository.findJob(ORGANIZATION_ID,importJobId)).thenReturn(Optional.of(job));
        var summary=service.previewSummary(importJobId);
        assertThat(summary.rows()).isEmpty();assertThat(summary.mappings()).isEmpty();
        org.mockito.Mockito.verify(repository,org.mockito.Mockito.never()).listRows(any(),any());
        org.mockito.Mockito.verify(repository,org.mockito.Mockito.never()).listMappings(any(),any());
        org.mockito.Mockito.verify(repository,org.mockito.Mockito.never()).listSheets(any(),any());
        org.mockito.Mockito.verify(repository,org.mockito.Mockito.never()).findCompatibilityReport(any(),any());
        verifyNoInteractions(templates);
    }

    @Test
    void appendsAuditWithIdentifiersAndHashesWithoutObsoleteIndexingOutbox() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID, USER_ID, "developer"));
        var recordId = UUID.randomUUID();
        when(repository.findJobForUpdate(ORGANIZATION_ID, importJobId)).thenReturn(Optional.of(job));
        when(repository.listIssues(ORGANIZATION_ID, importJobId)).thenReturn(List.of());
        when(repository.listRows(ORGANIZATION_ID, importJobId)).thenReturn(List.of(
                new DataRepository.Row(UUID.randomUUID(), "sheet-1", 2,
                        new ObjectMapper().createObjectNode().put("A", "raw-secret"),
                        new ObjectMapper().createObjectNode().set("code", new ObjectMapper().createObjectNode().put("normalizedValue", "STD-1")),
                        new ObjectMapper().createObjectNode(), "VALID")));
        when(repository.listMappings(ORGANIZATION_ID, importJobId)).thenReturn(List.of());
        when(repository.listSheets(ORGANIZATION_ID, importJobId)).thenReturn(List.of());
        when(repository.commit(eq(ORGANIZATION_ID), eq(importJobId), eq(USER_ID), any()))
                .thenReturn(new DataRepository.CommitResult(
                        List.of(new DataRepository.CommittedRecord(recordId, "record-1", "data-hash")), 1));

        service.commit(importJobId);

        var detail = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(auditLog).append(eq(ORGANIZATION_ID), eq(USER_ID), eq("DATA_IMPORT_COMMITTED"),
                eq("DATA_IMPORT_JOB"), eq(importJobId), detail.capture());
        verifyNoInteractions(opsAsync);
        assertThat(detail.getValue().path("sourceSha256").asText()).isEqualTo("source-sha");
        assertThat(detail.getValue().path("reviewedRevision").asText()).isEqualTo(job.getRevision());
        assertThat(detail.getValue().path("reviewedContentHash").asText()).hasSize(64);
        assertThat(detail.getValue().path("records").get(0).path("recordId").asText()).isEqualTo(recordId.toString());
        assertThat(detail.getValue().toString()).doesNotContain("raw-secret");
    }

    @Test
    void auditFailurePreventsOutboxAndPropagatesForTransactionRollback() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID, USER_ID, "developer"));
        when(repository.findJobForUpdate(ORGANIZATION_ID, importJobId)).thenReturn(Optional.of(job));
        when(repository.listIssues(ORGANIZATION_ID, importJobId)).thenReturn(List.of());
        when(repository.listRows(ORGANIZATION_ID, importJobId)).thenReturn(List.of());
        when(repository.listMappings(ORGANIZATION_ID, importJobId)).thenReturn(List.of());
        when(repository.listSheets(ORGANIZATION_ID, importJobId)).thenReturn(List.of());
        when(repository.commit(eq(ORGANIZATION_ID), eq(importJobId), eq(USER_ID), any()))
                .thenReturn(new DataRepository.CommitResult(List.of(), 0));
        doThrow(new IllegalStateException("audit unavailable")).when(auditLog).append(
                eq(ORGANIZATION_ID), eq(USER_ID), eq("DATA_IMPORT_COMMITTED"), eq("DATA_IMPORT_JOB"),
                eq(importJobId), any());

        assertThatThrownBy(() -> service.commit(importJobId)).hasMessage("audit unavailable");
        verifyNoInteractions(opsAsync);
    }

    @Test
    void usesCorrectedIdentityValueWhenCommitting() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID, USER_ID, "developer"));
        var mapper = new ObjectMapper();
        var normalizedWrapper = mapper.createObjectNode()
                .put("fieldCode", "MATERIAL.CODE").put("bindingId", "material-code")
                .put("valuePath", "/material/code").put("normalizedValue", "OLD-001");
        var correctedWrapper = normalizedWrapper.deepCopy().put("correctedValue", "NEW-001");
        var row = new DataRepository.Row(UUID.randomUUID(), "sheet-1", 2,
                mapper.createObjectNode().put("A", "OLD-001"),
                mapper.createObjectNode().set("MATERIAL.CODE", normalizedWrapper),
                mapper.createObjectNode().set("MATERIAL.CODE", correctedWrapper), "VALID");
        var mappingDetail = mapper.createObjectNode().put("identity", true)
                .put("bindingId", "material-code").put("dataPath", "/material/code");
        var mapping = new DataRepository.Mapping(UUID.randomUUID(), "sheet-1", "A", "物料编码",
                "MATERIAL.CODE", "物料编码", "MAP", "TEXT", null, null, mappingDetail, "CONFIRMED");
        when(repository.findJobForUpdate(ORGANIZATION_ID, importJobId)).thenReturn(Optional.of(job));
        when(repository.listIssues(ORGANIZATION_ID, importJobId)).thenReturn(List.of());
        when(repository.listRows(ORGANIZATION_ID, importJobId)).thenReturn(List.of(row));
        when(repository.listMappings(ORGANIZATION_ID, importJobId)).thenReturn(List.of(mapping));
        when(repository.listSheets(ORGANIZATION_ID, importJobId)).thenReturn(List.of());
        when(repository.commit(eq(ORGANIZATION_ID), eq(importJobId), eq(USER_ID), any()))
                .thenReturn(new DataRepository.CommitResult(List.of(), 1));

        service.commit(importJobId);

        @SuppressWarnings("unchecked")
        var committed = (ArgumentCaptor<List<DataRepository.CommittedRow>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(repository).commit(eq(ORGANIZATION_ID), eq(importJobId), eq(USER_ID), committed.capture());
        assertThat(committed.getValue()).singleElement()
                .extracting(DataRepository.CommittedRow::recordKey).isEqualTo("NEW-001");
    }

    @Test
    void confirmedExperimentEnqueuesPreparationInTheCommitAndReusesAnActiveJob() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID,USER_ID,"developer"));
        var confirmed=experimentJob("COMPLETED");
        when(repository.findJobForUpdate(ORGANIZATION_ID,importJobId)).thenReturn(Optional.of(confirmed));
        var payload=ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        service.commit(importJobId);
        verify(opsAsync).enqueue(eq(ORGANIZATION_ID),eq("DATA_TO_EXPERIMENT_SYNC"),payload.capture(),
                eq("data-experiment-prepare:"+importJobId+":"+confirmed.getRevision()),eq(40));
        assertThat(payload.getValue().path("phase").asText()).isEqualTo("PREPARE");
        assertThat(payload.getValue().path("reviewedRevision").asText()).isEqualTo(confirmed.getRevision());
        org.mockito.Mockito.clearInvocations(opsAsync);
        when(opsAsync.findLatestJob(eq(ORGANIZATION_ID),any())).thenReturn(Optional.of(new OpsAsyncFacade.AsyncJobView(
                UUID.randomUUID(),"DATA_TO_EXPERIMENT_SYNC","RUNNING",0,"PREPARING",1,5,Instant.EPOCH,null,null)));
        service.commit(importJobId);
        org.mockito.Mockito.verify(opsAsync,org.mockito.Mockito.never()).enqueue(any(),any(),any(),any(),org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void initialConfirmationRecordsAuditBeforeEnqueueAndPropagatesEnqueueFailure() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID,USER_ID,"developer"));
        when(repository.findJobForUpdate(ORGANIZATION_ID,importJobId)).thenReturn(Optional.of(experimentJob("WAITING_CONFIRM")));
        when(repository.findJob(ORGANIZATION_ID,importJobId)).thenReturn(Optional.of(experimentJob("COMPLETED")));
        when(repository.commit(eq(ORGANIZATION_ID),eq(importJobId),eq(USER_ID),any())).thenReturn(new DataRepository.CommitResult(List.of(),0));
        doThrow(new IllegalStateException("queue unavailable")).when(opsAsync).enqueue(any(),any(),any(),any(),org.mockito.ArgumentMatchers.anyInt());
        assertThatThrownBy(()->service.commit(importJobId)).hasMessage("queue unavailable");
        var order=org.mockito.Mockito.inOrder(auditLog,opsAsync);
        order.verify(auditLog).append(eq(ORGANIZATION_ID),eq(USER_ID),eq("DATA_IMPORT_COMMITTED"),eq("DATA_IMPORT_JOB"),eq(importJobId),any());
        order.verify(opsAsync).enqueue(any(),any(),any(),any(),org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void failedPreparationCanBeRetriedWithoutExceedingTheJobKeyLimit() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID,USER_ID,"developer"));
        var confirmed=experimentJob("COMPLETED");var failed=UUID.randomUUID();
        when(repository.findJobForUpdate(ORGANIZATION_ID,importJobId)).thenReturn(Optional.of(confirmed));
        when(opsAsync.findLatestJob(eq(ORGANIZATION_ID),any())).thenReturn(Optional.of(new OpsAsyncFacade.AsyncJobView(
                failed,"DATA_TO_EXPERIMENT_SYNC","FAILED",0,"PREPARING",1,5,Instant.EPOCH,null,null)));
        service.commit(importJobId);
        var key=ArgumentCaptor.forClass(String.class);
        verify(opsAsync).enqueue(eq(ORGANIZATION_ID),eq("DATA_TO_EXPERIMENT_SYNC"),any(),key.capture(),eq(40));
        assertThat(key.getValue()).hasSizeLessThanOrEqualTo(160)
                .startsWith("data-experiment-prepare:"+importJobId+":"+confirmed.getRevision())
                .endsWith(failed.toString().replace("-",""));
    }

    private DataRepository.Job experimentJob(String status) {
        return new DataRepository.Job(importJobId,job.sourceFileId(),"source-sha","source.xlsx","XLSX",templateVersionId,
                null,status,100,status,"parser",null,Instant.EPOCH,Instant.EPOCH,9,"contract","COMPATIBLE","EXPERIMENT_DRAFT",null);
    }

    @Test
    void preservesV9StructuralRecordKeyInsteadOfUsingSourceIdentity() {
        ActorContext.set(new com.jsd.aird.shared.security.Actor(ORGANIZATION_ID, USER_ID, "developer"));
        var mapper = new ObjectMapper();
        var structuralKey = "IMPORT:STRUCT:sheet-1:component-a:COLUMN:1";
        var normalizedWrapper = mapper.createObjectNode()
                .put("fieldCode", "EXPERIMENT.NO").put("bindingId", "experiment-no")
                .put("valuePath", "/experimentNo").put("normalizedValue", "G-6563");
        var metadata = mapper.createObjectNode()
                .put("recordKey", structuralKey)
                .put("recordKeyKind", "STRUCTURAL")
                .put("sourceIdentity", "G-6563")
                .put("sourceIdentityType", "EXPERIMENT_NO");
        var row = new DataRepository.Row(UUID.randomUUID(), "sheet-1", 1,
                mapper.createObjectNode().put("A", "G-6563"),
                mapper.createObjectNode().set("EXPERIMENT.NO", normalizedWrapper),
                mapper.createObjectNode(), "VALID", metadata);
        var mappingDetail = mapper.createObjectNode().put("identity", true)
                .put("bindingId", "experiment-no").put("dataPath", "/experimentNo");
        var mapping = new DataRepository.Mapping(UUID.randomUUID(), "sheet-1", "A", "实验编号",
                "EXPERIMENT.NO", "实验编号", "MAP", "TEXT", null, null, mappingDetail, "CONFIRMED");
        when(repository.findJobForUpdate(ORGANIZATION_ID, importJobId)).thenReturn(Optional.of(job));
        when(repository.listIssues(ORGANIZATION_ID, importJobId)).thenReturn(List.of());
        when(repository.listRows(ORGANIZATION_ID, importJobId)).thenReturn(List.of(row));
        when(repository.listMappings(ORGANIZATION_ID, importJobId)).thenReturn(List.of(mapping));
        when(repository.listSheets(ORGANIZATION_ID, importJobId)).thenReturn(List.of());
        when(repository.commit(eq(ORGANIZATION_ID), eq(importJobId), eq(USER_ID), any()))
                .thenReturn(new DataRepository.CommitResult(List.of(), 1));

        service.commit(importJobId);

        @SuppressWarnings("unchecked")
        var committed = (ArgumentCaptor<List<DataRepository.CommittedRow>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(repository).commit(eq(ORGANIZATION_ID), eq(importJobId), eq(USER_ID), committed.capture());
        assertThat(committed.getValue()).singleElement()
                .extracting(DataRepository.CommittedRow::recordKey).isEqualTo(structuralKey);
    }
}
