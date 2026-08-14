package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.infrastructure.model.MaterialRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface MaterialMapper {

    String COLS = "id, code, name, category, source_category, source_module, stage, contact_person, status, description, version, created_at, updated_at";

    @Select("SELECT " + COLS + " FROM mdm.material WHERE id=#{id}")
    MaterialRow findById(@Param("id") UUID id);

    @Select("SELECT " + COLS + " FROM mdm.material WHERE code=#{code}")
    MaterialRow findByCode(@Param("code") String code);

    @Select("SELECT " + COLS + " FROM mdm.material ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<MaterialRow> list(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT count(*) FROM mdm.material")
    long count();

    @Select("<script>SELECT " + COLS + ", " +
            "CASE WHEN pm.project_id IS NOT NULL THEN true ELSE false END AS linked " +
            "FROM mdm.material " +
            "LEFT JOIN mdm.project_material pm ON pm.material_id = mdm.material.id AND pm.project_id=#{projectId} " +
            "<where>" +
            " <if test='keyword!=null and keyword!=\"\"'>AND (mdm.material.code ILIKE concat('%', #{keyword}, '%') OR mdm.material.name ILIKE concat('%', #{keyword}, '%'))</if>" +
            " <if test='category!=null and category!=\"\"'>AND mdm.material.category=#{category}</if>" +
            " <if test='owner!=null and owner!=\"\"'>AND mdm.material.source_module=#{owner}</if>" +
            "</where>" +
            "ORDER BY mdm.material.created_at DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<MaterialRow> listByProject(@Param("projectId") UUID projectId,
                                    @Param("keyword") String keyword,
                                    @Param("category") String category,
                                    @Param("owner") String owner,
                                    @Param("offset") int offset,
                                    @Param("size") int size);

    @Select("<script>SELECT count(*) FROM mdm.material " +
            "LEFT JOIN mdm.project_material pm ON pm.material_id = mdm.material.id AND pm.project_id=#{projectId} " +
            "<where>" +
            " <if test='keyword!=null and keyword!=\"\"'>AND (mdm.material.code ILIKE concat('%', #{keyword}, '%') OR mdm.material.name ILIKE concat('%', #{keyword}, '%'))</if>" +
            " <if test='category!=null and category!=\"\"'>AND mdm.material.category=#{category}</if>" +
            " <if test='owner!=null and owner!=\"\"'>AND mdm.material.source_module=#{owner}</if>" +
            "</where></script>")
    long countByProject(@Param("projectId") UUID projectId,
                        @Param("keyword") String keyword,
                        @Param("category") String category,
                        @Param("owner") String owner);

    @Insert("INSERT INTO mdm.material(id, code, name, category, source_category, source_module, stage, contact_person, status, description, version, created_at, created_by, updated_at, updated_by) " +
            "VALUES(#{row.id}, #{row.code}, #{row.name}, #{row.category}, #{row.sourceCategory}, #{row.sourceModule}, #{row.stage}, #{row.contactPerson}, #{row.status}, #{row.description}, #{row.version}, #{row.createdAt}, #{operator}, #{row.updatedAt}, #{operator})")
    void insert(@Param("row") MaterialRow row, @Param("operator") String operator);

    @Update("UPDATE mdm.material SET name=#{row.name}, category=#{row.category}, source_category=#{row.sourceCategory}, source_module=#{row.sourceModule}, stage=#{row.stage}, contact_person=#{row.contactPerson}, status=#{row.status}, description=#{row.description}, version=#{row.version}, updated_at=#{row.updatedAt}, updated_by=#{operator} WHERE id=#{row.id} AND version=#{currentVersion}")
    int update(@Param("row") MaterialRow row, @Param("currentVersion") long currentVersion, @Param("operator") String operator);

    @Delete("DELETE FROM mdm.material WHERE id=#{id} AND version=#{version}")
    int delete(@Param("id") UUID id, @Param("version") long version);
}