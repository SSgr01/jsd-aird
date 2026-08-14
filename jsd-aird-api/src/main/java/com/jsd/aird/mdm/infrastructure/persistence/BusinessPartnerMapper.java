package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.application.port.ContactProjectVector;
import com.jsd.aird.mdm.infrastructure.model.BusinessPartnerRow;
import com.jsd.aird.mdm.infrastructure.model.PartnerContactRow;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface BusinessPartnerMapper {
    record ContactProjectLink(UUID contactId, String projectId) {
    }

    String SELECT_COLUMNS = "SELECT p.id, p.partner_code partnerCode, p.name, p.industry, p.address, " +
        "p.status, p.remark, p.customer_level customerLevel, p.cooperation_status cooperationStatus, " +
        "p.main_business mainBusiness, p.custom_fields::text customFields, p.version, p.created_at createdAt, p.updated_at updatedAt " +
        "FROM mdm.business_partner p ";

    @Select("<script>" + SELECT_COLUMNS +
        "WHERE 1=1 <if test='keyword != null and keyword != &quot;&quot;'> AND (p.partner_code ILIKE concat('%',#{keyword},'%') OR p.name ILIKE concat('%',#{keyword},'%'))</if>" +
        "<if test='status != null'> AND p.status=#{status}</if> ORDER BY p.updated_at DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<BusinessPartnerRow> findPage(@Param("keyword") String keyword,
                                      @Param("status") String status, @Param("offset") int offset, @Param("size") int size);

    record PartnerStatsRow(UUID id, long requirementCount, long projectCount,
                           Instant latestFollowUpAt, String ownerNames) {
    }

    @Select("""
        <script>
        SELECT bp.id,
               COALESCE(req.req_count, 0) AS requirement_count,
               COALESCE(prj.project_count, 0) AS project_count,
               prj.latest_follow_up_at AS latest_follow_up_at,
               COALESCE(owners.owner_names, '') AS owner_names
        FROM mdm.business_partner bp
        LEFT JOIN (
            SELECT partner_id, COUNT(*) AS req_count
            FROM mdm.customer_requirement
            GROUP BY partner_id
        ) req ON req.partner_id = bp.id
        LEFT JOIN (
            SELECT pc.partner_id,
                   COUNT(DISTINCT pcp.project_id) AS project_count,
                   MAX(cr.communicated_at) AS latest_follow_up_at
            FROM mdm.partner_contact pc
            LEFT JOIN mdm.partner_contact_project pcp ON pcp.contact_id = pc.id
            LEFT JOIN mdm.communication_record cr ON cr.partner_id = pc.partner_id
            GROUP BY pc.partner_id
        ) prj ON prj.partner_id = bp.id
        LEFT JOIN (
            SELECT partner_id, string_agg(name, ', ') AS owner_names
            FROM mdm.partner_contact
            WHERE status = 'ACTIVE'
            GROUP BY partner_id
        ) owners ON owners.partner_id = bp.id
        WHERE bp.id IN
        <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id,jdbcType=OTHER}</foreach>
        </script>
        """)
    List<PartnerStatsRow> selectPartnerStats(@Param("ids") List<UUID> ids);

    @Select("<script>SELECT count(*) FROM mdm.business_partner p WHERE 1=1 " +
        "<if test='keyword != null and keyword != &quot;&quot;'> AND (p.partner_code ILIKE concat('%',#{keyword},'%') OR p.name ILIKE concat('%',#{keyword},'%'))</if>" +
        "<if test='status != null'> AND p.status=#{status}</if></script>")
    long count(@Param("keyword") String keyword, @Param("status") String status);

    @Select(SELECT_COLUMNS + "WHERE p.id=#{id} GROUP BY p.id")
    Optional<BusinessPartnerRow> findById(@Param("id") UUID id);

    @Select("<script>SELECT EXISTS(SELECT 1 FROM mdm.business_partner WHERE (partner_code=#{code} OR normalized_name=#{name}) <if test='excludedId != null'>AND id != #{excludedId}</if>)</script>")
    boolean exists(@Param("code") String code, @Param("name") String name, @Param("excludedId") UUID excludedId);

    @Insert("INSERT INTO mdm.business_partner(id,partner_code,name,normalized_name,industry,address,status,remark,customer_level,cooperation_status,main_business,custom_fields,version,created_at,updated_at,created_by,updated_by) VALUES(#{id},#{code},#{name},#{normalized},#{industry},#{address},#{status},#{remark},#{customerLevel},#{cooperationStatus},#{mainBusiness},CAST(#{customFields} AS jsonb),0,#{now},#{now},#{operator},#{operator})")
    void insert(@Param("id") UUID id, @Param("code") String code, @Param("name") String name,
                @Param("normalized") String normalized, @Param("industry") String industry, @Param("address") String address,
                @Param("status") String status, @Param("remark") String remark,
                @Param("customerLevel") String customerLevel, @Param("cooperationStatus") String cooperationStatus,
                @Param("mainBusiness") String mainBusiness, @Param("customFields") String customFields,
                @Param("now") Instant now, @Param("operator") String operator);

    @Update("UPDATE mdm.business_partner SET partner_code=#{code},name=#{name},normalized_name=#{normalized},industry=#{industry},address=#{address},remark=#{remark},customer_level=#{customerLevel},cooperation_status=#{cooperationStatus},main_business=#{mainBusiness},custom_fields=CAST(#{customFields} AS jsonb),version=version+1,updated_at=#{now},updated_by=#{operator} WHERE id=#{id} AND version=#{version}")
    int update(@Param("id") UUID id, @Param("code") String code, @Param("name") String name,
               @Param("normalized") String normalized, @Param("industry") String industry, @Param("address") String address,
               @Param("remark") String remark, @Param("customerLevel") String customerLevel,
               @Param("cooperationStatus") String cooperationStatus, @Param("mainBusiness") String mainBusiness,
               @Param("customFields") String customFields, @Param("version") long version,
               @Param("now") Instant now, @Param("operator") String operator);

    @Update("UPDATE mdm.business_partner SET status=#{status},version=version+1,updated_at=now(),updated_by=#{operator} WHERE id=#{id} AND version=#{version}")
    int updateStatus(@Param("id") UUID id, @Param("status") String status, @Param("version") long version, @Param("operator") String operator);

    @Select("SELECT id,partner_id partnerId,name,department,title,phone,email,status,assigned_project_ids::text assignedProjectIds,members,wechat,custom_fields::text customFields,version,created_at createdAt,updated_at updatedAt FROM mdm.partner_contact WHERE partner_id=#{partnerId} ORDER BY status,created_at")
    List<PartnerContactRow> findContacts(@Param("partnerId") UUID partnerId);

    @Insert("INSERT INTO mdm.partner_contact(id,partner_id,name,department,title,phone,email,status,assigned_project_ids,members,wechat,custom_fields,version,created_at,updated_at,created_by,updated_by) VALUES(#{id},#{partnerId},#{name},#{department},#{title},#{phone},#{email},'ACTIVE',CAST(#{assignedProjectIds} AS jsonb),#{members},#{wechat},CAST(#{customFields} AS jsonb),0,#{now},#{now},#{operator},#{operator})")
    void insertContact(@Param("id") UUID id, @Param("partnerId") UUID partnerId, @Param("name") String name,
                       @Param("department") String department, @Param("title") String title, @Param("phone") String phone,
                       @Param("email") String email, @Param("assignedProjectIds") String assignedProjectIds,
                       @Param("members") String members, @Param("wechat") String wechat, @Param("customFields") String customFields,
                       @Param("now") Instant now, @Param("operator") String operator);

    @Update("UPDATE mdm.partner_contact SET name=#{name},department=#{department},title=#{title},phone=#{phone},email=#{email},assigned_project_ids=CAST(#{assignedProjectIds} AS jsonb),members=#{members},wechat=#{wechat},custom_fields=CAST(#{customFields} AS jsonb),version=version+1,updated_at=now(),updated_by=#{operator} WHERE id=#{id} AND partner_id=#{partnerId} AND version=#{version}")
    int updateContact(@Param("id") UUID id, @Param("partnerId") UUID partnerId, @Param("name") String name,
                      @Param("department") String department, @Param("title") String title, @Param("phone") String phone,
                      @Param("email") String email, @Param("assignedProjectIds") String assignedProjectIds,
                      @Param("members") String members, @Param("wechat") String wechat, @Param("customFields") String customFields,
                      @Param("version") long version, @Param("operator") String operator);

    @Select("""
        <script>
        SELECT contact_id AS contactId, project_id::text AS projectId
        FROM mdm.partner_contact_project
        WHERE contact_id IN
        <foreach collection='contactIds' item='id' open='(' separator=',' close=')'>#{id,jdbcType=OTHER}</foreach>
        </script>
        """)
    List<ContactProjectLink> findContactProjects(@Param("contactIds") List<UUID> contactIds);

    @Delete("DELETE FROM mdm.partner_contact_project WHERE contact_id = #{contactId}")
    void deleteContactProjects(@Param("contactId") UUID contactId);

    @Select("""
        SELECT pc.partner_id AS partnerId, pc.id AS contactId, pc.name AS contactName,
               p.id AS projectId, p.project_code AS projectCode, p.name AS projectName,
               p.owner AS projectOwner, p.status AS projectStatus,
               (SELECT s.name FROM mdm.project_stage s
                WHERE s.project_id = p.id AND s.deleted = false
                ORDER BY s.order_no DESC LIMIT 1) AS currentStageName,
               (SELECT coalesce((count(*) FILTER (WHERE status = 'COMPLETED'))::float
                                / nullif(count(*), 0) * 100, 0)
                FROM mdm.project_stage WHERE project_id = p.id AND deleted = false) AS progress
        FROM mdm.partner_contact pc
        JOIN mdm.partner_contact_project pcp ON pcp.contact_id = pc.id
        JOIN mdm.project p ON p.id = pcp.project_id AND p.deleted = false
        WHERE pc.partner_id = #{partnerId} AND pc.status = 'ACTIVE'
        ORDER BY pc.created_at, p.created_at
        """)
    List<com.jsd.aird.mdm.application.port.ContactProjectVector> findContactProjectVectors(@Param("partnerId") UUID partnerId);

    @Insert("""
        INSERT INTO mdm.partner_contact_project (id, contact_id, project_id, created_at, updated_at, created_by, updated_by)
        VALUES (gen_random_uuid(), #{contactId}, #{projectId}, now(), now(), 'system', 'system')
        ON CONFLICT DO NOTHING
        """)
    void insertContactProject(@Param("contactId") UUID contactId, @Param("projectId") UUID projectId);

    @Update("UPDATE mdm.partner_contact SET status=#{status},version=version+1,updated_at=now(),updated_by=#{operator} WHERE id=#{id} AND partner_id=#{partnerId} AND version=#{version}")
    int updateContactStatus(@Param("partnerId") UUID partnerId, @Param("id") UUID id, @Param("status") String status,
                            @Param("version") long version, @Param("operator") String operator);

    @Delete("DELETE FROM mdm.partner_contact WHERE partner_id=#{partnerId}")
    void deleteContacts(@Param("partnerId") UUID partnerId);

    @Update("UPDATE mdm.business_partner target SET customer_level=source.customer_level,cooperation_status=source.cooperation_status,main_business=source.main_business,custom_fields=source.custom_fields FROM mdm.business_partner source WHERE target.id=#{newId} AND source.id=#{sourceId}")
    void copyPartnerExtensions(@Param("sourceId") UUID sourceId, @Param("newId") UUID newId);

    @Update("WITH source_rows AS (SELECT custom_fields,row_number() OVER (ORDER BY created_at,id) rn FROM mdm.partner_contact WHERE partner_id=#{sourceId}), target_rows AS (SELECT id,row_number() OVER (ORDER BY created_at,id) rn FROM mdm.partner_contact WHERE partner_id=#{newId}) UPDATE mdm.partner_contact target SET custom_fields=source.custom_fields FROM source_rows source JOIN target_rows mapped ON mapped.rn=source.rn WHERE target.id=mapped.id")
    void copyContactExtensions(@Param("sourceId") UUID sourceId, @Param("newId") UUID newId);

    @Insert("INSERT INTO ops.audit_log(id,object_type,object_id,action,operator,detail,created_at) VALUES(#{id},'BUSINESS_PARTNER',#{objectId},#{action},#{operator},#{detail},now())")
    void audit(@Param("id") UUID id, @Param("objectId") UUID objectId, @Param("action") String action,
               @Param("detail") String detail, @Param("operator") String operator);
}
