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
        "WHERE cr.deleted = false AND EXISTS (SELECT 1 FROM mdm.business_partner p WHERE p.id=cr.partner_id AND p.deleted=false " + BusinessPartnerMapper.SCOPE_CLAUSE + ") <if test='partnerId != null'> AND cr.partner_id=#{partnerId}</if>" +
        "<if test='status != null'> AND cr.status=#{status}</if> " +
        "ORDER BY cr.communicated_at DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<CommunicationRow> findPage(@Param("partnerId") UUID partnerId, @Param("status") String status,
                                    @Param("offset") int offset, @Param("size") int size,
                                    @Param("organizationId") UUID organizationId, @Param("userId") UUID userId,
                                    @Param("scopeType") String scopeType, @Param("targetIds") List<UUID> targetIds);

    @Select("<script>SELECT count(*) FROM mdm.communication_record cr WHERE cr.deleted = false AND EXISTS (SELECT 1 FROM mdm.business_partner p WHERE p.id=cr.partner_id AND p.deleted=false " + BusinessPartnerMapper.SCOPE_CLAUSE + ") " +
        "<if test='partnerId != null'> AND cr.partner_id=#{partnerId}</if>" +
        "<if test='status != null'> AND cr.status=#{status}</if></script>")
    long count(@Param("partnerId") UUID partnerId, @Param("status") String status,
               @Param("organizationId") UUID organizationId, @Param("userId") UUID userId,
               @Param("scopeType") String scopeType, @Param("targetIds") List<UUID> targetIds);

    @Select(COLS + "WHERE cr.id=#{id} AND cr.deleted = false")
    Optional<CommunicationRow> findById(@Param("id") UUID id);

    @Insert("INSERT INTO mdm.communication_record(id,record_code,name,partner_id,communicated_at,internal_participants," +
        "communication_method,content,status,custom_fields,version,created_at,updated_at,created_by,updated_by) " +
        "VALUES(#{id},#{recordCode},#{name},#{partnerId},#{communicatedAt},#{internalParticipants},#{communicationMethod}," +
        "#{content},#{status},CAST(#{customFields} AS jsonb),0,#{now},#{now},CAST(#{operator} AS uuid),CAST(#{operator} AS uuid))")
    void insertCommunication(@Param("id") UUID id, @Param("recordCode") String recordCode, @Param("name") String name,
                             @Param("partnerId") UUID partnerId, @Param("communicatedAt") Instant communicatedAt,
                             @Param("internalParticipants") String internalParticipants,
                             @Param("communicationMethod") String communicationMethod, @Param("content") String content,
                             @Param("status") String status, @Param("customFields") String customFields,
                             @Param("now") Instant now, @Param("operator") String operator);

    @Update("UPDATE mdm.communication_record SET name=#{name},communicated_at=#{communicatedAt},internal_participants=#{internalParticipants}," +
        "communication_method=#{communicationMethod},content=#{content},status=#{status},custom_fields=CAST(#{customFields} AS jsonb)," +
        "version=version+1,updated_at=now(),updated_by=CAST(#{operator} AS uuid) WHERE id=#{id} AND version=#{version}")
    int updateCommunication(@Param("id") UUID id, @Param("name") String name,
                            @Param("communicatedAt") Instant communicatedAt,
                            @Param("internalParticipants") String internalParticipants,
                            @Param("communicationMethod") String communicationMethod, @Param("content") String content,
                            @Param("status") String status, @Param("customFields") String customFields,
                            @Param("version") long version, @Param("operator") String operator);

    @Update("UPDATE mdm.communication_record SET deleted=true, deleted_at=now(), deleted_by=#{operator}, " +
        "version=version+1, updated_at=now(), updated_by=CAST(#{operator} AS uuid) WHERE id=#{id} AND version=#{version} AND deleted=false")
    int deleteCommunication(@Param("id") UUID id, @Param("version") long version, @Param("operator") String operator);
}
