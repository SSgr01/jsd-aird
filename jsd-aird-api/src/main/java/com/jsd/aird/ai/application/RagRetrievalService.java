package com.jsd.aird.ai.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import com.jsd.aird.ai.application.port.AssistantRepository;
import com.jsd.aird.ai.application.port.RerankerProvider;
import com.jsd.aird.ai.application.port.WebSearchProvider;
import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);
    private static final int MAX_EXPERIMENT_CONTEXT_RECORDS = 12;
    private static final Pattern BUSINESS_RECORD_LABEL = Pattern.compile(
            "(?i)记录\\s*[：:=]\\s*([^；;]+)[；;]\\s*字段");

    private static final WebSearchProvider NO_WEB = new WebSearchProvider() {
        @Override public boolean isAvailable() { return false; }
        @Override public SearchResponse search(SearchQuery query) {
            return new SearchResponse("NOT_CONFIGURED", List.of(), 0, 0, null, "provider not configured");
        }
    };

    private final KnowledgeSearchFacade knowledge;
    private final DataSourceFileSearchFacade dataFiles;
    private final QueryRewriteService rewrite;
    private final RerankerProvider reranker;
    private final WebSearchProvider webSearch;
    private final Executor webSearchExecutor;
    private final Executor retrievalExecutor;
    private final Executor dataSearchExecutor;
    private final Duration webSearchTimeout;
    private final int maxWebCandidates;
    private final int maxDataHits;

    @Autowired
    public RagRetrievalService(KnowledgeSearchFacade knowledge, DataSourceFileSearchFacade dataFiles,
                               QueryRewriteService rewrite, RerankerProvider reranker,
                               WebSearchProvider webSearch,
                               @Qualifier("webSearchExecutor") Executor webSearchExecutor,
                               @Qualifier("ragRetrievalExecutor") Executor retrievalExecutor,
                               @Qualifier("dataSearchExecutor") Executor dataSearchExecutor,
                               @Value("${app.ai.tavily.timeout:5s}") Duration webSearchTimeout,
                               @Value("${app.ai.tavily.max-candidates:6}") int maxWebCandidates,
                               @Value("${app.ai.retrieval.data-max-hits:32}") int maxDataHits) {
        this.knowledge = knowledge;
        this.dataFiles = dataFiles;
        this.rewrite = rewrite;
        this.reranker = reranker;
        this.webSearch = webSearch;
        this.webSearchExecutor = webSearchExecutor;
        this.retrievalExecutor = retrievalExecutor;
        this.dataSearchExecutor = dataSearchExecutor;
        this.webSearchTimeout = webSearchTimeout == null || webSearchTimeout.isNegative() || webSearchTimeout.isZero()
                ? Duration.ofSeconds(5) : webSearchTimeout;
        this.maxWebCandidates = Math.max(1, Math.min(12, maxWebCandidates));
        this.maxDataHits = Math.max(1, Math.min(100, maxDataHits));
    }

    RagRetrievalService(KnowledgeSearchFacade knowledge, DataSourceFileSearchFacade dataFiles,
                        QueryRewriteService rewrite, RerankerProvider reranker) {
        this(knowledge, dataFiles, rewrite, reranker, NO_WEB, Runnable::run, Runnable::run, Runnable::run,
                Duration.ofSeconds(5), 6, 16);
    }

    RagRetrievalService(KnowledgeSearchFacade knowledge, DataSourceFileSearchFacade dataFiles,
                        QueryRewriteService rewrite, RerankerProvider reranker, WebSearchProvider webSearch,
                        Executor webSearchExecutor, Duration webSearchTimeout, int maxWebCandidates) {
        this(knowledge, dataFiles, rewrite, reranker, webSearch, webSearchExecutor, webSearchExecutor,
                Runnable::run,
                webSearchTimeout, maxWebCandidates, 16);
    }

    RagRetrievalService(KnowledgeSearchFacade knowledge, DataSourceFileSearchFacade dataFiles,
                        QueryRewriteService rewrite, RerankerProvider reranker, WebSearchProvider webSearch,
                        Executor webSearchExecutor, Executor retrievalExecutor, Duration webSearchTimeout,
                        int maxWebCandidates) {
        this(knowledge, dataFiles, rewrite, reranker, webSearch, webSearchExecutor, retrievalExecutor,
                Runnable::run,
                webSearchTimeout, maxWebCandidates, 16);
    }

    public boolean webSearchAvailable() {
        return webSearch.isAvailable();
    }

    public Retrieval retrieve(UUID organizationId, String question, List<AssistantRepository.MessageRow> history,
                              List<UUID> knowledgeCategoryIds,
                              List<UUID> dataCategoryIds, boolean aiOnly) {
        return retrieve(organizationId, question, history, knowledgeCategoryIds, dataCategoryIds, aiOnly, false);
    }

    public Retrieval retrieve(UUID organizationId, String question, List<AssistantRepository.MessageRow> history,
                              List<UUID> knowledgeCategoryIds,
                              List<UUID> dataCategoryIds, boolean aiOnly, boolean webSearchEnabled) {
        return retrieve(organizationId, question, history, knowledgeCategoryIds, dataCategoryIds, aiOnly,
                webSearchEnabled, DataSourceFileSearchFacade.AccessScope.all());
    }

    public Retrieval retrieve(UUID organizationId, String question, List<AssistantRepository.MessageRow> history,
                              List<UUID> knowledgeCategoryIds, List<UUID> dataCategoryIds, boolean aiOnly,
                              boolean webSearchEnabled, DataSourceFileSearchFacade.AccessScope dataAccessScope) {
        return retrieveInternal(organizationId, question, history, knowledgeCategoryIds, dataCategoryIds, aiOnly,
                webSearchEnabled, new DataSourceFileSearchFacade.RetrievalAccessScope(dataAccessScope, dataAccessScope),
                true);
    }

    public Retrieval retrieve(UUID organizationId, String question, List<AssistantRepository.MessageRow> history,
                              List<UUID> knowledgeCategoryIds, List<UUID> dataCategoryIds, boolean aiOnly,
                              boolean webSearchEnabled, DataSourceFileSearchFacade.RetrievalAccessScope accessScope) {
        return retrieveInternal(organizationId, question, history, knowledgeCategoryIds, dataCategoryIds, aiOnly,
                webSearchEnabled, accessScope == null ? DataSourceFileSearchFacade.RetrievalAccessScope.denied() : accessScope,
                false);
    }

    private Retrieval retrieveInternal(UUID organizationId, String question, List<AssistantRepository.MessageRow> history,
                              List<UUID> knowledgeCategoryIds, List<UUID> dataCategoryIds, boolean aiOnly,
                              boolean webSearchEnabled, DataSourceFileSearchFacade.RetrievalAccessScope accessScope,
                              boolean legacyFacadeCalls) {
        var started = System.nanoTime();
        var timings = new LinkedHashMap<String, Long>();
        var stage = System.nanoTime();
        var plan = rewrite.rewrite(question, history, webSearchEnabled);
        timings.put("queryRewriteMs", elapsedMs(stage));
        var explicitIdentifiers = explicitQueryIdentifiers(question, plan.plan());
        var webTasks = launchWebSearch(webSearchEnabled, plan.plan().webQueries());
        // The scope selectors are explicit module boundaries. When the user
        // selects a knowledge category, data-center rows must not be used as a
        // silent fallback (and vice versa), otherwise an unrelated file can be
        // presented as evidence for a knowledge-base question.
        var searchKnowledge = knowledgeCategoryIds != null && !knowledgeCategoryIds.isEmpty();
        var searchData = dataCategoryIds != null && !dataCategoryIds.isEmpty();
        var knowledgeFilters = knowledgeCategoryIds == null ? List.<UUID>of() : knowledgeCategoryIds;
        var dataFilters = dataCategoryIds == null ? List.<UUID>of() : dataCategoryIds;
        var dataQueries = searchData ? dataQueries(question, plan.plan()) : List.<String>of();
        var dataDetailQuery = dataDetailQuery(plan.plan());
        CompletableFuture<DataChannelResult> dataFuture;
        try {
            dataFuture = CompletableFuture.supplyAsync(
                    () -> searchDataChannel(organizationId, question, dataQueries, dataDetailQuery,
                            dataFilters, accessScope, searchData, legacyFacadeCalls),
                    retrievalExecutor);
        } catch (RejectedExecutionException exception) {
            dataFuture = CompletableFuture.completedFuture(new DataChannelResult(List.of(), dataQueries.size(),
                    dataQueries.size(), 0, searchData ? "FAILED" : "SKIPPED",
                    DataSourceFileSearchFacade.DataDetailResult.empty(dataDetailQuery.mode())));
        }
        var internalFallbacks = new ArrayList<String>();
        var knowledgeChannelStatus = searchKnowledge ? "SUCCEEDED" : "SKIPPED";
        stage = System.nanoTime();
        KnowledgeSearchFacade.SearchResult knowledgeResult;
        if (searchKnowledge) {
            try {
                knowledgeResult = knowledge.search(new KnowledgeSearchFacade.SearchRequest(
                        organizationId, plan.plan().rewrittenQuery(), aiOnly, 30, knowledgeFilters,
                        plan.plan().subQueries(), searchTerms(plan.plan()), requiredFacts(plan.plan()), question));
            } catch (RuntimeException exception) {
                log.warn("Knowledge retrieval failed; continuing without knowledge evidence errorType={}",
                        exception.getClass().getSimpleName());
                knowledgeChannelStatus = "FAILED";
                knowledgeResult = new KnowledgeSearchFacade.SearchResult(List.of(),
                        new KnowledgeSearchFacade.RetrievalTrace("FAILED", 0, 0, 0,
                                List.of("KNOWLEDGE_SEARCH_ERROR")));
            }
        } else {
            knowledgeResult = new KnowledgeSearchFacade.SearchResult(List.of(),
                        new KnowledgeSearchFacade.RetrievalTrace("SKIPPED", 0, 0, 0,
                                List.of("KNOWLEDGE_SCOPE_NOT_SELECTED")));
        }
        timings.putAll(knowledgeResult.trace().timings());
        timings.put("knowledgeSearchMs", elapsedMs(stage));
        // Search original wording, generic phrase segments and rewrite terms
        // independently. Exact record/field lookup is an optimization, not a
        // gate: if it returns no evidence, the general structured search still
        // gets a chance to match experiment/project metadata and row values.
        DataChannelResult dataResult;
        try {
            dataResult = dataFuture.join();
        } catch (RuntimeException exception) {
            dataResult = new DataChannelResult(List.of(), dataQueries.size(), dataQueries.size(), 0,
                    searchData ? "FAILED" : "SKIPPED",
                    DataSourceFileSearchFacade.DataDetailResult.empty(dataDetailQuery.mode()));
        }
        dataResult = scopeDataResultToExplicitIdentifiers(dataResult, explicitIdentifiers);
        var dataFailureCount = dataResult.failureCount();
        if (dataFailureCount > 0) internalFallbacks.add(dataFailureCount >= Math.max(1, dataResult.queryCount())
                ? "DATA_SEARCH_ERROR" : "DATA_SEARCH_PARTIAL");
        var data = dataResult.hits();
        timings.put("dataSearchMs", dataResult.elapsedMs());
        stage = System.nanoTime();
        Reranked reranked;
        var knowledgeCandidates = scopeKnowledgeHitsForStructuredLookup(
                knowledgeResult.hits(), explicitIdentifiers, dataDetailQuery.mode());
        try {
            reranked = rerank(plan.plan().rewrittenQuery(), knowledgeCandidates);
        } catch (RuntimeException exception) {
            internalFallbacks.add("RERANKER_PROVIDER_ERROR");
            reranked = new Reranked(knowledgeCandidates,
                    RerankerProvider.RerankOutcome.skipped("PROVIDER_ERROR", knowledgeCandidates.size()));
        }
        var coverage = selectCoverage(reranked.hits(), knowledgeResult.factCandidates(), 12);
        var knowledgeHits = coverage.hits();
        timings.put("rerankMs", elapsedMs(stage));
        var webResult = collectWebSearch(webTasks);
        timings.put("webSearchMs", webResult.trace().webSearchMs());
        timings.put("retrievalTotalMs", elapsedMs(started));
        var fallbacks = new ArrayList<String>(knowledgeResult.trace().fallbacks());
        fallbacks.addAll(internalFallbacks);
        if ("MODEL_UNAVAILABLE".equals(plan.status()) || "FALLBACK_ORIGINAL_QUERY".equals(plan.status())) {
            fallbacks.add("QUERY_REWRITE_FALLBACK");
        }
        if (!"SUCCEEDED".equals(reranked.outcome().status())) fallbacks.add("RERANKER_" + reranked.outcome().status());
        if (webResult.trace().failureReason() != null && !webResult.trace().failureReason().isBlank()) {
            fallbacks.add("WEB_SEARCH_" + webResult.trace().status());
        }
        if (knowledgeHits.isEmpty() && data.isEmpty() && webResult.hits().isEmpty()) fallbacks.add("NO_RETRIEVAL_RESULT");
        var allDataEvidence = new ArrayList<DataSourceFileSearchFacade.SourceFileHit>();
        var seenDataEvidence = new LinkedHashSet<UUID>();
        var allDataCandidates = new ArrayList<DataSourceFileSearchFacade.SourceFileHit>(data);
        allDataCandidates.addAll(dataResult.detail().hits());
        allDataCandidates.addAll(dataResult.detail().recordContexts().stream()
                .flatMap(context -> context.contextHits().stream()).toList());
        for (var hit : allDataCandidates) {
            if (hit.hitId() == null || seenDataEvidence.add(hit.hitId())) allDataEvidence.add(hit);
        }
        var dataCenterHitCount = (int) allDataEvidence.stream()
                .filter(hit -> "DATA_CENTER".equalsIgnoreCase(hit.sourceOwner())).count();
        var experimentHitCount = (int) allDataEvidence.stream()
                .filter(hit -> "EXPERIMENT".equalsIgnoreCase(hit.sourceOwner())).count();
        return new Retrieval(plan.plan(), knowledgeHits, data, dataResult.detail(), webResult.hits(), new Trace(
                plan.status(), plan.model(), plan.thinkingEnabled(), knowledgeResult.trace().strategy(), knowledgeResult.trace().bm25Candidates(),
                knowledgeResult.trace().vectorCandidates(), knowledgeResult.trace().mergedCandidates(),
                knowledgeResult.trace().variantCount(), data.size(), reranked.outcome().status(), fallbacks,
                dataResult.queryCount(), List.of(
                        new ChannelTrace("KNOWLEDGE_KEYWORD_VECTOR", knowledgeChannelStatus, knowledgeResult.trace().mergedCandidates()),
                        new ChannelTrace("STRUCTURED_DATA_ROW", dataResult.status(), allDataEvidence.size()),
                        new ChannelTrace("DATA_CENTER_ROW", scopedDataStatus(searchData,
                                accessScope.dataScope(), dataCenterHitCount, dataResult), dataCenterHitCount),
                        new ChannelTrace("EXPERIMENT_ROW", scopedDataStatus(searchData,
                                accessScope.experimentScope(), experimentHitCount, dataResult), experimentHitCount),
                        new ChannelTrace("WEB_PUBLIC", webResult.trace().status(), webResult.hits().size())),
                timings, rerankTrace(reranked.outcome()), coverage.coverage(), knowledgeResult.trace().queries(),
                webResult.trace()));
    }

    private DataChannelResult searchDataChannel(UUID organizationId, String question, List<String> queries,
                                                DataSourceFileSearchFacade.DataDetailQuery detailQuery,
                                                List<UUID> filters,
                                                DataSourceFileSearchFacade.RetrievalAccessScope accessScope,
                                                boolean enabled, boolean legacyFacadeCalls) {
        if (!enabled) return new DataChannelResult(List.of(), 0, 0, 0, "SKIPPED",
                DataSourceFileSearchFacade.DataDetailResult.empty(detailQuery.mode()));
        var started = System.nanoTime();
        var detail = DataSourceFileSearchFacade.DataDetailResult.empty(detailQuery.mode());
        var failures = 0;
        var queryCount = 0;
        if (detailQuery.mode() != DataSourceFileSearchFacade.DataQueryMode.NONE) {
            queryCount++;
            try {
                detail = legacyFacadeCalls
                        ? dataFiles.queryDetails(organizationId, detailQuery, filters, accessScope.dataScope())
                        : dataFiles.queryDetails(organizationId, detailQuery, filters, accessScope);
            } catch (RuntimeException exception) {
                log.warn("assistant_data_detail_lookup_failed organizationId={} mode={} fileName={} fileScope={} recordTerms={} fieldTerms={} categoryIds={} exceptionType={} detail={}",
                        organizationId, detailQuery.mode(), detailQuery.fileName(), detailQuery.fileScope(),
                        detailQuery.recordTerms(), detailQuery.fieldTerms(), filters,
                        exception.getClass().getSimpleName(), rootCauseText(exception));
                failures++;
                detail = DataSourceFileSearchFacade.DataDetailResult.empty(detailQuery.mode());
            }
            if (!detail.hits().isEmpty()) {
                return new DataChannelResult(detail.hits(), failures, queryCount, elapsedMs(started),
                        failures == 0 ? "SUCCEEDED" : "PARTIAL", detail);
            }
            if (detail.requiresFileSelection()) {
                if (preserveExplicitFileChoice(detailQuery)) {
                    return new DataChannelResult(List.of(), failures, queryCount, elapsedMs(started),
                            "AMBIGUOUS", detail);
                }
                // A broad project/topic query can match several source files.
                // Keep searching the authorized experiment/data scope so the
                // model can synthesize relevant evidence instead of forcing a
                // file picker. Explicit file and coded-record lookups retain
                // the existing ambiguity prompt.
                detail = DataSourceFileSearchFacade.DataDetailResult.empty(detailQuery.mode());
            }
        }
        var byHit = new LinkedHashMap<UUID, DataHitAccumulator>();
        var searchTasks = new ArrayList<CompletableFuture<DataSearchResponse>>();
        for (var query : queries) {
            queryCount++;
            try {
                searchTasks.add(CompletableFuture.supplyAsync(() -> {
                    var queryStarted = System.nanoTime();
                    try {
                        var found = legacyFacadeCalls
                                ? dataFiles.search(organizationId, query, filters, accessScope.dataScope(),
                                Math.max(1, Math.min(10, maxDataHits)))
                                : dataFiles.search(organizationId, query, filters, accessScope,
                                Math.max(1, Math.min(10, maxDataHits)));
                        return new DataSearchResponse(query, found, null, elapsedMs(queryStarted));
                    } catch (RuntimeException exception) {
                        return new DataSearchResponse(query, List.of(), exception, elapsedMs(queryStarted));
                    }
                }, dataSearchExecutor));
            } catch (RuntimeException exception) {
                failures++;
                log.warn("assistant_data_row_lookup_failed organizationId={} query={} categoryIds={} exceptionType={} detail={}",
                        organizationId, query, filters, exception.getClass().getSimpleName(), rootCauseText(exception));
            }
        }
        var searchElapsedMs = 0L;
        var maxSearchElapsedMs = 0L;
        for (var searchTask : searchTasks) {
            try {
                var response = searchTask.join();
                searchElapsedMs += response.elapsedMs();
                maxSearchElapsedMs = Math.max(maxSearchElapsedMs, response.elapsedMs());
                if (response.error() != null) {
                    failures++;
                    log.warn("assistant_data_row_lookup_failed organizationId={} query={} categoryIds={} exceptionType={} detail={}",
                            organizationId, response.query(), filters, response.error().getClass().getSimpleName(),
                            rootCauseText(response.error()));
                    continue;
                }
                response.hits().forEach(hit -> byHit.computeIfAbsent(hit.hitId(),
                        ignored -> new DataHitAccumulator(hit)).add(hit));
            } catch (RuntimeException exception) {
                failures++;
                log.warn("assistant_data_row_lookup_failed organizationId={} categoryIds={} exceptionType={} detail={}",
                        organizationId, filters, exception.getClass().getSimpleName(), rootCauseText(exception));
            }
        }
        if (!searchTasks.isEmpty()) {
            log.info("assistant_data_row_search_completed organizationId={} queryCount={} summedQueryMs={} maxQueryMs={}",
                    organizationId, searchTasks.size(), searchElapsedMs, maxSearchElapsedMs);
        }
        var matchedHits = byHit.values().stream().map(DataHitAccumulator::result).toList();
        var experimentSeeds = selectExperimentSeeds(question, matchedHits);
        if (!experimentSeeds.isEmpty()) {
            queryCount++;
            try {
                var contextHits = dataFiles.expandExperimentContexts(organizationId, experimentSeeds, queries,
                        accessScope);
                for (var hit : contextHits) {
                    byHit.computeIfAbsent(hit.hitId(), ignored -> new DataHitAccumulator(hit)).add(hit);
                }
                var formulaRows = contextHits.stream()
                        .filter(hit -> "FORMULA".equalsIgnoreCase(hit.semanticDomain())).count();
                var totalRows = contextHits.stream().filter(RagRetrievalService::isFormulaTotalHit).count();
                log.info("assistant_experiment_context_expanded organizationId={} seedHits={} contextHits={} formulaRows={} totalRows={}",
                        organizationId, experimentSeeds.size(), contextHits.size(), formulaRows, totalRows);
            } catch (RuntimeException exception) {
                failures++;
                log.warn("assistant_experiment_context_expansion_failed organizationId={} seedHits={} exceptionType={} detail={}",
                        organizationId, experimentSeeds.size(), exception.getClass().getSimpleName(), rootCauseText(exception));
            }
        }
        var hits = prioritizeFormulaEvidence(question,
                byHit.values().stream().map(DataHitAccumulator::result).toList(), maxDataHits);
        var status = dataChannelStatus(true, hits, failures, queryCount);
        return new DataChannelResult(hits, failures, queryCount, elapsedMs(started), status, detail);
    }

    static List<DataSourceFileSearchFacade.SourceFileHit> selectExperimentSeeds(
            String question, List<DataSourceFileSearchFacade.SourceFileHit> matchedHits) {
        if (matchedHits == null || matchedHits.isEmpty()) return List.of();
        var compositionRequest = isCompositionQuestion(question);
        var explicitlyMatchedHits = explicitlyMatchedRecordHits(question, matchedHits);
        var seedCandidates = explicitlyMatchedHits.isEmpty() ? matchedHits : explicitlyMatchedHits;
        var selectedExperimentRecords = new LinkedHashSet<String>();
        return seedCandidates.stream()
                .filter(hit -> "EXPERIMENT".equalsIgnoreCase(hit.sourceOwner()))
                .filter(hit -> hit.importJobId() != null)
                .filter(hit -> hit.recordKey() != null && !hit.recordKey().isBlank())
                .sorted(Comparator.<DataSourceFileSearchFacade.SourceFileHit>comparingInt(hit ->
                                compositionRequest && isFormulaEvidence(hit) ? 0 : 1)
                        .thenComparing(Comparator.comparingDouble(
                                DataSourceFileSearchFacade.SourceFileHit::score).reversed()))
                // Search terms can produce hundreds of cell hits for the same
                // few experiments. Expanding all of them adds latency and floods
                // the answer context with duplicate records. Expand only the top
                // distinct records; every selected record still brings back its
                // sibling formula and test fields with source anchors.
                .filter(hit -> selectedExperimentRecords.add(hit.importJobId() + "\u0000" + hit.recordKey()))
                .limit(MAX_EXPERIMENT_CONTEXT_RECORDS)
                .toList();
    }

    static List<DataSourceFileSearchFacade.SourceFileHit> prioritizeFormulaEvidence(
            String question, List<DataSourceFileSearchFacade.SourceFileHit> hits, int limit) {
        if (hits == null || hits.isEmpty() || limit <= 0) return List.of();
        var explicitlyMatchedHits = explicitlyMatchedRecordHits(question, hits);
        var scopedHits = explicitlyMatchedHits.isEmpty() ? hits : explicitlyMatchedHits;
        var byScore = Comparator.comparingDouble(DataSourceFileSearchFacade.SourceFileHit::score).reversed();
        if (!isCompositionQuestion(question)) {
            return scopedHits.stream().sorted(Comparator
                    .comparingInt((DataSourceFileSearchFacade.SourceFileHit hit) -> requestedFieldRelevance(question, hit))
                    .reversed().thenComparing(byScore)).limit(limit).toList();
        }
        var groups = new LinkedHashMap<String, List<DataSourceFileSearchFacade.SourceFileHit>>();
        for (var hit : scopedHits) {
            if (hit == null) continue;
            var key = String.join("|", safeText(hit.fileObjectId() == null ? null : hit.fileObjectId().toString()),
                    safeText(hit.importJobId() == null ? null : hit.importJobId().toString()),
                    businessRecordGroupKey(hit), safeText(hit.sheetName()));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(hit);
        }
        var orderedGroups = groups.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<DataSourceFileSearchFacade.SourceFileHit>>>comparingInt(entry ->
                                entry.getValue().stream().mapToInt(hit -> requestedFieldRelevance(question, hit)).max().orElse(0))
                        .reversed().thenComparing(Comparator
                                .<Map.Entry<String, List<DataSourceFileSearchFacade.SourceFileHit>>>comparingDouble(entry ->
                                        entry.getValue().stream().mapToDouble(
                                                DataSourceFileSearchFacade.SourceFileHit::score).max().orElse(0d))
                                .reversed()))
                .toList();
        var prioritized = new ArrayList<DataSourceFileSearchFacade.SourceFileHit>();
        var selectedFormulaGroups = new LinkedHashSet<String>();
        for (var entry : orderedGroups) {
            var formulaRows = entry.getValue().stream().filter(RagRetrievalService::isFormulaEvidence)
                    .sorted(byScore).toList();
            if (formulaRows.isEmpty() || formulaRows.size() > limit - prioritized.size()) continue;
            prioritized.addAll(formulaRows);
            selectedFormulaGroups.add(entry.getKey());
        }
        for (var entry : orderedGroups) {
            var groupHasFormula = entry.getValue().stream().anyMatch(RagRetrievalService::isFormulaEvidence);
            if (groupHasFormula && !selectedFormulaGroups.contains(entry.getKey())) continue;
            entry.getValue().stream().filter(hit -> !isFormulaEvidence(hit))
                    .sorted(Comparator.comparingInt((DataSourceFileSearchFacade.SourceFileHit hit) ->
                                    requestedFieldRelevance(question, hit)).reversed().thenComparing(byScore))
                    .forEach(hit -> {
                        if (prioritized.size() < limit) prioritized.add(hit);
                    });
        }
        return List.copyOf(prioritized);
    }

    private static List<DataSourceFileSearchFacade.SourceFileHit> explicitlyMatchedRecordHits(
            String question, List<DataSourceFileSearchFacade.SourceFileHit> hits) {
        var identifiers = explicitQueryIdentifiers(question, null);
        if (identifiers.isEmpty()) return List.of();
        return hits.stream().filter(hit -> containsExplicitIdentifier(identifiers, sourceHitText(hit))).toList();
    }

    private static List<KnowledgeSearchFacade.SearchHit> scopeKnowledgeHitsForStructuredLookup(
            List<KnowledgeSearchFacade.SearchHit> hits, List<String> identifiers,
            DataSourceFileSearchFacade.DataQueryMode mode) {
        if (hits == null || hits.isEmpty() || identifiers == null || identifiers.isEmpty()
                || mode == null || mode == DataSourceFileSearchFacade.DataQueryMode.NONE) {
            return hits == null ? List.of() : hits;
        }
        return hits.stream().filter(hit -> containsExplicitIdentifier(identifiers,
                String.join(" ", safeText(hit.title()), safeText(hit.originalName()), safeText(hit.section()),
                        safeText(hit.fieldCode()), safeText(hit.content())))).toList();
    }

    private static int requestedFieldRelevance(String question,
                                               DataSourceFileSearchFacade.SourceFileHit hit) {
        if (question == null || question.isBlank() || hit == null) return 0;
        var normalizedQuestion = stripDataFileReferences(question)
                .replaceAll("(?i)(?:please|查询|查找|检索|查看|分析|请问|请|业务记录|实验记录|实验数据|测试数据|完整|分别|对应|其中|这条|该条|记录|数据|结果|是什么|是多少|有哪些|有什么|哪些|什么|多少)", " ")
                .replaceAll("[的与和及或在中对上、，。！？!?；;：:（）()\\s]+", " ")
                .toLowerCase(Locale.ROOT);
        var fieldText = String.join(" ", safeText(hit.fieldName()), safeText(hit.sourceLabel()),
                        safeText(hit.semanticField()))
                .replaceAll("[\\s、，。！？!?；;：:（）()]+", "")
                .toLowerCase(Locale.ROOT);
        var matcher = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._/-]*|[\\u4E00-\\u9FFF]{2,}")
                .matcher(normalizedQuestion);
        var score = 0;
        while (matcher.find()) {
            var term = matcher.group();
            if (term.length() < 2 || term.matches("\\d+")) continue;
            if (fieldText.contains(term)) {
                score += Math.min(12, term.length());
                continue;
            }
            if (term.matches(".*[\\u4E00-\\u9FFF].*")) {
                for (var index = 0; index + 2 <= term.length(); index++) {
                    var pair = term.substring(index, index + 2);
                    if (pair.matches("[\\u4E00-\\u9FFF]{2}") && fieldText.contains(pair)) score += 2;
                }
            }
        }
        return score;
    }

    private static boolean isFormulaEvidence(DataSourceFileSearchFacade.SourceFileHit hit) {
        if (hit == null) return false;
        if ("FORMULA".equalsIgnoreCase(hit.semanticDomain())) return true;
        return isFormulaTotalHit(hit);
    }

    private static String businessRecordGroupKey(DataSourceFileSearchFacade.SourceFileHit hit) {
        var matcher = BUSINESS_RECORD_LABEL.matcher(hit.content() == null ? "" : hit.content());
        return matcher.find() ? matcher.group(1).strip() : safeText(hit.recordKey());
    }

    private static boolean isFormulaTotalHit(DataSourceFileSearchFacade.SourceFileHit hit) {
        if (hit == null) return false;
        var label = (safeText(hit.sourceLabel()) + " " + safeText(hit.fieldName()) + " "
                + safeText(hit.semanticField())).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return label.matches(".*(合计|总计|小计|总和|total|subtotal|sum).*");
    }

    private static boolean isCompositionQuestion(String question) {
        return question != null && question.matches("(?s).*(配方|组分|成分|成份|组成|合计|总计|添加量|用量|formula|composition).*");
    }

    private String scopedDataStatus(boolean enabled, DataSourceFileSearchFacade.AccessScope scope,
                                    int hitCount, DataChannelResult result) {
        if (!enabled || scope == null || scope.deniesAll()) return "SKIPPED";
        if ("AMBIGUOUS".equals(result.status())) return "AMBIGUOUS";
        if (result.failureCount() > 0 && result.failureCount() >= Math.max(1, result.queryCount())) {
            return hitCount == 0 ? "FAILED" : "PARTIAL";
        }
        if (result.failureCount() > 0 && hitCount == 0) return "PARTIAL";
        return hitCount > 0 ? "SUCCEEDED" : "EMPTY";
    }

    private static boolean preserveExplicitFileChoice(DataSourceFileSearchFacade.DataDetailQuery query) {
        if (query.fileScope() != DataSourceFileSearchFacade.DataFileScope.AUTO || !query.fileName().isBlank()) {
            return true;
        }
        return query.recordTerms().stream().anyMatch(term -> term != null && term.matches("(?s).*\\d+.*"));
    }

    /**
     * Explicit record/sample identifiers in the question are hard evidence boundaries.
     * Semantic matches can find related material, but they must not let a different
     * record be presented as the requested one when the identifier itself did not match.
     */
    static List<String> explicitQueryIdentifiers(String question, QueryRewriteService.QueryPlan plan) {
        if (question == null || question.isBlank()) return List.of();
        var identifiers = new LinkedHashSet<String>();
        var code = java.util.regex.Pattern.compile(
                "(?i)(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9]*(?:[-_/][A-Za-z0-9]+)*(?![A-Za-z0-9])")
                .matcher(stripDataFileReferences(question));
        while (code.find()) addIdentifier(identifiers, code.group());
        var labeled = java.util.regex.Pattern.compile(
                "(?:配方|树脂|样品|试样|实验|记录)[\\u4E00-\\u9FFF]*(?:[-_:#\\s]*)[A-Za-z0-9][A-Za-z0-9._/-]{0,120}")
                .matcher(stripDataFileReferences(question));
        while (labeled.find()) addIdentifier(identifiers, labeled.group());

        var range = java.util.regex.Pattern.compile(
                "(?i)([A-Za-z][A-Za-z0-9_-]*?)(\\d+)\\s*(?:至|到|through|to|~|～)\\s*([A-Za-z][A-Za-z0-9_-]*?)(\\d+)")
                .matcher(question);
        while (range.find()) {
            var leftPrefix = range.group(1);
            var rightPrefix = range.group(3);
            var first = range.group(2);
            var last = range.group(4);
            if (!leftPrefix.equalsIgnoreCase(rightPrefix) || first.length() != last.length()) continue;
            try {
                var from = Integer.parseInt(first);
                var to = Integer.parseInt(last);
                if (from > to || to - from > 99) continue;
                for (var value = from; value <= to; value++) {
                    addIdentifier(identifiers, leftPrefix + String.format(Locale.ROOT, "%0" + first.length() + "d", value));
                }
            } catch (NumberFormatException ignored) {
                // Keep the explicit endpoints parsed by the ordinary identifier scanner.
            }
        }
        if (plan != null && plan.dataRequest() != null) {
            plan.dataRequest().recordTerms().stream().filter(RagRetrievalService::looksLikeExplicitIdentifier)
                    .forEach(value -> addIdentifier(identifiers, value));
        }
        return identifiers.stream().limit(64).toList();
    }

    private static void addIdentifier(Set<String> identifiers, String value) {
        if (looksLikeExplicitIdentifier(value)) identifiers.add(value.strip());
    }

    private static boolean looksLikeExplicitIdentifier(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || !value.matches("(?s).*\\d.*")) return false;
        var letters = value.replaceAll("[^A-Za-z\\u4E00-\\u9FFF]", "");
        return letters.length() >= 1 && value.replaceAll("[^A-Za-z0-9\\u4E00-\\u9FFF]", "").length() >= 4;
    }

    private static DataChannelResult scopeDataResultToExplicitIdentifiers(DataChannelResult result,
                                                                           List<String> identifiers) {
        if (result == null || identifiers == null || identifiers.isEmpty()) return result;
        var detail = result.detail() == null
                ? DataSourceFileSearchFacade.DataDetailResult.empty(DataSourceFileSearchFacade.DataQueryMode.NONE)
                : result.detail();
        var allHits = new ArrayList<DataSourceFileSearchFacade.SourceFileHit>(result.hits());
        allHits.addAll(detail.hits());
        detail.recordContexts().forEach(context -> {
            allHits.addAll(context.targetHits());
            allHits.addAll(context.contextHits());
        });
        var matchingGroups = new LinkedHashSet<String>();
        var matchingHitIds = new LinkedHashSet<UUID>();
        for (var hit : allHits) {
            if (containsExplicitIdentifier(identifiers, sourceHitText(hit))) {
                var group = sourceRecordGroup(hit);
                if (!group.isBlank()) matchingGroups.add(group);
                if (hit.hitId() != null) matchingHitIds.add(hit.hitId());
            }
        }
        if (matchingGroups.isEmpty() && matchingHitIds.isEmpty()) {
            if (detail.requiresFileSelection()) return result;
            var emptyDetail = new DataSourceFileSearchFacade.DataDetailResult(detail.mode(),
                    detail.mode() == DataSourceFileSearchFacade.DataQueryMode.NONE
                            ? DataSourceFileSearchFacade.DataResolution.NOT_REQUESTED
                            : DataSourceFileSearchFacade.DataResolution.NOT_FOUND,
                    null, null, "", detail.sourceRecordCount(), detail.searchableValueCount(), List.of(),
                    detail.truncated(), List.of(), false, List.of(), detail.analysisGoal());
            return new DataChannelResult(List.of(), result.failureCount(), result.queryCount(), result.elapsedMs(),
                    result.failureCount() == 0 ? "EMPTY" : "PARTIAL", emptyDetail);
        }
        java.util.function.Predicate<DataSourceFileSearchFacade.SourceFileHit> selected = hit -> {
            if (hit == null) return false;
            if (hit.hitId() != null && matchingHitIds.contains(hit.hitId())) return true;
            var group = sourceRecordGroup(hit);
            return !group.isBlank() && matchingGroups.contains(group);
        };
        var scopedHits = result.hits().stream().filter(selected).toList();
        var scopedDetailHits = detail.hits().stream().filter(selected).toList();
        var scopedContexts = detail.recordContexts().stream().filter(context -> {
            var group = contextRecordGroup(context);
            return (!group.isBlank() && matchingGroups.contains(group))
                    || java.util.stream.Stream.concat(context.targetHits().stream(), context.contextHits().stream())
                    .anyMatch(selected);
        }).toList();
        var scopedDetail = new DataSourceFileSearchFacade.DataDetailResult(detail.mode(), detail.resolution(),
                detail.fileObjectId(), detail.importJobId(), detail.originalName(), detail.sourceRecordCount(),
                detail.searchableValueCount(), scopedDetailHits, detail.truncated(), detail.candidates(),
                detail.candidatesTruncated(), scopedContexts, detail.analysisGoal());
        var hasEvidence = !scopedHits.isEmpty() || !scopedDetailHits.isEmpty() || !scopedContexts.isEmpty();
        return new DataChannelResult(scopedHits, result.failureCount(), result.queryCount(), result.elapsedMs(),
                hasEvidence ? result.status() : (result.failureCount() == 0 ? "EMPTY" : "PARTIAL"), scopedDetail);
    }

    private static String sourceHitText(DataSourceFileSearchFacade.SourceFileHit hit) {
        if (hit == null) return "";
        return String.join(" ", safeText(hit.originalName()), safeText(hit.content()), safeText(hit.recordKey()),
                safeText(hit.fieldCode()), safeText(hit.fieldName()), safeText(hit.fieldValue()),
                safeText(hit.sourceLabel()), safeText(hit.semanticField()));
    }

    private static String sourceRecordGroup(DataSourceFileSearchFacade.SourceFileHit hit) {
        if (hit == null || hit.importJobId() == null || hit.recordKey() == null || hit.recordKey().isBlank()) return "";
        return hit.importJobId() + "|" + hit.recordKey().strip();
    }

    private static String contextRecordGroup(DataSourceFileSearchFacade.RecordContext context) {
        if (context == null || context.recordKey().isBlank()) return "";
        var hit = java.util.stream.Stream.concat(context.targetHits().stream(), context.contextHits().stream())
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return hit == null || hit.importJobId() == null ? "" : hit.importJobId() + "|" + context.recordKey().strip();
    }

    static boolean containsExplicitIdentifier(List<String> identifiers, String text) {
        if (identifiers == null || identifiers.isEmpty() || text == null || text.isBlank()) return false;
        for (var identifier : identifiers) {
            var compact = identifier.replaceAll("[^A-Za-z0-9\\u4E00-\\u9FFF]", "");
            if (compact.length() < 4) continue;
            var regex = new StringBuilder("(?i)(?<![A-Za-z0-9])");
            for (var index = 0; index < compact.length(); index++) {
                if (index > 0) regex.append("[^A-Za-z0-9]*");
                regex.append(java.util.regex.Pattern.quote(compact.substring(index, index + 1)));
            }
            regex.append("(?![A-Za-z0-9])");
            if (java.util.regex.Pattern.compile(regex.toString()).matcher(text).find()) return true;
        }
        return false;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private DataSourceFileSearchFacade.DataDetailQuery dataDetailQuery(QueryRewriteService.QueryPlan plan) {
        var value = plan == null ? null : plan.dataRequest();
        var request = value == null ? new QueryRewriteService.DataRequest("NONE", "", List.of(), List.of()) : value;
        var originalQuestion = plan == null ? "" : plan.originalQuery();
        var analysisGoal = request.analysisGoal();
        var comparisonQuestion = "COMPARE".equals(analysisGoal) || isComparisonQuestion(originalQuestion);
        DataSourceFileSearchFacade.DataQueryMode mode;
        try {
            mode = DataSourceFileSearchFacade.DataQueryMode.valueOf(request.intent());
        } catch (IllegalArgumentException exception) {
            mode = DataSourceFileSearchFacade.DataQueryMode.NONE;
        }
        var recordTerms = new LinkedHashSet<>(request.recordTerms());
        var fieldTerms = new LinkedHashSet<>(request.fieldTerms());
        var structuredQuestion = stripDataFileReferences(originalQuestion);
        var explicitQuestionTerms = extractStructuredQueryTerms(structuredQuestion).entities();
        var questionHasExplicitIdentifier = explicitQuestionTerms.stream()
                .anyMatch(term -> looksLikeExplicitIdentifier(term) && !isMeasurementValueToken(term));
        if (comparisonQuestion && explicitQuestionTerms.size() > 1) {
            // Prefer the independent opaque identifiers found in the original
            // question. A rewrite model can otherwise return one aggregate term
            // such as "比较 APP-001 和 APP-002", which cannot match any record.
            recordTerms.clear();
            explicitQuestionTerms.stream().filter(term -> isStrongExplicitRecordTerm(
                            term, structuredQuestion, questionHasExplicitIdentifier))
                    .forEach(recordTerms::add);
        } else if (!explicitQuestionTerms.isEmpty()) {
            // Prefer identifiers and numeric lookup values explicitly present in
            // the current question. A rewrite model may wrap a value in a broad
            // phrase such as "粘度/黏度 887.4"; keeping the explicit token 887.4
            // makes reverse lookup work without trusting the broad phrase as a
            // record identifier.
            recordTerms.clear();
            explicitQuestionTerms.stream().filter(term -> isStrongExplicitRecordTerm(
                            term, structuredQuestion, questionHasExplicitIdentifier))
                    .forEach(recordTerms::add);
        } else {
            extractStructuredQueryTerms(structuredQuestion,
                    removeFileNameFragments(request.recordTerms(), request.fileName())).entities()
                    .forEach(recordTerms::add);
        }
        if (mode == DataSourceFileSearchFacade.DataQueryMode.FIELD_LOOKUP) {
            var inferred = inferFieldLookup(structuredQuestion);
            if (!comparisonQuestion && isStrongExplicitRecordTerm(inferred.recordTerm(), structuredQuestion,
                    questionHasExplicitIdentifier)) recordTerms.add(inferred.recordTerm());
            if (usableInferredField(inferred.fieldTerm())) fieldTerms.add(inferred.fieldTerm());
            inferRequestedFields(originalQuestion).forEach(fieldTerms::add);
        } else if (mode == DataSourceFileSearchFacade.DataQueryMode.RECORD_DETAIL) {
            var recordTerm = inferRecordLookup(structuredQuestion);
            if (isStrongExplicitRecordTerm(recordTerm, structuredQuestion, questionHasExplicitIdentifier)) {
                recordTerms.add(recordTerm);
            }
        }
        recordTerms = new LinkedHashSet<>(canonicalRecordTerms(recordTerms));
        recordTerms = new LinkedHashSet<>(longestSpecificTerms(recordTerms));
        // Apply deterministic field aliases for every detail mode. In
        // particular, a planner may emit the broad literal "粘度/黏度" while
        // the stored field is simply "粘度"; RECORD_DETAIL used to skip this
        // canonical field inference and then resolve a file with zero hits.
        inferRequestedFields(originalQuestion).forEach(fieldTerms::add);
        // Field matching is an OR query. Keep shorter canonical names inferred
        // from the user's wording because a planner-added suffix such as
        // "观察值" is more specific linguistically but may not exist in the
        // stored field name.
        fieldTerms = fieldTerms.stream().filter(term -> term != null && !term.isBlank())
                .map(String::strip).distinct().limit(20)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var recordLimit = mode == DataSourceFileSearchFacade.DataQueryMode.FILE_OVERVIEW
                || "COMPARE".equals(analysisGoal) || "EVALUATE".equals(analysisGoal) ? 5 : 1;
        DataSourceFileSearchFacade.DataFileScope fileScope;
        try {
            fileScope = DataSourceFileSearchFacade.DataFileScope.valueOf(request.fileScope());
        } catch (IllegalArgumentException exception) {
            fileScope = request.fileName().isBlank() ? DataSourceFileSearchFacade.DataFileScope.AUTO
                    : DataSourceFileSearchFacade.DataFileScope.EXPLICIT;
        }
        var recordTermMatch = comparisonQuestion
                && recordTerms.size() > 1
                ? DataSourceFileSearchFacade.RecordTermMatch.ANY
                : DataSourceFileSearchFacade.RecordTermMatch.ALL;
        var evidenceFilter = inferEvidenceFilter(originalQuestion);
        var contextMode = switch (analysisGoal) {
            case "COMPARE", "EVALUATE" -> DataSourceFileSearchFacade.ContextMode.COMPARABLE;
            case "EXPLAIN" -> DataSourceFileSearchFacade.ContextMode.ANALYTIC;
            default -> DataSourceFileSearchFacade.ContextMode.MINIMAL;
        };
        return new DataSourceFileSearchFacade.DataDetailQuery(mode, request.fileName(), fileScope,
                List.copyOf(recordTerms), List.copyOf(fieldTerms), recordTermMatch, evidenceFilter, recordLimit, 5, 50,
                contextMode, 32, request.analysisGoal().equals("EVALUATE") ? 5 : 1, request.analysisGoal());
    }

    private static String stripDataFileReferences(String question) {
        if (question == null || question.isBlank()) return "";
        var value = question.replaceAll("(?i)[“\\\"《][^”\\\"》\\r\\n]{1,260}\\.(?:xlsx|xls|csv)[”\\\"》]", " ");
        return value.replaceAll("(?i)[^\\s，。；;：:“”\\\"》]{1,260}\\.(?:xlsx|xls|csv)", " ");
    }

    private static List<String> removeFileNameFragments(List<String> terms, String fileName) {
        if (terms == null || terms.isEmpty() || fileName == null || fileName.isBlank()) return terms == null ? List.of() : terms;
        var fragments = new LinkedHashSet<>(List.of(fileName.split("[^A-Za-z0-9\\u4E00-\\u9FFF]+")));
        return terms.stream().filter(term -> term != null && !fragments.contains(term.strip())).toList();
    }

    /**
     * Lifecycle words in the question are source-boundary requirements, not answer content.
     * Keep this small and deterministic so a model cannot broaden an explicit formal-data request.
     */
    static DataSourceFileSearchFacade.EvidenceFilter inferEvidenceFilter(String question) {
        if (question == null || question.isBlank()) return DataSourceFileSearchFacade.EvidenceFilter.ANY;
        var normalized = question.replaceAll("\\s+", "");
        if (normalized.contains("正式数据中心") || normalized.contains("确认来源")
                || normalized.contains("已确认数据中心")) {
            return DataSourceFileSearchFacade.EvidenceFilter.CONFIRMED_DATA;
        }
        if (normalized.contains("已完成实验") || normalized.contains("完成的实验")
                || normalized.contains("正式实验") || normalized.contains("已发布实验")) {
            return DataSourceFileSearchFacade.EvidenceFilter.COMPLETED_EXPERIMENT;
        }
        if (normalized.contains("正式数据") || normalized.contains("已发布数据")) {
            return DataSourceFileSearchFacade.EvidenceFilter.FORMAL;
        }
        return DataSourceFileSearchFacade.EvidenceFilter.ANY;
    }

    static boolean isComparisonQuestion(String question) {
        if (question == null || question.isBlank()) return false;
        var normalized = question.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return List.of("比较", "对比", "相比", "差异", "分别", "哪个", "哪一个", "谁更", "vs", " versus ")
                .stream().anyMatch(normalized::contains);
    }

    private static boolean usableInferredField(String field) {
        if (field == null || field.isBlank() || field.length() > 40) return false;
        return !field.contains("、") && !field.contains("，") && !field.contains(",")
                && !field.contains("和") && !field.contains("请") && !field.contains("来源")
                && !field.contains("字段") && !field.contains("位置");
    }

    /**
     * Keep explicit identifiers and domain-labelled Chinese entities, but do not let
     * answer instructions such as “原样写出” become mandatory record terms.
     */
    static boolean isStrongExplicitRecordTerm(String value, String question,
            boolean questionHasExplicitIdentifier) {
        if (value == null || value.isBlank()) return false;
        var normalized = value.replaceAll("\\s+", "").strip();
        if (isExplicitlyLabelledIdentifier(question, normalized)) return true;
        if (normalized.matches("[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)")) {
            if (questionHasExplicitIdentifier) {
                // Once the user names a record, bare numbers in the question
                // are usually test conditions or requested values, not more IDs.
                return false;
            }
            // Preserve numeric reverse lookups when the question has no record ID.
            return true;
        }
        if (!looksLikeExplicitIdentifier(normalized)) {
            // Terms such as PMMA, PET, or PC describe materials and test
            // substrates. They become record terms only when the user labels
            // them as an ID (handled above).
            return false;
        }
        if (isMeasurementValueToken(normalized)) return !questionHasExplicitIdentifier;
        return true;
    }

    private static boolean isExplicitlyLabelledIdentifier(String question, String value) {
        if (question == null || question.isBlank() || value == null || value.isBlank()) return false;
        var label = "(?:记录编号|实验编号|样品编号|业务记录编号|配方编号|编号|记录号|实验号|样品号|型号|ID)";
        var token = java.util.regex.Pattern.quote(value);
        var separator = "(?:\\s*(?:为|是|[:：=#-])\\s*|\\s+|(?=[A-Za-z0-9]))";
        var pattern = java.util.regex.Pattern.compile("(?i)" + label + separator
                + token + "(?![A-Za-z0-9])");
        return pattern.matcher(question).find();
    }

    private static boolean isMeasurementValueToken(String value) {
        if (value == null || value.isBlank()) return false;
        var measurement = java.util.regex.Pattern.compile(
                "(?i)^[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)\\s*(?:°\\s*C|℃|度|小时|小時|分钟|分鐘|秒|天|周|月|年|"
                        + "h(?:ours?)?|min(?:utes?)?|sec(?:onds?)?|d|days?|"
                        + "%|％|mm|cm|μm|µm|um|nm|kg|mg|g(?:/mol)?|mPa(?:\\s*[·.]\\s*s)?|MPa|"
                        + "rpm|次|遍|级|級|层|層)(?![A-Za-z])");
        return measurement.matcher(value.strip()).find();
    }

    private static boolean isMeasurementConditionNumber(String question, String number) {
        if (question == null || question.isBlank()) return false;
        var token = java.util.regex.Pattern.quote(number);
        var measurement = java.util.regex.Pattern.compile(
                "(?i)(?<![\\d.])" + token
                        + "\\s*(?:°\\s*C|℃|度|小时|小時|分钟|分鐘|秒|天|周|月|年|"
                        + "h(?:ours?)?|min(?:utes?)?|sec(?:onds?)?|d|days?|"
                        + "%|％|mm|cm|μm|µm|um|nm|kg|mg|g(?:/mol)?|mPa(?:\\s*[·.]\\s*s)?|MPa|"
                        + "rpm|次|遍|级|級|层|層)(?![A-Za-z])");
        return measurement.matcher(question).find();
    }

    private static List<String> canonicalRecordTerms(Set<String> terms) {
        if (terms == null || terms.isEmpty()) return List.of();
        var result = new LinkedHashSet<String>();
        var opaque = java.util.regex.Pattern.compile(
                "(?i)(?<![A-Za-z0-9])[A-Za-z0-9][A-Za-z0-9._/-]{1,120}(?![A-Za-z0-9])");
        var labeled = java.util.regex.Pattern.compile(
                "(?:配方|树脂|样品|试样)[\\u4E00-\\u9FFF]*(?:[-_:#\\s]*)[A-Za-z0-9][A-Za-z0-9._/-]{0,120}");
        for (var raw : terms) {
            if (raw == null || raw.isBlank()) continue;
            var value = raw.strip();
            var labeledMatch = labeled.matcher(value);
            if (labeledMatch.find()) {
                result.add(labeledMatch.group());
                continue;
            }
            var opaqueMatch = opaque.matcher(value);
            if (opaqueMatch.find()) {
                result.add(opaqueMatch.group());
                continue;
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    static InferredFieldLookup inferFieldLookup(String question) {
        if (question == null || question.isBlank()) return new InferredFieldLookup("", "");
        var normalized = stripDataSelectionPrefix(question).strip()
                .replaceAll("[，,；;。！？?]*(?:请)?(?:用自然语言)?(?:简单|简要)?(?:分析|说明|解读)(?:一下)?[。！？?]*$", "")
                .replaceAll("[？?。！!]+$", "");
        var separator = normalized.lastIndexOf('的');
        if (separator <= 0 || separator >= normalized.length() - 1) {
            return new InferredFieldLookup("", inferStandaloneField(normalized));
        }
        var record = normalized.substring(0, separator)
                .replaceFirst("^(?:请问|请查询|查询|查看)", "")
                .replaceFirst("^(?:只查|仅查|只使用|仅使用)?(?:数据中心|实验本|电子实验记录本)\\s*[：:]?\\s*", "")
                .replaceFirst("^(?:实验编号|树脂编号|样品编号|试样编号|记录编号)[：:\\s]*", "").strip();
        var field = normalized.substring(separator + 1)
                .replaceFirst("(?:是多少|是什么|为多少|数值|数据|结果|情况|值)$", "").strip();
        if (record.length() > 120 || !record.matches(".*[A-Za-z0-9].*")) record = "";
        if (field.length() > 80) field = "";
        return new InferredFieldLookup(record, field);
    }

    static String inferRecordLookup(String question) {
        if (question == null || question.isBlank()) return "";
        var normalized = stripDataSelectionPrefix(question).strip();
        var labeled = java.util.regex.Pattern.compile(
                        "(?:实验编号|树脂编号|样品编号|试样编号|记录编号)[：:\\s]*([A-Za-z0-9][A-Za-z0-9._/-]{1,120})",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(normalized);
        if (labeled.find()) return labeled.group(1).strip();
        var inferred = inferFieldLookup(normalized);
        return inferred.recordTerm();
    }

    /**
     * Deterministic, deliberately small Chinese entity extraction for the
     * structured row index. It keeps user-provided identifiers intact and only
     * recognizes entities around explicit experiment/data language; it does not
     * create a full Chinese n-gram index.
     */
    static StructuredQueryTerms extractStructuredQueryTerms(String question) {
        return extractStructuredQueryTerms(question, List.of());
    }

    /**
     * Extracts terms using a maximum-specificity pass.  The seed terms come
     * from the rewrite model, but they are only accepted when they are literal
     * text from the user/context; the deterministic pass then prefers a whole
     * identifier, model/combination name, or labeled value over shorter terms
     * contained inside it.
     */
    static StructuredQueryTerms extractStructuredQueryTerms(String question, List<String> seedTerms) {
        if (question == null || question.isBlank()) return new StructuredQueryTerms(List.of());
        var normalized = stripDataSelectionPrefix(question).strip();
        var candidates = new LinkedHashSet<String>();
        var labeled = java.util.regex.Pattern.compile(
                        "(?:实验人|负责人|实验者|操作者|样品(?:名称|名)?|物料|材料|原料|实验编号|样品编号|试样编号|记录编号)[：:\\s]*([A-Za-z0-9\\u4E00-\\u9FFF._/\\-+&·、()（）\\[\\]【】#%]{2,120})")
                .matcher(normalized);
        while (labeled.find()) addStructuredEntity(candidates, labeled.group(1));

        var owner = java.util.regex.Pattern.compile("([\\u4E00-\\u9FFF]{2,24})的实验(?:有什么|有哪些|数据|结果|记录|情况)?")
                .matcher(normalized);
        while (owner.find()) addStructuredEntity(candidates, owner.group(1));

        var experiment = java.util.regex.Pattern.compile(
                        "(?:分析|查询|检索|查找|查看|了解|统计|研究|请问|请查询)?(?:一下)?([\\u4E00-\\u9FFF]{2,24})实验(?:的|数据|结果|记录|有哪些|有什么)")
                .matcher(normalized);
        while (experiment.find()) {
            var value = ownerPrefix(experiment.group(1));
            addStructuredEntity(candidates, value);
            if (!normalized.contains(value + "的实验")) addStructuredEntity(candidates, value + "实验");
        }

        var mixedIdentifier = java.util.regex.Pattern.compile("(?i)(?<![A-Za-z0-9])[A-Za-z0-9][A-Za-z0-9._/-]{1,120}(?![A-Za-z0-9])")
                .matcher(normalized);
        while (mixedIdentifier.find()) addStructuredEntity(candidates, mixedIdentifier.group());

        // Keep compact Chinese identifiers such as "配方001" and "树脂A12"
        // searchable even when the stored key uses separators (配方-001-01).
        var cjkIdentifier = java.util.regex.Pattern.compile(
                        "(?<![\\u4E00-\\u9FFF A-Za-z0-9])[\\u4E00-\\u9FFF]{1,16}[A-Za-z0-9][A-Za-z0-9._/-]{0,120}(?![A-Za-z0-9])")
                .matcher(normalized);
        while (cjkIdentifier.find()) addStructuredEntity(candidates, cjkIdentifier.group());

        var labeledCjkIdentifier = java.util.regex.Pattern.compile(
                        "(?:配方|树脂|样品|试样)[\\u4E00-\\u9FFF]*(?:[-_:#\\s]*)[A-Za-z0-9][A-Za-z0-9._/-]{0,120}")
                .matcher(normalized);
        while (labeledCjkIdentifier.find()) addStructuredEntity(candidates, labeledCjkIdentifier.group());

        // Query-rewrite terms are the dynamic domain dictionary for this
        // request. QueryRewriteService has already restricted them to literal
        // text from the user question or recent conversation; keeping them
        // here lets follow-up questions reuse a complete identifier/model.
        if (seedTerms != null) seedTerms.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .forEach(value -> addStructuredEntity(candidates, value));

        return new StructuredQueryTerms(longestSpecificTerms(candidates));
    }

    /**
     * Maximum matching over the candidate dictionary.  A shorter candidate is
     * removed only when it is fully contained in a longer candidate, so
     * unrelated entities remain independently searchable.  This keeps
     * `UV-01A` and `甲油胶耐磨实验` intact while still allowing two separate
     * entities such as `陈琪` and `UV-01A` to coexist.
     */
    private static List<String> longestSpecificTerms(Set<String> candidates) {
        var ordered = candidates == null ? List.<String>of() : candidates.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .sorted(Comparator.comparingInt((String value) -> containsAsciiOrDigit(value) ? 0 : 1)
                        .thenComparing(Comparator.comparingInt(String::length).reversed()))
                .toList();
        var selected = new ArrayList<String>();
        for (var candidate : ordered) {
            var compact = compactTerm(candidate);
            if (compact.length() < 2) continue;
            var covered = selected.stream().map(RagRetrievalService::compactTerm)
                    .anyMatch(existing -> existing.length() > compact.length() && existing.contains(compact));
            if (!covered) selected.add(candidate);
            if (selected.size() >= 12) break;
        }
        return List.copyOf(selected);
    }

    private static String compactTerm(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").strip();
    }

    private static boolean containsAsciiOrDigit(String value) {
        return value != null && value.matches(".*[A-Za-z0-9].*");
    }

    private static String ownerPrefix(String value) {
        if (value == null) return "";
        var result = value.strip();
        if (result.endsWith("的")) result = result.substring(0, result.length() - 1).strip();
        return result.startsWith("分析") || result.startsWith("查询") || result.startsWith("查找")
                ? result.substring(2) : result;
    }

    private static void addStructuredEntity(Set<String> entities, String value) {
        if (value == null) return;
        var normalized = value.replaceAll("^[请问请查询分析查询检索查找查看了解统计研究]+|[的，,。；;：:?？!！]+$", "").strip();
        // A labeled identifier is often followed by the rest of a natural
        // language question (for example: "实验编号 APP-UV-0001 的粘度").
        // Keep the opaque identifier instead of turning the whole sentence
        // into a record term that can never match one source row.
        if (normalized.matches("^[A-Za-z0-9][A-Za-z0-9._/-]*.*")) {
            var leadingIdentifier = java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]*")
                    .matcher(normalized);
            if (leadingIdentifier.find()) normalized = leadingIdentifier.group();
        } else {
            var separator = java.util.regex.Pattern.compile("的|、|，|,|；|;|。|！|!").matcher(normalized);
            if (separator.find() && separator.start() > 1) normalized = normalized.substring(0, separator.start()).strip();
        }
        if (normalized.length() >= 2 && normalized.length() <= 120
                && !Set.of("实验有什么", "实验数据", "实验结果", "实验记录").contains(normalized)) {
            entities.add(normalized);
        }
    }

    record StructuredQueryTerms(List<String> entities) {
        StructuredQueryTerms {
            entities = entities == null ? List.of() : List.copyOf(entities);
        }
    }

    private static String inferStandaloneField(String question) {
        var fields = inferRequestedFields(question);
        return fields.isEmpty() ? "" : fields.getFirst();
    }

    static List<String> inferRequestedFields(String question) {
        if (question == null || question.isBlank()) return List.of();
        var fields = new LinkedHashSet<String>();
        List.of("配方比例", "表干性", "实测固含", "钢丝绒耐磨", "固含", "粘度", "外观", "耐磨", "硬度",
                        "附着力", "光泽", "色差", "酸值", "羟值")
                .stream().filter(question::contains).forEach(fields::add);
        if (question.contains("黏度")) fields.add("粘度");
        if (question.contains("磨耗")) {
            fields.add("耐磨");
            fields.add("钢丝绒耐磨");
        }
        return List.copyOf(fields);
    }

    private static String stripDataSelectionPrefix(String question) {
        return question.replaceFirst("^在数据文件[《“\\\"]?[^》”\\\"\\r\\n]+\\.(?:xlsx|xls|csv)[》”\\\"]?中(?:查询)?[：:]\\s*", "");
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private WebTasks launchWebSearch(boolean requested, List<QueryRewriteService.WebQuery> queries) {
        var started = System.nanoTime();
        if (!requested) return new WebTasks(false, "SKIPPED", started, started, List.of(), List.of());
        if (!webSearch.isAvailable()) {
            return new WebTasks(true, "UNAVAILABLE", started, started, List.of(), List.of("NOT_CONFIGURED"));
        }
        var safeQueries = queries == null ? List.<QueryRewriteService.WebQuery>of() : queries.stream().limit(3).toList();
        if (safeQueries.isEmpty()) {
            return new WebTasks(true, "EMPTY", started, started, List.of(), List.of("NO_QUERY"));
        }
        var futures = new ArrayList<WebFuture>();
        var failures = new ArrayList<String>();
        for (var query : safeQueries) {
            try {
                var providerQuery = new WebSearchProvider.SearchQuery(query.query(), query.topic(), query.timeRange());
                futures.add(new WebFuture(providerQuery,
                        CompletableFuture.supplyAsync(() -> webSearch.search(providerQuery), webSearchExecutor)));
            } catch (RejectedExecutionException exception) {
                failures.add("REJECTED");
            }
        }
        return new WebTasks(true, futures.isEmpty() ? "REJECTED" : "RUNNING", started,
                started + webSearchTimeout.toNanos(), List.copyOf(futures), List.copyOf(failures));
    }

    private WebSearchResult collectWebSearch(WebTasks tasks) {
        if (!tasks.requested()) return new WebSearchResult(List.of(), new WebTrace(
                false, false, tasks.initialStatus(), 0, 0, 0, 0, ""));
        if (tasks.futures().isEmpty()) return new WebSearchResult(List.of(), new WebTrace(
                true, false, tasks.initialStatus(), elapsedMs(tasks.startedNanos()), 0, 0, 0,
                String.join(",", new LinkedHashSet<>(tasks.failures()))));
        var failures = new ArrayList<>(tasks.failures());
        var responses = new ArrayList<WebSearchProvider.SearchResponse>();
        long providerResponseMs = 0;
        for (var item : tasks.futures()) {
            var remaining = tasks.deadlineNanos() - System.nanoTime();
            if (remaining <= 0) {
                item.future().cancel(true);
                failures.add("TIMEOUT");
                continue;
            }
            try {
                var response = item.future().get(remaining, TimeUnit.NANOSECONDS);
                if (response == null) {
                    failures.add("EMPTY_RESPONSE");
                    continue;
                }
                responses.add(response);
                providerResponseMs += Math.max(0, response.providerResponseMs());
                if (!"SUCCEEDED".equals(response.status()) && !"EMPTY".equals(response.status())) {
                    failures.add(response.status() + (response.httpStatus() == null ? "" : "_" + response.httpStatus()));
                }
            } catch (TimeoutException exception) {
                item.future().cancel(true);
                failures.add("TIMEOUT");
            } catch (Exception exception) {
                failures.add("PROVIDER_ERROR");
            }
        }
        var hits = fuseWebResults(responses);
        var status = !hits.isEmpty() ? (failures.isEmpty() ? "SUCCEEDED" : "PARTIAL")
                : failures.stream().anyMatch("TIMEOUT"::equals) ? "TIMEOUT"
                : failures.isEmpty() ? "EMPTY" : tasks.initialStatus().equals("REJECTED") ? "REJECTED" : "PROVIDER_ERROR";
        var failureReason = String.join(",", new LinkedHashSet<>(failures));
        return new WebSearchResult(hits, new WebTrace(true, !hits.isEmpty(), status, elapsedMs(tasks.startedNanos()),
                tasks.futures().size(), hits.size(), providerResponseMs, failureReason));
    }

    private List<WebHit> fuseWebResults(List<WebSearchProvider.SearchResponse> responses) {
        var candidates = new LinkedHashMap<String, WebCandidate>();
        for (var response : responses) {
            for (var index = 0; index < response.results().size(); index++) {
                var result = response.results().get(index);
                var url = canonicalWebUrl(result.url());
                var content = boundedWebText(result.content(), 2_000);
                if (url.isBlank() || content.isBlank()) continue;
                var title = boundedWebText(result.title(), 300);
                var siteName = URI.create(url).getHost();
                var rrf = 1d / (60d + index + 1d);
                var current = candidates.get(url);
                if (current == null) {
                    candidates.put(url, new WebCandidate(title.isBlank() ? siteName : title, siteName, url, content,
                            Math.max(0d, result.score()), rrf, normalizedDate(result.publishedAt())));
                } else {
                    var useNew = result.score() > current.providerScore();
                    candidates.put(url, new WebCandidate(useNew && !title.isBlank() ? title : current.title(),
                            current.siteName(), url, useNew ? content : current.content(),
                            Math.max(current.providerScore(), Math.max(0d, result.score())), current.rrfScore() + rrf,
                            useNew ? normalizedDate(result.publishedAt()) : current.publishedAt()));
                }
            }
        }
        var fetchedAt = Instant.now().toString();
        var seenContent = new LinkedHashSet<String>();
        return candidates.values().stream()
                .sorted(Comparator.comparingInt((WebCandidate item) -> webAuthorityTier(item.url())).reversed()
                        .thenComparing(Comparator.comparingDouble(WebCandidate::rrfScore).reversed())
                        .thenComparing(Comparator.comparingDouble(WebCandidate::providerScore).reversed()))
                .filter(item -> seenContent.add(sha256(item.content())))
                .limit(maxWebCandidates)
                .map(item -> new WebHit(item.title(), item.siteName(), item.url(), item.content(),
                        item.rrfScore(), item.providerScore(), item.publishedAt(), fetchedAt, sha256(item.content())))
                .toList();
    }

    /**
     * Gives public authorities and academic institutions a generic priority without
     * maintaining product, manufacturer or subject-matter domain lists. Relevance
     * ordering is preserved inside the same authority tier.
     */
    static int webAuthorityTier(String value) {
        try {
            var host = URI.create(value == null ? "" : value).getHost();
            if (host == null || host.isBlank()) return 0;
            var labels = host.toLowerCase(java.util.Locale.ROOT).split("\\.");
            for (var label : labels) {
                if ("gov".equals(label) || "gouv".equals(label) || "gob".equals(label)
                        || "mil".equals(label) || "int".equals(label)) return 3;
            }
            for (var label : labels) {
                if ("edu".equals(label) || "ac".equals(label)) return 2;
            }
            return 0;
        } catch (IllegalArgumentException ignored) {
            return 0;
        }
    }

    static String canonicalWebUrl(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            var source = URI.create(value.strip());
            var scheme = source.getScheme() == null ? "" : source.getScheme().toLowerCase(java.util.Locale.ROOT);
            var host = source.getHost() == null ? "" : source.getHost().toLowerCase(java.util.Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || host.isBlank() || source.getUserInfo() != null
                    || privateWebHost(host)) return "";
            var port = source.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1;
            var path = source.getRawPath() == null || source.getRawPath().isBlank() ? "/" : source.getRawPath();
            return new URI(scheme, null, host, port, path, source.getRawQuery(), null).normalize().toASCIIString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean privateWebHost(String host) {
        if ("localhost".equals(host) || host.endsWith(".local") || "::1".equals(host)) return true;
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")
                || host.startsWith("169.254.")) return true;
        if (!host.startsWith("172.")) return false;
        var pieces = host.split("\\.");
        if (pieces.length < 2) return false;
        try {
            var second = Integer.parseInt(pieces[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String boundedWebText(String value, int maximum) {
        if (value == null) return "";
        var normalized = value.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum).strip() + "…";
    }

    private static String normalizedDate(String value) {
        return value == null ? "" : boundedWebText(value, 80);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var output = new StringBuilder();
            for (var item : digest) output.append(String.format("%02x", item));
            return output.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String dataChannelStatus(boolean searched, List<DataSourceFileSearchFacade.SourceFileHit> hits,
                                     int failureCount, int queryCount) {
        if (!searched) return "SKIPPED";
        if (failureCount > 0 && failureCount >= queryCount) return "FAILED";
        if (failureCount > 0) return "PARTIAL";
        return hits.isEmpty() ? "EMPTY" : "SUCCEEDED";
    }

    private static String rootCauseText(Throwable exception) {
        var root = exception;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        var message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private List<String> dataQueries(String question, QueryRewriteService.QueryPlan plan) {
        var queries = new LinkedHashSet<String>();
        if (question != null && !question.isBlank()) queries.add(question.strip());
        // Keep a generic lexical fallback from the user's wording. This is not
        // tied to a domain prompt or a finite list of experiment names; it
        // separates phrases at ordinary Chinese/English query boundaries so
        // title/context matches still run when the rewrite model returns NONE.
        extractDataSearchTerms(question).forEach(queries::add);
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
            if (plan.dataRequest() != null) {
                plan.dataRequest().recordTerms().stream().filter(value -> value != null && !value.isBlank())
                        .map(String::strip).forEach(queries::add);
                plan.dataRequest().fieldTerms().stream().filter(value -> value != null && !value.isBlank())
                        .map(String::strip).forEach(queries::add);
            }
            var seedTerms = new ArrayList<String>();
            if (plan.dataRequest() != null) seedTerms.addAll(plan.dataRequest().recordTerms());
            extractStructuredQueryTerms(question, seedTerms).entities().forEach(queries::add);
        }
        // Keep a few high-value lexical fallbacks without multiplying broad
        // Chinese n-gram matches into many slow database scans. The remaining
        // calls execute concurrently on the bounded data-search pool.
        return queries.stream().limit(8).toList();
    }

    private static List<String> extractDataSearchTerms(String question) {
        if (question == null || question.isBlank()) return List.of();
        var normalized = stripDataFileReferences(stripDataSelectionPrefix(question))
                .replaceFirst("^(?:(?:只)?查|只看)?数据中心[:：]?", "").strip();
        var segments = normalized.split("(?i)[，,、；;。！？!?\\s]+|的|及|与|和|或|对|在|中|关于|包含|相关|开展|进行|查询|查找|检索|查看|分析|研究|实验|项目|数据|记录|结果|是什么|有什么|有哪些|什么|哪些|多少|如何|怎么|是否|请问");
        var generic = Set.of("内容", "方面", "情况", "一下");
        var terms = new LinkedHashSet<String>();
        var lexicalSegments = new ArrayList<String>();
        var identifierPattern = java.util.regex.Pattern.compile("(?i)[A-Z]{2,}(?:[-_/][A-Z0-9]+)*|[A-Z]+\\d+[A-Z0-9_-]*");
        for (var segment : segments) {
            var term = segment.strip().replaceAll("^[：:（(【\\[\\\"“]+|[：:）)】\\]\\\"”]+$", "").strip();
            if (term.length() >= 2 && term.length() <= 120 && !generic.contains(term)) {
                terms.add(term);
                lexicalSegments.add(term);
                var identifiers = identifierPattern.matcher(term);
                while (identifiers.find()) {
                    var identifier = identifiers.group().strip();
                    if (identifier.length() >= 2) terms.add(identifier);
                }
            }
        }
        var hanRunPattern = java.util.regex.Pattern.compile("[\\u4E00-\\u9FFF]+");
        for (var index = lexicalSegments.size() - 1; index >= 0; index--) {
            var runs = hanRunPattern.matcher(lexicalSegments.get(index));
            while (runs.find()) {
                var run = runs.group();
                for (var offset = 0; offset + 2 <= run.length(); offset++) {
                    terms.add(run.substring(offset, offset + 2));
                }
            }
        }
        return terms.stream().limit(6).toList();
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
                            List<DataSourceFileSearchFacade.SourceFileHit> dataHits,
                            DataSourceFileSearchFacade.DataDetailResult dataDetail,
                            List<WebHit> webHits, Trace trace) {
        public Retrieval(QueryRewriteService.QueryPlan plan, List<KnowledgeSearchFacade.SearchHit> knowledgeHits,
                         List<DataSourceFileSearchFacade.SourceFileHit> dataHits, List<WebHit> webHits, Trace trace) {
            this(plan, knowledgeHits, dataHits,
                    DataSourceFileSearchFacade.DataDetailResult.empty(DataSourceFileSearchFacade.DataQueryMode.NONE),
                    webHits, trace);
        }

        public Retrieval {
            knowledgeHits = knowledgeHits == null ? List.of() : List.copyOf(knowledgeHits);
            dataHits = dataHits == null ? List.of() : List.copyOf(dataHits);
            dataDetail = dataDetail == null ? DataSourceFileSearchFacade.DataDetailResult.empty(
                    DataSourceFileSearchFacade.DataQueryMode.NONE) : dataDetail;
            webHits = webHits == null ? List.of() : List.copyOf(webHits);
        }
    }

    public record Trace(String rewriteStatus, String rewriteModel, boolean rewriteThinkingEnabled,
                        String strategy, int bm25Candidates, int vectorCandidates,
                        int mergedCandidates, int variantCount, int dataFileCandidates, String rerankerStatus,
                         List<String> fallbacks, int dataQueryCount, List<ChannelTrace> channels,
                         Map<String, Long> timings, RerankTrace reranker,
                         List<FactCoverageTrace> factCoverage, List<KnowledgeSearchFacade.QueryTrace> queryTraces,
                         WebTrace web) {
        public Trace(String rewriteStatus, String strategy, int bm25Candidates, int vectorCandidates,
                     int mergedCandidates, int dataFileCandidates, String rerankerStatus,
                     List<String> fallbacks, int dataQueryCount, List<ChannelTrace> channels) {
            this(rewriteStatus, "", false, strategy, bm25Candidates, vectorCandidates, mergedCandidates, 0,
                    dataFileCandidates, rerankerStatus, fallbacks, dataQueryCount, channels, Map.of(), null, List.of(), List.of(),
                    new WebTrace(false, false, "SKIPPED", 0, 0, 0, 0, ""));
        }
        public Trace {
            fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
            channels = channels == null ? List.of() : List.copyOf(channels);
            timings = timings == null ? Map.of() : Map.copyOf(timings);
            factCoverage = factCoverage == null ? List.of() : List.copyOf(factCoverage);
            queryTraces = queryTraces == null ? List.of() : List.copyOf(queryTraces);
            web = web == null ? new WebTrace(false, false, "SKIPPED", 0, 0, 0, 0, "") : web;
        }
    }

    public record ChannelTrace(String channel, String status, int resultCount) {
    }

    public record RerankTrace(String status, String provider, String model, Integer httpStatus, String error,
                              long elapsedMs, int inputCount, int outputCount) { }

    public record FactCoverageTrace(String label, String retrievalQuery, UUID chunkId, String status) { }

    public record WebHit(String title, String siteName, String url, String content, double retrievalScore,
                         double providerScore, String publishedAt, String fetchedAt, String contentHash) { }

    public record WebTrace(boolean webSearchRequested, boolean webSearchSucceeded, String status, long webSearchMs,
                           int queryCount, int candidateCount, long providerResponseMs, String failureReason) { }

    record InferredFieldLookup(String recordTerm, String fieldTerm) { }

    private record Reranked(List<KnowledgeSearchFacade.SearchHit> hits,
                            RerankerProvider.RerankOutcome outcome) { }

    private record CoverageSelection(List<KnowledgeSearchFacade.SearchHit> hits,
                                     List<FactCoverageTrace> coverage) { }

    private record DataChannelResult(List<DataSourceFileSearchFacade.SourceFileHit> hits, int failureCount,
                                     int queryCount, long elapsedMs, String status,
                                     DataSourceFileSearchFacade.DataDetailResult detail) {
        private DataChannelResult {
            hits = hits == null ? List.of() : List.copyOf(hits);
            detail = detail == null ? DataSourceFileSearchFacade.DataDetailResult.empty(
                    DataSourceFileSearchFacade.DataQueryMode.NONE) : detail;
        }
    }

    private record DataSearchResponse(String query, List<DataSourceFileSearchFacade.SourceFileHit> hits,
                                      RuntimeException error, long elapsedMs) {
        private DataSearchResponse {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    private static final class DataHitAccumulator {
        private DataSourceFileSearchFacade.SourceFileHit hit;
        private double bestScore;
        private int matches;

        private DataHitAccumulator(DataSourceFileSearchFacade.SourceFileHit hit) {
            this.hit = hit;
        }

        private void add(DataSourceFileSearchFacade.SourceFileHit candidate) {
            if (candidate == null) return;
            bestScore = Math.max(bestScore, candidate.score());
            matches++;
            if (metadataQuality(candidate) > metadataQuality(hit)) hit = candidate;
        }

        private static int metadataQuality(DataSourceFileSearchFacade.SourceFileHit hit) {
            if (hit == null) return 0;
            var quality = 0;
            if (hit.sourceLabel() != null && !hit.sourceLabel().isBlank()) quality++;
            if (hit.semanticDomain() != null && !hit.semanticDomain().isBlank()) quality++;
            if (hit.semanticField() != null && !hit.semanticField().isBlank()) quality++;
            if (hit.declaredCondition() != null && !hit.declaredCondition().isBlank()) quality++;
            if (hit.sourceUnit() != null && !hit.sourceUnit().isBlank()) quality++;
            if (hit.standardUnit() != null && !hit.standardUnit().isBlank()) quality++;
            if (hit.mappingKind() != null && !hit.mappingKind().isBlank()) quality++;
            if (hit.contextRelation() != null && "TARGET".equalsIgnoreCase(hit.contextRelation())) quality++;
            return quality;
        }

        private DataSourceFileSearchFacade.SourceFileHit result() {
            var combinedScore = bestScore + Math.min(2d, Math.max(0, matches - 1) * 0.35d);
            return new DataSourceFileSearchFacade.SourceFileHit(hit.hitId(), hit.fileObjectId(), hit.importJobId(),
                    hit.rowNumber(), hit.columnName(), hit.originalName(), hit.content(), combinedScore,
                    hit.sourceLocator(), hit.recordKey(), hit.fieldCode(), hit.fieldName(), hit.fieldValue(),
                    hit.unit(), hit.valueType(), hit.sheetName(), hit.cellAddress(), hit.sourceOwner(),
                    hit.recognitionMode(), hit.experimentId(), hit.experimentVersionId(), hit.sourceStatus(),
                    hit.sourceLabel(), hit.semanticDomain(), hit.semanticField(), hit.declaredCondition(),
                    hit.sourceUnit(), hit.standardUnit(), hit.mappingKind(), hit.contextRelation());
        }
    }

    private record WebFuture(WebSearchProvider.SearchQuery query,
                             CompletableFuture<WebSearchProvider.SearchResponse> future) { }

    private record WebTasks(boolean requested, String initialStatus, long startedNanos, long deadlineNanos,
                            List<WebFuture> futures, List<String> failures) { }

    private record WebSearchResult(List<WebHit> hits, WebTrace trace) { }

    private record WebCandidate(String title, String siteName, String url, String content, double providerScore,
                                double rrfScore, String publishedAt) { }
}
