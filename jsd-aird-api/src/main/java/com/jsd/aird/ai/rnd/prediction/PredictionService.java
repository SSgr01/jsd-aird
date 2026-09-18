package com.jsd.aird.ai.rnd.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.rnd.api.AiRndContracts.PredictionRequest;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ai.rnd.training.FormulaModelV2Client;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.prediction.PredictionContracts.*;

@Service
public class PredictionService {
    private static final String CONTRACT="ai-rnd.v1";
    private final PredictionRepository repository;
    private final FormulaModelArtifactStore artifacts;
    private final FormulaModelV2Client compute;
    private final ConfigurationHashing hashing;
    private final QualityPolicyEvaluator quality;
    private final ObjectMapper json;
    private final AuditLogFacade audit;
    private final TransactionTemplate transactions;
    private final Duration staleAfter;
    private final boolean allowSyntheticPrediction;

    public PredictionService(PredictionRepository repository, FormulaModelArtifactStore artifacts,
                             FormulaModelV2Client compute, ConfigurationHashing hashing,
                             QualityPolicyEvaluator quality, ObjectMapper json, AuditLogFacade audit,
                             PlatformTransactionManager tx,
                             @Value("${app.ai.rnd.prediction-stale-after:PT3M}") Duration staleAfter,
                             @Value("${JSD_AIRD_AI_ALLOW_SYNTHETIC_PREDICTION:false}") boolean allowSyntheticPrediction) {
        this.repository=repository;this.artifacts=artifacts;this.compute=compute;this.hashing=hashing;
        this.quality=quality;this.json=json;this.audit=audit;this.transactions=new TransactionTemplate(tx);
        this.staleAfter=staleAfter;this.allowSyntheticPrediction=allowSyntheticPrediction;
    }

    public JsonNode context(List<UUID> targetIds) {
        var actor=actor();var root=json.createObjectNode();var catalog=json.createArrayNode();
        for(var item:repository.targetCatalog(actor.organizationId())){
            var value=json.createObjectNode().put("targetId",item.id().toString()).put("code",item.code())
                    .put("name",item.name()).put("category",item.category()).put("valueType",item.valueType())
                    .put("targetStatus",item.status()).put("formalPredictionAvailable",item.modelId()!=null&&item.qualityPolicyId()!=null&&item.domainPolicyId()!=null);
            var unavailable=reasons(item.modelId(),item.qualityPolicyId(),item.domainPolicyId());value.set("unavailableReasons",json.valueToTree(unavailable));catalog.add(value);
        }
        root.set("targets",catalog);
        if(targetIds==null||targetIds.isEmpty()){root.set("selected",json.createArrayNode());root.set("requiredInputs",json.createArrayNode());root.set("materials",json.createArrayNode());return root;}
        var bindings=repository.targetBindings(actor.organizationId(),distinct(targetIds));
        var selected=json.createArrayNode();var inputs=new LinkedHashMap<String,JsonNode>();var materials=new LinkedHashMap<String,JsonNode>();
        for(var binding:bindings){var entry=bindingSummary(binding);selected.add(entry);
            binding.frozenInputScheme().path("fields").forEach(field->{if(!"COMPOSITION".equals(field.path("valueType").asText()))mergeInputField(inputs,field,binding);});
            binding.frozenDictionary().path("materials").forEach(material->{var enriched=material.deepCopy();repository.material(material.path("materialId").asText()).ifPresent(m->{if(enriched instanceof ObjectNode o){o.put("code",m.code());o.put("name",m.name());o.put("category",m.category());}});materials.putIfAbsent(material.path("materialId").asText(),enriched);});}
        root.set("selected",selected);root.set("requiredInputs",json.valueToTree(inputs.values()));root.set("materials",json.valueToTree(materials.values()));
        root.set("configurationConflicts",conflicts(bindings));return root;
    }

    @Transactional(readOnly=true)
    public List<QualityPolicyView> qualityPolicies(UUID targetId){requireTarget(targetId);return repository.qualityPolicies(actor().organizationId(),targetId);}

