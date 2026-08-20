package com.jsd.aird.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.iam.api.PermissionDefinitionContributor.PermissionDefinition;
import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.iam.application.port.IamStore.Binding;
import com.jsd.aird.iam.application.port.IamStore.Role;
import com.jsd.aird.iam.application.port.IamStore.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcAuthorizationServiceTest {

    private final JdbcIamStore store = mock(JdbcIamStore.class);
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID roleId = UUID.randomUUID();
    private final IamStore.User user = new User(userId, organizationId, "engineer", "研发工程师", null, null, null,
            roleId, "RND_ENGINEER", "研发工程师", "ACTIVE", false, "hash", 0, Instant.now(), 0, null);
    private final Role role = new Role(roleId, organizationId, "RND_ENGINEER", "研发工程师", true, true, 0);
    private JdbcAuthorizationService authorization;

    @BeforeEach
    void setUp() {
        authorization = new JdbcAuthorizationService(store, false);
        when(store.user(userId)).thenReturn(Optional.of(user));
        when(store.permissionDefinitions()).thenReturn(List.of(
                new PermissionDefinition("project.view", "project", "查看项目", "LOW", "ALL"),
                new PermissionDefinition("knowledge.view", "knowledge", "查看知识", "LOW", "SELECTED"),
                new PermissionDefinition("template.publish", "template", "发布模板", "HIGH", "ALL"),
                new PermissionDefinition("template.delete", "template", "删除模板", "CRITICAL", "ALL")));
        when(store.role(organizationId, roleId)).thenReturn(Optional.of(role));
    }

    @Test
    void inheritsAllowFromRole() {
        when(store.userOverrides(organizationId, userId)).thenReturn(List.of());
        when(store.roleBindings(organizationId, roleId)).thenReturn(List.of(
                new Binding("project.view", "ALLOW", "ALL", List.of())));

        var decision = authorization.check(new PermissionCheck(organizationId, userId, "project.view", "PROJECT", null, "READ"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.source()).isEqualTo("ROLE:RND_ENGINEER");
        assertThat(decision.scopeType()).isEqualTo("ALL");
    }

    @Test
    void userDenyOverridesRoleAllow() {
        when(store.userOverrides(organizationId, userId)).thenReturn(List.of(
                new Binding("project.view", "DENY", "ALL", List.of())));
        when(store.roleBindings(organizationId, roleId)).thenReturn(List.of(
                new Binding("project.view", "ALLOW", "ALL", List.of())));

        var decision = authorization.check(new PermissionCheck(organizationId, userId, "project.view", "PROJECT", null, "READ"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.effect()).isEqualTo("DENY");
        assertThat(decision.source()).isEqualTo("USER_OVERRIDE");
    }

    @Test
    void selectedScopeRequiresMatchingResource() {
        var selected = UUID.randomUUID();
        when(store.userOverrides(organizationId, userId)).thenReturn(List.of());
        when(store.roleBindings(organizationId, roleId)).thenReturn(List.of(
                new Binding("knowledge.view", "ALLOW", "SELECTED", List.of(selected))));

        assertThat(authorization.check(new PermissionCheck(organizationId, userId, "knowledge.view", "KNOWLEDGE",
                UUID.randomUUID(), "READ")).allowed()).isFalse();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId, "knowledge.view", "KNOWLEDGE",
                selected, "READ")).allowed()).isTrue();
    }

    @Test
    void unknownPermissionAndCrossOrganizationActorAreDenied() {
        when(store.userOverrides(organizationId, userId)).thenReturn(List.of());
        when(store.roleBindings(organizationId, roleId)).thenReturn(List.of());

        assertThat(authorization.check(new PermissionCheck(organizationId, userId, "missing.permission", "X", null, "READ"))
                .allowed()).isFalse();
        assertThat(authorization.check(new PermissionCheck(UUID.randomUUID(), userId, "project.view", "PROJECT", null, "READ"))
                .allowed()).isFalse();
    }

    @Test
    void atomicPermissionDoesNotImplyAnotherAction() {
        when(store.userOverrides(organizationId, userId)).thenReturn(List.of());
        when(store.roleBindings(organizationId, roleId)).thenReturn(List.of(
                new Binding("template.publish", "ALLOW", "ALL", List.of())));

        assertThat(authorization.check(new PermissionCheck(organizationId, userId, "template.publish", "TEMPLATE", null, "WRITE"))
                .allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId, "template.delete", "TEMPLATE", null, "WRITE"))
                .allowed()).isFalse();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId, "template.manage", "TEMPLATE", null, "WRITE"))
                .allowed()).isFalse();
    }
}
