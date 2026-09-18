package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ai.rnd.prediction.PredictionRepository;
import com.jsd.aird.ai.rnd.research.FormulaDesignService;
import com.jsd.aird.ai.rnd.training.FormulaModelV2Client;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.rnd.api.ExperimentDraftFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.optimization.OptimizationContracts.*;

@Service
public class OptimizationService {
    private static final List<String> STRATEGIES=List.of("CONTROL","CONSERVATIVE","BALANCED","EXPLORATORY");
    private final OptimizationRepository repository;
    private final PredictionRepository predictions;
    private final FormulaDesignService formulaDesign;
    private final FormulaModelV2Client compute;
    private final ConfigurationHashing hashing;
    private final OpsAsyncFacade jobs;
    private final AuditLogFacade audit;
    private final AuthorizationService authorization;
    private final ExperimentDraftFacade drafts;
    private final TemplateDataImportFacade templates;
    private final ObjectMapper json;
    private final boolean inlineWorker;

    public OptimizationService(OptimizationRepository repository, PredictionRepository predictions,
                               FormulaDesignService formulaDesign, FormulaModelV2Client compute,
                                ConfigurationHashing hashing, OpsAsyncFacade jobs, AuditLogFacade audit,
                                AuthorizationService authorization, ExperimentDraftFacade drafts,
                                TemplateDataImportFacade templates, ObjectMapper json,
                                @Value("${JSD_AIRD_R09_INLINE_ACCEPTANCE_WORKER:false}") boolean inlineWorker) {
        this.repository=repository;this.predictions=predictions;this.formulaDesign=formulaDesign;this.compute=compute;
        this.hashing=hashing;this.jobs=jobs;this.audit=audit;this.authorization=authorization;this.drafts=drafts;
        this.templates=templates;
        this.json=json;this.inlineWorker=inlineWorker;
    }

    public BaselinePage baselines(String type,String keyword,int page,int size){
        var a=actor();require(a,"ai.experiment.optimize","AI_EXPERIMENT_OPTIMIZATION",null,"READ");
        var p=Math.max(0,page);var s=Math.min(100,Math.max(1,size));
        var rows=repository.baselines(a.organizationId(),a.userId(),type,keyword,p,s).stream()
                .filter(row->canReadBaseline(a,row.type())).map(row->summary(a.organizationId(),row)).toList();
        return new BaselinePage(rows,p,s);
    }

    public JsonNode context(String baselineType,UUID baselineId,List<UUID> targetIds){
        var a=actor();require(a,"ai.experiment.optimize","AI_EXPERIMENT_OPTIMIZATION",baselineId,"READ");
        var baseline=resolveBaseline(a,baselineType,baselineId);var snapshot=baselineSnapshot(a.organizationId(),baseline);var root=json.createObjectNode();
        root.set("baseline",json.valueToTree(summary(a.organizationId(),baseline)));root.set("frozenBaseline",snapshot);
        var catalog=json.createArrayNode();predictions.targetCatalog(a.organizationId()).forEach(t->catalog.add(json.createObjectNode()
                .put("targetId",t.id().toString()).put("code",t.code()).put("name",t.name()).put("category",Objects.toString(t.category(),"未分类"))
                .put("valueType",t.valueType()).put("available",t.modelId()!=null&&t.qualityPolicyId()!=null&&t.domainPolicyId()!=null)));
        root.set("targets",catalog);
        var templateOptions=json.createArrayNode();
        try {
            templates.listPublished(a.organizationId()).stream()
                    .filter(TemplateDataImportFacade.DataTemplateOption::experimentImportReady)
                    .forEach(item -> templateOptions.add(json.createObjectNode()
                            .put("templateId", item.templateId().toString())
                            .put("templateVersionId", item.versionId().toString())
                            .put("templateCode", item.templateCode())
                            .put("name", item.name())
                            .put("category", item.category() == null ? "" : item.category())
                            .put("versionNo", item.versionNo())
                            .put("format", item.format())));
        } catch (RuntimeException ignored) {
            // A missing template catalog must be represented as an empty list;
            // draft creation will return the actionable template-required error.
        }
        root.set("experimentTemplates", templateOptions);
        if(targetIds==null||targetIds.isEmpty()){root.set("selected",json.createArrayNode());return root;}
        var bindings=predictions.targetBindings(a.organizationId(),targetIds.stream().distinct().toList());
        var selected=root.putArray("selected");var materials=(ArrayNode)json.createArrayNode();var intersection=new LinkedHashMap<String,JsonNode>();var first=true;
        var inputs=new LinkedHashMap<String,JsonNode>();
        for(var b:bindings){selected.add(targetView(b));var local=new LinkedHashMap<String,JsonNode>();b.frozenDictionary().path("materials").forEach(m->local.put(m.path("materialId").asText(),m));if(first){intersection.putAll(local);first=false;}else intersection.keySet().retainAll(local.keySet());b.frozenInputScheme().path("fields").forEach(f->inputs.putIfAbsent(f.path("code").asText(),f));}
        intersection.values().forEach(materials::add);root.set("materialIntersection",materials);root.set("adjustableInputs",json.valueToTree(inputs.values()));
        root.put("controlTotal",snapshot.path("formula").path("recordedTotal").asDouble());root.put("candidateCount",4);return root;
    }

