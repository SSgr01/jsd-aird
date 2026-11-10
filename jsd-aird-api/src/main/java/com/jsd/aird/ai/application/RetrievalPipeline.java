package com.jsd.aird.ai.application;
import static com.jsd.aird.ai.application.RagRetrievalService.*;
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
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.jsd.aird.shared.resource.BudgetedTask;
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


/** Single Agent retrieval orchestrator. Sources provide raw recall and authoritative reads. */
@Service
public class RetrievalPipeline {
    private static final Logger log=LoggerFactory.getLogger(RetrievalPipeline.class);
    private final RagRetrievalService rag;
    private final ContextCompressionService compression;
    final RetrievalPolicy policy;
    private com.jsd.aird.kb.api.ResearchKnowledgeFacade knowledgeSources;
    private PrivateResearchSourceService privateSources;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    @Autowired void sources(com.jsd.aird.kb.api.ResearchKnowledgeFacade knowledge,PrivateResearchSourceService privateSources) {
        this.knowledgeSources=knowledge;this.privateSources=privateSources;
    }
    public Map<String,Object> configuration() {var result=new LinkedHashMap<>(policy.describe());result.put("fingerprint",policy.fingerprint());return result;}
    @Autowired
    public RetrievalPipeline(RagRetrievalService rag, ContextCompressionService compression, RetrievalPolicy policy) {
        this.rag=rag;this.compression=compression;this.policy=policy;
    }
    public RetrievalPipeline(RagRetrievalService rag, ContextCompressionService compression) { this(rag,compression,new RetrievalPolicy()); }
    public RagRetrievalService.Retrieval retrieve(UUID org,String question,List<AssistantRepository.MessageRow> history,
            List<UUID> kb,List<UUID> data,boolean web,DataSourceFileSearchFacade.RetrievalAccessScope scope) {
        return retrieveQa(org,question,history,kb,data,true,web,scope,false);
    }
    public ContextCompressionService.Context compress(List<KnowledgeSearchFacade.SearchHit> kb,
            List<DataSourceFileSearchFacade.SourceFileHit> data,int budget) { return compression.compress(kb,data,budget); }
    public ContextCompressionService.Context compress(Retrieval retrieval,int budget) {
        return compression.compress(retrieval.knowledgeHits(),RetrievalContextSupport.answerDataHits(retrieval),budget,retrieval.contextCandidates());
    }

    private KnowledgeSearchFacade.SearchResult recallKnowledge(KnowledgeSearchFacade.SearchRequest request) {
        var raw=rag.knowledge.recall(request);
        if(raw==null) return rag.knowledge.search(request); // compatibility with older facade implementations
        var channels=raw.channels().stream().filter(c -> switch(c.name()) {
            case "BM25" -> policy.qa.bm25(); case "VECTOR" -> policy.qa.vector(); case "METADATA", "TAG" -> policy.qa.metadata(); default -> true;
        }).map(c->new com.jsd.aird.shared.retrieval.RankFusion.Channel<>(c.name(),c.weight(),c.hits())).toList();
        var hits=com.jsd.aird.shared.retrieval.RankFusion.fuse(channels,h->h.chunkId().toString(),policy.rrfK).stream().limit(request.limit())
            .map(r->r.value().withScores(r.value().retrievalScore(),r.score(),r.score())).toList();
        return new KnowledgeSearchFacade.SearchResult(hits,raw.trace(),raw.factCandidates());
    }
    Retrieval retrieveQa(UUID organizationId, String question, List<AssistantRepository.MessageRow> history,
                              List<UUID> knowledgeCategoryIds, List<UUID> dataCategoryIds, boolean aiOnly,
                              boolean webSearchEnabled, DataSourceFileSearchFacade.RetrievalAccessScope accessScope,
                              boolean legacyFacadeCalls) {
        return retrieveQa(organizationId, question, history, knowledgeCategoryIds, dataCategoryIds, aiOnly,
            webSearchEnabled, accessScope, legacyFacadeCalls, false, true);
    }

