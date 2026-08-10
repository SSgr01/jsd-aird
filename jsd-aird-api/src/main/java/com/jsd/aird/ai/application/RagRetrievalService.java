package com.jsd.aird.ai.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jsd.aird.ai.application.port.AssistantRepository;
import com.jsd.aird.ai.application.port.RerankerProvider;
import com.jsd.aird.data.api.DataAssetSearchFacade;
import com.jsd.aird.kb.api.KnowledgeScopeFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import org.springframework.stereotype.Service;

@Service
public class RagRetrievalService {

    private final KnowledgeSearchFacade knowledge;
    private final DataAssetSearchFacade dataAssets;
    private final KnowledgeScopeFacade scopes;
    private final QueryRewriteService rewrite;
    private final RerankerProvider reranker;

    public RagRetrievalService(KnowledgeSearchFacade knowledge, DataAssetSearchFacade dataAssets,
                               KnowledgeScopeFacade scopes, QueryRewriteService rewrite,
                               RerankerProvider reranker) {
        this.knowledge = knowledge;
        this.dataAssets = dataAssets;
        this.scopes = scopes;
        this.rewrite = rewrite;
        this.reranker = reranker;
    }

    public Retrieval retrieve(UUID organizationId, String question, List<AssistantRepository.MessageRow> history,
                             List<UUID> scopeIds, List<String> scopeTypes) {
        return retrieve(organizationId, question, history, scopeIds, scopeTypes, true);
    }

    public Retrieval retrieve(UUID organizationId, String question, List<AssistantRepository.MessageRow> history,
                              List<UUID> scopeIds, List<String> scopeTypes, boolean aiOnly) {
        var safeScopeIds = scopes.validate(organizationId, scopeIds, scopeTypes).stream().toList();
        var plan = rewrite.rewrite(question, history, scopeTypes == null ? List.of() : scopeTypes);
        var knowledgeResult = knowledge.search(new KnowledgeSearchFacade.SearchRequest(
                organizationId, plan.plan().rewrittenQuery(), aiOnly, 12, safeScopeIds, plan.plan().subQueries()));
        var data = safeScopeIds.isEmpty() ? List.<DataAssetSearchFacade.DataHit>of()
                : dataAssets.search(organizationId, plan.plan().rewrittenQuery(), safeScopeIds, 8);
        var knowledgeHits = rerank(plan.plan().rewrittenQuery(), knowledgeResult.hits());
        var fallbacks = new ArrayList<String>(knowledgeResult.trace().fallbacks());
        if ("MODEL_UNAVAILABLE".equals(plan.status()) || "FALLBACK_ORIGINAL_QUERY".equals(plan.status())) {
            fallbacks.add("QUERY_REWRITE_FALLBACK");
        }
        if (!reranker.isConfigured()) fallbacks.add("RERANKER_UNAVAILABLE");
        if (knowledgeHits.isEmpty() && data.isEmpty()) fallbacks.add("NO_RETRIEVAL_RESULT");
        return new Retrieval(plan.plan(), knowledgeHits, data, new Trace(
                plan.status(), knowledgeResult.trace().strategy(), knowledgeResult.trace().bm25Candidates(),
                knowledgeResult.trace().vectorCandidates(), knowledgeResult.trace().mergedCandidates(),
                data.size(), reranker.isConfigured() ? "CONFIGURED" : "RRF_FALLBACK", fallbacks));
    }

    private List<KnowledgeSearchFacade.SearchHit> rerank(String query, List<KnowledgeSearchFacade.SearchHit> hits) {
        if (hits == null || hits.isEmpty() || !reranker.isConfigured()) return hits == null ? List.of() : hits;
        var candidates = hits.stream().limit(30).map(hit -> new RerankerProvider.RankCandidate(
                hit.chunkId().toString(), hit.content(), hit.retrievalScore(), hit.rrfScore())).toList();
        var ranked = reranker.rerank(query, candidates);
        if (ranked.isEmpty()) return hits;
        var scores = ranked.stream().collect(java.util.stream.Collectors.toMap(RerankerProvider.RankedDocument::id,
                RerankerProvider.RankedDocument::score, (a, b) -> a));
        return hits.stream().filter(hit -> scores.containsKey(hit.chunkId().toString()))
                .sorted(Comparator.comparingDouble((KnowledgeSearchFacade.SearchHit hit) -> scores.get(hit.chunkId().toString())).reversed())
                .map(hit -> hit.withScores(hit.retrievalScore(), hit.rrfScore(), scores.get(hit.chunkId().toString())))
                .toList();
    }

    public record Retrieval(QueryRewriteService.QueryPlan plan, List<KnowledgeSearchFacade.SearchHit> knowledgeHits,
                            List<DataAssetSearchFacade.DataHit> dataHits, Trace trace) {
        public Retrieval {
            knowledgeHits = knowledgeHits == null ? List.of() : List.copyOf(knowledgeHits);
            dataHits = dataHits == null ? List.of() : List.copyOf(dataHits);
        }
    }

    public record Trace(String rewriteStatus, String strategy, int bm25Candidates, int vectorCandidates,
                        int mergedCandidates, int dataAssetCandidates, String rerankerStatus,
                        List<String> fallbacks) {
    }
}
