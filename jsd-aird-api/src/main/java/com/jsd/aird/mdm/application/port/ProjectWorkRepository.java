package com.jsd.aird.mdm.application.port;
import com.jsd.aird.mdm.application.query.*;
import com.jsd.aird.mdm.domain.model.*;
import java.util.*;
public interface ProjectWorkRepository {
 boolean stageBelongs(UUID stageId, UUID projectId);
 Optional<UUID> stageProjectId(UUID stageId);
 void insertTask(ProjectTask task, String operator);
 Optional<ProjectTask> task(UUID id);
 int updateTask(ProjectTask task, String operator);
 int deleteTask(UUID id,long version,String operator);

List<ProjectTask> tasks(UUID stageId);
int moveTaskExperiments(UUID taskId, UUID toStageId);
int moveTaskTestRecords(UUID taskId, UUID toStageId);
int moveTaskExperimentImportJobs(UUID taskId, UUID toStageId);
int moveTaskUploadFiles(UUID taskId, UUID toStageId);
int moveTaskDataRecords(UUID taskId, UUID toStageId);
int moveTaskProductionUploads(UUID taskId, UUID toStageId);
int dropShadowedTaskResourceLinks(UUID taskId, UUID toStageId);
int moveTaskResourceLinks(UUID taskId, UUID toStageId);
int dropShadowedTaskReferences(UUID taskId, UUID toStageId);
int moveTaskReferences(UUID taskId, UUID toStageId);
List<ProjectTaskSummaryRow> findTaskPage(ProjectTaskQuery query, long offset, int limit);
long countTasks(ProjectTaskQuery query);
List<String> findTaskOwners(ProjectTaskQuery query);
}
