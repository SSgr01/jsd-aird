package com.jsd.aird.ai.rnd.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.jsd.aird.ai.rnd.training.TrainingContracts.*;
import static com.jsd.aird.ai.rnd.training.TrainingRepository.*;

@Service
public class TrainingService {
    private final TrainingRepository repository;
    private final ConfigurationHashing hashing;
    private final ObjectMapper json;
    private final OpsAsyncFacade async;
    private final AuditLogFacade audit;
    private final FormulaModelArtifactStore artifacts;
    private final boolean allowSyntheticActivation;

    public TrainingService(TrainingRepository repository, ConfigurationHashing hashing, ObjectMapper json,
                           OpsAsyncFacade async, AuditLogFacade audit, FormulaModelArtifactStore artifacts,
                           @Value("${JSD_AIRD_AI_ALLOW_SYNTHETIC_MODEL_ACTIVATION:false}") boolean allowSyntheticActivation) {
        this.repository=repository;this.hashing=hashing;this.json=json;this.async=async;this.audit=audit;this.artifacts=artifacts;this.allowSyntheticActivation=allowSyntheticActivation;
    }

    public TrainingSettings settings(){return repository.settings(actor().organizationId());}

    @Transactional
    public TrainingSettings updateSettings(UpdateTrainingSettings command,String key){
        return idempotent("UPDATE_TRAINING_SETTINGS",key,command,TrainingSettings.class,()->doUpdateSettings(command));
    }

    private TrainingSettings doUpdateSettings(UpdateTrainingSettings command){
        var a=actor();
        if(repository.updateSettings(a.organizationId(),a.userId(),command.autoLearningEnabled(),command.expectedRevision())!=1)
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        audit.append(a.organizationId(),a.userId(),"AI_TRAINING_SETTINGS_UPDATED","AI_TRAINING_SETTINGS",a.organizationId(),json.valueToTree(command));
        return repository.settings(a.organizationId());
    }

    @Transactional
    public EvaluateResult evaluateAll(String key){
        return idempotent("EVALUATE_TRAINING_TARGETS",key,Map.of("scope","ALL_READY_TARGETS"),EvaluateResult.class,this::doEvaluateAll);
    }

    private EvaluateResult doEvaluateAll(){
        var a=actor();var results=new ArrayList<TargetEvaluation>();int created=0,blocked=0;
        for(var target:repository.candidateTargets(a.organizationId())){
            var result=schedule(a.organizationId(),a.userId(),target,false);results.add(result);
            if("QUEUED".equals(result.result()))created++;else blocked++;
        }
        repository.touchEvaluation(a.organizationId());
        audit.append(a.organizationId(),a.userId(),"AI_TRAINING_EVALUATED","AI_TRAINING_SETTINGS",a.organizationId(),json.valueToTree(results));
        return new EvaluateResult(results.size(),created,blocked,List.copyOf(results));
    }

    @Transactional
    public TargetEvaluation scheduleFromEligibility(UUID organizationId, UUID targetId, boolean automatic){
        var target=repository.candidateTarget(organizationId,targetId).orElse(null);
        if(target==null)return new TargetEvaluation(targetId,"","BLOCKED","CONFIG_NOT_READY",null,null);
        var user=repository.jdbc().query("SELECT id FROM iam.app_user WHERE organization_id=? ORDER BY created_at,id LIMIT 1",
                (rs,n)->rs.getObject(1,UUID.class),organizationId).stream().findFirst().orElse(null);
        if(user==null)return new TargetEvaluation(targetId,target.targetName(),"BLOCKED","ACTOR_NOT_AVAILABLE",null,null);
        return schedule(organizationId,user,target,automatic);
    }

