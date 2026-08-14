package com.jsd.aird.ai.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jsd.aird.ai.application.port.AssistantRepository;
import com.jsd.aird.ai.application.port.RerankerProvider;
import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeScopeFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import org.springframework.stereotype.Service;

@Service
public class RagRetrievalService {

    private final KnowledgeSearchFacade knowledge;
    private final DataSourceFileSearchFacade dataFiles;
    private final KnowledgeScopeFacade scopes;
    private final QueryRewriteService rewrite;
    private final RerankerProvider reranker;

    public RagRetrievalService(KnowledgeSearchFacade knowledge, DataSourceFileSearchFacade dataFiles,
                               KnowledgeScopeFacade scopes, QueryRewriteService rewrite,
                               RerankerProvider reranker) {
        this.knowledge = knowledge;
        this.dataFiles = dataFiles;
        this.scopes = scopes;
        this.rewrite = rewrite;
        this.reranker = reranker;
    }

    public Retrieval retrieve(UUID organizationId, String question, List<AssistantRepository.MessageRow> history,
                             List<UUID> scopeIds, List<String> scopeTypes) {
        return retrieve(organizationId, question, history, scopeIds, scopeTypes, List.of(), List.of(), true);
    }

    public Retrieval retrieve(UUID organizationId, String question, List<AssistantRepository.MessageRow> history,
                              List<UUID> scopeIds, List<String> scopeTypes, boolean aiOnly) {
        return retrieve(organizationId, question, history, scopeIds, scopeTypes, List.of(), List.of(), aiOnly);
    }

    public Retrieval retrieve(UUID organizationId, String question, List<AssistantRepository.MessageRow> history,
                              List<UUID> scopeIds, List<String> scopeTypes, List<UUID> knowledgeCategoryIds,
                              List<UUID> dataCategoryIds, boolean aiOnly) {
        var safeScopeIds = scopes.validate(organizationId, scopeIds, scopeTypes).stream().toList();
        var plan = rewrite.rewrite(question, history, scopeTypes == null ? List.of() : scopeTypes);
        var knowledgeResult = knowledge.search(new KnowledgeSearchFacade.SearchRequest(
                organizationId, plan.plan().rewrittenQuery(), aiOnly, 12, safeScopeIds, knowledgeCategoryIds,
                plan.plan().subQueries()));
        // Structured data indexes contain field values, not the document-oriented
        // expansion terms produced by the rewrite model. Searching them with the
        // rewritten query can turn an exact source-record lookup into an impossible
        // AND match (for example, code + unrelated manual/document terms).
        var dataQuery = question == null || question.isBlank() ? plan.plan().rewrittenQuery() : question.strip();
        // Data-center source files are part of the default retrieval corpus. Filters
        // narrow the result set when present; an empty filter must not disable
        // source-file retrieval altogether.
        var data = dataFiles.search(organizationId, dataQuery, dataCategoryIds, 8);
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
                            List<DataSourceFileSearchFacade.SourceFileHit> dataHits, Trace trace) {
        public Retrieval {
            knowledgeHits = knowledgeHits == null ? List.of() : List.copyOf(knowledgeHits);
            dataHits = dataHits == null ? List.of() : List.copyOf(dataHits);
        }
    }

    public record Trace(String rewriteStatus, String strategy, int bm25Candidates, int vectorCandidates,
                            int mergedCandidates, int dataFileCandidates, String rerankerStatus,
                        List<String> fallbacks) {
    }
}
