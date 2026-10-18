package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.application.port.KnowledgeUploadTaskRepository;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeUploadTaskServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    private final KnowledgeUploadTaskRepository tasks = mock(KnowledgeUploadTaskRepository.class);
    private final KnowledgeRepository knowledge = mock(KnowledgeRepository.class);
    private final KnowledgeUploadTaskService service = new KnowledgeUploadTaskService(tasks, knowledge);

    @BeforeEach
    void setActor() {
        ActorContext.set(new Actor(ORGANIZATION_ID, ACTOR_ID, "tester"));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void createsTaskForCurrentOrganizationAndActor() {
        when(tasks.create(any(UUID.class), eq(ORGANIZATION_ID), eq(ACTOR_ID), eq("report.pdf"), eq(1024L),
                any(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenAnswer(invocation -> row(invocation.getArgument(0), "STAGING", null, null));

        var result = service.create(new KnowledgeUploadTaskService.CreateCommand(" report.pdf ", 1024,
                JsonNodeFactory.instance.objectNode()));

        assertThat(result.fileName()).isEqualTo("report.pdf");
        assertThat(result.status()).isEqualTo("STAGING");
        verify(tasks).create(any(UUID.class), eq(ORGANIZATION_ID), eq(ACTOR_ID), eq("report.pdf"), eq(1024L),
                any(com.fasterxml.jackson.databind.JsonNode.class));
    }

    @Test
    void queuedTaskMustReferenceAnExistingDocumentVersion() {
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var document = mock(KnowledgeRepository.DocumentRow.class);
        var version = mock(KnowledgeRepository.VersionRow.class);
        when(knowledge.findDocument(ORGANIZATION_ID, documentId)).thenReturn(Optional.of(document));
        when(document.id()).thenReturn(documentId);
        when(knowledge.listVersions(ORGANIZATION_ID, documentId)).thenReturn(List.of(version));
        when(version.id()).thenReturn(versionId);
        when(tasks.update(eq(ORGANIZATION_ID), eq(ACTOR_ID), any(UUID.class), eq("QUEUED"), eq(100),
                eq(null), eq(null), eq(documentId), eq(versionId), eq("已提交解析")))
                .thenAnswer(invocation -> Optional.of(row(invocation.getArgument(2), "QUEUED", documentId, versionId)));

        var result = service.update(UUID.randomUUID(), new KnowledgeUploadTaskService.UpdateCommand(
                "QUEUED", 100, null, null, documentId, versionId, "已提交解析"));

        assertThat(result.documentId()).isEqualTo(documentId);
        assertThat(result.documentVersionId()).isEqualTo(versionId);
    }

    @Test
    void rejectsQueuedTaskWithoutDocumentAndVersion() {
        assertThatThrownBy(() -> service.update(UUID.randomUUID(), new KnowledgeUploadTaskService.UpdateCommand(
                "QUEUED", 100, null, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("必须关联知识文档版本");
    }

    @Test
    void limitsServerTaskHistoryToOneHundredRows() {
        when(tasks.list(ORGANIZATION_ID, ACTOR_ID, 100)).thenReturn(List.of());

        assertThat(service.list(500)).isEmpty();

        verify(tasks).list(ORGANIZATION_ID, ACTOR_ID, 100);
    }

    private KnowledgeUploadTaskRepository.UploadTaskRow row(UUID id, String status,
                                                             UUID documentId, UUID versionId) {
        return new KnowledgeUploadTaskRepository.UploadTaskRow(id, "report.pdf", 1024, status, 100,
                null, JsonNodeFactory.instance.objectNode(), documentId, versionId, "已提交解析",
                Instant.now(), Instant.now());
    }
}
