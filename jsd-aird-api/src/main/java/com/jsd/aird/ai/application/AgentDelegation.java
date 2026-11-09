package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.application.IamAuthService;
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
    private final IamAuthService auth;

    public AgentDelegation(@Value("${app.ai.harness.shared-secret:}") String secret, ObjectMapper mapper, IamAuthService auth) {
        this.secret = secret; this.mapper = mapper; this.auth = auth;
    }

    public String issue(Actor actor) {
        var user = auth.user(actor.userId());
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
        try {
            if (ticket == null || ticket.length() > 8192) throw new IllegalArgumentException();
            var parts = ticket.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(signature(parts[0]).getBytes(StandardCharsets.US_ASCII), parts[1].getBytes(StandardCharsets.US_ASCII)))
                throw new IllegalArgumentException();
            var node = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            var now = Instant.now().getEpochSecond();
            if (!"java-tools".equals(node.path("aud").asText()) || node.path("exp").asLong() <= now
                || node.path("iat").asLong() > now + 30 || node.path("exp").asLong() - node.path("iat").asLong() > 3600)
                throw new IllegalArgumentException();
            var user = auth.user(UUID.fromString(node.path("actor_id").asText()));
            if (user == null || !"ACTIVE".equals(user.status()) || user.authVersion() != node.path("auth_version").asLong()
                || !user.organizationId().toString().equals(node.path("organization_id").asText())) throw new IllegalArgumentException();
            return new Actor(user.organizationId(), user.id(), user.username(), user.roleCode() == null ? "USER" : user.roleCode());
        } catch (Exception error) { throw new ApiException(ApiErrorCode.AUTH_REQUIRED); }
    }

    private String signature(String body) throws Exception {
        if (secret.length() < 32) throw new ApiException(ApiErrorCode.AI_NOT_CONFIGURED, "Harness 服务密钥未配置");
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
    }
}
