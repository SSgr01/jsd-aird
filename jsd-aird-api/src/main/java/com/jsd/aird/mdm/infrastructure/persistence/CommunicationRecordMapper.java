package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.infrastructure.model.CommunicationRow;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface CommunicationRecordMapper {
    String COLS = "SELECT cr.id, cr.record_code recordCode, cr.name, cr.partner_id partnerId, cr.communicated_at communicatedAt, " +
        "cr.internal_participants internalParticipants, cr.communication_method communicationMethod, cr.content, " +
        "cr.status, cr.custom_fields::text customFields, cr.version, cr.created_at createdAt, cr.updated_at updatedAt " +
        "FROM mdm.communication_record cr ";

    @Select("<script>" + COLS +
        "WHERE 1=1 <if test='partnerId != null'> AND cr.partner_id=#{partnerId}</if>" +
        "<if test='status != null'> AND cr.status=#{status}</if> " +
        "ORDER BY cr.communicated_at DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<CommunicationRow> findPage(@Param("partnerId") UUID partnerId, @Param("status") String status,
                                    @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT count(*) FROM mdm.communication_record cr WHERE 1=1 " +
        "<if test='partnerId != null'> AND cr.partner_id=#{partnerId}</if>" +
        "<if test='status != null'> AND cr.status=#{status}</if></script>")
    long count(@Param("partnerId") UUID partnerId, @Param("status") String status);

    @Select(COLS + "WHERE cr.id=#{id}")
    Optional<CommunicationRow> findById(@Param("id") UUID id);

    @Insert("INSERT INTO mdm.communication_record(id,record_code,name,partner_id,communicated_at,internal_participants," +
        "communication_method,content,status,custom_fields,version,created_at,updated_at,created_by,updated_by) " +
        "VALUES(#{id},#{recordCode},#{name},#{partnerId},#{communicatedAt},#{internalParticipants},#{communicationMethod}," +
        "#{content},#{status},CAST(#{customFields} AS jsonb),0,#{now},#{now},#{operator},#{operator})")
    void insertCommunication(@Param("id") UUID id, @Param("recordCode") String recordCode, @Param("name") String name,
                             @Param("partnerId") UUID partnerId, @Param("communicatedAt") Instant communicatedAt,
                             @Param("internalParticipants") String internalParticipants,
                             @Param("communicationMethod") String communicationMethod, @Param("content") String content,
                             @Param("status") String status, @Param("customFields") String customFields,
                             @Param("now") Instant now, @Param("operator") String operator);

    @Update("UPDATE mdm.communication_record SET name=#{name},communicated_at=#{communicatedAt},internal_participants=#{internalParticipants}," +
        "communication_method=#{communicationMethod},content=#{content},status=#{status},custom_fields=CAST(#{customFields} AS jsonb)," +
        "version=version+1,updated_at=now(),updated_by=#{operator} WHERE id=#{id} AND version=#{version}")
    int updateCommunication(@Param("id") UUID id, @Param("name") String name,
                            @Param("communicatedAt") Instant communicatedAt,
                            @Param("internalParticipants") String internalParticipants,
                            @Param("communicationMethod") String communicationMethod, @Param("content") String content,
                            @Param("status") String status, @Param("customFields") String customFields,
                            @Param("version") long version, @Param("operator") String operator);

    @Delete("DELETE FROM mdm.communication_record WHERE id=#{id} AND version=#{version}")
    int deleteCommunication(@Param("id") UUID id, @Param("version") long version);
}
