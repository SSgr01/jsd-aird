package com.jsd.aird.iam.api;

import java.util.Set;
import java.util.UUID;

import com.jsd.aird.iam.application.port.IamStore.Binding;

public interface DataScopeResolver {

    ResolvedDataScope resolve(PermissionCheck check, Binding binding);

    record ResolvedDataScope(boolean allowed, String scopeType, Set<UUID> targetIds, String reason) {
        public static ResolvedDataScope allow(String scopeType) {
            return new ResolvedDataScope(true, scopeType, Set.of(), "SCOPE_ALLOW");
        }

        public static ResolvedDataScope allow(String scopeType, Set<UUID> targetIds) {
            return new ResolvedDataScope(true, scopeType, targetIds == null ? Set.of() : Set.copyOf(targetIds), "SCOPE_ALLOW");
        }

        public static ResolvedDataScope deny(String scopeType, String reason) {
            return new ResolvedDataScope(false, scopeType, Set.of(), reason);
        }
    }
}
