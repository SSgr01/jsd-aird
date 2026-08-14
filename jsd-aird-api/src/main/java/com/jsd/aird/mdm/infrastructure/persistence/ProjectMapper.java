package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.infrastructure.model.ProjectRow;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ProjectMapper {

    String SELECT_COLUMNS = "SELECT p.id, p.project_code projectCode, p.name, p.partner_id partnerId, " +
        "p.partner_name partnerName, p.owner, p.start_date startDate, p.end_date endDate, p.priority, p.status, " +
        "p.team_size teamSize, p.background, p.custom_fields::text customFields, p.team_members::text teamMembers, p.version, " +
        "p.created_at createdAt, p.updated_at updatedAt FROM mdm.project p ";

    String FILTERS = "<if test='keyword != null'> AND (p.name ILIKE concat('%',#{keyword},'%') " +
        "OR p.project_code ILIKE concat('%',#{keyword},'%') OR p.partner_name ILIKE concat('%',#{keyword},'%') " +
        "OR p.owner ILIKE concat('%',#{keyword},'%'))</if>" +
        "<if test='owner != null'> AND p.owner = #{owner}</if>" +
        "<if test='priority != null'> AND p.priority = #{priority}</if>" +
        "<if test='status != null'> AND p.status = #{status}</if>" +
        "<if test='partnerId != null'> AND p.partner_id = #{partnerId}</if>" +
        "<if test='startDateFrom != null'> AND p.start_date &gt;= #{startDateFrom}</if>" +
        "<if test='startDateTo != null'> AND p.start_date &lt;= #{startDateTo}</if>";

    @Select("<script>" + SELECT_COLUMNS + "WHERE p.deleted = false " + FILTERS +
        " ORDER BY p.updated_at DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<ProjectRow> findPage(@Param("keyword") String keyword, @Param("owner") String owner,
                              @Param("priority") String priority, @Param("status") String status,
                              @Param("partnerId") UUID partnerId,
                              @Param("startDateFrom") LocalDate startDateFrom, @Param("startDateTo") LocalDate startDateTo,
                              @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT count(*) FROM mdm.project p WHERE p.deleted = false " + FILTERS + "</script>")
    long count(@Param("keyword") String keyword, @Param("owner") String owner,
               @Param("priority") String priority, @Param("status") String status,
               @Param("partnerId") UUID partnerId,
               @Param("startDateFrom") LocalDate startDateFrom, @Param("startDateTo") LocalDate startDateTo);

    @Select(SELECT_COLUMNS + "WHERE p.id = #{id} AND p.deleted = false")
    Optional<ProjectRow> findById(@Param("id") UUID id);

    @Select("<script>" + SELECT_COLUMNS + "WHERE p.deleted = false AND p.id IN " +
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id,jdbcType=OTHER}</foreach>" +
        "</script>")
    List<ProjectRow> findAllByIds(@Param("ids") List<UUID> ids);

    @Select("<script>SELECT EXISTS(SELECT 1 FROM mdm.project WHERE project_code = #{projectCode} AND deleted = false " +
        "<if test='excludedId != null'> AND id != #{excludedId}</if>)</script>")
    boolean existsByProjectCode(@Param("projectCode") String projectCode, @Param("excludedId") UUID excludedId);

    @Select("SELECT coalesce(max(substring(project_code from '[0-9]+$')::int), 0) FROM mdm.project " +
        "WHERE project_code LIKE #{prefix} || '%'")
    int maxSequence(@Param("prefix") String prefix);

    @Insert("INSERT INTO mdm.project(id,project_code,name,partner_id,partner_name,owner,start_date,end_date," +
        "priority,status,team_size,background,custom_fields,team_members,version,created_at,updated_at,created_by,updated_by) " +
        "VALUES(#{id},#{projectCode},#{name},#{partnerId},#{partnerName},#{owner},#{startDate},#{endDate}," +
        "#{priority},#{status},#{teamSize},#{background},CAST(#{customFields} AS jsonb),CAST(#{teamMembers} AS jsonb),0,#{now},#{now},#{operator},#{operator})")
    void insert(@Param("id") UUID id, @Param("projectCode") String projectCode, @Param("name") String name,
                @Param("partnerId") UUID partnerId, @Param("partnerName") String partnerName,
                @Param("owner") String owner, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
                @Param("priority") String priority, @Param("status") String status, @Param("teamSize") int teamSize,
                @Param("background") String background, @Param("customFields") String customFields,
                @Param("teamMembers") String teamMembers,
                @Param("now") Instant now, @Param("operator") String operator);

    @Update("UPDATE mdm.project SET project_code=#{projectCode},name=#{name},partner_id=#{partnerId}," +
        "partner_name=#{partnerName},owner=#{owner},start_date=#{startDate},end_date=#{endDate}," +
        "priority=#{priority},status=#{status},team_size=#{teamSize},background=#{background}," +
        "custom_fields=CAST(#{customFields} AS jsonb),team_members=CAST(#{teamMembers} AS jsonb)," +
        "version=version+1,updated_at=now(),updated_by=#{operator} " +
        "WHERE id=#{id} AND deleted=false AND version=#{version}")
    int update(@Param("id") UUID id, @Param("projectCode") String projectCode, @Param("name") String name,
               @Param("partnerId") UUID partnerId, @Param("partnerName") String partnerName,
               @Param("owner") String owner, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
               @Param("priority") String priority, @Param("status") String status, @Param("teamSize") int teamSize,
               @Param("background") String background, @Param("customFields") String customFields,
               @Param("teamMembers") String teamMembers,
               @Param("version") long version, @Param("operator") String operator);

    @Update("UPDATE mdm.project SET deleted=true,version=version+1,updated_at=now(),updated_by=#{operator} " +
        "WHERE id=#{id} AND deleted=false")
    int softDelete(@Param("id") UUID id, @Param("operator") String operator);
}
