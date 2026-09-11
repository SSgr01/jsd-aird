package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.DataScopeResolver;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import org.junit.jupiter.api.Test;

class DataSearchAuthorizationServiceTest {

    @Test
    void convertsDataViewCategoryPermissionIntoRepositoryScope() {
        var authorization = mock(AuthorizationService.class);
        var categoryId = UUID.randomUUID();
        when(authorization.resolveScope(any())).thenReturn(
                DataScopeResolver.ResolvedDataScope.allow("CATEGORY", Set.of(categoryId)));
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "analyst");

        var scope = new DataSearchAuthorizationService(authorization).requireDataView(actor);

        assertThat(scope.type()).isEqualTo("CATEGORY");
        assertThat(scope.actorId()).isEqualTo(actor.userId());
        assertThat(scope.targetIds()).containsExactly(categoryId);
    }

    @Test
    void rejectsUsersWithoutDataViewPermission() {
        var authorization = mock(AuthorizationService.class);
        when(authorization.resolveScope(any())).thenReturn(
                DataScopeResolver.ResolvedDataScope.deny("ALL", "PERMISSION_DENIED"));
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "analyst");

        assertThatThrownBy(() -> new DataSearchAuthorizationService(authorization).requireDataView(actor))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("数据中心查看权限");
    }
}
