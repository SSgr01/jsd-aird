package com.jsd.aird.mdm.infrastructure.persistence;
import com.jsd.aird.mdm.application.query.ProjectTaskQuery;
import com.jsd.aird.mdm.application.query.ProjectTaskSummary;
import com.jsd.aird.mdm.application.port.ProjectWorkRepository;
import com.jsd.aird.mdm.domain.model.*;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.*;

@Mapper
public interface ProjectWorkMapper extends ProjectWorkRepository {
 @Select("SELECT t.id,t.task_code taskCode,t.project_id projectId,t.stage_id stageId,t.name,t.owner,t.planned_date plannedDate,t.status,"+
  "(SELECT count(*) FROM mdm.project_experiment e WHERE e.task_id=t.id AND e.deleted=false) experimentCount,t.version,t.created_at createdAt,t.updated_at updatedAt " +
  "FROM mdm.project_task t WHERE t.stage_id=#{stageId} AND t.deleted=false ORDER BY t.created_at")
 List<ProjectTask> tasks(UUID stageId);
 @Select("SELECT count(*) FROM mdm.project_stage WHERE id=#{stageId} AND project_id=#{projectId} AND deleted=false") boolean stageBelongs(UUID stageId,UUID projectId);
 @Insert("INSERT INTO mdm.project_task(id,task_code,project_id,stage_id,name,owner,planned_date,status,created_by,updated_by) VALUES(#{id},#{taskCode},#{projectId},#{stageId},#{name},#{owner},#{plannedDate},#{status},'system','system')") void insertTask(ProjectTask task);
 @Select("SELECT t.id,t.task_code taskCode,t.project_id projectId,t.stage_id stageId,t.name,t.owner,t.planned_date plannedDate,t.status,"+
  "(SELECT count(*) FROM mdm.project_experiment e WHERE e.task_id=t.id AND e.deleted=false) experimentCount,t.version,t.created_at createdAt,t.updated_at updatedAt FROM mdm.project_task t WHERE t.id=#{id} AND t.deleted=false") Optional<ProjectTask> task(UUID id);
 @Select("SELECT id,experiment_code experimentCode,project_id projectId,stage_id stageId,task_id taskId,title,category,owner,experiment_date experimentDate,status,template_name templateName,template_version templateVersion,workbook_content workbookContent,version,created_at createdAt,updated_at updatedAt FROM mdm.project_experiment WHERE task_id=#{taskId} AND deleted=false ORDER BY created_at") List<ProjectExperiment> experiments(UUID taskId);
 @Update("UPDATE mdm.project_task SET name=#{name},owner=#{owner},planned_date=#{plannedDate},status=#{status},version=version+1,updated_at=now() WHERE id=#{id} AND version=#{version}") int updateTask(ProjectTask task);
 @Insert("INSERT INTO mdm.project_experiment(id,experiment_code,project_id,stage_id,task_id,title,category,owner,experiment_date,status,template_name,template_version,workbook_content,created_by,updated_by) VALUES(#{id},#{experimentCode},#{projectId},#{stageId},#{taskId},#{title},#{category},#{owner},#{experimentDate},#{status},#{templateName},#{templateVersion},#{workbookContent},'system','system')") void insertExperiment(ProjectExperiment experiment);
 @Select("SELECT id,experiment_code experimentCode,project_id projectId,stage_id stageId,task_id taskId,title,category,owner,experiment_date experimentDate,status,template_name templateName,template_version templateVersion,workbook_content workbookContent,version,created_at createdAt,updated_at updatedAt FROM mdm.project_experiment WHERE id=#{id} AND deleted=false") Optional<ProjectExperiment> experiment(UUID id);
 @Update("UPDATE mdm.project_experiment SET title=#{title},category=#{category},owner=#{owner},experiment_date=#{experimentDate},version=version+1,updated_at=now() WHERE id=#{id} AND version=#{version}") int updateExperiment(UUID id,String title,String category,String owner,LocalDate experimentDate,long version);
@Update("UPDATE mdm.project_experiment SET deleted=true,version=version+1,updated_at=now() WHERE id=#{id} AND version=#{version} AND deleted=false") int deleteExperiment(UUID id,long version);
 @SelectProvider(type = ProjectWorkSqlProvider.class, method = "findPage")
 List<ProjectTaskSummary> findTaskPage(@Param("query") ProjectTaskQuery query, @Param("offset") long offset, @Param("limit") int limit);
 @SelectProvider(type = ProjectWorkSqlProvider.class, method = "count")
 long countTasks(@Param("query") ProjectTaskQuery query);
 @Select("SELECT DISTINCT t.owner FROM mdm.project_task t WHERE t.deleted = false AND t.owner IS NOT NULL AND t.owner <> '' ORDER BY t.owner")
 List<String> findTaskOwners();
}
