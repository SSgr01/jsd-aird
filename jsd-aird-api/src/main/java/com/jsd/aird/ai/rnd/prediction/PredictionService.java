package com.jsd.aird.ai.rnd.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.rnd.api.AiRndContracts.PredictionRequest;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ai.rnd.compute.ModelComputeClient;
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
import org.springframework.transaction.support.TransactionTemplate;

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
    private final ModelComputeClient compute;
    private final ConfigurationHashing hashing;
    private final ObjectMapper json;
    private final AuditLogFacade audit;
    private final TransactionTemplate transactions;
    private final Duration staleAfter;

    public PredictionService(PredictionRepository repository, FormulaModelArtifactStore artifacts,
                             ModelComputeClient compute, ConfigurationHashing hashing,
                             ObjectMapper json, AuditLogFacade audit,
                             PlatformTransactionManager tx,
                              @Value("${app.ai.rnd.prediction-stale-after:PT3M}") Duration staleAfter) {
        this.repository=repository;this.artifacts=artifacts;this.compute=compute;this.hashing=hashing;
        this.json=json;this.audit=audit;this.transactions=new TransactionTemplate(tx);
        this.staleAfter=staleAfter;
    }

    public JsonNode context(List<UUID> targetIds) {
        var actor=actor();var root=json.createObjectNode();var catalog=json.createArrayNode();
        var catalogRows=repository.targetCatalog(actor.organizationId());
        var catalogBindings=new LinkedHashMap<UUID,PredictionRepository.TargetBinding>();
        for(var binding:repository.targetBindings(actor.organizationId(),catalogRows.stream().map(PredictionRepository.TargetCatalog::id).toList()))catalogBindings.put(binding.targetId(),binding);
        for(var item:catalogRows){
            var binding=catalogBindings.get(item.id());
            var unavailable=binding==null?reasons(item.modelId()):gateAvailability(binding);
            var formalAvailable=unavailable.isEmpty()&&item.modelId()!=null;
            var value=json.createObjectNode().put("targetId",item.id().toString()).put("code",item.code())
                    .put("name",item.name()).put("category",item.category()).put("valueType",item.valueType())
                    .put("targetStatus",item.status()).put("formalPredictionAvailable",formalAvailable)
                    .put("predictionAvailable",formalAvailable);
            if(binding!=null&&binding.definition().has("modelSource"))value.set("modelSource",binding.definition().path("modelSource"));
            if(item.unit()!=null)value.put("unit",item.unit());
            if(item.testMethod()!=null)value.put("testMethod",item.testMethod());
            if(item.testStage()!=null)value.put("testStage",item.testStage());
            value.set("unavailableReasons",json.valueToTree(unavailable));catalog.add(value);
        }
        root.set("targets",catalog);
        if(targetIds==null||targetIds.isEmpty()){root.set("selected",json.createArrayNode());root.set("requiredInputs",json.createArrayNode());root.set("materials",json.createArrayNode());return root;}
        var bindings=repository.targetBindings(actor.organizationId(),distinct(targetIds));
        var selected=json.createArrayNode();var inputs=new LinkedHashMap<String,JsonNode>();var materials=new LinkedHashMap<String,JsonNode>();
        for(var binding:bindings){var entry=bindingSummary(binding);selected.add(entry);
            binding.frozenInputScheme().path("fields").forEach(field->{if(!"COMPOSITION".equals(field.path("valueType").asText()))mergeInputField(inputs,field,binding);});
        }
        for(var field:inputs.values()) {
            if(!(field instanceof ObjectNode object)||!"NUMBER".equals(field.path("valueType").asText()))continue;
            var ranges=json.createArrayNode();var feature="input:"+field.path("code").asText();
            for(var binding:bindings){var domain=binding.modelDomain();if(domain==null)continue;var names=domain.path("features");
                for(int index=0;index<names.size();index++)if((feature.equals(names.path(index).asText())||field.path("code").asText().equals(names.path(index).asText()))&&domain.path("minimum").path(index).isNumber()&&domain.path("maximum").path(index).isNumber()){
                    var range=json.createObjectNode().put("targetId",binding.targetId().toString()).put("targetName",binding.targetName());
                    range.set("minimum",domain.path("minimum").path(index));range.set("maximum",domain.path("maximum").path(index));ranges.add(range);
                }
            }
            object.set("supportedRanges",ranges);
        }
        root.set("selected",selected);root.set("requiredInputs",json.valueToTree(inputs.values()));root.set("materials",json.valueToTree(materials.values()));
        root.set("configurationConflicts",conflicts(bindings));return root;
    }

    public PredictionHttpResult predict(PredictionRequest request,String idempotencyKey){
        var a=actor();requireKey(idempotencyKey);validateRequest(request);var started=System.nanoTime();
        var requestNode=(ObjectNode)json.valueToTree(request);
        var targetIds=request.targets().stream().map(x->x.targetId()).toList();var bindings=repository.targetBindings(a.organizationId(),distinct(targetIds));
        var frozen=bindingDocument(bindings);var normalized=normalized(requestNode);var requestHash=hashing.hash(normalized);
        var id=UUID.randomUUID();var requestId=RequestIdHolder.currentOrUnknown();
        var won=Boolean.TRUE.equals(transactions.execute(ignored->repository.insertRunning(id,a.organizationId(),a.userId(),idempotencyKey,requestId,requestHash,normalized,frozen)));
        if(!won){var old=repository.byKey(a.organizationId(),a.userId(),idempotencyKey).orElseThrow();
            if(!old.requestHash().equals(requestHash))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            if("RUNNING".equals(old.executionStatus()))return new PredictionHttpResult(202,running(old.id(),old.requestId()));
            return new PredictionHttpResult(old.terminalHttpStatus(),old.terminalResponse());}
        audit.append(a.organizationId(),a.userId(),"AI_PERFORMANCE_PREDICTION_STARTED","AI_PREDICTION",id,frozen);
        var result=execute(id,requestId,request,bindings);
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

    private PredictionHttpResult execute(UUID id,String requestId,PredictionRequest request,List<PredictionRepository.TargetBinding> bindings){
        var byTarget=new HashMap<UUID,PredictionRepository.TargetBinding>();bindings.forEach(x->byTarget.put(x.targetId(),x));
        var results=json.createArrayNode();var scoreBindings=json.createArrayNode();var modelToBinding=new HashMap<String,PredictionRepository.TargetBinding>();
        var warnings=json.createArrayNode();
        for(var wanted:request.targets()){
            var b=byTarget.get(wanted.targetId());if(b==null){results.add(blocked(wanted.targetId(),null,"TARGET_NOT_FOUND","预测目标不存在",json.createObjectNode()));continue;}
            var reason=gate(b,wanted.expectedModelVersionId(),request);
            if(reason!=null){results.add(reason);continue;}
            try{var binding=scoreBinding(b);scoreBindings.add(binding);modelToBinding.put(b.modelId().toString(),b);}
            catch(ApiException e){results.add(failed(b.targetId(),b.modelId(),"MODEL_ARTIFACT_UNAVAILABLE",e.getMessage(),json.createObjectNode()));}
        }
        if(!scoreBindings.isEmpty()){
            var score=json.createObjectNode().put("contractVersion","formula-model.v2").put("requestId",requestId).put("seed",0).put("runId",id.toString());
            score.put("allowExtrapolation",true);
            score.set("inputs",json.valueToTree(request.inputs()));score.set("modelBindings",scoreBindings);
            try{var computed=compute.score(score);var returned=new HashSet<String>();
                for(var item:computed.path("results")){var modelId=item.path("modelVersionId").asText();returned.add(modelId);var b=modelToBinding.get(modelId);
                    if(b==null)continue;if("SUCCEEDED".equals(item.path("status").asText()))results.add(success(b,item));
                    else if("BLOCKED".equals(item.path("status").asText()))results.add(rewriteTarget(item,b.targetId()));
                    else results.add(rewriteTarget(item,b.targetId()));}
                for(var entry:modelToBinding.entrySet())if(!returned.contains(entry.getKey()))results.add(failed(entry.getValue().targetId(),entry.getValue().modelId(),"MODEL_RESPONSE_MISSING","计算服务未返回该目标结果",json.createObjectNode()));
            }catch(Exception exception){for(var b:modelToBinding.values())results.add(failed(b.targetId(),b.modelId(),"COMPUTE_UNAVAILABLE","计算服务暂不可用",json.createObjectNode().put("message",String.valueOf(exception.getMessage()))));}
        }
        var count=results.size();var succeeded=0;var failed=0;for(var x:results){if("SUCCEEDED".equals(x.path("status").asText()))succeeded++;if("FAILED".equals(x.path("status").asText()))failed++;}
        var simulation=false;
        var body=json.createObjectNode().put("predictionId",id.toString()).put("requestId",requestId)
                .put("simulation",simulation).put("predictionMode",simulation?"SIMULATION":"FORMAL");
        body.set("results",results);body.set("warnings",warnings);
        int http;if(failed>0&&succeeded==0){body.put("executionStatus","FAILED");body.putNull("outcomeStatus");http=503;body.put("code","PREDICTION_EXECUTION_FAILED");body.put("message","一个或多个正式模型无法执行");}
        else{body.put("executionStatus","SUCCEEDED");var outcome=succeeded==count?"SUCCEEDED":succeeded>0?"PARTIAL":"BLOCKED";body.put("outcomeStatus",outcome);http="BLOCKED".equals(outcome)&&count==1?422:200;}
        return new PredictionHttpResult(http,body);
    }

    private ObjectNode success(PredictionRepository.TargetBinding b,JsonNode computed){
        var result=json.createObjectNode().put("targetId",b.targetId().toString()).put("targetName",b.targetName()).put("status","SUCCEEDED")
            .put("modelVersionId",b.modelId().toString()).put("targetVersionId",b.targetVersionId().toString())
            .put("inputSchemeId",b.modelInputSchemeId().toString()).put("workbenchRunId",b.workbenchRunId().toString());
        var combinedWarnings=json.createArrayNode();if(computed.path("warnings").isArray())computed.path("warnings").forEach(combinedWarnings::add);
        result.set("prediction",computed.path("result"));result.set("applicability",computed.path("applicability"));result.set("warnings",combinedWarnings);
        result.set("evidence",evidence(b));return result;
    }
    private ObjectNode evidence(PredictionRepository.TargetBinding b){return json.createObjectNode().put("modelVersionId",b.modelId().toString()).put("workbenchRunId",b.workbenchRunId().toString());}
    private JsonNode gate(PredictionRepository.TargetBinding b,UUID expected,PredictionRequest request){
        if(!"ACTIVE".equals(b.targetStatus())||b.targetVersionId()==null)return blocked(b.targetId(),null,"TARGET_NOT_READY","预测目标尚未发布",json.createObjectNode());
        if(!modelAvailable(b))return blocked(b.targetId(),null,"NO_ACTIVE_MODEL","目标尚无可用于当前环境的模型",json.createObjectNode());
        if(expected!=null&&!expected.equals(b.modelId()))return blocked(b.targetId(),b.modelId(),"MODEL_VERSION_CHANGED","模型版本已变化，请刷新后重试",json.createObjectNode().put("currentModelVersionId",b.modelId().toString()));
        if(!"modeling-workbench.v1".equals(b.contractVersion()))return failed(b.targetId(),b.modelId(),"MODEL_CONTRACT_MISMATCH","模型契约版本不受支持",json.createObjectNode());
        if(!domainReady(b))return blocked(b.targetId(),b.modelId(),"MODEL_DOMAIN_UNAVAILABLE","模型训练信息不完整，请重新训练后重试",json.createObjectNode());

        var missing=json.createArrayNode();for(var field:b.frozenInputScheme().path("fields"))if(field.path("required").asBoolean()&&!"COMPOSITION".equals(field.path("valueType").asText())&&(request.inputs().get(field.path("code").asText())==null||request.inputs().get(field.path("code").asText()).isNull()))missing.add(field.path("code").asText());
        if(!missing.isEmpty())return blocked(b.targetId(),b.modelId(),"MISSING_REQUIRED_X","缺少模型要求的输入字段",json.createObjectNode().set("missingFields",missing));

        return null;
    }
    private boolean domainReady(PredictionRepository.TargetBinding b){var domain=b.modelDomain();return domain!=null&&domain.path("features").isArray()&&domain.path("minimum").isArray()&&domain.path("maximum").isArray()&&domain.path("nearBoundaryRatio").isNumber();}
    private ObjectNode scoreBinding(PredictionRepository.TargetBinding b){return com.jsd.aird.ai.rnd.modeling.WorkbenchModelBinding.create(b,json,artifacts,false);}
    private ObjectNode mergedDomain(PredictionRepository.TargetBinding b){return (ObjectNode)b.modelDomain().deepCopy();}
    static ObjectNode rewriteTarget(JsonNode source,UUID target){
        var out=((ObjectNode)source).deepCopy();out.put("targetId",target.toString());
        if(!out.hasNonNull("errorCode")&&out.hasNonNull("code"))out.set("errorCode",out.get("code"));
        return out;
    }
    private ObjectNode blocked(UUID target,UUID model,String code,String message,JsonNode detail){var x=json.createObjectNode().put("targetId",target.toString()).put("status","BLOCKED").put("errorCode",code).put("message",message);if(model!=null)x.put("modelVersionId",model.toString());x.set("detail",detail);return x;}
    private ObjectNode failed(UUID target,UUID model,String code,String message,JsonNode detail){var x=json.createObjectNode().put("targetId",target.toString()).put("status","FAILED").put("errorCode",code).put("message",message);if(model!=null)x.put("modelVersionId",model.toString());x.set("detail",detail);return x;}
    private ObjectNode bindingSummary(PredictionRepository.TargetBinding b){
        var x=json.createObjectNode().put("targetId",b.targetId().toString()).put("name",b.targetName()).put("category",b.category())
            .put("valueType",b.valueType()).put("available",gateAvailability(b).isEmpty()).put("formulaRequirement","NOT_USED");
        if(b.modelId()!=null)x.put("modelVersionId",b.modelId().toString());
        x.set("unavailableReasons",json.valueToTree(gateAvailability(b)));x.set("fixedConditions",fixedConditions(b));
        if (b.definition().has("modelSource")) x.set("modelSource", b.definition().path("modelSource").deepCopy());
        return x;
    }
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
    public List<String> gateAvailability(PredictionRepository.TargetBinding b){var out=new ArrayList<String>();if(!modelAvailable(b))out.add("NO_ACTIVE_MODEL");else if(!domainReady(b))out.add("MODEL_DOMAIN_UNAVAILABLE");return out;}
    private boolean modelAvailable(PredictionRepository.TargetBinding b){return b.modelId()!=null&&"modeling-workbench.v1".equals(b.contractVersion());}
    private List<String> reasons(UUID model){return model==null?List.of("NO_ACTIVE_MODEL"):List.of();}
    private ArrayNode conflicts(List<PredictionRepository.TargetBinding> bindings){var byCode=new HashMap<String,Set<String>>();for(var b:bindings)for(var field:b.frozenInputScheme().path("fields"))byCode.computeIfAbsent(field.path("code").asText(),k->new HashSet<>()).add(field.path("valueType").asText()+"|"+field.path("unit").asText());var out=json.createArrayNode();byCode.forEach((code,defs)->{if(defs.size()>1)out.add(json.createObjectNode().put("fieldCode",code).set("definitions",json.valueToTree(defs)));});return out;}
    private ObjectNode bindingDocument(List<PredictionRepository.TargetBinding> bindings){var root=json.createObjectNode();var list=json.createArrayNode();bindings.forEach(x->list.add(bindingSummary(x)));root.set("targets",list);return root;}
    private ObjectNode running(UUID id,String requestId){return json.createObjectNode().put("predictionRecordId",id.toString()).put("requestId",requestId).put("executionStatus","RUNNING").putNull("outcomeStatus").put("pollAfterMs",1000);}
    private JsonNode normalized(JsonNode request){return hashing.canonical(request);}
    private List<UUID> distinct(List<UUID> ids){return ids.stream().filter(Objects::nonNull).distinct().sorted().toList();}
    private void validateRequest(PredictionRequest request){if(request==null||request.targets()==null||request.targets().isEmpty())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"至少选择一个预测目标");if(request.inputs()==null)throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"实验前条件不能为空");if(distinct(request.targets().stream().map(x->x.targetId()).toList()).size()!=request.targets().size())throw new ApiException(ApiErrorCode.VALIDATION_ERROR,"预测目标不能重复");}
    private JsonNode read(String raw){try{return json.readTree(raw);}catch(Exception e){throw new IllegalStateException(e);}}
    private String textOrNull(JsonNode node,String name){return node.hasNonNull(name)?node.path(name).asText():null;}
    private void requireKey(String key){if(key==null||key.isBlank()||key.length()>200)throw new ApiException(ApiErrorCode.BAD_REQUEST,"Idempotency-Key不能为空且不能超过200字符");}
    private Actor actor(){return ActorContext.required();}
}
