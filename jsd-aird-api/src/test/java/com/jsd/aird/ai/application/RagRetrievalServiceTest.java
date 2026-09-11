package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.jsd.aird.ai.application.port.RerankerProvider;
import com.jsd.aird.ai.application.port.WebSearchProvider;
import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import org.junit.jupiter.api.Test;

class RagRetrievalServiceTest {

    @Test
    void infersRecordAndFieldFromChinesePossessiveLookup() {
        var inferred = RagRetrievalService.inferFieldLookup("SJ-230水洗后的粘度是多少？");

        assertThat(inferred.recordTerm()).isEqualTo("SJ-230水洗后");
        assertThat(inferred.fieldTerm()).isEqualTo("粘度");
    }

    @Test
    void ignoresNaturalLanguageAnalysisInstructionWhenInferringTheField() {
        var inferred = RagRetrievalService.inferFieldLookup("SJ-230水洗后的粘度是多少？请用自然语言简单分析");

        assertThat(inferred.recordTerm()).isEqualTo("SJ-230水洗后");
        assertThat(inferred.fieldTerm()).isEqualTo("粘度");
    }

    @Test
    void explicitEmptyScopesDoNotSearchAnySource() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var plan = new QueryRewriteService.QueryPlan("question", "question", List.of(), List.of(),
                List.of(), Map.of(), "", List.of());
        when(rewrite.rewrite(eq("question"), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "ORIGINAL", "", false));

        var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(UUID.randomUUID(), "question", List.of(), List.of(), List.of(), true);

