package com.jsd.aird.iam.api;

import java.util.UUID;

public record PermissionCheck(
        UUID organizationId,
        UUID userId,
        String permissionCode,
        String resourceType,
        UUID resourceId,
        String operation
) {
    public PermissionCheck {
        if (organizationId == null || userId == null || permissionCode == null || permissionCode.isBlank()) {
            throw new IllegalArgumentException("权限检查缺少组织、用户或权限编码");
        }
    }
}
