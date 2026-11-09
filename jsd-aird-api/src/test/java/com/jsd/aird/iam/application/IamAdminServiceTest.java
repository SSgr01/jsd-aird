package com.jsd.aird.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.DataScopeResolver;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.iam.api.PermissionDecision;
import com.jsd.aird.iam.api.PermissionDefinitionContributor;
import com.jsd.aird.iam.api.RecordDataScope;
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
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class IamAdminServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    private final IamStore store = mock(IamStore.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AuditLogFacade audit = mock(AuditLogFacade.class);
    private final AuditLogPresentationService auditPresentation = mock(AuditLogPresentationService.class);
    private final Actor actor = new Actor(ORGANIZATION_ID, ACTOR_ID, "admin");
    private final AuthorizationService authorization = check ->
            new PermissionDecision(true, check.permissionCode(), "ALLOW", "ALL", "TEST");
    private IamAdminService service;

    @BeforeEach
    void setUp() {
        service = new IamAdminService(store, authorization, passwordEncoder, audit, auditPresentation);
        when(store.userInScope(eq(ORGANIZATION_ID), any(), any())).thenReturn(true);
    }

    @Test
    void hidesUserDetailOutsideCreatorScope() {
        var userId = UUID.randomUUID();
        when(store.userInScope(eq(ORGANIZATION_ID), eq(userId), any())).thenReturn(false);
        assertApiError(() -> service.user(actor, userId), ApiErrorCode.NOT_FOUND);
    }

    @Test
    void permissionMatrixOnlyShowsOperatorsOwnMenusAndOperations() {
        var resolver = mock(EffectivePermissionResolver.class);
        when(resolver.resolve(ORGANIZATION_ID, ACTOR_ID)).thenReturn(
                new EffectivePermissionResolver.EffectivePermissions(Set.of("system.permissions"),
                        Map.of("system.permissions.view", new Binding("system.permissions.view", "ALLOW", "CREATOR", List.of())), false));
        when(store.permissionDefinitions()).thenReturn(List.of(
                permission("system.permissions.view"), permission("system.permissions.update")));
        when(store.permissionLevels(ORGANIZATION_ID)).thenReturn(List.of());
        var scoped = new IamAdminService(store, authorization, passwordEncoder, audit, auditPresentation, resolver);

        var model = scoped.permissionModel(actor);

        assertThat(model.menus()).extracting(IamAdminService.MenuView::key).containsExactly("system.permissions");
        assertThat(model.operations()).extracting(PermissionDefinitionContributor.PermissionDefinition::code)
                .containsExactly("system.permissions.view");
        assertThat(model.delegableScopes().get("system.permissions.view").scopeType()).isEqualTo("CREATOR");
        assertThat(model.unrestricted()).isFalse();
    }

    @Test
    void restrictedPermissionModelShowsRoleAndLevelTabsWithOwnedPermissionsOnly() {
        var resolver = mock(EffectivePermissionResolver.class);
        when(resolver.resolve(ORGANIZATION_ID, ACTOR_ID)).thenReturn(
                new EffectivePermissionResolver.EffectivePermissions(Set.of("system.permissions"), Map.of(), false));
        var ownRole = role(UUID.randomUUID(), "RND_ENGINEER", false, 0);
        var otherRole = role(UUID.randomUUID(), "OTHER", false, 0);
        var ownLevel = new IamStore.PermissionLevel(UUID.randomUUID(), ORGANIZATION_ID, "OWN", "自己的等级", null, false, true, 1, 0);
        var otherLevel = new IamStore.PermissionLevel(UUID.randomUUID(), ORGANIZATION_ID, "OTHER", "其他等级", null, false, true, 1, 0);
        when(store.userRoles(ORGANIZATION_ID, ACTOR_ID)).thenReturn(List.of(ownRole));
        when(store.userPermissionLevel(ORGANIZATION_ID, ACTOR_ID)).thenReturn(Optional.of(ownLevel.id()));
        when(store.permissionLevels(ORGANIZATION_ID)).thenReturn(List.of(ownLevel, otherLevel));
        when(store.role(ORGANIZATION_ID, ownRole.id())).thenReturn(Optional.of(ownRole));
        when(store.role(ORGANIZATION_ID, otherRole.id())).thenReturn(Optional.of(otherRole));
        when(store.roleMenus(ORGANIZATION_ID, ownRole.id())).thenReturn(List.of());
        var scoped = new IamAdminService(store, authorization, passwordEncoder, audit, auditPresentation, resolver);

        var model = scoped.permissionModel(actor);

        assertThat(model.levels()).extracting(IamAdminService.PermissionLevelView::id)
                .containsExactly(ownLevel.id(), otherLevel.id());
        assertThat(scoped.roleMenus(actor, ownRole.id()).menuKeys()).isEmpty();
    }

    @Test
    void restrictedOperatorCanEditOwnedRoleMenuWithoutRemovingHiddenMenus() {
        var resolver = mock(EffectivePermissionResolver.class);
        when(resolver.resolve(ORGANIZATION_ID, ACTOR_ID)).thenReturn(
                new EffectivePermissionResolver.EffectivePermissions(Set.of("system.permissions"), Map.of(), false));
        var targetRole = role(UUID.randomUUID(), "CUSTOM_ROLE", false, 0);
        when(store.role(ORGANIZATION_ID, targetRole.id())).thenReturn(Optional.of(targetRole));
        when(store.roleMenus(ORGANIZATION_ID, targetRole.id())).thenReturn(List.of("customer.list"));
        var scoped = new IamAdminService(store, authorization, passwordEncoder, audit, auditPresentation, resolver);

        scoped.saveRoleMenus(actor, targetRole.id(), 0, List.of("system.permissions"));

        verify(store).replaceRoleMenus(ORGANIZATION_ID, targetRole.id(), 0,
                List.of("system.permissions", "customer.list"));
        assertApiError(() -> scoped.saveRoleMenus(actor, targetRole.id(), 0, List.of("project.list")),
                ApiErrorCode.OPERATION_FORBIDDEN);
    }

    @Test
    void restrictedOperatorCanEditOwnedLevelBindingWithoutRemovingHiddenBindings() {
        var resolver = mock(EffectivePermissionResolver.class);
        when(resolver.resolve(ORGANIZATION_ID, ACTOR_ID)).thenReturn(
                new EffectivePermissionResolver.EffectivePermissions(Set.of("system.permissions"),
                        Map.of("system.permissions.view", new Binding("system.permissions.view", "ALLOW", "CREATOR", List.of())), false));
        var level = new IamStore.PermissionLevel(UUID.randomUUID(), ORGANIZATION_ID, "CUSTOM", "自定义", null, false, true, 1, 0);
        when(store.permissionLevel(ORGANIZATION_ID, level.id())).thenReturn(Optional.of(level));
        when(store.permissionDefinitions()).thenReturn(List.of(permission("system.permissions.view")));
        var hidden = new IamStore.PermissionLevelBinding("project.list", "project.list.view", "ALLOW", "ALL", List.of());
        when(store.permissionLevelBindings(ORGANIZATION_ID, level.id())).thenReturn(List.of(hidden));
        var scoped = new IamAdminService(store, authorization, passwordEncoder, audit, auditPresentation, resolver);
        var owned = new IamStore.PermissionLevelBinding("system.permissions", "system.permissions.view", "ALLOW", "CREATOR", List.of());

        scoped.savePermissionLevelBindings(actor, level.id(), 0, List.of(owned));

        verify(store).replacePermissionLevelBindings(ORGANIZATION_ID, level.id(), 0, List.of(owned, hidden));
        var excessive = new IamStore.PermissionLevelBinding("system.permissions", "system.permissions.view", "ALLOW", "ALL", List.of());
        assertApiError(() -> scoped.savePermissionLevelBindings(actor, level.id(), 0, List.of(excessive)),
                ApiErrorCode.OPERATION_FORBIDDEN);
    }

    @Test
    void standardMethodAllowsSelectedTestStandardFiles() {
        var level = new IamStore.PermissionLevel(UUID.randomUUID(), ORGANIZATION_ID, "CUSTOM", "自定义", null, false, true, 1, 0);
        var methodId = UUID.randomUUID();
        var oldBinding = new IamStore.PermissionLevelBinding("research-test.standard", "research-test.standard.view",
                "ALLOW", "SELECTED", List.of(methodId));
        when(store.permissionLevel(ORGANIZATION_ID, level.id())).thenReturn(Optional.of(level));
        when(store.permissionDefinitions()).thenReturn(List.of(permission("research-test.standard.view")));
        when(store.permissionLevelBindings(ORGANIZATION_ID, level.id())).thenReturn(List.of(oldBinding));

        service.savePermissionLevelBindings(actor, level.id(), 0, List.of(oldBinding));
        verify(store).replacePermissionLevelBindings(ORGANIZATION_ID, level.id(), 0, List.of(oldBinding));

        var newBinding = new IamStore.PermissionLevelBinding("research-test.standard", "research-test.standard.view",
                "ALLOW", "SELECTED", List.of(UUID.randomUUID()));
        service.savePermissionLevelBindings(actor, level.id(), 0, List.of(newBinding));
        verify(store).replacePermissionLevelBindings(ORGANIZATION_ID, level.id(), 0, List.of(newBinding));
    }

    @Test
    void cannotChangeOwnPersonalPermissions() {
        when(store.user(ACTOR_ID)).thenReturn(Optional.of(user(ACTOR_ID, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE")));
        assertApiError(() -> service.saveUserModel(actor, ACTOR_ID,
                new IamAdminService.SaveUserModelCommand(List.of(), null, null, List.of(), List.of(), List.of(), 0)),
                ApiErrorCode.OPERATION_FORBIDDEN);
        assertApiError(() -> service.saveUserPermissions(actor, ACTOR_ID, 0, List.of()),
                ApiErrorCode.OPERATION_FORBIDDEN);
        assertApiError(() -> service.deleteUserOverride(actor, ACTOR_ID, "customer.view", 0),
                ApiErrorCode.OPERATION_FORBIDDEN);
        assertApiError(() -> service.update(actor, ACTOR_ID,
                new IamAdminService.UpdateUserCommand(0, "自己", null, null, null, UUID.randomUUID())),
                ApiErrorCode.OPERATION_FORBIDDEN);
    }

    @Test
    void cannotChangeRoleOrLevelAssignedToSelf() {
        var ownRole = role(UUID.randomUUID(), "RND_ENGINEER", false, 0);
        var ownLevel = new IamStore.PermissionLevel(UUID.randomUUID(), ORGANIZATION_ID, "OWN", "自己的等级", null, false, true, 1, 0);
        when(store.userRoles(ORGANIZATION_ID, ACTOR_ID)).thenReturn(List.of(ownRole));
        when(store.userPermissionLevel(ORGANIZATION_ID, ACTOR_ID)).thenReturn(Optional.of(ownLevel.id()));
        when(store.role(ORGANIZATION_ID, ownRole.id())).thenReturn(Optional.of(ownRole));

        assertApiError(() -> service.saveRoleMenus(actor, ownRole.id(), 0, List.of()),
                ApiErrorCode.OPERATION_FORBIDDEN);
        assertApiError(() -> service.deleteRole(actor, ownRole.id(), 0),
                ApiErrorCode.OPERATION_FORBIDDEN);
        assertApiError(() -> service.savePermissionLevelBindings(actor, ownLevel.id(), 0, List.of()),
                ApiErrorCode.OPERATION_FORBIDDEN);
        assertApiError(() -> service.deletePermissionLevel(actor, ownLevel.id()),
                ApiErrorCode.OPERATION_FORBIDDEN);
    }

    @Test
    void rejectsDelegatingAnOperationOrScopeNotOwnedByOperator() {
        var resolver = mock(EffectivePermissionResolver.class);
        when(resolver.resolve(ORGANIZATION_ID, ACTOR_ID)).thenReturn(
                new EffectivePermissionResolver.EffectivePermissions(Set.of("system.permissions"),
                        Map.of("system.permissions.view", new Binding("system.permissions.view", "ALLOW", "CREATOR", List.of())), false));
        var target = UUID.randomUUID();
        when(store.user(target)).thenReturn(Optional.of(user(target, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE")));
        when(store.userRoles(ORGANIZATION_ID, target)).thenReturn(List.of());
        when(store.userPermissionLevel(ORGANIZATION_ID, target)).thenReturn(Optional.empty());
        when(store.permissionDefinitions()).thenReturn(List.of(permission("system.permissions.view"),
                permission("system.permissions.update")));
        var scoped = new IamAdminService(store, authorization, passwordEncoder, audit, auditPresentation, resolver);
        var roleIds = List.of(store.user(target).orElseThrow().roleId());
        when(store.role(ORGANIZATION_ID, roleIds.get(0)))
                .thenReturn(Optional.of(role(roleIds.get(0), "RND_ENGINEER", false, 0)));

        assertApiError(() -> scoped.saveUserModel(actor, target,
                new IamAdminService.SaveUserModelCommand(roleIds, roleIds.get(0), null, List.of(),
                        List.of(new Binding("system.permissions.update", "ALLOW", "CREATOR", List.of())), List.of(), 0)),
                ApiErrorCode.OPERATION_FORBIDDEN);
        assertApiError(() -> scoped.saveUserModel(actor, target,
                new IamAdminService.SaveUserModelCommand(roleIds, roleIds.get(0), null, List.of(), List.of(),
                        List.of(new Binding("system.permissions.view", "ALLOW", "ALL", List.of())), 0)),
                ApiErrorCode.OPERATION_FORBIDDEN);
    }

    @Test
    void restrictedSavePreservesOverridesOutsideOperatorsView() {
        var resolver = mock(EffectivePermissionResolver.class);
        when(resolver.resolve(ORGANIZATION_ID, ACTOR_ID)).thenReturn(
                new EffectivePermissionResolver.EffectivePermissions(Set.of("system.permissions"),
                        Map.of("system.permissions.view", new Binding("system.permissions.view", "ALLOW", "CREATOR", List.of())), false));
        var target = UUID.randomUUID();
        var targetUser = user(target, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE");
        when(store.user(target)).thenReturn(Optional.of(targetUser));
        when(store.userRoles(ORGANIZATION_ID, target)).thenReturn(List.of());
        when(store.userPermissionLevel(ORGANIZATION_ID, target)).thenReturn(Optional.empty());
        when(store.role(ORGANIZATION_ID, targetUser.roleId()))
                .thenReturn(Optional.of(role(targetUser.roleId(), "RND_ENGINEER", false, 0)));
        when(store.permissionDefinitions()).thenReturn(List.of(permission("system.permissions.view")));
        var hidden = new Binding("project.list", "project.list.view", "ALLOW", "ALL", List.of());
        when(store.userOperationOverrides(ORGANIZATION_ID, target)).thenReturn(List.of(hidden));
        var scoped = new IamAdminService(store, authorization, passwordEncoder, audit, auditPresentation, resolver);

        scoped.saveUserModel(actor, target, new IamAdminService.SaveUserModelCommand(
                List.of(targetUser.roleId()), targetUser.roleId(), null, List.of(), List.of(), List.of(), 0));

        verify(store).replaceUserModelOverrides(eq(ORGANIZATION_ID), eq(target), eq(List.of()),
                eq(List.of(hidden)), eq(List.of()));
    }

    @Test
    void preservesOrgScopeWhenSavingUserModel() {
        var targetId = UUID.randomUUID();
        when(store.user(targetId)).thenReturn(Optional.of(user(targetId, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE")));
        when(store.permissionDefinitions()).thenReturn(List.of(permission("customer.create")));
        var command = new IamAdminService.SaveUserModelCommand(List.of(), null, null, List.of(), List.of(),
                List.of(new Binding("customer.list", "customer.create", "ALLOW", "ORG", List.of())), 0);

        service.saveUserModel(actor, targetId, command);

        verify(store).replaceUserModelOverrides(eq(ORGANIZATION_ID), eq(targetId), eq(List.of()), eq(List.of()),
                eq(List.of(new Binding("customer.list", "customer.create", "ALLOW", "ORG", List.of()))));
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
        assertApiError(() -> service.setStatus(actor, adminId, false,0), ApiErrorCode.OPERATION_FORBIDDEN);
        verify(store).activeSystemAdmins(ORGANIZATION_ID);
    }

    @Test
    void protectsBuiltinRolesAndRequiresUnboundCustomRoleForDeletion() {
        var builtin = role(UUID.randomUUID(), "SYSTEM_ADMIN", true, 0);
        when(store.role(ORGANIZATION_ID, builtin.id())).thenReturn(Optional.of(builtin));
        doThrow(new IllegalArgumentException("内置角色不可删除")).when(store).deleteRole(ORGANIZATION_ID, builtin.id());
        assertApiError(() -> service.renameRole(actor, builtin.id(), "新名称",0), ApiErrorCode.OPERATION_FORBIDDEN);
        assertApiError(() -> service.deleteRole(actor, builtin.id(),0), ApiErrorCode.OPERATION_FORBIDDEN);

        var custom = role(UUID.randomUUID(), "CUSTOM_ROLE", false, 0);
        when(store.role(ORGANIZATION_ID, custom.id())).thenReturn(Optional.of(custom));
        service.deleteRole(actor, custom.id(),0);
        verify(store).deleteRole(ORGANIZATION_ID, custom.id());
    }

    @Test
    void userAdministrationAcceptsTheAtomicPermissionGrantedByTheMatrix() {
        var service = serviceGranting("system.users.update", "system.user.view");
        var userId = UUID.randomUUID();
        var role = role(UUID.randomUUID(), "RND_ENGINEER", false, 0);
        when(store.user(userId)).thenReturn(Optional.of(user(userId, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE")));
        when(store.role(ORGANIZATION_ID, role.id())).thenReturn(Optional.of(role));

        var updated = service.update(actor, userId, new IamAdminService.UpdateUserCommand(0, "新姓名", null, null, null, role.id()));

        assertThat(updated.id()).isEqualTo(userId);
        verify(store).updateUser(ORGANIZATION_ID, userId, "新姓名", null, null, null, role.id());
    }

    @Test
    void userAdministrationStillAcceptsTheLegacyManagePermission() {
        var service = serviceGranting("system.user.manage", "system.user.view");
        var userId = UUID.randomUUID();
        var role = role(UUID.randomUUID(), "RND_ENGINEER", false, 0);
        when(store.user(userId)).thenReturn(Optional.of(user(userId, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE")));
        when(store.role(ORGANIZATION_ID, role.id())).thenReturn(Optional.of(role));

        service.update(actor, userId, new IamAdminService.UpdateUserCommand(0, "新姓名", null, null, null, role.id()));

        verify(store).updateUser(ORGANIZATION_ID, userId, "新姓名", null, null, null, role.id());
    }

    @Test
    void userAdministrationIsDeniedWithoutEitherPermission() {
        var service = serviceGranting("system.user.view");
        var userId = UUID.randomUUID();
        var role = role(UUID.randomUUID(), "RND_ENGINEER", false, 0);
        when(store.user(userId)).thenReturn(Optional.of(user(userId, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE")));

        assertApiError(() -> service.update(actor, userId,
                new IamAdminService.UpdateUserCommand(0, "新姓名", null, null, null, role.id())), ApiErrorCode.PERMISSION_DENIED);
        verify(store, org.mockito.Mockito.never()).updateUser(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void userOverrideChangesAreAuditedAndReturnCurrentVersion() {
        var userId = UUID.randomUUID();
        when(store.user(userId)).thenReturn(Optional.of(user(userId, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE")));
        when(store.permissionDefinitions()).thenReturn(List.of(permission("project.view")));
        assertThat(service.userPermissions(actor, userId).version()).isZero();

        when(store.user(userId)).thenReturn(Optional.of(user(userId, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE")),Optional.of(user(userId, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE")),Optional.of(userWithVersion(userId, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE", 1)));
        var version = service.saveUserPermissions(actor, userId, 0,
                List.of(new Binding("project.view", "DENY", "ALL", List.of())));

        assertThat(version).isEqualTo(1);
        verify(store).replaceUserOverrides(eq(ORGANIZATION_ID), eq(userId), eq(0L), anyList());
        verify(audit).append(eq(ORGANIZATION_ID), eq(ACTOR_ID), eq("IAM_USER_PERMISSIONS_UPDATED"),
                eq("USER"), eq(userId), any());
    }

    @Test
    void rejectsStaleAdministrativeActionsBeforeChangingSessionsPasswordsOrRoles() {
        var id = UUID.randomUUID();
        when(store.user(id)).thenReturn(Optional.of(userWithVersion(id, ORGANIZATION_ID, "RND_ENGINEER", "ACTIVE", 3)));
        var custom = role(UUID.randomUUID(), "CUSTOM", false, 4);
        when(store.role(ORGANIZATION_ID, custom.id())).thenReturn(Optional.of(custom));
        assertApiError(() -> service.setStatus(actor,id,false,2),ApiErrorCode.POLICY_VERSION_CONFLICT);
        assertApiError(() -> service.resetPassword(actor,id,"password",2),ApiErrorCode.POLICY_VERSION_CONFLICT);
        assertApiError(() -> service.forceLogout(actor,id,2),ApiErrorCode.POLICY_VERSION_CONFLICT);
        assertApiError(() -> service.renameRole(actor,custom.id(),"new",3),ApiErrorCode.POLICY_VERSION_CONFLICT);
        assertApiError(() -> service.deleteRole(actor,custom.id(),3),ApiErrorCode.POLICY_VERSION_CONFLICT);
        verifyNoInteractions(passwordEncoder,audit);
        verify(store,org.mockito.Mockito.never()).revokeUserSessions(ORGANIZATION_ID,id);
        verify(store,org.mockito.Mockito.never()).deleteRole(ORGANIZATION_ID,custom.id());
    }

    /** An administrator holding exactly the given permission codes. */
    private IamAdminService serviceGranting(String... permissionCodes) {
        var granted = Set.of(permissionCodes);
        AuthorizationService restricting = check -> granted.contains(check.permissionCode())
                ? new PermissionDecision(true, check.permissionCode(), "ALLOW", "ALL", "TEST")
                : PermissionDecision.denied(check.permissionCode());
        return new IamAdminService(store, restricting, passwordEncoder, audit, auditPresentation);
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

    @Test
    void appliesUserScopeByPermissionView() {
        var targetId = UUID.randomUUID();
        var scopedAuthorization = mock(AuthorizationService.class);
        when(scopedAuthorization.check(any())).thenReturn(
                new PermissionDecision(true, "system.user.view", "ALLOW", "SELECTED", "TEST"));
        when(scopedAuthorization.resolveScope(any())).thenReturn(
                DataScopeResolver.ResolvedDataScope.allow("SELECTED", Set.of(targetId)));
        when(store.users(any(), any(), anyInt(), anyInt(), any())).thenReturn(List.of());
        when(store.countUsers(any(), any(), any())).thenReturn(0L);
        var scopedService = new IamAdminService(store, scopedAuthorization, passwordEncoder, audit, auditPresentation);
        var expectedScope = new RecordDataScope("SELECTED", ACTOR_ID, Set.of(targetId));

        scopedService.users(actor, null, 1, 20, "permissions");
        scopedService.users(actor, null, 1, 20);

        verify(store, times(2)).users(eq(ORGANIZATION_ID), isNull(), eq(1), eq(20), eq(expectedScope));
        verify(store, times(2)).countUsers(eq(ORGANIZATION_ID), isNull(), eq(expectedScope));
        var captor = ArgumentCaptor.forClass(PermissionCheck.class);
        verify(scopedAuthorization, times(2)).resolveScope(captor.capture());
        assertThat(captor.getAllValues()).extracting(PermissionCheck::permissionCode)
                .containsExactly("system.permissions.view", "system.users.view");
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
