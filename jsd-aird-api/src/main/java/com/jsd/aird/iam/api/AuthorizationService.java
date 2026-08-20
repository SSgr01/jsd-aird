package com.jsd.aird.iam.api;

import com.jsd.aird.shared.security.Actor;

public interface AuthorizationService {

    PermissionDecision check(PermissionCheck check);

    /**
     * Resolves the effective data scope for list/export queries.  The default
     * implementation preserves compatibility for lightweight test doubles;
     * the JDBC implementation returns the configured target ids as well.
     */
    default DataScopeResolver.ResolvedDataScope resolveScope(PermissionCheck check) {
        var decision = check(check);
        return decision.allowed()
                ? DataScopeResolver.ResolvedDataScope.allow(decision.scopeType() == null ? "ALL" : decision.scopeType())
                : DataScopeResolver.ResolvedDataScope.deny(decision.scopeType(), "PERMISSION_DENIED");
    }

    default PermissionDecision check(Actor actor, String permissionCode, String resourceType) {
        return check(new PermissionCheck(actor.organizationId(), actor.userId(), permissionCode, resourceType, null, null));
    }

    default void require(PermissionCheck check) {
        var decision = check(check);
        if (!decision.allowed()) {
            throw new com.jsd.aird.shared.error.ApiException(
                    com.jsd.aird.shared.error.ApiErrorCode.PERMISSION_DENIED,
                    "当前用户没有权限：" + check.permissionCode(),
                    decision
            );
        }
    }
}
