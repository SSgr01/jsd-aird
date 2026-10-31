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

    public AgentSourceAuthorization(AuthorizationService authorization, ResearchKnowledgeFacade knowledge,
            AgentProjectFacade projects, DataSourceFileSearchFacade data) {
        this.authorization = authorization; this.knowledge = knowledge; this.projects = projects; this.data = data;
    }

    public void authorize(JsonNode sources) {
        if (!sources.isArray() || sources.size() > 500) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        for (var source : sources) one(source);
    }

    public void one(JsonNode source) {
        var actor = ActorContext.required();
        switch (source.path("kind").asText()) {
            case "KB_PUBLICATION" -> knowledge.authorize(List.of(new ResearchKnowledgeFacade.DocumentRef(
                UUID.fromString(source.path("document_id").asText()), UUID.fromString(source.path("publication_id").asText()))));
            case "BUSINESS_RECORD" -> projects.authorizeSource(source);
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

    public void requireExport(JsonNode sources) {
        authorize(sources);
        var actor = ActorContext.required();
        for (var source : sources) if ("BUSINESS_RECORD".equals(source.path("kind").asText()))
            authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "project.export", "PROJECT",
                UUID.fromString(source.path("project_id").asText()), "READ"));
    }
}
