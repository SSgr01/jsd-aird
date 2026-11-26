package com.jsd.aird.mdm.domain.model;
import java.time.*;
import java.util.UUID;
import java.util.List;
import com.jsd.aird.shared.api.AllowedActions;
public record ProjectTask(UUID id,String taskCode,UUID projectId,UUID stageId,String name,String description,String owner,UUID ownerUserId,ProjectPriority priority,LocalDate plannedDate,LocalDate plannedEnd,String status,long experimentCount,long version,Instant createdAt,Instant updatedAt) {
    public ProjectTask(UUID id,String taskCode,UUID projectId,UUID stageId,String name,String description,String owner,ProjectPriority priority,LocalDate plannedDate,LocalDate plannedEnd,String status,long experimentCount,long version,Instant createdAt,Instant updatedAt) {
        this(id,taskCode,projectId,stageId,name,description,owner,null,priority,plannedDate,plannedEnd,status,experimentCount,version,createdAt,updatedAt);
    }
    public ProjectTask(UUID id,String taskCode,UUID projectId,UUID stageId,String name,String owner,ProjectPriority priority,LocalDate plannedDate,LocalDate plannedEnd,String status,long experimentCount,long version,Instant createdAt,Instant updatedAt) {
        this(id,taskCode,projectId,stageId,name,null,owner,null,priority,plannedDate,plannedEnd,status,experimentCount,version,createdAt,updatedAt);
    }
 public List<String> getAllowedActions() { return AllowedActions.projectResource(status, "COMPLETED".equalsIgnoreCase(status), experimentCount > 0); }
}
