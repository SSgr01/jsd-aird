package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jsd.aird.ai.application.port.RerankerProvider;
import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import org.junit.jupiter.api.Test;

class RagRetrievalServiceTest {

    @Test
    void explicitEmptyScopesDoNotSearchAnySource() {
        var knowledge = mock(KnowledgeSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var rewrite = mock(QueryRewriteService.class);
        var reranker = mock(RerankerProvider.class);
        var plan = new QueryRewriteService.QueryPlan("question", "question", List.of(), List.of(),
                List.of(), Map.of(), "", false);
        when(rewrite.rewrite(eq("question"), anyList()))
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
                List.of(new QueryRewriteService.RequiredFact("requested fact", "fact query")), Map.of(), "", false);
        when(rewrite.rewrite(eq("original"), anyList()))
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

    private KnowledgeSearchFacade.SearchHit hit(int index) {
        return new KnowledgeSearchFacade.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "document", "document.pdf", 3, "section", "content " + index, 1d - index / 100d);
    }
}
