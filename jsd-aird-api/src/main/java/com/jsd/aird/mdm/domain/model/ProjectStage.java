package com.jsd.aird.mdm.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import com.jsd.aird.shared.api.AllowedActions;

public record ProjectStage(
    UUID id,
    UUID projectId,
    String projectCode,
    String projectName,
    String stageCode,
    String name,
    int orderNo,
    StageStatus status,
    String owner,
    String description,
    LocalDate plannedStart,
    LocalDate plannedEnd,
    Instant actualStart,
    Instant actualEnd,
    long taskCount,
    long openTaskCount,
    long experimentCount,
    long materialCount,
    long version,
    Instant createdAt,
    Instant updatedAt
) {
    public List<String> getAllowedActions() {
        return AllowedActions.projectResource(status == null ? null : status.name(), status == StageStatus.COMPLETED,
                taskCount > 0 || materialCount > 0);
    }
}
