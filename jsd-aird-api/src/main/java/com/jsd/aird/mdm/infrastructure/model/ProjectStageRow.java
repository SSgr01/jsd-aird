package com.jsd.aird.mdm.infrastructure.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProjectStageRow(UUID id, UUID projectId, String projectCode, String projectName,
                              String stageCode, String name, int orderNo, String status, String owner,
                              String description, LocalDate plannedStart, LocalDate plannedEnd,
                              Instant actualStart, Instant actualEnd, long taskCount, long openTaskCount,
                              long version, Instant createdAt, Instant updatedAt) {
}
