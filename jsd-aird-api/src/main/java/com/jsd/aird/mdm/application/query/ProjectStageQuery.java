package com.jsd.aird.mdm.application.query;

import com.jsd.aird.mdm.domain.model.StageStatus;

import java.time.LocalDate;
import java.util.UUID;

public record ProjectStageQuery(String keyword, UUID projectId, StageStatus status, String owner,
                                LocalDate plannedFrom, LocalDate plannedTo, int page, int size) {
    public ProjectStageQuery {
        keyword = blankToNull(keyword);
        owner = blankToNull(owner);
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 200);
    }

    public int offset() {
        return (page - 1) * size;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
