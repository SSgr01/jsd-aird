package com.jsd.aird.ai.rnd.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.formula.infrastructure.ParquetArtifactEncoder;
import com.jsd.aird.ai.formula.infrastructure.ParquetArtifactEncoder.Column;
import com.jsd.aird.ops.application.port.AsyncJobHandler;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TrainingJobHandler implements AsyncJobHandler {
    private static final String TYPE="AI_MODEL_TRAIN_V2";
    private final TrainingRepository repository;
    private final FormulaModelArtifactStore artifacts;
    private final ParquetArtifactEncoder parquet;
    private final FormulaModelV2Client compute;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public TrainingJobHandler(TrainingRepository repository,FormulaModelArtifactStore artifacts,
                              ParquetArtifactEncoder parquet,FormulaModelV2Client compute,
                              ObjectMapper json,PlatformTransactionManager transactionManager){this.repository=repository;this.artifacts=artifacts;this.parquet=parquet;this.compute=compute;this.json=json;this.transactions=new TransactionTemplate(transactionManager);}
    @Override public boolean supports(String jobType){return TYPE.equals(jobType);}
    @Override public JsonNode handle(JsonNode payload){throw new IllegalStateException("训练任务需要租约执行上下文");}

    @Override
    public JsonNode handle(JsonNode payload,ExecutionContext context){
        var org=UUID.fromString(payload.path("organizationId").asText());
        var jobId=UUID.fromString(payload.path("trainingJobId").asText());
        var snapshotId=UUID.fromString(payload.path("snapshotId").asText());
        var attemptId=UUID.randomUUID();
        begin(org,jobId,attemptId,context,payload);
        try{
            checkLease(org,jobId,attemptId,context);
            stage(org,jobId,attemptId,context,10,"MATERIALIZING");
            var snapshot=repository.snapshot(org,snapshotId);
            var items=repository.snapshotItems(org,snapshotId,1,Integer.MAX_VALUE);
            var prefix=repository.jdbc().queryForObject("SELECT object_prefix FROM ai.training_snapshot WHERE organization_id=? AND id=?",String.class,org,snapshotId);
            var attemptPrefix=prefix+"attempt-"+attemptId+"/";
            var rows=new ArrayList<Map<String,Object>>();var sources=new ArrayList<Map<String,Object>>();
            for(var item:items){var row=item.row();var value=row.path("targetValue");var valueType=row.path("targetValueType").asText();var data=new LinkedHashMap<String,Object>();data.put("row_id",row.path("rowId").asText());data.put("formula_json",jsonString(row.path("formula")));data.put("inputs_json",jsonString(row.path("inputs")));data.put("target_numeric","CONTINUOUS".equals(valueType)?value.asDouble():null);data.put("target_label","CONTINUOUS".equals(valueType)?null:value.asText());rows.add(data);var group=item.validationGroups();var source=new LinkedHashMap<String,Object>();source.put("row_id",row.path("rowId").asText());source.put("sample_id",row.path("sampleId").asText());source.put("formula_lineage",group.path("formulaLineage").asText());source.put("source_context",group.path("sourceContext").asText());source.put("content_hash",item.sourceReferences().path("contentHash").asText());sources.add(source);}
            // Snapshot artifacts are immutable and retries must reuse the
            // original bytes.  Parquet writers can produce different binary
            // metadata on each invocation even when the rows are identical;
            // attempting to put them again would incorrectly turn a retry
            // into RESOURCE_CONFLICT.
            var rowsArtifact=existingArtifact(org,snapshotId,"SNAPSHOT_ROWS");
            if(rowsArtifact==null)rowsArtifact=artifacts.put(attemptPrefix+"rows.parquet",parquet.encode("TrainingRows",List.of(Column.string("row_id"),Column.string("formula_json"),Column.string("inputs_json"),Column.nullableNumber("target_numeric"),Column.nullableString("target_label")),rows),"application/vnd.apache.parquet");
            var sourceArtifact=existingArtifact(org,snapshotId,"SOURCE_MAP");
            if(sourceArtifact==null)sourceArtifact=artifacts.put(attemptPrefix+"source-map.parquet",parquet.encode("TrainingSources",List.of(Column.string("row_id"),Column.string("sample_id"),Column.string("formula_lineage"),Column.string("source_context"),Column.string("content_hash")),sources),"application/vnd.apache.parquet");
            var manifestArtifact=existingArtifact(org,snapshotId,"SNAPSHOT_MANIFEST");
            if(manifestArtifact==null){var manifest=snapshot.manifest().deepCopy();((ObjectNode)manifest).put("rowsSha256",rowsArtifact.sha256()).put("sourceMapSha256",sourceArtifact.sha256());manifestArtifact=artifacts.put(attemptPrefix+"manifest.json",json.writeValueAsBytes(manifest),"application/json");}
            registerArtifact(org,snapshotId,attemptId,"SNAPSHOT_ROWS",rowsArtifact);registerArtifact(org,snapshotId,attemptId,"SOURCE_MAP",sourceArtifact);registerArtifact(org,snapshotId,attemptId,"SNAPSHOT_MANIFEST",manifestArtifact);
            checkLease(org,jobId,attemptId,context);stage(org,jobId,attemptId,context,30,"SNAPSHOT_VALIDATING");
            var base=requestBase(jobId,snapshot,manifestArtifact,rowsArtifact,sourceArtifact);
            var validated=compute.validate(base);
            if(!"VALID".equals(validated.path("status").asText()))throw new ApiException(ApiErrorCode.AI_MODEL_ARTIFACT_INVALID,"训练快照校验未通过",validated.path("issues"));
            stage(org,jobId,attemptId,context,45,"FOLDING");
            var foldRequest=base.deepCopy();((ObjectNode)foldRequest).put("foldCount",snapshot.trainingPolicy().path("config").path("foldCount").asInt());foldRequest.set("groupFields",json.createArrayNode().add("formula_lineage").add("source_context"));
            var foldResponse=compute.folds(foldRequest);var foldDocument=json.createObjectNode().set("assignments",foldResponse.path("assignments"));var foldArtifact=artifacts.put(attemptPrefix+"validation-folds.json",json.writeValueAsBytes(foldDocument),"application/json");registerArtifact(org,snapshotId,attemptId,"VALIDATION_FOLDS",foldArtifact);
            checkLease(org,jobId,attemptId,context);stage(org,jobId,attemptId,context,60,"TRAINING");
            var modelKey=attemptPrefix+"model-bundle.zip";var train=base.deepCopy();((ObjectNode)train).put("jobId",jobId.toString());train.set("validationFolds",artifactRef(foldArtifact));train.put("output",artifacts.writeRef(modelKey,"application/zip").url());
            var trained=compute.train(train);
            checkLease(org,jobId,attemptId,context);stage(org,jobId,attemptId,context,88,"VALIDATING");
            if("CANDIDATE".equals(trained.path("status").asText())){
                var expected=trained.path("modelBundle").path("sha256").asText();var modelArtifact=artifacts.verify(modelKey,expected,"application/zip");var artifactId=registerArtifact(org,snapshotId,attemptId,"MODEL_BUNDLE",modelArtifact);
                if(!passes(snapshot.trainingPolicy().path("config"),trained.path("metrics"))){var rejected=((ObjectNode)trained).deepCopy();rejected.put("status","REJECTED");rejected.set("reasons",json.createArrayNode().add("MODEL_QUALITY_GATE_REJECTED"));var modelId=registerModel(org,jobId,snapshot,artifactId,rejected,true);reject(org,jobId,attemptId,context,modelId,rejected);return json.createObjectNode().put("trainingJobId",jobId.toString()).put("modelVersionId",modelId.toString()).put("status","REJECTED");}
                var modelId=registerModel(org,jobId,snapshot,artifactId,trained,false);complete(org,jobId,attemptId,context,modelId,trained);return json.createObjectNode().put("trainingJobId",jobId.toString()).put("modelVersionId",modelId.toString()).put("status","CANDIDATE");
            }
            var modelId=registerModel(org,jobId,snapshot,null,trained,true);reject(org,jobId,attemptId,context,modelId,trained);return json.createObjectNode().put("trainingJobId",jobId.toString()).put("modelVersionId",modelId.toString()).put("status","REJECTED");
        }catch(Exception exception){interrupt(org,jobId,attemptId,context,exception);throw exception instanceof RuntimeException r?r:new IllegalStateException(exception);}
    }

    @Override public boolean isRetryable(Exception exception){return exception instanceof FormulaModelV2Client.ComputeTransportException||exception instanceof ApiException api&&api.errorCode()==ApiErrorCode.FILE_NOT_READY||exception instanceof DataIntegrityViolationException conflict&&String.valueOf(conflict.getMostSpecificCause().getMessage()).contains("uq_training_job_running_organization");}
    @Override public void handleTerminalFailure(JsonNode payload,Exception exception){var org=UUID.fromString(payload.path("organizationId").asText());var job=UUID.fromString(payload.path("trainingJobId").asText());repository.jdbc().update("UPDATE ai.training_job SET status=CASE WHEN status='CANCELLED' THEN status ELSE 'FAILED' END,current_stage=CASE WHEN status='CANCELLED' THEN current_stage ELSE 'FAILED' END,last_error_code=?,last_error_message=?,error_jsonb=?::jsonb,finished_at=coalesce(finished_at,now()),revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND status<>'SUCCEEDED'",code(exception),exception.getMessage(),error(exception).toString(),org,job);}

    private void begin(UUID org,UUID job,UUID attempt,ExecutionContext context,JsonNode payload){transactions.executeWithoutResult(ignored->{var previous=repository.jdbc().query("SELECT active_attempt_id FROM ai.training_job WHERE organization_id=? AND id=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),org,job).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);if(previous!=null)repository.jdbc().update("UPDATE ai.training_job_attempt SET status='STALE',current_stage='STALE',finished_at=now() WHERE organization_id=? AND id=? AND status='RUNNING'",org,previous);var updated=repository.jdbc().update("""
            UPDATE ai.training_job SET status='MATERIALIZING',current_stage='MATERIALIZING',progress=5,
                attempt_count=attempt_count+1,fencing_generation=?,started_at=coalesce(started_at,now()),updated_at=now()
            WHERE organization_id=? AND id=? AND status IN ('QUEUED','MATERIALIZING','SNAPSHOT_VALIDATING','FOLDING','TRAINING','VALIDATING')
            """,context.leaseGeneration(),org,job);if(updated!=1)throw new ApiException(ApiErrorCode.STALE_TRAINING_ATTEMPT);var attemptNo=repository.jdbc().queryForObject("SELECT attempt_count FROM ai.training_job WHERE organization_id=? AND id=?",Integer.class,org,job);repository.jdbc().update("""
            INSERT INTO ai.training_job_attempt(id,organization_id,training_job_id,attempt_no,status,worker_id,
                frozen_request_jsonb,request_hash,lease_token,fencing_generation,heartbeat_at,current_stage,progress)
            VALUES(?,?,?,?, 'RUNNING',?,?,?,?,?,now(),'MATERIALIZING',5)
            """,attempt,org,job,attemptNo,"worker:"+context.jobId(),repository.pg(payload),hash(payload),context.leaseToken(),context.leaseGeneration());repository.jdbc().update("UPDATE ai.training_job SET active_attempt_id=? WHERE organization_id=? AND id=? AND fencing_generation=?",attempt,org,job,context.leaseGeneration());});}
    private void checkLease(UUID org,UUID job,UUID attempt,ExecutionContext context){if(context.cancelled()){repository.jdbc().update("UPDATE ai.training_job SET status='CANCELLED',current_stage='CANCELLED',finished_at=now(),updated_at=now() WHERE organization_id=? AND id=?",org,job);repository.jdbc().update("UPDATE ai.training_job_attempt SET status='CANCELLED',finished_at=now(),current_stage='CANCELLED' WHERE organization_id=? AND id=? AND status='RUNNING'",org,attempt);throw new ApiException(ApiErrorCode.STALE_TRAINING_ATTEMPT,"任务已取消");}Long valid=repository.jdbc().queryForObject("""
            SELECT count(*) FROM ops.async_job o JOIN ai.training_job j ON j.organization_id=o.organization_id AND j.ops_job_id=o.id
            WHERE j.organization_id=? AND j.id=? AND j.active_attempt_id=? AND j.fencing_generation=?
              AND o.id=? AND o.status='RUNNING' AND o.lease_token=? AND o.lease_generation=?
            """,Long.class,org,job,attempt,context.leaseGeneration(),context.jobId(),context.leaseToken(),context.leaseGeneration());if(valid==null||valid!=1)throw new ApiException(ApiErrorCode.STALE_TRAINING_ATTEMPT);}
    private void stage(UUID org,UUID job,UUID attempt,ExecutionContext context,int progress,String stage){checkLease(org,job,attempt,context);repository.jdbc().update("UPDATE ai.training_job SET status=?,current_stage=?,progress=?,updated_at=now() WHERE organization_id=? AND id=? AND active_attempt_id=? AND fencing_generation=?",stage,stage,progress,org,job,attempt,context.leaseGeneration());repository.jdbc().update("UPDATE ai.training_job_attempt SET current_stage=?,progress=?,heartbeat_at=now() WHERE organization_id=? AND id=? AND status='RUNNING' AND fencing_generation=?",stage,progress,org,attempt,context.leaseGeneration());repository.jdbc().update("UPDATE ops.async_job SET progress=?,current_stage=?,updated_at=now() WHERE organization_id=? AND id=? AND status='RUNNING' AND lease_token=? AND lease_generation=?",progress,stage,org,context.jobId(),context.leaseToken(),context.leaseGeneration());}
    private ObjectNode requestBase(UUID job,TrainingContracts.SnapshotDetail snapshot,FormulaModelArtifactStore.StoredArtifact manifest,FormulaModelArtifactStore.StoredArtifact rows,FormulaModelArtifactStore.StoredArtifact source){var request=json.createObjectNode().put("contractVersion","formula-model.v2").put("requestId",job.toString()).put("seed",snapshot.trainingPolicy().path("config").path("seed").asLong());var ref=json.createObjectNode().put("id",snapshot.summary().id().toString()).put("version",1).put("sha256",snapshot.summary().snapshotHash());ref.set("manifest",artifactRef(manifest));ref.set("rows",artifactRef(rows));ref.set("sourceMap",artifactRef(source));request.set("snapshot",ref);var context=json.createObjectNode();context.set("targetDefinition",snapshot.targetDefinition());context.set("inputScheme",snapshot.inputScheme());context.set("materialDictionary",snapshot.materialDictionary());context.set("preprocessing",snapshot.preprocessing());context.set("trainingPolicy",snapshot.trainingPolicy());request.set("context",context);return request;}
    private ObjectNode artifactRef(FormulaModelArtifactStore.StoredArtifact a){var ref=artifacts.readRef(a.objectKey(),a.objectKey(),a.sha256());return json.createObjectNode().put("url",ref.url()).put("sha256",ref.sha256());}
    private UUID registerArtifact(UUID org,UUID snapshot,UUID attempt,String type,FormulaModelArtifactStore.StoredArtifact a){var id=repository.jdbc().query("SELECT id FROM ai.artifact WHERE organization_id=? AND sha256=? AND artifact_type=?",(rs,n)->rs.getObject(1,UUID.class),org,a.sha256(),type).stream().findFirst().orElse(null);if(id!=null)return id;id=UUID.randomUUID();repository.jdbc().update("INSERT INTO ai.artifact(id,organization_id,artifact_type,training_snapshot_id,training_job_attempt_id,object_key,sha256,size_bytes,metadata_jsonb,media_type,content_addressed) VALUES(?,?,?,?,?,?,?,?, '{}'::jsonb,?,true)",id,org,type,snapshot,attempt,a.objectKey(),a.sha256(),a.size(),a.contentType());return id;}
    private FormulaModelArtifactStore.StoredArtifact existingArtifact(UUID org,UUID snapshot,String type){
        var result=repository.jdbc().query("SELECT object_key,sha256,size_bytes,media_type FROM ai.artifact WHERE organization_id=? AND training_snapshot_id=? AND artifact_type=? ORDER BY created_at LIMIT 1",(rs,n)->new FormulaModelArtifactStore.StoredArtifact(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4)),org,snapshot,type).stream().findFirst().orElse(null);
        return result;
    }
    private UUID registerModel(UUID org,UUID job,TrainingContracts.SnapshotDetail snapshot,UUID artifact,JsonNode response,boolean rejected){
        var metrics=response.path("metrics");
        var modelHash=artifact==null?hash(response):response.path("modelBundle").path("sha256").asText();
        // A retry reuses the immutable snapshot and therefore produces the
        // same content-addressed bundle.  Reuse the existing registry row
        // instead of turning a safe retry into a duplicate-key failure.
        var existing=repository.jdbc().query("SELECT id FROM ai.model_version WHERE organization_id=? AND model_hash=?",(rs,n)->rs.getObject(1,UUID.class),org,modelHash).stream().findFirst().orElse(null);
        if(existing!=null)return existing;
        var id=UUID.randomUUID();var next=repository.jdbc().queryForObject("SELECT coalesce(max(version_no),0)+1 FROM ai.model_version WHERE organization_id=? AND target_id=?",Integer.class,org,snapshot.summary().targetId());var selected=metrics.path("selectedAlgorithm").asText(rejected?"REJECTED":"UNKNOWN");var nature=snapshot.summary().dataNature();var activeMetrics=repository.jdbc().query("SELECT metrics_jsonb FROM ai.model_version WHERE organization_id=? AND target_id=? AND status='ACTIVE'",(rs,n)->repository.node(rs,"metrics_jsonb"),org,snapshot.summary().targetId()).stream().findFirst().orElse(null);var comparison=compare(snapshot.trainingPolicy().path("config"),metrics,activeMetrics);var fairRequired=snapshot.trainingPolicy().path("config").path("requireFairComparison").asBoolean();var eligible=!rejected&&"REAL".equals(nature)&&passes(snapshot.trainingPolicy().path("config"),metrics)&&(activeMetrics==null||!fairRequired||"BETTER".equals(comparison));var creator=repository.jdbc().queryForObject("SELECT requested_by FROM ai.training_job WHERE organization_id=? AND id=?",UUID.class,org,job);repository.jdbc().update("""
            INSERT INTO ai.model_version(id,organization_id,target_id,target_version_id,input_scheme_id,training_snapshot_id,
                training_job_id,model_artifact_id,model_type,status,metrics_jsonb,applicability_domain_jsonb,model_card_jsonb,
                contract_version,model_hash,revision,created_by,version_no,data_nature,production_eligible,comparison_status,rejection_reasons_jsonb)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'formula-model.v2',?,0,?,?,?,?,?,?)
            """,id,org,snapshot.summary().targetId(),snapshot.summary().targetVersionId(),snapshot.summary().inputSchemeId(),snapshot.summary().id(),job,artifact,selected,rejected?"REJECTED":"CANDIDATE",repository.pg(metrics),repository.pg(response.path("applicabilityDomain")),repository.pg(json.createObjectNode().put("snapshotId",snapshot.summary().id().toString()).put("trainedAt",Instant.now().toString())),modelHash,creator,next,nature,eligible,comparison,repository.pg(response.path("reasons")));return id;}
    private String compare(JsonNode policy,JsonNode candidate,JsonNode active){if(active==null)return "NOT_COMPARABLE";var set=candidate.path("comparisonSetHash").asText();if(set.isBlank()||!set.equals(active.path("comparisonSetHash").asText()))return "NOT_COMPARABLE";var metric=policy.path("primaryMetric").asText();var cNode=metricNode(candidate,metric);var aNode=metricNode(active,metric);if(cNode==null||aNode==null)return "NOT_COMPARABLE";var c=cNode.asDouble();var a=aNode.asDouble();if(Math.abs(c-a)<1e-12)return "EQUIVALENT";var lower=metric.toLowerCase().matches(".*(mae|rmse|loss|error|width).*");return lower?(c<a?"BETTER":"WORSE"):(c>a?"BETTER":"WORSE");}
    private boolean passes(JsonNode policy,JsonNode metrics){var metric=policy.path("primaryMetric").asText();var valueNode=metricNode(metrics,metric);if(metric.isBlank()||valueNode==null)return false;var value=valueNode.asDouble();var threshold=policy.path("metricThreshold").asDouble();return metric.toLowerCase().matches(".*(mae|rmse|loss|error|width).*")?value<=threshold:value>=threshold;}
    private JsonNode metricNode(JsonNode metrics,String metric){if(metrics==null||metric==null||metric.isBlank())return null;var direct=metrics.path(metric);if(direct.isNumber())return direct;var lower=metric.toLowerCase();var fields=metrics.fields();while(fields.hasNext()){var entry=fields.next();if(entry.getKey().toLowerCase().equals(lower)&&entry.getValue().isNumber())return entry.getValue();}return null;}
    private void complete(UUID org,UUID job,UUID attempt,ExecutionContext c,UUID model,JsonNode response){repository.jdbc().update("UPDATE ai.training_job_attempt SET status='SUCCEEDED',current_stage='SUCCEEDED',progress=100,result_jsonb=?,finished_at=now(),heartbeat_at=now() WHERE organization_id=? AND id=? AND status='RUNNING' AND fencing_generation=?",repository.pg(response),org,attempt,c.leaseGeneration());var updated=repository.jdbc().update("UPDATE ai.training_job SET status='SUCCEEDED',current_stage='SUCCEEDED',progress=100,finished_at=now(),revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND active_attempt_id=? AND fencing_generation=? AND status<>'CANCELLED'",org,job,attempt,c.leaseGeneration());if(updated!=1)throw new ApiException(ApiErrorCode.STALE_TRAINING_ATTEMPT);}
    private void reject(UUID org,UUID job,UUID attempt,ExecutionContext c,UUID model,JsonNode response){repository.jdbc().update("UPDATE ai.training_job_attempt SET status='SUCCEEDED',current_stage='REJECTED',progress=100,result_jsonb=?,finished_at=now() WHERE organization_id=? AND id=? AND status='RUNNING' AND fencing_generation=?",repository.pg(response),org,attempt,c.leaseGeneration());repository.jdbc().update("UPDATE ai.training_job SET status='FAILED',current_stage='FAILED',progress=100,last_error_code='MODEL_QUALITY_GATE_REJECTED',last_error_message='候选模型未通过训练或质量门禁',error_jsonb=?,finished_at=now(),revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND active_attempt_id=? AND fencing_generation=? AND status<>'CANCELLED'",repository.pg(response),org,job,attempt,c.leaseGeneration());}
    private void interrupt(UUID org,UUID job,UUID attempt,ExecutionContext c,Exception e){repository.jdbc().update("UPDATE ai.training_job_attempt SET status='INTERRUPTED',current_stage='INTERRUPTED',error_code=?,error_message=?,finished_at=now() WHERE organization_id=? AND id=? AND status='RUNNING' AND fencing_generation=?",code(e),e.getMessage(),org,attempt,c.leaseGeneration());}
    private String hash(JsonNode n){return com.jsd.aird.ai.formula.application.FormulaModelArtifactStore.sha256(n.toString().getBytes(StandardCharsets.UTF_8));}
    private String jsonString(JsonNode n){try{return json.writeValueAsString(n);}catch(Exception e){throw new IllegalStateException(e);}}
    private String code(Exception e){return e instanceof ApiException a?a.errorCode().code():e.getClass().getSimpleName();}
    private JsonNode error(Exception e){return json.createObjectNode().put("code",code(e)).put("message",String.valueOf(e.getMessage()));}
}

