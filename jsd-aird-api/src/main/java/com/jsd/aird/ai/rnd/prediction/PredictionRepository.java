package com.jsd.aird.ai.rnd.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.modeling.WorkbenchFormulaSupport;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PredictionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PredictionRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public JdbcTemplate jdbc() { return jdbc; }

    public List<TargetBinding> targetBindings(UUID org,List<UUID> targetIds){
        if(targetIds.isEmpty())return List.of();
        var marks=String.join(",",java.util.Collections.nCopies(targetIds.size(),"?"));
        var args=new ArrayList<Object>();args.add(org);args.addAll(targetIds);
        return jdbc.query("""
            SELECT t.id target_id,t.target_code,t.name,t.performance_project,t.status target_status,
                tv.id target_version_id,tv.version_no,tv.value_type,tv.unit,tv.classes_jsonb,tv.definition_jsonb,
                tv.observation_semantics_jsonb,tv.config_hash target_hash,mv.id model_id,mv.target_version_id model_target_version_id,
                mv.input_scheme_id,mv.contract_version,mv.metrics_jsonb,mv.applicability_domain_jsonb,
                mv.model_card_jsonb->'targetDefinition' frozen_target,mv.model_card_jsonb->'inputScheme' input_scheme,
                wr.request_jsonb,wr.id workbench_run_id,a.object_key,a.sha256 artifact_sha,a.media_type
            FROM ai.prediction_target t
            JOIN ai.target_version tv ON tv.organization_id=t.organization_id AND tv.id=t.current_version_id
            LEFT JOIN ai.model_version mv ON mv.organization_id=t.organization_id AND mv.target_id=t.id
                AND mv.status='ACTIVE' AND mv.contract_version='modeling-workbench.v1' AND mv.workbench_run_id IS NOT NULL
            LEFT JOIN ai.modeling_workbench_run wr ON wr.organization_id=mv.organization_id AND wr.id=mv.workbench_run_id
            LEFT JOIN ai.artifact a ON a.organization_id=mv.organization_id AND a.id=mv.model_artifact_id
            WHERE t.organization_id=? AND t.id IN (%s)
                AND EXISTS(SELECT 1 FROM ai.model_version registered WHERE registered.organization_id=t.organization_id
                    AND registered.target_id=t.id AND registered.contract_version='modeling-workbench.v1' AND registered.workbench_run_id IS NOT NULL)
            ORDER BY t.name,t.id
            """.formatted(marks),(rs,n)->{
                var scheme=node(rs,"input_scheme");var definition=node(rs,"definition_jsonb");
                var request=node(rs,"request_jsonb");
                if(definition instanceof ObjectNode object && request.isObject())object.withObject("modelSource").set("yColumn",request.path("yColumn"));
                return new TargetBinding(uuid(rs,"target_id"),rs.getString("target_code"),rs.getString("name"),rs.getString("performance_project"),
                    rs.getString("target_status"),rs.getString("value_type"),uuid(rs,"target_version_id"),rs.getInt("version_no"),rs.getString("unit"),
                    node(rs,"classes_jsonb"),definition,node(rs,"observation_semantics_jsonb"),rs.getString("target_hash"),uuid(rs,"model_id"),
                    uuid(rs,"model_target_version_id"),uuid(rs,"input_scheme_id"),rs.getString("contract_version"),node(rs,"metrics_jsonb"),
                    node(rs,"applicability_domain_jsonb"),rs.getString("object_key"),rs.getString("artifact_sha"),rs.getString("media_type"),
                    node(rs,"frozen_target"),scheme,WorkbenchFormulaSupport.dictionary(json,scheme,request.path("rows")),uuid(rs,"workbench_run_id"));
            },args.toArray());
    }
    public List<TargetCatalog> targetCatalog(UUID org){
        return jdbc.query("""
            SELECT t.id,t.target_code,t.name,t.performance_project,t.value_type,t.status,mv.id model_id,
                tv.unit,tv.definition_jsonb->>'testMethod' test_method,tv.definition_jsonb->>'testStage' test_stage
            FROM ai.prediction_target t JOIN ai.target_version tv ON tv.organization_id=t.organization_id AND tv.id=t.current_version_id
            LEFT JOIN ai.model_version mv ON mv.organization_id=t.organization_id AND mv.target_id=t.id
                AND mv.status='ACTIVE' AND mv.contract_version='modeling-workbench.v1' AND mv.workbench_run_id IS NOT NULL
            WHERE t.organization_id=? AND EXISTS(SELECT 1 FROM ai.model_version registered WHERE registered.organization_id=t.organization_id
                AND registered.target_id=t.id AND registered.contract_version='modeling-workbench.v1' AND registered.workbench_run_id IS NOT NULL)
            ORDER BY t.name,t.id
            """,(rs,n)->new TargetCatalog(uuid(rs,"id"),rs.getString("target_code"),rs.getString("name"),rs.getString("performance_project"),
                rs.getString("value_type"),rs.getString("status"),uuid(rs,"model_id"),rs.getString("unit"),rs.getString("test_method"),rs.getString("test_stage")),org);
    }
    public boolean insertRunning(UUID id, UUID org, UUID user, String key, String requestId,
                                 String requestHash, JsonNode request, JsonNode bindings) {
        return jdbc.update("""
            INSERT INTO ai.prediction_record(id,organization_id,prediction_type,idempotency_key,request_hash,
                request_jsonb,target_bindings_jsonb,result_jsonb,created_by,request_id,contract_version,
                execution_status,target_results_jsonb,heartbeat_at)
            VALUES(?,?, 'PERFORMANCE',?,?,?,?, '{}'::jsonb,?,?,'ai-rnd.v1','RUNNING','[]'::jsonb,now())
            ON CONFLICT(organization_id,created_by,prediction_type,idempotency_key) DO NOTHING
            """,id,org,key,requestHash,pg(request),pg(bindings),user,requestId)==1;
    }

    public Optional<PredictionRow> byKey(UUID org,UUID user,String key) {
        return jdbc.query("""
            SELECT * FROM ai.prediction_record
            WHERE organization_id=? AND created_by=? AND prediction_type='PERFORMANCE' AND idempotency_key=?
            """,this::prediction,org,user,key).stream().findFirst();
    }

    public Optional<PredictionRow> byId(UUID org,UUID user,UUID id) {
        return jdbc.query("SELECT * FROM ai.prediction_record WHERE organization_id=? AND created_by=? AND id=? AND prediction_type='PERFORMANCE'",
                this::prediction,org,user,id).stream().findFirst();
    }

    public boolean complete(UUID org,UUID id,long expectedRevision,String execution,String outcome,int http,
                            JsonNode results,JsonNode response,long durationMs,JsonNode error) {
        return jdbc.update("""
            UPDATE ai.prediction_record SET execution_status=?,outcome_status=?,target_results_jsonb=?,
                terminal_http_status=?,terminal_response_jsonb=?,result_jsonb=?,error_jsonb=?,duration_ms=?,
                heartbeat_at=now(),completed_at=now(),updated_at=now(),revision=revision+1
            WHERE organization_id=? AND id=? AND execution_status='RUNNING' AND revision=?
            """,execution,outcome,pg(results),http,pg(response),pg(response),error==null?null:pg(error),durationMs,org,id,expectedRevision)==1;
    }

    public int failStale(Instant before) {
        var response=json.createObjectNode().put("code","PREDICTION_TIMEOUT")
                .put("message","性能预测执行超时").put("requestId","reconciler");
        response.set("detail",json.createObjectNode().put("retryWithNewIdempotencyKey",true));
        return jdbc.update("""
            UPDATE ai.prediction_record SET execution_status='FAILED',outcome_status=NULL,terminal_http_status=503,
                terminal_response_jsonb=?,result_jsonb=?,error_jsonb=?,duration_ms=greatest(0,(extract(epoch from(now()-created_at))*1000)::bigint),
                completed_at=now(),updated_at=now(),revision=revision+1
            WHERE execution_status='RUNNING' AND heartbeat_at<?
            """,pg(response),pg(response),pg(response),java.sql.Timestamp.from(before));
    }

    public PGobject pg(JsonNode value){try{var p=new PGobject();p.setType("jsonb");p.setValue((value==null?json.nullNode():value).toString());return p;}catch(Exception e){throw new IllegalArgumentException(e);}}
    private JsonNode node(ResultSet rs,String name)throws SQLException{var raw=rs.getString(name);try{return raw==null?json.nullNode():json.readTree(raw);}catch(Exception e){throw new SQLException(e);}}
    private UUID uuid(ResultSet rs,String name)throws SQLException{
        Object value = rs.getObject(name);
        if (value == null) return null;
        if (value instanceof UUID uuid) return uuid;
        return UUID.fromString(value.toString());
    }
    private Instant instant(ResultSet rs,String name)throws SQLException{var t=rs.getTimestamp(name);return t==null?null:t.toInstant();}

    private PredictionRow prediction(ResultSet rs,int n)throws SQLException{return new PredictionRow(uuid(rs,"id"),
            rs.getString("request_id"),rs.getString("request_hash"),rs.getString("execution_status"),
            rs.getString("outcome_status"),(Integer)rs.getObject("terminal_http_status"),node(rs,"terminal_response_jsonb"),
            node(rs,"target_results_jsonb"),rs.getLong("revision"),instant(rs,"created_at"),instant(rs,"completed_at"));}

    public record TargetCatalog(UUID id,String code,String name,String category,String valueType,String status,
        UUID modelId,String unit,String testMethod,String testStage) { }
    public record TargetBinding(UUID targetId,String targetCode,String targetName,String category,String targetStatus,
        String valueType,UUID targetVersionId,int targetVersion,String unit,JsonNode classes,JsonNode definition,
        JsonNode observationSemantics,String targetHash,UUID modelId,UUID modelTargetVersionId,UUID modelInputSchemeId,
        String contractVersion,JsonNode metrics,JsonNode modelDomain,String artifactKey,String artifactSha,String artifactMediaType,
        JsonNode frozenTarget,JsonNode frozenInputScheme,JsonNode frozenDictionary,UUID workbenchRunId) { }
    public record PredictionRow(UUID id,String requestId,String requestHash,String executionStatus,String outcomeStatus,
                                Integer terminalHttpStatus,JsonNode terminalResponse,JsonNode targetResults,long revision,
                                Instant createdAt,Instant completedAt) { }
}
