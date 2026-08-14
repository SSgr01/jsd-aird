package com.jsd.aird.mdm.domain.model;
import java.time.*;
import java.util.UUID;
public record ProjectExperiment(UUID id,String experimentCode,UUID projectId,UUID stageId,UUID taskId,String title,String category,String owner,LocalDate experimentDate,String status,String templateName,String templateVersion,String workbookContent,long version,Instant createdAt,Instant updatedAt) {}