    @Transactional
    public QualityPolicyView createQualityPolicy(UUID targetId,QualityPolicyCommand command,String idempotencyKey){
        var a=actor();requireKey(idempotencyKey);validateQuality(command);lockTarget(a,targetId,command.expectedRevision());
        var operation="CREATE_QUALITY_POLICY:"+targetId;var request=json.valueToTree(command);var hash=hashing.hash(request);
        var existing=receipt(a,operation,idempotencyKey,hash);if(existing!=null)return json.convertValue(existing,QualityPolicyView.class);
        var next=repository.jdbc().queryForObject("SELECT coalesce(max(version_no),0)+1 FROM ai.modeling_policy_version WHERE organization_id=? AND target_id=? AND kind='QUALITY'",Integer.class,a.organizationId(),targetId);
        var config=json.createObjectNode().put("defaultTrustLevel",command.defaultTrustLevel()).put("defaultExplanation",command.defaultExplanation());config.set("rules",json.valueToTree(command.rules()));
        var id=UUID.randomUUID();var policyHash=hashing.hash(config);
        repository.jdbc().update("""
            INSERT INTO ai.modeling_policy_version(id,organization_id,target_id,version_no,status,kind,
                qualification_jsonb,validation_jsonb,training_jsonb,configuration_jsonb,policy_hash,created_by)
            VALUES(?,?,?,?,'DRAFT','QUALITY','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,?,?,?)
            """,id,a.organizationId(),targetId,next,repository.pg(config),policyHash,a.userId());
        bumpTarget(a,targetId,command.expectedRevision());var view=repository.qualityPolicy(a.organizationId(),id).orElseThrow();
        saveReceipt(a,operation,idempotencyKey,hash,id,json.valueToTree(view));audit.append(a.organizationId(),a.userId(),"AI_QUALITY_POLICY_CREATED","AI_MODELING_POLICY",id,config);return view;
    }

