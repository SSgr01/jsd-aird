package com.jsd.aird.iam.api;

public record PermissionDecision(
        boolean allowed,
        String permissionCode,
        String effect,
        String scopeType,
        String source
) {
    public static PermissionDecision denied(String permissionCode) {
        return new PermissionDecision(false, permissionCode, "DENY", null, "DEFAULT_DENY");
    }
}
