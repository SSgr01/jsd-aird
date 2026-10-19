package com.jsd.aird.mdm.infrastructure.persistence;
import com.jsd.aird.mdm.application.query.ProjectTaskQuery;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.mdm.infrastructure.model.ProjectTaskSummaryRow;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.*;

@Mapper
public interface ProjectWorkMapper {
 @Select("SELECT t.id,t.task_code taskCode,t.project_id projectId,t.stage_id stageId,t.name,t.owner,t.priority,t.planned_date plannedDate,t.status,"+
  "(SELECT count(*) FROM rnd.experiment e WHERE e.task_id=t.id AND e.deleted=false) experimentCount,t.version,t.created_at createdAt,t.updated_at updatedAt " +
  "FROM mdm.project_task t WHERE t.stage_id=#{stageId} AND t.deleted=false ORDER BY t.created_at")
 List<ProjectTask> tasks(UUID stageId);
 @Select("SELECT count(*) FROM mdm.project_stage WHERE id=#{stageId} AND project_id=#{projectId} AND deleted=false") boolean stageBelongs(UUID stageId,UUID projectId);
 @Insert("INSERT INTO mdm.project_task(id,task_code,project_id,stage_id,name,owner,priority,planned_date,status,created_by,updated_by) VALUES(#{id},#{taskCode},#{projectId},#{stageId},#{name},#{owner},#{priority},#{plannedDate},#{status},'system','system')") void insertTask(ProjectTask task);
 @Select("SELECT t.id,t.task_code taskCode,t.project_id projectId,t.stage_id stageId,t.name,t.owner,t.priority,t.planned_date plannedDate,t.status,"+
  "(SELECT count(*) FROM rnd.experiment e WHERE e.task_id=t.id AND e.deleted=false) experimentCount,t.version,t.created_at createdAt,t.updated_at updatedAt FROM mdm.project_task t WHERE t.id=#{id} AND t.deleted=false") Optional<ProjectTask> task(UUID id);
 // 任务可以调整所属阶段：updateTask 必须一并写入 stage_id，否则前端改了“关联阶段”保存后
 // 仍停留在原阶段（服务层已用 stageBelongs 校验目标阶段属于同一项目）。
 @Update("UPDATE mdm.project_task SET stage_id=#{stageId},name=#{name},owner=#{owner},priority=#{priority},planned_date=#{plannedDate},status=#{status},version=version+1,updated_at=now() WHERE id=#{id} AND version=#{version}") int updateTask(ProjectTask task);
 @Update("UPDATE mdm.project_task SET deleted=true,version=version+1,updated_at=now() WHERE id=#{id} AND version=#{version} AND deleted=false") int deleteTask(@Param("id") UUID id,@Param("version") long version);

 // ===================== 任务换阶段：过程产物一起迁移 =====================
 // 任务下的实验、资料、记录等都以 (stage_id, task_id) 成对存储，且多处查询要求两者一致
 // （ProjectResourceService 的关联查询要求 t.stage_id = r.stage_id，任务板的实验列表也按
 // 阶段+任务过滤），所以任务换阶段时必须把它们一起迁到新阶段，否则这些数据会在新阶段里“消失”。
 // 各表的内容更新语句本身也会写入 stage_id，因此这里同步递增各自的乐观锁版本，避免停留在旧
 // 阶段的编辑器保存时把阶段又写回原值。stage_name 是冗余列，需要一并同步。
 @Update("UPDATE rnd.experiment SET stage_id=#{toStageId},revision=revision+1,updated_at=now() " +
  "WHERE task_id=#{taskId} AND deleted=false AND stage_id IS DISTINCT FROM #{toStageId}")
 int moveTaskExperiments(@Param("taskId") UUID taskId, @Param("toStageId") UUID toStageId);

 @Update("UPDATE rnd.research_test_record SET stage_id=#{toStageId},lock_version=lock_version+1,updated_at=now() " +
  "WHERE task_id=#{taskId} AND deleted=false AND stage_id IS DISTINCT FROM #{toStageId}")
 int moveTaskTestRecords(@Param("taskId") UUID taskId, @Param("toStageId") UUID toStageId);

