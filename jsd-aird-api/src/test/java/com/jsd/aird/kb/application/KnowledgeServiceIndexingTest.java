package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.FileSafetyScanner;
import com.jsd.aird.kb.domain.LexicalAnalyzer;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class KnowledgeServiceIndexingTest {

    @Test
    void runsBm25AndEmbeddingVectorBranchesInParallelAndKeepsBothResults() throws Exception {
        var repository = mock(KnowledgeRepository.class);
        var embedding = mock(KnowledgeEmbeddingFacade.class);
        @SuppressWarnings("unchecked")
        var provider = (ObjectProvider<KnowledgeEmbeddingFacade>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embedding);
        var bm25Started = new CountDownLatch(1);
        var embeddingStarted = new CountDownLatch(1);
        var bm25Row = new KnowledgeRepository.SearchRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "BM25", "bm25.pdf", 1, "paragraph", "bm25", 1.0, 1);
        var vectorRow = new KnowledgeRepository.SearchRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "VECTOR", "vector.pdf", 1, "paragraph", "vector", 0.9, 2);
        when(repository.batchBm25Rank(any(), anyList(), anyBoolean(), anyList(), anyInt())).thenAnswer(ignored -> {
            bm25Started.countDown();
            assertThat(embeddingStarted.await(1, TimeUnit.SECONDS)).isTrue();
            return List.of(new KnowledgeRepository.RankedChunk(0, bm25Row.chunkId(), bm25Row.score(), 1));
        });
        when(embedding.embedVectors(anyList())).thenAnswer(ignored -> {
            embeddingStarted.countDown();
            assertThat(bm25Started.await(1, TimeUnit.SECONDS)).isTrue();
            return List.of(Optional.of("[0.1, 0.2]"));
        });
        when(repository.batchVectorRank(any(), anyList(), anyBoolean(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of(new KnowledgeRepository.RankedChunk(0, vectorRow.chunkId(), vectorRow.score(), 1)));
        when(repository.loadSearchRows(any(), anyList())).thenReturn(List.of(bm25Row, vectorRow));

        var executor = Executors.newFixedThreadPool(2);
        try {
            var mapper = new ObjectMapper();
            var service = new KnowledgeService(repository, mock(KnowledgeGovernanceRepository.class),
                    mock(FileStorageFacade.class), mock(OpsAsyncFacade.class), mock(AuditLogFacade.class), mapper,
                    new StructuredDocumentCodec(mapper), List.of(), mock(FileSafetyScanner.class), provider, List.of(),
                    "embedding-model", 2, Duration.ofMinutes(15), new BlockAwareChunker(mapper), testAnalyzer(),
                    executor);

            var result = service.search(new KnowledgeSearchFacade.SearchRequest(UUID.randomUUID(), "parallel", false,
                    30, List.of(), List.of()));

            assertThat(result.hits()).extracting(KnowledgeSearchFacade.SearchHit::content)
                    .contains("bm25", "vector");
            assertThat(result.trace().queries()).extracting(KnowledgeSearchFacade.QueryTrace::channel)
                    .contains("BM25", "EMBEDDING_BATCH", "VECTOR");
            verify(repository, times(1)).batchBm25Rank(any(), anyList(), anyBoolean(), anyList(), anyInt());
            verify(repository, times(1)).batchVectorRank(any(), anyList(), anyBoolean(), anyList(), anyInt(), anyInt());
            verify(repository, times(1)).loadSearchRows(any(), anyList());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reportsBm25NoHitSeparatelyFromAnUnavailableIndex() {
        var repository = mock(KnowledgeRepository.class);
        var organizationId = UUID.randomUUID();
        var row = new KnowledgeRepository.SearchRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "测试文档", "test.pdf", 1, "paragraph", "全文命中", 1.0, 1);
        when(repository.batchBm25Rank(any(), anyList(), anyBoolean(), anyList(), anyInt())).thenReturn(List.of());
        when(repository.fullTextSearch(any(), anyString(), anyBoolean(), any(), anyInt()))
                .thenReturn(List.of(row));

        var service = new KnowledgeService(repository, mock(KnowledgeGovernanceRepository.class),
                mock(FileStorageFacade.class), mock(OpsAsyncFacade.class), mock(AuditLogFacade.class),
                new ObjectMapper(), new StructuredDocumentCodec(new ObjectMapper()), List.of(),
                mock(FileSafetyScanner.class), mockEmbeddingProvider(), List.of(), "embedding-model", 1024,
                Duration.ofMinutes(15), new BlockAwareChunker(new ObjectMapper()), testAnalyzer());

        var result = service.search(new KnowledgeSearchFacade.SearchRequest(organizationId, "无索引词", false,
                5, List.of(), List.of()));

        assertThat(result.hits()).hasSize(1);
        assertThat(result.trace().fallbacks()).contains("BM25_EMPTY")
                .doesNotContain("BM25_UNAVAILABLE");
    }

    @Test
    void reportsBm25ErrorAndContinuesWithFullText() {
        var repository = mock(KnowledgeRepository.class);
        var organizationId = UUID.randomUUID();
        var row = new KnowledgeRepository.SearchRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "测试文档", "test.pdf", 1, "paragraph", "全文命中", 1.0, 1);
        when(repository.batchBm25Rank(any(), anyList(), anyBoolean(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("index unavailable"));
        when(repository.fullTextSearch(any(), anyString(), anyBoolean(), any(), anyInt()))
                .thenReturn(List.of(row));

        var service = new KnowledgeService(repository, mock(KnowledgeGovernanceRepository.class),
                mock(FileStorageFacade.class), mock(OpsAsyncFacade.class), mock(AuditLogFacade.class),
                new ObjectMapper(), new StructuredDocumentCodec(new ObjectMapper()), List.of(),
                mock(FileSafetyScanner.class), mockEmbeddingProvider(), List.of(), "embedding-model", 1024,
                Duration.ofMinutes(15), new BlockAwareChunker(new ObjectMapper()), testAnalyzer());

        var result = service.search(new KnowledgeSearchFacade.SearchRequest(organizationId, "索引异常", false,
                5, List.of(), List.of()));

        assertThat(result.hits()).hasSize(1);
        assertThat(result.trace().fallbacks()).contains("BM25_ERROR")
                .doesNotContain("BM25_UNAVAILABLE");
    }

    @Test
    void doesNotUseRetrievalTimeNeighborCompensation() {
        var repository = mock(KnowledgeRepository.class);
        var organizationId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var sample = new KnowledgeRepository.SearchRow(UUID.randomUUID(), documentId, versionId,
                "测试文档", "form.pdf", 1, "OCR-LINE", "TEST-TPL-丙烯酸树脂", 3.0, 16);
        var density = new KnowledgeRepository.SearchRow(UUID.randomUUID(), documentId, versionId,
                "测试文档", "form.pdf", 1, "OCR-LINE", "密度", 2.0, 21);
        when(repository.batchBm25Rank(any(), anyList(), anyBoolean(), anyList(), anyInt()))
                .thenReturn(List.of(new KnowledgeRepository.RankedChunk(0, sample.chunkId(), sample.score(), 1),
                        new KnowledgeRepository.RankedChunk(0, density.chunkId(), density.score(), 2)));
        when(repository.loadSearchRows(any(), anyList())).thenReturn(List.of(sample, density));
        var service = new KnowledgeService(repository, mock(KnowledgeGovernanceRepository.class),
                mock(FileStorageFacade.class), mock(OpsAsyncFacade.class), mock(AuditLogFacade.class),
                new ObjectMapper(), new StructuredDocumentCodec(new ObjectMapper()), List.of(),
                mock(FileSafetyScanner.class), mockEmbeddingProvider(), List.of(), "embedding-model", 1024,
                Duration.ofMinutes(15), new BlockAwareChunker(new ObjectMapper()), testAnalyzer());

        var hits = service.search(organizationId, "TEST-TPL-丙烯酸树脂的密度", false, 2);

        assertThat(hits).extracting(KnowledgeSearchFacade.SearchHit::content)
                .containsExactly("TEST-TPL-丙烯酸树脂", "密度");
        assertThat(service.search(organizationId, "密度", false, 3))
                .extracting(KnowledgeSearchFacade.SearchHit::content).doesNotContain("1.05");
    }

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
                1, "SUCCEEDED", null, Instant.now(), initialized.sourceDocument(), 1,
                objectMapper.createObjectNode(), "test-parser", "test", null, false, "LOCAL");
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
                Duration.ofMinutes(15), new BlockAwareChunker(objectMapper), testAnalyzer());

        var result = service.buildAndPublish(organizationId, actorId, documentId, versionId,
                reviewRevisionId, 3);

        assertThat(result).isEqualTo(publication);
        @SuppressWarnings("unchecked")
        var chunks = ArgumentCaptor.forClass((Class<List<KnowledgeRepository.ChunkWrite>>) (Class<?>) List.class);
        verify(repository).replaceChunks(eq(documentId), eq(versionId), eq(reviewRevisionId), chunks.capture());
        assertThat(chunks.getValue()).hasSize(2);
        assertThat(chunks.getValue()).extracting(KnowledgeRepository.ChunkWrite::chunkRole)
                .containsExactly("PARENT", "CHILD");
        var child = chunks.getValue().getLast();
        assertThat(child.parentKey()).isEqualTo(chunks.getValue().getFirst().chunkKey());
        assertThat(child.content()).contains("文档：测试文档", "人工确认文本");
        assertThat(child).satisfies(chunk -> {
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

    private LexicalAnalyzer testAnalyzer() {
        return simpleAnalyzer();
    }

    private LexicalAnalyzer simpleAnalyzer() {
        return new LexicalAnalyzer() {
            @Override public String version() { return "material-smartcn-v2"; }
            @Override public Analysis analyzeDocument(String text) { return analyze(text); }
            @Override public Analysis analyzeQuery(String text) { return analyze(text); }
            private Analysis analyze(String text) {
                var frequencies = new LinkedHashMap<String, Integer>();
                for (var term : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}-]+")) {
                    if (!term.isBlank()) frequencies.merge(term, 1, Integer::sum);
                }
                return new Analysis(frequencies,
                        frequencies.values().stream().mapToInt(Integer::intValue).sum());
            }
        };
    }
}
