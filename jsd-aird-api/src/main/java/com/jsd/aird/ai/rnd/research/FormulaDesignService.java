package com.jsd.aird.ai.rnd.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.rnd.prediction.PredictionRepository;
import com.jsd.aird.ai.rnd.prediction.PredictionService;
import com.jsd.aird.ai.rnd.compute.ModelComputeClient;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ai.rnd.modeling.WorkbenchFormulaSupport;
import com.jsd.aird.ai.rnd.formula.FormulaNormalizationService;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
    private final PredictionService predictionContext;
    private final FormulaModelArtifactStore artifacts;
    private final ModelComputeClient compute;
    private final ConfigurationHashing hashing;
    private final OpsAsyncFacade jobs;
    private final AuditLogFacade audit;
    private final ObjectMapper json;
    private final FormulaNormalizationService formulaNormalization;
    private final boolean inlineAcceptanceWorker;

    public FormulaDesignService(FormulaDesignRepository repository, PredictionRepository predictions, PredictionService predictionContext,
                                FormulaModelArtifactStore artifacts, ModelComputeClient compute,
                                ConfigurationHashing hashing, OpsAsyncFacade jobs, AuditLogFacade audit,
                                ObjectMapper json, FormulaNormalizationService formulaNormalization,
                                @Value("${JSD_AIRD_R08_INLINE_ACCEPTANCE_WORKER:false}") boolean inlineAcceptanceWorker) {
        this.repository=repository; this.predictions=predictions; this.predictionContext=predictionContext; this.artifacts=artifacts; this.compute=compute;
        this.hashing=hashing; this.jobs=jobs; this.audit=audit; this.json=json; this.formulaNormalization=formulaNormalization;  this.inlineAcceptanceWorker=inlineAcceptanceWorker;
    }

    public JsonNode context(List<UUID> targetIds) {
        var org = actor().organizationId();
        var root = (ObjectNode) predictionContext.context(targetIds).deepCopy();
        var ids = new ArrayList<UUID>();
        root.path("targets").forEach(t -> ids.add(UUID.fromString(t.path("targetId").asText())));
        var all = new LinkedHashMap<String,PredictionRepository.TargetBinding>();
        predictions.targetBindings(org,ids).forEach(b -> all.put(b.targetId().toString(),b));
        for (var item : root.path("targets")) if (item instanceof ObjectNode target) {
            var binding = all.get(target.path("targetId").asText());
            if (binding != null) enrichTarget(target,binding);
            else target.put("available",false);
        }
        var bindings = new ArrayList<PredictionRepository.TargetBinding>();
        for (var item : root.path("selected")) if (item instanceof ObjectNode target) {
            var binding = all.get(target.path("targetId").asText());
            if (binding != null) {
                enrichTarget(target,binding);
                if (availabilityReasons(binding).isEmpty()) bindings.add(binding);
            }
        }
        var availableIds = bindings.stream().map(b -> b.targetId().toString()).collect(java.util.stream.Collectors.toSet());
        var conditionDefinitions = new LinkedHashMap<String,java.util.Set<String>>();
        for (var binding : bindings) for (var field : binding.frozenInputScheme().path("fields"))
            conditionDefinitions.computeIfAbsent(field.path("code").asText(), ignored -> new java.util.LinkedHashSet<>())
                    .add(field.path("valueType").asText() + "|" + field.path("unit").asText());
        var conditionConflicts = json.createArrayNode();
        conditionDefinitions.forEach((code, definitions) -> {
            if (definitions.size() > 1) conditionConflicts.add(json.createObjectNode().put("fieldCode",code).set("definitions",json.valueToTree(definitions)));
        });
        root.set("configurationConflicts", conditionConflicts);
        var requiredInputs = (ArrayNode) root.path("requiredInputs");
        for (int index = requiredInputs.size() - 1; index >= 0; index--) {
            var field = requiredInputs.path(index);
            if (field.path("requiredByTargets").isArray() && !field.path("requiredByTargets").isEmpty()) {
                boolean used = false;
                for (var target : field.path("requiredByTargets")) if (availableIds.contains(target.path("targetId").asText())) { used = true; break; }
                if (!used) requiredInputs.remove(index);
            }
        }
        var materials = commonMaterials(bindings);
        var ratioCodes=new LinkedHashSet<String>();
        for(var b:bindings)if(WorkbenchFormulaSupport.supports(b.contractVersion()))
            WorkbenchFormulaSupport.materialIds(b.frozenInputScheme()).forEach(id->ratioCodes.add("formula:"+id));
        var conditions=json.createArrayNode();
        for(var field:root.path("requiredInputs")){
            if(!ratioCodes.contains(field.path("code").asText()))conditions.add(field);
        }
        root.set("requiredInputs",conditions);
        materials.forEach(m -> {
            var ranges = json.createArrayNode();
            for (var b : bindings) addRange(ranges,b,"formula:"+m.path("materialId").asText());
            ((ObjectNode)m).set("supportedRanges",ranges);
        });
        var commonIds=new LinkedHashSet<String>();materials.forEach(m->commonIds.add(m.path("materialId").asText()));
        if(bindings.stream().anyMatch(b->WorkbenchFormulaSupport.supports(b.contractVersion())
                && !commonIds.containsAll(WorkbenchFormulaSupport.materialIds(b.frozenInputScheme()))))
            root.withArray("configurationConflicts").addObject().put("fieldCode","FORMULA_MATERIALS");
        for (var field : root.path("requiredInputs")) if (field instanceof ObjectNode f && "NUMBER".equals(f.path("valueType").asText()) && f.path("supportedRanges").isEmpty()) {
            var alias = switch (f.path("code").asText()) {
                case "COATING_SOLIDS", "COATING_SOLIDS_PCT" -> "input:facts:solidContent";
                case "TEMPERATURE", "TEMPERATURE_C", "TEST_TEMPERATURE" -> "input:conditions:temperature";
                case "HUMIDITY", "HUMIDITY_RH_PCT" -> "input:conditions:humidity";
                case "UV_ENERGY", "UV_ENERGY_MJ_CM2" -> "input:process:uvEnergy";
                case "UVA_INTENSITY", "UVA_INTENSITY_MW_CM2" -> "input:process:uvIntensity";
                default -> "input:"+f.path("code").asText();
            };
            var ranges = json.createArrayNode();
            for (var b : bindings) addRange(ranges,b,alias);
            f.set("supportedRanges",ranges);
        }
        for(var field:root.path("requiredInputs"))if(field instanceof ObjectNode f){
            java.util.LinkedHashSet<String> categories=null;
            boolean required=f.path("required").asBoolean();
            for(var binding:bindings)for(var raw:binding.frozenInputScheme().path("fields"))if(raw.path("code").asText().equals(f.path("code").asText())){
                required=required||raw.path("required").asBoolean();
                if("CATEGORY".equals(raw.path("valueType").asText())){
                    var allowed=raw.path("allowedValues").isArray()?raw.path("allowedValues"):raw.path("encoding").path("allowedValues");
                    if(!allowed.isArray())allowed=raw.path("encoding").path("categoryValues");
                    if(allowed.isArray()&&!allowed.isEmpty()){
                        var values=new LinkedHashSet<String>();allowed.forEach(value->values.add(value.asText()));
                        if(categories==null)categories=values;else categories.retainAll(values);
                    }
                }
            }
            f.put("required",required);
            if(categories!=null){
                f.set("allowedValues",json.valueToTree(categories));
                if(categories.isEmpty())root.withArray("configurationConflicts").add(json.createObjectNode().put("fieldCode",f.path("code").asText()));
            }
        }
        root.remove("materials"); root.set("materialIntersection",materials);
        root.put("candidateCountDefault",4);
        return root;
    }

    private void enrichTarget(ObjectNode target, PredictionRepository.TargetBinding binding) {
        var reasons = availabilityReasons(binding);
        target.put("available",reasons.isEmpty()); target.set("unavailableReasons",reasons);
        target.set("missingFormulaMaterials",binding.frozenDictionary().path("missingMaterials").isArray()?binding.frozenDictionary().path("missingMaterials"):json.createArrayNode());
        target.set("formulaIssues",binding.frozenDictionary().path("compositionIssues").isArray()?binding.frozenDictionary().path("compositionIssues"):json.createArrayNode());

        target.set("operators",operators(binding.valueType()));
        target.set("classes",binding.classes()==null?json.createArrayNode():binding.classes());
        if (binding.unit()!=null) target.put("unit",binding.unit());
        if (binding.modelId()!=null) target.put("modelVersionId",binding.modelId().toString());
        if (binding.definition().has("modelSource")) target.set("modelSource", binding.definition().path("modelSource").deepCopy());
        for (var key : List.of("testMethod","testStage","positiveClass","decisionThreshold"))
            if (binding.definition().hasNonNull(key)) target.set(key,binding.definition().get(key));
    }

    private ArrayNode commonMaterials(List<PredictionRepository.TargetBinding> bindings) {
        var materials = new LinkedHashMap<String,JsonNode>(); var first = true;
        for (var binding : bindings) {
            var ids = new LinkedHashSet<String>();
            for (var raw : binding.frozenDictionary().path("materials")) {
                var id = raw.path("materialId").asText();
                if(binding.modelDomain()!=null&&binding.modelDomain().path("features").isArray()&&!binding.modelDomain().path("features").isEmpty()){
                    boolean learned=false;
                    var prefix="formula:";
                    for(var feature:binding.modelDomain().path("features"))if(feature.asText().equals(prefix+id)){learned=true;break;}
                    if(!learned)continue;
                }
                ids.add(id);
                if (first) {
                    var material = raw.deepCopy();
                    for(var field:binding.frozenInputScheme().path("fields"))
                        if(id.equals(WorkbenchFormulaSupport.materialId(field)) && material instanceof ObjectNode o)o.set("sourceColumnName",field.path("name"));
                    materials.put(id,material);
                }
            }
            materials.keySet().retainAll(ids); first=false;
        }
        return json.valueToTree(materials.values());
    }

    private void addRange(ArrayNode ranges, PredictionRepository.TargetBinding binding, String feature) {
        var domain=binding.modelDomain();if(domain==null)return;
        for(int i=0;i<domain.path("features").size();i++) {
            if(!feature.equals(domain.path("features").path(i).asText())||!finite(domain.path("minimum").path(i))||!finite(domain.path("maximum").path(i)))continue;
            var range=json.createObjectNode().put("targetId",binding.targetId().toString()).put("targetName",binding.targetName());
            range.set("minimum",domain.path("minimum").path(i));range.set("maximum",domain.path("maximum").path(i));ranges.add(range);
        }
    }

    private boolean usesFormula(PredictionRepository.TargetBinding binding){return !WorkbenchFormulaSupport.materialIds(binding.frozenInputScheme()).isEmpty();}

    public RunAccepted submit(JsonNode body, String idempotencyKey) {
        var actor=actor(); requireKey(idempotencyKey); if(body==null||!body.isObject())throw validation("配方预测请求不能为空");
        body=normalizeDesignRequest(body);
        var normalized=hashing.canonical(body); var requestHash=hashing.hash(normalized);
        var previous=repository.byKey(actor.organizationId(),actor.userId(),idempotencyKey.strip());
        if(previous.isPresent()) {
            if(!previous.get().requestHash().equals(requestHash))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            return accepted(previous.get());
        }
        var goals=body.path("typedGoals"); if(!goals.isArray()||goals.isEmpty())throw validation("至少选择一个研发目标");
        var targetIds=new ArrayList<UUID>(); for(var goal:goals){var id=uuid(goal,"targetId");if(!targetIds.contains(id))targetIds.add(id);}
        var bindings=predictions.targetBindings(actor.organizationId(),targetIds); if(bindings.size()!=targetIds.size())throw new ApiException(ApiErrorCode.NOT_FOUND,"存在不存在的研发目标");
        for(var binding:bindings)if(!available(binding))throw new ApiException(usesFormula(binding)?ApiErrorCode.NO_ACTIVE_MODEL:ApiErrorCode.MATERIAL_SPACE_CONFLICT,"所选指标没有可用于配方反推的模型，请检查模型状态与材料输入");
        var byTarget=new LinkedHashMap<UUID,PredictionRepository.TargetBinding>();bindings.forEach(b->byTarget.put(b.targetId(),b));
        for(var goal:goals)validateGoal(goal,byTarget.get(uuid(goal,"targetId")));
        validateFormulaRequest(body, bindings);
        var bindingMap=new LinkedHashMap<UUID,UUID>(); for(var b:bindings){if(!available(b))throw new ApiException(ApiErrorCode.NO_ACTIVE_MODEL,"所选目标尚无可用模型");var expected=uuidNullable((ArrayNode)goals, b.targetId());if(expected!=null&&!expected.equals(b.modelId()))throw new ApiException(ApiErrorCode.MODEL_VERSION_CHANGED,"模型版本已变化，请刷新后重试");bindingMap.put(b.targetId(),b.modelId());}
        var requestId=RequestIdHolder.currentOrUnknown(); var runId=UUID.randomUUID(); var seed=longSeed(requestHash);
        var display=context(targetIds);
        if(!display.path("configurationConflicts").isEmpty())throw new ApiException(ApiErrorCode.INPUT_SPACE_CONFLICT,"所选指标的实验条件类型、单位或可选类别不同，请调整指标组合");
        var storedRequest=(ObjectNode)normalized.deepCopy(); storedRequest.set("displayContext",display);
        storedRequest.put("simulation",false);
        var bindingsNode=json.valueToTree(bindingMap); var config=json.createObjectNode().put("maxEvaluations",512).put("initialDesign",64).put("batchSize",64).put("diversityWeight",0.15);
        var inserted=repository.insertRun(runId,actor.organizationId(),actor.userId(),RUN_TYPE,idempotencyKey.strip(),requestId,requestHash,storedRequest,bindingsNode,seed,config);
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

    public JsonNode execute(UUID org,UUID runId){var row=repository.any(org,runId).orElseThrow();if(!repository.markRunning(org,runId))return row.result();try{repository.progress(org,runId,15,"CHECKING_CONDITIONS");var request=row.request().deepCopy();var payload=buildRecommendPayload(org,row,request);var targetNames=targetNames(org,row);repository.progress(org,runId,35,"SEARCHING");var response=compute.recommend(payload);repository.progress(org,runId,85,"RULE_CHECKING");if("FAILED".equals(response.path("executionStatus").asText())){repository.fail(org,runId,response.path("code").asText("COMPUTE_UNAVAILABLE"),response.path("message").asText("计算服务执行失败"));return response;}var candidates=response.path("candidates");var saved=0;for(var c:candidates){if(!c.isObject()||!candidateMatchesRequest(c,request,payload))continue;var f=c.path("formula");var inputs=c.has("inputs")?c.path("inputs"):c.path("process");var document=json.createObjectNode();document.set("formula",f);document.set("inputs",inputs);var hash=hashing.hash(document);var resultNode=(ObjectNode)resultsObject(c.path("results"),targetNames);normalizeResultTargetIds(resultNode,row.bindings());var quality=c.path("quality");repository.saveCandidate(org,runId,++saved,f,inputs,resultNode,quality,c.path("applicability"),c.path("ruleCheck"),c.path("evidence"),c.path("score").asDouble(),hash,request.path("fixedInputs"),request.path("searchSpace"),c.path("targetGate"),c.path("preferenceScore").asDouble(0),c.path("diversityScore").asDouble(0),request.path("targetTotal").asDouble(100));}var simulation=false;var summary=json.createObjectNode().put("requestedCandidateCount",request.path("candidateCount").asInt(4)).put("actualCandidateCount",saved).put("simulation",simulation);if(simulation)summary.put("predictionMode","SIMULATION");for(var name:List.of("shortfallReason","shortfallReasonCode","rejectionSummary","actionHints","searchEngine","searchStrategy","searchEvidence"))if(response.has(name))summary.set(name,response.path(name));summary.set("candidates",repositoryCandidates(repository.candidates(org,runId)));var outcome=saved==0?"BLOCKED":saved<request.path("candidateCount").asInt(4)?"PARTIAL":"SUCCEEDED";repository.complete(org,runId,outcome,summary,null);return summary;}catch(Exception e){repository.fail(org,runId,e instanceof ApiException a?a.errorCode().code():"COMPUTE_UNAVAILABLE",String.valueOf(e.getMessage()));throw e instanceof RuntimeException r?r:new IllegalStateException(e);}}

    private JsonNode buildRecommendPayload(UUID org,FormulaDesignRepository.Row row,JsonNode request){ObjectNode p=(ObjectNode)request.deepCopy();p.put("contractVersion","formula-model.v2").put("requestId",row.requestId()).put("seed",row.seed()).put("runId",row.id().toString()).put("mode",RUN_TYPE);var bindings=json.createArrayNode();var orderedGoals=json.createArrayNode();var originalGoals=p.path("typedGoals");var goalsByTarget=new LinkedHashMap<String,JsonNode>();if(originalGoals.isArray())originalGoals.forEach(g->{if(g.isObject())goalsByTarget.put(g.path("targetId").asText(),g);});var it=row.bindings().fields();while(it.hasNext()){var e=it.next();var id=UUID.fromString(e.getKey());var b=predictions.targetBindings(org,List.of(id)).stream().findFirst().orElseThrow();bindings.add(binding(b));if(!Objects.equals(b.modelId(),UUID.fromString(e.getValue().asText())))throw new ApiException(ApiErrorCode.MODEL_VERSION_CHANGED);var goal=goalsByTarget.get(id.toString());if(goal!=null){var copy=goal.deepCopy();if(copy.isObject()){((ObjectNode)copy).put("targetId",b.targetVersionId().toString());((ObjectNode)copy).remove("expectedModelVersionId");((ObjectNode)copy).remove("valueType");}orderedGoals.add(copy);}}if(orderedGoals.isEmpty()&&originalGoals.isArray())originalGoals.forEach(g->{if(g.isObject()){var copy=g.deepCopy();((ObjectNode)copy).remove("expectedModelVersionId");((ObjectNode)copy).remove("valueType");orderedGoals.add(copy);}});p.set("goals",orderedGoals);p.remove("typedGoals");p.remove("targetTotal");p.remove("formulaBasis");p.remove(List.of("simulation","displayContext"));p.set("modelBindings",bindings);if(!p.has("candidateCount"))p.put("candidateCount",4);if(!p.has("fixedInputs"))p.set("fixedInputs",json.createObjectNode());if(!p.has("constraints"))p.set("constraints",json.createObjectNode());if(!p.has("searchSpace"))p.set("searchSpace",json.createObjectNode());p.set("baselineFormula",formulaNormalization.forModelCompute(p.path("baselineFormula")));return p;}
    public ObjectNode frozenModelBinding(PredictionRepository.TargetBinding b){return com.jsd.aird.ai.rnd.modeling.WorkbenchModelBinding.create(b,json,artifacts,true);}
    private ObjectNode binding(PredictionRepository.TargetBinding b){return frozenModelBinding(b);}
    private ArrayNode operators(String type){var a=json.createArrayNode();if("CONTINUOUS".equals(type))List.of("AT_LEAST","AT_MOST","RANGE","MATCH","MAXIMIZE","MINIMIZE").forEach(a::add);else if("ORDINAL".equals(type))List.of("AT_LEAST","AT_MOST","MATCH").forEach(a::add);else a.add("MATCH");return a;}
    private ArrayNode availabilityReasons(PredictionRepository.TargetBinding binding) {
        var reasons = json.valueToTree(predictionContext.gateAvailability(binding));
        var out = reasons instanceof ArrayNode a ? a : json.createArrayNode();
        if (binding.modelId()!=null && !usesFormula(binding)) out.add("MODEL_NOT_FORMULA_ENABLED");
        if(binding.modelId()!=null && WorkbenchFormulaSupport.supports(binding.contractVersion())){
            if(WorkbenchFormulaSupport.materialId(binding.definition().path("modelSource").path("yColumn"))!=null)out.add("FORMULA_TARGET_IS_RATIO");
            if(usesFormula(binding) && "INCOMPLETE".equals(binding.frozenDictionary().path("compositionStatus").asText()))out.add("MODEL_FORMULA_INPUTS_INCOMPLETE");
        }
        if (binding.modelId()!=null && usesFormula(binding) && !formulaMaterialsConsistent(binding)) out.add("MODEL_MATERIALS_INCONSISTENT");
        if ("RETIRED".equals(binding.targetStatus())) out.add("TARGET_RETIRED");
        return out;
    }

    private boolean formulaMaterialsConsistent(PredictionRepository.TargetBinding binding){
        var ids=new LinkedHashSet<String>();binding.frozenDictionary().path("materials").forEach(material->ids.add(material.path("materialId").asText()));
        return ids.equals(WorkbenchFormulaSupport.materialIds(binding.frozenInputScheme()));
    }
    public boolean modelAvailable(PredictionRepository.TargetBinding b){return b.modelId()!=null&&WorkbenchFormulaSupport.supports(b.contractVersion());}
    private boolean available(PredictionRepository.TargetBinding b){return availabilityReasons(b).isEmpty();}
    private ResearchRun view(UUID org,FormulaDesignRepository.Row row){var candidates=repository.candidates(org,row.id()).stream().map(c->new Candidate(c.id(),c.candidateNo(),c.title(),c.formula(),c.inputs(),c.results(),c.quality(),c.applicability(),c.ruleCheck(),c.score())).toList();var map=new LinkedHashMap<UUID,UUID>();row.bindings().fields().forEachRemaining(e->map.put(UUID.fromString(e.getKey()),UUID.fromString(e.getValue().asText())));var summary=row.result();var reason=summary.has("shortfallReason")?summary.path("shortfallReason"):row.error();var simulation=summary.path("simulation").asBoolean()||row.request().path("simulation").asBoolean()||false;var evidence=row.request().deepCopy();if(evidence.isObject()){((ObjectNode)evidence).set("modelBindings",row.bindings());((ObjectNode)evidence).put("seed",row.seed());((ObjectNode)evidence).set("searchConfig",row.searchConfig());((ObjectNode)evidence).put("simulation",simulation);if(summary.has("searchEngine"))((ObjectNode)evidence).set("searchEngine",summary.path("searchEngine"));if(summary.has("searchStrategy"))((ObjectNode)evidence).set("searchStrategy",summary.path("searchStrategy"));if(summary.has("searchEvidence"))((ObjectNode)evidence).set("searchEvidence",summary.path("searchEvidence"));((ObjectNode)evidence).set("displayContext",row.request().path("displayContext"));((ObjectNode)evidence).put("contractVersion","ai-rnd.v1");}return new ResearchRun(row.id(),row.runType(),ExecutionStatus.valueOf(row.executionStatus()),row.outcomeStatus()==null?null:OutcomeStatus.valueOf(row.outcomeStatus()),row.progress(),row.stage(),row.request().path("candidateCount").asInt(4),candidates.size(),candidates,reason,summary.path("rejectionSummary"),summary.path("actionHints"),map,evidence,row.requestId(),row.updatedAt(),simulation,simulation?"SIMULATION":"FORMAL");}
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
    private boolean candidateMatchesRequest(JsonNode candidate,JsonNode request,JsonNode payload) {
        boolean mapped=false;
        for(var binding:payload.path("modelBindings"))for(var field:binding.path("inputScheme").path("fields"))
            if(field.path("encoding").has("formulaMaterialId"))mapped=true;
        if(!mapped)return true;
        var formula=candidate.path("formula");var components=formula.path("components");
        if(!formula.path("compositionComplete").asBoolean()||!components.isArray()||components.isEmpty())return false;
        var expected=new LinkedHashMap<String,Double>();
        request.path("baselineFormula").path("components").forEach(c->expected.put(c.path("materialId").asText(),c.path("ratio").asDouble()));
        var bounds=new LinkedHashMap<String,JsonNode>();
        request.path("searchSpace").path("variableMaterials").forEach(v->bounds.put(v.path("materialId").asText(),v));
        var actual=new LinkedHashSet<String>();double total=0;
        for(var component:components){
            var id=component.path("materialId").asText();var ratio=component.path("ratio");
            if(!actual.add(id)||!expected.containsKey(id)||!finite(ratio)||ratio.asDouble()<0)return false;
            if(!List.of("PERCENT","%").contains(component.path("unit").asText()))return false;
            var value=ratio.asDouble();total+=value;var range=bounds.get(id);
            if(range==null){if(Math.abs(value-expected.get(id))>1e-6)return false;}
            else if(value<range.path("minimum").asDouble()-1e-6||value>range.path("maximum").asDouble()+1e-6)return false;
        }
        if(!actual.equals(expected.keySet())||Math.abs(total-100)>1e-4)return false;
        var inputs=candidate.path("inputs");
        var fixed=request.path("fixedInputs").fields();
        while(fixed.hasNext()){
            var entry=fixed.next();var value=inputs.path(entry.getKey());
            if(entry.getValue().isNumber()){if(!finite(value)||Math.abs(value.asDouble()-entry.getValue().asDouble())>1e-6)return false;}
            else if(!entry.getValue().equals(value))return false;
        }
        var variables=request.path("searchSpace").path("variableInputs").fields();
        while(variables.hasNext()){
            var entry=variables.next();var value=inputs.path(entry.getKey());var range=entry.getValue();
            if(!finite(value)||value.asDouble()<range.path("minimum").asDouble()-1e-6||value.asDouble()>range.path("maximum").asDouble()+1e-6)return false;
        }
        return true;
    }

    private void validateFormulaRequest(JsonNode body,List<PredictionRepository.TargetBinding> bindings){
        var total=body.path("targetTotal"); if(!total.isNumber()||!Double.isFinite(total.asDouble())||total.asDouble()<=0)throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"目标合计必须是大于0的有限数值");
        if(!"MASS_PERCENT".equals(body.path("formulaBasis").asText("MASS_PERCENT")))throw new ApiException(ApiErrorCode.FORMULA_BASIS_UNRESOLVED,"当前配方预测只支持质量百分比基准");
        var formula=body.path("baselineFormula"); var components=formula.path("components"); if(!components.isArray()||components.isEmpty())throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"至少需要一种配方材料");
        var tolerance=new java.math.BigDecimal("0.001");
        var recorded=formula.path("recordedTotal"); if(!recorded.isNumber()||recorded.decimalValue().subtract(total.decimalValue()).abs().compareTo(tolerance)>0)throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"配方记录的总量与目标合计不一致，请检查比例");
        var sum=java.math.BigDecimal.ZERO; for(var c:components){if(!c.path("amountKnown").asBoolean(false)||!finite(c.path("ratio")))throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"材料用量必须明确填写，空值不能作为搜索条件");if(c.path("ratio").asDouble()<0)throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"材料比例不能为负数");sum=sum.add(c.path("ratio").decimalValue());}
        if(sum.subtract(total.decimalValue()).abs().compareTo(tolerance)>0)throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"材料比例合计与目标合计偏差超过0.001个百分点，请检查缺项和原始数值");
        var intersection=new LinkedHashSet<String>(); boolean first=true; for(var binding:bindings){var ids=new LinkedHashSet<String>();binding.frozenDictionary().path("materials").forEach(m->ids.add(m.path("materialId").asText()));if(first){intersection.addAll(ids);first=false;}else intersection.retainAll(ids);}for(var c:components)if(!intersection.contains(c.path("materialId").asText()))throw new ApiException(ApiErrorCode.MATERIAL_SPACE_CONFLICT,"配方材料不在所选目标共同材料空间");
        var variableMaterials=body.path("searchSpace").path("variableMaterials");if(!variableMaterials.isArray()||variableMaterials.isEmpty())throw new ApiException(ApiErrorCode.MATERIAL_SPACE_CONFLICT,"至少需要一种允许调整的材料");
        for(var binding:bindings)for(var material:binding.frozenDictionary().path("materials"))if(material.hasNonNull("fixedValue")) {
            var id=material.path("materialId").asText();var value=material.path("fixedValue").asDouble();
            for(var component:components)if(id.equals(component.path("materialId").asText())&&Math.abs(component.path("ratio").asDouble()-value)>1e-6)
                throw new ApiException(ApiErrorCode.MATERIAL_SPACE_CONFLICT,"材料“"+material.path("name").asText()+"”在训练中固定为"+value+"%，本轮请保持原值");
            for(var variable:variableMaterials)if(id.equals(variable.path("materialId").asText())&&(variable.path("minimum").asDouble()!=value||variable.path("maximum").asDouble()!=value))
                throw new ApiException(ApiErrorCode.MATERIAL_SPACE_CONFLICT,"固定材料不能作为可变比例："+material.path("name").asText());
        }
        for(var variable:variableMaterials){var id=variable.path("materialId").asText();if(!intersection.contains(id))throw new ApiException(ApiErrorCode.MATERIAL_SPACE_CONFLICT,"可变材料不在所选目标共同材料空间");if(!finite(variable.path("minimum"))||!finite(variable.path("maximum"))||variable.path("minimum").asDouble()<0||variable.path("minimum").asDouble()>variable.path("maximum").asDouble())throw new ApiException(ApiErrorCode.MATERIAL_SPACE_CONFLICT,"材料比例范围无效");}
        var fixed=body.path("fixedInputs");var variableInputs=body.path("searchSpace").path("variableInputs");
        for(var binding:bindings)if(WorkbenchFormulaSupport.supports(binding.contractVersion()))for(var field:binding.frozenInputScheme().path("fields")) {
            var materialId=WorkbenchFormulaSupport.materialId(field);if(materialId==null)continue;
            if(!java.util.stream.StreamSupport.stream(components.spliterator(),false).anyMatch(c->materialId.equals(c.path("materialId").asText())))
                throw new ApiException(ApiErrorCode.MATERIAL_SPACE_CONFLICT,"配方缺少模型使用的材料："+field.path("name").asText());
            var code=field.path("code").asText();
            if(fixed.has(code)||variableInputs.has(code))throw new ApiException(ApiErrorCode.INPUT_SPACE_CONFLICT,"材料比例请在配方材料中设置："+field.path("name").asText());
        }

        for(var binding:bindings)for(var field:binding.frozenInputScheme().path("fields"))if(field.path("required").asBoolean()&&!"COMPOSITION".equals(field.path("valueType").asText())
                && !(WorkbenchFormulaSupport.supports(binding.contractVersion())&&WorkbenchFormulaSupport.materialId(field)!=null)){var code=field.path("code").asText();if("POST_EXPERIMENT".equals(field.path("availabilityStage").asText()))throw new ApiException(ApiErrorCode.INPUT_SPACE_CONFLICT,"实验后字段不能作为配方搜索输入");if((!fixed.has(code)||fixed.path(code).isNull())&&!variableInputs.has(code))throw new ApiException(ApiErrorCode.INPUT_SPACE_CONFLICT,"缺少模型要求的实验前条件："+field.path("name").asText(code));if(variableInputs.has(code)){var range=variableInputs.path(code);if(!finite(range.path("minimum"))||!finite(range.path("maximum"))||range.path("minimum").asDouble()>range.path("maximum").asDouble())throw new ApiException(ApiErrorCode.INPUT_SPACE_CONFLICT,"实验条件范围无效："+field.path("name").asText(code));}}
        if(!body.path("candidateCount").isMissingNode()&&(body.path("candidateCount").asInt()<1||body.path("candidateCount").asInt()>16))throw new ApiException(ApiErrorCode.FIXED_CONSTRAINT_CONFLICT,"候选数量必须在1到16之间");
    }
    private JsonNode normalizeDesignRequest(JsonNode body){
        var copy=(ObjectNode)body.deepCopy();
        copy.remove("displayContext");copy.remove("simulation");
        var result=formulaNormalization.requireValid(copy.path("baselineFormula"));
        copy.set("baselineFormula",result.formula());
        if("MASS_PERCENT".equals(copy.path("formulaBasis").asText("MASS_PERCENT"))) copy.put("targetTotal",100);
        return copy;
    }
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
