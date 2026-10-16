package com.jsd.aird.ai.rnd.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.rnd.prediction.PredictionRepository;
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
public class FormulaDesignRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public FormulaDesignRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public JdbcTemplate jdbc() { return jdbc; }

    public boolean insertRun(UUID id, UUID org, UUID user, String runType, String key, String requestId,
                             String hash, JsonNode request, JsonNode bindings, long seed, JsonNode searchConfig) {
        return jdbc.update("""
            INSERT INTO ai.research_run_v2(id,organization_id,run_type,status,idempotency_key,request_hash,
                request_jsonb,model_bindings_jsonb,result_summary_jsonb,created_by,request_id,contract_version,
                seed,search_engine_version,search_config_jsonb,execution_status,outcome_status,progress,current_stage,heartbeat_at)
            VALUES(?,?,?,'QUEUED',?,?,?,?,'{}'::jsonb,?,?,'ai-rnd.v1',?,'formula-search.v2',?,'QUEUED',NULL,0,'QUEUED',now())
            ON CONFLICT(organization_id,idempotency_key) DO NOTHING
            """, id,org,runType,key,hash,pg(request),pg(bindings),user,requestId,seed,pg(searchConfig))==1;
    }
    public void attachJob(UUID org, UUID run, UUID job) {
        jdbc.update("UPDATE ai.research_run_v2 SET ops_job_id=?, updated_at=now() WHERE organization_id=? AND id=? AND execution_status='QUEUED'",job,org,run);
    }
    public Optional<Row> byKey(UUID org, UUID user, String key) {
        return jdbc.query("SELECT * FROM ai.research_run_v2 WHERE organization_id=? AND created_by=? AND idempotency_key=?",
                this::row,org,user,key).stream().findFirst();
    }
    public Optional<Row> byId(UUID org, UUID user, UUID id) {
        return jdbc.query("SELECT * FROM ai.research_run_v2 WHERE organization_id=? AND created_by=? AND id=?",
                this::row,org,user,id).stream().findFirst();
    }
    public Optional<Row> any(UUID org, UUID id) {
        return jdbc.query("SELECT * FROM ai.research_run_v2 WHERE organization_id=? AND id=?",this::row,org,id).stream().findFirst();
    }
    public List<Row> page(UUID org, UUID user, int page, int size, String status) {
        var where = status==null || status.isBlank() ? "" : " AND execution_status=?";
        var args = new java.util.ArrayList<Object>(); args.add(org); args.add(user); if(!where.isBlank()) args.add(status);
        args.add(size); args.add(Math.max(0,page)*size);
        return jdbc.query("SELECT * FROM ai.research_run_v2 WHERE organization_id=? AND created_by=?"+where+
                " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",this::row,args.toArray());
    }
    public long count(UUID org, UUID user, String status) {
        if(status==null||status.isBlank()) return jdbc.queryForObject("SELECT count(*) FROM ai.research_run_v2 WHERE organization_id=? AND created_by=?",Long.class,org,user);
        return jdbc.queryForObject("SELECT count(*) FROM ai.research_run_v2 WHERE organization_id=? AND created_by=? AND execution_status=?",Long.class,org,user,status);
    }
    public boolean markRunning(UUID org, UUID id) {
        return jdbc.update("UPDATE ai.research_run_v2 SET execution_status='RUNNING',status='RUNNING',current_stage='CHECKING_CONDITIONS',progress=5,started_at=coalesce(started_at,now()),heartbeat_at=now(),updated_at=now() WHERE organization_id=? AND id=? AND execution_status='QUEUED'",org,id)==1;
    }
    public boolean progress(UUID org, UUID id, int value, String stage) {
        return jdbc.update("UPDATE ai.research_run_v2 SET progress=?,current_stage=?,heartbeat_at=now(),updated_at=now() WHERE organization_id=? AND id=? AND execution_status='RUNNING'",Math.max(0,Math.min(100,value)),stage,org,id)>0;
    }
    public boolean complete(UUID org, UUID id, String outcome, JsonNode summary, JsonNode error) {
        return jdbc.update("UPDATE ai.research_run_v2 SET execution_status='SUCCEEDED',status=?,outcome_status=?,result_summary_jsonb=?,error_jsonb=?,progress=100,current_stage='SUCCEEDED',finished_at=now(),heartbeat_at=now(),revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND execution_status='RUNNING'",outcome, outcome, pg(summary), error==null?null:pg(error),org,id)==1;
    }
    public boolean fail(UUID org, UUID id, String code, String message) {
        var error=json.createObjectNode().put("code",code).put("message",message);
        return jdbc.update("UPDATE ai.research_run_v2 SET execution_status='FAILED',status='FAILED',outcome_status=NULL,error_jsonb=?,current_stage='FAILED',finished_at=now(),heartbeat_at=now(),revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND execution_status IN ('QUEUED','RUNNING')",pg(error),org,id)>0;
    }
    public void saveCandidate(UUID org, UUID run, int no, JsonNode formula, JsonNode process, JsonNode results,
                              JsonNode quality, JsonNode applicability, JsonNode rules, JsonNode evidence,
                              double score, String hash, JsonNode fixedInputs, JsonNode searchSpace, JsonNode gates,
                              double preference, double diversity, double targetTotal) {
        jdbc.update("""
            INSERT INTO ai.research_candidate_v2(id,organization_id,research_run_id,candidate_no,title,formula_jsonb,process_jsonb,
                target_results_jsonb,rule_check_jsonb,applicability_jsonb,evidence_jsonb,score,content_hash,target_total,
                fixed_inputs_jsonb,search_space_jsonb,target_gate_jsonb,quality_jsonb,preference_score,diversity_score,search_strategy)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(organization_id,research_run_id,content_hash) DO NOTHING
            """,UUID.randomUUID(),org,run,no,"候选配方 "+no,pg(formula),pg(process),pg(results),pg(rules),pg(applicability),pg(evidence),score,hash,targetTotal,pg(fixedInputs),pg(searchSpace),pg(gates),pg(quality),preference,diversity,"CONTROLLED_POOL");
    }
    public List<CandidateRow> candidates(UUID org, UUID run) {
        return jdbc.query("SELECT id,candidate_no,title,formula_jsonb,process_jsonb,target_results_jsonb,quality_jsonb,applicability_jsonb,rule_check_jsonb,score FROM ai.research_candidate_v2 WHERE organization_id=? AND research_run_id=? ORDER BY candidate_no",this::candidate,org,run);
    }
    public List<JsonNode> modelDisplaySnapshots(UUID org, List<UUID> modelIds) {
        if (modelIds.isEmpty()) return List.of();
        var marks = String.join(",", java.util.Collections.nCopies(modelIds.size(), "?"));
        var args = new java.util.ArrayList<Object>(); args.add(org); args.addAll(modelIds);
        return jdbc.query("""
            SELECT jsonb_build_object('targetId',m.target_id,'name',coalesce(ts.target_definition_jsonb->>'name',t.name),
                'valueType',t.value_type,'unit',tv.unit,'classes',tv.classes_jsonb,'testMethod',tv.definition_jsonb->>'testMethod',
                'testStage',tv.definition_jsonb->>'testStage','modelVersionId',m.id,
                'inputScheme',ts.input_scheme_jsonb,'dictionary',ts.material_dictionary_jsonb,
                'domain',m.applicability_domain_jsonb) metadata
            FROM ai.model_version m
            LEFT JOIN ai.prediction_target t ON t.organization_id=m.organization_id AND t.id=m.target_id
            LEFT JOIN ai.target_version tv ON tv.organization_id=m.organization_id AND tv.id=m.target_version_id
            LEFT JOIN ai.training_snapshot ts ON ts.organization_id=m.organization_id AND ts.id=m.training_snapshot_id
            WHERE m.organization_id=? AND m.id IN (%s) ORDER BY m.target_id
            """.formatted(marks), (r,n)->read(r,"metadata"), args.toArray());
    }
    private Row row(ResultSet r,int n)throws SQLException { return new Row(r.getObject("id",UUID.class),r.getString("run_type"),r.getString("execution_status"),r.getString("outcome_status"),r.getInt("progress"),r.getString("current_stage"),r.getString("request_id"),r.getString("request_hash"),read(r,"request_jsonb"),read(r,"model_bindings_jsonb"),read(r,"result_summary_jsonb"),readNullable(r,"error_jsonb"),r.getObject("created_by",UUID.class),r.getObject("ops_job_id",UUID.class),r.getLong("seed"),read(r,"search_config_jsonb"),instant(r,"updated_at")); }
    private CandidateRow candidate(ResultSet r,int n)throws SQLException { return new CandidateRow(r.getObject("id",UUID.class),r.getInt("candidate_no"),r.getString("title"),read(r,"formula_jsonb"),read(r,"process_jsonb"),read(r,"target_results_jsonb"),read(r,"quality_jsonb"),read(r,"applicability_jsonb"),read(r,"rule_check_jsonb"),r.getDouble("score")); }
    private JsonNode read(ResultSet r,String col)throws SQLException { try { var s=r.getString(col); return s==null?json.createObjectNode():json.readTree(s); } catch(Exception e){throw new SQLException(e);} }
    private JsonNode readNullable(ResultSet r,String col)throws SQLException { var s=r.getString(col); if(s==null)return null; try{return json.readTree(s);}catch(Exception e){throw new SQLException(e);} }
    private Instant instant(ResultSet r,String col)throws SQLException { var t=r.getTimestamp(col);return t==null?null:t.toInstant(); }
    private PGobject pg(JsonNode n){ try{var p=new PGobject();p.setType("jsonb");p.setValue(n==null||n.isMissingNode()||n.isNull()?"{}":n.toString());return p;}catch(Exception e){throw new IllegalArgumentException(e);} }
    public record Row(UUID id,String runType,String executionStatus,String outcomeStatus,int progress,String stage,
                      String requestId,String requestHash,JsonNode request,JsonNode bindings,JsonNode result,JsonNode error,
                      UUID createdBy,UUID opsJobId,long seed,JsonNode searchConfig,Instant updatedAt) { }
    public record CandidateRow(UUID id,int candidateNo,String title,JsonNode formula,JsonNode inputs,JsonNode results,JsonNode quality,JsonNode applicability,JsonNode ruleCheck,double score) { }
}
