package com.jsd.aird.mdm.application.query;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ProjectTaskQuery(
    String keyword,
    String projectId,
    String stageId,
    String status,
    String owner,
    String priority,
    Integer page,
    Integer size,
    UUID organizationId,
    String scopeType,
    UUID scopeUserId,
    List<UUID> scopeTargetIds
) {
    public ProjectTaskQuery(String keyword, String projectId, String stageId, String status,
                            String owner, String priority, Integer page, Integer size) {
        this(keyword, projectId, stageId, status, owner, priority, page, size, null, "ALL", null, List.of());
    }

    public ProjectTaskQuery {
        scopeTargetIds = scopeTargetIds == null ? List.of() : List.copyOf(scopeTargetIds);
    }

    public ProjectTaskQuery withDataScope(UUID organizationId, String scopeType, UUID userId, Set<UUID> targetIds) {
        return new ProjectTaskQuery(keyword, projectId, stageId, status, owner, priority, page, size,
                organizationId, scopeType, userId, List.copyOf(targetIds));
    }
}
