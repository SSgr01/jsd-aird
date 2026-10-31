package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.api.RetrievalCapability;
import com.jsd.aird.ai.application.port.AssistantRepository;
import com.jsd.aird.ai.application.port.RerankerProvider;
import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;

/** One evidence transport with explicit fixed-QA and scoped-document strategies. */
@Service
public class SharedRetrievalService implements RetrievalCapability {
    private final RetrievalPipeline pipeline;
    private final DataSearchAuthorizationService dataAuthorization;
    private final ResearchKnowledgeFacade knowledge;
    private final RerankerProvider reranker;
    private final AgentSourceAuthorization sources;
    private final ObjectMapper mapper;

    public SharedRetrievalService(RetrievalPipeline pipeline, DataSearchAuthorizationService dataAuthorization,
            ResearchKnowledgeFacade knowledge, RerankerProvider reranker, AgentSourceAuthorization sources, ObjectMapper mapper) {
        this.pipeline = pipeline; this.dataAuthorization = dataAuthorization; this.knowledge = knowledge;
        this.reranker = reranker; this.sources = sources; this.mapper = mapper;
    }

    public ResearchKnowledgeFacade.Result documents(ObjectNode scope, ObjectNode args) {
        var refs = new ArrayList<ResearchKnowledgeFacade.DocumentRef>();
        for (var ref : scope.path("documents")) refs.add(mapper.convertValue(ref, ResearchKnowledgeFacade.DocumentRef.class));
        var result = "AUTOMATIC".equals(scope.path("scope_mode").asText())
            ? knowledge.discover(args.path("query").asText(), args.path("offset").asInt(), args.path("cursor").asText(null))
            : knowledge.search(refs, args.path("query").asText(), args.path("offset").asInt());
        if (result.candidates().isEmpty() || !reranker.isConfigured()) return result;
        var candidates = new ArrayList<RerankerProvider.RankCandidate>();
        for (int i = 0; i < result.candidates().size(); i++) {
            var item = result.candidates().get(i);
            candidates.add(new RerankerProvider.RankCandidate(Integer.toString(i), item.text(), item.score(), item.score()));
        }
        var ranking = reranker.rerank(args.path("query").asText(), candidates);
        var scores = new HashMap<Integer, Double>();
        for (var item : ranking.documents()) try { scores.put(Integer.parseInt(item.id()), item.score()); } catch (NumberFormatException ignored) { }
        var sorted = new ArrayList<ResearchKnowledgeFacade.Candidate>();
        for (int i = 0; i < result.candidates().size(); i++) {
            var item = result.candidates().get(i);
            sorted.add(new ResearchKnowledgeFacade.Candidate(item.text(), item.source(), scores.getOrDefault(i, item.score())));
        }
        sorted.sort(Comparator.comparingDouble(ResearchKnowledgeFacade.Candidate::score).reversed());
        var trace = new LinkedHashMap<>(result.trace()); trace.put("reranker", ranking.status());
        return new ResearchKnowledgeFacade.Result(sorted, trace, result.next_offset(), result.next_cursor());
    }

