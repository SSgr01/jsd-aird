package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Company-managed role definitions. A role never grants tools or business permissions. */
@Service
public class ExpertCatalogService {
    private final AgentArtifactService artifacts;
    private final JsonNode catalog;

    public ExpertCatalogService(ObjectMapper mapper, AgentArtifactService artifacts) throws java.io.IOException {
        this.artifacts = artifacts;
        try (var input = new ClassPathResource("ai/expert-presets.json").getInputStream()) {
            catalog = mapper.readTree(input);
        }
    }

    public JsonNode list() {
        artifacts.requireUse();
        return catalog.deepCopy();
    }

    public ObjectNode resolve(String id, String revision, boolean execution) {
        artifacts.requireUse();
        for (var expert : catalog.path("experts")) {
            if (!expert.path("id").asText().equals(id)) continue;
            // Released revisions stay in the catalog; only the current one is discoverable.
            var versions = expert.path("versions");
            var selected = revision == null || revision.isBlank() ? expert.path("current_revision").asText() : revision;
            if (!versions.has(selected)) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "专家版本已不可用");
            if (execution && !expert.path("enabled").asBoolean())
                throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "此专家已停用，请新建其他专家会话");
            var value = ((ObjectNode) versions.path(selected)).deepCopy();
            value.put("id", id).put("revision", selected).put("enabled", expert.path("enabled").asBoolean());
            return value;
        }
        throw new ApiException(ApiErrorCode.NOT_FOUND, "未找到此专家");
    }

    public JsonNode invoke(ObjectNode args) {
        return switch (args.path("action").asText("list")) {
            case "list" -> list();
            case "resolve" -> resolve(args.path("expert_id").asText(), args.path("revision").asText(""), true);
            case "history" -> resolve(args.path("expert_id").asText(), args.path("revision").asText(""), false);
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST);
        };
    }
}
