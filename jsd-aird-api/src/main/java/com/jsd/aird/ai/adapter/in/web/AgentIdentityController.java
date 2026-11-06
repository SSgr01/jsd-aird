package com.jsd.aird.ai.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.application.AgentDelegation;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Server-only identity exchange. No DeepSeek credential crosses the enterprise browser boundary. */
@RestController
public class AgentIdentityController {
    private final AgentDelegation delegation;
    private final AuthorizationService authorization;
    private final ObjectMapper mapper;
    private final String secret;

    public AgentIdentityController(AgentDelegation delegation, AuthorizationService authorization,
            ObjectMapper mapper, @Value("${app.ai.harness.runtime-secret:${JSD_AIRD_HARNESS_RUNTIME_SECRET:}}") String secret) {
        this.delegation = delegation;
        this.authorization = authorization;
        this.mapper = mapper;
        this.secret = secret;
    }

    @PostMapping("/internal/v1/agent-identity")
    public Map<String, Object> identity(HttpServletRequest request,
            @RequestHeader(value = "Authorization", defaultValue = "") String credential,
            @RequestHeader(value = "X-Jsd-Agent-Ticket", defaultValue = "") String ticket) throws Exception {
        if (secret.length() < 32 || !MessageDigest.isEqual(("Bearer " + secret).getBytes(StandardCharsets.UTF_8),
                credential.getBytes(StandardCharsets.UTF_8))) throw new ApiException(ApiErrorCode.AUTH_REQUIRED);
        Actor actor;
        // Cookie authentication is authoritative for browser requests, including a revoked cookie.
        if (request.getHeader("Cookie") != null) {
            actor = ActorContext.current();
            if (actor == null) throw new ApiException(ApiErrorCode.AUTH_REQUIRED);
        }
        else actor = delegation.verify(ticket);
        try {
            ActorContext.set(actor);
            authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "ai.use", "AI", null, "READ"));
            var signed = delegation.issue(actor);
            var claims = mapper.readTree(Base64.getUrlDecoder().decode(signed.split("\\.")[0]));
            return Map.of("organization_id", actor.organizationId(), "actor_id", actor.userId(),
                    "auth_version", claims.path("auth_version").asLong(), "username", actor.username(),
                    "role", claims.path("role").asText(), "ticket", signed,
                    "expires_at", claims.path("exp").asLong());
        } finally { ActorContext.clear(); }
    }
}
