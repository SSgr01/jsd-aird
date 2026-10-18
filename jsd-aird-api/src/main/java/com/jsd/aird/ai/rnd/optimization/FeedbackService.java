package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.jsd.aird.ai.rnd.optimization.OptimizationContracts.ObservationCommand;

@Service
public class FeedbackService {
    private final JdbcTemplate jdbc;
    private final OptimizationRepository runs;
    private final ObjectMapper json;
    private final AuthorizationService authorization;

    public FeedbackService(JdbcTemplate jdbc,OptimizationRepository runs,ObjectMapper json,AuthorizationService authorization){
        this.jdbc=jdbc;this.runs=runs;this.json=json;this.authorization=authorization;
    }

    public JsonNode observations(UUID runId){
        var a=ActorContext.required();requireRunAccess(a.organizationId(),a.userId(),runId,"READ");
        var root=json.createObjectNode();var items=root.putArray("observations");

        jdbc.query("""
                SELECT research_candidate_id,field_code,observed_value_jsonb,updated_at
                FROM ai.research_candidate_history_observation_v2
                WHERE organization_id=? AND research_run_id=? ORDER BY research_candidate_id,field_code
                """,rs->{var item=items.addObject();item.put("candidateId",rs.getObject("research_candidate_id",UUID.class).toString());
                    item.put("fieldCode",rs.getString("field_code"));item.set("value",read(rs.getString("observed_value_jsonb")));
                    item.put("updatedAt",rs.getTimestamp("updated_at").toInstant().toString());},a.organizationId(),runId);
        return root;
    }

    @Transactional
    public JsonNode saveObservations(UUID runId,ObservationCommand command){
        var a=ActorContext.required();var run=requireRunAccess(a.organizationId(),a.userId(),runId,"UPDATE");
        if(!"SUCCEEDED".equals(run.executionStatus()))throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"请等待方案生成完成后再填写实测值");
        if(command==null||command.observations()==null||command.observations().isEmpty())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"请至少填写一项实测值");
        return saveHistoricalObservations(a.organizationId(),a.userId(),runId,command,run);
    }

    private JsonNode saveHistoricalObservations(UUID org,UUID user,UUID runId,ObservationCommand command,OptimizationRepository.RunRow run){
        var target=run.request().path("target").asText();
        for(var entry:command.observations()){
            if(entry==null||entry.candidateId()==null||entry.fieldCode()==null||entry.value()==null||entry.value().isNull()||entry.value().isMissingNode())
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"请填写方案实测值");
            if(!target.equals(entry.fieldCode()))throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"只能填写本轮选择的 Y 指标");
            if(!entry.value().isNumber()||!Double.isFinite(entry.value().asDouble()))throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"Y 实测值需要填写有效数字");
            if(runs.candidate(org,runId,entry.candidateId()).isEmpty())throw new ApiException(ApiErrorCode.NOT_FOUND,"方案不存在");
            jdbc.update("""
                INSERT INTO ai.research_candidate_history_observation_v2(id,organization_id,research_run_id,research_candidate_id,field_code,observed_value_jsonb,created_by,updated_by)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(organization_id,research_run_id,research_candidate_id,field_code)
                DO UPDATE SET observed_value_jsonb=EXCLUDED.observed_value_jsonb,updated_by=EXCLUDED.updated_by,updated_at=now()
                """,UUID.randomUUID(),org,runId,entry.candidateId(),entry.fieldCode(),pg(entry.value()),user,user);
        }
        var result=observations(runId);((ObjectNode)result).put("savedCount",command.observations().size());return result;
    }

    private OptimizationRepository.RunRow requireRunAccess(UUID org,UUID user,UUID runId,String action){
        authorization.require(new PermissionCheck(org,user,"ai.experiment.optimize","AI_RESEARCH_RUN",runId,action));
        return runs.byId(org,user,runId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
    }

    private JsonNode read(String v){try{return v==null?json.createObjectNode():json.readTree(v);}catch(Exception e){return json.createObjectNode();}}
    private PGobject pg(JsonNode n){try{var p=new PGobject();p.setType("jsonb");p.setValue(n==null?"{}":n.toString());return p;}catch(Exception e){throw new IllegalArgumentException(e);}}
}

