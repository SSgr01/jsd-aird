package com.jsd.aird.mdm.infrastructure.persistence;
import com.jsd.aird.mdm.application.query.ProjectTaskQuery;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.mdm.infrastructure.model.ProjectTaskSummaryRow;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.*;

@Mapper
public interface ProjectWorkMapper {
 @Select("SELECT t.id,t.task_code taskCode,t.project_id projectId,t.stage_id stageId,t.name,t.owner,t.planned_date plannedDate,t.status,"+
  "(SELECT count(*) FROM rnd.experiment e WHERE e.task_id=t.id AND e.deleted=false) experimentCount,t.version,t.created_at createdAt,t.updated_at updatedAt " +
  "FROM mdm.project_task t WHERE t.stage_id=#{stageId} AND t.deleted=false ORDER BY t.created_at")
 List<ProjectTask> tasks(UUID stageId);
 @Select("SELECT count(*) FROM mdm.project_stage WHERE id=#{stageId} AND project_id=#{projectId} AND deleted=false") boolean stageBelongs(UUID stageId,UUID projectId);
 @Insert("INSERT INTO mdm.project_task(id,task_code,project_id,stage_id,name,owner,planned_date,status,created_by,updated_by) VALUES(#{id},#{taskCode},#{projectId},#{stageId},#{name},#{owner},#{plannedDate},#{status},'system','system')") void insertTask(ProjectTask task);
 @Select("SELECT t.id,t.task_code taskCode,t.project_id projectId,t.stage_id stageId,t.name,t.owner,t.planned_date plannedDate,t.status,"+
  "(SELECT count(*) FROM rnd.experiment e WHERE e.task_id=t.id AND e.deleted=false) experimentCount,t.version,t.created_at createdAt,t.updated_at updatedAt FROM mdm.project_task t WHERE t.id=#{id} AND t.deleted=false") Optional<ProjectTask> task(UUID id);
 @Update("UPDATE mdm.project_task SET name=#{name},owner=#{owner},planned_date=#{plannedDate},status=#{status},version=version+1,updated_at=now() WHERE id=#{id} AND version=#{version}") int updateTask(ProjectTask task);
 @Update("UPDATE mdm.project_task SET deleted=true,version=version+1,updated_at=now() WHERE id=#{id} AND version=#{version} AND deleted=false") int deleteTask(@Param("id") UUID id,@Param("version") long version);
 @SelectProvider(type = ProjectWorkSqlProvider.class, method = "findPage")
 List<ProjectTaskSummaryRow> findTaskPage(@Param("query") ProjectTaskQuery query, @Param("offset") long offset, @Param("limit") int limit);
 @SelectProvider(type = ProjectWorkSqlProvider.class, method = "count")
 long countTasks(@Param("query") ProjectTaskQuery query);
 @Select("SELECT DISTINCT t.owner FROM mdm.project_task t WHERE t.deleted = false AND t.owner IS NOT NULL AND t.owner <> '' ORDER BY t.owner")
 List<String> findTaskOwners();
}
