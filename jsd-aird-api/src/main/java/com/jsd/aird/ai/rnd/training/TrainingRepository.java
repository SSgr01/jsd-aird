package com.jsd.aird.ai.rnd.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.api.PageResponse;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.training.TrainingContracts.*;

@Repository
public class TrainingRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TrainingRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public JdbcTemplate jdbc() { return jdbc; }

    public TrainingSettings settings(UUID org) {
        return jdbc.query("SELECT * FROM ai.training_scheduler_setting WHERE organization_id=?", (rs,n) -> new TrainingSettings(
                rs.getBoolean("auto_learning_enabled"), instant(rs,"last_evaluated_at"), rs.getLong("revision"), instant(rs,"updated_at")), org)
                .stream().findFirst().orElse(new TrainingSettings(false,null,0,null));
    }

    public int updateSettings(UUID org, UUID actor, boolean enabled, long expected) {
        if (jdbc.update("""
                UPDATE ai.training_scheduler_setting SET auto_learning_enabled=?,revision=revision+1,
                    updated_by=?,updated_at=now() WHERE organization_id=? AND revision=?
                """, enabled,actor,org,expected) == 1) return 1;
        if (expected == 0) return jdbc.update("""
                INSERT INTO ai.training_scheduler_setting(organization_id,auto_learning_enabled,revision,updated_by)
                VALUES(?,?,1,?) ON CONFLICT DO NOTHING
                """,org,enabled,actor);
        return 0;
    }

    public void touchEvaluation(UUID org) {
        jdbc.update("""
                INSERT INTO ai.training_scheduler_setting(organization_id,last_evaluated_at)
                VALUES(?,now()) ON CONFLICT(organization_id) DO UPDATE SET last_evaluated_at=now()
                """,org);
    }

    public List<CandidateTarget> candidateTargets(UUID org) {
        return jdbc.query("""
                SELECT t.id target_id,t.name target_name,t.performance_project category,tv.id target_version_id,
                       tv.version_no target_version_no,tv.value_type,tv.unit,tv.classes_jsonb,
                       tv.definition_jsonb,tv.observation_semantics_jsonb,coalesce(tv.definition_jsonb->>'sha256',tv.config_hash) target_hash,
                       s.id input_scheme_id,s.version_no input_scheme_version,s.config_hash scheme_hash,
                       s.preprocessing_jsonb,s.material_dictionary_version_id,
                       p.id policy_id,p.version_no policy_version,p.policy_hash,
                       p.qualification_jsonb,p.validation_jsonb,p.training_jsonb,
                       er.id eligibility_run_id,er.rule_fingerprint,er.total_samples,er.trainable_count,
                       er.finished_at eligibility_finished_at,
                       coalesce((select max(revision_no) from ai.sample_revision sr where sr.organization_id=t.organization_id),0) fact_high_watermark
                FROM ai.prediction_target t
                JOIN ai.target_version tv ON tv.organization_id=t.organization_id AND tv.id=t.current_version_id AND tv.status='PUBLISHED'
                JOIN ai.input_scheme s ON s.organization_id=t.organization_id AND s.id=t.current_input_scheme_id AND s.status='FROZEN'
                JOIN LATERAL (SELECT p1.* FROM ai.modeling_policy_version p1
                    WHERE p1.organization_id=t.organization_id AND p1.target_id=t.id AND p1.kind='TRAINING' AND p1.status='PUBLISHED'
                    ORDER BY p1.version_no DESC LIMIT 1) p ON true
                LEFT JOIN LATERAL (SELECT er1.* FROM ai.eligibility_evaluation_run er1
                    WHERE er1.organization_id=t.organization_id AND er1.target_id=t.id
                      AND er1.target_version_id=tv.id AND er1.input_scheme_id=s.id
                      AND er1.modeling_policy_version_id=p.id AND er1.status='SUCCEEDED'
                    ORDER BY er1.finished_at DESC,er1.id DESC LIMIT 1) er ON true
                WHERE t.organization_id=? AND t.status='ACTIVE'
                ORDER BY t.performance_project,t.name,t.id
                """, this::candidate, org);
    }

    public Optional<CandidateTarget> candidateTarget(UUID org, UUID targetId) {
        return candidateTargets(org).stream().filter(x -> x.targetId().equals(targetId)).findFirst();
    }

    public FrozenConfig frozenConfig(UUID org, CandidateTarget target) {
        var fields = jdbc.query("""
                SELECT fv.id,f.field_code,f.name,fv.value_type,fv.unit,fv.availability_stage,
                       sf.required,sf.ordinal,fv.definition_jsonb,fv.preprocessing_jsonb,sf.override_jsonb
                FROM ai.input_scheme_field sf
                JOIN ai.input_field_version fv ON fv.organization_id=sf.organization_id AND fv.id=sf.input_field_version_id
                JOIN ai.input_field f ON f.organization_id=fv.organization_id AND f.id=fv.input_field_id
                WHERE sf.organization_id=? AND sf.input_scheme_id=? ORDER BY sf.ordinal,fv.id
                """, this::fieldNode, org,target.inputSchemeId());
        var dictionary = jdbc.query("""
                SELECT id,version_no,dictionary_code,vocabulary_jsonb,encoder_jsonb,dictionary_hash
                FROM ai.material_dictionary_version WHERE organization_id=? AND id=? AND status IN ('FROZEN','RETIRED')
                """, (rs,n) -> new DictionaryRow(rs.getObject("id",UUID.class),rs.getInt("version_no"),rs.getString("dictionary_code"),
                node(rs,"vocabulary_jsonb"),node(rs,"encoder_jsonb"),rs.getString("dictionary_hash")),org,target.dictionaryId()).stream().findFirst().orElse(null);
        var mappings = jdbc.query("""
                SELECT sm.id,sm.source_type,sm.version_no,sm.mapping_jsonb,sm.mapping_hash
                FROM ai.eligibility_evaluation_run er
                CROSS JOIN LATERAL jsonb_array_elements(er.source_mapping_versions_jsonb) entry
                JOIN ai.source_mapping_version sm ON sm.organization_id=er.organization_id
                    AND sm.id=(entry->>'versionId')::uuid
                WHERE er.organization_id=? AND er.id=? ORDER BY sm.source_type,sm.id
                """,this::mappingNode,org,target.eligibilityRunId());
        return new FrozenConfig(fields,dictionary,json.valueToTree(mappings));
    }

    public List<EligibleSample> eligibleSamples(UUID org, UUID runId) {
        return jdbc.query("""
                SELECT te.id eligibility_id,te.evidence_jsonb,te.warnings_jsonb,
                       sr.id revision_id,sr.revision_no,sr.composition_jsonb,sr.process_jsonb,
                       sr.conditions_jsonb,sr.observations_jsonb,sr.facts_jsonb,sr.source_coordinates_jsonb,
                       sr.fact_hash content_hash,ts.id sample_id,ts.logical_sample_key,ts.authority_source_type,
                       ss.id source_id,ss.source_type,ss.source_group_keys_jsonb,ss.source_business_key,
                       ss.physical_identity_hash,ss.source_version
                FROM ai.training_eligibility te
                JOIN ai.sample_revision sr ON sr.organization_id=te.organization_id AND sr.id=te.sample_revision_id
                JOIN ai.training_sample ts ON ts.organization_id=sr.organization_id AND ts.id=sr.training_sample_id
                JOIN ai.sample_source ss ON ss.organization_id=sr.organization_id AND ss.id=sr.sample_source_id
                WHERE te.organization_id=? AND te.evaluation_run_id=? AND te.state='TRAINABLE'
                ORDER BY ts.logical_sample_key,sr.id
                """,this::sample,org,runId);
    }

    public Optional<TrainingJobSummary> job(UUID org, UUID id) {
        return jdbc.query(jobSelect()+" WHERE j.organization_id=? AND j.id=?",this::jobSummary,org,id).stream().findFirst();
    }

    public PageResponse<TrainingJobSummary> jobs(UUID org,String status,String keyword,int page,int size) {
        var where = new StringBuilder(" WHERE j.organization_id=?"); var args=new ArrayList<Object>();args.add(org);
        if(status!=null&&!status.isBlank()){where.append(" AND j.status=?");args.add(status);}
        if(keyword!=null&&!keyword.isBlank()){where.append(" AND (t.name ILIKE ? OR t.target_code ILIKE ?)");args.add("%"+keyword+"%");args.add("%"+keyword+"%");}
        Long total=jdbc.queryForObject("SELECT count(*) FROM ai.training_job j JOIN ai.prediction_target t ON t.organization_id=j.organization_id AND t.id=j.target_id"+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        var items=jdbc.query(jobSelect()+where+" ORDER BY j.created_at DESC,j.id LIMIT ? OFFSET ?",this::jobSummary,args.toArray());
        long count=total==null?0:total;return new PageResponse<>(items,page,size,count,(count+size-1)/size);
    }

    public TrainingJobDetail jobDetail(UUID org,UUID id) {
        var summary=job(org,id).orElseThrow();
        var attempts=jdbc.query("""
                SELECT * FROM ai.training_job_attempt WHERE organization_id=? AND training_job_id=? ORDER BY attempt_no
                """,(rs,n)->new AttemptView(rs.getObject("id",UUID.class),rs.getInt("attempt_no"),rs.getString("status"),
                rs.getString("current_stage"),rs.getInt("progress"),rs.getString("worker_id"),instant(rs,"started_at"),
                instant(rs,"heartbeat_at"),instant(rs,"finished_at"),rs.getString("error_code"),rs.getString("error_message"),node(rs,"artifact_refs_jsonb")),org,id);
        var snap=snapshotSummary(org,summary.snapshotId()).orElseThrow();
        var configs=jdbc.query("SELECT training_policy_jsonb,target_definition_jsonb,input_scheme_jsonb,material_dictionary_jsonb,source_mapping_versions_jsonb,preprocessing_jsonb FROM ai.training_snapshot WHERE organization_id=? AND id=?",
                this::configNodes,org,summary.snapshotId()).getFirst();
        return new TrainingJobDetail(summary,snap,attempts,configs[0],configs[1]);
    }

    public Optional<SnapshotSummary> snapshotSummary(UUID org,UUID id){return jdbc.query("SELECT * FROM ai.training_snapshot WHERE organization_id=? AND id=?",this::snapshotSummary,org,id).stream().findFirst();}
    public SnapshotDetail snapshot(UUID org,UUID id){return jdbc.query("SELECT * FROM ai.training_snapshot WHERE organization_id=? AND id=?",(rs,n)->new SnapshotDetail(snapshotSummary(rs,n),node(rs,"target_definition_jsonb"),node(rs,"input_scheme_jsonb"),node(rs,"material_dictionary_jsonb"),node(rs,"source_mapping_versions_jsonb"),node(rs,"preprocessing_jsonb"),node(rs,"training_policy_jsonb"),node(rs,"authorization_scope_jsonb"),node(rs,"validation_groups_jsonb"),node(rs,"manifest_jsonb")),org,id).stream().findFirst().orElseThrow();}
    public List<SnapshotItem> snapshotItems(UUID org,UUID id,int page,int size){return jdbc.query("""
            SELECT * FROM ai.training_snapshot_item WHERE organization_id=? AND training_snapshot_id=?
            ORDER BY ordinal LIMIT ? OFFSET ?
            """,(rs,n)->new SnapshotItem(rs.getObject("sample_revision_id",UUID.class),rs.getObject("training_sample_id",UUID.class),rs.getLong("ordinal"),rs.getString("split_group"),rs.getString("row_hash"),node(rs,"row_jsonb"),strings(node(rs,"observation_ids_jsonb")),strings(node(rs,"replicate_group_keys_jsonb")),node(rs,"source_refs_jsonb"),node(rs,"validation_groups_jsonb")),org,id,size,(page-1)*size);}

    public Optional<ModelSummary> model(UUID org,UUID id){return jdbc.query(modelSelect()+" WHERE m.organization_id=? AND m.id=?",this::modelSummary,org,id).stream().findFirst();}
    public PageResponse<ModelSummary> models(UUID org,String status,String category,String keyword,int page,int size){var where=new StringBuilder(" WHERE m.organization_id=?");var args=new ArrayList<Object>();args.add(org);if(status!=null&&!status.isBlank()){where.append(" AND m.status=?");args.add(status);}if(category!=null&&!category.isBlank()){where.append(" AND t.performance_project=?");args.add(category);}if(keyword!=null&&!keyword.isBlank()){where.append(" AND (t.name ILIKE ? OR t.target_code ILIKE ?)");args.add("%"+keyword+"%");args.add("%"+keyword+"%");}Long total=jdbc.queryForObject("SELECT count(*) FROM ai.model_version m JOIN ai.prediction_target t ON t.organization_id=m.organization_id AND t.id=m.target_id"+where,Long.class,args.toArray());var pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((page-1)*size);var items=jdbc.query(modelSelect()+where+" ORDER BY t.performance_project,t.name,m.version_no DESC,m.id LIMIT ? OFFSET ?",this::modelSummary,pageArgs.toArray());long count=total==null?0:total;return new PageResponse<>(items,page,size,count,(count+size-1)/size);}
    public ModelDetail modelDetail(UUID org,UUID id){var model=model(org,id).orElseThrow();var payload=jdbc.query("SELECT applicability_domain_jsonb,model_card_jsonb,rejection_reasons_jsonb FROM ai.model_version WHERE organization_id=? AND id=?",(rs,n)->new JsonNode[]{node(rs,"applicability_domain_jsonb"),node(rs,"model_card_jsonb"),node(rs,"rejection_reasons_jsonb")},org,id).getFirst();var releases=jdbc.query("SELECT * FROM ai.model_release WHERE organization_id=? AND target_id=? ORDER BY created_at DESC",(rs,n)->new ReleaseView(rs.getObject("id",UUID.class),rs.getString("action"),rs.getObject("model_version_id",UUID.class),rs.getObject("previous_model_version_id",UUID.class),rs.getString("reason"),instant(rs,"created_at")),org,model.targetId());return new ModelDetail(model,snapshotSummary(org,jdbc.queryForObject("SELECT training_snapshot_id FROM ai.model_version WHERE organization_id=? AND id=?",UUID.class,org,id)).orElseThrow(),payload[0],payload[1],payload[2],releases);}

    public JsonNode node(ResultSet rs,String column){try{var raw=rs.getString(column);return raw==null?json.nullNode():json.readTree(raw);}catch(Exception e){throw new IllegalStateException("无法解析"+column,e);}}
    public PGobject pg(JsonNode n){try{var p=new PGobject();p.setType("jsonb");p.setValue((n==null?json.nullNode():n).toString());return p;}catch(Exception e){throw new IllegalArgumentException(e);}}
    public ObjectMapper json(){return json;}

    private CandidateTarget candidate(ResultSet rs,int n)throws java.sql.SQLException{return new CandidateTarget(rs.getObject("target_id",UUID.class),rs.getString("target_name"),rs.getString("category"),rs.getObject("target_version_id",UUID.class),rs.getInt("target_version_no"),rs.getString("value_type"),rs.getString("unit"),node(rs,"classes_jsonb"),node(rs,"definition_jsonb"),node(rs,"observation_semantics_jsonb"),rs.getString("target_hash"),rs.getObject("input_scheme_id",UUID.class),rs.getInt("input_scheme_version"),rs.getString("scheme_hash"),node(rs,"preprocessing_jsonb"),rs.getObject("material_dictionary_version_id",UUID.class),rs.getObject("policy_id",UUID.class),rs.getInt("policy_version"),rs.getString("policy_hash"),node(rs,"qualification_jsonb"),node(rs,"validation_jsonb"),node(rs,"training_jsonb"),rs.getObject("eligibility_run_id",UUID.class),rs.getString("rule_fingerprint"),rs.getLong("total_samples"),rs.getLong("trainable_count"),instant(rs,"eligibility_finished_at"),rs.getLong("fact_high_watermark"));}
    private JsonNode fieldNode(ResultSet rs,int n)throws java.sql.SQLException{var out=json.createObjectNode().put("fieldVersionId",rs.getString("id")).put("code",rs.getString("field_code")).put("name",rs.getString("name")).put("valueType",rs.getString("value_type")).put("unit",rs.getString("unit")).put("acquisitionTiming",rs.getString("availability_stage")).put("required",rs.getBoolean("required")).put("ordinal",rs.getInt("ordinal"));out.set("encoding",node(rs,"override_jsonb"));return out;}
    private JsonNode mappingNode(ResultSet rs,int n)throws java.sql.SQLException{var out=json.createObjectNode().put("id",rs.getString("id")).put("sourceType",rs.getString("source_type")).put("version",rs.getInt("version_no")).put("sha256",rs.getString("mapping_hash"));out.set("mapping",node(rs,"mapping_jsonb"));return out;}
    private JsonNode[] configNodes(ResultSet rs,int n)throws java.sql.SQLException{var detail=json.createObjectNode();detail.set("target",node(rs,"target_definition_jsonb"));detail.set("inputScheme",node(rs,"input_scheme_jsonb"));detail.set("materialDictionary",node(rs,"material_dictionary_jsonb"));detail.set("sourceMappings",node(rs,"source_mapping_versions_jsonb"));detail.set("preprocessing",node(rs,"preprocessing_jsonb"));return new JsonNode[]{node(rs,"training_policy_jsonb"),detail};}
    private EligibleSample sample(ResultSet rs,int n)throws java.sql.SQLException{return new EligibleSample(rs.getObject("eligibility_id",UUID.class),node(rs,"evidence_jsonb"),node(rs,"warnings_jsonb"),rs.getObject("revision_id",UUID.class),rs.getLong("revision_no"),node(rs,"composition_jsonb"),node(rs,"process_jsonb"),node(rs,"conditions_jsonb"),node(rs,"observations_jsonb"),node(rs,"facts_jsonb"),node(rs,"source_coordinates_jsonb"),rs.getString("content_hash"),rs.getObject("sample_id",UUID.class),rs.getString("logical_sample_key"),rs.getString("authority_source_type"),rs.getObject("source_id",UUID.class),rs.getString("source_type"),node(rs,"source_group_keys_jsonb"),rs.getString("source_business_key"),rs.getString("physical_identity_hash"),rs.getString("source_version"));}
    private String jobSelect(){return "SELECT j.*,t.name target_name,t.performance_project category,tv.value_type,(SELECT id FROM ai.model_version m WHERE m.organization_id=j.organization_id AND m.training_job_id=j.id ORDER BY m.created_at DESC LIMIT 1) candidate_model_id FROM ai.training_job j JOIN ai.prediction_target t ON t.organization_id=j.organization_id AND t.id=j.target_id JOIN ai.target_version tv ON tv.organization_id=j.organization_id AND tv.id=j.target_version_id";}
    private TrainingJobSummary jobSummary(ResultSet rs,int n)throws java.sql.SQLException{return new TrainingJobSummary(rs.getObject("id",UUID.class),rs.getObject("target_id",UUID.class),rs.getString("target_name"),rs.getString("category"),rs.getString("value_type"),rs.getString("status"),rs.getString("current_stage"),rs.getInt("progress"),rs.getInt("attempt_count"),rs.getInt("max_attempts"),rs.getObject("training_snapshot_id",UUID.class),rs.getObject("candidate_model_id",UUID.class),rs.getString("last_error_code"),rs.getString("last_error_message"),instant(rs,"created_at"),instant(rs,"started_at"),instant(rs,"finished_at"),rs.getLong("revision"));}
    private SnapshotSummary snapshotSummary(ResultSet rs,int n)throws java.sql.SQLException{return new SnapshotSummary(rs.getObject("id",UUID.class),rs.getObject("target_id",UUID.class),rs.getObject("target_version_id",UUID.class),rs.getObject("input_scheme_id",UUID.class),rs.getObject("eligibility_run_id",UUID.class),rs.getInt("sample_count"),rs.getString("data_nature"),rs.getString("snapshot_hash"),rs.getString("business_fingerprint"),instant(rs,"frozen_at"));}
    private String modelSelect(){return "SELECT m.*,t.name target_name,t.performance_project category,tv.value_type,s.sample_count FROM ai.model_version m JOIN ai.prediction_target t ON t.organization_id=m.organization_id AND t.id=m.target_id JOIN ai.target_version tv ON tv.organization_id=m.organization_id AND tv.id=m.target_version_id JOIN ai.training_snapshot s ON s.organization_id=m.organization_id AND s.id=m.training_snapshot_id";}
    private ModelSummary modelSummary(ResultSet rs,int n)throws java.sql.SQLException{return new ModelSummary(rs.getObject("id",UUID.class),rs.getObject("target_id",UUID.class),rs.getString("target_name"),rs.getString("category"),rs.getString("value_type"),rs.getInt("version_no"),rs.getString("status"),rs.getString("model_type"),rs.getString("data_nature"),rs.getBoolean("production_eligible"),rs.getString("comparison_status"),node(rs,"metrics_jsonb"),rs.getInt("sample_count"),rs.getObject("training_job_id",UUID.class),instant(rs,"created_at"),instant(rs,"updated_at"),rs.getLong("revision"));}
    private Instant instant(ResultSet rs,String column)throws java.sql.SQLException{var t=rs.getTimestamp(column);return t==null?null:t.toInstant();}
    private List<String> strings(JsonNode n){var out=new ArrayList<String>();if(n!=null&&n.isArray())n.forEach(x->out.add(x.asText()));return out;}

    public record CandidateTarget(UUID targetId,String targetName,String category,UUID targetVersionId,int targetVersionNo,String valueType,String unit,JsonNode classes,JsonNode definition,JsonNode observationSemantics,String targetHash,UUID inputSchemeId,int inputSchemeVersion,String schemeHash,JsonNode preprocessing,UUID dictionaryId,UUID policyId,int policyVersion,String policyHash,JsonNode qualification,JsonNode validation,JsonNode training,UUID eligibilityRunId,String eligibilityFingerprint,long totalSamples,long trainableSamples,Instant eligibilityFinishedAt,long factHighWatermark){}
    public record DictionaryRow(UUID id,int version,String code,JsonNode vocabulary,JsonNode encoder,String hash){}
    public record FrozenConfig(List<JsonNode> fields,DictionaryRow dictionary,JsonNode mappings){}
    public record EligibleSample(UUID eligibilityId,JsonNode evidence,JsonNode warnings,UUID revisionId,long revisionNo,JsonNode composition,JsonNode process,JsonNode conditions,JsonNode observations,JsonNode facts,JsonNode sourceCoordinates,String contentHash,UUID sampleId,String logicalKey,String authoritySourceType,UUID sourceId,String sourceType,JsonNode sourceGroupKeys,String sourceBusinessKey,String physicalIdentityHash,String sourceVersion){}
}
