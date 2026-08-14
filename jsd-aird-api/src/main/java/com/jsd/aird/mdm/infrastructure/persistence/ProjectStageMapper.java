package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.infrastructure.model.ProjectStageRow;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ProjectStageMapper {
    String COLUMNS = "SELECT s.id,s.project_id projectId,p.project_code projectCode,p.name projectName," +
        "s.stage_code stageCode,s.name,s.order_no orderNo,s.status,s.owner,s.description,s.planned_start plannedStart," +
        "s.planned_end plannedEnd,s.actual_start actualStart,s.actual_end actualEnd," +
        "mdm.project_stage_task_count(s.id) taskCount,mdm.project_stage_open_task_count(s.id) openTaskCount," +
        "s.version,s.created_at createdAt,s.updated_at updatedAt FROM mdm.project_stage s " +
        "JOIN mdm.project p ON p.id=s.project_id ";

    String FILTERS = "<if test='keyword != null'> AND (s.name ILIKE concat('%',#{keyword},'%') OR p.name ILIKE concat('%',#{keyword},'%') OR p.project_code ILIKE concat('%',#{keyword},'%') OR s.stage_code ILIKE concat('%',#{keyword},'%'))</if>" +
        "<if test='projectId != null'> AND s.project_id=#{projectId}</if>" +
        "<if test='status != null'> AND s.status=#{status}</if>" +
        "<if test='owner != null'> AND s.owner=#{owner}</if>" +
        "<if test='plannedFrom != null'> AND s.planned_end &gt;= #{plannedFrom}</if>" +
        "<if test='plannedTo != null'> AND s.planned_start &lt;= #{plannedTo}</if>";

    @Select(COLUMNS + "WHERE s.project_id=#{projectId} AND s.deleted=false AND p.deleted=false ORDER BY s.order_no")
    List<ProjectStageRow> findByProject(@Param("projectId") UUID projectId);

    @Select(COLUMNS + "WHERE s.id=#{id} AND s.deleted=false AND p.deleted=false")
    Optional<ProjectStageRow> findById(@Param("id") UUID id);

    @Select("<script>" + COLUMNS + "WHERE s.deleted=false AND p.deleted=false " + FILTERS +
        " ORDER BY s.updated_at DESC,s.order_no LIMIT #{size} OFFSET #{offset}</script>")
    List<ProjectStageRow> findPage(@Param("keyword") String keyword, @Param("projectId") UUID projectId,
                                   @Param("status") String status, @Param("owner") String owner,
                                   @Param("plannedFrom") LocalDate plannedFrom, @Param("plannedTo") LocalDate plannedTo,
                                   @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT count(*) FROM mdm.project_stage s JOIN mdm.project p ON p.id=s.project_id " +
        "WHERE s.deleted=false AND p.deleted=false " + FILTERS + "</script>")
    long count(@Param("keyword") String keyword, @Param("projectId") UUID projectId,
               @Param("status") String status, @Param("owner") String owner,
               @Param("plannedFrom") LocalDate plannedFrom, @Param("plannedTo") LocalDate plannedTo);

    @Select("<script>SELECT EXISTS(SELECT 1 FROM mdm.project_stage WHERE project_id=#{projectId} AND deleted=false " +
        "AND lower(name)=lower(#{name}) <if test='excludedId != null'>AND id&lt;&gt;#{excludedId}</if>)</script>")
    boolean existsActiveName(@Param("projectId") UUID projectId, @Param("name") String name,
                             @Param("excludedId") UUID excludedId);

    @Select("SELECT coalesce(max(order_no),0)+1 FROM mdm.project_stage WHERE project_id=#{projectId} AND deleted=false")
    int nextOrderNo(@Param("projectId") UUID projectId);

    @Insert("INSERT INTO mdm.project_stage(id,project_id,stage_code,name,order_no,status,owner,description,planned_start,planned_end," +
        "actual_start,actual_end,version,deleted,created_at,updated_at,created_by,updated_by) VALUES(" +
        "#{id},#{projectId},#{stageCode},#{name},#{orderNo},#{status},#{owner},#{description},#{plannedStart},#{plannedEnd}," +
        "#{actualStart},#{actualEnd},0,false,#{now},#{now},#{operator},#{operator})")
    void insert(@Param("id") UUID id, @Param("projectId") UUID projectId, @Param("stageCode") String stageCode,
                @Param("name") String name, @Param("orderNo") int orderNo, @Param("status") String status,
                @Param("owner") String owner, @Param("description") String description,
                @Param("plannedStart") LocalDate plannedStart, @Param("plannedEnd") LocalDate plannedEnd,
                @Param("actualStart") Instant actualStart, @Param("actualEnd") Instant actualEnd,
                @Param("now") Instant now, @Param("operator") String operator);

    @Update("UPDATE mdm.project_stage SET stage_code=#{stageCode},name=#{name},status=#{status},owner=#{owner},description=#{description}," +
        "planned_start=#{plannedStart},planned_end=#{plannedEnd},actual_start=#{actualStart},actual_end=#{actualEnd}," +
        "version=version+1,updated_at=now(),updated_by=#{operator} WHERE id=#{id} AND deleted=false AND version=#{version}")
    int update(@Param("id") UUID id, @Param("stageCode") String stageCode, @Param("name") String name,
               @Param("status") String status, @Param("owner") String owner, @Param("description") String description,
               @Param("plannedStart") LocalDate plannedStart, @Param("plannedEnd") LocalDate plannedEnd,
               @Param("actualStart") Instant actualStart, @Param("actualEnd") Instant actualEnd,
               @Param("version") long version, @Param("operator") String operator);

    @Update("UPDATE mdm.project_stage SET deleted=true,deleted_at=now(),deleted_by=#{operator},version=version+1," +
        "updated_at=now(),updated_by=#{operator} WHERE id=#{id} AND deleted=false AND version=#{version}")
    int softDelete(@Param("id") UUID id, @Param("version") long version, @Param("operator") String operator);

    @Update("UPDATE mdm.project_stage SET order_no=order_no+1000000 WHERE project_id=#{projectId} AND deleted=false")
    void parkOrderNumbers(@Param("projectId") UUID projectId);

    @Update("UPDATE mdm.project_stage SET order_no=#{orderNo},version=version+1,updated_at=now(),updated_by=#{operator} " +
        "WHERE id=#{id} AND project_id=#{projectId} AND deleted=false AND version=#{version}")
    int updateOrder(@Param("id") UUID id, @Param("projectId") UUID projectId, @Param("orderNo") int orderNo,
                    @Param("version") long version, @Param("operator") String operator);

}
