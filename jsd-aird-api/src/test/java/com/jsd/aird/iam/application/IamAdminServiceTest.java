package com.jsd.aird.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionDecision;
import com.jsd.aird.iam.api.PermissionDefinitionContributor;
import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.iam.application.port.IamStore.Binding;
import com.jsd.aird.iam.application.port.IamStore.Role;
import com.jsd.aird.iam.application.port.IamStore.User;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.security.crypto.password.PasswordEncoder;

class IamAdminServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    private final IamStore store = mock(IamStore.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AuditLogFacade audit = mock(AuditLogFacade.class);
    private final Actor actor = new Actor(ORGANIZATION_ID, ACTOR_ID, "admin");
    private final AuthorizationService authorization = check ->
            new PermissionDecision(true, check.permissionCode(), "ALLOW", "ALL", "TEST");
    private IamAdminService service;

    @BeforeEach
    void setUp() {
        service = new IamAdminService(store, authorization, passwordEncoder, audit);
    }

    @Test
    void savesSelectedAllowBindingAndReturnsNewRoleVersion() {
        var role = role(UUID.randomUUID(), "RND_MANAGER", false, 3);
        var targetId = UUID.randomUUID();
        when(store.role(ORGANIZATION_ID, role.id())).thenReturn(Optional.of(role));
        when(store.permissionDefinitions()).thenReturn(List.of(permission("knowledge.view")));
        when(store.roleVersion(ORGANIZATION_ID, role.id())).thenReturn(4L);

        var version = service.saveRolePermissions(actor, role.id(), 3,
                List.of(new Binding("knowledge.view", "ALLOW", "SELECTED", List.of(targetId))));

        assertThat(version).isEqualTo(4L);
        verify(store).replaceRoleBindings(eq(ORGANIZATION_ID), eq(role.id()), eq(3L), anyList());
        verify(audit).append(eq(ORGANIZATION_ID), eq(ACTOR_ID), eq("IAM_ROLE_PERMISSIONS_UPDATED"),
                eq("ROLE"), eq(role.id()), any());
    }

    @Test
    void rejectsDenyTargetsAndEmptySelectedRanges() {
        var role = role(UUID.randomUUID(), "RND_MANAGER", false, 0);
        when(store.role(ORGANIZATION_ID, role.id())).thenReturn(Optional.of(role));
        when(store.permissionDefinitions()).thenReturn(List.of(permission("knowledge.view")));

        assertApiError(() -> service.saveRolePermissions(actor, role.id(), 0,
                List.of(new Binding("knowledge.view", "DENY", "ALL", List.of(UUID.randomUUID())))),
                ApiErrorCode.BAD_REQUEST);
        assertApiError(() -> service.saveRolePermissions(actor, role.id(), 0,
                List.of(new Binding("knowledge.view", "ALLOW", "SELECTED", List.of()))),
                ApiErrorCode.BAD_REQUEST);
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsUnknownPermissionAndPolicyVersionConflict() {
        var role = role(UUID.randomUUID(), "RND_MANAGER", false, 0);
        when(store.role(ORGANIZATION_ID, role.id())).thenReturn(Optional.of(role));
        when(store.permissionDefinitions()).thenReturn(List.of(permission("knowledge.view")));

        assertApiError(() -> service.saveRolePermissions(actor, role.id(), 0,
                List.of(new Binding("unknown.permission", "ALLOW", "ALL", List.of()))),
                ApiErrorCode.BAD_REQUEST);

        doThrow(new IamStore.PolicyVersionConflict()).when(store)
                .replaceRoleBindings(eq(ORGANIZATION_ID), eq(role.id()), eq(0L), anyList());
        assertApiError(() -> service.saveRolePermissions(actor, role.id(), 0,
                List.of(new Binding("knowledge.view", "ALLOW", "ALL", List.of()))),
                ApiErrorCode.POLICY_VERSION_CONFLICT);
    }

    @Test
    void blocksCrossOrganizationUserAccessAndLastAdminDisable() {
        var userId = UUID.randomUUID();
        when(store.user(userId)).thenReturn(Optional.of(user(userId, UUID.randomUUID(), "RND_ENGINEER", "ACTIVE")));
        assertApiError(() -> service.user(actor, userId), ApiErrorCode.NOT_FOUND);

        var adminId = UUID.randomUUID();
        when(store.user(adminId)).thenReturn(Optional.of(user(adminId, ORGANIZATION_ID, "SYSTEM_ADMIN", "ACTIVE")));
        when(store.activeSystemAdmins(ORGANIZATION_ID)).thenReturn(1L);
        assertApiError(() -> service.setStatus(actor, adminId, false), ApiErrorCode.OPERATION_FORBIDDEN);
        verify(store).activeSystemAdmins(ORGANIZATION_ID);
    }

    @Test
    void protectsBuiltinRolesAndRequiresUnboundCustomRoleForDeletion() {
        var builtin = role(UUID.randomUUID(), "SYSTEM_ADMIN", true, 0);
        when(store.role(ORGANIZATION_ID, builtin.id())).thenReturn(Optional.of(builtin));
        doThrow(new IllegalArgumentException("内置角色不可删除")).when(store).deleteRole(ORGANIZATION_ID, builtin.id());
        assertApiError(() -> service.renameRole(actor, builtin.id(), "新名称"), ApiErrorCode.OPERATION_FORBIDDEN);
        assertApiError(() -> service.deleteRole(actor, builtin.id()), ApiErrorCode.OPERATION_FORBIDDEN);

        var custom = role(UUID.randomUUID(), "CUSTOM_ROLE", false, 0);
        when(store.role(ORGANIZATION_ID, custom.id())).thenReturn(Optional.of(custom));
        service.deleteRole(actor, custom.id());
        verify(store).deleteRole(ORGANIZATION_ID, custom.id());
    }

    @Test
    void userOverrideChangesAreAuditedAndReturnCurrentVersion() {
        var userId = UUID.randomUUID();
        when(store.user(userId)).thenReturn(Optional.of(user(userId, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE")));
        when(store.permissionDefinitions()).thenReturn(List.of(permission("project.view")));
        assertThat(service.userPermissions(actor, userId).version()).isZero();

        when(store.user(userId)).thenReturn(Optional.of(userWithVersion(userId, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE", 1)));
        var version = service.saveUserPermissions(actor, userId, 0,
                List.of(new Binding("project.view", "DENY", "ALL", List.of())));

        assertThat(version).isEqualTo(1);
        verify(store).replaceUserOverrides(eq(ORGANIZATION_ID), eq(userId), eq(0L), anyList());
        verify(audit).append(eq(ORGANIZATION_ID), eq(ACTOR_ID), eq("IAM_USER_PERMISSIONS_UPDATED"),
                eq("USER"), eq(userId), any());
    }

    private static PermissionDefinitionContributor.PermissionDefinition permission(String code) {
        return new PermissionDefinitionContributor.PermissionDefinition(code, "test", code, "LOW", "ALL");
    }

    private static Role role(UUID id, String code, boolean builtin, long version) {
        return new Role(id, ORGANIZATION_ID, code, code, builtin, true, version);
    }

    private static User user(UUID id, UUID organizationId, String roleCode, String status) {
        return userWithVersion(id, organizationId, roleCode, status, 0);
    }

    private static User userWithVersion(UUID id, UUID organizationId, String roleCode, String status, long version) {
        return new User(id, organizationId, "user-" + id.toString().substring(0, 8), "测试用户", null, null, null,
                UUID.randomUUID(), roleCode, roleCode, status, false, "hash", version, Instant.now(), 0, null);
    }

    private static void assertApiError(ThrowingCallable action, ApiErrorCode expected) {
        assertThatThrownBy(action).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).errorCode()).isEqualTo(expected));
    }
}