    @Transactional
    public QualityPolicyView publishQualityPolicy(UUID id,PublishPolicyCommand command,String idempotencyKey){
        var a=actor();requireKey(idempotencyKey);var current=repository.qualityPolicy(a.organizationId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"质量策略不存在"));
        if(!"DRAFT".equals(current.status()))throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"只有草稿质量策略可以发布");
        lockTarget(a,current.targetId(),command.expectedRevision());var operation="PUBLISH_QUALITY_POLICY:"+id;var hash=hashing.hash(command);
        var existing=receipt(a,operation,idempotencyKey,hash);if(existing!=null)return json.convertValue(existing,QualityPolicyView.class);
        repository.jdbc().update("UPDATE ai.modeling_policy_version SET status='PUBLISHED',published_at=now(),published_by=? WHERE organization_id=? AND id=? AND kind='QUALITY' AND status='DRAFT'",a.userId(),a.organizationId(),id);
        var updated=repository.jdbc().update("""
            UPDATE ai.prediction_target SET current_quality_policy_version_id=?,revision=revision+1,updated_by=?,updated_at=now()
            WHERE organization_id=? AND id=? AND revision=?
            """,id,a.userId(),a.organizationId(),current.targetId(),command.expectedRevision());
        if(updated!=1)throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        var view=repository.qualityPolicy(a.organizationId(),id).orElseThrow();saveReceipt(a,operation,idempotencyKey,hash,id,json.valueToTree(view));
        audit.append(a.organizationId(),a.userId(),"AI_QUALITY_POLICY_PUBLISHED","AI_MODELING_POLICY",id,json.valueToTree(command));return view;
    }

    public PredictionHttpResult predict(PredictionRequest request,String idempotencyKey){
        var a=actor();requireKey(idempotencyKey);validateRequest(request);var started=System.nanoTime();
        var targetIds=request.targets().stream().map(x->x.targetId()).toList();var bindings=repository.targetBindings(a.organizationId(),distinct(targetIds));
        var frozen=bindingDocument(bindings);var requestNode=json.valueToTree(request);var normalized=normalized(requestNode);var requestHash=hashing.hash(normalized);
        var id=UUID.randomUUID();var requestId=RequestIdHolder.currentOrUnknown();
        var won=Boolean.TRUE.equals(transactions.execute(ignored->repository.insertRunning(id,a.organizationId(),a.userId(),idempotencyKey,requestId,requestHash,normalized,frozen)));
        if(!won){var old=repository.byKey(a.organizationId(),a.userId(),idempotencyKey).orElseThrow();
            if(!old.requestHash().equals(requestHash))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            if("RUNNING".equals(old.executionStatus()))return new PredictionHttpResult(202,running(old.id(),old.requestId()));
            return new PredictionHttpResult(old.terminalHttpStatus(),old.terminalResponse());}
        audit.append(a.organizationId(),a.userId(),"AI_PERFORMANCE_PREDICTION_STARTED","AI_PREDICTION",id,frozen);
        var result=execute(id,requestId,request,bindings,started);
        var row=repository.byId(a.organizationId(),a.userId(),id).orElseThrow();
        if("RUNNING".equals(row.executionStatus())){
            var duration=Duration.ofNanos(System.nanoTime()-started).toMillis();var response=result.body();
            var targetResults=response.path("results");var error="FAILED".equals(response.path("executionStatus").asText())?response:null;
            if(!repository.complete(a.organizationId(),id,row.revision(),response.path("executionStatus").asText(),textOrNull(response,"outcomeStatus"),result.status(),targetResults,response,duration,error)){
                var finalRow=repository.byId(a.organizationId(),a.userId(),id).orElseThrow();return new PredictionHttpResult(finalRow.terminalHttpStatus(),finalRow.terminalResponse());}
            audit.append(a.organizationId(),a.userId(),"AI_PERFORMANCE_PREDICTION_COMPLETED","AI_PREDICTION",id,response);
        }
        return result;
    }

    public PredictionHttpResult record(UUID id){var a=actor();var row=repository.byId(a.organizationId(),a.userId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"预测记录不存在"));
        if("RUNNING".equals(row.executionStatus()))return new PredictionHttpResult(200,running(row.id(),row.requestId()));
        return new PredictionHttpResult(200,row.terminalResponse());}

    @Scheduled(fixedDelayString="${app.ai.rnd.prediction-reconcile-delay:PT1M}")
    public void reconcileStale(){repository.failStale(Instant.now().minus(staleAfter));}

    private PredictionHttpResult execute(UUID id,String requestId,PredictionRequest request,List<PredictionRepository.TargetBinding> bindings,long started){
        var byTarget=new HashMap<UUID,PredictionRepository.TargetBinding>();bindings.forEach(x->byTarget.put(x.targetId(),x));
        var results=json.createArrayNode();var scoreBindings=json.createArrayNode();var modelToBinding=new HashMap<String,PredictionRepository.TargetBinding>();
        var warnings=formulaWarnings(request);var total=formulaTotal(request);
        for(var wanted:request.targets()){
            var b=byTarget.get(wanted.targetId());if(b==null){results.add(blocked(wanted.targetId(),null,"TARGET_NOT_FOUND","预测目标不存在",json.createObjectNode()));continue;}
            var reason=gate(b,wanted.expectedModelVersionId(),request);
            if(reason!=null){results.add(reason);continue;}
            try{var binding=scoreBinding(b);scoreBindings.add(binding);modelToBinding.put(b.modelId().toString(),b);}
            catch(ApiException e){results.add(failed(b.targetId(),b.modelId(),"MODEL_ARTIFACT_UNAVAILABLE",e.getMessage(),json.createObjectNode()));}
        }
        if(!scoreBindings.isEmpty()){
            var score=json.createObjectNode().put("contractVersion","formula-model.v2").put("requestId",requestId).put("seed",0).put("runId",id.toString());
            if(request.formula()==null) score.putNull("formula"); else score.set("formula",json.valueToTree(request.formula()));
            score.set("inputs",json.valueToTree(request.inputs()));score.set("modelBindings",scoreBindings);
            try{var computed=compute.score(score);var returned=new HashSet<String>();
                for(var item:computed.path("results")){var modelId=item.path("modelVersionId").asText();returned.add(modelId);var b=modelToBinding.get(modelId);
                    if(b==null)continue;if("SUCCEEDED".equals(item.path("status").asText()))results.add(success(b,item,warnings,total,request));
                    else if("BLOCKED".equals(item.path("status").asText()))results.add(rewriteTarget(item,b.targetId()));
                    else results.add(rewriteTarget(item,b.targetId()));}
                for(var entry:modelToBinding.entrySet())if(!returned.contains(entry.getKey()))results.add(failed(entry.getValue().targetId(),entry.getValue().modelId(),"MODEL_RESPONSE_MISSING","计算服务未返回该目标结果",json.createObjectNode()));
            }catch(Exception exception){for(var b:modelToBinding.values())results.add(failed(b.targetId(),b.modelId(),"COMPUTE_UNAVAILABLE","计算服务暂不可用",json.createObjectNode().put("message",String.valueOf(exception.getMessage()))));}
        }
        var count=results.size();var succeeded=0;var failed=0;for(var x:results){if("SUCCEEDED".equals(x.path("status").asText()))succeeded++;if("FAILED".equals(x.path("status").asText()))failed++;}
        var body=json.createObjectNode().put("predictionId",id.toString()).put("requestId",requestId);body.set("results",results);body.set("warnings",warnings);
        int http;if(failed>0&&succeeded==0){body.put("executionStatus","FAILED");body.putNull("outcomeStatus");http=503;body.put("code","PREDICTION_EXECUTION_FAILED");body.put("message","一个或多个正式模型无法执行");}
        else{body.put("executionStatus","SUCCEEDED");var outcome=succeeded==count?"SUCCEEDED":succeeded>0?"PARTIAL":"BLOCKED";body.put("outcomeStatus",outcome);http="BLOCKED".equals(outcome)&&count==1?422:200;}
        return new PredictionHttpResult(http,body);
    }

    private ObjectNode success(PredictionRepository.TargetBinding b,JsonNode computed,ArrayNode warnings,BigDecimal total,PredictionRequest request){
        var result=json.createObjectNode().put("targetId",b.targetId().toString()).put("targetName",b.targetName()).put("status","SUCCEEDED")
                .put("modelVersionId",b.modelId().toString()).put("targetVersionId",b.targetVersionId().toString())
                .put("inputSchemeId",b.modelInputSchemeId().toString()).put("snapshotId",b.snapshotId().toString());
        result.set("prediction",computed.path("result"));result.set("applicability",computed.path("applicability"));result.set("warnings",warnings.deepCopy());
        var required=0;var present=0;for(var field:b.frozenInputScheme().path("fields"))if(field.path("required").asBoolean()){required++;if("COMPOSITION".equals(field.path("valueType").asText())){if(request.formula()!=null&&request.formula().compositionComplete())present++;}else if(request.inputs().get(field.path("code").asText())!=null&&!request.inputs().get(field.path("code").asText()).isNull())present++;}
        var coverage=required==0?1d:(double)present/required;var codes=new ArrayList<String>();warnings.forEach(x->codes.add(x.path("code").asText()));
        var evaluated=quality.evaluate(b.qualityPolicy(),b.metrics(),computed.path("applicability").path("status").asText(),coverage,codes,total);
        result.set("quality",json.createObjectNode().put("level",evaluated.level()).put("explanation",evaluated.explanation()).put("policyVersionId",b.qualityPolicyId().toString()).put("evidenceCoverage",coverage));
        result.set("evidence",evidence(b));return result;
    }
    private ObjectNode evidence(PredictionRepository.TargetBinding b){return json.createObjectNode().put("modelVersionId",b.modelId().toString()).put("targetVersionId",b.targetVersionId().toString()).put("inputSchemeId",b.modelInputSchemeId().toString()).put("snapshotId",b.snapshotId().toString()).put("domainPolicyVersionId",b.domainPolicyId().toString()).put("qualityPolicyVersionId",b.qualityPolicyId().toString());}
    private JsonNode gate(PredictionRepository.TargetBinding b,UUID expected,PredictionRequest request){
        if(!"ACTIVE".equals(b.targetStatus())||b.targetVersionId()==null)return blocked(b.targetId(),null,"TARGET_NOT_READY","预测目标尚未发布",json.createObjectNode());
        if(!modelAvailable(b))return blocked(b.targetId(),null,"NO_ACTIVE_MODEL","目标尚无可用的正式模型",json.createObjectNode());
        if(expected!=null&&!expected.equals(b.modelId()))return blocked(b.targetId(),b.modelId(),"MODEL_VERSION_CHANGED","正式模型版本已变化，请刷新后重试",json.createObjectNode().put("currentModelVersionId",b.modelId().toString()));
        if(!"formula-model.v2".equals(b.contractVersion()))return failed(b.targetId(),b.modelId(),"MODEL_CONTRACT_MISMATCH","模型契约版本不受支持",json.createObjectNode());
        if(b.qualityPolicyId()==null||!"PUBLISHED".equals(b.qualityPolicyStatus()))return blocked(b.targetId(),b.modelId(),"QUALITY_POLICY_NOT_READY","质量策略尚未发布",json.createObjectNode());
        if(b.domainPolicyId()==null||!"PUBLISHED".equals(b.domainPolicyStatus())||!domainReady(b))return blocked(b.targetId(),b.modelId(),"DOMAIN_POLICY_NOT_READY","模型适用域策略尚未完整冻结",json.createObjectNode());
        if(formulaRequired(b)){
            var inputProblem=formulaProblem(request);
            if(inputProblem!=null)return blocked(b.targetId(),b.modelId(),inputProblem,
                    "FORMULA_AMOUNT_MISSING".equals(inputProblem)?"存在尚未填写用量的材料":"请确认已录入全部配方成分",
                    json.createObjectNode());
        }
        var missing=json.createArrayNode();for(var field:b.frozenInputScheme().path("fields"))if(field.path("required").asBoolean()&&!"COMPOSITION".equals(field.path("valueType").asText())&&(request.inputs().get(field.path("code").asText())==null||request.inputs().get(field.path("code").asText()).isNull()))missing.add(field.path("code").asText());
        if(!missing.isEmpty())return blocked(b.targetId(),b.modelId(),"MISSING_REQUIRED_X","缺少模型要求的输入字段",json.createObjectNode().set("missingFields",missing));
        if(formulaRequired(b)){
            var known=new HashSet<String>();b.frozenDictionary().path("materials").forEach(x->known.add(x.path("materialId").asText()));
            var unknown=json.createArrayNode();request.formula().components().forEach(x->{if(x.materialId()!=null&&!known.contains(x.materialId().toString()))unknown.add(x.materialId().toString());});
            if(!unknown.isEmpty())return blocked(b.targetId(),b.modelId(),"MATERIAL_NOT_IN_MODEL","材料已识别，但当前模型未覆盖",json.createObjectNode().set("materialIds",unknown));
        }
        return null;
    }
    private boolean domainReady(PredictionRepository.TargetBinding b){var merged=mergedDomain(b);return merged.has("features")&&merged.has("minimum")&&merged.has("maximum")&&merged.path("nearBoundaryRatio").isNumber();}
    private ObjectNode scoreBinding(PredictionRepository.TargetBinding b){var ref=artifacts.readRef(b.artifactKey(),b.artifactKey(),b.artifactSha());var out=json.createObjectNode();out.set("target",scoreTarget(b));out.put("modelVersionId",b.modelId().toString());out.set("modelBundle",json.createObjectNode().put("url",ref.url()).put("sha256",ref.sha256()));out.set("inputScheme",b.frozenInputScheme());out.set("materialDictionary",v2MaterialDictionary(b.frozenDictionary()));out.set("preprocessing",v2Preprocessing(b.frozenPreprocessing()));out.set("applicabilityDomain",mergedDomain(b));return out;}
    /** Normalize older frozen snapshots at the Java/Python boundary. The database snapshot remains immutable. */
    private ObjectNode v2MaterialDictionary(JsonNode source){
        var out=json.createObjectNode();
        if(source!=null&&source.isObject()){
            out.put("id",source.path("id").asText("material-dictionary-v1"));
            out.put("version",Math.max(1,source.path("version").asInt(1)));
            var sha=source.path("sha256").asText("");out.put("sha256",sha.matches("[0-9a-f]{64}")?sha:"0000000000000000000000000000000000000000000000000000000000000000");
        } else {out.put("id","material-dictionary-v1").put("version",1).put("sha256","0000000000000000000000000000000000000000000000000000000000000000");}
        var materials=json.createArrayNode();
        if(source!=null)source.path("materials").forEach((m)->{var x=json.createObjectNode().put("materialId",m.path("materialId").asText());
            var code=m.path("code").asText(m.path("token").asText(m.path("materialId").asText()));x.put("code",code);if(m.hasNonNull("role"))x.put("role",m.path("role").asText());x.put("encoderIndex",m.path("encoderIndex").isNumber()?m.path("encoderIndex").asInt():m.path("ordinal").asInt(materials.size()));materials.add(x);});
        if(materials.isEmpty())materials.add(json.createObjectNode().put("materialId","UNUSED").put("code","UNUSED").put("encoderIndex",0));
        out.set("materials",materials);return out;
    }
    private ObjectNode v2Preprocessing(JsonNode source){var out=json.createObjectNode();
        if(source!=null&&source.isObject()){
            out.put("id",source.path("id").asText("preprocessing-v1"));out.put("version",Math.max(1,source.path("version").asInt(1)));
            var sha=source.path("sha256").asText("");out.put("sha256",sha.matches("[0-9a-f]{64}")?sha:"0000000000000000000000000000000000000000000000000000000000000000");
            out.set("config",source.has("config")?source.path("config"):json.createObjectNode());
        } else out.put("id","preprocessing-v1").put("version",1).put("sha256","0000000000000000000000000000000000000000000000000000000000000000").set("config",json.createObjectNode());
        return out;
    }
    /** Build the strict formula-model.v2 target shape from the frozen domain data.
     *  Model cards may contain display-only fields such as name/catalogSource/synthetic;
     *  those fields must never cross the runtime contract boundary. */
    private ObjectNode scoreTarget(PredictionRepository.TargetBinding b){
        var frozen=b.frozenTarget();
        var targetHash=frozen!=null&&frozen.path("sha256").isTextual()?frozen.path("sha256").asText():b.targetHash();
        var target=json.createObjectNode().put("id",b.targetVersionId().toString())
                .put("version",b.targetVersion()).put("sha256",targetHash)
                .put("code",b.targetCode()).put("valueType",b.valueType());
        if(b.unit()!=null) target.put("unit",b.unit()); else target.putNull("unit");
        target.set("classes",b.classes()==null?json.createArrayNode():b.classes());
        if ("BINARY".equals(b.valueType())) {
            var positiveClass = b.definition() == null ? null : b.definition().path("positiveClass");
            if (positiveClass != null && positiveClass.isTextual() && !positiveClass.asText().isBlank()) {
                target.put("positiveClass", positiveClass.asText());
            } else if (b.classes() != null && b.classes().isArray() && b.classes().size() == 2) {
                target.put("positiveClass", b.classes().get(1).asText());
            } else {
                target.putNull("positiveClass");
            }
        } else {
            target.putNull("positiveClass");
        }
        target.set("observationSemantics",b.observationSemantics()==null?json.createObjectNode():b.observationSemantics());
        return target;
    }
    private ObjectNode mergedDomain(PredictionRepository.TargetBinding b){var out=b.modelDomain()!=null&&b.modelDomain().isObject()?((ObjectNode)b.modelDomain()).deepCopy():json.createObjectNode();var config=b.domainPolicy();if(config!=null&&config.isObject())config.fields().forEachRemaining(e->{if(!Set.of("features","minimum","maximum").contains(e.getKey()))out.set(e.getKey(),e.getValue());});out.put("policyVersionId",b.domainPolicyId()==null?"":b.domainPolicyId().toString());out.put("policyHash",Objects.toString(b.domainPolicyHash(),""));return out;}
    private ObjectNode rewriteTarget(JsonNode source,UUID target){var out=((ObjectNode)source).deepCopy();out.put("targetId",target.toString());return out;}
    private ObjectNode blocked(UUID target,UUID model,String code,String message,JsonNode detail){var x=json.createObjectNode().put("targetId",target.toString()).put("status","BLOCKED").put("errorCode",code).put("message",message);if(model!=null)x.put("modelVersionId",model.toString());x.set("detail",detail);return x;}
    private ObjectNode failed(UUID target,UUID model,String code,String message,JsonNode detail){var x=json.createObjectNode().put("targetId",target.toString()).put("status","FAILED").put("errorCode",code).put("message",message);if(model!=null)x.put("modelVersionId",model.toString());x.set("detail",detail);return x;}
    private ArrayNode formulaWarnings(PredictionRequest request){var warnings=json.createArrayNode();if(request.formula()==null)return warnings;var total=formulaTotal(request);if("MASS_PERCENT".equals(request.formula().basis())&&total!=null&&total.compareTo(new BigDecimal("100"))!=0){var item=json.createObjectNode().put("code","FORMULA_TOTAL_WARNING").put("message","配方合计为 "+total.stripTrailingZeros().toPlainString()+"%");item.set("detail",json.createObjectNode().put("recordedTotal",total));warnings.add(item);}return warnings;}
    private BigDecimal formulaTotal(PredictionRequest request){if(request.formula()==null)return null;if(request.formula().recordedTotal()!=null)return request.formula().recordedTotal();BigDecimal total=BigDecimal.ZERO;for(var x:request.formula().components()){if(x.ratio()==null)return null;total=total.add(x.ratio());}return total;}
    private String formulaProblem(PredictionRequest request){if(request.formula()==null||!request.formula().compositionComplete())return "FORMULA_INCOMPLETE";if(request.formula().components()==null||request.formula().components().isEmpty())return "FORMULA_INCOMPLETE";for(var x:request.formula().components()){if(x.materialId()==null||x.ratio()==null)return "FORMULA_AMOUNT_MISSING";if(x.unit()==null||x.unit().isBlank())return "FORMULA_UNIT_UNRESOLVED";}return null;}
    private boolean formulaRequired(PredictionRepository.TargetBinding b){
        for(var field:b.frozenInputScheme().path("fields")){
            var code=field.path("code").asText("").toUpperCase(java.util.Locale.ROOT);
            if("COMPOSITION".equals(field.path("valueType").asText())||code.contains("FORMULA")||code.contains("COMPOSITION"))return true;
        }
        for(var feature:mergedDomain(b).path("features")){
            var name=feature.asText();if(name.startsWith("material:")||name.startsWith("formula:"))return true;
        }
        return false;
    }
    private ObjectNode bindingSummary(PredictionRepository.TargetBinding b){var x=json.createObjectNode().put("targetId",b.targetId().toString()).put("name",b.targetName()).put("category",b.category()).put("valueType",b.valueType()).put("available",gateAvailability(b).isEmpty()).put("formulaRequirement",formulaRequired(b)?"REQUIRED":"NOT_USED");if(b.modelId()!=null)x.put("modelVersionId",b.modelId().toString());x.set("unavailableReasons",json.valueToTree(gateAvailability(b)));x.set("fixedConditions",fixedConditions(b));x.set("evidence",b.modelId()==null?json.createObjectNode():evidence(b));return x;}
    private void mergeInputField(Map<String,JsonNode> inputs,JsonNode raw,PredictionRepository.TargetBinding binding){
        var code=raw.path("code").asText();if(code.isBlank())return;
        var field=raw.deepCopy();
        if(field instanceof ObjectNode object){
            var requiredBy=object.withArray("requiredByTargets");
            var target=json.createObjectNode().put("targetId",binding.targetId().toString()).put("targetName",binding.targetName());
            var duplicate=false;for(var existing:requiredBy)if(existing.path("targetId").asText().equals(binding.targetId().toString()))duplicate=true;
            if(!duplicate)requiredBy.add(target);
            var encoding=object.path("encoding");
            if(!object.has("sourceLabel")||object.path("sourceLabel").asText().isBlank())object.put("sourceLabel",encoding.path("sourceLabel").asText(object.path("name").asText(code)));
            if(!object.has("inputGroup")||object.path("inputGroup").asText().isBlank())object.put("inputGroup",encoding.path("inputGroup").asText(inputGroup(code,object.path("valueType").asText())));
            var allowed=object.path("allowedValues");if(!allowed.isArray())allowed=encoding.path("allowedValues");if(!allowed.isArray())allowed=encoding.path("categoryValues");if(allowed.isArray())object.set("allowedValues",allowed);
            object.put("fixedByTargetDefinition",isFixedByTargetDefinition(binding,code));
        }
        var current=inputs.get(code);if(current==null){inputs.put(code,field);return;}
        if(current instanceof ObjectNode object){
            var target=current.path("requiredByTargets");var from=field.path("requiredByTargets");if(target instanceof ArrayNode targetArray&&from.isArray())for(var item:from){var duplicate=false;for(var existing:targetArray)if(existing.path("targetId").asText().equals(item.path("targetId").asText()))duplicate=true;if(!duplicate)targetArray.add(item);}
        }
    }
    private String inputGroup(String code,String type){return "COMPOSITION".equals(type)?"FORMULA":"OTHER";}
    private boolean isFixedByTargetDefinition(PredictionRepository.TargetBinding binding,String code){var fixed=binding.definition().path("fixedInputs");return fixed.isObject()&&fixed.has(code);}
    private ObjectNode fixedConditions(PredictionRepository.TargetBinding b){var fixed=json.createObjectNode();var definition=b.definition();for(var key:new String[]{"testMethod","testStage","pretreatment","fixedCondition","fixedConditions","load","substrate","angle"})if(definition.hasNonNull(key))fixed.set(key,definition.get(key));return fixed;}
    private List<String> gateAvailability(PredictionRepository.TargetBinding b){var out=new ArrayList<String>();if(!modelAvailable(b))out.add("NO_ACTIVE_MODEL");if(b.qualityPolicyId()==null)out.add("QUALITY_POLICY_NOT_READY");if(b.domainPolicyId()==null||!domainReady(b))out.add("DOMAIN_POLICY_NOT_READY");return out;}
    private boolean modelAvailable(PredictionRepository.TargetBinding b){return b.modelId()!=null&&(b.productionEligible()&&"REAL".equals(b.dataNature())||(allowSyntheticPrediction&&"SYNTHETIC".equals(b.dataNature())));}
    private List<String> reasons(UUID model,UUID quality,UUID domain){var out=new ArrayList<String>();if(model==null)out.add("NO_ACTIVE_MODEL");if(quality==null)out.add("QUALITY_POLICY_NOT_READY");if(domain==null)out.add("DOMAIN_POLICY_NOT_READY");return out;}
    private ArrayNode conflicts(List<PredictionRepository.TargetBinding> bindings){var byCode=new HashMap<String,Set<String>>();for(var b:bindings)for(var field:b.frozenInputScheme().path("fields"))byCode.computeIfAbsent(field.path("code").asText(),k->new HashSet<>()).add(field.path("valueType").asText()+"|"+field.path("unit").asText());var out=json.createArrayNode();byCode.forEach((code,defs)->{if(defs.size()>1)out.add(json.createObjectNode().put("fieldCode",code).set("definitions",json.valueToTree(defs)));});return out;}
    private ObjectNode bindingDocument(List<PredictionRepository.TargetBinding> bindings){var root=json.createObjectNode();var list=json.createArrayNode();bindings.forEach(x->list.add(bindingSummary(x)));root.set("targets",list);return root;}
    private ObjectNode running(UUID id,String requestId){return json.createObjectNode().put("predictionRecordId",id.toString()).put("requestId",requestId).put("executionStatus","RUNNING").putNull("outcomeStatus").put("pollAfterMs",1000);}
    private JsonNode normalized(JsonNode request){return hashing.canonical(request);}
    private List<UUID> distinct(List<UUID> ids){return ids.stream().filter(Objects::nonNull).distinct().sorted().toList();}
    private void validateRequest(PredictionRequest request){if(request==null||request.targets()==null||request.targets().isEmpty())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"至少选择一个预测目标");if(request.inputs()==null)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"实验前条件不能为空");if(request.formula()!=null&&request.formula().components()==null)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"配方组成格式无效");if(distinct(request.targets().stream().map(x->x.targetId()).toList()).size()!=request.targets().size())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"预测目标不能重复");}
    private void validateQuality(QualityPolicyCommand command){if(command==null||!Set.of("HIGH","MEDIUM","LOW").contains(command.defaultTrustLevel())||command.defaultExplanation()==null||command.defaultExplanation().isBlank())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"必须配置默认可信等级及说明");var priorities=new HashSet<Integer>();for(var rule:command.rules()==null?List.<QualityRule>of():command.rules()){if(rule.code()==null||rule.code().isBlank()||rule.explanation()==null||rule.explanation().isBlank()||!Set.of("HIGH","MEDIUM","LOW").contains(rule.trustLevel())||rule.when()==null||!rule.when().isObject()||!priorities.add(rule.priority()))throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"质量规则编码、优先级、条件、可信等级或说明无效");}}
    private void requireTarget(UUID id){var a=actor();var count=repository.jdbc().queryForObject("SELECT count(*) FROM ai.prediction_target WHERE organization_id=? AND id=?",Long.class,a.organizationId(),id);if(count==null||count!=1)throw new ApiException(ApiErrorCode.NOT_FOUND,"预测目标不存在");}
    private void lockTarget(Actor a,UUID id,long revision){var current=repository.jdbc().query("SELECT revision FROM ai.prediction_target WHERE organization_id=? AND id=? FOR UPDATE",(rs,n)->rs.getLong(1),a.organizationId(),id).stream().findFirst().orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"预测目标不存在"));if(current!=revision)throw new ApiException(ApiErrorCode.VERSION_CONFLICT);}
    private void bumpTarget(Actor a,UUID id,long revision){if(repository.jdbc().update("UPDATE ai.prediction_target SET revision=revision+1,updated_by=?,updated_at=now() WHERE organization_id=? AND id=? AND revision=?",a.userId(),a.organizationId(),id,revision)!=1)throw new ApiException(ApiErrorCode.VERSION_CONFLICT);}
    private JsonNode receipt(Actor a,String operation,String key,String hash){return repository.jdbc().query("SELECT request_hash,response_jsonb FROM ai.configuration_command_receipt WHERE organization_id=? AND operation=? AND idempotency_key=?",(rs,n)->new JsonNode[]{json.getNodeFactory().textNode(rs.getString(1)),read(rs.getString(2))},a.organizationId(),operation,key).stream().findFirst().map(x->{if(!x[0].asText().equals(hash))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);return x[1];}).orElse(null);}
    private void saveReceipt(Actor a,String operation,String key,String hash,UUID id,JsonNode response){repository.jdbc().update("INSERT INTO ai.configuration_command_receipt(id,organization_id,operation,idempotency_key,request_hash,resource_type,resource_id,response_jsonb,created_by) VALUES(?,?,?,?,?,'MODELING_POLICY',?,?,?)",UUID.randomUUID(),a.organizationId(),operation,key,hash,id,repository.pg(response),a.userId());}
    private JsonNode read(String raw){try{return json.readTree(raw);}catch(Exception e){throw new IllegalStateException(e);}}
    private String textOrNull(JsonNode node,String name){return node.hasNonNull(name)?node.path(name).asText():null;}
    private void requireKey(String key){if(key==null||key.isBlank()||key.length()>200)throw new ApiException(ApiErrorCode.BAD_REQUEST,"Idempotency-Key不能为空且不能超过200字符");}
    private Actor actor(){return ActorContext.required();}
}
