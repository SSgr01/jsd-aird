package com.jsd.aird.ai.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.application.AgentDelegation;
import com.jsd.aird.ai.application.LiteratureResearchService;
import com.jsd.aird.ai.application.AgentArtifactService;
import com.jsd.aird.ai.application.AgentHostService;
import com.jsd.aird.ai.application.SharedRetrievalService;
import com.jsd.aird.ai.application.PrivateResearchSourceService;
import com.jsd.aird.mdm.api.AgentProjectFacade;
import com.jsd.aird.ai.application.port.WebSearchProvider;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.kb.api.ScholarlyLiteratureFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class AgentToolsController {
    private final AgentDelegation delegation;
    private final LiteratureResearchService research;
    private final ResearchKnowledgeFacade knowledge;
    private final WebSearchProvider web;
    private final AgentHostService host;
    private final SharedRetrievalService retrieval;
    private final AgentProjectFacade projects;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final AgentArtifactService artifacts;
    private final PrivateResearchSourceService privateSources;
    private final ScholarlyLiteratureFacade scholarly;
    private com.jsd.aird.rnd.api.AgentExperimentFacade experiments;

    @org.springframework.beans.factory.annotation.Autowired
    void experiments(com.jsd.aird.rnd.api.AgentExperimentFacade experiments) { this.experiments = experiments; }

    public AgentToolsController(AgentDelegation delegation, LiteratureResearchService research,
            ResearchKnowledgeFacade knowledge, WebSearchProvider web, ObjectMapper mapper, AgentArtifactService artifacts,
            AgentHostService host, SharedRetrievalService retrieval, AgentProjectFacade projects,
            @Value("${app.ai.harness.enabled:false}") boolean enabled, PrivateResearchSourceService privateSources,
            ScholarlyLiteratureFacade scholarly) {
        this.delegation = delegation; this.research = research; this.knowledge = knowledge;
        this.web = web; this.mapper = mapper; this.enabled = enabled;
        this.host = host; this.retrieval = retrieval; this.projects = projects;
        this.artifacts = artifacts;
        this.privateSources = privateSources;
        this.scholarly = scholarly;
    }

    public record ToolRequest(String action, ObjectNode arguments) { }

    @PostMapping("/internal/v1/agent-tools")
    public Object invoke(@RequestHeader(value="X-Jsd-Agent-Ticket", defaultValue="") String ticket, @RequestBody ToolRequest command) {
        if (!enabled) throw new ApiException(ApiErrorCode.AI_NOT_CONFIGURED, "Agent 尚未启用");
        var previous = ActorContext.current();
        try {
            ActorContext.set(delegation.verify(ticket));
            artifacts.requireUse();
            var args = command.arguments();
            if (args == null || args.toString().length() > 16000000) throw new ApiException(ApiErrorCode.BAD_REQUEST);
            return switch (command.action()) {
                case "topic" -> topic(UUID.fromString(args.path("id").asText()));
                case "runtime_context" -> host.context(args);
                case "context_choices" -> host.contextChoices(args);
                case "context_resource" -> host.contextResource(args);
                case "research_report_get" -> research.get("report", UUID.fromString(args.path("id").asText()));
                case "authorize_sources" -> host.authorizeSources(args);
                case "validate_cached_evidence" -> host.validateEvidence(args);
                case "research_topic_ensure" -> host.ensureTopic(args);
                case "retrieval_context" -> retrieval.retrieve(args);
                case "query_structured_data" -> retrieval.structured(args);
                case "private_source_upload" -> privateSources.upload(args);
                case "private_source_get" -> privateSources.metadata(UUID.fromString(args.path("document_id").asText()));
                case "private_source_read" -> privateSources.read(args);
                case "private_source_search" -> retrieval.privateDocuments(host.context(args), args);
                case "private_source_outline" -> privateSources.outline(args);
                case "private_source_context" -> privateSources.context(args);
                case "source_context" -> "parent".equals(args.path("relation").asText("neighbors"))
                    ? knowledge.parentContext(mapper.convertValue(args.path("source"), ResearchKnowledgeFacade.SourceRef.class),args.path("cursor").asInt(),2000)
                    : knowledge.context(mapper.convertValue(args.path("source"), ResearchKnowledgeFacade.SourceRef.class),args.path("before").asInt(1),args.path("after").asInt(1),args.path("cursor").asInt());
                case "operation_receipt" -> "create_experiment_draft".equals(args.path("tool").asText())
                    ? (args.path("close_uncommitted").asBoolean(false)
                        ? experiments.reconcileReceipt(args.path("operation_key").asText(), args.path("arguments") instanceof ObjectNode request ? request : null)
                        : experiments.receipt(args.path("operation_key").asText(), args.path("arguments") instanceof ObjectNode request ? request : null)) : research.receipt(args);
                case "search_experiments", "get_experiment_record" -> experiments.read(command.action(), args);
                case "create_experiment_draft" -> experiments.createDraft(args);
                case "search_projects", "get_project_snapshot", "list_project_stages", "list_project_tasks", "get_task_detail", "get_project_records" -> projects.read(command.action(), args);
                case "authorize" -> { knowledge.authorize(refs(args.path("documents"))); yield Map.of("allowed", true); }
                case "knowledge_search" -> {
                    var scope = args.hasNonNull("topic_id") ? topic(UUID.fromString(args.path("topic_id").asText())) : host.context(args);
                    yield retrieval.documents(scope, args);
                }
                case "document_read", "document_outline", "table_read" -> {
                    var topic = args.hasNonNull("topic_id") ? topic(UUID.fromString(args.path("topic_id").asText())) : host.context(args);
                    var ref = new ResearchKnowledgeFacade.DocumentRef(UUID.fromString(args.path("document_id").asText()), UUID.fromString(args.path("publication_id").asText()));
                    if (!"AUTOMATIC".equals(topic.path("scope_mode").asText()) && !research.documents(topic).contains(ref))
                        throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED);
                    yield "document_outline".equals(command.action()) ? knowledge.outline(ref,args.path("offset").asInt()) : "document_read".equals(command.action())
                        ? knowledge.read(ref, args.path("offset").asInt(), args.path("limit").asInt(12))
                        : knowledge.table(ref, UUID.fromString(args.path("table_id").asText()), args.path("offset").asInt());
                }
                case "note_save" -> research.note(UUID.fromString(args.path("topic_id").asText()), args.path("text").asText(), args.path("key").asText());
                case "evidence_save" -> research.create("card", args.deepCopy(), args.path("key").asText());
                case "report_save" -> research.create("report", args.deepCopy(), args.path("key").asText());
                case "artifact_save" -> artifacts.save(args);
                case "external_search" -> web.search(new WebSearchProvider.SearchQuery(args.path("query").asText(), "general", null));
                case "web_search_status" -> Map.of("available", web.isAvailable());
                case "bibliography_lookup" -> scholarly.lookup(args);
                case "literature_search" -> scholarly.search(args);
                case "literature_expand" -> scholarly.expand(args);
                case "literature_acquire" -> privateSources.acquire(args, scholarly);
                case "literature_acquire_status" -> privateSources.acquisitionStatus(args);
                default -> throw new ApiException(ApiErrorCode.BAD_REQUEST, "未知 Agent 工具");
            };
        } finally {
            if (previous == null) ActorContext.clear(); else ActorContext.set(previous);
        }
    }

    private ObjectNode topic(UUID id) {
        var topic = research.get("topic", id);
        knowledge.authorize(research.documents(topic));
        topic.set("notes", mapper.valueToTree(research.list("annotation", id)));
        return topic;
    }

    private List<ResearchKnowledgeFacade.DocumentRef> refs(JsonNode nodes) {
        var result = new ArrayList<ResearchKnowledgeFacade.DocumentRef>();
        for (var node : nodes) result.add(mapper.convertValue(node, ResearchKnowledgeFacade.DocumentRef.class));
        return result;
    }
}
