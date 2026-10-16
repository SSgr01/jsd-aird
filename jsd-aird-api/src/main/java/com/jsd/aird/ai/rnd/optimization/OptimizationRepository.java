package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OptimizationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OptimizationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public JdbcTemplate jdbc() { return jdbc; }

    public Optional<BaselineRow> baseline(UUID org, UUID user, String type, UUID id) {
        var rows = switch (type) {
            case "EXPERIMENT_VERSION" -> jdbc.query("""
                SELECT 'EXPERIMENT_VERSION' source_type,e.id entity_id,v.id version_id,e.title,
                       coalesce(e.experiment_no,'') source_label,v.edit_model_jsonb payload,
                       v.template_version_id,v.template_snapshot_hash,v.template_snapshot_jsonb,v.created_at updated_at
                FROM rnd.experiment e JOIN rnd.experiment_version v ON v.organization_id=e.organization_id AND v.id=e.current_version_id
                WHERE e.organization_id=? AND e.id=? AND e.status='COMPLETED' AND e.deleted=false
                """, this::baseline, org, id);
            case "DATA_SAMPLE_REVISION" -> jdbc.query("""
                SELECT 'DATA_SAMPLE_REVISION' source_type,s.id entity_id,r.id version_id,
                       coalesce(nullif(ij.source_file_name,''), nullif(src.source_business_key,''), '数据中心样本') ||
                         CASE WHEN ci.sheet_name IS NOT NULL THEN ' · ' || ci.sheet_name ELSE '' END ||
                         CASE WHEN ci.row_coordinate IS NOT NULL THEN ' · ' || ci.row_coordinate ELSE '' END title,
                       '数据中心正式数据' source_label,
                       jsonb_build_object('formula',r.composition_jsonb,'inputs',r.conditions_jsonb,
                         'process',r.process_jsonb,'observations',r.observations_jsonb,
                         'sourceCoordinates',r.source_coordinates_jsonb,'factHash',r.fact_hash) payload,
                       NULL::uuid template_version_id,NULL::char(64) template_snapshot_hash,
                       '{}'::jsonb template_snapshot_jsonb,r.created_at updated_at
                FROM ai.training_sample s JOIN ai.sample_revision r ON r.organization_id=s.organization_id AND r.id=s.current_sample_revision_id
                LEFT JOIN ai.sample_source src ON src.organization_id=s.organization_id AND src.id=r.sample_source_id
                LEFT JOIN data.confirmed_submission_item ci ON ci.organization_id=s.organization_id
                  AND ci.id=src.confirmed_submission_item_id
                LEFT JOIN data.confirmed_submission cs ON cs.organization_id=ci.organization_id
                  AND cs.id=ci.confirmed_submission_id
                LEFT JOIN data.import_job ij ON ij.organization_id=cs.organization_id
                  AND ij.id=cs.import_job_id
                WHERE s.organization_id=? AND s.id=? AND s.status='ACTIVE' AND s.authority_source_type='DATA_CENTER'
                """, this::baseline, org, id);
            case "RESEARCH_CANDIDATE" -> jdbc.query("""
                SELECT 'RESEARCH_CANDIDATE' source_type,c.id entity_id,c.id version_id,c.title,
                       concat('配方预测 ',r.created_at::date) source_label,
                       jsonb_build_object('formula',c.formula_jsonb,'inputs',c.fixed_inputs_jsonb,
                         'predictions',c.target_results_jsonb,'quality',c.quality_jsonb,
                         'applicability',c.applicability_jsonb,'runId',r.id) payload,
                       NULL::uuid template_version_id,NULL::char(64) template_snapshot_hash,
                       '{}'::jsonb template_snapshot_jsonb,c.created_at updated_at
                FROM ai.research_candidate_v2 c JOIN ai.research_run_v2 r ON r.organization_id=c.organization_id AND r.id=c.research_run_id
                WHERE c.organization_id=? AND c.id=? AND r.created_by=? AND r.run_type='FORMULA_PREDICTION'
                  AND r.execution_status='SUCCEEDED'
                """, this::baseline, org, id, user);
            default -> List.<BaselineRow>of();
        };
        return rows.stream().findFirst();
    }

    public List<HistoryDatasetRow> historyDatasets(UUID org, String keyword) {
        var like = "%" + (keyword == null ? "" : keyword.strip()) + "%";
        return jdbc.query("""
            SELECT tv.id dataset_id,t.name dataset_name,count(*) row_count,max(v.created_at) updated_at
            FROM rnd.experiment e
            JOIN rnd.experiment_version v ON v.organization_id=e.organization_id AND v.id=e.current_version_id
            JOIN tpl.template_version tv ON tv.id=v.template_version_id
            JOIN tpl.template t ON t.id=tv.template_id AND t.organization_id=e.organization_id
            WHERE e.organization_id=? AND e.deleted=false AND e.status='COMPLETED'
              AND v.status='COMPLETED'
              AND (t.name ILIKE ? OR e.title ILIKE ? OR e.experiment_no ILIKE ?)
            GROUP BY tv.id,t.name ORDER BY max(v.created_at) DESC,t.name
            """, (r,n)->new HistoryDatasetRow(r.getObject("dataset_id",UUID.class),r.getString("dataset_name"),
                r.getInt("row_count"),instant(r,"updated_at")),org,like,like,like);
    }

    public List<HistoryRecordRow> historyRecords(UUID org, UUID templateVersionId) {
        return jdbc.query("""
            SELECT e.id experiment_id,v.id version_id,e.experiment_no,e.title,v.edit_model_jsonb,v.created_at
            FROM rnd.experiment e
            JOIN rnd.experiment_version v ON v.organization_id=e.organization_id AND v.id=e.current_version_id
            WHERE e.organization_id=? AND e.deleted=false AND e.status='COMPLETED' AND v.status='COMPLETED'
              AND v.template_version_id=?
            ORDER BY v.created_at,e.experiment_no,e.id
            """,(r,n)->new HistoryRecordRow(r.getObject("experiment_id",UUID.class),r.getObject("version_id",UUID.class),
                r.getString("experiment_no"),r.getString("title"),read(r,"edit_model_jsonb"),instant(r,"created_at")),org,templateVersionId);
    }

    public Optional<String> materialName(String materialId) {
        try {
            return jdbc.query("SELECT name FROM mdm.material WHERE id=? AND status<>'RETIRED'",
                    (rs,n)->rs.getString("name"),UUID.fromString(materialId)).stream().findFirst();
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public Map<String,String> historyFieldNames(UUID organizationId) {
        var names = new LinkedHashMap<String,String>();
        jdbc.query("SELECT dictionary_code,display_name FROM tpl.standard_field_dictionary WHERE status='ACTIVE' ORDER BY version_no",
                (rs,n)->Map.entry(rs.getString(1),rs.getString(2))).forEach(entry->names.put(entry.getKey(),entry.getValue()));
        jdbc.query("SELECT field_code,name FROM ai.input_field WHERE organization_id=? AND status<>'RETIRED'",
                (rs,n)->Map.entry(rs.getString(1),rs.getString(2)),organizationId).forEach(entry->names.putIfAbsent(entry.getKey(),entry.getValue()));
        return names;
    }

    public boolean insertRun(UUID id, UUID org, UUID user, String key, String requestId, String requestHash,
                             JsonNode request, JsonNode bindings, long seed, JsonNode searchConfig,
                             BaselineRow baseline, String baselineHash, JsonNode baselineSnapshot,
                             JsonNode optimizationConfig) {
        return jdbc.update("""
            INSERT INTO ai.research_run_v2(id,organization_id,run_type,status,idempotency_key,request_hash,
              request_jsonb,model_bindings_jsonb,result_summary_jsonb,created_by,request_id,contract_version,
              seed,search_engine_version,search_config_jsonb,execution_status,outcome_status,progress,current_stage,
              heartbeat_at,baseline_type,baseline_entity_id,baseline_version_id,baseline_content_hash,
              baseline_snapshot_jsonb,optimization_config_jsonb)
            VALUES(?,?, 'EXPERIMENT_OPTIMIZATION','QUEUED',?,?,?,?,'{}'::jsonb,?,?,'ai-rnd.v1',?,
              ?,?,'QUEUED',NULL,0,'QUEUED',now(),?,?,?,?,?,?)
            ON CONFLICT(organization_id,idempotency_key) DO NOTHING
            """, id,org,key,requestHash,pg(request),pg(bindings),user,requestId,seed,
                "GAUSSIAN_PROCESS_EI".equals(searchConfig.path("algorithm").asText())?"gaussian-process-ei.v1":"formula-search.v2",pg(searchConfig),
                baseline.type(),baseline.entityId(),baseline.versionId(),baselineHash,pg(baselineSnapshot),pg(optimizationConfig))==1;
    }

    public Optional<RunRow> byKey(UUID org, UUID user, String key) {
        return jdbc.query("SELECT * FROM ai.research_run_v2 WHERE organization_id=? AND created_by=? AND idempotency_key=? AND run_type='EXPERIMENT_OPTIMIZATION'",this::run,org,user,key).stream().findFirst();
    }
    public Optional<RunRow> byId(UUID org, UUID user, UUID id) {
        return jdbc.query("SELECT * FROM ai.research_run_v2 WHERE organization_id=? AND created_by=? AND id=? AND run_type='EXPERIMENT_OPTIMIZATION'",this::run,org,user,id).stream().findFirst();
    }
    public Optional<RunRow> any(UUID org, UUID id) {
        return jdbc.query("SELECT * FROM ai.research_run_v2 WHERE organization_id=? AND id=? AND run_type='EXPERIMENT_OPTIMIZATION'",this::run,org,id).stream().findFirst();
    }
    public void attachJob(UUID org, UUID run, UUID job) { jdbc.update("UPDATE ai.research_run_v2 SET ops_job_id=?,updated_at=now() WHERE organization_id=? AND id=? AND execution_status='QUEUED'",job,org,run); }
    public boolean markRunning(UUID org,UUID run){return jdbc.update("UPDATE ai.research_run_v2 SET execution_status='RUNNING',status='RUNNING',current_stage='BASELINE_CHECK',progress=5,started_at=coalesce(started_at,now()),heartbeat_at=now(),updated_at=now() WHERE organization_id=? AND id=? AND execution_status='QUEUED'",org,run)==1;}
    public void progress(UUID org,UUID run,int progress,String stage){jdbc.update("UPDATE ai.research_run_v2 SET progress=?,current_stage=?,heartbeat_at=now(),updated_at=now() WHERE organization_id=? AND id=? AND execution_status='RUNNING'",progress,stage,org,run);}
    public boolean complete(UUID org,UUID run,String outcome,JsonNode summary){return jdbc.update("UPDATE ai.research_run_v2 SET execution_status='SUCCEEDED',status=?,outcome_status=?,result_summary_jsonb=?,progress=100,current_stage='SUCCEEDED',finished_at=now(),heartbeat_at=now(),revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND execution_status='RUNNING'",outcome,outcome,pg(summary),org,run)==1;}
    public void fail(UUID org,UUID run,String code,String message){var e=json.createObjectNode().put("code",code).put("message",message==null?"执行失败":message);jdbc.update("UPDATE ai.research_run_v2 SET execution_status='FAILED',status='FAILED',outcome_status=NULL,error_jsonb=?,current_stage='FAILED',finished_at=now(),heartbeat_at=now(),revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND execution_status IN ('QUEUED','RUNNING')",pg(e),org,run);}

    public UUID saveCandidate(UUID org,UUID run,int no,String strategy,String title,JsonNode formula,JsonNode inputs,
                              JsonNode results,JsonNode quality,JsonNode applicability,JsonNode rules,JsonNode evidence,
                              double score,String hash,double targetTotal,double distance,JsonNode changes,JsonNode strategyEvidence,JsonNode risks) {
        var id=UUID.randomUUID();
        var written=jdbc.update("""
            INSERT INTO ai.research_candidate_v2(id,organization_id,research_run_id,candidate_no,title,formula_jsonb,
              process_jsonb,target_results_jsonb,rule_check_jsonb,applicability_jsonb,evidence_jsonb,score,content_hash,
              target_total,fixed_inputs_jsonb,search_space_jsonb,target_gate_jsonb,quality_jsonb,preference_score,
              diversity_score,search_strategy,baseline_distance,change_summary_jsonb,strategy_evidence_jsonb,risk_flags_jsonb)
            VALUES(?,?,?,?,?,?,'{}'::jsonb,?,?,?,?,?,?,?,?,'{}'::jsonb,'{}'::jsonb,?,0,0,?,?,?,?,?)
            ON CONFLICT(organization_id,research_run_id,content_hash) DO NOTHING
            """,id,org,run,no,title,pg(formula),pg(results),pg(rules),pg(applicability),pg(evidence),score,hash,
                targetTotal,pg(inputs),pg(quality),strategy,distance,pg(changes),pg(strategyEvidence),pg(risks));
        return written==1?id:null;
    }

    public List<CandidateRow> candidates(UUID org,UUID run){return jdbc.query("SELECT * FROM ai.research_candidate_v2 WHERE organization_id=? AND research_run_id=? ORDER BY candidate_no",this::candidate,org,run);}
    public Optional<CandidateRow> candidate(UUID org,UUID run,UUID candidate){return jdbc.query("SELECT * FROM ai.research_candidate_v2 WHERE organization_id=? AND research_run_id=? AND id=?",this::candidate,org,run,candidate).stream().findFirst();}

    private BaselineRow baseline(ResultSet r,int n)throws SQLException{return new BaselineRow(r.getString("source_type"),r.getObject("entity_id",UUID.class),r.getObject("version_id",UUID.class),r.getString("title"),r.getString("source_label"),read(r,"payload"),r.getObject("template_version_id",UUID.class),r.getString("template_snapshot_hash"),read(r,"template_snapshot_jsonb"),instant(r,"updated_at"));}
    private RunRow run(ResultSet r,int n)throws SQLException{return new RunRow(r.getObject("id",UUID.class),r.getString("execution_status"),r.getString("outcome_status"),r.getInt("progress"),r.getString("current_stage"),r.getString("request_id"),r.getString("request_hash"),read(r,"request_jsonb"),read(r,"model_bindings_jsonb"),read(r,"result_summary_jsonb"),readNullable(r,"error_jsonb"),r.getObject("created_by",UUID.class),r.getObject("ops_job_id",UUID.class),r.getLong("seed"),read(r,"search_config_jsonb"),r.getString("baseline_type"),r.getObject("baseline_entity_id",UUID.class),r.getObject("baseline_version_id",UUID.class),r.getString("baseline_content_hash"),read(r,"baseline_snapshot_jsonb"),read(r,"optimization_config_jsonb"),instant(r,"updated_at"));}
    private CandidateRow candidate(ResultSet r,int n)throws SQLException{return new CandidateRow(r.getObject("id",UUID.class),r.getInt("candidate_no"),r.getString("title"),r.getString("search_strategy"),read(r,"formula_jsonb"),read(r,"fixed_inputs_jsonb"),read(r,"target_results_jsonb"),read(r,"quality_jsonb"),read(r,"applicability_jsonb"),read(r,"rule_check_jsonb"),read(r,"evidence_jsonb"),r.getDouble("score"),r.getDouble("target_total"),r.getDouble("baseline_distance"),read(r,"change_summary_jsonb"),read(r,"strategy_evidence_jsonb"),read(r,"risk_flags_jsonb"));}

    private JsonNode read(ResultSet r,String col)throws SQLException{try{var s=r.getString(col);return s==null?json.createObjectNode():json.readTree(s);}catch(Exception e){throw new SQLException(e);}}
    private JsonNode readNullable(ResultSet r,String col)throws SQLException{var s=r.getString(col);if(s==null)return null;try{return json.readTree(s);}catch(Exception e){throw new SQLException(e);}}
    private Instant instant(ResultSet r,String col)throws SQLException{var t=r.getTimestamp(col);return t==null?Instant.EPOCH:t.toInstant();}
    private PGobject pg(JsonNode node){try{var p=new PGobject();p.setType("jsonb");p.setValue(node==null||node.isNull()||node.isMissingNode()?"{}":node.toString());return p;}catch(Exception e){throw new IllegalArgumentException(e);}}

    public record BaselineRow(String type,UUID entityId,UUID versionId,String title,String sourceLabel,JsonNode payload,
                              UUID templateVersionId,String templateSnapshotHash,JsonNode templateSnapshot,Instant updatedAt){}
    public record HistoryDatasetRow(UUID id,String name,int rowCount,Instant updatedAt){}
    public record HistoryRecordRow(UUID experimentId,UUID versionId,String experimentNo,String title,JsonNode editModel,Instant updatedAt){}
    public record RunRow(UUID id,String executionStatus,String outcomeStatus,int progress,String stage,String requestId,
                         String requestHash,JsonNode request,JsonNode bindings,JsonNode result,JsonNode error,UUID createdBy,
                         UUID opsJobId,long seed,JsonNode searchConfig,String baselineType,UUID baselineEntityId,
                         UUID baselineVersionId,String baselineHash,JsonNode baseline,JsonNode optimizationConfig,Instant updatedAt){}
    public record CandidateRow(UUID id,int candidateNo,String title,String strategy,JsonNode formula,JsonNode inputs,
                               JsonNode results,JsonNode quality,JsonNode applicability,JsonNode ruleCheck,JsonNode evidence,
                               double score,double targetTotal,double distance,JsonNode changes,JsonNode strategyEvidence,JsonNode risks){}

}
