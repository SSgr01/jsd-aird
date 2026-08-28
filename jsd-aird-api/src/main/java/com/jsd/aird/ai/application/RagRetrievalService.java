package com.jsd.aird.ai.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jsd.aird.ai.application.port.AssistantRepository;
import com.jsd.aird.ai.application.port.RerankerProvider;
import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import org.springframework.stereotype.Service;

@Service
public class RagRetrievalService {

    private final KnowledgeSearchFacade knowledge;
    private final DataSourceFileSearchFacade dataFiles;
    private final QueryRewriteService rewrite;
    private final RerankerProvider reranker;

    public RagRetrievalService(KnowledgeSearchFacade knowledge, DataSourceFileSearchFacade dataFiles,
                               QueryRewriteService rewrite, RerankerProvider reranker) {
        this.knowledge = knowledge;
        this.dataFiles = dataFiles;
        this.rewrite = rewrite;
        this.reranker = reranker;
    }

    public Retrieval retrieve(UUID organizationId, String question, List<AssistantRepository.MessageRow> history,
                              List<UUID> knowledgeCategoryIds,
                              List<UUID> dataCategoryIds, boolean aiOnly) {
        var started = System.nanoTime();
        var timings = new LinkedHashMap<String, Long>();
        var stage = System.nanoTime();
        var plan = rewrite.rewrite(question, history);
        timings.put("queryRewriteMs", elapsedMs(stage));
        // The scope selectors are explicit module boundaries. When the user
        // selects a knowledge category, data-center rows must not be used as a
        // silent fallback (and vice versa), otherwise an unrelated file can be
        // presented as evidence for a knowledge-base question.
        var searchKnowledge = knowledgeCategoryIds != null && !knowledgeCategoryIds.isEmpty();
        var searchData = dataCategoryIds != null && !dataCategoryIds.isEmpty();
        var knowledgeFilters = knowledgeCategoryIds == null ? List.<UUID>of() : knowledgeCategoryIds;
        var dataFilters = dataCategoryIds == null ? List.<UUID>of() : dataCategoryIds;
        stage = System.nanoTime();
        var knowledgeResult = searchKnowledge
                ? knowledge.search(new KnowledgeSearchFacade.SearchRequest(
                        organizationId, plan.plan().rewrittenQuery(), aiOnly, 30, knowledgeFilters,
                        plan.plan().subQueries(), searchTerms(plan.plan()), requiredFacts(plan.plan()), question))
                : new KnowledgeSearchFacade.SearchResult(List.of(),
                        new KnowledgeSearchFacade.RetrievalTrace("SKIPPED", 0, 0, 0,
                                List.of("KNOWLEDGE_SCOPE_NOT_SELECTED")));
        timings.put("knowledgeSearchMs", elapsedMs(stage));
        // Structured data indexes contain field values, not the document-oriented
        // expansion terms produced by the rewrite model. Searching them with the
        // rewritten query can turn an exact source-record lookup into an impossible
        // AND match (for example, code + unrelated manual/document terms).
        // Structured rows must not be searched with one long natural-language
        // string. A question such as "比较 UA-2524 中 184、TPO、BP" contains
        // several independent lookup keys, and plainto_tsquery/ILIKE against the
        // whole sentence can miss every cell. Search the original question,
        // rewrite sub-queries and extracted keywords independently, then merge
        // the row/cell evidence by stable hit id.
        var dataByHit = new LinkedHashMap<UUID, DataSourceFileSearchFacade.SourceFileHit>();
        var dataQueries = searchData ? dataQueries(question, plan.plan()) : List.<String>of();
        stage = System.nanoTime();
        if (searchData) {
            for (var dataQuery : dataQueries) {
                dataFiles.search(organizationId, dataQuery, dataFilters, 8)
                        .forEach(hit -> dataByHit.putIfAbsent(hit.hitId(), hit));
            }
        }
        var data = dataByHit.values().stream()
                .sorted(Comparator.comparingDouble(DataSourceFileSearchFacade.SourceFileHit::score).reversed())
                .limit(16)
                .toList();
        timings.put("dataSearchMs", elapsedMs(stage));
        stage = System.nanoTime();
        var reranked = rerank(plan.plan().rewrittenQuery(), knowledgeResult.hits());
        var coverage = selectCoverage(reranked.hits(), knowledgeResult.factCandidates(), 12);
        var knowledgeHits = coverage.hits();
        timings.put("rerankMs", elapsedMs(stage));
        timings.put("retrievalTotalMs", elapsedMs(started));
        var fallbacks = new ArrayList<String>(knowledgeResult.trace().fallbacks());
        if ("MODEL_UNAVAILABLE".equals(plan.status()) || "FALLBACK_ORIGINAL_QUERY".equals(plan.status())) {
            fallbacks.add("QUERY_REWRITE_FALLBACK");
        }
        if (!"SUCCEEDED".equals(reranked.outcome().status())) fallbacks.add("RERANKER_" + reranked.outcome().status());
        if (knowledgeHits.isEmpty() && data.isEmpty()) fallbacks.add("NO_RETRIEVAL_RESULT");
        return new Retrieval(plan.plan(), knowledgeHits, data, new Trace(
                plan.status(), plan.model(), plan.thinkingEnabled(), knowledgeResult.trace().strategy(), knowledgeResult.trace().bm25Candidates(),
                knowledgeResult.trace().vectorCandidates(), knowledgeResult.trace().mergedCandidates(),
                data.size(), reranked.outcome().status(), fallbacks,
                dataQueries.size(), List.of(
                        new ChannelTrace("KNOWLEDGE_KEYWORD_VECTOR", searchKnowledge ? "SUCCEEDED" : "SKIPPED", knowledgeResult.trace().mergedCandidates()),
                        new ChannelTrace("DATA_CENTER_ROW", searchData ? (data.isEmpty() ? "EMPTY" : "SUCCEEDED") : "SKIPPED", data.size())),
                timings, rerankTrace(reranked.outcome()), coverage.coverage(), knowledgeResult.trace().queries()));
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private List<String> dataQueries(String question, QueryRewriteService.QueryPlan plan) {
        var queries = new LinkedHashSet<String>();
        if (question != null && !question.isBlank()) queries.add(question.strip());
        if (plan != null) {
            if (plan.rewrittenQuery() != null && !plan.rewrittenQuery().isBlank()) {
                queries.add(plan.rewrittenQuery().strip());
            }
            if (plan.subQueries() != null) plan.subQueries().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::strip).forEach(queries::add);
            if (plan.retrievalTerms() != null) plan.retrievalTerms().forEach(term -> {
                if (term != null && term.text() != null && !term.text().isBlank()) queries.add(term.text().strip());
                if (term != null && term.aliases() != null) term.aliases().stream()
                        .filter(value -> value != null && !value.isBlank()).map(String::strip).forEach(queries::add);
            });
        }
        return queries.stream().limit(20).toList();
    }

