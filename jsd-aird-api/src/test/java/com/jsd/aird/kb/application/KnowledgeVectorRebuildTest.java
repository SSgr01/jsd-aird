package com.jsd.aird.kb.application;

import org.springframework.test.util.ReflectionTestUtils;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

class KnowledgeVectorRebuildTest {
    @Test void creationConflictsDistinguishOldSchemaAndFileDuplicatesFromOtherDatabaseErrors() {
        var service = worker();
        var oldSchema = new org.springframework.dao.DuplicateKeyException("insert", new java.sql.SQLException(
                "document_organization_id_title_current_version_no_key", "23505"));
        RuntimeException oldResult = ReflectionTestUtils.invokeMethod(service, "creationConflict", oldSchema);
        assertThat(oldResult).isInstanceOf(ApiException.class).hasMessageContaining("V119");
        var duplicate = new org.springframework.dao.DuplicateKeyException("insert", new java.sql.SQLException(
                "uq_kb_document_version_org_sha256", "23505"));
        RuntimeException duplicateResult = ReflectionTestUtils.invokeMethod(service, "creationConflict", duplicate);
        assertThat(duplicateResult).isInstanceOf(ApiException.class).hasMessageContaining("相同文件");
        var other = new org.springframework.dao.DataIntegrityViolationException("foreign key");
        RuntimeException otherResult = ReflectionTestUtils.invokeMethod(service, "creationConflict", other);
        assertThat(otherResult).isSameAs(other);
    }
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
        var key = "kb-rebuild:"+doc+":"+com.jsd.aird.shared.evidence.EvidenceProtocol.sha("qwen-new\n1024\n"+pub+"\nfull").substring(0,32)+":"+com.jsd.aird.shared.evidence.EvidenceProtocol.sha("resume-key");
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
        when(async.findJob(actor.organizationId(), "kb-rebuild:"+doc+":"+com.jsd.aird.shared.evidence.EvidenceProtocol.sha("qwen-new\n1024\n"+pub+"\nfull").substring(0,32)+":"+com.jsd.aird.shared.evidence.EvidenceProtocol.sha("resume-key")))
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
        when(repository.chunksForEmbedding(org, doc, revision,"qwen-new",1024))
                .thenReturn(List.of(new KnowledgeRepository.ChunkEmbeddingRow(chunkId, "published evidence")));
        var embedding = mock(KnowledgeEmbeddingFacade.class);
        when(embedding.embedVectors(List.of("published evidence"))).thenReturn(List.of(Optional.of("[0.1,0.2]")));
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

