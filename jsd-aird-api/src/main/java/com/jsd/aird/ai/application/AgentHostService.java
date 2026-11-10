package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.mdm.api.AgentProjectFacade;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.stereotype.Service;

/** JSD adapter, not part of the reusable runtime. */
@Service
public class AgentHostService {
    private final LiteratureResearchService research;
    private final ResearchKnowledgeFacade knowledge;
    private final AgentProjectFacade projects;
    private final AgentSourceAuthorization sources;
    private final AuthorizationService authorization;
    private final ObjectMapper mapper;
    private final PrivateResearchSourceService privateSources;
    private RetrievalPipeline retrieval;
    private com.jsd.aird.rnd.api.AgentExperimentFacade experiments;

    @org.springframework.beans.factory.annotation.Autowired
    void experiments(com.jsd.aird.rnd.api.AgentExperimentFacade experiments) { this.experiments = experiments; }

    @org.springframework.beans.factory.annotation.Autowired
    void retrievalPolicy(RetrievalPipeline retrieval) { this.retrieval = retrieval; }

    public AgentHostService(LiteratureResearchService research, ResearchKnowledgeFacade knowledge, AgentProjectFacade projects,
            AgentSourceAuthorization sources, AuthorizationService authorization, ObjectMapper mapper, PrivateResearchSourceService privateSources) {
        this.research = research; this.knowledge = knowledge; this.projects = projects;
        this.sources = sources; this.authorization = authorization; this.mapper = mapper;
        this.privateSources = privateSources;
    }

