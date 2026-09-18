package com.jsd.aird.ai.rnd.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.rnd.prediction.PredictionRepository;
import com.jsd.aird.ai.rnd.training.FormulaModelV2Client;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.research.FormulaDesignContracts.*;

@Service
public class FormulaDesignService {
    private static final String RUN_TYPE = "FORMULA_PREDICTION";
    private final FormulaDesignRepository repository;
    private final PredictionRepository predictions;
    private final FormulaModelArtifactStore artifacts;
    private final FormulaModelV2Client compute;
    private final ConfigurationHashing hashing;
    private final OpsAsyncFacade jobs;
    private final AuditLogFacade audit;
    private final ObjectMapper json;
    private final boolean allowSynthetic;
    private final boolean inlineAcceptanceWorker;

    public FormulaDesignService(FormulaDesignRepository repository, PredictionRepository predictions,
                                FormulaModelArtifactStore artifacts, FormulaModelV2Client compute,
                                ConfigurationHashing hashing, OpsAsyncFacade jobs, AuditLogFacade audit,
                                ObjectMapper json,
                                @Value("${JSD_AIRD_AI_ALLOW_SYNTHETIC_PREDICTION:false}") boolean allowSynthetic,
                                @Value("${JSD_AIRD_R08_INLINE_ACCEPTANCE_WORKER:false}") boolean inlineAcceptanceWorker) {
        this.repository=repository; this.predictions=predictions; this.artifacts=artifacts; this.compute=compute;
        this.hashing=hashing; this.jobs=jobs; this.audit=audit; this.json=json; this.allowSynthetic=allowSynthetic; this.inlineAcceptanceWorker=inlineAcceptanceWorker;
    }

    public JsonNode context(List<UUID> targetIds) {
        var actor=actor(); var root=json.createObjectNode(); var catalog=json.createArrayNode();
        predictions.targetCatalog(actor.organizationId()).forEach(t -> {
            var n=json.createObjectNode().put("targetId",t.id().toString()).put("code",t.code()).put("name",t.name())
                    .put("category",Objects.toString(t.category(),"未分类")).put("valueType",t.valueType())
                    .put("available",t.modelId()!=null&&t.qualityPolicyId()!=null&&t.domainPolicyId()!=null);
            var reasons=json.createArrayNode(); if(t.modelId()==null)reasons.add("NO_ACTIVE_MODEL"); if(t.qualityPolicyId()==null)reasons.add("QUALITY_POLICY_NOT_READY"); if(t.domainPolicyId()==null)reasons.add("DOMAIN_POLICY_NOT_READY"); n.set("unavailableReasons",reasons); catalog.add(n);
        });
        root.set("targets",catalog);
        if(targetIds==null||targetIds.isEmpty()){root.set("selected",json.createArrayNode());return root;}
        var bindings=predictions.targetBindings(actor.organizationId(),targetIds.stream().filter(Objects::nonNull).distinct().toList());
        var selected=json.createArrayNode(); var inputMap=new LinkedHashMap<String,JsonNode>(); var materialMap=new LinkedHashMap<String,JsonNode>();var materialIds=new LinkedHashSet<String>();var firstBinding=true;
        for(var b:bindings){var n=json.createObjectNode().put("targetId",b.targetId().toString()).put("name",b.targetName()).put("valueType",b.valueType()).put("available",available(b));if(b.unit()!=null)n.put("unit",b.unit());n.set("classes",b.classes()==null?json.createArrayNode():b.classes());if("BINARY".equals(b.valueType()))n.put("positiveClass",b.definition().path("positiveClass").asText());n.set("operators",operators(b.valueType()));n.set("unavailableReasons",availabilityReasons(b));if(b.modelId()!=null)n.put("modelVersionId",b.modelId().toString());selected.add(n);b.frozenInputScheme().path("fields").forEach(f->{if(!"COMPOSITION".equals(f.path("valueType").asText()))inputMap.putIfAbsent(f.path("code").asText(),f);});var current=new LinkedHashSet<String>();b.frozenDictionary().path("materials").forEach(m->{current.add(m.path("materialId").asText());materialMap.putIfAbsent(m.path("materialId").asText(),m);});if(firstBinding){materialIds.addAll(current);firstBinding=false;}else materialIds.retainAll(current);}
        materialMap.keySet().removeIf(id->!materialIds.contains(id));materialMap.replaceAll((id,material)->{var enriched=material.deepCopy();predictions.material(id).ifPresent(m->{if(enriched instanceof ObjectNode o){o.put("code",m.code());o.put("name",m.name());o.put("category",m.category());}});return enriched;});
        root.set("selected",selected);root.set("requiredInputs",json.valueToTree(inputMap.values()));root.set("materialIntersection",json.valueToTree(materialMap.values()));root.put("candidateCountDefault",4);root.put("searchEngine","BayBE / controlled pool");return root;
    }