    public OptimizationAccepted submit(JsonNode body,String idempotencyKey){
        var a=actor();require(a,"ai.experiment.optimize","AI_EXPERIMENT_OPTIMIZATION",null,"CREATE");
        if(idempotencyKey==null||idempotencyKey.isBlank())throw validation("必须提供幂等键");
        if(body==null||!body.isObject())throw validation("实验优化请求不能为空");
        var ref=body.path("baselineRef");var type=ref.path("type").asText();var baselineId=uuid(ref,"id",ApiErrorCode.BASELINE_REQUIRED);
        var baseline=resolveBaseline(a,type,baselineId);var snapshot=baselineSnapshot(a.organizationId(),baseline);var baselineHash=hashing.hash(snapshot);
        if(ref.hasNonNull("versionId")&&!baseline.versionId().equals(uuid(ref,"versionId",ApiErrorCode.BASELINE_VERSION_CHANGED)))throw new ApiException(ApiErrorCode.BASELINE_VERSION_CHANGED);
        if(ref.hasNonNull("contentHash")&&!baselineHash.equals(ref.path("contentHash").asText()))throw new ApiException(ApiErrorCode.BASELINE_VERSION_CHANGED);
        var goals=body.path("typedGoals");if(!goals.isArray()||goals.isEmpty())throw validation("至少选择一个优化目标");
        var targetIds=new ArrayList<UUID>();for(var g:goals){var id=uuid(g,"targetId",ApiErrorCode.GOAL_VALUE_INVALID);if(!targetIds.contains(id))targetIds.add(id);}
        var bindings=predictions.targetBindings(a.organizationId(),targetIds);if(bindings.size()!=targetIds.size())throw new ApiException(ApiErrorCode.NO_ACTIVE_MODEL);
        var bindingMap=new LinkedHashMap<UUID,UUID>();var frozenBindings=json.createArrayNode();
        for(var b:bindings){if(!formulaDesign.modelAvailable(b))throw new ApiException(ApiErrorCode.NO_ACTIVE_MODEL,"所选目标尚无完整正式模型、DOMAIN或QUALITY策略");var expected=expected(goals,b.targetId());if(expected!=null&&!expected.equals(b.modelId()))throw new ApiException(ApiErrorCode.MODEL_VERSION_CHANGED);bindingMap.put(b.targetId(),b.modelId());frozenBindings.add(formulaDesign.frozenModelBinding(b));}
        validateSpace(body,snapshot,bindings);
        precheckBaseline(snapshot,frozenBindings,RequestIdHolder.currentOrUnknown());
        var normalized=hashing.canonical(body);var requestHash=hashing.hash(normalized);var requestId=RequestIdHolder.currentOrUnknown();var seed=seed(requestHash);var runId=UUID.randomUUID();
        var config=json.createObjectNode().put("candidatePool",512).put("initialDesign",64).put("batchSize",64).put("diversityWeight",0.15);
        var optimization=json.createObjectNode().put("controlTotal",snapshot.path("formula").path("recordedTotal").asDouble())
                .put("optimizationTotal",body.path("targetTotal").asDouble(snapshot.path("formula").path("recordedTotal").asDouble()));optimization.set("strategies",json.valueToTree(STRATEGIES));
        boolean inserted;
        try{inserted=repository.insertRun(runId,a.organizationId(),a.userId(),idempotencyKey.strip(),requestId,requestHash,normalized,json.valueToTree(bindingMap),seed,config,baseline,baselineHash,snapshot,optimization);}catch(DataIntegrityViolationException e){throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"当前组织已有实验优化任务执行中",e);}
        if(!inserted){var old=repository.byKey(a.organizationId(),a.userId(),idempotencyKey).orElseThrow();if(!old.requestHash().equals(requestHash))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);return accepted(old);}
        var payload=json.createObjectNode().put("organizationId",a.organizationId().toString()).put("actorId",a.userId().toString()).put("runId",runId.toString()).put("requestId",requestId);
        var jobId=jobs.enqueue(a.organizationId(),"AI_EXPERIMENT_OPTIMIZATION_V2",payload,"ai-experiment-optimization:"+runId,45,3);repository.attachJob(a.organizationId(),runId,jobId);
        audit.append(a.organizationId(),a.userId(),"AI_EXPERIMENT_OPTIMIZATION_STARTED","AI_RESEARCH_RUN",runId,normalized);
        if(inlineWorker)Thread.startVirtualThread(()->{try{execute(a.organizationId(),runId);}catch(Exception ignored){}});
        return new OptimizationAccepted(runId,jobId,"QUEUED",bindingMap,1000);
    }

    public OptimizationRun get(UUID runId){var a=actor();require(a,"ai.experiment.optimize","AI_RESEARCH_RUN",runId,"READ");return view(a.organizationId(),repository.byId(a.organizationId(),a.userId(),runId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"实验优化运行不存在")));}

    public JsonNode execute(UUID org,UUID runId){
        var run=repository.any(org,runId).orElseThrow();if(!repository.markRunning(org,runId))return run.result();
        try{
            repository.progress(org,runId,15,"BASELINE_CHECK");var payload=recommendPayload(org,run);repository.progress(org,runId,35,"BUILDING_SPACE");
            var response=compute.recommend(payload);repository.progress(org,runId,75,"STRATEGY_SELECTION");
            if("FAILED".equals(response.path("executionStatus").asText())){repository.fail(org,runId,response.path("code").asText("COMPUTE_UNAVAILABLE"),response.path("message").asText("计算服务执行失败"));return response;}
            var savedStrategies=new LinkedHashSet<String>();var no=0;for(var c:response.path("candidates")){if(!c.isObject())continue;var strategy=c.path("strategy").asText();if(!STRATEGIES.contains(strategy)||!savedStrategies.add(strategy))continue;var formula=c.path("formula");var hash=hashing.hash(json.createObjectNode().put("strategy",strategy).set("formula",formula));repository.saveCandidate(org,runId,++no,strategy,strategyTitle(strategy),formula,c.path("inputs"),resultsObject(c.path("results"),org,run.bindings()),c.path("quality"),c.path("applicability"),c.path("ruleCheck"),c.path("evidence"),c.path("score").asDouble(),hash,formula.path("recordedTotal").asDouble(),c.path("baselineDistance").asDouble(),c.path("changeSummary"),c.path("strategyEvidence"),c.path("riskFlags"));}
            var missing=STRATEGIES.stream().filter(s->!savedStrategies.contains(s)).toList();var summary=json.createObjectNode().put("requestedCandidateCount",4).put("actualCandidateCount",savedStrategies.size());summary.set("missingStrategies",json.valueToTree(missing));
            if(response.has("missingStrategyReasons"))summary.set("missingStrategyReasons",response.path("missingStrategyReasons"));
            if(response.has("shortfallReason"))summary.set("shortfallReason",response.path("shortfallReason"));
            if(response.has("searchEngine"))summary.set("searchEngine",response.path("searchEngine"));
            if(response.has("searchStrategy"))summary.set("searchStrategy",response.path("searchStrategy"));
            if(response.has("searchEvidence"))summary.set("searchEvidence",response.path("searchEvidence"));
            var outcome=savedStrategies.isEmpty()?"BLOCKED":missing.isEmpty()?"SUCCEEDED":"PARTIAL";repository.complete(org,runId,outcome,summary);return summary;
        }catch(Exception e){repository.fail(org,runId,e instanceof ApiException a?a.errorCode().code():"COMPUTE_UNAVAILABLE",e.getMessage());throw e instanceof RuntimeException r?r:new IllegalStateException(e);}
    }

    @Transactional
    public DraftCreationResult createDrafts(UUID runId,DraftCommand command,String key){
        var a=actor();require(a,"ai.experiment.optimize","AI_RESEARCH_RUN",runId,"UPDATE");require(a,"experiment.create","EXPERIMENT",null,"CREATE");
        if(key==null||key.isBlank())throw validation("必须提供幂等键");if(command==null||command.candidateIds()==null||command.candidateIds().isEmpty())throw validation("至少选择一个实验方案");if(command.candidateIds().size()>4)throw validation("一次最多创建4个实验草稿");
        var run=repository.byId(a.organizationId(),a.userId(),runId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));if(!"SUCCEEDED".equals(run.executionStatus()))throw new ApiException(ApiErrorCode.DRAFT_CREATION_CONFLICT,"实验优化尚未完成");
        var links=new ArrayList<DraftLink>();
        for(var candidateId:new LinkedHashSet<>(command.candidateIds())){
            var itemKey=key.strip()+":"+candidateId;var old=repository.linkByKey(a.organizationId(),a.userId(),itemKey);if(old.isPresent()){links.add(linkView(old.get()));continue;}
            var candidate=repository.candidate(a.organizationId(),runId,candidateId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"候选方案不存在"));
            var edit=editModel(candidate,run);
            var template=resolveExperimentTemplate(a, run, command.templateVersionId());
            var created=drafts.createResearchDraft(new ExperimentDraftFacade.ResearchDraftCommand(
                    "AI实验优化-"+strategyTitle(candidate.strategy()), command.categoryId(), command.projectId(),
                    command.stageId(), command.taskId(), command.ownerName(),
                    command.plannedExperimentDate()==null?LocalDate.now():command.plannedExperimentDate(),
                    template.versionId(), template.snapshotHash(), template.snapshot(), edit));
            var intent=json.valueToTree(command);var intentHash=hashing.hash(intent);var evidenceId=UUID.randomUUID();var evidenceHash=hashing.hash(Map.of("runId",runId,"candidateId",candidateId,"experimentVersionId",created.experimentVersionId(),"requestHash",run.requestHash()));
            try{repository.insertEvidenceAndLink(a.organizationId(),a.userId(),run,candidate,created.experimentId(),created.experimentVersionId(),itemKey,intent,intentHash,evidenceId,evidenceHash);}catch(DataIntegrityViolationException e){throw new ApiException(ApiErrorCode.DRAFT_CREATION_CONFLICT,"实验草稿创建发生并发冲突",e);}
            links.add(new DraftLink(candidateId,created.experimentId(),created.experimentVersionId(),created.experimentNo(),"DRAFT","/experiments/"+created.experimentId(),java.time.Instant.now()));
        }
        audit.append(a.organizationId(),a.userId(),"AI_EXPERIMENT_DRAFTS_CREATED","AI_RESEARCH_RUN",runId,json.valueToTree(links));return new DraftCreationResult(runId,links);
    }

    public List<DraftLink> links(UUID runId){var a=actor();require(a,"ai.experiment.optimize","AI_RESEARCH_RUN",runId,"READ");repository.byId(a.organizationId(),a.userId(),runId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));return repository.links(a.organizationId(),runId).stream().map(this::linkView).toList();}
    public void fail(UUID org,UUID run,String code,String message){repository.fail(org,run,code,message);}

    /**
     * R09 drafts must use a published customer experiment template. A baseline
     * template is kept when it was already frozen with the run; otherwise the
     * caller may select a published template explicitly. The first published
     * experiment template is the deterministic default for API clients that do
     * not send a selection.
     */
    private TemplateDataImportFacade.PublishedExperimentTemplate resolveExperimentTemplate(
            Actor actor, OptimizationRepository.RunRow run, UUID requestedVersionId) {
        if (requestedVersionId != null) {
            var option = templates.listPublished(actor.organizationId()).stream()
                    .filter(item -> item.experimentImportReady() && requestedVersionId.equals(item.versionId()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(ApiErrorCode.EXPERIMENT_TEMPLATE_REQUIRED,
                            "所选实验模板未发布或不允许生成实验草稿"));
            return templates.getPublishedExperimentTemplate(actor.organizationId(), option.templateCode());
        }
        var baselineId = uuidOrNull(run.baseline().path("templateVersionId").asText(null));
        var baselineHash = run.baseline().path("templateSnapshotHash").asText("");
        var baselineSnapshot = run.baseline().path("templateSnapshot");
        if (baselineId != null && !baselineHash.isBlank() && baselineSnapshot.isObject()) {
            var definition = templates.getVersion(actor.organizationId(), baselineId);
            return new TemplateDataImportFacade.PublishedExperimentTemplate(
                    definition.templateId(), definition.versionId(), definition.templateCode(),
                    definition.name(), definition.versionNo(), baselineHash, baselineSnapshot.deepCopy(),
                    definition.mappings() == null ? json.createArrayNode() : definition.mappings().deepCopy(),
                    definition.importContract() == null ? json.createObjectNode() : definition.importContract().deepCopy());
        }
        var option = templates.listPublished(actor.organizationId()).stream()
                .filter(TemplateDataImportFacade.DataTemplateOption::experimentImportReady)
                .findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.EXPERIMENT_TEMPLATE_REQUIRED,
                        "请先发布一个允许生成实验草稿的客户实验模板"));
        return templates.getPublishedExperimentTemplate(actor.organizationId(), option.templateCode());
    }

    private OptimizationRepository.BaselineRow resolveBaseline(Actor a,String type,UUID id){if(type==null||type.isBlank()||id==null)throw new ApiException(ApiErrorCode.BASELINE_REQUIRED);if(!canReadBaseline(a,type))throw new ApiException(ApiErrorCode.PERMISSION_DENIED);return repository.baseline(a.organizationId(),a.userId(),type,id).orElseThrow(()->new ApiException(ApiErrorCode.BASELINE_NOT_CURRENT));}
    private boolean canReadBaseline(Actor a,String type){var permission=switch(type){case "EXPERIMENT_VERSION"->"experiment.view";case "DATA_SAMPLE_REVISION"->"data.view";case "RESEARCH_CANDIDATE"->"ai.formula.predict";default->null;};return permission!=null&&authorization.check(new PermissionCheck(a.organizationId(),a.userId(),permission,"AI_OPTIMIZATION_BASELINE",null,"READ")).allowed();}
    private BaselineSummary summary(UUID org,OptimizationRepository.BaselineRow row){var snap=baselineSnapshot(org,row);return new BaselineSummary(row.type(),row.entityId(),row.versionId(),row.title(),row.sourceLabel(),snap.path("formula").path("recordedTotal").asDouble(),snap.path("formula").path("components"),snap.path("inputs"),snap.path("results"),hashing.hash(snap),row.updatedAt());}
    private ObjectNode baselineSnapshot(UUID org,OptimizationRepository.BaselineRow row){var out=json.createObjectNode().put("type",row.type()).put("entityId",row.entityId().toString()).put("versionId",row.versionId().toString()).put("title",row.title());var payload=row.payload();JsonNode formula;JsonNode inputs;JsonNode results;
        if("EXPERIMENT_VERSION".equals(row.type())){formula=formulaFromExperiment(payload.path("formulaItems"));inputs=payload.path("dynamicValues");results=payload.path("testResults");}else{formula=payload.path("formula");inputs=payload.path("inputs");results=payload.has("predictions")?payload.path("predictions"):observationsForBaseline(org,payload.path("observations"));}
        out.set("formula",normalizeFormula(formula));out.set("inputs",inputs.isObject()?inputs.deepCopy():json.createObjectNode());out.set("results",results.deepCopy());if(row.templateVersionId()!=null)out.put("templateVersionId",row.templateVersionId().toString());if(row.templateSnapshotHash()!=null)out.put("templateSnapshotHash",row.templateSnapshotHash());out.set("templateSnapshot",row.templateSnapshot());return out;}
    private JsonNode observationsForBaseline(UUID org,JsonNode raw){
        if(raw==null||raw.isMissingNode()||raw.isNull())return json.createObjectNode().putArray("targets");
        if(raw.isArray()||(raw.isObject()&&raw.path("targets").isArray()))return raw;
        var targets=json.createArrayNode();var names=new LinkedHashMap<String,String>();predictions.targetCatalog(org).forEach(t->names.put(t.code(),t.name()));
        if(raw.isObject())raw.fields().forEachRemaining(entry->{var value=entry.getValue();if(!value.isObject())return;var item=(ObjectNode)value.deepCopy();var code=item.path("targetCode").asText("");if(code.isBlank()&&!item.has("targetId"))return;if(!item.has("value")&&item.has("rawValue"))item.set("value",item.get("rawValue"));if(!code.isBlank()&&names.containsKey(code)&&!item.has("targetName"))item.put("targetName",names.get(code));targets.add(item);});
        return json.createObjectNode().set("targets",targets);
    }
    private ObjectNode formulaFromExperiment(JsonNode items){var f=json.createObjectNode().put("basis","MASS_PERCENT").put("compositionComplete",true);var components=f.putArray("components");double total=0;if(items.isArray())for(var item:items){var ratio=number(item,"ratio","value","rawValue","amount");if(ratio==null)continue;var c=components.addObject().put("materialId",item.path("materialId").asText(item.path("materialCode").asText())).put("materialCode",item.path("materialCode").asText()).put("ratio",ratio).put("unit","PERCENT").put("amountKnown",true);total+=ratio;}f.put("recordedTotal",total);return f;}
    private ObjectNode normalizeFormula(JsonNode value){if(value.isObject()&&(value.path("components").isArray()||value.path("items").isArray())){var copy=(ObjectNode)value.deepCopy();if(!copy.has("components"))copy.set("components",copy.path("items").deepCopy());copy.remove("items");if(!copy.has("basis"))copy.put("basis","MASS_PERCENT");if(!copy.has("compositionComplete"))copy.put("compositionComplete",true);if(!copy.has("recordedTotal")){double sum=0;for(var c:copy.path("components"))sum+=c.path("ratio").asDouble();copy.put("recordedTotal",sum);}for(var component:copy.withArray("components")){if(component instanceof ObjectNode c){if(!c.has("unit"))c.put("unit","PERCENT");if(!c.has("amountKnown"))c.put("amountKnown",true);}}return copy;}return formulaFromExperiment(value);}
    private void precheckBaseline(JsonNode snapshot,ArrayNode bindings,String requestId){var p=json.createObjectNode().put("contractVersion","formula-model.v2").put("requestId",requestId+":baseline").put("seed",7).put("runId",UUID.randomUUID().toString());p.set("formula",formulaForCompute(snapshot.path("formula")));p.set("inputs",alignedInputs(snapshot.path("inputs"),bindings));p.set("modelBindings",bindings);JsonNode result;try{result=compute.score(p);}catch(FormulaModelV2Client.ComputeTransportException e){throw new ApiException(ApiErrorCode.COMPUTE_UNAVAILABLE,"formula-model.v2计算服务暂不可用",e);}if("FAILED".equals(result.path("executionStatus").asText())){var failed=result.path("results").isArray()&&!result.path("results").isEmpty()?result.path("results").get(0):json.createObjectNode();throw new ApiException(ApiErrorCode.MODEL_ARTIFACT_INVALID,failed.path("code").asText("MODEL_LOAD_FAILED")+"："+failed.path("message").asText("基线模型评分失败"));}for(var item:result.path("results")){if("BLOCKED".equals(item.path("status").asText())&&"OUT_OF_DOMAIN".equals(item.path("code").asText()))throw new ApiException(ApiErrorCode.BASELINE_OUT_OF_DOMAIN);if(!"SUCCEEDED".equals(item.path("status").asText()))throw new ApiException(ApiErrorCode.BASELINE_DATA_INCOMPLETE,item.path("message").asText("基线输入不完整"));}}
    private void validateSpace(JsonNode body,JsonNode snapshot,List<PredictionRepository.TargetBinding> bindings){var formula=snapshot.path("formula");if(!formula.path("components").isArray()||formula.path("components").isEmpty()||!formula.path("recordedTotal").isNumber())throw new ApiException(ApiErrorCode.BASELINE_DATA_INCOMPLETE);var targetTotal=body.path("targetTotal").asDouble(formula.path("recordedTotal").asDouble());if(!Double.isFinite(targetTotal)||targetTotal<=0)throw new ApiException(ApiErrorCode.TARGET_TOTAL_CONFLICT);var materials=body.path("variableMaterials");var inputs=body.path("variableInputs");if((!materials.isArray()||materials.isEmpty())&&(!inputs.isArray()||inputs.isEmpty()))throw new ApiException(ApiErrorCode.NO_ADJUSTABLE_INPUT);for(var v:materials){if(v.path("minimum").asDouble()>v.path("maximum").asDouble())throw new ApiException(ApiErrorCode.OPTIMIZATION_SPACE_CONFLICT);}for(var v:inputs){var timing=v.path("availabilityTiming").asText(v.path("acquisitionTiming").asText());if("POST_EXPERIMENT".equals(timing))throw new ApiException(ApiErrorCode.INPUT_SPACE_CONFLICT,"实验后字段不能作为优化变量");}}
    private ObjectNode recommendPayload(UUID org,OptimizationRepository.RunRow run){
        var p=(ObjectNode)run.request().deepCopy();
        p.put("contractVersion","formula-model.v2").put("requestId",run.requestId()).put("seed",run.seed()).put("runId",run.id().toString()).put("mode","EXPERIMENT_OPTIMIZATION").put("candidateCount",4);
        p.set("baselineFormula",formulaForCompute(run.baseline().path("formula")));
        var sourceGoals=p.path("typedGoals");
        var goals=p.putArray("goals");
        var bindings=p.putArray("modelBindings");
        // Keep model binding order aligned with the user's goal order. The JSONB
        // object used to hold frozen bindings has no ordering guarantee; using
        // that order paired ordinal goals with the wrong result type.
        if(sourceGoals.isArray()) for(var source:sourceGoals){
            if(!source.isObject()) continue;
            var targetId=UUID.fromString(source.path("targetId").asText());
            var targetBinding=predictions.targetBindings(org,List.of(targetId)).stream().findFirst().orElseThrow();
            var goal=goals.addObject();
            goal.put("targetId",targetBinding.targetVersionId().toString()).put("operator",source.path("operator").asText()).put("mandatory",source.path("mandatory").asBoolean(false)).put("weight",source.path("weight").asDouble(1));
            if(source.has("value")) goal.set("value",source.get("value"));
            if(source.hasNonNull("minimumProbability")) goal.put("minimumProbability",source.path("minimumProbability").asDouble());
            bindings.add(formulaDesign.frozenModelBinding(targetBinding));
        }
        p.remove("typedGoals");
        p.set("fixedInputs",alignedInputs(run.baseline().path("inputs"),bindings));
        var constraints=p.withObject("/constraints");
        constraints.put("targetTotal",p.path("targetTotal").asDouble(run.baseline().path("formula").path("recordedTotal").asDouble()));
        constraints.set("variableMaterials",p.path("variableMaterials"));
        constraints.set("variableInputs",p.path("variableInputs"));
        constraints.set("strategies",json.valueToTree(STRATEGIES));
        p.remove("baselineRef");p.remove("targetTotal");p.remove("variableMaterials");p.remove("variableInputs");
        return p;
    }
    private JsonNode formulaForCompute(JsonNode value){var copy=value==null?json.createObjectNode():value.deepCopy();if(copy.isObject()&&copy.path("components").isArray())for(var c:copy.path("components"))if(c.isObject())((ObjectNode)c).remove("materialCode");return copy;}
    private ObjectNode alignedInputs(JsonNode raw,ArrayNode bindings){var out=json.createObjectNode();for(var binding:bindings)for(var field:binding.path("inputScheme").path("fields")){var code=field.path("code").asText();if(code.isBlank()||out.hasNonNull(code))continue;var wanted=normalizeKey(code);var fields=raw==null?java.util.Collections.<Map.Entry<String,JsonNode>>emptyIterator():raw.fields();while(fields.hasNext()){var entry=fields.next();var actual=normalizeKey(entry.getKey());if(actual.equals(wanted)||wanted.endsWith(actual)||actual.endsWith(wanted)){out.set(code,entry.getValue());break;}}}return out;}
    private String normalizeKey(String value){return value==null?"":value.toUpperCase(java.util.Locale.ROOT).replace("TEST_","").replace("PROCESS_","").replaceAll("[^A-Z0-9]","");}
    private OptimizationRun view(UUID org,OptimizationRepository.RunRow run){var candidates=repository.candidates(org,run.id()).stream().map(c->new OptimizationCandidate(c.id(),c.candidateNo(),c.strategy(),c.title(),c.formula(),c.inputs(),c.results(),c.quality(),c.applicability(),c.ruleCheck(),c.score(),c.targetTotal(),c.distance(),c.changes(),c.strategyEvidence(),c.risks())).toList();var missing=STRATEGIES.stream().filter(s->candidates.stream().noneMatch(c->s.equals(c.strategy()))).toList();var map=new LinkedHashMap<UUID,UUID>();run.bindings().fields().forEachRemaining(e->map.put(UUID.fromString(e.getKey()),UUID.fromString(e.getValue().asText())));var result=run.result();return new OptimizationRun(run.id(),run.executionStatus(),run.outcomeStatus(),run.progress(),run.stage(),4,candidates.size(),missing,result.path("missingStrategyReasons"),candidates,run.error(),run.baseline(),run.request(),map,run.requestId(),run.updatedAt(),result.path("searchEngine").asText(null),result.path("searchStrategy").asText(null),result.path("searchEvidence"));}
    private OptimizationAccepted accepted(OptimizationRepository.RunRow run){var map=new LinkedHashMap<UUID,UUID>();run.bindings().fields().forEachRemaining(e->map.put(UUID.fromString(e.getKey()),UUID.fromString(e.getValue().asText())));var status=List.of("QUEUED","RUNNING").contains(run.executionStatus())?"RUNNING":run.executionStatus();return new OptimizationAccepted(run.id(),run.opsJobId(),status,map,1000);}
    private ObjectNode targetView(PredictionRepository.TargetBinding b){var n=json.createObjectNode().put("targetId",b.targetId().toString()).put("name",b.targetName()).put("valueType",b.valueType()).put("available",formulaDesign.modelAvailable(b));if(b.modelId()!=null)n.put("modelVersionId",b.modelId().toString());n.set("classes",b.classes());return n;}
    private ObjectNode editModel(OptimizationRepository.CandidateRow c,OptimizationRepository.RunRow run){var m=json.createObjectNode().put("title","AI实验优化-"+strategyTitle(c.strategy())).put("purpose","验证"+strategyTitle(c.strategy())+"方案的实际表现").put("plan","按候选配方和实验前条件执行并补录实测结果");m.set("dynamicValues",c.inputs().deepCopy());m.set("tables",json.createArrayNode());var items=m.putArray("formulaItems");for(var component:c.formula().path("components"))items.add(json.createObjectNode().put("materialId",component.path("materialId").asText()).put("materialCode",component.path("materialCode").asText(component.path("materialId").asText())).put("rawValue",component.path("ratio").asDouble()).put("rawUnit","%").put("ratio",component.path("ratio").asDouble()));m.set("processSteps",json.createArrayNode());m.set("testResults",json.createArrayNode());m.set("events",json.createArrayNode());m.set("conclusion",json.createObjectNode().put("resultStatus","").put("mainConclusion","").put("failureCategory",""));m.set("aiSource",json.createObjectNode().put("runId",run.id().toString()).put("candidateId",c.id().toString()).put("strategy",c.strategy()).set("predictions",c.results()));return m;}
    private JsonNode resultsObject(JsonNode value,UUID org,JsonNode bindings){var out=json.createObjectNode();var targets=out.putArray("targets");var names=new LinkedHashMap<String,String>();var modelToTarget=new LinkedHashMap<String,String>();predictions.targetCatalog(org).forEach(t->names.put(t.id().toString(),t.name()));if(bindings!=null&&bindings.isObject())bindings.fields().forEachRemaining(e->modelToTarget.put(e.getValue().asText(),e.getKey()));var source=value!=null&&value.isObject()&&value.path("targets").isArray()?value.path("targets"):value;if(source!=null&&source.isArray())source.forEach(v->{var c=v.deepCopy();if(c.isObject()){var modelId=c.path("modelVersionId").asText();var canonical=modelToTarget.get(modelId);if(canonical!=null)((ObjectNode)c).put("targetId",canonical);var n=names.get(c.path("targetId").asText());if(n!=null)((ObjectNode)c).put("targetName",n);}targets.add(c);});return out;}
    private DraftLink linkView(OptimizationRepository.LinkRow l){return new DraftLink(l.candidateId(),l.experimentId(),l.experimentVersionId(),l.experimentNo(),l.status(),"/experiments/"+l.experimentId(),l.createdAt());}
    private String strategyTitle(String value){return switch(value){case "CONTROL"->"原样对照";case "CONSERVATIVE"->"保守改进";case "BALANCED"->"多目标平衡";case "EXPLORATORY"->"受控探索";default->value;};}
    private Double number(JsonNode n,String...names){for(var name:names){var v=n.path(name);if(v.isNumber())return v.asDouble();if(v.isTextual())try{return Double.parseDouble(v.asText().replace("%","").strip());}catch(Exception ignored){}}return null;}
    private UUID expected(JsonNode goals,UUID target){for(var g:goals)if(target.toString().equals(g.path("targetId").asText())&&g.hasNonNull("expectedModelVersionId"))return uuid(g,"expectedModelVersionId",ApiErrorCode.MODEL_VERSION_CHANGED);return null;}
    private UUID uuid(JsonNode node,String field,ApiErrorCode code){try{return UUID.fromString(node.path(field).asText());}catch(Exception e){throw new ApiException(code);}}
    private UUID uuidOrNull(String v){try{return v==null?null:UUID.fromString(v);}catch(Exception e){return null;}}
    private long seed(String hash){return ByteBuffer.wrap(java.util.HexFormat.of().parseHex(hash.substring(0,16))).getLong();}
    private void require(Actor a,String permission,String type,UUID id,String operation){authorization.require(new PermissionCheck(a.organizationId(),a.userId(),permission,type,id,operation));}
    private Actor actor(){return ActorContext.required();}
    private ApiException validation(String m){return new ApiException(ApiErrorCode.VALIDATION_ERROR,m);}
}
