package com.jsd.aird.mdm.application.query;

import com.jsd.aird.mdm.domain.model.StageStatus;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public record ProjectStageQuery(String keyword, UUID projectId, StageStatus status, String owner,
                                LocalDate plannedFrom, LocalDate plannedTo, int page, int size,
                                UUID organizationId, String scopeType, UUID scopeUserId, Set<UUID> scopeTargetIds) {
    public ProjectStageQuery(String keyword, UUID projectId, StageStatus status, String owner,
                             LocalDate plannedFrom, LocalDate plannedTo, int page, int size) {
        this(keyword, projectId, status, owner, plannedFrom, plannedTo, page, size, null, "ALL", null, Set.of());
    }

    public ProjectStageQuery {
        keyword = blankToNull(keyword);
        owner = blankToNull(owner);
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 200);
        scopeTargetIds = scopeTargetIds == null ? Set.of() : Set.copyOf(scopeTargetIds);
    }

    public int offset() {
        return (page - 1) * size;
    }

    public ProjectStageQuery withDataScope(UUID organizationId, String scopeType, UUID userId, Set<UUID> targetIds) {
        return new ProjectStageQuery(keyword, projectId, status, owner, plannedFrom, plannedTo, page, size,
                organizationId, scopeType, userId, targetIds);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
