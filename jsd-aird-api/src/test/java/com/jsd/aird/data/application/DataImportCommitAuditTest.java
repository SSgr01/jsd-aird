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
    void appendsAuditAndOutboxWithIdentifiersAndHashes() {
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
        verify(opsAsync).appendOutbox(eq("DATA_IMPORT_JOB"), eq(importJobId), eq("DATA_RECORDS_COMMITTED"), any());
        assertThat(detail.getValue().path("sourceSha256").asText()).isEqualTo("source-sha");
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
}