        assertThat(retrieval.knowledgeHits()).isEmpty();
        assertThat(retrieval.dataHits()).isEmpty();
        verifyNoInteractions(knowledge, data);
    }

    @Test
    void reranksThirtyCandidatesAndCoverageCanRecoverCandidateFourteen() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var organizationId = UUID.randomUUID();
        var categoryId = UUID.randomUUID();
        var plan = new QueryRewriteService.QueryPlan("original", "rewritten", List.of("fact query"),
                List.of(new QueryRewriteService.RetrievalTerm("dynamic", List.of("alias"), "PHRASE")),
                List.of(new QueryRewriteService.RequiredFact("requested fact", "fact query")), Map.of(), "", List.of());
        when(rewrite.rewrite(eq("original"), anyList(), anyBoolean()))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));

        var hits = new ArrayList<KnowledgeSearchFacade.SearchHit>();
        for (var index = 0; index < 30; index++) hits.add(hit(index));
        var target = hits.get(13);
        var result = new KnowledgeSearchFacade.SearchResult(hits,
                new KnowledgeSearchFacade.RetrievalTrace("test", 30, 30, 30, List.of()),
                List.of(new KnowledgeSearchFacade.FactCandidateSet("requested fact", "fact query",
                        List.of(target.chunkId()))));
        when(knowledge.search(any(KnowledgeSearchFacade.SearchRequest.class))).thenReturn(result);

        when(reranker.isConfigured()).thenReturn(true);
        var ranked = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> new RerankerProvider.RankedDocument(hits.get(index).chunkId().toString(),
                        1d - index / 100d, index, "test", "test-model")).toList();
        when(reranker.rerank(eq("rewritten"), anyList())).thenReturn(new RerankerProvider.RerankOutcome(
                ranked, "SUCCEEDED", "test", "test-model", 200, "", 10, 30, 12));

        var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker)
                .retrieve(organizationId, "original", List.of(), List.of(categoryId), List.of(), true);

        assertThat(retrieval.knowledgeHits()).hasSize(12);
        assertThat(retrieval.knowledgeHits().getFirst().chunkId()).isEqualTo(target.chunkId());
        assertThat(retrieval.trace().factCoverage()).singleElement().satisfies(coverage -> {
            assertThat(coverage.status()).isEqualTo("COVERED");
            assertThat(coverage.chunkId()).isEqualTo(target.chunkId());
        });
    }

    @Test
    void knowledgeAndDataChannelsRunInParallelAndBothCompleteBeforeMerge() throws Exception {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var web = mock(WebSearchProvider.class);
        var knowledgeStarted = new CountDownLatch(1);
        var dataStarted = new CountDownLatch(1);
        var plan = new QueryRewriteService.QueryPlan("question", "question", List.of(), List.of(), List.of(),
                Map.of(), "", List.of());
        when(rewrite.rewrite(eq("question"), anyList(), eq(false)))
                .thenReturn(new QueryRewriteService.Result(plan, "ORIGINAL", "", false));
        when(knowledge.search(any(KnowledgeSearchFacade.SearchRequest.class))).thenAnswer(ignored -> {
            knowledgeStarted.countDown();
            assertThat(dataStarted.await(1, TimeUnit.SECONDS)).isTrue();
            return new KnowledgeSearchFacade.SearchResult(List.of(),
                    new KnowledgeSearchFacade.RetrievalTrace("test", 0, 0, 0, List.of()));
        });
        when(data.search(any(), any(), anyList(), any(DataSourceFileSearchFacade.AccessScope.class), anyInt()))
                .thenAnswer(ignored -> {
            dataStarted.countDown();
            assertThat(knowledgeStarted.await(1, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        when(reranker.isConfigured()).thenReturn(false);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker, web, Runnable::run,
                    executor, Duration.ofSeconds(1), 6).retrieve(UUID.randomUUID(), "question", List.of(),
                    List.of(UUID.randomUUID()), List.of(UUID.randomUUID()), true, false);

            assertThat(retrieval.trace().channels()).extracting(RagRetrievalService.ChannelTrace::status)
                    .contains("EMPTY");
            verify(knowledge).search(any(KnowledgeSearchFacade.SearchRequest.class));
            verify(data).search(any(), eq("question"), anyList(),
                    any(DataSourceFileSearchFacade.AccessScope.class), eq(10));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void webSearchRunsInParallelWithInternalRetrieval() throws Exception {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var web = mock(WebSearchProvider.class);
        var webStarted = new CountDownLatch(1);
        var internalFinished = new CountDownLatch(1);
        var categoryId = UUID.randomUUID();
        var plan = new QueryRewriteService.QueryPlan("A-186", "A-186", List.of(), List.of(), List.of(),
                Map.of(), "", List.of(new QueryRewriteService.WebQuery("A-186 silane", "GENERAL", "ALL")));
        when(rewrite.rewrite(eq("A-186"), anyList(), eq(true)))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        when(web.isAvailable()).thenReturn(true);
        when(web.search(any(WebSearchProvider.SearchQuery.class))).thenAnswer(invocation -> {
            webStarted.countDown();
            assertThat(internalFinished.await(1, TimeUnit.SECONDS)).isTrue();
            return new WebSearchProvider.SearchResponse("SUCCEEDED", List.of(
                    new WebSearchProvider.SearchResult("A-186", "https://example.com/a-186", "public evidence",
                            0.9, "2026-08-01")), 20, 10, 200, "");
        });
        when(knowledge.search(any(KnowledgeSearchFacade.SearchRequest.class))).thenAnswer(invocation -> {
            assertThat(webStarted.await(1, TimeUnit.SECONDS)).isTrue();
            internalFinished.countDown();
            return new KnowledgeSearchFacade.SearchResult(List.of(),
                    new KnowledgeSearchFacade.RetrievalTrace("test", 0, 0, 0, List.of()));
        });
        when(reranker.isConfigured()).thenReturn(false);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker, web, executor,
                    Duration.ofSeconds(2), 6).retrieve(UUID.randomUUID(), "A-186", List.of(),
                    List.of(categoryId), List.of(), true, true);

            assertThat(retrieval.webHits()).singleElement().satisfies(hit -> {
                assertThat(hit.url()).isEqualTo("https://example.com/a-186");
                assertThat(hit.content()).isEqualTo("public evidence");
            });
            assertThat(retrieval.trace().web().status()).isEqualTo("SUCCEEDED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void canonicalWebUrlRejectsPrivateAndUnsupportedTargets() {
        assertThat(RagRetrievalService.canonicalWebUrl("https://Example.com:443/path#part"))
                .isEqualTo("https://example.com/path");
        assertThat(RagRetrievalService.canonicalWebUrl("http://127.0.0.1/admin")).isEmpty();
        assertThat(RagRetrievalService.canonicalWebUrl("file:///etc/passwd")).isEmpty();
    }

    @Test
    void disabledWebSearchNeverTouchesTheProvider() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var web = mock(WebSearchProvider.class);
        var plan = new QueryRewriteService.QueryPlan("question", "question", List.of(), List.of(), List.of(),
                Map.of(), "", List.of());
        when(rewrite.rewrite(eq("question"), anyList(), eq(false)))
                .thenReturn(new QueryRewriteService.Result(plan, "ORIGINAL", "", false));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker, web, executor,
                    Duration.ofSeconds(1), 6).retrieve(UUID.randomUUID(), "question", List.of(),
                    List.of(), List.of(), true, false);

            assertThat(retrieval.webHits()).isEmpty();
            assertThat(retrieval.trace().web().status()).isEqualTo("SKIPPED");
            verifyNoInteractions(web);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void webEvidenceSurvivesAnInternalRetrievalFailure() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var web = mock(WebSearchProvider.class);
        var categoryId = UUID.randomUUID();
        var plan = new QueryRewriteService.QueryPlan("question", "question", List.of(), List.of(), List.of(),
                Map.of(), "", List.of(new QueryRewriteService.WebQuery("public question", "GENERAL", "ALL")));
        when(rewrite.rewrite(eq("question"), anyList(), eq(true)))
                .thenReturn(new QueryRewriteService.Result(plan, "MODEL", "fast", false));
        when(knowledge.search(any(KnowledgeSearchFacade.SearchRequest.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(web.isAvailable()).thenReturn(true);
        when(web.search(any(WebSearchProvider.SearchQuery.class))).thenReturn(new WebSearchProvider.SearchResponse(
                "SUCCEEDED", List.of(new WebSearchProvider.SearchResult("Public", "https://example.com/public",
                "external evidence", 0.8, "")), 10, 8, 200, ""));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var retrieval = new RagRetrievalService(knowledge, data, rewrite, reranker, web, executor,
                    Duration.ofSeconds(1), 6).retrieve(UUID.randomUUID(), "question", List.of(),
                    List.of(categoryId), List.of(), true, true);

            assertThat(retrieval.knowledgeHits()).isEmpty();
            assertThat(retrieval.webHits()).hasSize(1);
            assertThat(retrieval.trace().channels())
                    .filteredOn(channel -> "KNOWLEDGE_KEYWORD_VECTOR".equals(channel.channel()))
                    .singleElement().extracting(RagRetrievalService.ChannelTrace::status).isEqualTo("FAILED");
            verify(web).search(any(WebSearchProvider.SearchQuery.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private KnowledgeSearchFacade.SearchHit hit(int index) {
        return new KnowledgeSearchFacade.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "document", "document.pdf", 3, "section", "content " + index, 1d - index / 100d);
    }
}
