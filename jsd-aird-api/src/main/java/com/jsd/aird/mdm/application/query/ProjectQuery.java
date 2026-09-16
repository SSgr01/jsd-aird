package com.jsd.aird.mdm.application.query;

import com.jsd.aird.mdm.domain.model.ProjectPriority;
import com.jsd.aird.mdm.domain.model.ProjectStatus;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public record ProjectQuery(String keyword, String owner, ProjectPriority priority, ProjectStatus status,
                           LocalDate startDateFrom, LocalDate startDateTo, UUID partnerId, int page, int size,
                           UUID organizationId, String scopeType, UUID scopeUserId, Set<UUID> scopeTargetIds) {

    public ProjectQuery(String keyword, String owner, ProjectPriority priority, ProjectStatus status,
                        LocalDate startDateFrom, LocalDate startDateTo, UUID partnerId, int page, int size) {
        this(keyword, owner, priority, status, startDateFrom, startDateTo, partnerId, page, size,
                null, "ALL", null, Set.of());
    }

    public ProjectQuery {
        page = page <= 0 ? 1 : page;
        size = size <= 0 ? 10 : Math.min(size, 200);
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        owner = owner == null || owner.isBlank() ? null : owner.trim();
        scopeType = scopeType == null || scopeType.isBlank() ? "ALL" : scopeType;
        scopeTargetIds = scopeTargetIds == null ? Set.of() : Set.copyOf(scopeTargetIds);
    }

    public int offset() {
        return (page - 1) * size;
    }

    public ProjectQuery withDataScope(UUID organizationId, String scopeType, UUID scopeUserId, Set<UUID> targetIds) {
        return new ProjectQuery(keyword, owner, priority, status, startDateFrom, startDateTo, partnerId, page, size,
                organizationId, scopeType, scopeUserId, targetIds);
    }
}