    public ObjectNode context(ObjectNode request) {
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "ai.use", "AI", null, "READ"));
        var profile = request.path("profile").asText("agent");
        if (!"agent".equals(profile)) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var result = mapper.createObjectNode().put("scope_mode", "AUTOMATIC").put("profile", profile);
        result.put("conversation_id", request.path("conversation_id").asText());
        if (retrieval != null) result.set("retrieval_policy", mapper.valueToTree(retrieval.configuration()));
        result.putArray("documents"); result.putArray("notes");
        if (request.hasNonNull("topic_id")) {
            var topic = research.get("topic", UUID.fromString(request.path("topic_id").asText()));
            for (var field : List.of("id", "title", "goal", "documents", "notes", "scope_mode")) if (topic.has(field)) result.set(field, topic.get(field));
        }
        var refs = request.path("context_refs");
        if (!refs.isMissingNode() && !refs.isNull() && (!refs.isArray() || refs.size() > 100)) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var contexts = result.putArray("context_refs");
        for (var ref : refs) {
            var id = UUID.fromString(ref.path("resource_id").asText());
            if ("KB_PUBLICATION".equals(ref.path("kind").asText())) {
                var publication = UUID.fromString(ref.path("publication_id").asText());
                knowledge.authorize(List.of(new ResearchKnowledgeFacade.DocumentRef(id, publication)));
                ((com.fasterxml.jackson.databind.node.ArrayNode) result.path("documents")).add(mapper.createObjectNode()
                    .put("document_id", id.toString()).put("publication_id", publication.toString()));
            } else if (Set.of("PROJECT", "EXPERIMENT").contains(ref.path("kind").asText())) {
                var record = resource(ref, 0);
                if (!result.has("context_candidates")) result.putArray("context_candidates");
                ((com.fasterxml.jackson.databind.node.ArrayNode) result.path("context_candidates")).addAll(
                    (com.fasterxml.jackson.databind.node.ArrayNode) record.path("candidates"));
            } else throw new ApiException(ApiErrorCode.BAD_REQUEST);
            contexts.add(ref);
        }
        // A reference is an input, not a search boundary. Only the current request's
        // explicit filter selects publications, and every selected version is reauthorized.
        var filter = request.path("retrieval_scope").path("knowledge_documents");
        if (!filter.isMissingNode() && !filter.isNull()) {
            if (!filter.isArray() || filter.isEmpty() || filter.size() > 100) throw new ApiException(ApiErrorCode.BAD_REQUEST);
            var documents = result.putArray("documents");
            for (var value : filter) {
                var ref = mapper.convertValue(value, ResearchKnowledgeFacade.DocumentRef.class);
                knowledge.authorize(List.of(ref)); documents.add(mapper.valueToTree(ref));
            }
            result.put("scope_mode", "SELECTED");
        }
        result.set("retrieval_scope", request.hasNonNull("retrieval_scope") ? request.path("retrieval_scope") : mapper.createObjectNode());
        if (request.path("private_source_ids").isArray() && !request.path("private_source_ids").isEmpty()) {
            if (request.path("private_source_ids").size() > 20) throw new ApiException(ApiErrorCode.BAD_REQUEST);
            var privateDocuments = result.putArray("private_documents");
            var privateIds = result.putArray("private_source_ids");
            for (var sourceId : request.path("private_source_ids")) {
                var source = privateSources.get(UUID.fromString(sourceId.asText()));
                if (!source.path("conversation_id").asText().equals(request.path("conversation_id").asText()))
                    throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED);
                if (!"READY".equals(source.path("status").asText())) result.put("private_sources_ready", false);
                privateIds.add(sourceId.asText());
                privateDocuments.add(mapper.createObjectNode().put("document_id", sourceId.asText())
                    .put("publication_id", source.path("version_id").asText()));
            }
        }
        var packs = result.putArray("pack_ids").add("core");
        {
            packs.add("files").add("literature").add("private-literature").add("retrieval").add("business-data");
            if (projects.available()) packs.add("project-readonly");
            if (experiments != null && experiments.available()) packs.add("experiments");
        }
        var documents = new ArrayList<ResearchKnowledgeFacade.DocumentRef>();
        for (var document : result.path("documents")) documents.add(mapper.convertValue(document, ResearchKnowledgeFacade.DocumentRef.class));
        knowledge.authorize(documents);
        return result;
    }

    public ObjectNode validateEvidence(ObjectNode args) {
        if(!args.path("candidates").isArray()||args.path("candidates").size()>50) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        for(var candidate:args.path("candidates")) sources.validate(candidate.path("source"),candidate.path("text").asText());
        return mapper.createObjectNode().put("allowed",true);
    }

    public ObjectNode ensureTopic(ObjectNode args) {
        var payload = mapper.createObjectNode().put("title", args.path("title").asText()).put("goal", args.path("goal").asText())
            .put("scope_mode", args.path("scope_mode").asText("AUTOMATIC"));
        payload.set("documents", args.path("documents"));
        return research.create("topic", payload, "conversation-topic:" + UUID.fromString(args.path("conversation_id").asText()));
    }

    public ObjectNode authorizeSources(ObjectNode args) {
        if ("briefing-history".equals(args.path("purpose").asText())) sources.authorizeBriefingHistory(args.path("sources"));
        else sources.authorize(args.path("sources"));
        return mapper.createObjectNode().put("allowed", true);
    }

    public ObjectNode contextChoices(ObjectNode args) {
        var query = args.path("query").asText().strip();
        if (query.length() > 200) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        context(mapper.createObjectNode().put("profile", "agent"));
        var result = mapper.createObjectNode(); var choices = result.putArray("choices");
        var seen = new HashSet<String>();
        // Every source adapter filters live ACLs before returning titles or versions.
        if (!query.isBlank()) for (var candidate : knowledge.discover(query, 0, null).candidates()) {
            var source = candidate.source();
            var key = source.document_id() + ":" + source.publication_id();
            if (!seen.add(key)) continue;
            var value = mapper.createObjectNode().put("key", key).put("title", source.title()).put("section", "文献");
            value.putObject("ref").put("kind", "KB_PUBLICATION").put("resource_id", source.document_id()).put("publication_id", source.publication_id());
            choices.add(value);
            if (choices.size() >= 12) break;
        }
        if (projects.available()) addChoices(choices, projects.read("search_projects", mapper.createObjectNode().put("query", query)), "PROJECT", "项目");
        if (experiments != null && experiments.available()) addChoices(choices,
            experiments.read("search_experiments", mapper.createObjectNode().put("query", query)), "EXPERIMENT", "实验");
        return result;
    }
    private void addChoices(com.fasterxml.jackson.databind.node.ArrayNode choices, ObjectNode records, String kind, String section) {
        int count = 0;
        for (var candidate : records.path("candidates")) {
            var source = candidate.path("source");
            var value = mapper.createObjectNode().put("key", kind + ":" + source.path("resource_id").asText() + ":" + source.path("version_id").asText())
                .put("title", source.path("title").asText()).put("section", section);
            value.putObject("ref").put("kind", kind).put("resource_id", source.path("resource_id").asText())
                .put("version_id", source.path("version_id").asText());
            choices.add(value);
            if (++count >= 8) break;
        }
    }

    public ObjectNode contextResource(ObjectNode args) {
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "ai.use", "AI", null, "READ"));
        return resource(args.path("ref"), args.path("offset").asInt());
    }

    private ObjectNode resource(JsonNode ref, int offset) {
        if (offset < 0 || offset > 100000) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var id = UUID.fromString(ref.path("resource_id").asText());
        ObjectNode result;
        switch (ref.path("kind").asText()) {
            case "KB_PUBLICATION" -> {
                var document = new ResearchKnowledgeFacade.DocumentRef(id, UUID.fromString(ref.path("publication_id").asText()));
                knowledge.authorize(List.of(document));
                result = mapper.valueToTree(knowledge.read(document, offset, 8));
            }
            case "PROJECT" -> {
                result = projects.read("get_project_snapshot", mapper.createObjectNode().put("project_id", id.toString()));
                if (ref.hasNonNull("version_id") && !ref.path("version_id").asText().equals(
                    result.path("candidates").path(0).path("source").path("version_id").asText()))
                    throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "项目记录已更新，请重新引用当前版本");
            }
            case "EXPERIMENT" -> {
                if (experiments == null || !ref.hasNonNull("version_id")) throw new ApiException(ApiErrorCode.BAD_REQUEST);
                result = experiments.read("get_experiment_record", mapper.createObjectNode()
                    .put("experiment_id", id.toString()).put("version_id", UUID.fromString(ref.path("version_id").asText()).toString()));
            }
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST);
        }
        result.set("ref", ref);
        var source = result.path("candidates").path(0).path("source");
        result.put("title", source.path("title").asText("资料"));
        return result;
    }

}
