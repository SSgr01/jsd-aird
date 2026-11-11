package com.jsd.aird.iam.infrastructure;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.DataScopeResolver;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.iam.api.PermissionDecision;
import com.jsd.aird.iam.application.IamPermissionCatalog;
import com.jsd.aird.iam.application.MenuViewPermissions;
import com.jsd.aird.iam.application.PermissionModelCatalog;
import com.jsd.aird.iam.application.TransferViewPermissions;
import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.iam.application.port.IamStore.User;
import com.jsd.aird.iam.application.port.IamStore.Binding;
import com.jsd.aird.iam.application.port.IamStore.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class JdbcAuthorizationService implements AuthorizationService {

    private final JdbcIamStore store;
    private final DataScopeResolver dataScopeResolver;
    private final boolean developmentMode;

    @Autowired
    public JdbcAuthorizationService(JdbcIamStore store,
                                    DataScopeResolver dataScopeResolver,
                                    @Value("${app.identity.development-mode:false}") boolean developmentMode) {
        this.store = store;
        this.dataScopeResolver = dataScopeResolver;
        this.developmentMode = developmentMode;
    }

    public JdbcAuthorizationService(JdbcIamStore store,
                                    @Value("${app.identity.development-mode:false}") boolean developmentMode) {
        this(store, (check, binding) -> {
            if ("ALL".equals(binding.scopeType()) || "ORG".equals(binding.scopeType())) {
                return DataScopeResolver.ResolvedDataScope.allow(binding.scopeType());
            }
            if ("SELECTED".equals(binding.scopeType()) && check.resourceId() != null
                    && binding.targetIds().contains(check.resourceId())) {
                return DataScopeResolver.ResolvedDataScope.allow("SELECTED");
            }
            return DataScopeResolver.ResolvedDataScope.deny(binding.scopeType(), "SCOPE_NOT_RESOLVED");
        }, developmentMode);
    }

    @Override
    public PermissionDecision check(PermissionCheck check) {
        var decision = checkExact(check);
        var viewCodes = TransferViewPermissions.viewsFor(check.permissionCode());
        if (decision.allowed() || viewCodes.isEmpty() || String.valueOf(decision.source()).startsWith("USER_")
                || "ALLOW".equals(decision.effect())) return decision;
        for (var viewCode : viewCodes) {
            var view = checkExact(new PermissionCheck(check.organizationId(), check.userId(), viewCode,
                    check.resourceType(), check.resourceId(), "READ", check.menuKey()));
            if (view.allowed()) return new PermissionDecision(true, check.permissionCode(),
                    "ALLOW", view.scopeType(), "VIEW:" + view.source());
        }
        return decision;
    }

    private PermissionDecision checkExact(PermissionCheck check) {
        if (developmentMode) {
            return new PermissionDecision(true, check.permissionCode(), "ALLOW", "ALL", "DEVELOPMENT");
        }
        var user = store.user(check.userId()).orElse(null);
        if (user == null || !check.organizationId().equals(user.organizationId()) || !"ACTIVE".equals(user.status())) {
            return PermissionDecision.denied(check.permissionCode());
        }
        var definition = store.permissionDefinitions().stream()
                .filter(item -> item.code().equals(check.permissionCode())).findFirst().orElse(null);
        if (definition == null) return PermissionDecision.denied(check.permissionCode());

        var selection = binding(check, user);
        Binding binding = projectListTargets(check, user, selection.binding());
        var source = selection.source();
        if (binding == null || "DENY".equals(binding.effect())) {
            return new PermissionDecision(false, check.permissionCode(), binding == null ? "DENY" : binding.effect(),
                    binding == null ? null : binding.scopeType(), source);
        }
        var scope = dataScopeResolver.resolve(check, binding);
        if (!scope.allowed()) {
            return new PermissionDecision(false, check.permissionCode(), binding.effect(), binding.scopeType(), source + ":" + scope.reason());
        }
        return new PermissionDecision(true, check.permissionCode(), binding.effect(), binding.scopeType(), source);
    }

    @Override
    public DataScopeResolver.ResolvedDataScope resolveScope(PermissionCheck check) {
        if (developmentMode) return DataScopeResolver.ResolvedDataScope.allow("ALL");
        var user = store.user(check.userId()).orElse(null);
        if (user == null || !check.organizationId().equals(user.organizationId()) || !"ACTIVE".equals(user.status())) {
            return DataScopeResolver.ResolvedDataScope.deny("", "USER_NOT_ACTIVE");
        }
        var selection = binding(check, user);
        if (selection.binding() == null || "DENY".equals(selection.binding().effect())) {
            return DataScopeResolver.ResolvedDataScope.deny(
                    selection.binding() == null ? "" : selection.binding().scopeType(), "PERMISSION_DENIED");
        }
        return dataScopeResolver.resolve(check, projectListTargets(check, user, selection.binding()));
    }

    private Binding projectListTargets(PermissionCheck check, User user, Binding binding) {
        if (binding == null || !"SELECTED".equals(binding.scopeType())
                || java.util.stream.Stream.of("eln.view.", "research-test.report.view.",
                        "quality.view.", "production.view.").noneMatch(binding.permissionCode()::startsWith)) return binding;
        var projectCheck = new PermissionCheck(check.organizationId(), check.userId(), "project.list.view",
                "PROJECT", null, "READ", "project.list");
        var projectBinding = binding(projectCheck, user).binding();
        var targets = projectBinding != null && "ALLOW".equals(projectBinding.effect())
                && java.util.Set.of("SELECTED", "PROJECT").contains(projectBinding.scopeType())
                ? projectBinding.targetIds() : List.<java.util.UUID>of();
        return new Binding(binding.permissionCode(), binding.effect(), "SELECTED", targets);
    }

    private BindingSelection binding(PermissionCheck check, User user) {
        var roles = safeRoles(check.organizationId(), check.userId());
        if ("SYSTEM_ADMIN".equals(user.roleCode())
                || roles.stream().anyMatch(role -> "SYSTEM_ADMIN".equals(role.code()) && role.enabled())) {
            return new BindingSelection(new Binding(check.permissionCode(), "ALLOW", "ALL", List.of()),
                    "SYSTEM_ADMIN", true);
        }
        var v2 = v2Binding(check, user);
        var scopedViewCode = switch (check.permissionCode()) {
            case "experiment.view" -> "eln.view.view";
            case "quality.view" -> "quality.view.view";
            case "research-test.report.view" -> "research-test.report.view.view";
            case "production.view" -> "production.view.view";
            case "spectrum.view" -> "spectrum.view.view";
            case "knowledge.view" -> "knowledge.view.view";
            case "data.view" -> "data.view.view";
            default -> null;
        };
        if (scopedViewCode != null && (v2.binding() == null || !"DENY".equals(v2.binding().effect()))) {
            var pageCheck = new PermissionCheck(check.organizationId(), check.userId(), scopedViewCode,
                    check.resourceType(), check.resourceId(), check.operation(), check.menuKey());
            var pageBinding = v2Binding(pageCheck, user);
            if (pageBinding.binding() != null) return pageBinding;
        }
        if ("PROJECT".equals(check.resourceType()) && "project.view".equals(check.permissionCode())
                && (v2.binding() == null || !"DENY".equals(v2.binding().effect()))) {
            var listCheck = new PermissionCheck(check.organizationId(), check.userId(), "project.list.view",
                    check.resourceType(), check.resourceId(), check.operation(), "project.list");
            var listBinding = v2Binding(listCheck, user);
            if (listBinding.binding() != null) return listBinding;
        }
        if (v2.binding() == null && v2.v2Active()) {
            for (var pageCode : alternateCodes(check.permissionCode())) {
                var pageCheck = new PermissionCheck(check.organizationId(), check.userId(), pageCode,
                        check.resourceType(), check.resourceId(), check.operation(), null);
                var pageGrant = v2Binding(pageCheck, user);
                if (pageGrant.binding() != null && "ALLOW".equals(pageGrant.binding().effect())) return pageGrant;
            }
        }
        if (v2.binding() != null || v2.v2Active()) return v2;
        Binding binding = null;
        var source = "DEFAULT_DENY";
        var candidates = new java.util.ArrayList<String>();
        candidates.add(check.permissionCode());
        candidates.addAll(alternateCodes(check.permissionCode()));
        var overrides = store.userOverrides(check.organizationId(), check.userId()).stream()
                .collect(Collectors.toMap(Binding::permissionCode, Function.identity(), (left, right) -> right));
        for (var code : candidates) {
            if (overrides.containsKey(code)) {
                binding = overrides.get(code);
                source = "USER_OVERRIDE";
                break;
            }
        }
        if (binding == null && user.roleId() != null) {
            var role = store.role(check.organizationId(), user.roleId()).orElse(null);
            if (role != null && role.enabled()) {
                source = "ROLE:" + role.code();
                var roleBindings = store.roleBindings(check.organizationId(), role.id());
                for (var code : candidates) {
                    binding = roleBindings.stream()
                            .filter(item -> item.permissionCode().equals(code)).findFirst().orElse(null);
                    if (binding != null) break;
                }
            }
        }
        return new BindingSelection(binding, source, false);
    }

    /**
     * Some operations are granted on a page row (for example
     * {@code research-test.report.view.update}) while other code paths or roles still
     * refer to the module-level code ({@code research-test.report.update}). Keep both
     * spellings working so UI configuration and legacy role grants stay valid.
     */
    private static final Map<String, List<String>> ALTERNATE_CODES = Map.ofEntries(
            Map.entry("template.view", List.of("template.view.view")),
            Map.entry("template.create", List.of("template.view.create", "template.upload.create")),
            Map.entry("eln.upload.retry", List.of("eln.upload.create")),
            Map.entry("research-test.report.create", List.of("research-test.report.view.create")),
            Map.entry("research-test.report.view.create", List.of("research-test.report.create")),
            Map.entry("research-test.report.update", List.of("research-test.report.view.update")),
            Map.entry("research-test.report.view.update", List.of("research-test.report.update")),
            Map.entry("research-test.report.delete", List.of("research-test.report.view.delete")),
            Map.entry("research-test.report.view.delete", List.of("research-test.report.delete")),
            Map.entry("research-test.report.submit", List.of("research-test.report.view.submit")),
            Map.entry("research-test.report.view.submit", List.of("research-test.report.submit")),
            Map.entry("research-test.report.approve", List.of("research-test.report.view.approve")),
            Map.entry("research-test.report.view.approve", List.of("research-test.report.approve")),
            Map.entry("research-test.report.publish", List.of("research-test.report.view.publish")),
            Map.entry("research-test.report.view.publish", List.of("research-test.report.publish")),
            Map.entry("research-test.report.view.archive", List.of("research-test.report.publish")),
            Map.entry("research-test.report.export", List.of("research-test.report.view.export")),
            Map.entry("research-test.report.view.export", List.of("research-test.report.export")),
            // Publish and review are separate columns in the permission UI, while the
            // built-in roles still hold the legacy module action. Accept both.
            Map.entry("eln.view.publish", List.of("experiment.approve")),
            Map.entry("experiment.review", List.of("eln.view.approve")),
            Map.entry("template.view.submit", List.of("template.review")),
            // 审核既有旧配置页发放的 template.approve，也有内置角色与旧接口
            // 共用的 template.review；两者都视为通过审核。
            Map.entry("template.view.approve", List.of("template.review", "template.approve")),
            Map.entry("template.view.review", List.of("template.review")),
            Map.entry("knowledge.review", List.of("knowledge.view.approve")),
            Map.entry("knowledge.view.submit", List.of("knowledge.submit")),
            Map.entry("knowledge.view.approve", List.of("knowledge.review", "knowledge.approve")),
            Map.entry("data.create", List.of("data.view.create")),
            Map.entry("data.upload.create", List.of("data.view.create")),
            Map.entry("data.update", List.of("data.view.update")),
            Map.entry("data.submit", List.of("data.view.submit")),
            Map.entry("data.approve", List.of("data.view.approve")),
            Map.entry("production.view.publish", List.of("production.submit")),
            // 库存冲销与库存表「编辑」同权：只授予研发/生产库存表编辑权限时也允许冲销流水。
            Map.entry("inventory.reverse", List.of("inventory.rnd.reverse", "inventory.production.reverse",
                    "inventory.rnd.update", "inventory.production.update")),
            // 系统管理早期只发放统一的 *.manage 编码；权限配置页现在按「用户管理」
            // 「用户权限配置」功能行发放原子操作，两种写法都要继续可用。
            Map.entry("system.users.create", List.of("system.user.manage")),
            Map.entry("system.users.update", List.of("system.user.manage")),
            // 账号维护（停用/启用、重置密码、强制下线）与「编辑用户」同权：权限配置页的
            // 用户管理行只发放查看/新增/编辑/删除，不授予编辑就无法维护账号。
            Map.entry("system.users.status", List.of("system.users.update", "system.user.manage")),
            Map.entry("system.users.reset", List.of("system.users.update", "system.user.manage")),
            Map.entry("system.users.logout", List.of("system.users.update", "system.user.manage")),
            Map.entry("system.permissions.create", List.of("system.role.create", "system.permission.manage")),
            Map.entry("system.permissions.update", List.of("system.role.manage", "system.permission.manage")),
            Map.entry("system.permissions.delete", List.of("system.role.manage", "system.permission.manage")));

    /**
     * The API filter checks module-level actions ({@code project.update}) while the
     * permission UI grants the bracketing page row ({@code project.list.update}).
     * Without this alias a configured page action would never authorize its API.
     * Only codes present in the catalog are offered, so unknown rows stay denied.
     */
    private static final Map<String, String> MODULE_PAGE_ROWS = Map.ofEntries(
            Map.entry("customer", "customer.list"),
            Map.entry("project", "project.list"),
            Map.entry("template", "template.view"),
            Map.entry("experiment", "eln.view"),
            Map.entry("research-test.report", "research-test.report.view"),
            Map.entry("quality", "quality.view"),
            Map.entry("spectrum", "spectrum.view"),
            Map.entry("production", "production.view"),
            Map.entry("knowledge", "knowledge.view"),
            Map.entry("data", "data.view"),
            Map.entry("inventory", "inventory.rnd"),
            Map.entry("system.user", "system.users"),
            Map.entry("system.role", "system.permissions"),
            Map.entry("system.permission", "system.permissions"));

    private static final List<String> PAGE_ACTION_SUFFIXES =
            List.of("view", "create", "update", "delete", "approve", "publish");

    private static List<String> alternateCodes(String permissionCode) {
        var codes = new LinkedHashSet<>(ALTERNATE_CODES.getOrDefault(permissionCode, List.of()));
        var dot = permissionCode.lastIndexOf('.');
        if (dot > 0) {
            var page = MODULE_PAGE_ROWS.get(permissionCode.substring(0, dot));
            if (page != null) {
                var candidate = page + permissionCode.substring(dot);
                if (IamPermissionCatalog.codes().contains(candidate)) codes.add(candidate);
            }
        }
        // Some API actions use the menu row key itself (knowledge.upload,
        // template.upload, knowledge.review). The UI grants that row's actions.
        var menuRow = PermissionModelCatalog.menus().stream()
                .filter(menu -> permissionCode.equals(menu.key()) && menu.parent() != null)
                .findFirst();
        if (menuRow.isPresent()) {
            for (var suffix : PAGE_ACTION_SUFFIXES) {
                var candidate = permissionCode + "." + suffix;
                if (IamPermissionCatalog.codes().contains(candidate)) codes.add(candidate);
            }
        }
        return List.copyOf(codes);
    }

    private BindingSelection v2Binding(PermissionCheck check, User user) {
        var roles = safeRoles(check.organizationId(), check.userId());
        var levelId = safeLevel(check.organizationId(), check.userId());
        var operationOverrides = safeBindings(() -> store.userOperationOverrides(check.organizationId(), check.userId()));
        var scopeOverrides = safeBindings(() -> store.userScopeOverrides(check.organizationId(), check.userId()));
        var menuOverrides = store.userMenuOverrides(check.organizationId(), check.userId());
        var v2Active = !roles.isEmpty() || levelId != null || !operationOverrides.isEmpty()
                || !scopeOverrides.isEmpty() || (menuOverrides != null && !menuOverrides.isEmpty());
        if (!v2Active) return new BindingSelection(null, "DEFAULT_DENY", false);

        var override = operationOverrides.stream()
                .filter(item -> item.permissionCode().equals(check.permissionCode()))
                .filter(item -> check.menuKey() == null || check.menuKey().equals(item.menuKey()))
                .findFirst().orElse(null);
        var level = levelId == null ? null : store.permissionLevel(check.organizationId(), levelId).orElse(null);
        var levelBinding = level == null ? null : store.permissionLevelBindings(check.organizationId(), level.id()).stream()
                .filter(item -> item.permissionCode().equals(check.permissionCode()))
                .filter(item -> check.menuKey() == null || item.menuKey() == null || check.menuKey().equals(item.menuKey()))
                // The same atomic operation can be displayed on several menu rows.
                // Without a route menu key, use the broadest granted row instead of
                // whichever row happens to sort first; otherwise an empty SELECTED
                // row can incorrectly hide data granted as ORG on another row.
                .sorted(java.util.Comparator
                        .comparingInt((IamStore.PermissionLevelBinding item) -> scopePriority(item.scopeType())).reversed()
                        .thenComparing(item -> item.menuKey() == null ? "" : item.menuKey()))
                .findFirst().orElse(null);
        var binding = levelBinding == null ? null
                : new Binding(levelBinding.permissionCode(), levelBinding.effect(), levelBinding.scopeType(), levelBinding.targetIds());
        var menuKeys = new LinkedHashSet<String>();
        roles.forEach(role -> {
            var keys = store.roleMenus(check.organizationId(), role.id());
            if (keys != null) menuKeys.addAll(keys);
        });
        if (roles.isEmpty() && user.roleId() != null) {
            menuKeys.addAll(PermissionModelCatalog.presetMenus(user.roleCode()));
        }
        if (menuOverrides != null) menuOverrides.forEach(item -> {
            if ("ALLOW".equals(item.effect())) menuKeys.add(item.menuKey());
            else menuKeys.remove(item.menuKey());
        });
        var menuView = MenuViewPermissions.impliedBy(menuKeys).contains(check.permissionCode());
        if (menuView && binding == null)
            binding = new Binding(check.permissionCode(), "ALLOW", "ALL", List.of());
        var scope = scopeOverrides.stream()
                .filter(item -> item.permissionCode().equals(check.permissionCode()))
                .filter(item -> check.menuKey() == null || check.menuKey().equals(item.menuKey()))
                // A permission code can be shown on several menu rows while an
                // API request only carries the atomic permission code. Select
                // the broadest personal scope instead of the first DB row.
                .sorted(java.util.Comparator
                        .comparingInt((Binding item) -> scopePriority(item.scopeType())).reversed()
                        .thenComparing(item -> item.menuKey() == null ? "" : item.menuKey()))
                .findFirst().orElse(null);
        if (scope != null && binding != null) {
            binding = new Binding(binding.permissionCode(), binding.effect(), scope.scopeType(), scope.targetIds());
        } else if (scope != null && override != null && "ALLOW".equals(override.effect())) {
            binding = new Binding(override.permissionCode(), "ALLOW", scope.scopeType(), scope.targetIds());
        }
        if (override != null) {
            if ("DENY".equals(override.effect())) return new BindingSelection(override, "USER_OPERATION_OVERRIDE", true);
            binding = binding == null ? new Binding(override.permissionCode(), "ALLOW", "ALL", List.of())
                    : new Binding(binding.permissionCode(), "ALLOW", binding.scopeType(), binding.targetIds());
            return new BindingSelection(binding, "USER_OPERATION_OVERRIDE", true);
        }
        if (binding != null) return new BindingSelection(binding,
                menuView ? "FEATURE_MENU" : "PERMISSION_LEVEL:" + level.code(), true);
        return new BindingSelection(null, level == null ? "V2_NO_PERMISSION_LEVEL" : "PERMISSION_LEVEL:" + level.code(), true);
    }

    private int scopePriority(String scopeType) {
        if (scopeType == null) return 0;
        return switch (scopeType) {
            case "ALL", "ORG" -> 100;
            case "DEPARTMENT" -> 70;
            case "SELF", "CREATOR", "ASSIGNED", "OWNER", "MANAGER", "PARTICIPANT", "PROJECT_GROUP" -> 60;
            case "PROJECT", "CATEGORY", "SELECTED" -> 20;
            default -> 0;
        };
    }

    private List<Role> safeRoles(java.util.UUID organizationId, java.util.UUID userId) {
        var roles = store.userRoles(organizationId, userId);
        return roles == null ? List.of() : roles;
    }

    private java.util.UUID safeLevel(java.util.UUID organizationId, java.util.UUID userId) {
        var level = store.userPermissionLevel(organizationId, userId);
        return level == null ? null : level.orElse(null);
    }

    private List<Binding> safeBindings(java.util.function.Supplier<List<Binding>> supplier) {
        var bindings = supplier.get();
        return bindings == null ? List.of() : bindings;
    }

    private record BindingSelection(Binding binding, String source, boolean v2Active) { }
}