    public Retrieval retrieveCurrentAccess(UUID org, String question, List<AssistantRepository.MessageRow> history,
            boolean web, DataSourceFileSearchFacade.RetrievalAccessScope access) {
        return retrieveQa(org, question, history, List.of(), List.of(), true, web, access, false, true, false);
    }

    /** The native agent already plans its query; retrieval must not invoke a hidden model. */
    public Retrieval retrieveAgentQuery(UUID org, String question, List<AssistantRepository.MessageRow> history,
            List<UUID> kb, List<UUID> data, boolean web, DataSourceFileSearchFacade.RetrievalAccessScope access) {
        return retrieveQa(org, question, history, kb, data, true, web, access, false,
            kb.isEmpty() && data.isEmpty(), false);
    }

    private Retrieval retrieveQa(UUID organizationId, String question, List<AssistantRepository.MessageRow> history,
            List<UUID> knowledgeCategoryIds, List<UUID> dataCategoryIds, boolean aiOnly, boolean webSearchEnabled,
            DataSourceFileSearchFacade.RetrievalAccessScope accessScope, boolean legacyFacadeCalls, boolean automatic,
            boolean rewriteQuery) {
        var started = System.nanoTime();
        var timings = new LinkedHashMap<String, Long>();
        var stage = System.nanoTime();
        var authorizedDocuments=knowledgeSources!=null&&(automatic||(knowledgeCategoryIds!=null&&!knowledgeCategoryIds.isEmpty()))
            ? knowledgeSources.authorizedSearchDocuments() : null;
        timings.put("authorizationMs",rag.elapsedMs(stage));stage=System.nanoTime();
        var plan = rewriteQuery && policy.qa.rewrite() ? rag.rewrite.rewrite(question, history, webSearchEnabled) : rag.rewrite.original(question, history, webSearchEnabled);
        timings.put("queryRewriteMs", rag.elapsedMs(stage));
        var explicitIdentifiers = explicitQueryIdentifiers(question, plan.plan());
        var webTasks = rag.launchWebSearch(webSearchEnabled, plan.plan().webQueries());
        // The scope selectors are explicit module boundaries. When the user
        // selects a knowledge category, data-center rows must not be used as a
        // silent fallback (and vice versa), otherwise an unrelated file can be
        // presented as evidence for a knowledge-base question.
        var searchKnowledge = automatic || (knowledgeCategoryIds != null && !knowledgeCategoryIds.isEmpty());
        var searchData = automatic || (dataCategoryIds != null && !dataCategoryIds.isEmpty());
        var knowledgeFilters = knowledgeCategoryIds == null ? List.<UUID>of() : knowledgeCategoryIds;
        var dataFilters = dataCategoryIds == null ? List.<UUID>of() : dataCategoryIds;
        var dataQueries = searchData ? rag.dataQueries(question, plan.plan()) : List.<String>of();
        var dataDetailQuery = rag.dataDetailQuery(plan.plan());
        BudgetedTask<DataChannelResult> dataFuture = null;
        try {
            dataFuture = BudgetedTask.submit(rag.retrievalExecutor, 20000,
                    () -> rag.searchDataChannel(organizationId, question, dataQueries, dataDetailQuery,
                            dataFilters, accessScope, searchData, legacyFacadeCalls));
        } catch (RejectedExecutionException exception) {
            // The result below records a rejected channel; never run it inline.
        }
        var internalFallbacks = new ArrayList<String>();
        var knowledgeChannelStatus = searchKnowledge ? "SUCCEEDED" : "SKIPPED";
        stage = System.nanoTime();
        KnowledgeSearchFacade.SearchResult knowledgeResult;
        if (searchKnowledge) {
            try {
                knowledgeResult = recallKnowledge(new KnowledgeSearchFacade.SearchRequest(
                        organizationId, plan.plan().rewrittenQuery(), aiOnly, 30, knowledgeFilters,
                        plan.plan().subQueries(), rag.searchTerms(plan.plan()), rag.requiredFacts(plan.plan()), question,
                        new KnowledgeSearchFacade.RecallOptions(policy.qa.bm25(), policy.qa.vector(), policy.qa.metadata()),authorizedDocuments));
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
        timings.put("knowledgeSearchMs", rag.elapsedMs(stage));
        // Search original wording, generic phrase segments and rewrite terms
        // independently. Exact record/field lookup is an optimization, not a
        // gate: if it returns no evidence, the general structured search still
        // gets a chance to match experiment/project metadata and row values.
        DataChannelResult dataResult;
        try {
            if (dataFuture == null) throw new RejectedExecutionException();
            dataResult = dataFuture.await();
        } catch (RuntimeException exception) {
            dataResult = new DataChannelResult(List.of(), dataQueries.size(), dataQueries.size(), 0,
                    searchData ? "FAILED" : "SKIPPED",
                    DataSourceFileSearchFacade.DataDetailResult.empty(dataDetailQuery.mode()));
        }
        dataResult = rag.scopeDataResultToExplicitIdentifiers(dataResult, explicitIdentifiers);
        var dataFailureCount = dataResult.failureCount();
        if (dataFailureCount > 0) internalFallbacks.add(dataFailureCount >= Math.max(1, dataResult.queryCount())
                ? "DATA_SEARCH_ERROR" : "DATA_SEARCH_PARTIAL");
        var data = dataResult.hits();
        timings.put("dataSearchMs", dataResult.elapsedMs());
        stage = System.nanoTime();
        Reranked reranked;
        var knowledgeCandidates = rag.scopeKnowledgeHitsForStructuredLookup(
                knowledgeResult.hits(), explicitIdentifiers, dataDetailQuery.mode());
        // Validate the selected publication authority before sending source bodies to a reranker.
        var authorizedKnowledgeSources=knowledgeSources==null||knowledgeCandidates.isEmpty()
            ? Map.<UUID,com.jsd.aird.kb.api.ResearchKnowledgeFacade.SourceRef>of() : knowledgeSources.indexedSources(knowledgeCandidates);
        try {
            reranked = rerank(plan.plan().rewrittenQuery(), knowledgeCandidates);
        } catch (RuntimeException exception) {
            internalFallbacks.add("RERANKER_PROVIDER_ERROR");
            reranked = new Reranked(knowledgeCandidates,
                    RerankerProvider.RerankOutcome.skipped("PROVIDER_ERROR", knowledgeCandidates.size()));
        }
        var coverage = rag.selectCoverage(reranked.hits(), knowledgeResult.factCandidates(), 12);
        var knowledgeHits = coverage.hits();
        timings.put("rerankMs", rag.elapsedMs(stage));
        var webResult = collectWebSearch(webTasks);
        timings.put("webSearchMs", webResult.trace().webSearchMs());
        timings.put("retrievalTotalMs", rag.elapsedMs(started));
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
        var base = new Retrieval(plan.plan(), knowledgeHits, data, dataResult.detail(), webResult.hits(), new Trace(
                plan.status(), plan.model(), plan.thinkingEnabled(), knowledgeResult.trace().strategy(), knowledgeResult.trace().bm25Candidates(),
                knowledgeResult.trace().vectorCandidates(), knowledgeResult.trace().mergedCandidates(),
                knowledgeResult.trace().variantCount(), data.size(), reranked.outcome().status(), fallbacks,
                dataResult.queryCount(), List.of(
                        new ChannelTrace("KNOWLEDGE_KEYWORD_VECTOR", knowledgeChannelStatus, knowledgeResult.trace().mergedCandidates()),
                        new ChannelTrace("STRUCTURED_DATA_ROW", dataResult.status(), allDataEvidence.size()),
                        new ChannelTrace("DATA_CENTER_ROW", rag.scopedDataStatus(searchData,
                                accessScope.dataScope(), dataCenterHitCount, dataResult), dataCenterHitCount),
                        new ChannelTrace("EXPERIMENT_ROW", rag.scopedDataStatus(searchData,
                                accessScope.experimentScope(), experimentHitCount, dataResult), experimentHitCount),
                        new ChannelTrace("WEB_PUBLIC", webResult.trace().status(), webResult.hits().size())),
                timings, rag.rerankTrace(reranked.outcome()), coverage.coverage(), knowledgeResult.trace().queries(),
                webResult.trace()));
        return enrichQa(base,authorizedKnowledgeSources);
    }

    private Retrieval enrichQa(Retrieval base,Map<UUID,com.jsd.aird.kb.api.ResearchKnowledgeFacade.SourceRef> refs) {
        var trace=new LinkedHashMap<String,Object>();trace.put("profile","qa");trace.put("policy_hash",policy.fingerprint());
        trace.put("rewrite_model_calls",Set.of("SKIPPED_CONFIG","MODEL_UNAVAILABLE").contains(base.trace().rewriteStatus())?0:1);
        if(knowledgeSources==null||base.knowledgeHits().isEmpty())return new Retrieval(base.plan(),base.knowledgeHits(),base.dataHits(),base.dataDetail(),base.webHits(),base.trace(),List.of(),Map.of(),trace);
        var parents=policy.parents ? knowledgeSources.parents(base.knowledgeHits().stream().map(h->refs.get(h.chunkId())).toList(),policy.parentCount,policy.parentChars,
            Math.min(policy.parentBudget,(RetrievalContextSupport.answerDataHits(base).isEmpty()?18000:9000)/3))
            : new com.jsd.aird.kb.api.ResearchKnowledgeFacade.Result(List.of(),Map.of("parent_status","SKIPPED_CONFIG"),null);
        trace.putAll(parents.trace());
        return new Retrieval(base.plan(),base.knowledgeHits(),base.dataHits(),base.dataDetail(),base.webHits(),base.trace(),parents.context_candidates(),refs,trace);
    }

    private WebSearchResult collectWebSearch(WebTasks tasks) {
        if (!tasks.requested()) return new WebSearchResult(List.of(), new WebTrace(
                false, false, tasks.initialStatus(), 0, 0, 0, 0, ""));
        if (tasks.futures().isEmpty()) return new WebSearchResult(List.of(), new WebTrace(
                true, false, tasks.initialStatus(), rag.elapsedMs(tasks.startedNanos()), 0, 0, 0,
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
        return new WebSearchResult(hits, new WebTrace(true, !hits.isEmpty(), status, rag.elapsedMs(tasks.startedNanos()),
                tasks.futures().size(), hits.size(), providerResponseMs, failureReason));
    }

    private List<WebHit> fuseWebResults(List<WebSearchProvider.SearchResponse> responses) {
        var candidates = new LinkedHashMap<String, WebCandidate>();
        for (var response : responses) {
            for (var index = 0; index < response.results().size(); index++) {
                var result = response.results().get(index);
                var url = rag.canonicalWebUrl(result.url());
                var content = rag.boundedWebText(result.content(), 2_000);
                if (url.isBlank() || content.isBlank()) continue;
                var title = rag.boundedWebText(result.title(), 300);
                var siteName = URI.create(url).getHost();
                var rrf = com.jsd.aird.shared.retrieval.RankFusion.contribution(policy.rrfK, index+1);
                var current = candidates.get(url);
                if (current == null) {
                    candidates.put(url, new WebCandidate(title.isBlank() ? siteName : title, siteName, url, content,
                            Math.max(0d, result.score()), rrf, rag.normalizedDate(result.publishedAt())));
                } else {
                    var useNew = result.score() > current.providerScore();
                    candidates.put(url, new WebCandidate(useNew && !title.isBlank() ? title : current.title(),
                            current.siteName(), url, useNew ? content : current.content(),
                            Math.max(current.providerScore(), Math.max(0d, result.score())), current.rrfScore() + rrf,
                            useNew ? rag.normalizedDate(result.publishedAt()) : current.publishedAt()));
                }
            }
        }
        var fetchedAt = Instant.now().toString();
        var seenContent = new LinkedHashSet<String>();
        return candidates.values().stream()
                .sorted(Comparator.comparingInt((WebCandidate item) -> rag.webAuthorityTier(item.url())).reversed()
                        .thenComparing(Comparator.comparingDouble(WebCandidate::rrfScore).reversed())
                        .thenComparing(Comparator.comparingDouble(WebCandidate::providerScore).reversed()))
                .filter(item -> seenContent.add(rag.sha256(item.content())))
                .limit(rag.maxWebCandidates)
                .map(item -> new WebHit(item.title(), item.siteName(), item.url(), item.content(),
                        item.rrfScore(), item.providerScore(), item.publishedAt(), fetchedAt, rag.sha256(item.content())))
                .toList();
    }

    /**
     * Gives public authorities and academic institutions a generic priority without
     * maintaining product, manufacturer or subject-matter domain lists. Relevance
     * ordering is preserved inside the same authority tier.
     */
    private Reranked rerank(String query,List<KnowledgeSearchFacade.SearchHit> hits) {
        var ranked=rank(query,hits,h->h.chunkId().toString(),KnowledgeSearchFacade.SearchHit::content,
            KnowledgeSearchFacade.SearchHit::rrfScore,policy.qa.rerank());
        var admitted=hits.stream().limit(policy.rerankTop).map(h->h.chunkId().toString()).collect(java.util.stream.Collectors.toSet());
        var scores=new HashMap<String,Double>();
        if("SUCCEEDED".equals(ranked.outcome().status()))for(var item:ranked.outcome().documents())
            if(admitted.contains(item.id())&&Double.isFinite(item.score()))scores.merge(item.id(),item.score(),Math::max);
        var projected=ranked.hits().stream().map(h->scores.containsKey(h.chunkId().toString())
            ?h.withScores(h.retrievalScore(),h.rrfScore(),scores.get(h.chunkId().toString())):h).toList();
        return new Reranked(projected,ranked.outcome());
    }

    public com.fasterxml.jackson.databind.node.ObjectNode research(com.fasterxml.jackson.databind.node.ObjectNode scope,
            com.fasterxml.jackson.databind.node.ObjectNode args,boolean privateMode) {
        var started=System.nanoTime();
        var original=args.path("query").asText().strip().replaceAll("\\s+", " ");var mode=args.path("match_mode").asText("auto");
        com.jsd.aird.kb.api.ResearchTextMatch.terms(original);
        if(!Set.of("auto","phrase","all","any").contains(mode))throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.BAD_REQUEST);
        int offset=args.path("offset").asInt();if(offset<0||offset>100000)throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.BAD_REQUEST);
        var actor=com.jsd.aird.shared.security.ActorContext.required();
        var scopeIdentity=mapper.createObjectNode();
        for(var key:List.of("documents","scope_mode","private_source_ids","conversation_id"))if(scope.has(key))scopeIdentity.set(key,scope.get(key));
        scopeIdentity.put("actor",actor.userId().toString()).put("organization",actor.organizationId().toString());
        var scopeHash=com.jsd.aird.ai.api.EvidenceProtocol.sha(scopeIdentity.toString());
        var queryHash=com.jsd.aird.ai.api.EvidenceProtocol.sha(original.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ")+"\n"+mode+"\n"+args.path("cursor").asText("")+"\n"+privateMode+"\n"+args.path("document_id").asText(""));
        var trace=new LinkedHashMap<String,Object>();trace.put("profile","research");trace.put("policy_hash",policy.fingerprint());
        trace.put("rewrite_status","SKIPPED_CONFIG");trace.put("rewrite_model_calls",0);trace.put("fulltext_coverage",false);
        List<com.jsd.aird.kb.api.ResearchKnowledgeFacade.Candidate> ordered;
        String nextCursor=null;
        com.fasterxml.jackson.databind.node.ObjectNode snapshot;
        boolean replay=args.path("ranking_snapshot").isObject();
        if(replay) {
            snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)args.path("ranking_snapshot");
            if(!policy.fingerprint().equals(snapshot.path("policy_hash").asText())||!scopeHash.equals(snapshot.path("scope_hash").asText())||!queryHash.equals(snapshot.path("query_hash").asText()))
                throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.VERSION_CONFLICT,"RETRIEVAL_SNAPSHOT_CHANGED: restart search at offset 0");
            var handles=snapshot.path("handles");
            if(!handles.isArray()||handles.size()>100000)throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.BAD_REQUEST);
            trace.putAll(mapper.convertValue(snapshot.path("trace"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
            trace.put("rewrite_model_calls",0);trace.put("snapshot_replay",true);trace.put("rerank_calls",0);trace.put("embedding_calls",0);
            nextCursor=snapshot.path("next_cursor").asText(null);
            var page=mapper.createArrayNode();for(int i=offset;i<Math.min(handles.size(),offset+policy.pageSize);i++)page.add(handles.get(i));
            if(privateMode) {
                var request=args.deepCopy();request.set("handles",page);request.put("conversation_id",scope.path("conversation_id").asText());
                ordered=new ArrayList<>();for(var item:snapshotRead(()->privateSources.replay(request)))ordered.add(mapper.convertValue(item,com.jsd.aird.kb.api.ResearchKnowledgeFacade.Candidate.class));
            } else {
                var values=new ArrayList<com.jsd.aird.kb.api.ResearchKnowledgeFacade.Handle>();
                for(var item:page)values.add(mapper.convertValue(item,com.jsd.aird.kb.api.ResearchKnowledgeFacade.Handle.class));
                for(var handle:values)checkSelectedScope(scope,handle.source());
                ordered=snapshotRead(()->knowledgeSources.replay(values));
            }
            trace.put("candidate_count",handles.size());
            if(!privateMode&&"AUTOMATIC".equals(scope.path("scope_mode").asText()))
                knowledgeSources.validateWindow(args.path("cursor").asText(null),snapshot.path("trace").path("window_revision").asText());
        } else {
            String query=original;
            if(policy.research.rewrite()&&"auto".equals(mode)) {
                if(!args.path("rewrite_budget_reserved").asBoolean())throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.BAD_REQUEST,"RETRIEVAL_REWRITE_BUDGET_REQUIRED");
                var rewritten=rag.rewrite.rewrite(original,List.of(),false);
                trace.put("rewrite_status",rewritten.status());trace.put("rewrite_model_calls","MODEL_UNAVAILABLE".equals(rewritten.status())?0:1);
                try {com.jsd.aird.kb.api.ResearchTextMatch.terms(rewritten.plan().rewrittenQuery());query=rewritten.plan().rewrittenQuery();}
                catch(RuntimeException error){trace.put("rewrite_status","INVALID_MODEL_OUTPUT");}
            }
            trace.put("effective_query",query);
            var recallStarted=System.nanoTime();
            var channels=new ArrayList<com.jsd.aird.shared.retrieval.RankFusion.Channel<com.jsd.aird.kb.api.ResearchKnowledgeFacade.Candidate>>();
            boolean literalOnly;
            if(privateMode) {
                boolean selected=false;for(var id:scope.path("private_source_ids"))if(id.asText().equals(args.path("document_id").asText()))selected=true;
                if(!selected)throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.DATA_SCOPE_DENIED);
                var request=args.deepCopy();request.put("offset",0).put("conversation_id",scope.path("conversation_id").asText()).put("query",query);
                var raw=privateSources.recall(request);var candidates=new ArrayList<com.jsd.aird.kb.api.ResearchKnowledgeFacade.Candidate>();
                for(var value:raw.path("candidates"))candidates.add(mapper.convertValue(value,com.jsd.aird.kb.api.ResearchKnowledgeFacade.Candidate.class));
                channels.add(new com.jsd.aird.shared.retrieval.RankFusion.Channel<>("PRIVATE_LEXICAL",1,candidates));literalOnly=true;
                trace.put("source_strategy","IMMUTABLE_PRIVATE_NODES");
            } else {
                var refs=new ArrayList<com.jsd.aird.kb.api.ResearchKnowledgeFacade.DocumentRef>();
                for(var ref:scope.path("documents"))refs.add(mapper.convertValue(ref,com.jsd.aird.kb.api.ResearchKnowledgeFacade.DocumentRef.class));
                var options=new com.jsd.aird.kb.api.ResearchKnowledgeFacade.RecallOptions(policy.research.bm25(),policy.research.vector(),policy.research.metadata(),48);
                var raw="AUTOMATIC".equals(scope.path("scope_mode").asText())?knowledgeSources.discoverRecall(query,args.path("cursor").asText(null),mode,options):knowledgeSources.recall(refs,query,mode,options);
                if(raw==null)throw new IllegalStateException("Source adapter did not return raw recall");
                trace.putAll(raw.trace());nextCursor=raw.next_cursor();literalOnly=Boolean.TRUE.equals(raw.trace().get("literal_only"));
                for(var channel:raw.channels())channels.add(new com.jsd.aird.shared.retrieval.RankFusion.Channel<>(channel.name(),channel.weight(),channel.candidates()));
            }
            if(channels.size()==1)ordered=channels.getFirst().hits();
            else ordered=com.jsd.aird.shared.retrieval.RankFusion.fuse(channels,c->com.jsd.aird.ai.api.EvidenceProtocol.evidenceId(mapper.valueToTree(c.source())),policy.rrfK).stream()
                .map(r->new com.jsd.aird.kb.api.ResearchKnowledgeFacade.Candidate(r.value().text(),r.value().source(),r.score(),r.value().node_offset(),r.value().role(),r.value().table_context())).toList();
            trace.put("recall_fusion_ms",rag.elapsedMs(recallStarted));
            var rankStarted=System.nanoTime();
            var ranking=rank(query,ordered,c->com.jsd.aird.ai.api.EvidenceProtocol.evidenceId(mapper.valueToTree(c.source())),
                com.jsd.aird.kb.api.ResearchKnowledgeFacade.Candidate::text,c->c.score(),policy.research.rerank()&&!literalOnly&&"auto".equals(mode));
            ordered=ranking.hits();trace.put("reranker",ranking.outcome().status());trace.put("rerank_calls",ranking.outcome().inputCount()>0?1:0);
            trace.put("rerank_ms",rag.elapsedMs(rankStarted));trace.put("rerank_window",Math.min(policy.rerankTop,ordered.size()));
            trace.put("candidate_count",ordered.size());
            snapshot=mapper.createObjectNode().put("policy_hash",policy.fingerprint()).put("scope_hash",scopeHash).put("query_hash",queryHash);
            var handles=snapshot.putArray("handles");
            for(var candidate:ordered)handles.add(mapper.valueToTree(new com.jsd.aird.kb.api.ResearchKnowledgeFacade.Handle(candidate.source(),candidate.score(),candidate.node_offset(),candidate.role())));
            snapshot.set("trace",mapper.valueToTree(trace));if(nextCursor==null)snapshot.putNull("next_cursor");else snapshot.put("next_cursor",nextCursor);
            ordered=ordered.stream().skip(offset).limit(policy.pageSize).toList();
        }
        int total=((Number)trace.getOrDefault("candidate_count",0)).intValue();
        var result=mapper.createObjectNode();result.set("candidates",mapper.valueToTree(ordered));result.set("ranking_snapshot",snapshot);
        trace.put("offset",offset);trace.put("page_order","RANKED_WINDOW_THEN_PAGED");trace.put("retrieval_ms",rag.elapsedMs(started));
        var parents= !privateMode&&policy.parents?knowledgeSources.parents(ordered.stream().map(com.jsd.aird.kb.api.ResearchKnowledgeFacade.Candidate::source).toList(),policy.parentCount,policy.parentChars,
                Math.min(policy.parentBudget,Math.min(18000,Math.max(0,args.path("context_character_budget").asInt(12000)))/3))
            : new com.jsd.aird.kb.api.ResearchKnowledgeFacade.Result(List.of(),Map.of("parent_status",privateMode?"NOT_APPLICABLE":"SKIPPED_CONFIG"),null);
        trace.putAll(parents.trace());trace.put("candidate_count",total);result.set("trace",mapper.valueToTree(trace));result.set("context_candidates",mapper.valueToTree(parents.context_candidates()));
        var failures = String.valueOf(trace.getOrDefault("fallbacks", List.of()));
        if (failures.matches("(?s).*(TIMEOUT|DEADLINE|_ERROR|REJECTED).*")) {
            result.put("retrieval_status", "PARTIAL");
            result.put("retrieval_notice", "部分检索未完成，覆盖不完整。");
        }
        if (!privateMode) {
            var metadataSources = new ArrayList<com.jsd.aird.kb.api.ResearchKnowledgeFacade.SourceRef>();
            ordered.forEach(c -> metadataSources.add(c.source()));
            parents.context_candidates().forEach(c -> metadataSources.add(c.source()));
            result.set("document_metadata", mapper.valueToTree(knowledgeSources.documentMetadata(metadataSources)));
        }
        if(offset+ordered.size()<total)result.put("next_offset",offset+ordered.size());else result.putNull("next_offset");
        if(nextCursor==null)result.putNull("next_cursor");else result.put("next_cursor",nextCursor);
        return result;
    }

    private void checkSelectedScope(com.fasterxml.jackson.databind.node.ObjectNode scope,com.jsd.aird.kb.api.ResearchKnowledgeFacade.SourceRef source) {
        if("AUTOMATIC".equals(scope.path("scope_mode").asText()))return;
        for(var ref:scope.path("documents"))if(ref.path("document_id").asText().equals(source.document_id())&&ref.path("publication_id").asText().equals(source.publication_id()))return;
        throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.DATA_SCOPE_DENIED);
    }

