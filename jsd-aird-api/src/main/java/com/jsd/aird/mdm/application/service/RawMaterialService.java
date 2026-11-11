package com.jsd.aird.mdm.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.mdm.api.RawMaterialFacade;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.jsd.aird.shared.business.BusinessViews.*;

/** Append-only chemical facts, pinned to exact source lines, alongside existing material codes. */
@Service
public class RawMaterialService implements RawMaterialFacade {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonCanonicalizer canonical;
    private final AuthorizationService authorization;
    public RawMaterialService(JdbcTemplate jdbc,ObjectMapper json,JsonCanonicalizer canonical,AuthorizationService authorization) {
        this.jdbc=jdbc;this.json=json;this.canonical=canonical;this.authorization=authorization;
    }
    public void require(UUID id,boolean write) {
        var actor=ActorContext.required();authorization.require(new PermissionCheck(actor.organizationId(),actor.userId(),
            write?"ai.modeling.manage":"ai.modeling.read","AI_MODELING",id,write?"WRITE":"READ"));
    }
    public ObjectNode list(String query,int offset,int limit) {
        require(null,false);var actor=ActorContext.required();var out=json.createObjectNode();var items=out.putArray("items");
        if(offset<0||limit<1||limit>100)throw invalid("分页范围无效");
        var ids=jdbc.queryForList("""
            SELECT p.material_id FROM mdm.material_profile p JOIN mdm.material m ON m.id=p.material_id
            JOIN mdm.material_profile_version v ON v.organization_id=p.organization_id AND v.id=p.current_version_id
            WHERE p.organization_id=? AND m.status<>'RETIRED' AND (m.code ILIKE ? OR v.facts_jsonb::text ILIKE ?)
            ORDER BY m.code LIMIT ? OFFSET ?
            """,UUID.class,actor.organizationId(),"%"+query+"%","%"+query+"%",limit+1,offset);
        for(var id:ids.stream().limit(limit).toList()) {
            if(!allowed(id))continue;var full=profile(id,null);var f=full.path("facts");
            items.addObject().put("id",id.toString()).put("code",full.path("code").asText()).put("grade",f.path("grade").asText())
                .put("manufacturer",f.path("manufacturer").asText()).put("category",f.path("category").asText())
                .put("versionId",full.path("versionId").asText()).set("roles",f.path("roles"));
        }
        if(ids.size()>limit)out.put("nextOffset",offset+limit);return out;
    }
    private boolean allowed(UUID id) {
        var actor=ActorContext.required();return authorization.check(new PermissionCheck(actor.organizationId(),actor.userId(),"ai.modeling.read","AI_MODELING",id,"READ")).allowed();
    }
    public ObjectNode profile(UUID id,UUID version) {
        require(id,false);var org=ActorContext.required().organizationId();
        return jdbc.query("""
            SELECT m.code,v.id,v.version_no,v.facts_jsonb::text,v.content_hash,v.created_at
            FROM mdm.material_profile p JOIN mdm.material m ON m.id=p.material_id
            JOIN mdm.material_profile_version v ON v.organization_id=p.organization_id AND v.material_id=p.material_id
            WHERE p.organization_id=? AND p.material_id=? AND v.id=COALESCE(?::uuid,p.current_version_id) AND m.status<>'RETIRED'
            """,(rs,n)->{var out=json.createObjectNode().put("id",id.toString()).put("code",rs.getString(1)).put("versionId",rs.getString(2))
                .put("versionNo",rs.getInt(3)).put("contentHash",rs.getString(5)).put("createdAt",rs.getTimestamp(6).toInstant().toString());
                out.set("facts",parse(rs.getString(4)));return out;},org,id,version).stream().findFirst().orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
    }
    public ObjectNode source(UUID id) {
        // Source access follows a linked material's current policy; unbound sources require catalog management.
        var org=ActorContext.required().organizationId();
        var linked=jdbc.queryForList("""
            SELECT DISTINCT material_id FROM mdm.material_profile_version
            WHERE organization_id=? AND EXISTS (
                SELECT 1 FROM (
                    SELECT jsonb_path_query(facts_jsonb,'$.identitySources[*]') AS ref
                    UNION ALL SELECT jsonb_path_query(facts_jsonb,'$.properties[*].sources[*]')
                    UNION ALL SELECT jsonb_path_query(facts_jsonb,'$.knownComponents[*].sources[*]')
                    UNION ALL SELECT jsonb_path_query(facts_jsonb,'$.functionalGroups[*].sources[*]')
                    UNION ALL SELECT jsonb_path_query(facts_jsonb,'$.mechanisms[*].sources[*]')
                ) refs WHERE ref->>'documentId'=?
            )
            """,UUID.class,org,id.toString());
        if(linked.stream().noneMatch(this::allowed))require(id,true);
        return sourceInternal(org,id);
    }
    private ObjectNode sourceInternal(UUID org,UUID id) {
        return jdbc.query("SELECT * FROM mdm.material_source_document WHERE organization_id=? AND id=?",(rs,n)->{
            var out=json.createObjectNode().put("id",id.toString()).put("documentCode",rs.getString("document_code")).put("versionNo",rs.getInt("version_no"))
                .put("title",rs.getString("title")).put("fileName",rs.getString("file_name")).put("sourceKind",rs.getString("source_kind"))
                .put("contentHash",rs.getString("content_hash")).put("contentText",rs.getString("content_text"));
            var lines=out.putArray("lines");String[] text=rs.getString("content_text").split("\n",-1);
            for(int i=0;i<text.length;i++)lines.addObject().put("line",i+1).put("text",text[i]);return out;},org,id).stream().findFirst().orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
    }
    @Transactional public ObjectNode saveSource(ObjectNode body) {
        require(null,true);var actor=ActorContext.required();
        var code=text(body,"documentCode",120);var title=text(body,"title",300);var file=text(body,"fileName",300);
        var kind=text(body,"sourceKind",40);var content=text(body,"contentText",250000).replace("\r\n","\n");int version=body.path("versionNo").asInt();
        if(version<1||!Set.of("SUPPLIER_DOCUMENT","INTERNAL_REFERENCE","GENERATED_REFERENCE").contains(kind))throw invalid("来源类型或版本无效");
        var hash=canonical.hash(json.createObjectNode().put("title",title).put("fileName",file).put("sourceKind",kind).put("contentText",content));
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,39))",actor.organizationId()+":source:"+code);
        var old=jdbc.queryForList("SELECT id FROM mdm.material_source_document WHERE organization_id=? AND document_code=? AND version_no=?",UUID.class,actor.organizationId(),code,version);
        if(!old.isEmpty()) {
            var saved=sourceInternal(actor.organizationId(),old.getFirst());if(!hash.equals(saved.path("contentHash").asText()))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);return saved;
        }
        var id=UUID.randomUUID();jdbc.update("INSERT INTO mdm.material_source_document(id,organization_id,document_code,version_no,title,file_name,source_kind,content_text,content_hash,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)",
            id,actor.organizationId(),code,version,title,file,kind,content,hash,actor.userId());return sourceInternal(actor.organizationId(),id);
    }
    @Transactional public ObjectNode save(UUID material,ObjectNode body,String key) {
        require(material,true);var actor=ActorContext.required();
        if(key==null||key.isBlank()||key.length()>200)throw invalid("保存请求缺少操作身份");
        var facts=body.path("facts");if(!facts.isObject()||facts.toString().length()>150000)throw invalid("请提供原料档案内容");
        var intent=canonical.hash(json.createObjectNode().put("materialId",material.toString()).set("body",body));
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,39))",actor.organizationId()+":profile:"+material);
        var existing=jdbc.queryForList("SELECT id,material_id,content_hash FROM mdm.material_profile_version WHERE organization_id=? AND created_by=? AND idempotency_key=?",actor.organizationId(),actor.userId(),key);
        if(!existing.isEmpty()) {
            var old=existing.getFirst();if(!material.equals(old.get("material_id"))||!intent.equals(old.get("content_hash")))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            return profile(material,(UUID)old.get("id"));
        }
        if(jdbc.queryForObject("SELECT count(*) FROM mdm.material WHERE id=? AND status<>'RETIRED'",Integer.class,material)==0)throw new ApiException(ApiErrorCode.NOT_FOUND);
        var current=jdbc.queryForList("SELECT current_version_id FROM mdm.material_profile WHERE organization_id=? AND material_id=?",UUID.class,actor.organizationId(),material);
        var expected=optionalId(body,"expectedVersionId");
        if(!Objects.equals(current.isEmpty()?null:current.getFirst(),expected))throw new ApiException(ApiErrorCode.VERSION_CONFLICT,"原料档案已变化，请读取当前版本");
        validateFacts(facts);
        var id=UUID.randomUUID();int version=jdbc.queryForObject("SELECT COALESCE(max(version_no),0)+1 FROM mdm.material_profile_version WHERE organization_id=? AND material_id=?",Integer.class,actor.organizationId(),material);
        jdbc.update("INSERT INTO mdm.material_profile_version(id,organization_id,material_id,version_no,facts_jsonb,content_hash,idempotency_key,created_by) VALUES(?,?,?,?,?::jsonb,?,?,?)",
            id,actor.organizationId(),material,version,facts.toString(),intent,key,actor.userId());
        jdbc.update("INSERT INTO mdm.material_profile(organization_id,material_id,current_version_id) VALUES(?,?,?) ON CONFLICT(organization_id,material_id) DO UPDATE SET current_version_id=EXCLUDED.current_version_id,updated_at=now()",actor.organizationId(),material,id);
        return profile(material,id);
    }
    private void validateFacts(JsonNode facts) {
        text(facts,"grade",200);text(facts,"manufacturer",200);text(facts,"category",100);
        for(var key:List.of("suppliers","roles")) {
            if(!facts.path(key).isArray()||facts.path(key).isEmpty()||facts.path(key).size()>30)throw invalid("请填写供应商与配方作用");
            for(var value:facts.path(key))if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>300)throw invalid("原料身份描述无效");
        }
        checkSources(facts.path("identitySources"));
        for(var key:List.of("properties","knownComponents","functionalGroups","mechanisms")) {
            var entries=facts.path(key);if(!entries.isArray()||entries.size()>100)throw invalid("原料属性分组无效："+key);
            for(var fact:entries) {
                text(fact,"name",300);checkSources(fact.path("sources"));
                if("properties".equals(key)||"knownComponents".equals(key)) {
                    text(fact,"unit",80);if(!fact.path("conditions").isObject())throw invalid("请明确属性适用条件");
                    if(fact.has("minimum")||fact.has("maximum")) {
                        if(!finite(fact.path("minimum"))||!finite(fact.path("maximum"))||fact.path("minimum").asDouble()>fact.path("maximum").asDouble())throw invalid("属性范围无效");
                    } else if(!fact.hasNonNull("value")||!fact.path("value").isValueNode())throw invalid("请填写属性值或范围");
                }
                if("knownComponents".equals(key)&&(!"wt%".equals(fact.path("unit").asText())||fact.path("minimum").asDouble(-1)<0||fact.path("maximum").asDouble(101)>100))throw invalid("组分含量需为零至一百的质量百分数范围");
                if("mechanisms".equals(key)&&!Set.of("REPORTED","INFERRED").contains(fact.path("basis").asText()))throw invalid("机理说明需区分资料陈述与推断");
            }
        }
    }
    private boolean finite(JsonNode value){return value.isNumber()&&Double.isFinite(value.asDouble());}
    private void checkSources(JsonNode refs) {
        if(!refs.isArray()||refs.isEmpty()||refs.size()>10)throw invalid("每条信息都需要具体来源位置");
        for(var ref:refs) {
            var source=sourceInternal(ActorContext.required().organizationId(),uuid(ref,"documentId"));
            if(ref.path("versionNo").asInt()!=source.path("versionNo").asInt())throw invalid("来源版本不一致");
            int start=ref.path("lineStart").asInt(),end=ref.path("lineEnd").asInt();var lines=source.path("lines");
            if(start<1||end<start||end>lines.size()||end-start>100)throw invalid("来源行号无效");
            var excerpt=new StringBuilder();for(int i=start-1;i<end;i++){if(i>=start)excerpt.append('\n');excerpt.append(lines.path(i).path("text").asText());}
            if(!excerpt.toString().equals(ref.path("excerpt").asText()))throw invalid("来源摘录与原文位置不一致");
        }
    }
    @Override public JsonNode snapshot(JsonNode columns) {
        var out=json.createArrayNode();var seen=new HashSet<UUID>();
        for(var column:columns)if(column.hasNonNull("materialId")) {
            var id=uuid(column,"materialId");if(!seen.add(id))continue;
            if(jdbc.queryForObject("SELECT count(*) FROM mdm.material_profile WHERE organization_id=? AND material_id=?",Integer.class,ActorContext.required().organizationId(),id)==0)continue;
            out.add(profile(id,null));
        }
        return out;
    }
    @Override public void authorizeSnapshot(JsonNode snapshot) {
        for(var saved:snapshot) {var actual=profile(uuid(saved,"id"),uuid(saved,"versionId"));if(!actual.equals(saved))throw new ApiException(ApiErrorCode.VERSION_CONFLICT);}
    }
    private String text(JsonNode node,String key,int max) {var value=node.path(key);if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>max)throw invalid("请填写有效内容："+key);return value.asText();}
    private JsonNode parse(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private ApiException invalid(String value){return new ApiException(ApiErrorCode.VALIDATION_ERROR,value);}
}
