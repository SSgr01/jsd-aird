package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.DelegationIdentity;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentDelegation {
    private final String secret;
    private final ObjectMapper mapper;
    private final DelegationIdentity auth;

    public AgentDelegation(@Value("${app.ai.harness.shared-secret:}") String secret, ObjectMapper mapper, DelegationIdentity auth) {
        this.secret = secret; this.mapper = mapper; this.auth = auth;
    }

    public String issue(Actor actor) {
        var user = auth.delegationUser(actor.userId());
        if (user == null || !user.organizationId().equals(actor.organizationId()) || !"ACTIVE".equals(user.status()))
            throw new ApiException(ApiErrorCode.AUTH_REQUIRED);
        var now = Instant.now().getEpochSecond();
        try {
            var payload = mapper.writeValueAsBytes(Map.of("organization_id", actor.organizationId(), "actor_id", actor.userId(),
                "auth_version", user.authVersion(), "username", user.username(), "role", user.roleCode() == null ? "USER" : user.roleCode(),
                "iat", now, "exp", now + 3600, "aud", "control"));
            var body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
            return body + "." + signature(body);
        } catch (ApiException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Unable to create delegation", error); }
    }

    public Actor verify(String ticket) {
        var node = claims(ticket, "java-tools");
        var user = live(node);
        return new Actor(user.organizationId(), user.id(), user.username(), user.roleCode() == null ? "USER" : user.roleCode());
    }

    public void requireInteractive(String ticket) {
        if (!claims(ticket, "java-tools").path("automation_grant_id").asText("").isBlank())
            throw new ApiException(ApiErrorCode.PERMISSION_DENIED);
    }

    /** A short-lived service proof is exchanged only after checking the live IAM identity. */
    public Map<String, Object> automationIdentity(String proof) {
        var node = claims(proof, "automation-exchange");
        if (!"research-briefings".equals(node.path("scope").asText())
                || node.path("automation_grant_id").asText().isBlank()) throw new ApiException(ApiErrorCode.AUTH_REQUIRED);
        var user = live(node);
        long now = Instant.now().getEpochSecond();
        var values = new java.util.HashMap<String, Object>();
        values.put("organization_id", user.organizationId()); values.put("actor_id", user.id());
        values.put("username", user.username()); values.put("role", user.roleCode() == null ? "USER" : user.roleCode());
        values.put("auth_version", user.authVersion()); values.put("automation_grant_id", node.path("automation_grant_id").asText());
        values.put("scope", "research-briefings"); values.put("aud", "control"); values.put("iat", now); values.put("exp", now + 300);
        try {
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(values));
            values.put("ticket", body + "." + signature(body)); values.put("expires_at", now + 300);
            values.keySet().removeAll(java.util.Set.of("aud", "iat", "exp"));
            return values;
        } catch (Exception error) { throw new IllegalStateException("Unable to create background delegation", error); }
    }

    public void requireAction(String ticket, String action) {
        requireAction(ticket, action, null);
    }

    public void requireAction(String ticket, String action, JsonNode arguments) {
        var node = claims(ticket, "java-tools");
        if (node.path("automation_grant_id").asText("").isBlank()) return;
        if (!"research-briefings".equals(node.path("scope").asText()) || !java.util.Set.of(
                "runtime_context", "authorize_sources", "validate_cached_evidence", "context_resource",
                "get_research_briefing_context", "get_experiment_loop", "get_compute_result", "business_search", "business_read", "business_links",
                "search_projects", "get_project_snapshot", "list_project_stages", "list_project_tasks", "get_task_detail", "get_project_records",
                "search_experiments", "get_experiment_record", "web_search_status", "authorize",
                "knowledge_search", "document_read", "document_outline", "table_read", "source_context", "source_image", "query_structured_data"
            ).contains(action)) throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "后台任务仅允许研发读取与分析");
        if (arguments != null && java.util.Set.of("business_search", "business_read", "business_links").contains(action)) {
            String type = "business_search".equals(action) ? arguments.path("type").asText()
                : arguments.path("ref").path("type").asText();
            if ("business_search".equals(action) && type.isBlank()) return;
            if (!java.util.Set.of("PROJECT", "STAGE", "TASK", "REQUIREMENT", "MATERIAL", "EXPERIMENT", "STRUCTURED_DATA", "RAW_MATERIAL", "RAW_MATERIAL_SOURCE").contains(type))
                throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "后台授权仅包含研发资料");
        }
    }

    private DelegationIdentity.Identity live(JsonNode node) {
        try {
            var user = auth.delegationUser(UUID.fromString(node.path("actor_id").asText()));
            if (user == null || !"ACTIVE".equals(user.status()) || user.authVersion() != node.path("auth_version").asLong()
                    || !user.organizationId().toString().equals(node.path("organization_id").asText())) throw new IllegalArgumentException();
            return user;
        } catch (Exception error) { throw new ApiException(ApiErrorCode.AUTH_REQUIRED); }
    }

    private JsonNode claims(String ticket, String audience) {
        try {
            if (ticket == null || ticket.length() > 8192) throw new IllegalArgumentException();
            var parts = ticket.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(signature(parts[0]).getBytes(StandardCharsets.US_ASCII), parts[1].getBytes(StandardCharsets.US_ASCII)))
                throw new IllegalArgumentException();
            var node = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            long now = Instant.now().getEpochSecond();
            if (!audience.equals(node.path("aud").asText()) || node.path("exp").asLong() <= now
                    || node.path("iat").asLong() > now + 30 || node.path("exp").asLong() - node.path("iat").asLong() > 3600)
                throw new IllegalArgumentException();
            return node;
        } catch (Exception error) { throw new ApiException(ApiErrorCode.AUTH_REQUIRED); }
    }

    private String signature(String body) throws Exception {
        if (secret.length() < 32) throw new ApiException(ApiErrorCode.AI_NOT_CONFIGURED, "Harness 服务密钥未配置");
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
    }
}