    @Override public ObjectNode retrieve(ObjectNode request) {
        var question = request.path("question").asText().strip();
        if (question.isBlank() || question.length() > 12000 || request.path("history").size() > 20)
            throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var actor = ActorContext.required();
        var scope = request.path("scope");
        var knowledgeCategories = ids(scope.path("knowledge_category_ids"));
        var dataCategories = ids(scope.path("data_category_ids"));
        var access = dataCategories.isEmpty() ? DataSourceFileSearchFacade.RetrievalAccessScope.denied()
            : dataAuthorization.requireRetrievalScopes(actor);
        var history = new ArrayList<AssistantRepository.MessageRow>();
        for (var turn : request.path("history")) {
            var role = turn.path("role").asText().toUpperCase(Locale.ROOT);
            var text = turn.path("content").asText();
            if (!Set.of("USER", "ASSISTANT", "SUMMARY").contains(role) || text.length() > 4000) throw new ApiException(ApiErrorCode.BAD_REQUEST);
            history.add(new AssistantRepository.MessageRow(role, text));
        }
        var retrieval = pipeline.retrieve(actor.organizationId(), question, history, knowledgeCategories, dataCategories,
            scope.path("web_search_enabled").asBoolean(false), access);
        var data = AssistantService.answerDataHits(retrieval);
        var compressed = pipeline.compress(retrieval.knowledgeHits(), data, 18000);
        var candidates = mapper.createArrayNode();
        for (int i = 0; i < retrieval.knowledgeHits().size(); i++) {
            var label = "K" + (i + 1);
            if (!compressed.evidenceRefs().contains(label)) continue;
            var hit = retrieval.knowledgeHits().get(i);
            candidates.add(candidate(label, hit.content(), mapper.valueToTree(knowledge.indexedSource(hit))));
        }
        for (int i = 0; i < data.size(); i++) {
            var label = "D" + (i + 1);
            if (!compressed.evidenceRefs().contains(label)) continue;
            var hit = data.get(i);
            boolean experiment = "EXPERIMENT".equals(hit.sourceOwner());
            var ownerId = experiment ? hit.experimentId() : hit.importJobId();
            if (ownerId == null || hit.fileObjectId() == null || hit.hitId() == null) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
            var source = mapper.createObjectNode().put("kind", "DATA_SOURCE_FILE").put("document_id", hit.fileObjectId().toString())
                .put("version_id", (hit.experimentVersionId() == null ? hit.fileObjectId() : hit.experimentVersionId()).toString())
                .put("resource_type", experiment ? "EXPERIMENT" : "DATA").put("resource_id", ownerId.toString())
                .put("source_node_key", hit.hitId().toString()).put("title", hit.originalName()).put("content_sha256", sha(hit.content()));
            source.putObject("anchor").put("fileId", hit.fileObjectId().toString()).put("locator", hit.sourceLocator())
                .put("sheetName", hit.sheetName()).put("cellAddress", hit.cellAddress());
            sources.one(source);
            candidates.add(candidate(label, hit.content(), source));
        }
        var context = new StringBuilder(compressed.text());
        for (int i = 0; i < retrieval.webHits().size(); i++) {
            var hit = retrieval.webHits().get(i);
            var label = "W" + (i + 1);
            var text = hit.content();
            if (text == null || text.isBlank() || context.length() + text.length() > 26000) continue;
            var source = mapper.createObjectNode().put("kind", "WEB_RESULT").put("document_id", sha(hit.url()))
                .put("version_id", sha(text)).put("source_node_key", sha(hit.url())).put("title", hit.title())
                .put("url", hit.url()).put("content_sha256", sha(text));
            source.putObject("anchor").put("url", hit.url()).put("fetchedAt", hit.fetchedAt());
            sources.one(source);
            candidates.add(candidate(label, text, source));
            context.append("\n\n[evidenceRef=").append(label).append("] ").append(text);
        }
        var channel = retrieval.trace().channels().stream().filter(item -> "STRUCTURED_DATA_ROW".equals(item.channel()))
            .map(RagRetrievalService.ChannelTrace::status).findFirst().orElse("UNKNOWN");
        context.append("\n\n结构化查询状态：").append(AssistantService.dataLookupContext(retrieval.dataDetail(), channel));
        var formula = AssistantService.formulaConsistencyContext(question, data);
        if (!formula.isBlank()) context.append("\n\n确定性配方核对：").append(formula);
        var ambiguity = AssistantService.detectFieldAmbiguity(question, data);
        if (ambiguity != null) context.append("\n\n字段含义有歧义，请让用户明确字段，不可自行选择：").append(mapper.valueToTree(ambiguity));
        if (context.length() > 32000) throw new ApiException(ApiErrorCode.BAD_REQUEST, "上下文预算不足，请缩小问题范围");
        var result = mapper.createObjectNode().put("schema_version", 1).put("context", context.toString());
        result.set("candidates", candidates); result.set("trace", mapper.valueToTree(retrieval.trace()));
        result.put("needs_input", ambiguity != null);
        return result;
    }

    private ObjectNode candidate(String label, String text, JsonNode source) {
        return mapper.createObjectNode().put("label", label).put("text", text).set("source", source);
    }

    private List<UUID> ids(JsonNode values) {
        if (values.isMissingNode() || values.isNull()) return List.of();
        if (!values.isArray() || values.size() > 100) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var result = new ArrayList<UUID>(); for (var value : values) result.add(UUID.fromString(value.asText())); return result;
    }

    private String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
