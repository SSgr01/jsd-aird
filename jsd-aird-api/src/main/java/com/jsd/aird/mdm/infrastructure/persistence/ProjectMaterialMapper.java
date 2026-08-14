package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.infrastructure.model.ProjectMaterialProjection;
import com.jsd.aird.mdm.infrastructure.model.ProjectMaterialRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ProjectMaterialMapper {

    String SELECT_COLS = "pm.id, pm.project_id, pm.material_id, pm.created_at, " +
            "m.code AS m_code, m.name AS m_name, m.category AS m_category, m.source_category AS m_source_category, " +
            "m.source_module AS m_source_module, m.stage AS m_stage, m.contact_person AS m_contact_person, m.status AS m_status";

    String JOIN = "FROM mdm.project_material pm JOIN mdm.material m ON m.id = pm.material_id";

    @Select("<script>SELECT " + SELECT_COLS + " " + JOIN +
            " <where>" +
            " <if test='projectId!=null'>AND pm.project_id=#{projectId}</if>" +
            " <if test='category!=null and category!=\"\"'>AND m.category=#{category}</if>" +
            " <if test='stage!=null and stage!=\"\"'>AND m.stage=#{stage}</if>" +
            " <if test='status!=null and status!=\"\"'>AND m.status=#{status}</if>" +
            " <if test='keyword!=null and keyword!=\"\"'>AND (m.code ILIKE concat('%', #{keyword}, '%') OR m.name ILIKE concat('%', #{keyword}, '%'))</if>" +
            " </where>" +
            " ORDER BY pm.created_at DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<ProjectMaterialProjection> listByProject(@Param("projectId") UUID projectId,
                                                   @Param("category") String category,
                                                   @Param("stage") String stage,
                                                   @Param("status") String status,
                                                   @Param("keyword") String keyword,
                                                   @Param("offset") int offset,
                                                   @Param("size") int size);

    @Select("<script>SELECT count(*) " + JOIN +
            " <where>" +
            " <if test='projectId!=null'>AND pm.project_id=#{projectId}</if>" +
            " <if test='category!=null and category!=\"\"'>AND m.category=#{category}</if>" +
            " <if test='stage!=null and stage!=\"\"'>AND m.stage=#{stage}</if>" +
            " <if test='status!=null and status!=\"\"'>AND m.status=#{status}</if>" +
            " <if test='keyword!=null and keyword!=\"\"'>AND (m.code ILIKE concat('%', #{keyword}, '%') OR m.name ILIKE concat('%', #{keyword}, '%'))</if>" +
            " </where></script>")
    long countByProject(@Param("projectId") UUID projectId,
                        @Param("category") String category,
                        @Param("stage") String stage,
                        @Param("status") String status,
                        @Param("keyword") String keyword);

    @Insert("INSERT INTO mdm.project_material(id, project_id, material_id, created_at, updated_at, created_by, updated_by) " +
            "VALUES(#{row.id}, #{row.projectId}, #{row.materialId}, now(), now(), 'system', 'system')")
    void insert(@Param("row") ProjectMaterialRow row);

    @Update("UPDATE mdm.project_material SET updated_at=now(), updated_by='system' WHERE id=#{row.id}")
    int updateRemark(@Param("row") ProjectMaterialRow row);

    @Delete("DELETE FROM mdm.project_material WHERE project_id=#{projectId} AND material_id=#{materialId}")
    int unlink(@Param("projectId") UUID projectId, @Param("materialId") UUID materialId);

    @Select("SELECT material_id FROM mdm.project_material WHERE project_id=#{projectId}")
    List<UUID> findLinkedMaterialIds(@Param("projectId") UUID projectId);
}