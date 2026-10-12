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
    private final Duration webSearchTimeout;
    private final int maxWebCandidates;
    private final int maxDataHits;

    @Autowired
    public RagRetrievalService(KnowledgeSearchFacade knowledge, DataSourceFileSearchFacade dataFiles,
                               QueryRewriteService rewrite, RerankerProvider reranker,
                               WebSearchProvider webSearch,
                               @Qualifier("webSearchExecutor") Executor webSearchExecutor,
                               @Qualifier("ragRetrievalExecutor") Executor retrievalExecutor,
                               @Value("${app.ai.tavily.timeout:5s}") Duration webSearchTimeout,
                               @Value("${app.ai.tavily.max-candidates:6}") int maxWebCandidates,
                               @Value("${app.ai.retrieval.data-max-hits:16}") int maxDataHits) {
        this.knowledge = knowledge;
        this.dataFiles = dataFiles;
        this.rewrite = rewrite;
        this.reranker = reranker;
        this.webSearch = webSearch;
        this.webSearchExecutor = webSearchExecutor;
        this.retrievalExecutor = retrievalExecutor;
        this.webSearchTimeout = webSearchTimeout == null || webSearchTimeout.isNegative() || webSearchTimeout.isZero()
                ? Duration.ofSeconds(5) : webSearchTimeout;
        this.maxWebCandidates = Math.max(1, Math.min(12, maxWebCandidates));
        this.maxDataHits = Math.max(1, Math.min(100, maxDataHits));
    }

    RagRetrievalService(KnowledgeSearchFacade knowledge, DataSourceFileSearchFacade dataFiles,
                        QueryRewriteService rewrite, RerankerProvider reranker) {
        this(knowledge, dataFiles, rewrite, reranker, NO_WEB, Runnable::run, Runnable::run,
                Duration.ofSeconds(5), 6, 16);
    }

    RagRetrievalService(KnowledgeSearchFacade knowledge, DataSourceFileSearchFacade dataFiles,
                        QueryRewriteService rewrite, RerankerProvider reranker, WebSearchProvider webSearch,
                        Executor webSearchExecutor, Duration webSearchTimeout, int maxWebCandidates) {
        this(knowledge, dataFiles, rewrite, reranker, webSearch, webSearchExecutor, webSearchExecutor,
                webSearchTimeout, maxWebCandidates, 16);
    }

    RagRetrievalService(KnowledgeSearchFacade knowledge, DataSourceFileSearchFacade dataFiles,
                        QueryRewriteService rewrite, RerankerProvider reranker, WebSearchProvider webSearch,
                        Executor webSearchExecutor, Executor retrievalExecutor, Duration webSearchTimeout,
                        int maxWebCandidates) {
        this(knowledge, dataFiles, rewrite, reranker, webSearch, webSearchExecutor, retrievalExecutor,
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
                    () -> searchDataChannel(organizationId, dataQueries, dataDetailQuery,
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
        DataChannelResult dataResult;
        try {
            dataResult = dataFuture.join();
        } catch (RuntimeException exception) {
            dataResult = new DataChannelResult(List.of(), dataQueries.size(), dataQueries.size(), 0,
                    searchData ? "FAILED" : "SKIPPED",
                    DataSourceFileSearchFacade.DataDetailResult.empty(dataDetailQuery.mode()));
        }
        var dataFailureCount = dataResult.failureCount();
        if (dataFailureCount > 0) internalFallbacks.add(dataFailureCount >= Math.max(1, dataResult.queryCount())
                ? "DATA_SEARCH_ERROR" : "DATA_SEARCH_PARTIAL");
        var data = dataResult.hits();
        timings.put("dataSearchMs", dataResult.elapsedMs());
        stage = System.nanoTime();
        Reranked reranked;
        try {
            reranked = rerank(plan.plan().rewrittenQuery(), knowledgeResult.hits());
        } catch (RuntimeException exception) {
            internalFallbacks.add("RERANKER_PROVIDER_ERROR");
            reranked = new Reranked(knowledgeResult.hits(),
                    RerankerProvider.RerankOutcome.skipped("PROVIDER_ERROR", knowledgeResult.hits().size()));
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
        return new Retrieval(plan.plan(), knowledgeHits, data, dataResult.detail(), webResult.hits(), new Trace(
                plan.status(), plan.model(), plan.thinkingEnabled(), knowledgeResult.trace().strategy(), knowledgeResult.trace().bm25Candidates(),
                knowledgeResult.trace().vectorCandidates(), knowledgeResult.trace().mergedCandidates(),
                knowledgeResult.trace().variantCount(), data.size(), reranked.outcome().status(), fallbacks,
                dataResult.queryCount(), List.of(
                        new ChannelTrace("KNOWLEDGE_KEYWORD_VECTOR", knowledgeChannelStatus, knowledgeResult.trace().mergedCandidates()),
                        new ChannelTrace("DATA_CENTER_ROW", dataResult.status(), data.size()),
                        new ChannelTrace("WEB_PUBLIC", webResult.trace().status(), webResult.hits().size())),
                timings, rerankTrace(reranked.outcome()), coverage.coverage(), knowledgeResult.trace().queries(),
                webResult.trace()));
    }

    private DataChannelResult searchDataChannel(UUID organizationId, List<String> queries,
                                                DataSourceFileSearchFacade.DataDetailQuery detailQuery,
                                                List<UUID> filters,
                                                DataSourceFileSearchFacade.RetrievalAccessScope accessScope,
                                                boolean enabled, boolean legacyFacadeCalls) {
        if (!enabled) return new DataChannelResult(List.of(), 0, 0, 0, "SKIPPED",
                DataSourceFileSearchFacade.DataDetailResult.empty(detailQuery.mode()));
        var started = System.nanoTime();
        if (detailQuery.mode() != DataSourceFileSearchFacade.DataQueryMode.NONE) {
            try {
                var detail = legacyFacadeCalls
                        ? dataFiles.queryDetails(organizationId, detailQuery, filters, accessScope.dataScope())
                        : dataFiles.queryDetails(organizationId, detailQuery, filters, accessScope);
                var status = detail.found() && !detail.hits().isEmpty() ? "SUCCEEDED"
                        : detail.found() ? "EMPTY" : "NOT_FOUND";
                return new DataChannelResult(detail.hits(), 0, 1, elapsedMs(started), status, detail);
            } catch (RuntimeException exception) {
                log.warn("assistant_data_detail_lookup_failed organizationId={} mode={} fileName={} fileScope={} recordTerms={} fieldTerms={} categoryIds={} exceptionType={} detail={}",
                        organizationId, detailQuery.mode(), detailQuery.fileName(), detailQuery.fileScope(),
                        detailQuery.recordTerms(), detailQuery.fieldTerms(), filters,
                        exception.getClass().getSimpleName(), rootCauseText(exception));
                return new DataChannelResult(List.of(), 1, 1, elapsedMs(started), "FAILED",
                        DataSourceFileSearchFacade.DataDetailResult.empty(detailQuery.mode()));
            }
        }
        var byHit = new LinkedHashMap<UUID, DataHitAccumulator>();
        var failures = 0;
        for (var query : queries) {
            try {
                var found = legacyFacadeCalls
                        ? dataFiles.search(organizationId, query, filters, accessScope.dataScope(),
                        Math.max(1, Math.min(10, maxDataHits)))
                        : dataFiles.search(organizationId, query, filters, accessScope,
                        Math.max(1, Math.min(10, maxDataHits)));
                found
                        .forEach(hit -> byHit.computeIfAbsent(hit.hitId(), ignored -> new DataHitAccumulator(hit))
                                .add(hit.score()));
            } catch (RuntimeException exception) {
                failures++;
                log.warn("assistant_data_row_lookup_failed organizationId={} query={} categoryIds={} exceptionType={} detail={}",
                        organizationId, query, filters, exception.getClass().getSimpleName(), rootCauseText(exception));
            }
        }
        var hits = byHit.values().stream().map(DataHitAccumulator::result)
                .sorted(Comparator.comparingDouble(DataSourceFileSearchFacade.SourceFileHit::score).reversed())
                .limit(maxDataHits).toList();
        return new DataChannelResult(hits, failures, queries.size(), elapsedMs(started),
                dataChannelStatus(true, hits, failures, queries.size()),
                DataSourceFileSearchFacade.DataDetailResult.empty(detailQuery.mode()));
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
        if (comparisonQuestion && explicitQuestionTerms.size() > 1) {
            // Prefer the independent opaque identifiers found in the original
            // question. A rewrite model can otherwise return one aggregate term
            // such as "比较 APP-001 和 APP-002", which cannot match any record.
            recordTerms.clear();
            explicitQuestionTerms.stream().filter(RagRetrievalService::isStrongExplicitRecordTerm)
                    .forEach(recordTerms::add);
        } else if (!explicitQuestionTerms.isEmpty()) {
            // Prefer identifiers and numeric lookup values explicitly present in
            // the current question. A rewrite model may wrap a value in a broad
            // phrase such as "粘度/黏度 887.4"; keeping the explicit token 887.4
            // makes reverse lookup work without trusting the broad phrase as a
            // record identifier.
            recordTerms.clear();
            explicitQuestionTerms.stream().filter(RagRetrievalService::isStrongExplicitRecordTerm)
                    .forEach(recordTerms::add);
        } else {
            extractStructuredQueryTerms(structuredQuestion,
                    removeFileNameFragments(request.recordTerms(), request.fileName())).entities()
                    .forEach(recordTerms::add);
        }
        if (mode == DataSourceFileSearchFacade.DataQueryMode.FIELD_LOOKUP) {
            var inferred = inferFieldLookup(structuredQuestion);
            if (!comparisonQuestion && !inferred.recordTerm().isBlank()) recordTerms.add(inferred.recordTerm());
            if (usableInferredField(inferred.fieldTerm())) fieldTerms.add(inferred.fieldTerm());
            inferRequestedFields(originalQuestion).forEach(fieldTerms::add);
        } else if (mode == DataSourceFileSearchFacade.DataQueryMode.RECORD_DETAIL) {
            var recordTerm = inferRecordLookup(structuredQuestion);
            if (!recordTerm.isBlank()) recordTerms.add(recordTerm);
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
    private static boolean isStrongExplicitRecordTerm(String value) {
        if (value == null || value.isBlank()) return false;
        var normalized = value.replaceAll("\\s+", "").strip();
        if (containsAsciiOrDigit(normalized)) return true;
        return List.of("配方", "树脂", "样品", "试样", "材料", "实验")
                .stream().anyMatch(normalized::contains);
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

    private static final class DataHitAccumulator {
        private final DataSourceFileSearchFacade.SourceFileHit hit;
        private double bestScore;
        private int matches;

        private DataHitAccumulator(DataSourceFileSearchFacade.SourceFileHit hit) {
            this.hit = hit;
        }

        private void add(double score) {
            bestScore = Math.max(bestScore, score);
            matches++;
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
