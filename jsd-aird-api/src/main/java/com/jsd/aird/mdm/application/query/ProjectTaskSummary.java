package com.jsd.aird.mdm.application.query;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import com.jsd.aird.shared.api.AllowedActions;

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
    public List<String> getAllowedActions() {
        return AllowedActions.projectResource(status, "COMPLETED".equalsIgnoreCase(status), experimentCount > 0);
    }
}
