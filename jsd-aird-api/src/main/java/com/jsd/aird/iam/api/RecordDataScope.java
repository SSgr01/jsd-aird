package com.jsd.aird.iam.api;

import java.util.Set;
import java.util.UUID;
import java.util.List;

public record RecordDataScope(String type, UUID actorId, Set<UUID> targetIds) {
    public RecordDataScope {
        targetIds = targetIds == null ? Set.of() : Set.copyOf(targetIds);
    }

    public static RecordDataScope all() {
        return new RecordDataScope("ALL", null, Set.of());
    }

    public String sqlCondition(String alias, List<Object> args) {
        return switch (type) {
            case "ALL", "ORG" -> "";
            case "SELF", "CREATOR" -> { args.add(actorId); yield " AND " + alias + ".created_by = ?"; }
            case "DEPARTMENT" -> {
                args.add(actorId);
                yield " AND " + alias + ".department_id = (SELECT department_id FROM iam.app_user WHERE id = ? AND organization_id = " + alias + ".organization_id)";
            }
            case "SELECTED" -> {
                if (targetIds.isEmpty()) yield " AND false";
                args.addAll(targetIds);
                yield " AND " + alias + ".id IN (" + String.join(",", targetIds.stream().map(id -> "?").toList()) + ")";
            }
            default -> " AND false";
        };
    }
}