    record Ranked<T>(List<T> hits,RerankerProvider.RerankOutcome outcome) { }
    private <T> T snapshotRead(java.util.function.Supplier<T> read) {
        try {return read.get();}
        catch(com.jsd.aird.shared.error.ApiException error) {
            if(error.errorCode()==com.jsd.aird.shared.error.ApiErrorCode.VERSION_CONFLICT||error.errorCode()==com.jsd.aird.shared.error.ApiErrorCode.NOT_FOUND)
                throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.VERSION_CONFLICT,"RETRIEVAL_SNAPSHOT_CHANGED: restart search at offset 0");
            throw error;
        }
    }
    private <T> Ranked<T> rank(String query,List<T> values,java.util.function.Function<T,String> id,
            java.util.function.Function<T,String> text,java.util.function.ToDoubleFunction<T> rawScore,boolean enabled) {
        if(!enabled||!rag.reranker.isConfigured()||values.isEmpty())return new Ranked<>(values,RerankerProvider.RerankOutcome.skipped(!enabled?"SKIPPED_POLICY":"NOT_CONFIGURED",0));
        var input=values.stream().limit(policy.rerankTop).map(v->new RerankerProvider.RankCandidate(id.apply(v),text.apply(v),rawScore.applyAsDouble(v),rawScore.applyAsDouble(v))).toList();
        RerankerProvider.RerankOutcome outcome;
        try {outcome=rag.reranker.rerank(query,input);}
        catch(RuntimeException error) {return new Ranked<>(values,RerankerProvider.RerankOutcome.skipped("PROVIDER_ERROR",input.size()));}
        if(outcome==null)return new Ranked<>(values,RerankerProvider.RerankOutcome.skipped("EMPTY_RESPONSE",input.size()));
        if(!"SUCCEEDED".equals(outcome.status()))return new Ranked<>(values,outcome);
        var scores=new HashMap<String,Double>();var positions=new HashMap<String,Integer>();
        for(int i=0;i<values.size();i++)positions.putIfAbsent(id.apply(values.get(i)),i);
        var admitted=input.stream().map(RerankerProvider.RankCandidate::id).collect(java.util.stream.Collectors.toSet());
        for(var row:outcome.documents())if(admitted.contains(row.id())&&Double.isFinite(row.score()))scores.merge(row.id(),row.score(),Math::max);
        var result=new ArrayList<>(values);result.sort(Comparator.<T>comparingInt(v->scores.containsKey(id.apply(v))?0:1)
            .thenComparing(Comparator.<T>comparingDouble(v->scores.getOrDefault(id.apply(v),0d)).reversed()).thenComparingInt(v->positions.get(id.apply(v))));
        return new Ranked<>(List.copyOf(result),outcome);
    }

}