    @Transactional
    public TargetEvaluation schedule(UUID org,UUID user,CandidateTarget target,boolean automatic){
        if(target.eligibilityRunId()==null)return blocked(org,target,"ELIGIBILITY_NOT_EVALUATED");
        if(target.dictionaryId()==null)return blocked(org,target,"MATERIAL_DICTIONARY_REQUIRED");
        var policyIssues=TrainingPolicyRules.validate(target.valueType(),target.qualification(),target.validation(),target.training());
        if(!policyIssues.isEmpty())return blocked(org,target,"TRAINING_POLICY_NOT_APPROVED");
        if(automatic&&(!repository.settings(org).autoLearningEnabled()||!target.training().path("autoTrainingEnabled").asBoolean(false)))
            return blocked(org,target,"AUTO_TRAINING_DISABLED");
        var samples=repository.eligibleSamples(org,target.eligibilityRunId());
        var gate=sampleGate(target,samples);if(gate!=null)return blocked(org,target,gate);
        var config=repository.frozenConfig(org,target);if(config.dictionary()==null)return blocked(org,target,"MATERIAL_DICTIONARY_NOT_FROZEN");
        var rows=rows(target,samples);
        var valueHashes=rows.stream().map(SnapshotRow::hash).sorted().toList();
        var seed=target.training().path("seed").asLong();
        var fingerprint=hashing.hash(Map.of("targetHash",target.targetHash(),"schemeHash",target.schemeHash(),
                "policyHash",target.policyHash(),"dictionaryHash",config.dictionary().hash(),
                "eligibilityFingerprint",target.eligibilityFingerprint(),"factHighWatermark",target.factHighWatermark(),
                "seed",seed,"rowHashes",valueHashes));
        var existing=repository.jdbc().query("""
                SELECT j.id,j.training_snapshot_id FROM ai.training_job j JOIN ai.training_snapshot s
                  ON s.organization_id=j.organization_id AND s.id=j.training_snapshot_id
                WHERE j.organization_id=? AND s.business_fingerprint=? ORDER BY j.created_at DESC LIMIT 1
                """,(rs,n)->new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)},org,fingerprint).stream().findFirst();
        if(existing.isPresent())return new TargetEvaluation(target.targetId(),target.targetName(),"EXISTING","SAME_SNAPSHOT",existing.get()[0],existing.get()[1]);
        var running=repository.jdbc().queryForObject("SELECT count(*) FROM ai.training_job WHERE organization_id=? AND target_id=? AND status IN ('QUEUED','MATERIALIZING','SNAPSHOT_VALIDATING','FOLDING','TRAINING','VALIDATING')",Long.class,org,target.targetId());
        if(running!=null&&running>0)return blocked(org,target,"TRAINING_ALREADY_RUNNING");
        var snapshotId=UUID.randomUUID();var jobId=UUID.randomUUID();var objectPrefix="ai/rnd/v2/"+org+"/"+fingerprint+"/";
        var targetDefinition=targetDefinition(org,target);var inputScheme=inputScheme(target,config);var dictionary=materialDictionary(config.dictionary());
        var policy=trainingPolicy(target);var preprocessing=versionedConfig(target.inputSchemeId(),target.inputSchemeVersion(),target.schemeHash(),target.preprocessing());
        var manifest=json.createObjectNode().put("contractVersion","formula-model.v2").put("snapshotHash",fingerprint)
                .put("sampleCount",rows.size()).put("factHighWatermark",target.factHighWatermark()).put("seed",seed);
        manifest.set("rowHashes",json.valueToTree(valueHashes));
        var groups=json.createObjectNode().put("formulaLineageField","formula_lineage").put("sourceContextField","source_context");
        var mappings=config.mappings();var dataNature=target.training().path("dataNature").asText();
        repository.jdbc().update("""
                INSERT INTO ai.training_snapshot(id,organization_id,target_id,target_version_id,input_scheme_id,
                    material_dictionary_version_id,modeling_policy_version_id,eligibility_run_id,status,sample_count,
                    validation_groups_jsonb,manifest_jsonb,snapshot_hash,object_prefix,frozen_by,
                    source_mapping_versions_jsonb,target_definition_jsonb,input_scheme_jsonb,material_dictionary_jsonb,
                    preprocessing_jsonb,training_policy_jsonb,authorization_scope_jsonb,fact_high_watermark,data_nature,business_fingerprint)
                VALUES(?,?,?,?,?,?,?,?, 'FROZEN', ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,snapshotId,org,target.targetId(),target.targetVersionId(),target.inputSchemeId(),target.dictionaryId(),target.policyId(),
                target.eligibilityRunId(),rows.size(),repository.pg(groups),repository.pg(manifest),fingerprint,objectPrefix,user,
                repository.pg(mappings),repository.pg(targetDefinition),repository.pg(inputScheme),repository.pg(dictionary),repository.pg(preprocessing),
                repository.pg(policy),repository.pg(json.createObjectNode().put("organizationId",org.toString())),target.factHighWatermark(),dataNature,fingerprint);
        long ordinal=0;for(var row:rows){repository.jdbc().update("""
                INSERT INTO ai.training_snapshot_item(organization_id,training_snapshot_id,sample_revision_id,
                    eligibility_id,ordinal,split_group,row_hash,row_jsonb,training_sample_id,observation_ids_jsonb,
                    replicate_group_keys_jsonb,source_refs_jsonb,validation_groups_jsonb,value_hash)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,org,snapshotId,row.sample().revisionId(),row.sample().eligibilityId(),ordinal++,row.splitGroup(),row.hash(),
                repository.pg(row.row()),row.sample().sampleId(),repository.pg(row.observationIds()),repository.pg(row.replicateKeys()),
                repository.pg(row.sourceRefs()),repository.pg(row.validationGroups()),row.hash());}
        repository.jdbc().update("""
                INSERT INTO ai.training_job(id,organization_id,training_snapshot_id,status,idempotency_key,request_hash,
                    priority,max_attempts,requested_by,target_id,target_version_id,modeling_policy_version_id,seed,
                    business_key,current_stage,error_jsonb)
                VALUES(?,?,?,'QUEUED',?,?,60,3,?,?,?,?,?,?,'QUEUED','{}'::jsonb)
                """,jobId,org,snapshotId,"ai-rnd-train:"+fingerprint,fingerprint,user,target.targetId(),target.targetVersionId(),target.policyId(),seed,fingerprint);
        repository.jdbc().update("""
                INSERT INTO ai.training_schedule_intent(id,organization_id,target_id,target_version_id,input_scheme_id,
                    eligibility_run_id,configuration_fingerprint,fact_high_watermark,status)
                VALUES(?,?,?,?,?,?,?,?, 'CONSUMED')
                ON CONFLICT(organization_id,target_id) DO UPDATE SET target_version_id=excluded.target_version_id,
                    input_scheme_id=excluded.input_scheme_id,eligibility_run_id=excluded.eligibility_run_id,
                    configuration_fingerprint=excluded.configuration_fingerprint,fact_high_watermark=excluded.fact_high_watermark,
                    status='CONSUMED',block_code=null,block_detail_jsonb='{}'::jsonb,updated_at=now()
                """,UUID.randomUUID(),org,target.targetId(),target.targetVersionId(),target.inputSchemeId(),target.eligibilityRunId(),fingerprint,target.factHighWatermark());
        var payload=json.createObjectNode().put("organizationId",org.toString()).put("trainingJobId",jobId.toString())
                .put("snapshotId",snapshotId.toString()).put("businessKey",fingerprint);
        async.appendOutbox(org,"AI_TRAINING_JOB",jobId,"AI_TRAINING_REQUESTED","training-outbox:"+fingerprint,payload);
        audit.append(org,user,"AI_TRAINING_SNAPSHOT_FROZEN","AI_TRAINING_SNAPSHOT",snapshotId,payload);
        return new TargetEvaluation(target.targetId(),target.targetName(),"QUEUED",null,jobId,snapshotId);
    }

    public PageResponse<TrainingJobSummary> jobs(String status,String keyword,int page,int size){var a=actor();return repository.jobs(a.organizationId(),status,keyword,page,size);}
    public TrainingJobDetail job(UUID id){var a=actor();try{return repository.jobDetail(a.organizationId(),id);}catch(Exception e){throw new ApiException(ApiErrorCode.NOT_FOUND,"训练任务不存在");}}
    public SnapshotDetail snapshot(UUID id){try{return repository.snapshot(actor().organizationId(),id);}catch(Exception e){throw new ApiException(ApiErrorCode.NOT_FOUND,"训练快照不存在");}}
    public List<SnapshotItem> snapshotItems(UUID id,int page,int size){snapshot(id);return repository.snapshotItems(actor().organizationId(),id,page,size);}

    @Transactional
    public JobActionResult retry(UUID id,RetryCommand command,String key){return idempotent("RETRY_TRAINING_JOB:"+id,key,command,JobActionResult.class,()->doRetry(id,command));}

    private JobActionResult doRetry(UUID id,RetryCommand command){var a=actor();var job=repository.job(a.organizationId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"训练任务不存在"));if(job.revision()!=command.expectedRevision())throw new ApiException(ApiErrorCode.VERSION_CONFLICT);if(!"FAILED".equals(job.status()))throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"只有失败任务可以重试");var generation=job.attemptCount()+1;var asyncKey="ai-rnd-train-retry:"+id+":"+generation;var payload=json.createObjectNode().put("organizationId",a.organizationId().toString()).put("trainingJobId",id.toString()).put("snapshotId",job.snapshotId().toString());var ops=async.enqueue(a.organizationId(),"AI_MODEL_TRAIN_V2",payload,asyncKey,60,3);var updated=repository.jdbc().update("UPDATE ai.training_job SET status='QUEUED',current_stage='QUEUED',progress=0,ops_job_id=?,last_error_code=null,last_error_message=null,error_jsonb='{}'::jsonb,revision=revision+1,finished_at=null,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",ops,a.organizationId(),id,job.revision());if(updated!=1)throw new ApiException(ApiErrorCode.VERSION_CONFLICT);audit.append(a.organizationId(),a.userId(),"AI_TRAINING_RETRIED","AI_TRAINING_JOB",id,payload);return new JobActionResult(id,"QUEUED",ops,job.attemptCount(),job.revision()+1);}

    @Transactional
    public JobActionResult cancel(UUID id,CancelCommand command,String key){return idempotent("CANCEL_TRAINING_JOB:"+id,key,command,JobActionResult.class,()->doCancel(id,command));}

    private JobActionResult doCancel(UUID id,CancelCommand command){var a=actor();var job=repository.job(a.organizationId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"训练任务不存在"));if(job.revision()!=command.expectedRevision())throw new ApiException(ApiErrorCode.VERSION_CONFLICT);if(!Set.of("QUEUED","MATERIALIZING","SNAPSHOT_VALIDATING","FOLDING","TRAINING","VALIDATING").contains(job.status()))throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"任务当前状态不能取消");var ops=repository.jdbc().query("SELECT ops_job_id FROM ai.training_job WHERE organization_id=? AND id=?",(rs,n)->rs.getObject(1,UUID.class),a.organizationId(),id).stream().findFirst().orElse(null);if(ops!=null)async.cancel(a.organizationId(),ops);var updated=repository.jdbc().update("UPDATE ai.training_job SET status='CANCELLED',cancellation_requested_at=now(),finished_at=now(),current_stage='CANCELLED',revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",a.organizationId(),id,job.revision());if(updated!=1)throw new ApiException(ApiErrorCode.VERSION_CONFLICT);audit.append(a.organizationId(),a.userId(),"AI_TRAINING_CANCELLED","AI_TRAINING_JOB",id,json.valueToTree(command));return new JobActionResult(id,"CANCELLED",ops,job.attemptCount(),job.revision()+1);}

    public PageResponse<ModelSummary> models(String status,String category,String keyword,int page,int size){var a=actor();return repository.models(a.organizationId(),status,category,keyword,page,size);}
    public ModelDetail model(UUID id){try{return repository.modelDetail(actor().organizationId(),id);}catch(Exception e){throw new ApiException(ApiErrorCode.NOT_FOUND,"模型版本不存在");}}
    public ModelComparison comparison(UUID id){var a=actor();var candidate=repository.model(a.organizationId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"模型版本不存在"));var active=repository.jdbc().query("SELECT id,metrics_jsonb,training_snapshot_id FROM ai.model_version WHERE organization_id=? AND target_id=? AND status='ACTIVE'",(rs,n)->new Object[]{rs.getObject(1,UUID.class),repository.node(rs,"metrics_jsonb"),rs.getObject(3,UUID.class)},a.organizationId(),candidate.targetId()).stream().findFirst().orElse(null);var baseline=candidate.metrics().path("baselines");var reasons=new ArrayList<String>();if(active!=null&&"NOT_COMPARABLE".equals(candidate.comparisonStatus()))reasons.add("当前候选与正式模型没有同一比较集，不能据此判断优劣");return new ModelComparison(id,active==null?null:(UUID)active[0],candidate.comparisonStatus(),active==null||!"NOT_COMPARABLE".equals(candidate.comparisonStatus()),candidate.metrics(),active==null?json.createObjectNode():(JsonNode)active[1],baseline,List.copyOf(reasons));}

    @Transactional
    public ModelActionResult activate(UUID id,ModelActionCommand command,String key){return changeModel(id,command,"ACTIVATE",key);}
    @Transactional
    public ModelActionResult pause(UUID id,ModelActionCommand command,String key){return changeModel(id,command,"PAUSE",key);}
    @Transactional
    public ModelActionResult rollback(UUID id,ModelActionCommand command,String key){return changeModel(id,command,"ROLLBACK",key);}

    private ModelActionResult changeModel(UUID id,ModelActionCommand command,String action,String key){return idempotent(action+"_MODEL:"+id,key,command,ModelActionResult.class,()->doChangeModel(id,command,action,key));}

    private ModelActionResult doChangeModel(UUID id, ModelActionCommand command, String action, String key) {
        var actor = actor();
        var model = repository.model(actor.organizationId(), id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "模型版本不存在"));
        if (model.revision() != command.expectedRevision()) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);

        var active = repository.jdbc().query(
                "SELECT id FROM ai.model_version WHERE organization_id=? AND target_id=? AND status='ACTIVE' FOR UPDATE",
                (rs, n) -> rs.getObject(1, UUID.class), actor.organizationId(), model.targetId())
                .stream().findFirst().orElse(null);
        if (!java.util.Objects.equals(active, command.expectedActiveModelVersionId())) {
            throw new ApiException(ApiErrorCode.MODEL_VERSION_CHANGED);
        }

        if ("PAUSE".equals(action)) {
            if (!id.equals(active)) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "只有使用中的模型可以暂停");
            var updated = repository.jdbc().update(
                    "UPDATE ai.model_version SET status='PAUSED',revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",
                    actor.organizationId(), id, model.revision());
            if (updated != 1) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
            insertRelease(actor, id, active, "PAUSE", command.reason(), key);
            return new ModelActionResult(id, "PAUSED", null, model.revision() + 1, Instant.now());
        }

        if (!model.productionEligible() && !(allowSyntheticActivation && "SYNTHETIC".equals(model.dataNature())))
            throw new ApiException(ApiErrorCode.MODEL_NOT_PRODUCTION_ELIGIBLE);
        if ("ACTIVATE".equals(action) && !"CANDIDATE".equals(model.status())) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "只有候选模型可以启用");
        }
        if ("ROLLBACK".equals(action) && !Set.of("PAUSED", "RETIRED").contains(model.status())) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "只能回退到历史正式模型");
        }

        var artifact = repository.jdbc().query(
                "SELECT a.object_key,a.sha256,coalesce(a.media_type,'application/zip') media_type "
                        + "FROM ai.model_version m JOIN ai.artifact a ON a.organization_id=m.organization_id "
                        + "AND a.id=m.model_artifact_id WHERE m.organization_id=? AND m.id=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, actor.organizationId(), id)
                .stream().findFirst().orElseThrow(() -> new ApiException(ApiErrorCode.MODEL_ARTIFACT_UNAVAILABLE));
        artifacts.verify(artifact[0], artifact[1], artifact[2]);

        var inferencePolicies = repository.jdbc().query("""
                SELECT t.current_quality_policy_version_id,t.current_domain_policy_version_id,p.configuration_jsonb
                FROM ai.prediction_target t LEFT JOIN ai.modeling_policy_version p
                  ON p.organization_id=t.organization_id AND p.id=t.current_domain_policy_version_id
                WHERE t.organization_id=? AND t.id=? FOR UPDATE OF t
                """, (rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                repository.node(rs, "configuration_jsonb")}, actor.organizationId(), model.targetId())
                .stream().findFirst().orElseThrow();
        if (inferencePolicies[0] == null) throw new ApiException(ApiErrorCode.QUALITY_POLICY_NOT_READY);

        // A candidate receives the current published DOMAIN policy exactly once. A rollback
        // reuses the domain binding frozen with that historical model; changing its payload
        // would violate the model immutability trigger.
        if ("ACTIVATE".equals(action)) {
            if (inferencePolicies[1] == null || !((JsonNode) inferencePolicies[2]).path("nearBoundaryRatio").isNumber()) {
                throw new ApiException(ApiErrorCode.DOMAIN_POLICY_NOT_READY);
            }
            var domain = (ObjectNode) repository.jdbc().queryForObject(
                    "SELECT applicability_domain_jsonb FROM ai.model_version WHERE organization_id=? AND id=?",
                    (rs, n) -> repository.node(rs, "applicability_domain_jsonb"), actor.organizationId(), id).deepCopy();
            ((JsonNode) inferencePolicies[2]).fields().forEachRemaining(entry -> {
                if (!Set.of("features", "minimum", "maximum").contains(entry.getKey())) {
                    domain.set(entry.getKey(), entry.getValue());
                }
            });
            repository.jdbc().update(
                    "UPDATE ai.model_version SET domain_policy_version_id=?,applicability_domain_jsonb=? WHERE organization_id=? AND id=?",
                    inferencePolicies[1], repository.pg(domain), actor.organizationId(), id);
        } else {
            var historicalDomain = repository.jdbc().query(
                    "SELECT domain_policy_version_id FROM ai.model_version WHERE organization_id=? AND id=?",
                    (rs, n) -> rs.getObject(1, UUID.class), actor.organizationId(), id).stream().findFirst().orElse(null);
            if (historicalDomain == null) throw new ApiException(ApiErrorCode.DOMAIN_POLICY_NOT_READY);
        }

        if (active != null && !active.equals(id)) {
            repository.jdbc().update("UPDATE ai.model_version SET status='RETIRED',revision=revision+1,updated_at=now() "
                    + "WHERE organization_id=? AND id=? AND status='ACTIVE'", actor.organizationId(), active);
        }
        var updated = repository.jdbc().update(
                "UPDATE ai.model_version SET status='ACTIVE',revision=revision+1,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",
                actor.organizationId(), id, model.revision());
        if (updated != 1) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        insertRelease(actor, id, active, action, command.reason(), key);
        return new ModelActionResult(id, "ACTIVE", id, model.revision() + 1, Instant.now());
    }

    private void insertRelease(Actor a,UUID id,UUID previous,String action,String reason,String key){if(reason==null||reason.isBlank())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"请填写操作原因");var model=repository.model(a.organizationId(),id).orElseThrow();var releaseKey=action+":"+hashing.hash(Map.of("modelId",id,"idempotencyKey",key));repository.jdbc().update("INSERT INTO ai.model_release(id,organization_id,target_id,model_version_id,previous_model_version_id,action,expected_previous_revision,reason,actor_id,request_id,idempotency_key) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),a.organizationId(),model.targetId(),id,previous,action,null,reason,a.userId(),RequestIdHolder.currentOrUnknown(),releaseKey);audit.append(a.organizationId(),a.userId(),"AI_MODEL_"+action,"AI_MODEL_VERSION",id,json.createObjectNode().put("reason",reason));}

    @Scheduled(fixedDelayString="${app.ai.rnd.training-reconcile-delay:PT5M}")
    public void reconcile(){for(var org:repository.jdbc().query("SELECT organization_id FROM ai.training_scheduler_setting WHERE auto_learning_enabled",(rs,n)->rs.getObject(1,UUID.class))){for(var target:repository.candidateTargets(org))try{scheduleFromEligibility(org,target.targetId(),true);}catch(Exception ignored){}}}

    private String sampleGate(CandidateTarget t,List<EligibleSample> samples){if(samples.size()<t.qualification().path("minimumTrainableSamples").asInt())return "INSUFFICIENT_DATA";if(samples.stream().map(EligibleSample::logicalKey).distinct().count()<t.qualification().path("minimumIndependentLineages").asInt())return "INSUFFICIENT_LINEAGES";var sourceGroups=new HashSet<String>();for(var sample:samples){if(sample.sourceGroupKeys()!=null&&sample.sourceGroupKeys().isArray()&&!sample.sourceGroupKeys().isEmpty())sample.sourceGroupKeys().forEach(group->sourceGroups.add(sample.sourceType()+":"+group.asText()));else sourceGroups.add(sample.sourceType()+":"+sample.sourceBusinessKey());}if(sourceGroups.size()<t.qualification().path("minimumSourceGroups").asInt())return "INSUFFICIENT_SOURCE_GROUPS";if(!"CONTINUOUS".equals(t.valueType())){var counts=new java.util.HashMap<String,Long>();samples.forEach(s->counts.merge(s.evidence().path("targetValue").asText(),1L,Long::sum));if(counts.size()<2||counts.values().stream().anyMatch(v->v<t.qualification().path("minimumPerClass").asInt()))return "INSUFFICIENT_CLASS_SAMPLES";}return null;}
    private TargetEvaluation blocked(UUID org,CandidateTarget t,String code){if(t.eligibilityRunId()!=null)repository.jdbc().update("""
            INSERT INTO ai.training_schedule_intent(id,organization_id,target_id,target_version_id,input_scheme_id,eligibility_run_id,configuration_fingerprint,fact_high_watermark,status,block_code,block_detail_jsonb)
            SELECT gen_random_uuid(),organization_id,?,?,?,coalesce(?,'00000000-0000-0000-0000-000000000000'::uuid),?,?,'BLOCKED',?,'{}'::jsonb FROM ai.prediction_target WHERE organization_id=? AND id=?
            ON CONFLICT(organization_id,target_id) DO UPDATE SET status='BLOCKED',block_code=excluded.block_code,updated_at=now()
            """,t.targetId(),t.targetVersionId(),t.inputSchemeId(),t.eligibilityRunId(),hashing.hash(Map.of("target",t.targetId(),"code",code)),t.factHighWatermark(),code,org,t.targetId());return new TargetEvaluation(t.targetId(),t.targetName(),"BLOCKED",code,null,null);}

    private List<SnapshotRow> rows(CandidateTarget target,List<EligibleSample> samples){var out=new ArrayList<SnapshotRow>();for(var sample:samples){var values=sample.evidence().path("targetValues");if("KEEP_GROUPED".equals(target.training().path("replicateHandling").asText())&&values.isArray()&&values.size()>1){int i=0;for(var value:values)out.add(row(target,sample,value.path("value"),value.path("metadata"),i++));}else out.add(row(target,sample,sample.evidence().path("targetValue"),sample.observations(),0));}return out;}
    private SnapshotRow row(CandidateTarget target,EligibleSample sample,JsonNode targetValue,JsonNode observation,int index){var row=json.createObjectNode().put("rowId",sample.revisionId()+":"+index).put("sampleId",sample.sampleId().toString()).put("logicalSampleKey",sample.logicalKey()).put("targetValueType",target.valueType());row.set("formula",formula(sample.composition()));var inputs=json.createObjectNode();inputs.set("facts",sample.facts());inputs.set("process",sample.process());inputs.set("conditions",sample.conditions());row.set("inputs",inputs);row.set("targetValue",targetValue);var observations=json.createArrayNode();var replicates=json.createArrayNode();collectIdentity(observation,observations,replicates);var refs=json.createObjectNode().put("sourceId",sample.sourceId().toString()).put("sourceType",sample.sourceType()).put("sourceBusinessKey",sample.sourceBusinessKey()).put("sourceVersion",sample.sourceVersion()).put("contentHash",sample.contentHash());refs.set("sourceGroupKeys",sample.sourceGroupKeys().deepCopy());refs.set("coordinates",sample.sourceCoordinates());var validation=json.createObjectNode().put("formulaLineage",sample.logicalKey()).put("sourceContext",sourceContext(sample));var hash=hashing.hash(row);return new SnapshotRow(sample,row,hash,sample.logicalKey(),observations,replicates,refs,validation);}
    private String sourceContext(EligibleSample sample){var groups=new ArrayList<String>();if(sample.sourceGroupKeys()!=null&&sample.sourceGroupKeys().isArray())sample.sourceGroupKeys().forEach(group->groups.add(group.asText()));groups.sort(String::compareTo);return sample.sourceType()+":"+(groups.isEmpty()?sample.sourceBusinessKey():String.join("|",groups));}
    private ObjectNode formula(JsonNode composition){var out=json.createObjectNode().put("basis",composition.path("basis").asText("MASS_PERCENT")).put("compositionComplete",true);var components=json.createArrayNode();double total=0;var items=composition.path("items");if(items.isArray())for(var item:items){var c=json.createObjectNode().put("materialId",item.path("materialId").asText(item.path("materialCode").asText(item.path("name").asText()))).put("unit",item.path("unit").asText("%"));var known=item.path("amountKnown").isMissingNode()?item.path("ratio").isNumber():item.path("amountKnown").asBoolean();c.put("amountKnown",known);if(known&&item.path("ratio").isNumber()){c.put("ratio",item.path("ratio").asDouble());total+=item.path("ratio").asDouble();}else c.putNull("ratio");components.add(c);}out.set("components",components);if(composition.hasNonNull("recordedTotal"))out.put("recordedTotal",composition.path("recordedTotal").asDouble());else out.put("recordedTotal",total);return out;}
    private void collectIdentity(JsonNode node,ArrayNode observations,ArrayNode replicates){if(node==null||node.isNull())return;if(node.isArray()){node.forEach(x->collectIdentity(x,observations,replicates));return;}if(!node.isObject())return;if(node.hasNonNull("observationId"))observations.add(node.path("observationId").asText());if(node.hasNonNull("replicateGroupKey"))replicates.add(node.path("replicateGroupKey").asText());node.forEach(x->collectIdentity(x,observations,replicates));}
    private ObjectNode targetDefinition(UUID org,CandidateTarget t){var out=json.createObjectNode().put("id",t.targetVersionId().toString()).put("version",t.targetVersionNo()).put("sha256",t.targetHash()).put("code",repository.jdbc().queryForObject("SELECT target_code FROM ai.prediction_target WHERE organization_id=? AND id=?",String.class,org,t.targetId())).put("valueType",t.valueType());if(t.unit()!=null)out.put("unit",t.unit());else out.putNull("unit");out.set("classes",t.classes());if("BINARY".equals(t.valueType()))out.put("positiveClass",t.definition().path("positiveClass").asText(t.classes().path(1).asText()));else out.putNull("positiveClass");out.set("observationSemantics",t.observationSemantics());return out;}

    private <T>T idempotent(String operation,String key,Object request,Class<T> type,Supplier<T> action){
        var a=actor();var normalized=requireKey(key);var requestHash=hashing.hash(request);
        repository.jdbc().query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(rs,n)->rs.getObject(1),a.organizationId()+":"+operation+":"+normalized);
        var receipt=repository.jdbc().query("SELECT request_hash,response_jsonb FROM ai.configuration_command_receipt WHERE organization_id=? AND operation=? AND idempotency_key=?",(rs,n)->new Object[]{rs.getString(1),repository.node(rs,"response_jsonb")},a.organizationId(),operation,normalized).stream().findFirst().orElse(null);
        if(receipt!=null){if(!requestHash.equals(receipt[0]))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);try{return json.treeToValue((JsonNode)receipt[1],type);}catch(Exception e){throw new IllegalStateException("幂等结果无法恢复",e);}}
        var response=action.get();var responseJson=json.valueToTree(response);UUID resourceId=responseJson.hasNonNull("id")?UUID.fromString(responseJson.path("id").asText()):responseJson.hasNonNull("jobId")?UUID.fromString(responseJson.path("jobId").asText()):responseJson.hasNonNull("modelVersionId")?UUID.fromString(responseJson.path("modelVersionId").asText()):null;
        repository.jdbc().update("INSERT INTO ai.configuration_command_receipt(id,organization_id,operation,idempotency_key,request_hash,resource_type,resource_id,response_jsonb,created_by) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),a.organizationId(),operation,normalized,requestHash,type.getSimpleName(),resourceId,repository.pg(responseJson),a.userId());return response;
    }
    private String requireKey(String key){if(key==null||key.isBlank())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"缺少 Idempotency-Key");var value=key.strip();if(value.length()>200)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"Idempotency-Key 不能超过200个字符");return value;}
    private ObjectNode inputScheme(CandidateTarget t,FrozenConfig c){var out=json.createObjectNode().put("id",t.inputSchemeId().toString()).put("version",t.inputSchemeVersion()).put("sha256",t.schemeHash()).put("targetVersionId",t.targetVersionId().toString());out.set("fields",json.valueToTree(c.fields()));return out;}
    private ObjectNode materialDictionary(DictionaryRow d){var materials=json.createArrayNode();for(var item:d.vocabulary()){materials.add(json.createObjectNode().put("materialId",item.path("materialId").asText()).put("code",item.path("token").asText(item.path("code").asText())).put("role",item.path("role").asText()).put("encoderIndex",item.path("ordinal").asInt()));}var out=json.createObjectNode().put("id",d.id().toString()).put("version",d.version()).put("sha256",d.hash());out.set("materials",materials);return out;}
    private ObjectNode trainingPolicy(CandidateTarget t){var config=json.createObjectNode();t.qualification().fields().forEachRemaining(e->config.set(e.getKey(),e.getValue()));t.validation().fields().forEachRemaining(e->config.set(e.getKey(),e.getValue()));t.training().fields().forEachRemaining(e->config.set(e.getKey(),e.getValue()));return versionedConfig(t.policyId(),t.policyVersion(),t.policyHash(),config);}
    private ObjectNode versionedConfig(UUID id,int version,String hash,JsonNode config){var out=json.createObjectNode().put("id",id.toString()).put("version",version).put("sha256",hash);out.set("config",config);return out;}
    private Actor actor(){return ActorContext.required();}
    private record SnapshotRow(EligibleSample sample,ObjectNode row,String hash,String splitGroup,ArrayNode observationIds,ArrayNode replicateKeys,ObjectNode sourceRefs,ObjectNode validationGroups){}
}
