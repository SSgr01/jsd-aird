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

    public ObjectNode resolve(String id, boolean execution) {
        artifacts.requireUse();
        for (var expert : catalog.path("experts")) {
            boolean matches = expert.path("id").asText().equals(id);
            for (var alias : expert.path("aliases")) matches |= alias.asText().equals(id);
            if (!matches) continue;
            if (execution && !expert.path("enabled").asBoolean())
                throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "此专家已停用，请新建其他专家会话");
            var value = ((ObjectNode) expert).deepCopy();
            return value;
        }
        throw new ApiException(ApiErrorCode.NOT_FOUND, "未找到此专家");
    }

    public JsonNode invoke(ObjectNode args) {
        return switch (args.path("action").asText("list")) {
            case "list" -> list();
            case "resolve" -> resolve(args.path("expert_id").asText(), true);
            case "history" -> resolve(args.path("expert_id").asText(), false);
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST);
        };
    }
}