    private Reranked rerank(String query, List<KnowledgeSearchFacade.SearchHit> hits) {
        var safeHits = hits == null ? List.<KnowledgeSearchFacade.SearchHit>of() : hits;
        if (safeHits.isEmpty()) return new Reranked(List.of(), RerankerProvider.RerankOutcome.skipped("EMPTY_INPUT", 0));
        if (!reranker.isConfigured()) {
            return new Reranked(safeHits, RerankerProvider.RerankOutcome.skipped("NOT_CONFIGURED", safeHits.size()));
        }
        var candidates = safeHits.stream().limit(30).map(hit -> new RerankerProvider.RankCandidate(
                hit.chunkId().toString(), hit.content(), hit.retrievalScore(), hit.rrfScore())).toList();
        var outcome = reranker.rerank(query, candidates);
        if (outcome.documents().isEmpty()) return new Reranked(safeHits, outcome);
        var scores = outcome.documents().stream().collect(java.util.stream.Collectors.toMap(RerankerProvider.RankedDocument::id,
                RerankerProvider.RankedDocument::score, (a, b) -> a));
        var result = new ArrayList<KnowledgeSearchFacade.SearchHit>();
        safeHits.stream().filter(hit -> scores.containsKey(hit.chunkId().toString()))
                .sorted(Comparator.comparingDouble((KnowledgeSearchFacade.SearchHit hit) -> scores.get(hit.chunkId().toString())).reversed())
                .map(hit -> hit.withScores(hit.retrievalScore(), hit.rrfScore(), scores.get(hit.chunkId().toString())))
                .forEach(result::add);
        safeHits.stream().filter(hit -> !scores.containsKey(hit.chunkId().toString())).forEach(result::add);
        return new Reranked(List.copyOf(result), outcome);
    }

