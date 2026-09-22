package com.jsd.aird.iam.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.iam.application.port.IamStore.Binding;
import com.jsd.aird.iam.application.port.IamStore.PermissionLevelBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IamBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(IamBootstrapService.class);
    private final IamStore store;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String organizationName;
    private final String adminUsername;
    private final String adminPassword;

    public IamBootstrapService(
            IamStore store,
            PasswordEncoder passwordEncoder,
            @Value("${app.iam.bootstrap.enabled:false}") boolean enabled,
            @Value("${app.iam.bootstrap.organization-name:本地开发组织}") String organizationName,
            @Value("${app.iam.bootstrap.admin-username:admin}") String adminUsername,
            @Value("${app.iam.bootstrap.admin-password:}") String adminPassword
    ) {
        this.store = store;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.organizationName = organizationName;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initialize() {
        var organization = store.defaultOrganization().orElseGet(() ->
                enabled ? store.ensureDefaultOrganization(organizationName) : null);
        if (organization == null) {
            log.warn("IAM 默认组织不存在，跳过权限种子初始化");
            return;
        }

        IamPermissionCatalog.definitions().forEach(store::ensurePermission);
        var roles = seedRoles(organization.id());
        seedRolePermissions(organization.id(), roles);
        seedPermissionModel(organization.id(), roles);

        if (enabled && !store.hasSystemAdmin(organization.id())) {
            createBootstrapAdmin(organization.id(), roles.get("SYSTEM_ADMIN").id());
            // The bootstrap account is inserted after the initial seed; run
            // the idempotent v2 backfill once more so its role/level profile
            // is available without waiting for the next restart.
            seedPermissionModel(organization.id(), roles);
        }
    }

    private java.util.Map<String, IamStore.Role> seedRoles(UUID organizationId) {
        return java.util.Map.of(
                "SYSTEM_ADMIN", store.ensureRole(organizationId, "SYSTEM_ADMIN", "系统管理员", true),
                "RND_MANAGER", store.ensureRole(organizationId, "RND_MANAGER", "研发负责人", true),
                "RND_ENGINEER", store.ensureRole(organizationId, "RND_ENGINEER", "研发工程师", true),
                "QUALITY_MANAGER", store.ensureRole(organizationId, "QUALITY_MANAGER", "质量负责人", true),
                "PRODUCTION_OPERATOR", store.ensureRole(organizationId, "PRODUCTION_OPERATOR", "生产管理员", true)
        );
    }

    private void seedRolePermissions(UUID organizationId, java.util.Map<String, IamStore.Role> roles) {
        var all = IamPermissionCatalog.definitions().stream().map(def -> def.code()).toList();
        var responsibility = List.of(
                "customer.view", "customer.create", "customer.update",
                "project.view", "project.export", "project.create", "project.update", "project.copy", "project.delete", "project.assign",
                "template.view", "template.create", "template.update", "template.upload", "template.copy",
                "template.recognition", "template.review", "template.publish", "template.rollback",
                "template.delete", "template.export", "category.create", "category.update",
                "category.delete", "experiment.view", "experiment.create", "experiment.update", "experiment.delete",
                "experiment.submit", "experiment.approve", "knowledge.view", "knowledge.upload",
                "research-test.report.view", "research-test.report.create", "research-test.report.update", "research-test.report.delete",
                "research-test.report.submit", "research-test.report.approve", "research-test.report.publish", "research-test.report.export",
                "research-test.standard.view", "research-test.standard.create", "research-test.standard.update", "research-test.standard.delete",
                "research-test.standard.submit", "research-test.standard.approve", "research-test.standard.publish", "research-test.standard.export",
                "knowledge.create", "knowledge.update", "knowledge.submit", "knowledge.review",
                "knowledge.approve", "knowledge.publish", "knowledge.export", "knowledge.download",
                "data.view", "data.create", "data.update", "data.submit", "data.approve", "data.export",
                "data.download", "quality.view", "quality.upload", "quality.create", "quality.update",
                "quality.delete", "quality.publish", "quality.export", "spectrum.view", "spectrum.create", "spectrum.update", "spectrum.export",
                "spectrum.download", "ai.use", "production.view", "production.create", "production.update",
                "production.submit", "production.cancel", "production.export", "ops.file.view", "ops.file.upload", "ops.file.download");
        var inventory = List.of("inventory.view", "inventory.create", "inventory.update", "inventory.reverse");
        var production = List.of("production.view", "production.create", "production.update", "production.submit",
                "production.cancel", "template.view", "data.view", "ops.file.view", "ops.file.upload", "ops.file.download");
        var quality = List.of("quality.view", "quality.upload", "quality.create", "quality.update", "quality.delete",
                "quality.publish", "quality.export", "knowledge.view", "knowledge.upload", "knowledge.create",
                "knowledge.update", "knowledge.submit", "knowledge.review", "knowledge.approve", "knowledge.publish",
                "data.view", "data.create", "data.update", "data.approve", "spectrum.view", "spectrum.create",
                "spectrum.update", "experiment.view", "project.view", "ops.file.view", "ops.file.upload", "ops.file.download");

        all.forEach(code -> store.ensureRoleBinding(organizationId, roles.get("SYSTEM_ADMIN").id(), new Binding(code, "ALLOW", "ALL", List.of())));
        responsibility.forEach(code -> store.ensureRoleBinding(organizationId, roles.get("RND_MANAGER").id(), new Binding(code, "ALLOW", defaultScope(code), List.of())));
        inventory.forEach(code -> store.ensureRoleBinding(organizationId, roles.get("RND_MANAGER").id(), new Binding(code, "ALLOW", defaultScope(code), List.of())));
                responsibility.stream().filter(code -> Set.of("customer.view", "project.view", "project.export", "project.create", "project.update",
                        "template.view", "template.create", "template.update", "template.upload", "template.copy",
                        "template.recognition", "experiment.view", "experiment.create", "experiment.update",
                        "experiment.submit", "knowledge.view", "knowledge.upload", "knowledge.create",
                        "research-test.report.view", "research-test.report.create", "research-test.report.update", "research-test.report.submit", "research-test.report.export",
                        "research-test.standard.view", "research-test.standard.create", "research-test.standard.update", "research-test.standard.submit", "research-test.standard.export",
                        "knowledge.update", "knowledge.submit", "data.view", "data.create", "data.update",
                        "data.submit", "spectrum.view", "spectrum.create", "spectrum.update", "ai.use",
                        "production.view", "production.create", "production.update", "production.submit",
                        "ops.file.view", "ops.file.upload").contains(code))
                .forEach(code -> store.ensureRoleBinding(organizationId, roles.get("RND_ENGINEER").id(), new Binding(code, "ALLOW", defaultScope(code), List.of())));
        inventory.stream().filter(code -> Set.of("inventory.view", "inventory.create", "inventory.update").contains(code))
                .forEach(code -> store.ensureRoleBinding(organizationId, roles.get("RND_ENGINEER").id(), new Binding(code, "ALLOW", defaultScope(code), List.of())));
        quality.forEach(code -> store.ensureRoleBinding(organizationId, roles.get("QUALITY_MANAGER").id(), new Binding(code, "ALLOW", defaultScope(code), List.of())));
        production.forEach(code -> store.ensureRoleBinding(organizationId, roles.get("PRODUCTION_OPERATOR").id(), new Binding(code, "ALLOW", defaultScope(code), List.of())));
        inventory.stream().filter(code -> Set.of("inventory.view", "inventory.create", "inventory.update").contains(code))
                .forEach(code -> store.ensureRoleBinding(organizationId, roles.get("PRODUCTION_OPERATOR").id(), new Binding(code, "ALLOW", defaultScope(code), List.of())));
    }

    private String defaultScope(String code) {
        return IamPermissionCatalog.definitions().stream().filter(def -> def.code().equals(code))
                .map(def -> def.defaultScope()).findFirst().orElse("ALL");
    }

    private void createBootstrapAdmin(UUID organizationId, UUID adminRoleId) {
        if (adminPassword == null || adminPassword.length() < 6) {
            throw new IllegalStateException("启用 IAM 引导时必须通过环境变量提供至少 6 位管理员密码");
        }
        if (store.user(organizationId, adminUsername).isPresent()) {
            throw new IllegalStateException("IAM 引导管理员用户名已存在但尚未具备系统管理员角色：" + adminUsername);
        }
        store.insertUser(UUID.randomUUID(), organizationId, adminUsername, "系统管理员", null, null,
                null, adminRoleId, passwordEncoder.encode(adminPassword));
        log.info("IAM 首个管理员账号已初始化：{}（密码不会写入日志）", adminUsername);
    }

    private void seedPermissionModel(UUID organizationId, java.util.Map<String, IamStore.Role> roles) {
        var allRoles = store.roles(organizationId);
        allRoles.forEach(role -> {
            if (role.policyVersion() == 0 && store.roleMenus(organizationId, role.id()).isEmpty()) {
                store.replaceRoleMenus(organizationId, role.id(), role.policyVersion(),
                        PermissionModelCatalog.presetMenus(role.code()));
            }
        });
        var definitions = IamPermissionCatalog.definitions();
        var levels = new java.util.LinkedHashMap<String, IamStore.PermissionLevel>();
        PermissionModelCatalog.levels().forEach(level -> {
            var stored = store.ensurePermissionLevel(organizationId, level.code(), level.name(), level.description(),
                    level.builtin(), level.tier());
            levels.put(level.code(), stored);
            if (store.permissionLevelBindings(organizationId, stored.id()).isEmpty()) {
                store.replacePermissionLevelBindings(organizationId, stored.id(), stored.policyVersion(),
                    PermissionModelCatalog.presetLevelBindings(level.code(), definitions).stream()
                            .map(this::globalLevelBinding).toList());
            }
        });
        // Legacy role bindings are copied into a generated level per role. This
        // preserves the old effective grant exactly instead of silently
        // widening it to one of the new shared presets.
        var migratedRoleLevels = new java.util.HashMap<UUID, IamStore.PermissionLevel>();
        allRoles.forEach(role -> {
            var legacy = store.roleBindings(organizationId, role.id());
            if (legacy.isEmpty() || "SYSTEM_ADMIN".equals(role.code())) return;
            var code = "MIGRATED_" + role.code();
            var generated = store.ensurePermissionLevel(organizationId, code,
                    "迁移：" + role.name(), "", false, 1);
            if (store.permissionLevelBindings(organizationId, generated.id()).isEmpty()) {
                store.replacePermissionLevelBindings(organizationId, generated.id(), generated.policyVersion(),
                        legacy.stream().filter(binding -> "ALLOW".equals(binding.effect()))
                                .map(binding -> {
                                    var normalized = legacyLevelBinding(binding);
                                    return new PermissionLevelBinding(null, normalized.permissionCode(), normalized.effect(),
                                            normalized.scopeType(), normalized.targetIds());
                                })
                                .toList());
            }
            migratedRoleLevels.put(role.id(), generated);
        });
        store.users(organizationId, null, 1, 10000).forEach(user -> {
            var assignedRoles = store.userRoles(organizationId, user.id());
            if ((assignedRoles == null || assignedRoles.isEmpty()) && user.roleId() != null) {
                try { store.replaceUserRoles(organizationId, user.id(), List.of(user.roleId()), user.roleId()); }
                catch (IamStore.PolicyVersionConflict ignored) { /* another startup/admin won the race */ }
                assignedRoles = store.userRoles(organizationId, user.id());
            }
            var roleCode = user.roleCode() == null ? "USER" : user.roleCode();
            var roleId = user.roleId();
            var level = migratedRoleLevels.get(roleId);
            if (level == null) level = levels.get(PermissionModelCatalog.defaultLevelCode(roleCode));
            var assignedLevel = store.userPermissionLevel(organizationId, user.id());
            if (level != null && (assignedLevel == null || assignedLevel.isEmpty())) {
                var currentUser = store.user(user.id()).orElse(user);
                try { store.saveUserPermissionLevel(organizationId, user.id(), currentUser.authVersion(), level.id()); }
                catch (IamStore.PolicyVersionConflict ignored) { /* another startup/admin won the race */ }
            }
            var legacyOverrides = store.userOverrides(organizationId, user.id());
            var operationOverrides = store.userOperationOverrides(organizationId, user.id());
            var scopeOverrides = store.userScopeOverrides(organizationId, user.id());
            if (!legacyOverrides.isEmpty() && (operationOverrides == null || operationOverrides.isEmpty())
                    && (scopeOverrides == null || scopeOverrides.isEmpty())) {
                var operations = legacyOverrides.stream()
                        .map(binding -> new Binding(binding.permissionCode(), binding.effect(), "ORG", List.of())).toList();
                var scopes = legacyOverrides.stream().filter(binding -> "ALLOW".equals(binding.effect()))
                        .map(this::legacyLevelBinding).toList();
                try { store.replaceUserModelOverrides(organizationId, user.id(), List.of(), operations, scopes); }
                catch (IamStore.PolicyVersionConflict ignored) { /* another startup/admin won the race */ }
            }
        });
    }

    private Binding legacyLevelBinding(Binding binding) {
        var scope = switch (binding.scopeType()) {
            case "ALL" -> "ORG";
            case "SELF" -> "CREATOR";
            case "PROJECT" -> binding.targetIds() == null || binding.targetIds().isEmpty() ? "PROJECT_GROUP" : "PROJECT";
            default -> binding.scopeType();
        };
        return new Binding(binding.permissionCode(), "ALLOW", scope,
                binding.targetIds() == null ? List.of() : binding.targetIds());
    }

    private PermissionLevelBinding globalLevelBinding(Binding binding) {
        return new PermissionLevelBinding(null, binding.permissionCode(), binding.effect(), binding.scopeType(),
                binding.targetIds() == null ? List.of() : binding.targetIds());
    }
}
