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

    public AgentHostService(LiteratureResearchService research, ResearchKnowledgeFacade knowledge, AgentProjectFacade projects,
            AgentSourceAuthorization sources, AuthorizationService authorization, ObjectMapper mapper) {
        this.research = research; this.knowledge = knowledge; this.projects = projects;
        this.sources = sources; this.authorization = authorization; this.mapper = mapper;
    }

    public ObjectNode context(ObjectNode request) {
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "ai.use", "AI", null, "READ"));
        var profile = request.path("profile").asText("agent");
        if (!Set.of("agent", "fast_qa").contains(profile)) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var result = mapper.createObjectNode().put("scope_mode", "AUTOMATIC").put("profile", profile);
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
            if ("PROJECT".equals(ref.path("kind").asText())) {
                var source = mapper.createObjectNode().put("project_id", id.toString()).put("resource_id", id.toString()).put("resource_type", "PROJECT");
                projects.authorizeSource(source);
            } else if ("KB_PUBLICATION".equals(ref.path("kind").asText())) {
                var publication = UUID.fromString(ref.path("publication_id").asText());
                knowledge.authorize(List.of(new ResearchKnowledgeFacade.DocumentRef(id, publication)));
                ((com.fasterxml.jackson.databind.node.ArrayNode) result.path("documents")).add(mapper.createObjectNode()
                    .put("document_id", id.toString()).put("publication_id", publication.toString()));
            } else throw new ApiException(ApiErrorCode.BAD_REQUEST);
            contexts.add(ref);
        }
        if (!contexts.isEmpty()) result.put("scope_mode", "SELECTED");
        result.set("retrieval_scope", request.hasNonNull("retrieval_scope") ? request.path("retrieval_scope") : mapper.createObjectNode());
        var packs = result.putArray("pack_ids").add("core");
        if ("fast_qa".equals(profile)) packs.add("fast-qa");
        else { packs.add("files").add("literature"); if (projects.available()) packs.add("project-readonly"); }
        var documents = new ArrayList<ResearchKnowledgeFacade.DocumentRef>();
        for (var document : result.path("documents")) documents.add(mapper.convertValue(document, ResearchKnowledgeFacade.DocumentRef.class));
        knowledge.authorize(documents);
        return result;
    }

    public ObjectNode ensureTopic(ObjectNode args) {
        var payload = mapper.createObjectNode().put("title", args.path("title").asText()).put("goal", args.path("goal").asText())
            .put("scope_mode", args.path("scope_mode").asText("AUTOMATIC"));
        payload.set("documents", args.path("documents"));
        return research.create("topic", payload, "conversation-topic:" + UUID.fromString(args.path("conversation_id").asText()));
    }

    public ObjectNode authorizeSources(ObjectNode args) {
        sources.authorize(args.path("sources"));
        return mapper.createObjectNode().put("allowed", true);
    }
}
