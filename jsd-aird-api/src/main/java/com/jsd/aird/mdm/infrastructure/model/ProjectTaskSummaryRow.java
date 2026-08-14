package com.jsd.aird.mdm.infrastructure.model;

import java.time.Instant;
import java.util.UUID;

public record ProjectTaskSummaryRow(
    UUID id,
    String taskCode,
    UUID projectId,
    String projectName,
    UUID stageId,
    String stageName,
    String name,
    String owner,
    String priority,
    String plannedDate,
    String status,
    long experimentCount,
    long version,
    Instant createdAt,
    Instant updatedAt
) {
}