    public RunAccepted submit(JsonNode body, String idempotencyKey) {
        var actor=actor(); requireKey(idempotencyKey); if(body==null||!body.isObject())throw validation("配方预测请求不能为空");
        var goals=body.path("typedGoals"); if(!goals.isArray()||goals.isEmpty())throw validation("至少选择一个研发目标");
        var targetIds=new ArrayList<UUID>(); for(var goal:goals){var id=uuid(goal,"targetId");if(!targetIds.contains(id))targetIds.add(id);}
        var bindings=predictions.targetBindings(actor.organizationId(),targetIds); if(bindings.size()!=targetIds.size())throw new ApiException(ApiErrorCode.NOT_FOUND,"存在不存在的研发目标");
        var byTarget=new LinkedHashMap<UUID,PredictionRepository.TargetBinding>();bindings.forEach(b->byTarget.put(b.targetId(),b));
        for(var goal:goals)validateGoal(goal,byTarget.get(uuid(goal,"targetId")));
        validateFormulaRequest(body, bindings);
        var bindingMap=new LinkedHashMap<UUID,UUID>(); for(var b:bindings){if(!available(b))throw new ApiException(ApiErrorCode.NO_ACTIVE_MODEL,"所选目标尚无可用模型");var expected=uuidNullable((ArrayNode)goals, b.targetId());if(expected!=null&&!expected.equals(b.modelId()))throw new ApiException(ApiErrorCode.MODEL_VERSION_CHANGED,"模型版本已变化，请刷新后重试");bindingMap.put(b.targetId(),b.modelId());}
        var normalized=hashing.canonical(body); var requestHash=hashing.hash(normalized); var requestId=RequestIdHolder.currentOrUnknown(); var runId=UUID.randomUUID(); var seed=longSeed(requestHash);
        var bindingsNode=json.valueToTree(bindingMap); var config=json.createObjectNode().put("maxEvaluations",512).put("initialDesign",64).put("batchSize",64).put("diversityWeight",0.15);
        var inserted=repository.insertRun(runId,actor.organizationId(),actor.userId(),RUN_TYPE,idempotencyKey.strip(),requestId,requestHash,normalized,bindingsNode,seed,config);
        if(!inserted){var old=repository.byKey(actor.organizationId(),actor.userId(),idempotencyKey).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"配方预测运行不存在"));if(!old.requestHash().equals(requestHash))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);return accepted(old);}
        var payload=json.createObjectNode().put("organizationId",actor.organizationId().toString()).put("actorId",actor.userId().toString()).put("runId",runId.toString()).put("requestId",requestId);
        var jobId=jobs.enqueue(actor.organizationId(),"AI_FORMULA_DESIGN_V2",payload,"ai-formula-design:"+runId,45,3);repository.attachJob(actor.organizationId(),runId,jobId);
        audit.append(actor.organizationId(),actor.userId(),"AI_FORMULA_DESIGN_STARTED","AI_RESEARCH_RUN",runId,normalized);
        if (inlineAcceptanceWorker) Thread.startVirtualThread(() -> { try { execute(actor.organizationId(),runId); } catch (Exception ignored) { } });
        return new RunAccepted(runId,jobId,ExecutionStatus.QUEUED,bindingMap,1000);
    }

    public ResearchRun get(UUID id) { var a=actor(); var row=repository.byId(a.organizationId(),a.userId(),id).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"配方预测运行不存在"));return view(a.organizationId(),row); }
    public ResearchPage page(int page,int size,String status){var a=actor();var p=Math.max(0,page);var s=Math.min(100,Math.max(1,size));var rows=repository.page(a.organizationId(),a.userId(),p,s,status);return new ResearchPage(rows.stream().map(r->view(a.organizationId(),r)).toList(),repository.count(a.organizationId(),a.userId(),status),p,s);}
    public void fail(UUID org,UUID run,String code,String message){repository.fail(org,run,code,message);}

    public JsonNode execute(UUID org,UUID runId){var row=repository.any(org,runId).orElseThrow();if(!repository.markRunning(org,runId))return row.result();try{repository.progress(org,runId,15,"CHECKING_CONDITIONS");var request=row.request().deepCopy();var payload=buildRecommendPayload(org,row,request);var targetNames=targetNames(org,row);repository.progress(org,runId,35,"SEARCHING");var response=compute.recommend(payload);repository.progress(org,runId,85,"RULE_CHECKING");if("FAILED".equals(response.path("executionStatus").asText())){repository.fail(org,runId,response.path("code").asText("COMPUTE_UNAVAILABLE"),response.path("message").asText("计算服务执行失败"));return response;}var candidates=response.path("candidates");var saved=0;for(var c:candidates){if(!c.isObject())continue;var f=c.path("formula");var hash=hashing.hash(f);var resultNode=(ObjectNode)resultsObject(c.path("results"),targetNames);normalizeResultTargetIds(resultNode,row.bindings());var quality=c.path("quality");if(!quality.isObject()||quality.isEmpty())quality=qualityResults(org,row,resultNode);repository.saveCandidate(org,runId,++saved,f,c.path("process"),resultNode,quality,c.path("applicability"),c.path("ruleCheck"),c.path("evidence"),c.path("score").asDouble(),hash,request.path("fixedInputs"),request.path("searchSpace"),c.path("targetGate"),c.path("preferenceScore").asDouble(0),c.path("diversityScore").asDouble(0),request.path("targetTotal").asDouble(100));}var summary=json.createObjectNode().put("requestedCandidateCount",request.path("candidateCount").asInt(4)).put("actualCandidateCount",saved);for(var name:List.of("shortfallReason","shortfallReasonCode","rejectionSummary","actionHints","searchEngine","searchStrategy","searchEvidence"))if(response.has(name))summary.set(name,response.path(name));summary.set("candidates",repositoryCandidates(repository.candidates(org,runId)));var outcome=saved==0?"BLOCKED":saved<request.path("candidateCount").asInt(4)?"PARTIAL":"SUCCEEDED";repository.complete(org,runId,outcome,summary,null);return summary;}catch(Exception e){repository.fail(org,runId,e instanceof ApiException a?a.errorCode().code():"COMPUTE_UNAVAILABLE",String.valueOf(e.getMessage()));throw e instanceof RuntimeException r?r:new IllegalStateException(e);}}

    private JsonNode buildRecommendPayload(UUID org,FormulaDesignRepository.Row row,JsonNode request){ObjectNode p=(ObjectNode)request.deepCopy();p.put("contractVersion","formula-model.v2").put("requestId",row.requestId()).put("seed",row.seed()).put("runId",row.id().toString()).put("mode",RUN_TYPE);var bindings=json.createArrayNode();var orderedGoals=json.createArrayNode();var originalGoals=p.path("typedGoals");var goalsByTarget=new LinkedHashMap<String,JsonNode>();if(originalGoals.isArray())originalGoals.forEach(g->{if(g.isObject())goalsByTarget.put(g.path("targetId").asText(),g);});var it=row.bindings().fields();while(it.hasNext()){var e=it.next();var id=UUID.fromString(e.getKey());var b=predictions.targetBindings(org,List.of(id)).stream().findFirst().orElseThrow();bindings.add(binding(b));var goal=goalsByTarget.get(id.toString());if(goal!=null){var copy=goal.deepCopy();if(copy.isObject()){((ObjectNode)copy).put("targetId",b.targetVersionId().toString());((ObjectNode)copy).remove("expectedModelVersionId");((ObjectNode)copy).remove("valueType");}orderedGoals.add(copy);}}if(orderedGoals.isEmpty()&&originalGoals.isArray())originalGoals.forEach(g->{if(g.isObject()){var copy=g.deepCopy();((ObjectNode)copy).remove("expectedModelVersionId");((ObjectNode)copy).remove("valueType");orderedGoals.add(copy);}});p.set("goals",orderedGoals);p.remove("typedGoals");p.remove("targetTotal");p.remove("formulaBasis");p.set("modelBindings",bindings);if(!p.has("candidateCount"))p.put("candidateCount",4);if(!p.has("fixedInputs"))p.set("fixedInputs",json.createObjectNode());if(!p.has("constraints"))p.set("constraints",json.createObjectNode());if(!p.has("searchSpace"))p.set("searchSpace",json.createObjectNode());if(!p.has("baselineFormula"))p.set("baselineFormula",defaultFormula(bindings,request.path("targetTotal").asDouble(100)));return p;}
    public ObjectNode frozenModelBinding(PredictionRepository.TargetBinding b){var n=json.createObjectNode().put("modelVersionId",b.modelId().toString());var target=json.createObjectNode().put("id",b.targetVersionId().toString()).put("version",b.targetVersion()).put("sha256",Objects.toString(b.targetHash(),"0000000000000000000000000000000000000000000000000000000000000000")).put("code",b.targetCode()).put("valueType",b.valueType()).put("unit",Objects.toString(b.unit(),""));target.set("classes",b.classes());if("BINARY".equals(b.valueType()))target.put("positiveClass",b.definition().path("positiveClass").asText(b.classes().path(0).asText()));else target.putNull("positiveClass");target.set("observationSemantics",b.observationSemantics());n.set("target",target);try{var ref=artifacts.readRef("model",b.artifactKey(),b.artifactSha());n.set("modelBundle",json.createObjectNode().put("url",ref.url()).put("sha256",ref.sha256()));}catch(Exception e){throw new ApiException(ApiErrorCode.MODEL_ARTIFACT_INVALID,"模型制品无法读取");}n.set("inputScheme",b.frozenInputScheme());n.set("materialDictionary",b.frozenDictionary());n.set("preprocessing",b.frozenPreprocessing());n.set("applicabilityDomain",mergedDomain(b));return n;}
    private ObjectNode binding(PredictionRepository.TargetBinding b){return frozenModelBinding(b);}
    private ObjectNode mergedDomain(PredictionRepository.TargetBinding b){var n=b.modelDomain()!=null&&b.modelDomain().isObject()?((ObjectNode)b.modelDomain()).deepCopy():json.createObjectNode();if(b.domainPolicy()!=null&&b.domainPolicy().isObject())b.domainPolicy().fields().forEachRemaining(e->{if(!List.of("features","minimum","maximum").contains(e.getKey()))n.set(e.getKey(),e.getValue());});return n;}
    private ArrayNode operators(String type){var a=json.createArrayNode();if("CONTINUOUS".equals(type))List.of("AT_LEAST","AT_MOST","RANGE","MATCH","MAXIMIZE","MINIMIZE").forEach(a::add);else if("ORDINAL".equals(type))List.of("AT_LEAST","AT_MOST","MATCH").forEach(a::add);else a.add("MATCH");return a;}
    private JsonNode qualityResults(UUID org,FormulaDesignRepository.Row row,JsonNode results){var out=json.createObjectNode();var items=out.putArray("targets");var it=row.bindings().fieldNames();while(it.hasNext()){var targetId=UUID.fromString(it.next());var b=predictions.targetBindings(org,List.of(targetId)).stream().findFirst().orElse(null);if(b==null)continue;var q=items.addObject().put("targetId",targetId.toString()).put("targetName",b.targetName()).put("level",b.qualityPolicy().path("defaultTrustLevel").asText("MEDIUM")).put("explanation",b.qualityPolicy().path("defaultExplanation").asText("按已发布 QUALITY 策略评估"));q.put("policyVersion",b.qualityPolicyVersion());q.put("policyVersionId",Objects.toString(b.qualityPolicyId(),""));}return out;}
    private ArrayNode availabilityReasons(PredictionRepository.TargetBinding b){var a=json.createArrayNode();if(!available(b)) {if(b.modelId()==null||(!b.productionEligible()&&!allowSynthetic))a.add("NO_ACTIVE_MODEL");if(b.qualityPolicyId()==null||!"PUBLISHED".equals(b.qualityPolicyStatus()))a.add("QUALITY_POLICY_NOT_READY");if(b.domainPolicyId()==null||!"PUBLISHED".equals(b.domainPolicyStatus()))a.add("DOMAIN_POLICY_NOT_READY");}return a;}
    public boolean modelAvailable(PredictionRepository.TargetBinding b){return b.modelId()!=null&&(b.productionEligible()||allowSynthetic&&"SYNTHETIC".equals(b.dataNature()))&&b.qualityPolicyId()!=null&&"PUBLISHED".equals(b.qualityPolicyStatus())&&b.domainPolicyId()!=null&&"PUBLISHED".equals(b.domainPolicyStatus());}
    private boolean available(PredictionRepository.TargetBinding b){return modelAvailable(b);}
    private ResearchRun view(UUID org,FormulaDesignRepository.Row row){var candidates=repository.candidates(org,row.id()).stream().map(c->new Candidate(c.id(),c.candidateNo(),c.title(),c.formula(),c.results(),c.quality(),c.applicability(),c.ruleCheck(),c.score())).toList();var map=new LinkedHashMap<UUID,UUID>();row.bindings().fields().forEachRemaining(e->map.put(UUID.fromString(e.getKey()),UUID.fromString(e.getValue().asText())));var summary=row.result();var reason=summary.has("shortfallReason")?summary.path("shortfallReason"):row.error();var evidence=row.request().deepCopy();if(evidence.isObject()){((ObjectNode)evidence).set("modelBindings",row.bindings());((ObjectNode)evidence).put("seed",row.seed());((ObjectNode)evidence).set("searchConfig",row.searchConfig());if(summary.has("searchEngine"))((ObjectNode)evidence).set("searchEngine",summary.path("searchEngine"));if(summary.has("searchStrategy"))((ObjectNode)evidence).set("searchStrategy",summary.path("searchStrategy"));if(summary.has("searchEvidence"))((ObjectNode)evidence).set("searchEvidence",summary.path("searchEvidence"));((ObjectNode)evidence).put("contractVersion","ai-rnd.v1");}return new ResearchRun(row.id(),row.runType(),ExecutionStatus.valueOf(row.executionStatus()),row.outcomeStatus()==null?null:OutcomeStatus.valueOf(row.outcomeStatus()),row.progress(),row.stage(),row.request().path("candidateCount").asInt(4),candidates.size(),candidates,reason,summary.path("rejectionSummary"),summary.path("actionHints"),map,evidence,row.requestId(),row.updatedAt());}
    private RunAccepted accepted(FormulaDesignRepository.Row r){var map=new LinkedHashMap<UUID,UUID>();r.bindings().fields().forEachRemaining(e->map.put(UUID.fromString(e.getKey()),UUID.fromString(e.getValue().asText())));return new RunAccepted(r.id(),r.opsJobId(),ExecutionStatus.valueOf(r.executionStatus()),map,1000);}
    private ArrayNode repositoryCandidates(List<FormulaDesignRepository.CandidateRow> rows){var a=json.createArrayNode();rows.forEach(c->a.add(c.formula()));return a;}
    private void normalizeResultTargetIds(ObjectNode result, JsonNode bindings){
        if(result==null||bindings==null||!bindings.isObject())return;
        var modelToTarget=new LinkedHashMap<String,String>();bindings.fields().forEachRemaining(e->modelToTarget.put(e.getValue().asText(),e.getKey()));
        var targets=result.path("targets");
        if(!targets.isArray())return;
        for(var item:targets)if(item.isObject()){
            var modelId=item.path("modelVersionId").asText();
            var canonical=modelToTarget.get(modelId);
            if(canonical!=null)((ObjectNode)item).put("targetId",canonical);
        }
    }
    private JsonNode resultsObject(JsonNode value,Map<String,String> targetNames){
        var out=json.createObjectNode();var targets=out.putArray("targets");
        var idsByName=new LinkedHashMap<String,String>();
        targetNames.forEach((id,name)->idsByName.putIfAbsent(name,id));
        var source=value!=null&&value.isObject()&&value.path("targets").isArray()?value.path("targets"):value;
        if(source!=null&&source.isArray())source.forEach(item->{
            var copy=item.deepCopy();
            if(copy.isObject()){
                var rawId=copy.path("targetId").asText();
                var name=targetNames.get(rawId);
                if(name==null||name.isBlank())name=copy.path("targetName").asText();
                if((rawId.isBlank()||!targetNames.containsKey(rawId))&&name!=null&&!name.isBlank()){
                    var canonicalId=idsByName.get(name);if(canonicalId!=null)((ObjectNode)copy).put("targetId",canonicalId);
                }
                if(name!=null&&!name.isBlank())((ObjectNode)copy).put("targetName",name);
            }
            targets.add(copy);
        });
        return out;
    }
    private Map<String,String> targetNames(UUID org,FormulaDesignRepository.Row row){var names=new LinkedHashMap<String,String>();predictions.targetCatalog(org).forEach(t->names.put(t.id().toString(),t.name()));var it=row.bindings().fieldNames();while(it.hasNext()){var id=UUID.fromString(it.next());predictions.targetBindings(org,List.of(id)).stream().findFirst().ifPresent(b->{names.put(b.targetId().toString(),b.targetName());names.put(b.targetVersionId().toString(),b.targetName());});}return names;}
    private void validateFormulaRequest(JsonNode body,List<PredictionRepository.TargetBinding> bindings){
        var total=body.path("targetTotal"); if(!total.isNumber()||!Double.isFinite(total.asDouble())||total.asDouble()<=0)throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"目标合计必须是大于0的有限数值");
        if(!"MASS_PERCENT".equals(body.path("formulaBasis").asText("MASS_PERCENT")))throw new ApiException(ApiErrorCode.FORMULA_BASIS_UNRESOLVED,"当前配方预测只支持质量百分比基准");
        var formula=body.path("baselineFormula"); var components=formula.path("components"); if(!components.isArray()||components.isEmpty())throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"至少需要一种配方材料");
        var recorded=formula.path("recordedTotal"); if(!recorded.isNumber()||Math.abs(recorded.asDouble()-total.asDouble())>1e-6)throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"配方记录总量必须与目标合计一致");
        double sum=0; for(var c:components){if(!c.path("amountKnown").asBoolean(false)||!c.path("ratio").isNumber())throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"材料用量必须明确填写，空值不能作为搜索条件");if(c.path("ratio").asDouble()<0)throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"材料比例不能为负数");sum+=c.path("ratio").asDouble();}
        if(Math.abs(sum-total.asDouble())>1e-6)throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"固定配方比例合计必须等于目标合计");
        var intersection=new LinkedHashSet<String>(); boolean first=true; for(var binding:bindings){var ids=new LinkedHashSet<String>();binding.frozenDictionary().path("materials").forEach(m->ids.add(m.path("materialId").asText()));if(first){intersection.addAll(ids);first=false;}else intersection.retainAll(ids);}for(var c:components)if(!intersection.contains(c.path("materialId").asText()))throw new ApiException(ApiErrorCode.MATERIAL_SPACE_CONFLICT,"配方材料不在所选目标共同材料空间");
        var variableMaterials=body.path("searchSpace").path("variableMaterials");if(!variableMaterials.isArray()||variableMaterials.isEmpty())throw new ApiException(ApiErrorCode.MATERIAL_SPACE_CONFLICT,"至少需要一种允许调整的材料");
        for(var variable:variableMaterials){var id=variable.path("materialId").asText();if(!intersection.contains(id))throw new ApiException(ApiErrorCode.MATERIAL_SPACE_CONFLICT,"可变材料不在所选目标共同材料空间");if(!finite(variable.path("minimum"))||!finite(variable.path("maximum"))||variable.path("minimum").asDouble()<0||variable.path("minimum").asDouble()>variable.path("maximum").asDouble())throw new ApiException(ApiErrorCode.MATERIAL_SPACE_CONFLICT,"材料比例范围无效");}
        var fixed=body.path("fixedInputs");var variableInputs=body.path("searchSpace").path("variableInputs");
        for(var binding:bindings)for(var field:binding.frozenInputScheme().path("fields"))if(field.path("required").asBoolean()&&!"COMPOSITION".equals(field.path("valueType").asText())){var code=field.path("code").asText();if("POST_EXPERIMENT".equals(field.path("availabilityStage").asText()))throw new ApiException(ApiErrorCode.INPUT_SPACE_CONFLICT,"实验后字段不能作为配方搜索输入");if((!fixed.has(code)||fixed.path(code).isNull())&&!variableInputs.has(code))throw new ApiException(ApiErrorCode.INPUT_SPACE_CONFLICT,"缺少模型要求的实验前条件："+field.path("name").asText(code));if(variableInputs.has(code)){var range=variableInputs.path(code);if(!finite(range.path("minimum"))||!finite(range.path("maximum"))||range.path("minimum").asDouble()>range.path("maximum").asDouble())throw new ApiException(ApiErrorCode.INPUT_SPACE_CONFLICT,"实验条件范围无效："+field.path("name").asText(code));}}
        if(!body.path("candidateCount").isMissingNode()&&(body.path("candidateCount").asInt()<1||body.path("candidateCount").asInt()>16))throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"候选数量必须在1到16之间");
    }
    private ObjectNode defaultFormula(ArrayNode bindings,double total){var f=json.createObjectNode().put("basis","MASS_PERCENT").put("compositionComplete",true).put("recordedTotal",total);var cs=f.putArray("components");var seen=new LinkedHashSet<String>();bindings.forEach(b->b.path("materialDictionary").path("materials").forEach(m->{if(seen.add(m.path("materialId").asText())&&seen.size()<=3){} }));if(seen.isEmpty())seen.add("UNKNOWN");double ratio=total/seen.size();for(var id:seen)cs.add(json.createObjectNode().put("materialId",id).put("ratio",ratio).put("unit","PERCENT").put("amountKnown",true));return f;}
    private void validateGoal(JsonNode g,PredictionRepository.TargetBinding b){
        if(b==null)throw new ApiException(ApiErrorCode.NOT_FOUND,"研发目标不存在");
        var op=g.path("operator").asText();var allowed=operators(b.valueType());var supported=false;for(var x:allowed)if(op.equals(x.asText()))supported=true;
        if(!supported)throw new ApiException(ApiErrorCode.GOAL_OPERATOR_NOT_SUPPORTED,"该结果类型不支持所选运算");
        var value=g.path("value");
        if("CONTINUOUS".equals(b.valueType())){
            if(List.of("MAXIMIZE","MINIMIZE").contains(op))return;
            if("RANGE".equals(op)){if(!value.isArray()||value.size()!=2||!finite(value.get(0))||!finite(value.get(1))||value.get(0).asDouble()>value.get(1).asDouble())throw new ApiException(ApiErrorCode.GOAL_VALUE_INVALID,"请填写有效的目标区间");return;}
            if("MATCH".equals(op)){if(!value.isObject()||!finite(value.path("target"))||!finite(value.path("tolerance"))||value.path("tolerance").asDouble()<0)throw new ApiException(ApiErrorCode.GOAL_VALUE_INVALID,"请填写目标值和容差");return;}
            if(!finite(value))throw new ApiException(ApiErrorCode.GOAL_VALUE_INVALID,"目标值不能为空");return;
        }
        var label=value.isObject()?value.path("label").asText(""):value.asText("");
        if(label.isBlank())throw new ApiException(ApiErrorCode.GOAL_VALUE_INVALID,"请选择目标等级或类别");
        var exists=false;for(var c:b.classes())if(label.equals(c.asText()))exists=true;
        if(!exists)throw new ApiException(ApiErrorCode.GOAL_VALUE_INVALID,"所选等级或类别不属于当前Y定义");
        var probability=g.path("minimumProbability");if(!finite(probability)||probability.asDouble()<0||probability.asDouble()>1)throw new ApiException(ApiErrorCode.MINIMUM_PROBABILITY_REQUIRED,"请填写0到1之间的最低概率");
    }
    private boolean finite(JsonNode value){return value!=null&&value.isNumber()&&Double.isFinite(value.asDouble());}
    private UUID uuid(JsonNode n,String name){try{return UUID.fromString(n.path(name).asText());}catch(Exception e){throw new ApiException(ApiErrorCode.GOAL_VALUE_INVALID,"目标ID无效");}}
    private UUID uuidNullable(ArrayNode goals,UUID id){for(var g:goals)if(id.toString().equals(g.path("targetId").asText())&&g.hasNonNull("expectedModelVersionId"))try{return UUID.fromString(g.path("expectedModelVersionId").asText());}catch(Exception ignored){}return null;}
    private UUID findCreator(UUID org,UUID id){return repository.jdbc().queryForObject("SELECT created_by FROM ai.research_run_v2 WHERE organization_id=? AND id=?",UUID.class,org,id);}
    private Actor actor(){return ActorContext.required();} private void requireKey(String k){if(k==null||k.isBlank())throw validation("必须提供幂等键");} private ApiException validation(String m){return new ApiException(ApiErrorCode.VALIDATION_ERROR,m);} private long longSeed(String h){return java.nio.ByteBuffer.wrap(java.util.HexFormat.of().parseHex(h.substring(0,16))).getLong();}
}
