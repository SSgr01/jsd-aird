package com.jsd.aird.mdm.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

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
    long version,
    Instant createdAt,
    Instant updatedAt
) {
}