 @Update("UPDATE rnd.experiment_import_job SET stage_id=#{toStageId},updated_at=now() " +
  "WHERE task_id=#{taskId} AND stage_id IS DISTINCT FROM #{toStageId}")
 int moveTaskExperimentImportJobs(@Param("taskId") UUID taskId, @Param("toStageId") UUID toStageId);

 @Update("UPDATE quality.upload_file SET stage_id=#{toStageId},stage_name=(SELECT name FROM mdm.project_stage WHERE id=#{toStageId}) " +
  "WHERE task_id=#{taskId} AND deleted=false AND stage_id IS DISTINCT FROM #{toStageId}")
 int moveTaskUploadFiles(@Param("taskId") UUID taskId, @Param("toStageId") UUID toStageId);

 @Update("UPDATE quality.data_record SET stage_id=#{toStageId},stage_name=(SELECT name FROM mdm.project_stage WHERE id=#{toStageId})," +
  "lock_version=lock_version+1,updated_at=now() WHERE task_id=#{taskId} AND deleted=false AND stage_id IS DISTINCT FROM #{toStageId}")
 int moveTaskDataRecords(@Param("taskId") UUID taskId, @Param("toStageId") UUID toStageId);

 @Update("UPDATE mfg.production_upload SET stage_id=#{toStageId},stage_name=(SELECT name FROM mdm.project_stage WHERE id=#{toStageId})," +
  "lock_version=lock_version+1,updated_at=now() WHERE task_id=#{taskId} AND stage_id IS DISTINCT FROM #{toStageId}")
 int moveTaskProductionUploads(@Param("taskId") UUID taskId, @Param("toStageId") UUID toStageId);

 // 关联表按 (organization_id, resource_type, resource_id, project_id, stage_id, task_id) 唯一，
 // 目标阶段已存在同一资源的关联时先删掉旧行，再迁移其余行，避免唯一键冲突。
 @Delete("DELETE FROM core.project_resource_link o WHERE o.task_id=#{taskId} AND o.stage_id IS DISTINCT FROM #{toStageId} " +
  "AND EXISTS (SELECT 1 FROM core.project_resource_link n WHERE n.organization_id=o.organization_id " +
  "AND n.resource_type=o.resource_type AND n.resource_id=o.resource_id AND n.project_id=o.project_id " +
  "AND n.task_id=o.task_id AND n.stage_id=#{toStageId})")
 int dropShadowedTaskResourceLinks(@Param("taskId") UUID taskId, @Param("toStageId") UUID toStageId);

 @Update("UPDATE core.project_resource_link SET stage_id=#{toStageId} WHERE task_id=#{taskId} AND stage_id IS DISTINCT FROM #{toStageId}")
 int moveTaskResourceLinks(@Param("taskId") UUID taskId, @Param("toStageId") UUID toStageId);

 @Delete("DELETE FROM core.project_reference o WHERE o.task_id=#{taskId} AND o.stage_id IS DISTINCT FROM #{toStageId} " +
  "AND EXISTS (SELECT 1 FROM core.project_reference n WHERE n.organization_id=o.organization_id " +
  "AND n.resource_type=o.resource_type AND n.resource_id=o.resource_id AND n.project_id=o.project_id " +
  "AND n.task_id=o.task_id AND n.stage_id=#{toStageId})")
 int dropShadowedTaskReferences(@Param("taskId") UUID taskId, @Param("toStageId") UUID toStageId);

 @Update("UPDATE core.project_reference SET stage_id=#{toStageId} WHERE task_id=#{taskId} AND stage_id IS DISTINCT FROM #{toStageId}")
 int moveTaskReferences(@Param("taskId") UUID taskId, @Param("toStageId") UUID toStageId);
 @SelectProvider(type = ProjectWorkSqlProvider.class, method = "findPage")
 List<ProjectTaskSummaryRow> findTaskPage(@Param("query") ProjectTaskQuery query, @Param("offset") long offset, @Param("limit") int limit);
 @SelectProvider(type = ProjectWorkSqlProvider.class, method = "count")
 long countTasks(@Param("query") ProjectTaskQuery query);
 @Select("SELECT DISTINCT t.owner FROM mdm.project_task t WHERE t.deleted = false AND t.owner IS NOT NULL AND t.owner <> '' ORDER BY t.owner")
 List<String> findTaskOwners();
}
