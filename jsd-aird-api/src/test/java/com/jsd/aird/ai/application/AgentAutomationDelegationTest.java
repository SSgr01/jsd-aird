package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.DelegationIdentity;
import com.jsd.aird.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentAutomationDelegationTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final String secret = "background-delegation-" + "s".repeat(32);
    final UUID org = UUID.randomUUID(), user = UUID.randomUUID();
    final DelegationIdentity iam = mock(DelegationIdentity.class);
    final AgentDelegation service = new AgentDelegation(secret, json, iam);

    String token(String audience, long authVersion) throws Exception {
        return token(audience, authVersion, "research-briefings");
    }

    String token(String audience, long authVersion, String scope) throws Exception {
        long now = Instant.now().getEpochSecond();
        var claims = Map.of("organization_id", org, "actor_id", user, "auth_version", authVersion,
            "automation_grant_id", UUID.randomUUID(), "scope", scope, "aud", audience, "iat", now, "exp", now+60);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims));
        var mac = Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test void scopedExecutionCannotBecomeInteractiveOrWriteBusinessRecords() throws Exception {
        when(iam.delegationUser(user)).thenReturn(new DelegationIdentity.Identity(user,org,"lin.chen","RND_MANAGER","ACTIVE",7));
        var identity = service.automationIdentity(token("automation-exchange",7));
        var claims = json.readTree(Base64.getUrlDecoder().decode(((String)identity.get("ticket")).split("\\.")[0]));
        assertThat(claims.path("exp").asLong()-claims.path("iat").asLong()).isEqualTo(300);
        assertThat(claims.path("scope").asText()).isEqualTo("research-briefings");
        var ticket = token("java-tools",7);
        assertThat(service.verify(ticket).organizationId()).isEqualTo(org);
        service.requireAction(ticket,"get_research_briefing_context");
        service.requireAction(ticket,"business_read");
        service.requireAction(ticket,"business_search",json.createObjectNode());
        service.requireAction(ticket,"business_read",json.readTree("{\"ref\":{\"type\":\"EXPERIMENT\"}}"));
        assertThatThrownBy(()->service.requireAction(ticket,"business_read",json.readTree("{\"ref\":{\"type\":\"CUSTOMER\"}}")))
            .isInstanceOf(ApiException.class);
        for (var action : List.of("create_experiment_draft","report_save","artifact_save","run_prediction","research_topic_ensure"))
            assertThatThrownBy(()->service.requireAction(ticket,action)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.requireInteractive(ticket)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.automationIdentity(token("java-tools",7))).isInstanceOf(ApiException.class);
    }

    @Test void liveAccountOrganizationAndAuthorizationVersionAreRequired() throws Exception {
        for (var current : List.of(new DelegationIdentity.Identity(user,org,"lin.chen","RND_MANAGER","DISABLED",7),
                new DelegationIdentity.Identity(user,org,"lin.chen","RND_MANAGER","ACTIVE",8),
                new DelegationIdentity.Identity(user,UUID.randomUUID(),"lin.chen","RND_MANAGER","ACTIVE",7))) {
            when(iam.delegationUser(user)).thenReturn(current);
            assertThatThrownBy(()->service.automationIdentity(token("automation-exchange",7))).isInstanceOf(ApiException.class);
            assertThatThrownBy(()->service.verify(token("java-tools",7))).isInstanceOf(ApiException.class);
        }
    }

    @Test void personalMemoryCanOnlyValidateItsOwnSources() throws Exception {
        when(iam.delegationUser(user)).thenReturn(new DelegationIdentity.Identity(user,org,"lin.chen","RND_MANAGER","ACTIVE",7));
        var identity = service.automationIdentity(token("automation-exchange",7,"personal-memory"));
        var claims = json.readTree(Base64.getUrlDecoder().decode(((String)identity.get("ticket")).split("\\.")[0]));
        assertThat(claims.path("scope").asText()).isEqualTo("personal-memory");
        var ticket = token("java-tools",7,"personal-memory");
        for (var action : List.of("runtime_context","authorize_sources","validate_cached_evidence"))
            service.requireAction(ticket,action);
        for (var action : List.of("get_research_briefing_context","business_read","business_search","create_experiment_draft",
                "report_save","artifact_save","research_topic_ensure"))
            assertThatThrownBy(()->service.requireAction(ticket,action)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.requireInteractive(ticket)).isInstanceOf(ApiException.class);
    }
}
