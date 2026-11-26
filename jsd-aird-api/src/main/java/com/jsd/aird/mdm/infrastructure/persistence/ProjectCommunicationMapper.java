package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.domain.model.ProjectCommunicationRecord;
import com.jsd.aird.mdm.infrastructure.model.ProjectCommunicationRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ProjectCommunicationMapper {
    String COLUMNS = "id, project_id projectId, occurred_at occurredAt, communication_method communicationMethod, "
            + "communicator, content, custom_fields::text customFields, version, created_at createdAt, updated_at updatedAt";

    @Select("SELECT " + COLUMNS + " FROM mdm.project_communication_record WHERE organization_id=#{organizationId} "
            + "AND project_id=#{projectId} AND deleted=false ORDER BY occurred_at DESC, created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<ProjectCommunicationRow> list(@Param("organizationId") UUID organizationId, @Param("projectId") UUID projectId,
                                          @Param("offset") int offset, @Param("size") int size);

    @Select("SELECT count(*) FROM mdm.project_communication_record WHERE organization_id=#{organizationId} "
            + "AND project_id=#{projectId} AND deleted=false")
    long count(@Param("organizationId") UUID organizationId, @Param("projectId") UUID projectId);

    @Select("SELECT " + COLUMNS + " FROM mdm.project_communication_record WHERE organization_id=#{organizationId} "
            + "AND project_id=#{projectId} AND id=#{id} AND deleted=false")
    ProjectCommunicationRow find(@Param("organizationId") UUID organizationId, @Param("projectId") UUID projectId,
                                    @Param("id") UUID id);

    @Insert("INSERT INTO mdm.project_communication_record(id,organization_id,project_id,occurred_at,communication_method,"
            + "communicator,content,custom_fields,created_by,updated_by) VALUES(#{record.id},#{organizationId},#{record.projectId},"
            + "#{record.occurredAt},#{record.communicationMethod},#{record.communicator},#{record.content},"
            + "CAST(#{customFields} AS jsonb),#{operator},#{operator})")
    void insert(@Param("organizationId") UUID organizationId, @Param("record") ProjectCommunicationRecord record,
                @Param("customFields") String customFields, @Param("operator") UUID operator);

    @Update("UPDATE mdm.project_communication_record SET occurred_at=#{record.occurredAt},"
            + "communication_method=#{record.communicationMethod},communicator=#{record.communicator},content=#{record.content},"
            + "custom_fields=CAST(#{customFields} AS jsonb),"
            + "version=version+1,updated_at=now(),updated_by=#{operator} WHERE organization_id=#{organizationId} "
            + "AND project_id=#{record.projectId} AND id=#{record.id} AND version=#{record.version} AND deleted=false")
    int update(@Param("organizationId") UUID organizationId, @Param("record") ProjectCommunicationRecord record,
               @Param("customFields") String customFields, @Param("operator") UUID operator);

    @Update("UPDATE mdm.project_communication_record SET deleted=true,version=version+1,updated_at=now(),updated_by=#{operator} "
            + "WHERE organization_id=#{organizationId} AND project_id=#{projectId} AND id=#{id} AND version=#{version} AND deleted=false")
    int delete(@Param("organizationId") UUID organizationId, @Param("projectId") UUID projectId,
               @Param("id") UUID id, @Param("version") long version, @Param("operator") UUID operator);
}
