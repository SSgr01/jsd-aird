package com.jsd.aird.mdm.domain.model;
import java.time.*;
import java.util.UUID;
public record ProjectTask(UUID id,String taskCode,UUID projectId,UUID stageId,String name,String owner,LocalDate plannedDate,String status,long experimentCount,long version,Instant createdAt,Instant updatedAt) {}
