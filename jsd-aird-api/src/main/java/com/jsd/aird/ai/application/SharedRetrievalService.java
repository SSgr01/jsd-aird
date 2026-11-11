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
    private PrivateResearchSourceService privateSourceService;
    @org.springframework.beans.factory.annotation.Autowired
    void privateSourceService(PrivateResearchSourceService service) { this.privateSourceService = service; }
    private ObjectNode privateSourceRead(ObjectNode args) { return privateSourceService.read(args); }
    private AgentHostService host;
    private DataSourceFileSearchFacade data;
    @org.springframework.beans.factory.annotation.Autowired
    void data(DataSourceFileSearchFacade data) { this.data = data; }
    @org.springframework.beans.factory.annotation.Autowired
    void host(AgentHostService host) { this.host = host; }

    public SharedRetrievalService(RetrievalPipeline pipeline, DataSearchAuthorizationService dataAuthorization,
            ResearchKnowledgeFacade knowledge, RerankerProvider reranker, AgentSourceAuthorization sources, ObjectMapper mapper) {
        this.pipeline = pipeline; this.dataAuthorization = dataAuthorization; this.knowledge = knowledge;
        this.reranker = reranker; this.sources = sources; this.mapper = mapper;
    }

    public RagRetrievalService.Retrieval retrieveFixed(UUID organizationId, String question,
            List<AssistantRepository.MessageRow> history, List<UUID> knowledgeCategories, List<UUID> dataCategories,
            boolean web, DataSourceFileSearchFacade.RetrievalAccessScope scope) {
        return pipeline.retrieve(organizationId, question, history, knowledgeCategories, dataCategories, web, scope);
    }

    public ContextCompressionService.Context compress(
            List<com.jsd.aird.kb.api.KnowledgeSearchFacade.SearchHit> knowledgeHits,
            List<DataSourceFileSearchFacade.SourceFileHit> dataHits, int budget) {
        return pipeline.compress(knowledgeHits, dataHits, budget);
    }

    public ObjectNode documents(ObjectNode scope,ObjectNode args) {return pipeline.research(scope,args,false);}
    public ContextCompressionService.Context compress(RagRetrievalService.Retrieval retrieval,int budget) { return pipeline.compress(retrieval,budget); }
    public ObjectNode privateDocuments(ObjectNode scope,ObjectNode args) {return pipeline.research(scope,args,true);}
    public Map<String,Object> configuration() {return pipeline.configuration();}

    /** Direct access to the existing structured reader, without a second model planning the query. */
    public ObjectNode structured(ObjectNode args) {
        var actor = ActorContext.required();
        var access = dataAuthorization.requireRetrievalScopes(actor);
        var file = args.path("file_name").asText("").strip();
        if (file.length() > 256) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        DataSourceFileSearchFacade.DataQueryMode mode;
        try { mode = DataSourceFileSearchFacade.DataQueryMode.valueOf(args.path("mode").asText("RECORD_DETAIL")); }
        catch (IllegalArgumentException error) { throw new ApiException(ApiErrorCode.BAD_REQUEST); }
        if (mode == DataSourceFileSearchFacade.DataQueryMode.NONE) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var query = new DataSourceFileSearchFacade.DataDetailQuery(mode, file,
            file.isBlank() ? DataSourceFileSearchFacade.DataFileScope.AUTO : DataSourceFileSearchFacade.DataFileScope.EXPLICIT,
            terms(args.path("record_terms")), terms(args.path("field_terms")),
            DataSourceFileSearchFacade.RecordTermMatch.ALL, DataSourceFileSearchFacade.EvidenceFilter.ANY,
            5, 20, 50, DataSourceFileSearchFacade.ContextMode.ANALYTIC, 16, 5, "VALUE");
        var detail = data.queryDetails(actor.organizationId(), query, List.of(), access);
        var result = mapper.createObjectNode().put("resolution", detail.resolution().name())
            .put("needs_input", detail.requiresFileSelection()).put("truncated", detail.truncated())
            .put("original_name", detail.originalName());
        result.set("file_candidates", mapper.valueToTree(detail.candidates()));
        result.put("file_candidates_truncated", detail.candidatesTruncated());
        var hits = new LinkedHashMap<UUID,DataSourceFileSearchFacade.SourceFileHit>();
        for (var hit : detail.hits()) hits.put(hit.hitId(), hit);
        var contexts = result.putArray("records");
        for (var record : detail.recordContexts()) {
            contexts.addObject().put("record_key", record.recordKey()).put("sheet", record.sheetName())
                .put("comparable", record.comparable()).put("comparison_reason", record.comparisonReason())
                .put("context_truncated", record.contextTruncated());
            for (var hit : record.targetHits()) hits.putIfAbsent(hit.hitId(), hit);
            for (var hit : record.contextHits()) hits.putIfAbsent(hit.hitId(), hit);
        }
        var candidates = result.putArray("candidates");
        for (var hit : hits.values()) candidates.add(dataCandidate("D" + (candidates.size()+1), hit));
        return result;
    }

    private List<String> terms(JsonNode values) {
        if (values.isMissingNode() || values.isNull()) return List.of();
        if (!values.isArray() || values.size() > 8) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var result = new ArrayList<String>();
        for (var value : values) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 128) throw new ApiException(ApiErrorCode.BAD_REQUEST);
            result.add(value.asText().strip());
        }
        return result;
    }

    @Override public ObjectNode retrieve(ObjectNode request) {
        var question = request.path("question").asText().strip();
        if (question.isBlank() || question.length() > 12000 || request.path("history").size() > 20)
            throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var actor = ActorContext.required();
        var scope = request.path("scope");
        var knowledgeCategories = ids(scope.path("knowledge_category_ids"));
        var dataCategories = ids(scope.path("data_category_ids"));
        var automatic = knowledgeCategories.isEmpty() && dataCategories.isEmpty();
        var access = automatic ? dataAuthorization.resolveRetrievalScopes(actor)
            : dataCategories.isEmpty() ? DataSourceFileSearchFacade.RetrievalAccessScope.denied()
            : dataAuthorization.requireRetrievalScopes(actor);
        var history = new ArrayList<AssistantRepository.MessageRow>();
        for (var turn : request.path("history")) {
            var role = turn.path("role").asText().toUpperCase(Locale.ROOT);
            var text = turn.path("content").asText();
            if (!Set.of("USER", "ASSISTANT", "SUMMARY").contains(role) || text.length() > 4000) throw new ApiException(ApiErrorCode.BAD_REQUEST);
            history.add(new AssistantRepository.MessageRow(role, text));
        }
        var contextRequest = request.deepCopy(); contextRequest.put("profile", "agent"); contextRequest.set("retrieval_scope", scope);
        var context = host.context(contextRequest);
        var filter = scope.path("knowledge_documents");
        ObjectNode result;
        if (filter.isArray()) {
            // Explicit per-request versions bypass current-publication indexes.
            var args = mapper.createObjectNode().put("query", question).put("match_mode", "any");
            var found = pipeline.research(context, args, false);
            result = mapper.createObjectNode().put("schema_version", 1).put("context", "");
            result.set("candidates", found.path("candidates")); result.set("trace", found.path("trace"));
            result.set("document_metadata", found.path("document_metadata"));
        } else {
            var retrieval = pipeline.retrieveAgentQuery(actor.organizationId(), question, history, knowledgeCategories,
                dataCategories, scope.path("web_search_enabled").asBoolean(false), access);
            result = project(question, retrieval);
        }
        var candidates = (com.fasterxml.jackson.databind.node.ArrayNode) result.path("candidates");
        // Direct references retain their exact original versions, independently of automatic search.
        for (var ref : request.path("context_refs")) {
            if (filter.isArray() && "KB_PUBLICATION".equals(ref.path("kind").asText())) {
                boolean allowed = false;
                for (var item : filter) if (item.path("document_id").asText().equals(ref.path("resource_id").asText())
                    && item.path("publication_id").asText().equals(ref.path("publication_id").asText())) allowed = true;
                if (!allowed) continue;
            }
            var args = mapper.createObjectNode(); args.set("ref", ref);
            for (var candidate : host.contextResource(args).path("candidates")) candidates.add(candidate);
        }
        for (var id : request.path("private_source_ids")) {
            var args = mapper.createObjectNode().put("document_id", id.asText()).put("conversation_id", request.path("conversation_id").asText()).put("offset", 0).put("limit", 8);
            for (var candidate : privateSourceRead(args).path("candidates")) candidates.add(candidate);
        }
        var text = new StringBuilder(result.path("context").asText());
        int number = 1;
        for (var candidate : candidates) if (!candidate.has("label")) {
            ((ObjectNode) candidate).put("label", "W" + number++);
            text.append("\n\n[evidenceRef=").append(candidate.path("label").asText()).append("] ").append(candidate.path("text").asText());
        }
        if (text.length() > 32000) throw new ApiException(ApiErrorCode.BAD_REQUEST, "引用内容超出本次问答预算，请减少资料或使用任务与研究");
        result.put("context", text.toString()); result.set("document_metadata", documentMetadata(candidates));
        return result;
    }

    private ObjectNode project(String question, RagRetrievalService.Retrieval retrieval) {
        var data = RetrievalContextSupport.answerDataHits(retrieval);
        var compressed = pipeline.compress(retrieval, 18000);
        var candidates = preparedCandidates(retrieval, compressed.evidenceRefs());
        var context = new StringBuilder(compressed.text());
        for (int i = 0; i < retrieval.webHits().size(); i++) {
            var hit = retrieval.webHits().get(i);
            var label = "E" + (i + 1);
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
        context.append("\n\n结构化查询状态：").append(RetrievalContextSupport.dataLookupContext(retrieval.dataDetail(), channel));
        var formula = RetrievalContextSupport.formulaConsistencyContext(question, data);
        if (!formula.isBlank()) context.append("\n\n确定性配方核对：").append(formula);
        var ambiguity = RetrievalContextSupport.detectFieldAmbiguity(question, data);
        if (ambiguity != null) context.append("\n\n字段含义有歧义，请让用户明确字段，不可自行选择：").append(mapper.valueToTree(ambiguity));
        if (context.length() > 32000) throw new ApiException(ApiErrorCode.BAD_REQUEST, "上下文预算不足，请缩小问题范围");
        var result = mapper.createObjectNode().put("schema_version", 1).put("context", context.toString());
        result.set("candidates", candidates);
        result.set("document_metadata", documentMetadata(candidates));
        ObjectNode trace=mapper.valueToTree(retrieval.trace()); trace.set("pipeline",mapper.valueToTree(retrieval.pipelineTrace()));result.set("trace",trace);
        result.put("needs_input", ambiguity != null);
        return result;
    }

    public com.fasterxml.jackson.databind.node.ArrayNode preparedCandidates(
            RagRetrievalService.Retrieval retrieval, Collection<String> allowedRefs) {
        var result = mapper.createArrayNode();
        for (int i = 0; i < retrieval.knowledgeHits().size(); i++) {
            var label = "K" + (i + 1);
            if (!allowedRefs.contains(label)) continue;
            var hit = retrieval.knowledgeHits().get(i);
            var ref=retrieval.sourceRefs().get(hit.chunkId());
            result.add(candidate(label, hit.content(), mapper.valueToTree(ref==null?knowledge.indexedSource(hit):ref)));
        }
        for (int i=0; i<retrieval.contextCandidates().size(); i++) {
            var label="K"+(retrieval.knowledgeHits().size()+i+1);
            if(!allowedRefs.contains(label))continue;
            var parent=retrieval.contextCandidates().get(i);
            result.add(candidate(label,parent.text(),mapper.valueToTree(parent.source())));
        }
        var data = RetrievalContextSupport.answerDataHits(retrieval);
        for (int i = 0; i < data.size(); i++) {
            var label = "D" + (i + 1);
            if (!allowedRefs.contains(label)) continue;
            result.add(dataCandidate(label, data.get(i)));
        }
        for (int i = 0; i < retrieval.webHits().size(); i++) {
            var label = "E" + (i + 1);
            if (!allowedRefs.contains(label)) continue;
            var hit = retrieval.webHits().get(i);
            var source = mapper.createObjectNode().put("kind", "WEB_RESULT").put("document_id", sha(hit.url()))
                .put("version_id", sha(hit.content())).put("source_node_key", sha(hit.url()))
                .put("title", hit.title()).put("url", hit.url()).put("content_sha256", sha(hit.content()));
            source.putObject("anchor").put("url", hit.url()).put("fetchedAt", hit.fetchedAt());
            sources.one(source);
            result.add(candidate(label, hit.content(), source));
        }
        if (result.size() != allowedRefs.size()) throw new ApiException(ApiErrorCode.VERSION_CONFLICT,
            "回答来源已变化，请重新检索");
        return result;
    }

    private ObjectNode dataCandidate(String label, DataSourceFileSearchFacade.SourceFileHit hit) {
        boolean experiment = "EXPERIMENT".equals(hit.sourceOwner());
        var ownerId = experiment ? hit.experimentId() : hit.importJobId();
        if (ownerId == null || hit.fileObjectId() == null || hit.hitId() == null)
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        var source = mapper.createObjectNode().put("kind", "DATA_SOURCE_FILE")
            .put("document_id", hit.fileObjectId().toString())
            .put("version_id", (hit.experimentVersionId() == null ? hit.fileObjectId() : hit.experimentVersionId()).toString())
            .put("resource_type", experiment ? "EXPERIMENT" : "DATA").put("resource_id", ownerId.toString())
            .put("source_node_key", hit.hitId().toString()).put("title", hit.originalName())
            .put("content_sha256", sha(hit.content()));
        source.putObject("anchor").put("fileId", hit.fileObjectId().toString())
            .put("locator", hit.sourceLocator()).put("sheetName", hit.sheetName()).put("cellAddress", hit.cellAddress());
        sources.one(source);
        return candidate(label, hit.content(), source).put("record_key", hit.recordKey())
            .put("field", hit.fieldName()).put("value", hit.fieldValue()).put("unit", hit.unit())
            .put("value_type", hit.valueType()).put("condition", hit.declaredCondition()).put("source_status", hit.sourceStatus());
    }

    public com.fasterxml.jackson.databind.node.ArrayNode documentMetadata(JsonNode candidates) {
        var refs = new ArrayList<ResearchKnowledgeFacade.SourceRef>();
        for (var candidate : candidates) if ("KB_PUBLICATION".equals(candidate.path("source").path("kind").asText()))
            refs.add(mapper.convertValue(candidate.path("source"), ResearchKnowledgeFacade.SourceRef.class));
        var result = mapper.createArrayNode();
        for (var value : knowledge.documentMetadata(refs)) {
            var item = (ObjectNode) value.deepCopy();
            var summary = item.path("abstract").asText();
            if (summary.length() > 800) { item.put("abstract", summary.substring(0, 800)); item.put("abstract_truncated", true); }
            result.add(item);
        }
        // Raw evidence keeps its existing budget; bibliography summaries are the first to shrink.
        if (result.toString().length() > 6000) for (var value : result)
            if (value.has("abstract")) { ((ObjectNode) value).remove("abstract"); ((ObjectNode) value).put("abstract_truncated", true); }
        return result;
    }

    private ObjectNode candidate(String label, String text, JsonNode source) {
        return com.jsd.aird.ai.api.EvidenceProtocol.candidate(label, text, source);
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
