package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.mdm.api.AgentProjectFacade;
import com.jsd.aird.rnd.api.ResearchExperimentFacade;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ResearchActivityService {
    private final JdbcTemplate jdbc;
    private final AgentProjectFacade projects;
    private final AgentSourceAuthorization sources;
    private final ObjectMapper json;
    private final ResearchExperimentFacade experiments;
    public ResearchActivityService(JdbcTemplate jdbc,AgentProjectFacade projects,AgentSourceAuthorization sources,ObjectMapper json,ResearchExperimentFacade experiments) {
        this.jdbc=jdbc;this.projects=projects;this.sources=sources;this.json=json;this.experiments=experiments;
    }
    public void requireProject(UUID project) {
        if(project!=null)projects.read("get_project_snapshot",json.createObjectNode().put("project_id",project.toString()));
    }
    public void bind(String kind,UUID id,ObjectNode args) {
        var actor=ActorContext.required();
        var saved=context(kind,id);
        UUID project=args.hasNonNull("project_id")?UUID.fromString(args.path("project_id").asText()):
            !saved.isEmpty()?(saved.hasNonNull("projectId")?UUID.fromString(saved.path("projectId").asText()):null):sourceProject(kind,id);
        requireProject(project);
        var refs=args.has("source_refs")?args.path("source_refs"):json.createArrayNode();
        if(!refs.isArray()||refs.size()>100)throw new ApiException(ApiErrorCode.BAD_REQUEST);
        sources.authorize(refs);
        var basis=researchBasis(args,refs);
        if(!saved.isEmpty()) {
            if(!Objects.equals(project==null?null:project.toString(),saved.path("projectId").asText(null))||!refs.equals(saved.path("sourceRefs"))||!basis.equals(saved.path("researchBasis")))
                throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            return;
        }
        jdbc.update("INSERT INTO ai.research_activity_context(organization_id,resource_kind,resource_id,project_id,source_refs_jsonb,research_basis_jsonb,created_by) VALUES(?,?,?,?,?::jsonb,?::jsonb,?) ON CONFLICT DO NOTHING",
            actor.organizationId(),kind,id,project,refs.toString(),basis.toString(),actor.userId());
    }
    public void check(ObjectNode args) {
        requireProject(args.hasNonNull("project_id")?UUID.fromString(args.path("project_id").asText()):null);
        var refs=args.has("source_refs")?args.path("source_refs"):json.createArrayNode();sources.authorize(refs);
        researchBasis(args,refs);
    }
    private JsonNode researchBasis(ObjectNode args,JsonNode refs) {
        var basis=args.has("research_basis")?args.path("research_basis"):json.createArrayNode();
        if(!basis.isArray()||basis.size()>50||basis.toString().length()>60000)throw new ApiException(ApiErrorCode.BAD_REQUEST,"研究依据格式无效");
        for(var item:basis) {
            boolean linked=false;for(var source:refs)if(source.equals(item.path("source_ref")))linked=true;
            if(!linked)throw new ApiException(ApiErrorCode.BAD_REQUEST,"研究依据须关联已读取的出处");
            for(var key:List.of("variables","metrics","conditions","pending_measurements")) {
                if(!item.path(key).isArray()||item.path(key).size()>30)throw new ApiException(ApiErrorCode.BAD_REQUEST,"请列明研究依据对应的变量、条件、指标与待实测事项");
                for(var value:item.path(key))if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>500)throw new ApiException(ApiErrorCode.BAD_REQUEST,"研究依据条目无效");
            }
            for(var key:List.of("conclusion","applicability","project_differences")) {
                if(!item.path(key).isTextual()||item.path(key).asText().isBlank()||item.path(key).asText().length()>4000)throw new ApiException(ApiErrorCode.BAD_REQUEST,"请说明研究结论、适用条件和本项目差异");
                ResearchWording.requireBusinessCopy(item.path(key).asText());
            }
        }
        return basis;
    }
    public ObjectNode authorizedContext(String kind,UUID id) {
        var out=context(kind,id);
        if(!out.hasNonNull("projectId")) {
            var inferred=sourceProject(kind,id);
            if(inferred!=null)out.put("projectId",inferred.toString()).put("projectRelationBasis","SOURCE_EXPERIMENTS_CURRENT_PROJECT");
        }
        requireProject(out.hasNonNull("projectId")?UUID.fromString(out.path("projectId").asText()):null);
        if(out.path("sourceRefs").isArray())sources.authorize(out.path("sourceRefs"));return out;
    }
    public ObjectNode context(String kind,UUID id) {
        var actor=ActorContext.required();
        return jdbc.query("SELECT project_id,source_refs_jsonb::text,research_basis_jsonb::text FROM ai.research_activity_context WHERE organization_id=? AND resource_kind=? AND resource_id=?",
            (rs,n)->{var out=json.createObjectNode();var project=rs.getObject(1,UUID.class);if(project!=null)out.put("projectId",project.toString());
                out.set("sourceRefs",read(rs.getString(2)));out.set("researchBasis",read(rs.getString(3)));return out;},actor.organizationId(),kind,id).stream().findFirst().orElseGet(json::createObjectNode);
    }
    public JsonNode projectActivities(UUID project) {
        requireProject(project);var actor=ActorContext.required();var out=json.createArrayNode();
        for(int offset=0;out.size()<50;offset+=100) {
            var page=jdbc.queryForList("SELECT resource_kind,resource_id,created_at FROM ai.research_activity_context WHERE organization_id=? AND created_by=? AND (?::uuid IS NULL OR project_id=? OR project_id IS NULL) ORDER BY created_at DESC,resource_id LIMIT 100 OFFSET ?",
                actor.organizationId(),actor.userId(),project,project,offset);
            for(var row:page) {
                var kind=row.get("resource_kind").toString();var id=(UUID)row.get("resource_id");
                try {
                    var context=authorizedContext(kind,id);
                    if(project!=null&&!project.toString().equals(context.path("projectId").asText()))continue;
                    out.addObject().put("kind",kind).put("runId",id.toString()).put("createdAt",((java.sql.Timestamp)row.get("created_at")).toInstant().toString());
                } catch(ApiException denied) {
                    if(!Set.of(ApiErrorCode.NOT_FOUND,ApiErrorCode.DATA_SCOPE_DENIED,ApiErrorCode.PERMISSION_DENIED).contains(denied.errorCode()))throw denied;
                }
                if(out.size()==50)break;
            }
            if(page.size()<100)break;
        }
        return out;
    }
    private UUID sourceProject(String kind,UUID id) {
        if(!"MODEL_TRAINING".equals(kind))return null;
        var actor=ActorContext.required();
        var requests=jdbc.queryForList("SELECT request_jsonb::text FROM ai.modeling_workbench_run WHERE organization_id=? AND created_by=? AND id=?",String.class,actor.organizationId(),actor.userId(),id);
        if(requests.isEmpty())return null;
        var rows=read(requests.getFirst()).path("rows");
        UUID common=null;var seen=new HashSet<String>();
        for(var row:rows) {
            if(!row.hasNonNull("sourceExperimentId")||!row.hasNonNull("versionId"))return null;
            var identity=row.path("sourceExperimentId").asText()+":"+row.path("versionId").asText();
            if(!seen.add(identity))continue;
            var record=experiments.record(actor.organizationId(),UUID.fromString(row.path("sourceExperimentId").asText()),UUID.fromString(row.path("versionId").asText()));
            if(!record.hasNonNull("projectId"))return null;
            var project=UUID.fromString(record.path("projectId").asText());
            if(common!=null&&!common.equals(project))return null;
            common=project;
        }
        return common;
    }
    private JsonNode read(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
