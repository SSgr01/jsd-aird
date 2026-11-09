package com.jsd.aird.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.iam.api.DataScopeResolver;
import com.jsd.aird.iam.api.PermissionDefinitionContributor.PermissionDefinition;
import com.jsd.aird.iam.application.IamPermissionCatalog;
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
                new PermissionDefinition("experiment.view", "experiment", "查看实验记录本", "LOW", "ALL"),
                new PermissionDefinition("knowledge.view", "knowledge", "查看知识", "LOW", "SELECTED"),
                new PermissionDefinition("template.publish", "template", "发布模板", "HIGH", "ALL"),
                new PermissionDefinition("template.delete", "template", "删除模板", "CRITICAL", "ALL")));
        when(store.role(organizationId, roleId)).thenReturn(Optional.of(role));
    }

    @Test
    void systemAdminHasAllCustomerDataWithoutV2Bindings() {
        var admin = new User(userId, organizationId, "admin", "系统管理员", null, null, null,
                roleId, "SYSTEM_ADMIN", "系统管理员", "ACTIVE", false, "hash", 0, Instant.now(), 0, null);
        when(store.user(userId)).thenReturn(Optional.of(admin));
        when(store.userRoles(organizationId, userId)).thenReturn(List.of());
        when(store.permissionDefinitions()).thenReturn(IamPermissionCatalog.definitions());
        var list = new PermissionCheck(organizationId, userId, "customer.list.view", "CUSTOMER", null, "READ");
        var detail = new PermissionCheck(organizationId, userId, "customer.view", "CUSTOMER", UUID.randomUUID(), "READ");

        assertThat(authorization.resolveScope(list).scopeType()).isEqualTo("ALL");
        assertThat(authorization.check(detail).allowed()).isTrue();
        assertThat(authorization.check(detail).source()).isEqualTo("SYSTEM_ADMIN");
    }

    @Test
    void menuViewDoesNotBroadenSelectedExperimentScope() {
        var selected = UUID.randomUUID();
        var oldExperimentTarget = UUID.randomUUID();
        var resolver = mock(DataScopeResolver.class);
        authorization = new JdbcAuthorizationService(store, resolver, false);
        when(resolver.resolve(any(), any())).thenAnswer(call -> {
            Binding binding = call.getArgument(1);
            return DataScopeResolver.ResolvedDataScope.allow(binding.scopeType(),
                    new java.util.HashSet<>(binding.targetIds()));
        });
        var levelId = UUID.randomUUID();
        var level = new IamStore.PermissionLevel(levelId, organizationId, "L1_OPERATOR", "L1", "", true, true, 1, 3);
        when(store.permissionDefinitions()).thenReturn(IamPermissionCatalog.definitions());
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.roleMenus(organizationId, roleId)).thenReturn(List.of("eln.view", "project.list"));
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(level));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding("eln.view", "eln.view.view", "ALLOW", "SELECTED", List.of(oldExperimentTarget)),
                new IamStore.PermissionLevelBinding("project.list", "project.list.view", "ALLOW", "SELECTED", List.of(selected))));

        var scope = authorization.resolveScope(new PermissionCheck(organizationId, userId,
                "eln.view.view", "EXPERIMENT", null, "READ"));
        assertThat(scope.scopeType()).isEqualTo("SELECTED");
        assertThat(scope.targetIds()).containsExactly(selected);
        var legacyScope = authorization.resolveScope(new PermissionCheck(organizationId, userId,
                "experiment.view", "EXPERIMENT", null, "READ"));
        assertThat(legacyScope.scopeType()).isEqualTo("SELECTED");
        assertThat(legacyScope.targetIds()).containsExactly(selected);
    }

    @Test
    void projectBasedViewHasNoTargetsWithoutSelectedProjectList() {
        var resolver = mock(DataScopeResolver.class);
        authorization = new JdbcAuthorizationService(store, resolver, false);
        when(resolver.resolve(any(), any())).thenAnswer(call -> {
            Binding binding = call.getArgument(1);
            return binding.targetIds().isEmpty()
                    ? DataScopeResolver.ResolvedDataScope.deny(binding.scopeType(), "EMPTY_SCOPE")
                    : DataScopeResolver.ResolvedDataScope.allow(binding.scopeType(),
                            new java.util.HashSet<>(binding.targetIds()));
        });
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.roleMenus(organizationId, roleId)).thenReturn(List.of("quality.view"));
        when(store.userScopeOverrides(organizationId, userId)).thenReturn(List.of(
                new Binding("quality.view", "quality.view.view", "ALLOW", "SELECTED", List.of(UUID.randomUUID()))));
        when(store.permissionDefinitions()).thenReturn(IamPermissionCatalog.definitions());

        var scope = authorization.resolveScope(new PermissionCheck(organizationId, userId,
                "quality.view.view", "QUALITY", null, "READ"));
        assertThat(scope.allowed()).isFalse();
        assertThat(scope.reason()).isEqualTo("EMPTY_SCOPE");
    }

    @Test
    void pageCreatorScopeOverridesLegacyAllForBusinessLists() {
        var resolver = mock(DataScopeResolver.class);
        authorization = new JdbcAuthorizationService(store, resolver, false);
        when(resolver.resolve(any(), any())).thenAnswer(call -> {
            Binding binding = call.getArgument(1);
            return DataScopeResolver.ResolvedDataScope.allow(binding.scopeType());
        });
        var levelId = UUID.randomUUID();
        var level = new IamStore.PermissionLevel(levelId, organizationId, "L1", "L1", "", true, true, 1, 3);
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.roleMenus(organizationId, roleId)).thenReturn(List.of(
                "production.view", "spectrum.view", "knowledge.view", "data.view"));
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(level));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding("", "production.view", "ALLOW", "ALL", List.of()),
                new IamStore.PermissionLevelBinding("", "spectrum.view", "ALLOW", "ALL", List.of()),
                new IamStore.PermissionLevelBinding("", "knowledge.view", "ALLOW", "ALL", List.of()),
                new IamStore.PermissionLevelBinding("", "data.view", "ALLOW", "ALL", List.of())));
        when(store.userScopeOverrides(organizationId, userId)).thenReturn(List.of(
                new Binding("production.view", "production.view.view", "ALLOW", "CREATOR", List.of()),
                new Binding("spectrum.view", "spectrum.view.view", "ALLOW", "CREATOR", List.of()),
                new Binding("knowledge.view", "knowledge.view.view", "ALLOW", "CREATOR", List.of()),
                new Binding("data.view", "data.view.view", "ALLOW", "CREATOR", List.of())));

        for (var code : List.of("production.view", "spectrum.view", "knowledge.view", "data.view")) {
            var resourceType = code.substring(0, code.indexOf('.')).toUpperCase();
            var scope = authorization.resolveScope(new PermissionCheck(organizationId, userId,
                    code, resourceType, null, "READ"));
            assertThat(scope.scopeType()).isEqualTo("CREATOR");
        }
    }

    @Test
    void pageViewAllowsItsExportButNotAnotherModulesExport() {
        when(store.permissionDefinitions()).thenReturn(IamPermissionCatalog.definitions());
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.roleMenus(organizationId, roleId)).thenReturn(List.of("customer.list"));
        var customerExport = new PermissionCheck(organizationId, userId, "customer.export", "CUSTOMER", null, "READ");

        assertThat(authorization.check(customerExport).allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "project.export", "PROJECT", null, "READ")).allowed()).isFalse();

        when(store.userOperationOverrides(organizationId, userId)).thenReturn(List.of(
                new Binding("customer.list", "customer.export", "DENY", "ALL", List.of())));
        assertThat(authorization.check(customerExport).allowed()).isFalse();
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
    void customerMenuGrantsViewUnlessPersonallyDenied() {
        when(store.permissionDefinitions()).thenReturn(List.of(
                new PermissionDefinition("customer.list.view", "customer", "查看客户列表", "LOW", "ALL"),
                new PermissionDefinition("customer.view", "customer", "查看客户", "LOW", "ALL")));
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.roleMenus(organizationId, roleId)).thenReturn(List.of("customer.list"));
        var check = new PermissionCheck(organizationId, userId, "customer.list.view", "CUSTOMER", null, "READ");

        assertThat(authorization.check(check).allowed()).isTrue();
        assertThat(authorization.check(check).source()).isEqualTo("FEATURE_MENU");
        assertThat(authorization.check(new PermissionCheck(organizationId, userId, "customer.view", "CUSTOMER", null, "READ"))
                .allowed()).isTrue();

        when(store.userOperationOverrides(organizationId, userId)).thenReturn(List.of(
                new Binding("customer.list", "customer.list.view", "DENY", "ALL", List.of())));
        assertThat(authorization.check(check).allowed()).isFalse();

        when(store.userOperationOverrides(organizationId, userId)).thenReturn(List.of());
        when(store.userMenuOverrides(organizationId, userId)).thenReturn(List.of(
                new IamStore.MenuOverride("customer.list", "DENY")));
        assertThat(authorization.check(check).allowed()).isFalse();
    }

    @Test
    void customerListCodeRetainsConfiguredCreatorScope() {
        var levelId = UUID.randomUUID();
        when(store.permissionDefinitions()).thenReturn(IamPermissionCatalog.definitions());
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.roleMenus(organizationId, roleId)).thenReturn(List.of("customer.list"));
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(
                new IamStore.PermissionLevel(levelId, organizationId, "TEST", "Test", "", false, true, 1, 0)));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding("customer.list", "customer.list.view", "ALLOW", "CREATOR", List.of())));

        var scopedAuthorization = new JdbcAuthorizationService(store,
                new JdbcDataScopeResolver(mock(org.springframework.jdbc.core.JdbcTemplate.class)), false);
        var scope = scopedAuthorization.resolveScope(new PermissionCheck(organizationId, userId,
                "customer.list.view", "CUSTOMER", null, "READ", "customer.list"));

        assertThat(scope.allowed()).isTrue();
        assertThat(scope.scopeType()).isEqualTo("CREATOR");
    }

    @Test
    void projectReadAliasesUseTheConfiguredListScope() {
        var levelId = UUID.randomUUID();
        when(store.permissionDefinitions()).thenReturn(IamPermissionCatalog.definitions());
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.roleMenus(organizationId, roleId)).thenReturn(List.of("project.list"));
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(
                new IamStore.PermissionLevel(levelId, organizationId, "TEST", "Test", "", false, true, 1, 0)));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding("project.list", "project.list.view", "ALLOW", "CREATOR", List.of())));

        var scoped = new JdbcAuthorizationService(store,
                new JdbcDataScopeResolver(mock(org.springframework.jdbc.core.JdbcTemplate.class)), false);
        for (var code : List.of("project.list.view", "project.view")) {
            var result = scoped.resolveScope(new PermissionCheck(organizationId, userId,
                    code, "PROJECT", null, "READ"));
            assertThat(result.allowed()).isTrue();
            assertThat(result.scopeType()).isEqualTo("CREATOR");
        }
    }

    @Test
    void projectStageOperationUsesItsOwnMenuScope() {
        var levelId = UUID.randomUUID();
        var projectId = UUID.randomUUID();
        when(store.permissionDefinitions()).thenReturn(IamPermissionCatalog.definitions());
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(
                new IamStore.PermissionLevel(levelId, organizationId, "TEST", "Test", "", false, true, 1, 0)));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding("project.stage", "project.stage.create", "ALLOW", "SELECTED", List.of(projectId))));

        var scoped = new JdbcAuthorizationService(store,
                new JdbcDataScopeResolver(mock(org.springframework.jdbc.core.JdbcTemplate.class)), false);
        assertThat(scoped.check(new PermissionCheck(organizationId, userId,
                "project.stage.create", "PROJECT", projectId, "WRITE")).allowed()).isTrue();
        assertThat(scoped.check(new PermissionCheck(organizationId, userId,
                "project.stage.create", "PROJECT", UUID.randomUUID(), "WRITE")).allowed()).isFalse();
    }

    @Test
    void personalCustomerListScopeOverridesBroadLegacyCustomerView() {
        var levelId = UUID.randomUUID();
        when(store.permissionDefinitions()).thenReturn(IamPermissionCatalog.definitions());
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.roleMenus(organizationId, roleId)).thenReturn(List.of("customer.list"));
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(
                new IamStore.PermissionLevel(levelId, organizationId, "TEST", "Test", "", false, true, 1, 0)));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding(null, "customer.view", "ALLOW", "ALL", List.of())));
        when(store.userScopeOverrides(organizationId, userId)).thenReturn(List.of(
                new Binding("customer.list", "customer.list.view", "ALLOW", "CREATOR", List.of())));
        var scopedAuthorization = new JdbcAuthorizationService(store,
                new JdbcDataScopeResolver(mock(org.springframework.jdbc.core.JdbcTemplate.class)), false);

        var scope = scopedAuthorization.resolveScope(new PermissionCheck(organizationId, userId,
                "customer.list.view", "CUSTOMER", null, "READ", "customer.list"));

        assertThat(scope.allowed()).isTrue();
        assertThat(scope.scopeType()).isEqualTo("CREATOR");
    }

    @Test
    void projectFeatureGrantsItsViewAndTheSharedProjectRead() {
        when(store.permissionDefinitions()).thenReturn(List.of(
                new PermissionDefinition("project.task.view", "project", "查看任务", "LOW", "ALL"),
                new PermissionDefinition("project.view", "project", "查看项目", "LOW", "ALL")));
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.roleMenus(organizationId, roleId)).thenReturn(List.of("project.task"));

        assertThat(authorization.check(new PermissionCheck(organizationId, userId, "project.task.view", "PROJECT", null, "READ"))
                .allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId, "project.view", "PROJECT", null, "READ"))
                .allowed()).isTrue();
    }

    @Test
    void templatePageCreateGrantDoesNotGrantExperimentCreation() {
        when(store.permissionDefinitions()).thenReturn(List.of(
                new PermissionDefinition("template.view", "template", "查看模板", "LOW", "ALL"),
                new PermissionDefinition("template.create", "template", "新建模板", "MEDIUM", "SELF"),
                new PermissionDefinition("eln.create.create", "experiment", "新增实验", "MEDIUM", "SELF")));
        var levelId = UUID.randomUUID();
        var level = new IamStore.PermissionLevel(levelId, organizationId, "L1_OPERATOR", "L1", "", true, true, 1, 3);
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(level));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding(null, "template.view.view", "ALLOW", "ALL", List.of()),
                new IamStore.PermissionLevelBinding(null, "template.view.create", "ALLOW", "ALL", List.of()),
                new IamStore.PermissionLevelBinding(null, "eln.view.create", "ALLOW", "ALL", List.of())));

        assertThat(authorization.resolveScope(new PermissionCheck(organizationId, userId,
                "template.view", "TEMPLATE", null, "READ")).allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "template.create", "TEMPLATE", null, "WRITE")).allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "eln.create.create", "EXPERIMENT", null, "WRITE")).allowed()).isFalse();
    }

    @Test
    void moduleActionAcceptsTheBracketingPageRowGrant() {
        var levelId = UUID.randomUUID();
        var level = new IamStore.PermissionLevel(levelId, organizationId, "L1_OPERATOR", "L1", "", true, true, 1, 3);
        when(store.permissionDefinitions()).thenReturn(IamPermissionCatalog.definitions());
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.roleMenus(organizationId, roleId)).thenReturn(List.of());
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(level));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding("project.list", "project.list.update", "ALLOW", "ALL", List.of()),
                new IamStore.PermissionLevelBinding("eln.view", "eln.view.delete", "ALLOW", "ALL", List.of()),
                new IamStore.PermissionLevelBinding("data.view", "data.view.update", "ALLOW", "ALL", List.of()),
                new IamStore.PermissionLevelBinding("data.view", "data.view.create", "ALLOW", "ALL", List.of()),
                new IamStore.PermissionLevelBinding("data.view", "data.view.submit", "ALLOW", "ALL", List.of()),
                new IamStore.PermissionLevelBinding("data.view", "data.view.approve", "ALLOW", "ALL", List.of()),
                new IamStore.PermissionLevelBinding("knowledge.view", "knowledge.view.update", "ALLOW", "ALL", List.of()),
                new IamStore.PermissionLevelBinding("template.upload", "template.upload.create", "ALLOW", "ALL", List.of()),
                new IamStore.PermissionLevelBinding("knowledge.review", "knowledge.review.approve", "ALLOW", "ALL", List.of())));

        // Module-level API actions resolve to the page row the permission UI renders.
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "project.update", "PROJECT", null, "WRITE")).allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "experiment.delete", "EXPERIMENT", null, "WRITE")).allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "data.update", "DATA", null, "WRITE")).allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "data.upload.create", "DATA", null, "WRITE")).allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "data.submit", "DATA", null, "WRITE")).allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "data.approve", "DATA", null, "WRITE")).allowed()).isTrue();
        // 审核和发布必须保持独立，审核权限不能隐式获得发布权限。
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "data.view.publish", "DATA", null, "WRITE")).allowed()).isFalse();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "knowledge.update", "KNOWLEDGE", null, "WRITE")).allowed()).isTrue();
        // Actions whose API code is the menu row itself resolve to that row's actions.
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "template.upload", "TEMPLATE", null, "WRITE")).allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "knowledge.review", "KNOWLEDGE", null, "WRITE")).allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "quality.update", "QUALITY", null, "WRITE")).allowed()).isFalse();
    }

    @Test
    void userAccountMaintenanceSharesTheEditGrant() {
        when(store.permissionDefinitions()).thenReturn(IamPermissionCatalog.definitions());
        when(store.userOverrides(organizationId, userId)).thenReturn(List.of());
        when(store.roleBindings(organizationId, roleId)).thenReturn(List.of(
                new Binding("system.users.update", "ALLOW", "ALL", List.of())));

        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "system.users.status", "IAM", null, "WRITE")).allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "system.users.reset", "IAM", null, "WRITE")).allowed()).isTrue();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "system.users.logout", "IAM", null, "WRITE")).allowed()).isTrue();
        // Creating users stays a separate grant.
        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "system.users.create", "IAM", null, "WRITE")).allowed()).isFalse();
    }

    @Test
    void explicitTemplateCreateDenyOverridesPageCreateGrant() {
        when(store.permissionDefinitions()).thenReturn(List.of(
                new PermissionDefinition("template.create", "template", "新建模板", "MEDIUM", "SELF")));
        var levelId = UUID.randomUUID();
        var level = new IamStore.PermissionLevel(levelId, organizationId, "L1_OPERATOR", "L1", "", true, true, 1, 3);
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(level));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding(null, "template.view.create", "ALLOW", "ALL", List.of())));
        when(store.userOperationOverrides(organizationId, userId)).thenReturn(List.of(
                new Binding("template.create", "DENY", "ALL", List.of())));

        assertThat(authorization.check(new PermissionCheck(organizationId, userId,
                "template.create", "TEMPLATE", null, "WRITE")).allowed()).isFalse();
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

    @Test
    void v2UsesOptionalLevelAndPersonalOperationDenyHasPriority() {
        var levelId = UUID.randomUUID();
        var level = new IamStore.PermissionLevel(levelId, organizationId, "L1_OPERATOR", "L1", "", true, true, 1, 3);
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(level));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding(null, "project.view", "ALLOW", "ALL", List.of())));
        when(store.userOperationOverrides(organizationId, userId)).thenReturn(List.of(
                new Binding("project.view", "DENY", "ORG", List.of())));
        when(store.userScopeOverrides(organizationId, userId)).thenReturn(List.of());

        var decision = authorization.check(new PermissionCheck(organizationId, userId, "project.view", "PROJECT", null, "READ"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.effect()).isEqualTo("DENY");
        assertThat(decision.source()).isEqualTo("USER_OPERATION_OVERRIDE");
    }

    @Test
    void v2ScopeOverrideReplacesInheritedScope() {
        var levelId = UUID.randomUUID();
        var selected = UUID.randomUUID();
        var level = new IamStore.PermissionLevel(levelId, organizationId, "L0_VIEWER", "L0", "", true, true, 0, 1);
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(level));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding(null, "knowledge.view", "ALLOW", "ALL", List.of())));
        when(store.userOperationOverrides(organizationId, userId)).thenReturn(List.of());
        when(store.userScopeOverrides(organizationId, userId)).thenReturn(List.of(
                new Binding("knowledge.view", "ALLOW", "SELECTED", List.of(selected))));

        assertThat(authorization.check(new PermissionCheck(organizationId, userId, "knowledge.view", "KNOWLEDGE",
                UUID.randomUUID(), "READ")).allowed()).isFalse();
        assertThat(authorization.check(new PermissionCheck(organizationId, userId, "knowledge.view", "KNOWLEDGE",
                selected, "READ")).allowed()).isTrue();
    }

    @Test
    void v2UsesBroadestPersonalScopeWhenPermissionAppearsOnSeveralMenuRows() {
        var levelId = UUID.randomUUID();
        var level = new IamStore.PermissionLevel(levelId, organizationId, "L0_VIEWER", "L0", "", true, true, 0, 1);
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(level));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding("project.list", "project.view", "ALLOW", "ORG", List.of())));
        when(store.userOperationOverrides(organizationId, userId)).thenReturn(List.of());
        when(store.userScopeOverrides(organizationId, userId)).thenReturn(List.of(
                new Binding("project.info", "project.view", "ALLOW", "CREATOR", List.of()),
                new Binding("project.list", "project.view", "ALLOW", "ORG", List.of())));

        var decision = authorization.check(new PermissionCheck(
                organizationId, userId, "project.view", "PROJECT", null, "READ"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.scopeType()).isEqualTo("ORG");
    }

    @Test
    void v2UsesOnlyTheRequestedMenuRowsPersonalScope() {
        var levelId = UUID.randomUUID();
        var level = new IamStore.PermissionLevel(levelId, organizationId, "L0_VIEWER", "L0", "", true, true, 0, 1);
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(level));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding("project.list", "project.view", "ALLOW", "ORG", List.of())));
        when(store.userOperationOverrides(organizationId, userId)).thenReturn(List.of());
        when(store.userScopeOverrides(organizationId, userId)).thenReturn(List.of(
                new Binding("project.document", "project.view", "ALLOW", "CREATOR", List.of())));

        var decision = authorization.check(new PermissionCheck(
                organizationId, userId, "project.view", "PROJECT", null, "READ", "project.list"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.scopeType()).isEqualTo("ORG");
    }

    @Test
    void v2AcceptsExperimentPermissionStoredOnAnElnMenuRow() {
        var levelId = UUID.randomUUID();
        var level = new IamStore.PermissionLevel(levelId, organizationId, "L0_VIEWER", "L0", "", true, true, 0, 1);
        when(store.userRoles(organizationId, userId)).thenReturn(List.of(role));
        when(store.userPermissionLevel(organizationId, userId)).thenReturn(Optional.of(levelId));
        when(store.permissionLevel(organizationId, levelId)).thenReturn(Optional.of(level));
        when(store.permissionLevelBindings(organizationId, levelId)).thenReturn(List.of(
                new IamStore.PermissionLevelBinding("eln.view", "experiment.view", "ALLOW", "ORG", List.of())));
        when(store.userOperationOverrides(organizationId, userId)).thenReturn(List.of());
        when(store.userScopeOverrides(organizationId, userId)).thenReturn(List.of());

        var decision = authorization.check(new PermissionCheck(
                organizationId, userId, "experiment.view", "EXPERIMENT", null, "READ"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.source()).isEqualTo("PERMISSION_LEVEL:L0_VIEWER");
    }
}
