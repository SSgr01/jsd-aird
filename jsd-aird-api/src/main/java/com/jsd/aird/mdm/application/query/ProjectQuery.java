package com.jsd.aird.mdm.application.query;

import com.jsd.aird.mdm.domain.model.ProjectPriority;
import com.jsd.aird.mdm.domain.model.ProjectStatus;

import java.time.LocalDate;
import java.util.UUID;

public record ProjectQuery(String keyword, String owner, ProjectPriority priority, ProjectStatus status,
                           LocalDate startDateFrom, LocalDate startDateTo, UUID partnerId, int page, int size) {

    public ProjectQuery {
        page = page <= 0 ? 1 : page;
        size = size <= 0 ? 10 : Math.min(size, 200);
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        owner = owner == null || owner.isBlank() ? null : owner.trim();
    }

    public int offset() {
        return (page - 1) * size;
    }
}
