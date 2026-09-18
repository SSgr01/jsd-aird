package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class FeedbackService {
    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);
    private final JdbcTemplate jdbc;
    private final OptimizationRepository runs;
    private final ObjectMapper json;
    private final ConfigurationHashing hashing;
    private final AuthorizationService authorization;

    public FeedbackService(JdbcTemplate jdbc,OptimizationRepository runs,ObjectMapper json,
                           ConfigurationHashing hashing,AuthorizationService authorization){
        this.jdbc=jdbc;this.runs=runs;this.json=json;this.hashing=hashing;this.authorization=authorization;
    }

    public JsonNode forRun(UUID runId){
        var a=ActorContext.required();authorization.require(new PermissionCheck(a.organizationId(),a.userId(),"ai.experiment.optimize","AI_RESEARCH_RUN",runId,"READ"));
        runs.byId(a.organizationId(),a.userId(),runId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        reconcile(a.organizationId(),runId);
        var root=json.createObjectNode();var candidates=root.putArray("candidates");
        jdbc.query("""
            SELECT l.research_candidate_id,l.experiment_id,l.experiment_version_id,e.experiment_no,e.status,
                   f.target_id,pt.name target_name,f.comparison_status,f.reason_code,f.predicted_result_jsonb,
                   f.observed_result_jsonb,f.metrics_jsonb,f.created_at
            FROM ai.research_experiment_link_v2 l JOIN rnd.experiment e
              ON e.organization_id=l.organization_id AND e.id=l.experiment_id
            LEFT JOIN ai.model_feedback f ON f.organization_id=l.organization_id
              AND f.research_candidate_id=l.research_candidate_id AND f.experiment_version_id=e.current_version_id
            LEFT JOIN ai.prediction_target pt ON pt.organization_id=f.organization_id AND pt.id=f.target_id
            WHERE l.organization_id=? AND l.research_run_id=? ORDER BY l.created_at,f.created_at,f.id
            """,rs->{var n=candidates.addObject().put("candidateId",rs.getObject("research_candidate_id",UUID.class).toString()).put("experimentId",rs.getObject("experiment_id",UUID.class).toString()).put("experimentVersionId",rs.getObject("experiment_version_id",UUID.class).toString()).put("experimentNo",rs.getString("experiment_no")).put("experimentStatus",rs.getString("status"));var tid=rs.getObject("target_id",UUID.class);if(tid!=null){n.put("targetId",tid.toString()).put("targetName",rs.getString("target_name")).put("comparisonStatus",rs.getString("comparison_status"));if(rs.getString("reason_code")!=null)n.put("reasonCode",rs.getString("reason_code"));n.set("predicted",read(rs.getString("predicted_result_jsonb")));n.set("observed",read(rs.getString("observed_result_jsonb")));n.set("metrics",read(rs.getString("metrics_jsonb")));}},a.organizationId(),runId);
        root.put("feedbackCount",candidates.size());return root;
    }

    @Scheduled(fixedDelayString="${JSD_AIRD_R09_FEEDBACK_RECONCILE_MS:300000}")
    public void scheduled(){
        var pending=jdbc.query("""
            SELECT DISTINCT l.organization_id,l.research_run_id FROM ai.research_experiment_link_v2 l
            JOIN rnd.experiment e ON e.organization_id=l.organization_id AND e.id=l.experiment_id
            WHERE e.status='COMPLETED' ORDER BY l.organization_id,l.research_run_id LIMIT 200
            """,(r,n)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)});
        pending.forEach(ids->{try{reconcile(ids[0],ids[1]);}catch(Exception ignored){}});
    }

    @Transactional
    public int reconcile(UUID org,UUID runId){
        var completed=jdbc.query("""
            SELECT l.research_candidate_id,e.id experiment_id,e.current_version_id,v.edit_model_jsonb,
                   c.target_results_jsonb,r.model_bindings_jsonb,o.id event_id,o.event_type,o.payload_jsonb
            FROM ai.research_experiment_link_v2 l
            JOIN rnd.experiment e ON e.organization_id=l.organization_id AND e.id=l.experiment_id AND e.status='COMPLETED'
            JOIN rnd.experiment_version v ON v.organization_id=e.organization_id AND v.id=e.current_version_id AND v.status='COMPLETED'
            JOIN ai.research_candidate_v2 c ON c.organization_id=l.organization_id AND c.id=l.research_candidate_id
            JOIN ai.research_run_v2 r ON r.organization_id=l.organization_id AND r.id=l.research_run_id
            JOIN LATERAL (SELECT x.* FROM rnd.experiment_outbox x WHERE x.organization_id=e.organization_id
                AND x.aggregate_id=e.id AND x.event_type IN ('experiment.published.v1','experiment.version.published.v1')
                AND x.payload_jsonb->>'versionId'=e.current_version_id::text ORDER BY x.created_at DESC LIMIT 1) o ON true
            LEFT JOIN ai.model_feedback_projection_receipt receipt ON receipt.organization_id=o.organization_id AND receipt.experiment_event_id=o.id
            WHERE l.organization_id=? AND l.research_run_id=?
              AND (receipt.id IS NULL OR receipt.status='FAILED'
                   OR (receipt.status='PROCESSING' AND receipt.updated_at < now() - interval '5 minutes'))
            """,(r,n)->new Pending(r.getObject("research_candidate_id",UUID.class),r.getObject("experiment_id",UUID.class),r.getObject("current_version_id",UUID.class),read(r.getString("edit_model_jsonb")),read(r.getString("target_results_jsonb")),read(r.getString("model_bindings_jsonb")),r.getObject("event_id",UUID.class),r.getString("event_type"),read(r.getString("payload_jsonb"))),org,runId);
        var count=0;for(var p:completed)count+=project(org,p);return count;
    }

    private int project(UUID org,Pending p){
        var sampleRevision=jdbc.query("""
            SELECT s.current_sample_revision_id FROM ai.training_sample s
            WHERE s.organization_id=? AND s.takeover_experiment_id=? AND s.takeover_experiment_version_id=?
              AND s.current_sample_revision_id IS NOT NULL ORDER BY s.updated_at DESC LIMIT 1
            """,(r,n)->r.getObject(1,UUID.class),org,p.experimentId(),p.versionId()).stream().findFirst().orElse(null);
        if(sampleRevision==null)return 0;
        var receiptId=UUID.randomUUID();var payloadHash=hashing.hash(p.eventPayload());
        var inserted=jdbc.update("INSERT INTO ai.model_feedback_projection_receipt(id,organization_id,experiment_event_id,experiment_id,experiment_version_id,event_type,payload_hash,status) VALUES(?,?,?,?,?,?,?,'PROCESSING') ON CONFLICT(organization_id,experiment_event_id) DO UPDATE SET status='PROCESSING',error_message=NULL,attempt_count=ai.model_feedback_projection_receipt.attempt_count+1,updated_at=now() WHERE ai.model_feedback_projection_receipt.status='FAILED' OR (ai.model_feedback_projection_receipt.status='PROCESSING' AND ai.model_feedback_projection_receipt.updated_at < now() - interval '5 minutes')",receiptId,org,p.eventId(),p.experimentId(),p.versionId(),p.eventType(),payloadHash);
        if(inserted==0)return 0;
        try{
            var models=modelBindings(org,p.bindings());var feedbackCount=0;
            for(var model:models){var predicted=findPrediction(p.results(),model.targetId(),model.targetVersionId());if(predicted==null)continue;var observed=findObservation(p.editModel().path("testResults"),model.targetName(),model.targetCode());var status="COMPARABLE";String reason=null;var observedNode=json.createObjectNode();var metrics=json.createObjectNode();
                if(observed==null){status="EXCLUDED";reason="FEEDBACK_OBSERVATION_MISSING";}else{observedNode.setAll((ObjectNode)observed.deepCopy());metrics=metrics(model.valueType(),predicted.path("result"),observed);if(metrics.path("excludedReason").isTextual()){status="EXCLUDED";reason=metrics.path("excludedReason").asText();}}
                var mapping=jdbc.query("SELECT id FROM ai.source_mapping_version WHERE organization_id=? AND target_id=? AND source_type='EXPERIMENT' AND status='PUBLISHED' ORDER BY version_no DESC LIMIT 1",(r,n)->r.getObject(1,UUID.class),org,model.targetId()).stream().findFirst().orElse(null);
                if(mapping==null){
                    // The same published target mapping is valid for an experiment observation
                    // when the observation itself carries the target semantic.  An EXPERIMENT
                    // source mapping is optional; falling back to the target's published
                    // mapping must not turn an otherwise matching observation into an
                    // artificial semantic mismatch.
                    mapping=jdbc.query("SELECT id FROM ai.source_mapping_version WHERE organization_id=? AND target_id=? AND status='PUBLISHED' ORDER BY version_no DESC LIMIT 1",(r,n)->r.getObject(1,UUID.class),org,model.targetId()).stream().findFirst().orElse(null);
                }
                if(mapping==null)continue;
                var hash=hashing.hash(List.of(p.candidateId(),p.versionId(),model.targetId(),predicted,observedNode));
                jdbc.update("""
                    INSERT INTO ai.model_feedback(id,organization_id,model_version_id,target_id,experiment_id,
                      experiment_version_id,research_candidate_id,predicted_result_jsonb,observed_result_jsonb,
                      residual_jsonb,status,content_hash,created_by,target_version_id,source_mapping_version_id,
                      sample_revision_id,comparison_status,reason_code,observation_refs_jsonb,metrics_jsonb,
                      prediction_evidence_jsonb,projection_event_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'00000000-0000-0000-0000-000000000002',?,?,?,?,?,'[]'::jsonb,?,?,?)
                    ON CONFLICT(organization_id,target_id,experiment_version_id,content_hash) DO NOTHING
                    """,UUID.randomUUID(),org,model.modelId(),model.targetId(),p.experimentId(),p.versionId(),p.candidateId(),pg(predicted),pg(observedNode),pg(metrics),"EXCLUDED".equals(status)?"EXCLUDED":"QUALIFIED",hash,model.targetVersionId(),mapping,sampleRevision,status,reason,pg(metrics),pg(predicted),p.eventId());feedbackCount++;
            }
            jdbc.update("UPDATE ai.model_feedback_projection_receipt SET status='COMPLETED',feedback_count=?,processed_at=now(),updated_at=now() WHERE organization_id=? AND id=?",feedbackCount,org,receiptId);return feedbackCount;
        }catch(Exception e){jdbc.update("UPDATE ai.model_feedback_projection_receipt SET status='FAILED',error_message=?,attempt_count=attempt_count+1,updated_at=now() WHERE organization_id=? AND id=?",String.valueOf(e.getMessage()),org,receiptId);throw e;}
    }

    private List<Model> modelBindings(UUID org,JsonNode bindings){var values=new ArrayList<UUID>();bindings.fields().forEachRemaining(e->{try{values.add(UUID.fromString(e.getValue().asText()));}catch(Exception ignored){}});if(values.isEmpty())return List.of();var placeholders=String.join(",",values.stream().map(v->"?").toList());var args=new ArrayList<Object>();args.add(org);args.addAll(values);return jdbc.query("SELECT m.id model_id,m.target_id,m.target_version_id,pt.name target_name,pt.target_code target_code,tv.value_type FROM ai.model_version m JOIN ai.prediction_target pt ON pt.organization_id=m.organization_id AND pt.id=m.target_id JOIN ai.target_version tv ON tv.organization_id=m.organization_id AND tv.id=m.target_version_id WHERE m.organization_id=? AND m.id IN ("+placeholders+")",(r,n)->new Model(r.getObject("model_id",UUID.class),r.getObject("target_id",UUID.class),r.getObject("target_version_id",UUID.class),r.getString("target_name"),r.getString("target_code"),r.getString("value_type")),args.toArray());}
    private JsonNode findPrediction(JsonNode results,UUID target,UUID version){var items=results.path("targets").isArray()?results.path("targets"):results;if(items.isArray())for(var item:items){var id=item.path("targetId").asText();if(target.toString().equals(id)||version.toString().equals(id))return item;}return null;}
    private JsonNode findObservation(JsonNode values,String name,String code){if(!values.isArray())return null;JsonNode found=null;for(var item:values){
        // Experiment workbench payloads use targetName/targetCode for typed result rows,
        // while legacy/manual rows use testItem/name/fieldName.  Resolve both forms so
        // feedback projection compares the actual observation instead of silently
        // recording FEEDBACK_OBSERVATION_MISSING.
        var label=first(item,"targetName","targetCode","testItem","name","fieldName","code","item");
        log.info("Feedback observation match label={} targetName={} targetCode={} row={}", label, name, code, item);
        if(normalize(label).equals(normalize(name))||normalize(label).equals(normalize(code))){if(found!=null)return null;found=item;}}
        return found;}
    private ObjectNode metrics(String type,JsonNode predicted,JsonNode observed){var m=json.createObjectNode();var raw=first(observed,"value","rawValue","result","parsedValue");if(raw==null||raw.isBlank())return m.put("excludedReason","FEEDBACK_OBSERVATION_MISSING");if("CONTINUOUS".equals(type)){try{var actual=Double.parseDouble(raw.replaceAll("[^0-9.+-]",""));var p=predicted.path("value").asDouble();return m.put("actual",actual).put("predicted",p).put("residual",actual-p).put("absoluteError",Math.abs(actual-p));}catch(Exception e){return m.put("excludedReason","FEEDBACK_SEMANTIC_MISMATCH");}}var label=predicted.path("label").asText();m.put("actualLabel",raw).put("predictedLabel",label).put("matched",normalize(raw).equals(normalize(label)));if("ORDINAL".equals(type))m.put("gradeDistance",normalize(raw).equals(normalize(label))?0:1);if(predicted.has("probability"))m.put("predictedProbability",predicted.path("probability").asDouble());return m;}
    private String first(JsonNode n,String...names){for(var name:names){var v=n.path(name);if(v.isValueNode()&&!v.asText().isBlank())return v.asText();}return null;}
    private String normalize(String v){return v==null?"":v.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-/]+","");}
    private JsonNode read(String v){try{return v==null?json.createObjectNode():json.readTree(v);}catch(Exception e){return json.createObjectNode();}}
    private PGobject pg(JsonNode n){try{var p=new PGobject();p.setType("jsonb");p.setValue(n==null?"{}":n.toString());return p;}catch(Exception e){throw new IllegalArgumentException(e);}}
    private record Pending(UUID candidateId,UUID experimentId,UUID versionId,JsonNode editModel,JsonNode results,JsonNode bindings,UUID eventId,String eventType,JsonNode eventPayload){}
    private record Model(UUID modelId,UUID targetId,UUID targetVersionId,String targetName,String targetCode,String valueType){}
}

