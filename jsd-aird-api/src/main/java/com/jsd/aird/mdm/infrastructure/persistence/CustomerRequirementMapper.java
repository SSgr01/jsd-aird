package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.infrastructure.model.RequirementRow;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface CustomerRequirementMapper {
    String COLS = "SELECT cr.id, cr.requirement_code requirementCode, cr.partner_id partnerId, cr.title, " +
        "cr.raw_requirement rawRequirement, cr.urgency, cr.raised_at raisedAt, cr.delivery_date deliveryDate, " +
        "cr.status, cr.custom_status_name customStatusName, cr.project_id projectId, " +
        "cr.assigned_project_ids::text assignedProjectIds, " +
        "cr.custom_fields::text customFields, cr.version, cr.created_at createdAt, cr.updated_at updatedAt " +
        "FROM mdm.customer_requirement cr ";

    @Select("<script>" + COLS +
        "WHERE 1=1 <if test='partnerId != null'> AND cr.partner_id=#{partnerId}</if>" +
        "<if test='status != null'> AND cr.status=#{status}</if>" +
        "<if test='projectId != null'> AND (cr.project_id=#{projectId} OR cr.assigned_project_ids @> jsonb_build_array(CAST(#{projectId} AS text)))</if> " +
        "ORDER BY cr.updated_at DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<RequirementRow> findPage(@Param("partnerId") UUID partnerId, @Param("projectId") UUID projectId,
                                  @Param("status") String status, @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT count(*) FROM mdm.customer_requirement cr WHERE 1=1 " +
        "<if test='partnerId != null'> AND cr.partner_id=#{partnerId}</if>" +
        "<if test='status != null'> AND cr.status=#{status}</if>" +
        "<if test='projectId != null'> AND (cr.project_id=#{projectId} OR cr.assigned_project_ids @> jsonb_build_array(CAST(#{projectId} AS text)))</if></script>")
    long count(@Param("partnerId") UUID partnerId, @Param("projectId") UUID projectId, @Param("status") String status);

    @Select(COLS + "WHERE cr.id=#{id}")
    Optional<RequirementRow> findById(@Param("id") UUID id);

    @Insert("INSERT INTO mdm.customer_requirement(id,requirement_code,partner_id,title,raw_requirement,urgency,raised_at," +
        "delivery_date,status,custom_status_name,project_id,assigned_project_ids,custom_fields,version,created_at,updated_at,created_by,updated_by) " +
        "VALUES(#{id},#{requirementCode},#{partnerId},#{title},#{rawRequirement},#{urgency},#{raisedAt}," +
        "#{deliveryDate},#{status},#{customStatusName},#{projectId},CAST(#{assignedProjectIds} AS jsonb),CAST(#{customFields} AS jsonb)," +
        "0,#{now},#{now},#{operator},#{operator})")
    void insertRequirement(@Param("id") UUID id, @Param("requirementCode") String requirementCode,
                           @Param("partnerId") UUID partnerId, @Param("title") String title,
                           @Param("rawRequirement") String rawRequirement, @Param("urgency") String urgency,
                           @Param("raisedAt") LocalDate raisedAt, @Param("deliveryDate") LocalDate deliveryDate,
                           @Param("status") String status, @Param("customStatusName") String customStatusName,
                           @Param("projectId") UUID projectId, @Param("assignedProjectIds") String assignedProjectIds,
                           @Param("customFields") String customFields,
                           @Param("now") Instant now, @Param("operator") String operator);

    @Update("UPDATE mdm.customer_requirement SET title=#{title},raw_requirement=#{rawRequirement},urgency=#{urgency}," +
        "raised_at=#{raisedAt},delivery_date=#{deliveryDate},status=#{status},custom_status_name=#{customStatusName}," +
        "project_id=#{projectId},assigned_project_ids=CAST(#{assignedProjectIds} AS jsonb),custom_fields=CAST(#{customFields} AS jsonb)," +
        "version=version+1,updated_at=now(),updated_by=#{operator} WHERE id=#{id} AND version=#{version}")
    int updateRequirement(@Param("id") UUID id, @Param("title") String title,
                          @Param("rawRequirement") String rawRequirement, @Param("urgency") String urgency,
                          @Param("raisedAt") LocalDate raisedAt, @Param("deliveryDate") LocalDate deliveryDate,
                          @Param("status") String status, @Param("customStatusName") String customStatusName,
                          @Param("projectId") UUID projectId, @Param("assignedProjectIds") String assignedProjectIds,
                          @Param("customFields") String customFields,
                          @Param("version") long version, @Param("operator") String operator);
}
