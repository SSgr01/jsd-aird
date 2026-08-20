package com.jsd.aird.mdm.application.query;

import java.time.Instant;
import java.util.UUID;

/** Read model returned by the task search use case. */
public record ProjectTaskSummary(
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