    @Test void vectorRebuildSuspendsAnyEnclosingTransactionWhileCallingEmbedding() throws Exception {
        var current = publication();
        when(repository.findDocument(org, doc)).thenReturn(Optional.of(mock(KnowledgeRepository.DocumentRow.class)));
        when(governance.currentPublication(org, doc)).thenReturn(Optional.of(current));
        when(governance.currentPublicationById(org, pub)).thenReturn(Optional.of(current));
        when(repository.isAiApproved(org, doc)).thenReturn(true);
        when(repository.chunksForEmbedding(org, doc, revision, "qwen-new", 1024))
                .thenReturn(List.of(new KnowledgeRepository.ChunkEmbeddingRow(UUID.randomUUID(), "evidence")));
        var embedding = mock(KnowledgeEmbeddingFacade.class);
        when(embedding.embedVectors(List.of("evidence"))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(Optional.of("[1,0]"));
        });
        var connection = mock(java.sql.Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var source = mock(javax.sql.DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        var factory = new org.springframework.aop.framework.ProxyFactory(worker(embedding));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var service = (KnowledgeService) factory.getProxy();
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status -> {
            service.rebuildPublishedIndex(org, doc, pub, "qwen-new", 1024, true);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
        verify(embedding).embedVectors(List.of("evidence"));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test void vectorBuildBatchesChunksInOrderAndKeepsCompletedBatchesOnFailure() {
        var chunks = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> new KnowledgeRepository.ChunkEmbeddingRow(UUID.randomUUID(), "text-" + i)).toList();
        when(repository.isAiApproved(org, doc)).thenReturn(true);
        var published = publication();
        when(governance.currentPublicationById(org, pub)).thenReturn(Optional.of(published));
        when(repository.chunksForEmbedding(org, doc, revision, "qwen-new", 1024)).thenReturn(chunks);
        var embedding = mock(KnowledgeEmbeddingFacade.class);
        when(embedding.embedVectors(List.of("text-0", "text-1")))
                .thenReturn(List.of(Optional.of("[1,0]"), Optional.of("[0,1]")));
        when(embedding.embedVectors(List.of("text-2", "text-3")))
                .thenReturn(List.of(Optional.of("[1,1]"), Optional.empty()));
        var service = worker(embedding);
        ReflectionTestUtils.setField(service, "embeddingBuildBatchSize", 2);
        assertThatThrownBy(() -> service.buildVectors(org, doc, pub, revision))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("完整结果");
        verify(repository).updateChunkEmbedding(org, chunks.get(0).id(), "[1,0]", "qwen-new");
        verify(repository).updateChunkEmbedding(org, chunks.get(1).id(), "[0,1]", "qwen-new");
        verify(repository, times(2)).updateChunkEmbedding(any(), any(), anyString(), anyString());
        verify(embedding, never()).embedVectors(List.of("text-4"));
        verify(repository, never()).finishProcessingStep(any(), any(), any(), anyString(), eq("SUCCEEDED"), any(), any());
    }

    @Test void batchBuildStopsOnRevokedAiGrantAndBoundsRequestText() {
        var chunks = List.of(new KnowledgeRepository.ChunkEmbeddingRow(UUID.randomUUID(), "a".repeat(20000)),
                new KnowledgeRepository.ChunkEmbeddingRow(UUID.randomUUID(), "b".repeat(20000)));
        when(repository.isAiApproved(org, doc)).thenReturn(true, true, false);
        var published = publication();
        when(governance.currentPublicationById(org, pub)).thenReturn(Optional.of(published));
        when(repository.chunksForEmbedding(org, doc, revision, "qwen-new", 1024)).thenReturn(chunks);
        var embedding = mock(KnowledgeEmbeddingFacade.class);
        when(embedding.embedVectors(List.of(chunks.getFirst().content()))).thenReturn(List.of(Optional.of("[1,0]")));
        worker(embedding).buildVectors(org, doc, pub, revision);
        verify(embedding, times(1)).embedVectors(anyList());
        verify(repository).clearDocumentEmbeddings(org, doc);
        verify(repository, never()).updateChunkEmbedding(eq(org), eq(chunks.getLast().id()), anyString(), anyString());
    }

    @Test void vectorStatusExposesMixedStoredModelsInsteadOfEquatingPresenceWithCompatibility() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class), eq(version), eq(org), eq(doc))).thenReturn(2L);
        when(jdbc.queryForList(anyString(), eq(version), eq(org), eq(doc))).thenReturn(List.of(
                Map.of("model", "old-cloud-model", "dimension", 1024, "count", 1L),
                Map.of("model", "qwen-new", "dimension", 1024, "count", 1L)));
        var repo=new JdbcBibliographyRepository(jdbc,json);
        ReflectionTestUtils.setField(repo,"targetModel","qwen-new");
        var result = repo.vectorStatus(org, doc, version);
        assertThat(result.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(result.path("modelCompatibilityChecked").asBoolean()).isTrue();
        assertThat(result.path("models").size()).isEqualTo(2);
        assertThat(result.path("models").get(0).path("model").asText()).isEqualTo("old-cloud-model");
    }
    @Test void localPublicationQueuesOnlyTheAutomaticQwenTarget() {
        var knowledge=mock(KnowledgeService.class);var service=api(knowledge);
        when(knowledge.indexModel()).thenReturn(new KnowledgeService.IndexModel("text-embedding-v4",1024));
        when(knowledge.autoIndexModel()).thenReturn(new KnowledgeService.IndexModel("Qwen/Qwen3-Embedding-4B",2560));
        ReflectionTestUtils.invokeMethod(service,"enqueueIndex",org,UUID.randomUUID(),doc,version,revision,1,true,false);
        var payload=ArgumentCaptor.forClass(JsonNode.class);
        verify(async).enqueue(eq(org),eq("KB_BUILD_KNOWLEDGE_INDEX"),payload.capture(),anyString(),eq(50));
        assertThat(payload.getValue().path("expectedEmbeddingModel").asText()).isEqualTo("Qwen/Qwen3-Embedding-4B");
        assertThat(payload.getValue().path("expectedEmbeddingDimension").asInt()).isEqualTo(2560);
        verify(knowledge,never()).buildAndPublish(any(),any(),any(),any(),any(),anyInt(),anyBoolean());
    }
    @Test void idempotencySeparatesModesAndFitsTheExistingDatabaseKeyLimit() {
        var service=api(mock(KnowledgeService.class));
        service.rebuildPublishedIndex(doc,"r".repeat(128),false);
        service.rebuildPublishedIndex(doc,"r".repeat(128),true);
        var keys=ArgumentCaptor.forClass(String.class);
        verify(async,times(2)).enqueue(any(),eq("KB_REBUILD_PUBLISHED_INDEX"),any(),keys.capture(),eq(45));
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
        assertThat(keys.getAllValues()).allSatisfy(key->assertThat(key.length()).isLessThanOrEqualTo(160));
    }

}
