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
import java.util.LinkedHashSet;
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

    public Optional<BaselineRow> historyBaseline(UUID org, UUID versionId) {
        return jdbc.query("""
                SELECT 'EXPERIMENT_VERSION' source_type,e.id entity_id,v.id version_id,e.title,
                       coalesce(e.experiment_no,'') source_label,v.edit_model_jsonb payload,
                       v.template_version_id,v.template_snapshot_hash,v.template_snapshot_jsonb,v.created_at updated_at
                FROM rnd.experiment e JOIN rnd.experiment_version v ON v.organization_id=e.organization_id AND v.experiment_id=e.id
                WHERE e.organization_id=? AND v.id=? AND v.status='COMPLETED' AND e.deleted=false
                """, this::baseline, org, versionId).stream().findFirst();
    }

    public List<HistoryDatasetRow> historyDatasets(UUID org, String keyword) {
        var like = "%" + (keyword == null ? "" : keyword.strip()) + "%";
        return jdbc.query("""
            SELECT tv.id dataset_id,t.name dataset_name,
                   sum(greatest(coalesce(samples.sample_count,0),1)) row_count,max(v.created_at) updated_at
            FROM rnd.experiment e
            JOIN rnd.experiment_version v ON v.organization_id=e.organization_id AND v.id=e.current_version_id
            JOIN tpl.template_version tv ON tv.id=v.template_version_id
            JOIN tpl.template t ON t.id=tv.template_id AND t.organization_id=e.organization_id
            LEFT JOIN LATERAL (
              SELECT count(DISTINCT group_key) sample_count
              FROM (
                SELECT coalesce(nullif(item->>'logicalSampleKey',''),nullif(item->>'sampleKey',''),nullif(item->>'sourceGroupKey',''),
                         CASE WHEN nullif(item->>'sourceIdentity','') IS NOT NULL THEN nullif(item->>'sourceRecordKey','') END) group_key
                FROM jsonb_array_elements(coalesce(v.edit_model_jsonb->'sourceGroups','[]'::jsonb)) item
                UNION ALL
                SELECT coalesce(nullif(item->>'logicalSampleKey',''),nullif(item->>'sampleKey',''),nullif(item->>'sourceGroupKey',''),
                         CASE WHEN nullif(item->>'sourceIdentity','') IS NOT NULL THEN nullif(item->>'sourceRecordKey','') END) group_key
                FROM jsonb_array_elements(coalesce(v.edit_model_jsonb->'formulaItems','[]'::jsonb)) item
                UNION ALL
                SELECT coalesce(nullif(item->>'logicalSampleKey',''),nullif(item->>'sampleKey',''),nullif(item->>'sourceGroupKey',''),
                         CASE WHEN nullif(item->>'sourceIdentity','') IS NOT NULL THEN nullif(item->>'sourceRecordKey','') END)
                FROM jsonb_array_elements(coalesce(v.edit_model_jsonb->'processSteps','[]'::jsonb)) item
                UNION ALL
                SELECT coalesce(nullif(item->>'logicalSampleKey',''),nullif(item->>'sampleKey',''),nullif(item->>'sourceGroupKey',''),
                         CASE WHEN nullif(item->>'sourceIdentity','') IS NOT NULL THEN nullif(item->>'sourceRecordKey','') END)
                FROM jsonb_array_elements(coalesce(v.edit_model_jsonb->'testResults','[]'::jsonb)) item
              ) grouped WHERE group_key IS NOT NULL
            ) samples ON true
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

    public MaterialCatalogSnapshot materialCatalog(UUID organizationId) {
        var identities=new LinkedHashMap<String,String>();
        var codesById=new LinkedHashMap<String,String>();
        var validIds=new LinkedHashSet<String>();
        jdbc.query("SELECT code,id FROM mdm.material WHERE status<>'RETIRED'",
                (rs,n)->Map.entry(rs.getString(1),rs.getObject(2).toString()))
                .forEach(entry->{
                    var code=entry.getKey();var id=entry.getValue();validIds.add(id);codesById.put(id,code);
                    addMaterialIdentity(identities,code,id);
                });
        jdbc.query("""
                SELECT a.normalized_alias,a.material_id
                FROM mdm.material_alias a JOIN mdm.material m ON m.id=a.material_id
                WHERE a.organization_id=? AND a.status='ACTIVE' AND m.status<>'RETIRED'
                """,(rs,n)->Map.entry(rs.getString(1),rs.getObject(2).toString()),organizationId)
                .forEach(entry->addMaterialIdentity(identities,entry.getKey(),entry.getValue()));
        return new MaterialCatalogSnapshot(identities,codesById,validIds);
    }

    private void addMaterialIdentity(Map<String,String> identities,String identity,String materialId) {
        var key=com.jsd.aird.ai.rnd.modeling.ConfigurationHashing.normalizeAlias(identity);
        if(key.isBlank())return;
        var existing=identities.putIfAbsent(key,materialId);
        if(existing!=null&&!existing.equals(materialId))identities.put(key,"");
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
