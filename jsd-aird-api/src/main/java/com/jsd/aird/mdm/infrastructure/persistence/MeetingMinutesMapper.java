package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.infrastructure.model.MeetingMinutesRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface MeetingMinutesMapper {

    String COLS = "id, project_id, title, attendees, summary, occurred_at, archived_to_kb, version, created_at, updated_at";

    @Select("SELECT " + COLS + " FROM mdm.meeting_minutes WHERE project_id=#{projectId} ORDER BY occurred_at DESC LIMIT #{size} OFFSET #{offset}")
    List<MeetingMinutesRow> listByProject(@Param("projectId") UUID projectId, @Param("offset") int offset, @Param("size") int size);

    @Select("SELECT count(*) FROM mdm.meeting_minutes WHERE project_id=#{projectId}")
    long countByProject(@Param("projectId") UUID projectId);

    @Select("SELECT " + COLS + " FROM mdm.meeting_minutes WHERE id=#{id}")
    MeetingMinutesRow findById(@Param("id") UUID id);

    @Insert("INSERT INTO mdm.meeting_minutes(id, project_id, title, attendees, summary, occurred_at, archived_to_kb, version, created_at, created_by, updated_at) " +
            "VALUES(#{row.id}, #{row.projectId}, #{row.title}, #{row.attendees}::jsonb, #{row.summary}, #{row.occurredAt}, #{row.archivedToKb}, #{row.version}, #{row.createdAt}, #{operator}, #{row.updatedAt})")
    void insert(@Param("row") MeetingMinutesRow row, @Param("operator") String operator);

    @Update("UPDATE mdm.meeting_minutes SET title=#{row.title}, attendees=#{row.attendees}::jsonb, summary=#{row.summary}, occurred_at=#{row.occurredAt}, archived_to_kb=#{row.archivedToKb}, version=#{row.version}, updated_at=#{row.updatedAt}, updated_by=#{operator} WHERE id=#{row.id} AND version=#{currentVersion}")
    int update(@Param("row") MeetingMinutesRow row, @Param("currentVersion") long currentVersion, @Param("operator") String operator);

    @Delete("DELETE FROM mdm.meeting_minutes WHERE id=#{id} AND version=#{version}")
    int delete(@Param("id") UUID id, @Param("version") long version);
}