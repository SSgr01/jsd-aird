package com.jsd.aird.ai.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.application.AgentDelegation;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentIdentityControllerTest {
    final String secret = "runtime-" + "s".repeat(32);
    final AgentDelegation delegation = mock(AgentDelegation.class);
    final AuthorizationService authorization = mock(AuthorizationService.class);
    final AgentIdentityController controller = new AgentIdentityController(delegation,authorization,new ObjectMapper(),secret);

    @Test void invalidRuntimeCredentialDoesNotRevokeAnyUserGrant() {
        assertThatThrownBy(()->controller.automation("Bearer incorrect",Map.of("proof","proof")))
            .isInstanceOfSatisfying(ApiException.class,error->{
                assertThat(error.errorCode()).isEqualTo(ApiErrorCode.AUTH_REQUIRED);
                assertThat(error.detail()).isNull();
            });
        verifyNoInteractions(delegation,authorization);
    }

    @Test void permissionRevocationReturnsStableReasonInExistingErrorEnvelope() {
        var identity = Map.<String,Object>of("organization_id",UUID.randomUUID(),"actor_id",UUID.randomUUID(),
            "username","zhou.yan","role","USER","ticket","signed");
        when(delegation.automationIdentity("proof")).thenReturn(identity);
        doThrow(new ApiException(ApiErrorCode.PERMISSION_DENIED)).when(authorization).require(any());
        assertThatThrownBy(()->controller.automation("Bearer "+secret,Map.of("proof","proof")))
            .isInstanceOfSatisfying(ApiException.class,error->{
                var response = ResponseFactory.error(error.errorCode(),error.getMessage(),error.detail(),"trace");
                assertThat(response.data()).isEqualTo(Map.of("reason","BACKGROUND_AUTHORIZATION_REQUIRED"));
            });
        assertThat(ActorContext.current()).isNull();
    }

    @Test void permissionServiceFailureRemainsRecoverable() {
        var identity = Map.<String,Object>of("organization_id",UUID.randomUUID(),"actor_id",UUID.randomUUID(),
            "username","zhou.yan","role","USER","ticket","signed");
        when(delegation.automationIdentity("proof")).thenReturn(identity);
        var unavailable = new ApiException(ApiErrorCode.DATABASE_BUSY);
        doThrow(unavailable).when(authorization).require(any());
        assertThatThrownBy(()->controller.automation("Bearer "+secret,Map.of("proof","proof"))).isSameAs(unavailable);
        assertThat(ActorContext.current()).isNull();
    }
}
