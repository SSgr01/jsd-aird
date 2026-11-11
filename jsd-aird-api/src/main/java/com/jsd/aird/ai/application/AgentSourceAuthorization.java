package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.mdm.api.AgentProjectFacade;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AgentSourceAuthorization {
    private final AuthorizationService authorization;
    private final ResearchKnowledgeFacade knowledge;
    private final AgentProjectFacade projects;
    private final DataSourceFileSearchFacade data;
    private final PrivateResearchSourceService privateSources;
    private com.jsd.aird.rnd.api.AgentExperimentFacade experiments;
    private org.springframework.beans.factory.ObjectProvider<BusinessDataService> businessData;
    private org.springframework.beans.factory.ObjectProvider<BusinessComputeService> businessCompute;

    @org.springframework.beans.factory.annotation.Autowired
    void businessServices(org.springframework.beans.factory.ObjectProvider<BusinessDataService> data,
            org.springframework.beans.factory.ObjectProvider<BusinessComputeService> compute) {
        this.businessData = data; this.businessCompute = compute;
    }

    private boolean businessSource(JsonNode source, String excerpt) {
        if (!"BUSINESS_RECORD".equals(source.path("kind").asText())
                || !"v1".equals(source.path("anchor").path("business_adapter").asText())) return false;
        if (source.path("anchor").hasNonNull("compute_kind")) businessCompute.getObject().validateSource(source, excerpt);
        else businessData.getObject().validateSource(source, excerpt);
        return true;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void experiments(com.jsd.aird.rnd.api.AgentExperimentFacade experiments) { this.experiments = experiments; }

    public AgentSourceAuthorization(AuthorizationService authorization, ResearchKnowledgeFacade knowledge,
            AgentProjectFacade projects, DataSourceFileSearchFacade data, PrivateResearchSourceService privateSources) {
        this.authorization = authorization; this.knowledge = knowledge; this.projects = projects; this.data = data;
        this.privateSources = privateSources;
    }

    public void authorize(JsonNode sources) {
        if (!sources.isArray() || sources.size() > 500) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        privateSources.authorizeAll(sources);
        var documents = new java.util.LinkedHashSet<ResearchKnowledgeFacade.DocumentRef>();
        for (var source : sources) {
            if ("KB_PUBLICATION".equals(source.path("kind").asText())) documents.add(new ResearchKnowledgeFacade.DocumentRef(
                UUID.fromString(source.path("document_id").asText()), UUID.fromString(source.path("publication_id").asText())));
            else if (!List.of("RUN_UPLOAD", "EXTERNAL_FULLTEXT").contains(source.path("kind").asText())) one(source);
        }
        if (!documents.isEmpty()) knowledge.authorize(List.copyOf(documents));
    }

    /** Reauthorize old period visibility without treating old mutable facts as current evidence. */
    public void authorizeBriefingHistory(JsonNode sources) {
        if (!sources.isArray() || sources.size() > 500) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var seen = new java.util.HashSet<String>();
        for (var source : sources) {
            String type = source.path("resource_type").asText();
            if (!"BUSINESS_RECORD".equals(source.path("kind").asText())
                    || !java.util.Set.of("PROJECT", "STAGE", "TASK", "REQUIREMENT", "MATERIAL", "EXPERIMENT").contains(type)) {
                authorize(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(source));
                continue;
            }
            var ref = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("type", type).put("id", source.path("resource_id").asText());
            if (source.hasNonNull("project_id")) ref.set("project_id", source.get("project_id"));
            // Experiment versions exist independently; a historical version must
            // still belong to this experiment and remain currently readable.
            if ("EXPERIMENT".equals(type)) ref.set("version_id", source.path("version_id"));
            if (!seen.add(ref.toString())) continue;
            var args = ref.objectNode(); args.set("ref", ref);
            var result = businessData.getObject().invoke("business_read", args);
            if (!result.path("candidates").isArray() || result.path("candidates").isEmpty())
                throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }

    public void one(JsonNode source) {
        if (businessSource(source, null)) return;
        var actor = ActorContext.required();
        switch (source.path("kind").asText()) {
            case "KB_PUBLICATION" -> knowledge.authorize(List.of(new ResearchKnowledgeFacade.DocumentRef(
                UUID.fromString(source.path("document_id").asText()), UUID.fromString(source.path("publication_id").asText()))));
            case "BUSINESS_RECORD" -> {
                if ("EXPERIMENT".equals(source.path("resource_type").asText())) experiments.validateSource(source, null);
                else projects.authorizeSource(source);
            }
            case "RUN_UPLOAD", "EXTERNAL_FULLTEXT" -> privateSources.validate(source, null);
            case "DATA_SOURCE_FILE" -> {
                boolean experiment = "EXPERIMENT".equals(source.path("resource_type").asText());
                if (!experiment && !"DATA".equals(source.path("resource_type").asText())) throw new ApiException(ApiErrorCode.BAD_REQUEST);
                var id = UUID.fromString(source.path("resource_id").asText());
                authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(),
                    experiment ? "experiment.view" : "data.view", experiment ? "EXPERIMENT" : "DATA", id, "READ"));
                if (!data.matchesSource(actor.organizationId(), UUID.fromString(source.path("source_node_key").asText()),
                        UUID.fromString(source.path("document_id").asText()), id, experiment)) throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            case "WEB_RESULT" -> {
                var url = URI.create(source.path("url").asText());
                if (!List.of("https", "http").contains(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null)
                    throw new ApiException(ApiErrorCode.BAD_REQUEST);
            }
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST, "尚未接入的来源类型");
        }
    }

    public void authorizeHistoryCitations(JsonNode citations) {
        if (citations == null || citations.isNull()) return;
        if (!citations.isArray() || citations.size() > 500) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var actor = ActorContext.required();
        for (var citation : citations) {
            if (citation.path("source").isObject()) { validate(citation.path("source"), ""); continue; }
            switch (citation.path("sourceType").asText()) {
                case "KNOWLEDGE_CHUNK" -> knowledge.authorizeHistoricalVersion(
                    UUID.fromString(citation.path("documentId").asText()), UUID.fromString(citation.path("versionId").asText()));
                case "DATA_SOURCE_FILE", "EXPERIMENT_TEMPLATE_SOURCE" -> {
                    boolean experiment = "EXPERIMENT_TEMPLATE_SOURCE".equals(citation.path("sourceType").asText());
                    var owner = UUID.fromString(citation.path(experiment ? "experimentId" : "importJobId").asText());
                    authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), experiment ? "experiment.view" : "data.view", experiment ? "EXPERIMENT" : "DATA", owner, "READ"));
                    if (!data.matchesSource(actor.organizationId(), UUID.fromString(citation.path("chunkId").asText()),
                            UUID.fromString(citation.path("fileObjectId").asText()), owner, experiment)) throw new ApiException(ApiErrorCode.NOT_FOUND);
                }
                case "EXTERNAL_REFERENCE" -> {
                    var url = URI.create(citation.path("url").asText());
                    if (!List.of("https", "http").contains(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null) throw new ApiException(ApiErrorCode.BAD_REQUEST);
                }
                default -> throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED, "历史来源无法重新授权");
            }
        }
    }

    public void validate(JsonNode source, String excerpt) {
        if (businessSource(source, excerpt)) return;
        if ("BUSINESS_RECORD".equals(source.path("kind").asText()) && !"EXPERIMENT".equals(source.path("resource_type").asText())) {
            projects.validateSource(source, excerpt); return;
        }
        if ("BUSINESS_RECORD".equals(source.path("kind").asText()) && "EXPERIMENT".equals(source.path("resource_type").asText())) {
            experiments.validateSource(source, excerpt);
            return;
        }
        if (List.of("RUN_UPLOAD", "EXTERNAL_FULLTEXT").contains(source.path("kind").asText())) {
            privateSources.validate(source, excerpt);
        } else {
            one(source);
            knowledge.validate(new ResearchKnowledgeFacade.SourceRef(source.path("kind").asText(),
                source.path("document_id").asText(), source.path("version_id").asText(),
                source.path("publication_id").asText(null), source.path("source_node_key").asText(),
                source.path("content_sha256").asText(), source.path("anchor"), source.path("title").asText(),
                source.path("url").asText(null)), excerpt);
        }
    }

    public void validateEvidence(JsonNode evidence) {
        if (!evidence.isObject() || evidence.size() > 500) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        privateSources.validateEvidence(evidence);
        var excerpts = new java.util.ArrayList<ResearchKnowledgeFacade.SourceExcerpt>();
        for (var item : evidence) {
            var source = item.path("source");
            if ("KB_PUBLICATION".equals(source.path("kind").asText())) {
                excerpts.add(new ResearchKnowledgeFacade.SourceExcerpt(new ResearchKnowledgeFacade.SourceRef(
                    source.path("kind").asText(), source.path("document_id").asText(), source.path("version_id").asText(),
                    source.path("publication_id").asText(null), source.path("source_node_key").asText(),
                    source.path("content_sha256").asText(), source.path("anchor"), source.path("title").asText(),
                    source.path("url").asText(null)), item.path("text").asText()));
            } else if (!List.of("RUN_UPLOAD", "EXTERNAL_FULLTEXT").contains(source.path("kind").asText()))
                validate(source, item.path("text").asText());
        }
        knowledge.validateAll(excerpts);
    }

    public void requireExport(JsonNode sources) {
        authorize(sources);
        var actor = ActorContext.required();
        for (var source : sources) if ("v1".equals(source.path("anchor").path("business_adapter").asText()))
            businessData.getObject().requireExport(source);
        for (var source : sources) if ("BUSINESS_RECORD".equals(source.path("kind").asText())
                && !"v1".equals(source.path("anchor").path("business_adapter").asText())
                && !"EXPERIMENT".equals(source.path("resource_type").asText()))
            authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "project.export", "PROJECT",
                UUID.fromString(source.path("project_id").asText()), "READ"));
    }
}
