package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.core.api.ProjectResourceFacade;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.kb.api.FileSafetyScanner;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.domain.LexicalAnalyzer;
import com.jsd.aird.kb.infrastructure.JdbcBibliographyRepository;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.review.WorkspaceReviewService;
import com.jsd.aird.shared.security.ActorContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class KnowledgeVectorRebuildTest {
    private final ObjectMapper json = new ObjectMapper();
    private final UUID org = UUID.randomUUID(), doc = UUID.randomUUID(), pub = UUID.randomUUID();
    private final UUID version = UUID.randomUUID(), revision = UUID.randomUUID(), job = UUID.randomUUID();
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final KnowledgeGovernanceRepository governance = mock(KnowledgeGovernanceRepository.class);
    private final OpsAsyncFacade async = mock(OpsAsyncFacade.class);

    @AfterEach void clearActor() { ActorContext.clear(); }

    private KnowledgeGovernanceRepository.PublicationRow publication() {
        var value = mock(KnowledgeGovernanceRepository.PublicationRow.class);
        when(value.id()).thenReturn(pub);
        when(value.versionId()).thenReturn(version);
        when(value.reviewRevisionId()).thenReturn(revision);
        return value;
    }

    private KnowledgeGovernanceService api(KnowledgeService knowledge) {
        var actor = ActorContext.developmentDefault();
        ActorContext.set(actor);
        var current = publication();
        when(governance.currentPublication(actor.organizationId(), doc)).thenReturn(Optional.of(current));
        when(knowledge.indexModel()).thenReturn(new KnowledgeService.IndexModel("qwen-new", 1024));
        return new KnowledgeGovernanceService(governance, repository, knowledge, mock(FileStorageFacade.class),
                async, mock(AuditLogFacade.class), json, new StructuredDocumentCodec(json), mock(SystemParsingPolicy.class),
                mock(PlatformTransactionManager.class), mock(ProjectResourceFacade.class), mock(AuthorizationService.class),
                mock(WorkspaceReviewService.class));
    }

    private KnowledgeService worker() {
        return worker(null);
    }

    private KnowledgeService worker(KnowledgeEmbeddingFacade embedding) {
        var provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embedding);
        return new KnowledgeService(repository, governance, mock(FileStorageFacade.class), async,
                mock(AuditLogFacade.class), json, new StructuredDocumentCodec(json), List.of(),
                mock(FileSafetyScanner.class), provider, List.of(), "qwen-new", 1024,
                Duration.ofMinutes(15), new BlockAwareChunker(json), mock(LexicalAnalyzer.class));
    }

    @Test void enqueueReturnsTheDurableJobAndBindsSnapshotAndWorkerProfile() {
        var service = api(mock(KnowledgeService.class));
        var actor = ActorContext.required();
        var key = "kb-rebuild-published-index:" + doc + ":resume-key";
        when(async.enqueue(eq(actor.organizationId()), eq("KB_REBUILD_PUBLISHED_INDEX"), any(), eq(key), eq(45)))
                .thenReturn(job);
        assertThat(service.rebuildPublishedIndex(doc, "resume-key").jobId()).isEqualTo(job);
        assertThat(service.rebuildPublishedIndex(doc, "resume-key").jobId()).isEqualTo(job);
        var payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(async, times(2)).enqueue(eq(actor.organizationId()), eq("KB_REBUILD_PUBLISHED_INDEX"), payload.capture(), eq(key), eq(45));
        assertThat(payload.getValue().path("expectedPublicationId").asText()).isEqualTo(pub.toString());
        assertThat(payload.getValue().path("expectedEmbeddingModel").asText()).isEqualTo("qwen-new");
        assertThat(payload.getValue().path("expectedEmbeddingDimension").asInt()).isEqualTo(1024);
    }

    @Test void progressReadsTheRequestedJobWithoutEnqueuingAnother() {
        var service = api(mock(KnowledgeService.class));
        var actor = ActorContext.required();
        when(repository.isAiApproved(actor.organizationId(), doc)).thenReturn(true);
        when(async.findJob(actor.organizationId(), "kb-rebuild-published-index:" + doc + ":resume-key"))
                .thenReturn(Optional.of(new OpsAsyncFacade.AsyncJobView(job, "KB_REBUILD_PUBLISHED_INDEX", "RUNNING",
                        0, null, 1, 5, null, null, null)));
        var result = service.rebuildIndexStatus(doc, "resume-key");
        assertThat(result.publicationId()).isEqualTo(pub);
        assertThat(result.versionId()).isEqualTo(version);
        assertThat(result.embeddingModel()).isEqualTo("qwen-new");
        assertThat(result.aiApproved()).isTrue();
        assertThat(result.job().id()).isEqualTo(job);
        verify(async, never()).enqueue(any(), anyString(), any(), anyString(), anyInt());
        verify(async, never()).findLatestJob(any(), anyString());
    }

    @Test void workerRejectsAJobForAnotherModelEvenAtTheSameDimension() {
        assertThatThrownBy(() -> worker().rebuildPublishedIndex(org, doc, pub, "old-cloud-model", 1024))
                .isInstanceOf(ApiException.class).hasMessageContaining("模型或维度不一致");
        verifyNoInteractions(repository, governance);
    }

    @Test void workerRejectsAChangedPublicationBeforeReplacingChunks() {
        when(repository.findDocument(org, doc)).thenReturn(Optional.of(mock(KnowledgeRepository.DocumentRow.class)));
        var current = publication();
        when(governance.currentPublication(org, doc)).thenReturn(Optional.of(current));
        assertThatThrownBy(() -> worker().rebuildPublishedIndex(org, doc, UUID.randomUUID(), "qwen-new", 1024))
                .isInstanceOf(ApiException.class).hasMessageContaining("发布版本已变化");
        verify(repository, never()).replaceChunks(any(), any(), any(), anyList());
    }

    @Test void handlerPassesTheBoundTargetToTheWorker() {
        var service = mock(KnowledgeService.class);
        var payload = json.createObjectNode().put("organizationId", org.toString()).put("documentId", doc.toString())
                .put("rebuildPublishedIndex", true).put("expectedPublicationId", pub.toString())
                .put("expectedEmbeddingModel", "qwen-new").put("expectedEmbeddingDimension", 1024);
        new KnowledgeIndexJobHandler(service, json).handle(payload);
        verify(service).rebuildPublishedIndex(org, doc, pub, "qwen-new", 1024);
        verify(service, never()).rebuildPublishedIndex(org, doc);
    }

    @Test void vectorMigrationPreservesChunkIdsAndTheLexicalIndex() {
        var current = publication();
        var chunkId = UUID.randomUUID();
        when(repository.findDocument(org, doc)).thenReturn(Optional.of(mock(KnowledgeRepository.DocumentRow.class)));
        when(governance.currentPublication(org, doc)).thenReturn(Optional.of(current));
        when(governance.currentPublicationById(org, pub)).thenReturn(Optional.of(current));
        when(repository.isAiApproved(org, doc)).thenReturn(true);
        when(repository.chunksForEmbedding(org, doc, revision))
                .thenReturn(List.of(new KnowledgeRepository.ChunkEmbeddingRow(chunkId, "published evidence")));
        var embedding = mock(KnowledgeEmbeddingFacade.class);
        when(embedding.embedVector("published evidence")).thenReturn(Optional.of("[0.1,0.2]"));
        worker(embedding).rebuildPublishedIndex(org, doc, pub, "qwen-new", 1024, true);
        verify(repository).updateChunkEmbedding(org, chunkId, "[0.1,0.2]", "qwen-new");
        verify(repository, never()).replaceChunks(any(), any(), any(), anyList());
        verify(repository, never()).rebuildTermStats(any());
    }

    @Test void handlerSelectsVectorOnlyMigrationWhenRequested() {
        var service = mock(KnowledgeService.class);
        var payload = json.createObjectNode().put("organizationId", org.toString()).put("documentId", doc.toString())
                .put("rebuildPublishedIndex", true).put("vectorsOnly", true).put("expectedPublicationId", pub.toString())
                .put("expectedEmbeddingModel", "qwen-new").put("expectedEmbeddingDimension", 1024);
        new KnowledgeIndexJobHandler(service, json).handle(payload);
        verify(service).rebuildPublishedIndex(org, doc, pub, "qwen-new", 1024, true);
    }

    @Test void vectorStatusExposesMixedStoredModelsInsteadOfEquatingPresenceWithCompatibility() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq(version), eq(org), eq(doc))).thenReturn(Map.of("total", 2L, "embedded", 2L));
        when(jdbc.queryForList(anyString(), eq(version), eq(org), eq(doc))).thenReturn(List.of(
                Map.of("model", "old-cloud-model", "dimension", 1024, "count", 1L),
                Map.of("model", "qwen-new", "dimension", 1024, "count", 1L)));
        var result = new JdbcBibliographyRepository(jdbc, json).vectorStatus(org, doc, version);
        assertThat(result.path("status").asText()).isEqualTo("PRESENT");
        assertThat(result.path("modelCompatibilityChecked").asBoolean()).isFalse();
        assertThat(result.path("models").size()).isEqualTo(2);
        assertThat(result.path("models").get(0).path("model").asText()).isEqualTo("old-cloud-model");
    }
}
