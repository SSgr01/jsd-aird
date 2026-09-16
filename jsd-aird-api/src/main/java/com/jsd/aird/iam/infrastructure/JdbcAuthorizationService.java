package com.jsd.aird.iam.infrastructure;

import java.util.Map;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.DataScopeResolver;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.iam.api.PermissionDecision;
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
        Binding binding = selection.binding();
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
        return dataScopeResolver.resolve(check, selection.binding());
    }

    private BindingSelection binding(PermissionCheck check, User user) {
        var v2 = v2Binding(check, user);
        if (v2.binding() != null || v2.v2Active()) return v2;
        Binding binding = null;
        var source = "DEFAULT_DENY";
        var overrides = store.userOverrides(check.organizationId(), check.userId()).stream()
                .collect(Collectors.toMap(Binding::permissionCode, Function.identity(), (left, right) -> right));
        if (overrides.containsKey(check.permissionCode())) {
            binding = overrides.get(check.permissionCode());
            source = "USER_OVERRIDE";
        } else if (user.roleId() != null) {
            var role = store.role(check.organizationId(), user.roleId()).orElse(null);
            if (role != null && role.enabled()) {
                binding = store.roleBindings(check.organizationId(), role.id()).stream()
                        .filter(item -> item.permissionCode().equals(check.permissionCode())).findFirst().orElse(null);
                source = "ROLE:" + role.code();
            }
        }
        return new BindingSelection(binding, source, false);
    }

    private BindingSelection v2Binding(PermissionCheck check, User user) {
        var roles = safeRoles(check.organizationId(), check.userId());
        var levelId = safeLevel(check.organizationId(), check.userId());
        var operationOverrides = safeBindings(() -> store.userOperationOverrides(check.organizationId(), check.userId()));
        var scopeOverrides = safeBindings(() -> store.userScopeOverrides(check.organizationId(), check.userId()));
        var v2Active = !roles.isEmpty() || levelId != null || !operationOverrides.isEmpty() || !scopeOverrides.isEmpty();
        if (!v2Active) return new BindingSelection(null, "DEFAULT_DENY", false);

        if (roles.stream().anyMatch(role -> "SYSTEM_ADMIN".equals(role.code())) || "SYSTEM_ADMIN".equals(user.roleCode())) {
            return new BindingSelection(new Binding(check.permissionCode(), "ALLOW", "ORG", List.of()),
                    "SYSTEM_ADMIN", true);
        }

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
            binding = binding == null ? new Binding(override.permissionCode(), "ALLOW", "ORG", List.of())
                    : new Binding(binding.permissionCode(), "ALLOW", binding.scopeType(), binding.targetIds());
            return new BindingSelection(binding, "USER_OPERATION_OVERRIDE", true);
        }
        if (binding != null) return new BindingSelection(binding, "PERMISSION_LEVEL:" + level.code(), true);
        return new BindingSelection(null, level == null ? "V2_NO_PERMISSION_LEVEL" : "PERMISSION_LEVEL:" + level.code(), true);
    }

    private int scopePriority(String scopeType) {
        if (scopeType == null) return 0;
        return switch (scopeType) {
            case "ALL", "ORG" -> 100;
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
