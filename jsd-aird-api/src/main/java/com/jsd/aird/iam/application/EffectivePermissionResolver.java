package com.jsd.aird.iam.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jsd.aird.iam.application.port.IamStore;
import com.jsd.aird.iam.application.port.IamStore.Binding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for "what a user can actually do".
 *
 * <p>The permission snapshot handed to the web client, the URL based route
 * filter and the delegation check in {@link IamAdminService} must agree on the
 * same answer: otherwise a manager could see entries they cannot save, or hand
 * out entries they do not own themselves.
 */
@Component
public class EffectivePermissionResolver {

    private final IamStore store;
    private final boolean developmentMode;

    public EffectivePermissionResolver(IamStore store,
                                       @Value("${app.identity.development-mode:false}") boolean developmentMode) {
        this.store = store;
        this.developmentMode = developmentMode;
    }

    /**
     * @param menuKeys     visible menu entries, in resolution order
     * @param bindings     resolved atomic operations keyed by permission code
     * @param unrestricted {@code true} when the account is exempt from
     *                     delegation limits (development identity or SYSTEM_ADMIN)
     */
    public record EffectivePermissions(Set<String> menuKeys, Map<String, Binding> bindings, boolean unrestricted) {

        /** Operation codes the user may perform, ignoring any data scope. */
        public Set<String> permissionCodes() {
            var codes = new LinkedHashSet<String>();
            bindings.values().stream()
                    .filter(binding -> "ALLOW".equals(binding.effect()))
                    .forEach(binding -> codes.add(binding.permissionCode()));
            return codes;
        }
    }

    /** Resolves an account by id; unknown or foreign accounts resolve to "no permission". */
    public EffectivePermissions resolve(UUID organizationId, UUID userId) {
        return store.user(userId)
                .filter(user -> organizationId.equals(user.organizationId()))
                .map(user -> resolve(organizationId, user))
                .orElseGet(() -> new EffectivePermissions(Set.of(), Map.of(), false));
    }

    public EffectivePermissions resolve(UUID organizationId, IamStore.User user) {
        var bindings = new LinkedHashMap<String, Binding>();
        var menuKeys = new LinkedHashSet<String>();
        var roles = store.userRoles(organizationId, user.id());
        if (roles != null && !roles.isEmpty()) {
            roles.forEach(role -> menuKeys.addAll(menusOf(organizationId, role.id())));
        } else if (user.roleId() != null) {
            store.role(organizationId, user.roleId()).ifPresent(role -> {
                store.roleBindings(organizationId, role.id()).forEach(binding -> bindings.put(binding.permissionCode(), binding));
                menuKeys.addAll(PermissionModelCatalog.presetMenus(role.code()));
            });
        }
        var levelId = store.userPermissionLevel(organizationId, user.id());
        var hasLevel = levelId != null && levelId.isPresent();
        if (hasLevel) {
            store.permissionLevel(organizationId, levelId.get()).ifPresent(level ->
                    store.permissionLevelBindings(organizationId, level.id()).forEach(binding -> bindings.put(binding.permissionCode(),
                            new Binding(binding.permissionCode(), binding.effect(), binding.scopeType(), binding.targetIds()))));
        }
        var operationOverrides = store.userOperationOverrides(organizationId, user.id());
        var deniedOperations = new LinkedHashSet<String>();
        if (operationOverrides != null) operationOverrides.forEach(binding -> {
            if ("ALLOW".equals(binding.effect())) bindings.put(binding.permissionCode(), binding);
            else {
                deniedOperations.add(binding.permissionCode());
                bindings.remove(binding.permissionCode());
            }
        });
        var menuOverrides = store.userMenuOverrides(organizationId, user.id());
        if (menuOverrides != null) menuOverrides.forEach(override -> {
            if ("ALLOW".equals(override.effect())) menuKeys.add(override.menuKey());
            else menuKeys.remove(override.menuKey());
        });
        if ((roles == null || roles.isEmpty()) && !hasLevel) {
            store.userOverrides(organizationId, user.id()).forEach(binding -> bindings.put(binding.permissionCode(), binding));
        }
        MenuViewPermissions.impliedBy(menuKeys).stream()
                .filter(code -> !deniedOperations.contains(code))
                .forEach(code -> bindings.compute(code, (key, existing) -> existing == null || "DENY".equals(existing.effect())
                        ? new Binding(code, "ALLOW", "ALL", List.of()) : existing));
        var systemAdmin = "SYSTEM_ADMIN".equals(user.roleCode())
                || (roles != null && roles.stream().anyMatch(role -> "SYSTEM_ADMIN".equals(role.code())));
        if (systemAdmin) {
            menuKeys.addAll(PermissionModelCatalog.allMenuKeys());
            grantEverything(bindings);
        }
        if (developmentMode) {
            // DevelopmentIdentityFilter/JdbcAuthorizationService intentionally allow
            // every permission in local development mode. Return the same effective
            // set to the web client; otherwise the UI hides all protected menus even
            // though the API accepts their requests.
            menuKeys.addAll(PermissionModelCatalog.allMenuKeys());
            bindings.clear();
            grantEverything(bindings);
        }
        return new EffectivePermissions(Collections.unmodifiableSet(menuKeys), bindings,
                systemAdmin || developmentMode);
    }

    private void grantEverything(Map<String, Binding> bindings) {
        store.permissionDefinitions().forEach(definition -> bindings.put(definition.code(),
                new Binding(definition.code(), "ALLOW", "ORG", List.of())));
    }

    private List<String> menusOf(UUID organizationId, UUID roleId) {
        var menuKeys = store.roleMenus(organizationId, roleId);
        return menuKeys == null ? List.of() : menuKeys;
    }
}