    private CoverageSelection selectCoverage(List<KnowledgeSearchFacade.SearchHit> ranked,
                                             List<KnowledgeSearchFacade.FactCandidateSet> facts, int limit) {
        var byId = ranked.stream().collect(java.util.stream.Collectors.toMap(
                KnowledgeSearchFacade.SearchHit::chunkId, value -> value, (left, right) -> left, LinkedHashMap::new));
        var selected = new LinkedHashMap<UUID, KnowledgeSearchFacade.SearchHit>();
        var traces = new ArrayList<FactCoverageTrace>();
        for (var fact : facts == null ? List.<KnowledgeSearchFacade.FactCandidateSet>of() : facts) {
            KnowledgeSearchFacade.SearchHit chosen = null;
            for (var chunkId : fact.chunkIds()) {
                if (byId.containsKey(chunkId)) { chosen = byId.get(chunkId); break; }
            }
            if (chosen != null) selected.putIfAbsent(chosen.chunkId(), chosen);
            traces.add(new FactCoverageTrace(fact.label(), fact.retrievalQuery(),
                    chosen == null ? null : chosen.chunkId(), chosen == null ? "MISSING" : "COVERED"));
        }
        for (var hit : ranked) {
            if (selected.size() >= limit) break;
            selected.putIfAbsent(hit.chunkId(), hit);
        }
        return new CoverageSelection(List.copyOf(selected.values()), List.copyOf(traces));
    }

    private List<KnowledgeSearchFacade.SearchTerm> searchTerms(QueryRewriteService.QueryPlan plan) {
        return plan.retrievalTerms().stream().map(value -> new KnowledgeSearchFacade.SearchTerm(
                value.text(), value.aliases(), value.kind())).toList();
    }

    private List<KnowledgeSearchFacade.RequiredFact> requiredFacts(QueryRewriteService.QueryPlan plan) {
        return plan.requiredFacts().stream().map(value -> new KnowledgeSearchFacade.RequiredFact(
                value.label(), value.retrievalQuery())).toList();
    }

    private RerankTrace rerankTrace(RerankerProvider.RerankOutcome value) {
        return new RerankTrace(value.status(), value.provider(), value.model(), value.httpStatus(), value.error(),
                value.elapsedMs(), value.inputCount(), value.outputCount());
    }

    public record Retrieval(QueryRewriteService.QueryPlan plan, List<KnowledgeSearchFacade.SearchHit> knowledgeHits,
                            List<DataSourceFileSearchFacade.SourceFileHit> dataHits, Trace trace) {
        public Retrieval {
            knowledgeHits = knowledgeHits == null ? List.of() : List.copyOf(knowledgeHits);
            dataHits = dataHits == null ? List.of() : List.copyOf(dataHits);
        }
    }

    public record Trace(String rewriteStatus, String rewriteModel, boolean rewriteThinkingEnabled,
                        String strategy, int bm25Candidates, int vectorCandidates,
                        int mergedCandidates, int dataFileCandidates, String rerankerStatus,
                         List<String> fallbacks, int dataQueryCount, List<ChannelTrace> channels,
                         Map<String, Long> timings, RerankTrace reranker,
                         List<FactCoverageTrace> factCoverage, List<KnowledgeSearchFacade.QueryTrace> queryTraces) {
        public Trace(String rewriteStatus, String strategy, int bm25Candidates, int vectorCandidates,
                     int mergedCandidates, int dataFileCandidates, String rerankerStatus,
                     List<String> fallbacks, int dataQueryCount, List<ChannelTrace> channels) {
            this(rewriteStatus, "", false, strategy, bm25Candidates, vectorCandidates, mergedCandidates, dataFileCandidates,
                    rerankerStatus, fallbacks, dataQueryCount, channels, Map.of(), null, List.of(), List.of());
        }
        public Trace {
            fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
            channels = channels == null ? List.of() : List.copyOf(channels);
            timings = timings == null ? Map.of() : Map.copyOf(timings);
            factCoverage = factCoverage == null ? List.of() : List.copyOf(factCoverage);
            queryTraces = queryTraces == null ? List.of() : List.copyOf(queryTraces);
        }
    }

    public record ChannelTrace(String channel, String status, int resultCount) {
    }

    public record RerankTrace(String status, String provider, String model, Integer httpStatus, String error,
                              long elapsedMs, int inputCount, int outputCount) { }

    public record FactCoverageTrace(String label, String retrievalQuery, UUID chunkId, String status) { }

    private record Reranked(List<KnowledgeSearchFacade.SearchHit> hits,
                            RerankerProvider.RerankOutcome outcome) { }

    private record CoverageSelection(List<KnowledgeSearchFacade.SearchHit> hits,
                                     List<FactCoverageTrace> coverage) { }
}
