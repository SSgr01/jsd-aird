package com.jsd.aird.iam.infrastructure;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.DataScopeResolver;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.iam.api.PermissionDecision;
import com.jsd.aird.iam.application.port.IamStore.User;
import com.jsd.aird.iam.application.port.IamStore.Binding;
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
            if ("ALL".equals(binding.scopeType())) return DataScopeResolver.ResolvedDataScope.allow("ALL");
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
        return new BindingSelection(binding, source);
    }

    private record BindingSelection(Binding binding, String source) { }
}
