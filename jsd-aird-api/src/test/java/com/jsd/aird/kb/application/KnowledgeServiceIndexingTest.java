package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.FileSafetyScanner;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class KnowledgeServiceIndexingTest {

    @Test
    void buildsKeywordChunksFromReviewRevisionWithoutCallingEmbeddingWhenNotAuthorized() {
        var repository = mock(KnowledgeRepository.class);
        var governance = mock(KnowledgeGovernanceRepository.class);
        var embeddingProvider = mockEmbeddingProvider();
        var objectMapper = new ObjectMapper();
        var documents = new StructuredDocumentCodec(objectMapper);
        var organizationId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var parseRunId = UUID.randomUUID();
        var reviewRevisionId = UUID.randomUUID();
        var publicationId = UUID.randomUUID();
        var initialized = documents.initialize(List.of(new DocumentParser.TextBlock(2, "paragraph",
                "人工确认文本", null, null, "paragraph-1", List.of(), null, null, 0.92)));
        var source = initialized.sourceNodes().getFirst();
        var parseRun = new KnowledgeGovernanceRepository.ParseRunRow(parseRunId, documentId, versionId,
                1, "SUCCEEDED", null, Instant.now(), initialized.sourceDocument(), 1);
        var revision = new KnowledgeGovernanceRepository.ReviewRevisionView(reviewRevisionId, parseRunId,
                1, 3, null, initialized.confirmedDocument(), List.of(), "BUILDING", null, Instant.now());
        var sourceNode = new KnowledgeGovernanceRepository.SourceNodeView(source.sourceNodeKey(), 0,
                source.nodeType(), source.rawText(), source.sourceAnchor(), source.confidence());
        var review = new KnowledgeGovernanceRepository.ReviewView(documentId, "测试文档", "INTERNAL",
                UUID.randomUUID(), "测试分类", "ACTIVE", versionId, 1, UUID.randomUUID(), "test.pdf",
                "application/pdf", 100, "READY", "PENDING_REVIEW", objectMapper.createObjectNode(), parseRun,
                List.of(sourceNode), revision, List.of(), List.of("测试"));
        var publication = new KnowledgeGovernanceRepository.PublicationRow(publicationId, documentId,
                versionId, parseRunId, reviewRevisionId, 1, "CURRENT", "PENDING", Instant.now());
        when(governance.review(organizationId, documentId, versionId)).thenReturn(Optional.of(review));
        when(governance.largeTableRows(organizationId, reviewRevisionId)).thenReturn(List.of());
        when(repository.isAiApproved(organizationId, documentId)).thenReturn(false);
        when(governance.publish(organizationId, actorId, documentId, versionId, reviewRevisionId, 3))
                .thenReturn(publication);

        var service = new KnowledgeService(repository, governance, mock(FileStorageFacade.class),
                mock(OpsAsyncFacade.class), mock(AuditLogFacade.class), objectMapper, documents, List.of(),
                mock(FileSafetyScanner.class), embeddingProvider, List.of(), "embedding-model", 1024,
                Duration.ofMinutes(15));

        var result = service.buildAndPublish(organizationId, actorId, documentId, versionId,
                reviewRevisionId, 3);

        assertThat(result).isEqualTo(publication);
        @SuppressWarnings("unchecked")
        var chunks = ArgumentCaptor.forClass((Class<List<KnowledgeRepository.ChunkWrite>>) (Class<?>) List.class);
        verify(repository).replaceChunks(eq(documentId), eq(versionId), eq(reviewRevisionId), chunks.capture());
        assertThat(chunks.getValue()).singleElement().satisfies(chunk -> {
            assertThat(chunk.content()).isEqualTo("人工确认文本");
            assertThat(chunk.vector()).isNull();
            assertThat(chunk.terms()).isNotEmpty();
            assertThat(chunk.pageNo()).isNull();
            assertThat(chunk.paragraphId()).isEqualTo("paragraph-1");
        });
        verify(embeddingProvider, never()).getIfAvailable();
        verify(repository).finishProcessingStep(organizationId, versionId, reviewRevisionId,
                "VECTOR_INDEX", "NOT_REQUIRED", null, null);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<KnowledgeEmbeddingFacade> mockEmbeddingProvider() {
        return (ObjectProvider<KnowledgeEmbeddingFacade>) mock(ObjectProvider.class);
    }
}
