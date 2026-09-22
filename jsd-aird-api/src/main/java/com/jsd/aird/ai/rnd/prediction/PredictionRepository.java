package com.jsd.aird.ai.rnd.prediction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PredictionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PredictionRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public JdbcTemplate jdbc() { return jdbc; }

    public List<PredictionContracts.QualityPolicyView> qualityPolicies(UUID org, UUID targetId) {
        return jdbc.query("""
            SELECT * FROM ai.modeling_policy_version
            WHERE organization_id=? AND target_id=? AND kind='QUALITY'
            ORDER BY version_no DESC,id
            """, this::qualityPolicy, org,targetId);
    }

    public Optional<PredictionContracts.QualityPolicyView> qualityPolicy(UUID org, UUID id) {
        return jdbc.query("SELECT * FROM ai.modeling_policy_version WHERE organization_id=? AND id=? AND kind='QUALITY'",
                this::qualityPolicy,org,id).stream().findFirst();
    }

    public List<TargetBinding> targetBindings(UUID org, List<UUID> targetIds) {
        if (targetIds.isEmpty()) return List.of();
        var marks=String.join(",",java.util.Collections.nCopies(targetIds.size(),"?"));
        var args=new java.util.ArrayList<Object>();args.add(org);args.addAll(targetIds);
        return jdbc.query("""
            SELECT t.id target_id,t.target_code,t.name,t.performance_project,t.status target_status,t.value_type,
                   t.current_version_id,t.current_input_scheme_id,t.current_quality_policy_version_id,
                   tv.version_no target_version_no,tv.unit,tv.classes_jsonb,tv.definition_jsonb,
                   tv.observation_semantics_jsonb,coalesce(tv.definition_jsonb->>'sha256',tv.config_hash) target_hash,
                   mv.id model_id,mv.target_version_id model_target_version_id,mv.input_scheme_id model_input_scheme_id,
                   mv.training_snapshot_id,mv.contract_version,mv.metrics_jsonb,mv.applicability_domain_jsonb,
                   mv.production_eligible,mv.data_nature,mv.domain_policy_version_id,
                   a.object_key,a.sha256 artifact_sha,a.media_type,
                   ts.target_definition_jsonb,ts.input_scheme_jsonb,ts.material_dictionary_jsonb,ts.preprocessing_jsonb,
                   qp.id quality_policy_id,qp.version_no quality_policy_version,qp.policy_hash quality_policy_hash,
                   qp.configuration_jsonb quality_policy_jsonb,qp.status quality_policy_status,
                   dp.id domain_policy_id,dp.version_no domain_policy_version,dp.policy_hash domain_policy_hash,
                   dp.configuration_jsonb domain_policy_jsonb,dp.status domain_policy_status
            FROM ai.prediction_target t
            LEFT JOIN ai.target_version tv ON tv.organization_id=t.organization_id AND tv.id=t.current_version_id
            LEFT JOIN ai.model_version mv ON mv.organization_id=t.organization_id AND mv.target_id=t.id AND mv.status='ACTIVE'
            LEFT JOIN ai.artifact a ON a.organization_id=mv.organization_id AND a.id=mv.model_artifact_id
            LEFT JOIN ai.training_snapshot ts ON ts.organization_id=mv.organization_id AND ts.id=mv.training_snapshot_id
            LEFT JOIN ai.modeling_policy_version qp ON qp.organization_id=t.organization_id AND qp.id=t.current_quality_policy_version_id
            LEFT JOIN ai.modeling_policy_version dp ON dp.organization_id=t.organization_id
                AND dp.id=coalesce(mv.domain_policy_version_id,t.current_domain_policy_version_id)
            WHERE t.organization_id=? AND t.id IN (%s)
            ORDER BY t.performance_project,t.name,t.id
            """.formatted(marks),this::binding,args.toArray());
    }

    public List<TargetCatalog> targetCatalog(UUID org) {
        return jdbc.query("""
            SELECT t.id,t.target_code,t.name,t.performance_project,t.value_type,t.status,
                   mv.id model_id,t.current_quality_policy_version_id,
                   coalesce(mv.domain_policy_version_id,t.current_domain_policy_version_id) domain_policy_version_id
            FROM ai.prediction_target t
            LEFT JOIN ai.model_version mv ON mv.organization_id=t.organization_id AND mv.target_id=t.id AND mv.status='ACTIVE'
            WHERE t.organization_id=? ORDER BY t.performance_project,t.name,t.id
            """,(rs,n)->new TargetCatalog(uuid(rs,"id"),rs.getString("target_code"),rs.getString("name"),
                rs.getString("performance_project"),rs.getString("value_type"),rs.getString("status"),
                uuid(rs,"model_id"),uuid(rs,"current_quality_policy_version_id"),uuid(rs,"domain_policy_version_id")),org);
    }

    public Optional<MaterialIdentity> material(String id) {
        try {
            return jdbc.query("SELECT id,code,name,category FROM mdm.material WHERE id=? AND status<>'RETIRED'",
                    (rs,n)->new MaterialIdentity(uuid(rs,"id"),rs.getString("code"),rs.getString("name"),rs.getString("category")),UUID.fromString(id)).stream().findFirst();
        } catch (IllegalArgumentException ignored) { return Optional.empty(); }
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

    private PredictionContracts.QualityPolicyView qualityPolicy(ResultSet rs,int n)throws SQLException{
        var config=node(rs,"configuration_jsonb");List<PredictionContracts.QualityRule> rules;
        try{rules=json.convertValue(config.path("rules"),new TypeReference<>(){});}catch(Exception e){rules=List.of();}
        return new PredictionContracts.QualityPolicyView(uuid(rs,"id"),uuid(rs,"target_id"),rs.getInt("version_no"),
                rs.getString("status"),rs.getString("kind"),config.path("defaultTrustLevel").asText(),
                config.path("defaultExplanation").asText(),rules,
                rs.getString("policy_hash"),rs.getLong("revision"),instant(rs,"published_at"),instant(rs,"created_at"));
    }
    private TargetBinding binding(ResultSet rs,int n)throws SQLException{return new TargetBinding(
            uuid(rs,"target_id"),rs.getString("target_code"),rs.getString("name"),rs.getString("performance_project"),
            rs.getString("target_status"),rs.getString("value_type"),uuid(rs,"current_version_id"),
            rs.getInt("target_version_no"),rs.getString("unit"),node(rs,"classes_jsonb"),node(rs,"definition_jsonb"),
            node(rs,"observation_semantics_jsonb"),rs.getString("target_hash"),uuid(rs,"model_id"),
            uuid(rs,"model_target_version_id"),uuid(rs,"model_input_scheme_id"),uuid(rs,"training_snapshot_id"),
            rs.getString("contract_version"),node(rs,"metrics_jsonb"),node(rs,"applicability_domain_jsonb"),
            rs.getBoolean("production_eligible"),rs.getString("data_nature"),rs.getString("object_key"),
            rs.getString("artifact_sha"),rs.getString("media_type"),node(rs,"target_definition_jsonb"),
            node(rs,"input_scheme_jsonb"),node(rs,"material_dictionary_jsonb"),node(rs,"preprocessing_jsonb"),
            uuid(rs,"quality_policy_id"),rs.getInt("quality_policy_version"),rs.getString("quality_policy_hash"),
            node(rs,"quality_policy_jsonb"),rs.getString("quality_policy_status"),uuid(rs,"domain_policy_id"),
            rs.getInt("domain_policy_version"),rs.getString("domain_policy_hash"),node(rs,"domain_policy_jsonb"),
            rs.getString("domain_policy_status"));}
    private PredictionRow prediction(ResultSet rs,int n)throws SQLException{return new PredictionRow(uuid(rs,"id"),
            rs.getString("request_id"),rs.getString("request_hash"),rs.getString("execution_status"),
            rs.getString("outcome_status"),(Integer)rs.getObject("terminal_http_status"),node(rs,"terminal_response_jsonb"),
            node(rs,"target_results_jsonb"),rs.getLong("revision"),instant(rs,"created_at"),instant(rs,"completed_at"));}

    public record TargetCatalog(UUID id,String code,String name,String category,String valueType,String status,
                                UUID modelId,UUID qualityPolicyId,UUID domainPolicyId) { }
    public record MaterialIdentity(UUID id,String code,String name,String category) { }
    public record TargetBinding(UUID targetId,String targetCode,String targetName,String category,String targetStatus,
        String valueType,UUID targetVersionId,int targetVersion,String unit,JsonNode classes,JsonNode definition,
        JsonNode observationSemantics,String targetHash,UUID modelId,UUID modelTargetVersionId,UUID modelInputSchemeId,
        UUID snapshotId,String contractVersion,JsonNode metrics,JsonNode modelDomain,boolean productionEligible,
        String dataNature,String artifactKey,String artifactSha,String artifactMediaType,JsonNode frozenTarget,
        JsonNode frozenInputScheme,JsonNode frozenDictionary,JsonNode frozenPreprocessing,UUID qualityPolicyId,
        int qualityPolicyVersion,String qualityPolicyHash,JsonNode qualityPolicy,String qualityPolicyStatus,
        UUID domainPolicyId,int domainPolicyVersion,String domainPolicyHash,JsonNode domainPolicy,String domainPolicyStatus) { }
    public record PredictionRow(UUID id,String requestId,String requestHash,String executionStatus,String outcomeStatus,
                                Integer terminalHttpStatus,JsonNode terminalResponse,JsonNode targetResults,long revision,
                                Instant createdAt,Instant completedAt) { }
}
