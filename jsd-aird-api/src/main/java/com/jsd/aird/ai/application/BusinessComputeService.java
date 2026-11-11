package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.api.AiRndContracts.PredictionRequest;
import com.jsd.aird.ai.rnd.prediction.*;
import com.jsd.aird.ai.rnd.research.*;
import com.jsd.aird.ai.rnd.optimization.*;
import com.jsd.aird.ai.rnd.modeling.*;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.*;

/** Calls the authoritative compute services; it never implements a second algorithm. */
@Service
public class BusinessComputeService {
    private static final Map<String,String> TYPES = Map.of("PREDICTION", "AI_PREDICTION", "FORMULA_DESIGN", "AI_FORMULA_DESIGN", "EXPERIMENT_OPTIMIZATION", "AI_EXPERIMENT_OPTIMIZATION", "MODEL_TRAINING","AI_MODEL_TRAINING");
    private final BusinessDataService data;
    private final BusinessExistingData existing;
    private final PredictionService predictions;
    private final FormulaDesignService formulas;
    private final OptimizationService optimizations;
    private final PredictionRepository predictionRecords;
    private final FormulaDesignRepository formulaRecords;
    private final OptimizationRepository optimizationRecords;
    private final ObjectMapper json;
    private final ModelingWorkbenchService modeling;
    private final WorkbenchModelRegistrationService registration;
    private final ResearchActivityService activities;
    private final com.jsd.aird.mdm.api.RawMaterialFacade rawMaterials;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.jsd.aird.iam.api.AuthorizationService authorization;
    private final com.jsd.aird.ops.application.port.AuditLogFacade audit;
    private com.jsd.aird.rnd.api.ResearchExperimentFacade sourceAccess;
    @org.springframework.beans.factory.annotation.Autowired
    void experimentAccess(com.jsd.aird.rnd.api.ResearchExperimentFacade sourceAccess) { this.sourceAccess=sourceAccess; }
    public BusinessComputeService(BusinessDataService data, BusinessExistingData existing, PredictionService predictions,
            FormulaDesignService formulas, OptimizationService optimizations, PredictionRepository predictionRecords,
            FormulaDesignRepository formulaRecords, OptimizationRepository optimizationRecords, ObjectMapper json,
            ModelingWorkbenchService modeling, WorkbenchModelRegistrationService registration, ResearchActivityService activities,
            com.jsd.aird.mdm.api.RawMaterialFacade rawMaterials, org.springframework.jdbc.core.JdbcTemplate jdbc,
            com.jsd.aird.iam.api.AuthorizationService authorization, com.jsd.aird.ops.application.port.AuditLogFacade audit) {
        this.data = data; this.existing = existing; this.predictions = predictions; this.formulas = formulas;
        this.optimizations = optimizations; this.predictionRecords = predictionRecords; this.formulaRecords = formulaRecords;
        this.optimizationRecords = optimizationRecords; this.json = json;
        this.modeling=modeling; this.registration=registration; this.activities=activities;
        this.rawMaterials=rawMaterials; this.jdbc=jdbc; this.authorization=authorization; this.audit=audit;
    }
    @org.springframework.transaction.annotation.Transactional
    public ObjectNode invoke(String action, ObjectNode args) {
        String kind = switch (action) {
            case "run_prediction" -> "PREDICTION"; case "run_formula_design" -> "FORMULA_DESIGN";
            case "run_experiment_optimization", "continue_experiment_optimization" -> "EXPERIMENT_OPTIMIZATION";
            case "run_model_training", "activate_trained_model" -> "MODEL_TRAINING"; default -> args.path("kind").asText();
        };
        var type = TYPES.get(kind); if (type == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "未知计算类型");
        UUID runId = optionalId(args, "run_id");
        data.require(type, runId, action.startsWith("run_") ? "EXECUTE" : "READ", args);
        if ("get_compute_context".equals(action)) {
            var response = context(kind, args); response.set("request_contract", contract(kind)); return response;
        }
        if ("get_compute_result".equals(action)) return result(kind, uuid(args, "run_id"));
        String key = args.path("operation_key").asText();
        if (!key.matches("[a-f0-9]{64}")) throw new ApiException(ApiErrorCode.BAD_REQUEST, "计算调用缺少服务端操作身份");
        if(jdbc!=null) {
            lockOperation(action,key);
            if(closedReceipt(action,key).has("result"))throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"原计算未提交且已关闭，请在明确继续后发起新一轮");
        }
        if("activate_trained_model".equals(action)) {
            if(!args.path("activate").asBoolean()||args.path("user_instruction").asText().isBlank())throw new ApiException(ApiErrorCode.BAD_REQUEST,"请先明确指定启用该建模结果");
            result("MODEL_TRAINING",uuid(args,"run_id"));
            var actor=ActorContext.required();authorization.require(new com.jsd.aird.iam.api.PermissionCheck(actor.organizationId(),actor.userId(),"ai.model.publish","AI_MODEL",runId,"PUBLISH"));
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,37))",actor.organizationId()+":"+actor.userId()+":activation:"+key);
            var intent=args.deepCopy();intent.remove("operation_key");var hash=new ConfigurationHashing(json).hash(intent);
            var prior=activationReceipt(key);
            if(prior.has("result")) {
                if(!hash.equals(prior.path("request_hash").asText()))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
                return (ObjectNode)prior.path("result");
            }
            var response=json.createObjectNode().put("id",runId.toString()).put("kind",kind).put("run_id",runId.toString()).put("operation_key",key);
            response.set("registration",registration.register(runId,key));
            var receipt=json.createObjectNode().put("operation_key",key).put("request_hash",hash);receipt.set("result",response);
            audit.append(actor.organizationId(),actor.userId(),"AI_ASSISTANT_MODEL_ACTIVATED","AI_MODEL_TRAINING",runId,receipt);
            return response;
        }
        if("continue_experiment_optimization".equals(action)) {
            result(kind,uuid(args,"run_id"));var accepted=optimizations.continueHistorical(runId,key);
            var metadata=args.deepCopy();var parent=activities.context(kind,runId);
            if(!metadata.has("project_id")&&parent.has("projectId"))metadata.set("project_id",parent.path("projectId"));
            if(!metadata.has("source_refs"))metadata.set("source_refs",parent.path("sourceRefs").isArray()?parent.path("sourceRefs"):json.createArrayNode());
            if(!metadata.has("research_basis"))metadata.set("research_basis",parent.path("researchBasis").isArray()?parent.path("researchBasis"):json.createArrayNode());
            activities.bind(kind,accepted.runId(),metadata);
            return json.createObjectNode().put("kind",kind).put("run_id",accepted.runId().toString()).put("id",accepted.runId().toString()).put("parent_run_id",runId.toString()).put("executionStatus",accepted.executionStatus()).put("operation_key",key).put("poll_tool","get_compute_result");
        }
        if (!(args.path("request") instanceof ObjectNode request)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "需要按计算上下文填写 request");
        if("EXPERIMENT_OPTIMIZATION".equals(kind)&&request.hasNonNull("trainingRunId")) {
            var resolved=trainingSelection(uuid(request,"trainingRunId"),args);
            for(var field:List.of("bounds","formulaTotal","direction"))if(request.has(field))resolved.set(field,request.path(field).deepCopy());
            request=resolved;
        }
        if(activities!=null)activities.check(args);
        ObjectNode response;
        switch (kind) {
            case "MODEL_TRAINING" -> {
                if(!request.path("selectedRowIdsByDataset").isObject())throw new ApiException(ApiErrorCode.BAD_REQUEST,"请明确选择每个数据集的实验行");
                for(var id:datasetIds(request))authorizeDataset(modeling.dataset(id),true);
                var accepted=modeling.train(json.convertValue(request,ModelingWorkbenchService.TrainCommand.class),key);
                response=(ObjectNode)accepted.deepCopy();response.set("id",accepted.path("runId"));
            }
            case "PREDICTION" -> {
                var accepted = predictions.predict(json.convertValue(request, PredictionRequest.class), key);
                response = (ObjectNode)accepted.body().deepCopy();
                if (response.hasNonNull("predictionId")) response.set("id", response.get("predictionId"));
                response.put("http_status", accepted.status());
            }
            case "FORMULA_DESIGN" -> {
                var accepted = formulas.submit(request, key); response = json.valueToTree(accepted);
                response.put("id", accepted.runId().toString());
            }
            case "EXPERIMENT_OPTIMIZATION" -> {
                if (!"HISTORICAL_GP_BO".equals(request.path("mode").asText())) throw new ApiException(ApiErrorCode.BAD_REQUEST, "当前优化支持历史实验数据及明确X/Y选择");
                var datasets = datasetIds(request);
                if (!request.path("selectedRowIdsByDataset").isObject()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "请明确选择每个数据集的实验行");
                var guardedRequest = request.deepCopy(); var snapshots = guardedRequest.putObject("expectedDatasetHashes");
                for (var id : datasets) {
                    var raw = optimizations.historyDataset(id);
                    // The current service freezes the whole dataset, even for a selected subset.
                    // Until its permission work lands, never submit a mixed-access snapshot.
                    authorizeDataset(raw, true);
                    snapshots.put(id.toString(), new com.jsd.aird.ai.rnd.modeling.ConfigurationHashing(json).hash(raw));
                }
                var accepted = optimizations.submit(guardedRequest, key); response = json.valueToTree(accepted);
                response.put("id", accepted.runId().toString());
            }
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST);
        }
        if(activities!=null&&response.hasNonNull("id"))activities.bind(kind,UUID.fromString(response.path("id").asText()),args);
        response.put("kind", kind).put("operation_key", key).put("result_semantics", "计算预测或候选方案，不是实测结果");
        response.put("poll_tool", "get_compute_result"); if (response.has("id")) response.set("run_id", response.path("id"));
        return response;
    }
    private ObjectNode contract(String kind) {
        var contract = json.createObjectNode().put("kind", kind).put("identity", "组织、用户和幂等键由服务端绑定，request不接受模型指定身份");
        var fields = contract.putObject("fields");
        switch (kind) {
            case "PREDICTION" -> {
                contract.putArray("required").add("inputs").add("targets");
                fields.put("inputs", "对象：按requiredInputs的code填写有类型的值；单位与条件沿用上下文，不把未测写成0")
                    .put("targets", "数组：[{targetId, expectedModelVersionId}]；取selected中的当前目标与模型版本");
            }
            case "FORMULA_DESIGN" -> {
                contract.putArray("required").add("typedGoals").add("formulaBasis").add("targetTotal").add("baselineFormula").add("searchSpace");
                fields.put("typedGoals", "数组：[{targetId, expectedModelVersionId, operator, typedValue, mandatory, weight, minimumProbability}]；operator和typedValue按所选目标的operators、类型及单位填写")
                    .put("formulaBasis", "MASS_PERCENT或ABSOLUTE_MASS；与baselineFormula.basis一致")
                    .put("targetTotal", "质量比例为100；绝对质量为明确的总克数")
                    .put("baselineFormula", "{basis, compositionComplete, recordedTotal, components:[{materialId, ratio, unit, amountKnown}]}；材料来自materialIntersection")
                    .put("searchSpace", "{variableMaterials:[{materialId, minimum, maximum}], variableInputs:{输入code:{minimum,maximum}}}")
                    .put("fixedInputs", "对象：其余必需实验前条件，使用requiredInputs中的code及单位")
                    .put("candidateCount", "1至16，省略使用现有服务默认值");
            }
            default -> {
                var required=contract.putArray("required").add("datasetIds").add("selectedRowIdsByDataset").add("xColumns").add("yColumn");
                if("MODEL_TRAINING".equals(kind)) contract.put("activation","训练结束后解释效果，收到用户明确启用指令才调用activate_trained_model");
                else {
                    required.removeAll();required.add("mode");
                    contract.putArray("selectionAlternatives").add("trainingRunId").add("datasetIds + selectedRowIdsByDataset + xColumns + yColumn");
                }
                fields.put("mode", "HISTORICAL_GP_BO，仅用于实验优化")
                    .put("trainingRunId", "明确沿用已完成建模的样品与X/Y时使用该运行id；服务端恢复选中行、数据集及X/Y，此时无需复制datasetIds、selectedRowIdsByDataset、xColumns、yColumn")
                    .put("datasetIds", "数组：get_compute_context返回的数据集id；也支持单个datasetId")
                    .put("selectedRowIdsByDataset", "对象：{数据集id:[实际row.id,...]}；仅明确选择的样品参与计算")
                    .put("xColumns", "数组：实际columns[].code；材料、单位和条件须可比，不选实验后才知道的输入")
                    .put("yColumn", "实际数值结果列的code；与X不同")
                    .put("bounds", "对象：{X列code:{minimum,maximum}}；不提供时由现有服务采用观测范围")
                    .put("formulaTotal", "按g记录且选中完整配方时的候选总克数；不提供时沿用历史一致的总量。服务端保持配方总量约束，固定原料通过bounds.minimum=maximum指定")
                    .put("direction", "MAXIMIZE或MINIMIZE")
                    .put("access_note", "当前底层服务会冻结整张数据集，包含未授权样品时阻止提交");
            }
        }
        return contract;
    }
    private ObjectNode context(String kind, ObjectNode args) {
        if("EXPERIMENT_OPTIMIZATION".equals(kind)&&args.hasNonNull("training_run_id")) {
            var id=uuid(args,"training_run_id");
            trainingSelection(id,args);
            var run=modeling.run(id);
            var out=json.createObjectNode().put("sourceTrainingRunId",id.toString()).put("rowCount",run.path("rowCount").asInt())
                .put("selectedRowCount",run.path("selectedRowCount").asInt());
            if(activities!=null)out.set("activity",activities.authorizedContext("MODEL_TRAINING",id));
            out.set("xColumns",run.path("xColumns"));out.set("yColumn",run.path("yColumn"));
            out.putObject("request_draft").put("mode","HISTORICAL_GP_BO").put("trainingRunId",id.toString());
            out.put("selection_note","沿用该建模运行选择的样品和X/Y；当前数据集版本发生变化时须重新选择，不会自动替换样品。");
            return out;
        }
        if (args.has("target_ids") && (!args.path("target_ids").isArray() || args.path("target_ids").size() > 20)) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var targets = new ArrayList<UUID>();
        for (var value : args.path("target_ids")) targets.add(uuid(json.createObjectNode().set("id", value), "id"));
        if ("PREDICTION".equals(kind)) return (ObjectNode)clean(predictions.context(targets.isEmpty() ? null : targets));
        if ("FORMULA_DESIGN".equals(kind)) return (ObjectNode)clean(formulas.context(targets.isEmpty() ? null : targets));
        if (args.hasNonNull("dataset_id")) {
            var raw="MODEL_TRAINING".equals(kind)?modeling.dataset(uuid(args,"dataset_id")):optimizations.historyDataset(uuid(args,"dataset_id"));
            var out=authorizeDataset(raw,false);
            for(var column:out.path("columns"))if(column instanceof ObjectNode c&&c.has("name"))c.put("name",ResearchWording.business(c.path("name").asText()));
            int offset=Math.max(0,args.path("offset").asInt(0));int limit=Math.min(40,Math.max(1,args.path("limit").asInt(16)));
            var selected=new LinkedHashSet<String>();args.path("column_codes").forEach(c->selected.add(c.asText()));
            if(selected.size()>100)throw new ApiException(ApiErrorCode.BAD_REQUEST);
            var page=json.createArrayNode();var all=out.path("rows");
            for(int i=offset;i<Math.min(offset+limit,all.size());i++) {
                var row=all.path(i);var compact=json.createObjectNode();
                for(var field:List.of("id","sampleNo","label","sourceExperimentId","versionId","sampleKey"))if(row.has(field))compact.set(field,row.path(field));
                var values=compact.putObject("values");row.path("values").properties().forEach(e->{if(selected.isEmpty()||selected.contains(e.getKey()))values.set(e.getKey(),e.getValue());});
                page.add(compact);
            }
            out.set("rows",page);out.put("offset",offset).put("limit",limit).put("total_rows",all.size());
            if(offset+limit<all.size())out.put("next_offset",offset+limit);
            out.put("source_detail_tool","get_experiment_record");
            if(rawMaterials!=null) {
                var materials=out.putArray("materialDossiers");
                for(var dossier:rawMaterials.snapshot(out.path("columns"))) {
                    var brief=json.createObjectNode();for(var key:List.of("id","code","versionId","versionNo"))if(dossier.has(key))brief.set(key,dossier.path(key));
                    for(var field:List.of("grade","manufacturer","category","roles"))if(dossier.path("facts").has(field))brief.set(field,dossier.path("facts").path(field));
                    materials.add(brief);
                }
            }
            return out;
        }
        var catalog = "MODEL_TRAINING".equals(kind)?modeling.datasets(args.path("query").asText("")):optimizations.historyDatasets(args.path("query").asText("")); var result = json.createObjectNode(); var items = result.putArray("items");
        for (var item : catalog.path("items")) {
            var allowed = authorizeDataset("MODEL_TRAINING".equals(kind)?modeling.dataset(uuid(item,"id")):optimizations.historyDataset(uuid(item, "id")), false);
            if (!allowed.path("rows").isEmpty()) items.add(json.createObjectNode().put("id", item.path("id").asText())
                .put("name", item.path("name").asText()).put("rowCount", allowed.path("rows").size()));
        }
        result.put("mode", "MODEL_TRAINING".equals(kind)?"MODEL_TRAINING":"HISTORICAL_GP_BO").put("selection_required", true); return result;
    }
    private ObjectNode trainingSelection(UUID id,ObjectNode args) {
        data.require("AI_MODEL_TRAINING",id,"READ",args);
        var run=modeling.run(id);
        if(!"SUCCEEDED".equals(run.path("status").asText()))throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"请等待所选建模结果完成");
        authorizeDataset(run.path("datasetSnapshot"),true);
        if(rawMaterials!=null)rawMaterials.authorizeSnapshot(run.path("materialDossiers"));
        var request=json.createObjectNode().put("mode","HISTORICAL_GP_BO").put("trainingRunId",id.toString());
        var ids=request.putArray("datasetIds");run.path("selectedRowIdsByDataset").fieldNames().forEachRemaining(ids::add);
        request.set("selectedRowIdsByDataset",run.path("selectedRowIdsByDataset").deepCopy());
        var xs=request.putArray("xColumns");run.path("xColumns").forEach(column->xs.add(column.path("code").asText()));
        request.put("yColumn",run.path("yColumn").path("code").asText());return request;
    }
    private ObjectNode authorizeDataset(JsonNode raw, boolean allRequired) {
        var result = json.createObjectNode();
        for (var field : List.of("id", "name", "projectionVersion", "sourceDatasetIds")) if (raw.has(field)) result.set(field, raw.path(field).deepCopy());
        var rows = result.putArray("rows"); var checked = new HashMap<String,Boolean>();
        for (var row : raw.path("rows")) {
            String id = row.path("sourceExperimentId").asText(); String version = row.path("versionId").asText();
            String key = id + ":" + version;
            boolean permitted = checked.computeIfAbsent(key, ignored -> {
                if (id.isBlank() || version.isBlank()) return false;
                try {
                    if(sourceAccess!=null)sourceAccess.requireReadableVersion(ActorContext.required().organizationId(),UUID.fromString(id),UUID.fromString(version));
                    else {
                        var ref = json.createObjectNode().put("type", "EXPERIMENT").put("id", id).put("version_id", version);
                        existing.invoke("business_read", json.createObjectNode().set("ref", ref));
                    }
                    return true;
                } catch (ApiException error) {
                    if (Set.of(ApiErrorCode.DATA_SCOPE_DENIED, ApiErrorCode.PERMISSION_DENIED, ApiErrorCode.NOT_FOUND).contains(error.errorCode())) return false;
                    throw error;
                }
            });
            if (permitted) rows.add(clean(row));
            else if (allRequired) throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED, "计算服务会冻结整个数据集；当前数据集包含未授权记录，请使用完整获授权的数据集");
        }
        var columns = result.putArray("columns");
        for (var column : raw.path("columns")) {
            String code = column.path("code").asText(); int filled = 0; boolean visible = false;
            for (var row : rows) {
                if (row.path("values").has(code) || row.path("cellStates").has(code)) visible = true;
                if (row.path("values").hasNonNull(code)) filled++;
            }
            if (visible) columns.add(((ObjectNode)clean(column)).put("filledCount", filled));
        }
        var issues = result.putArray("issues"); var visibleIds = new HashSet<String>(); rows.forEach(row -> visibleIds.add(row.path("id").asText()));
        for (var issue : raw.path("issues"))
            if (rows.size() == raw.path("rows").size() || visibleIds.contains(issue.path("rowId").asText())) issues.add(clean(issue));
        result.put("rowCount", rows.size()); return result;
    }
    private List<UUID> datasetIds(ObjectNode request) {
        var ids = new ArrayList<UUID>(); request.path("datasetIds").forEach(value -> ids.add(uuid(json.createObjectNode().set("id", value), "id")));
        if (ids.isEmpty()) ids.add(uuid(request, "datasetId"));
        if (ids.size() > 20) throw new ApiException(ApiErrorCode.BAD_REQUEST); return ids;
    }
    public ObjectNode result(String kind, UUID id) {
        String type = TYPES.get(kind); if (type == null) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        data.require(type, id, "READ", json.createObjectNode().put("run_id", id.toString()));
        ObjectNode body = switch (kind) {
            case "MODEL_TRAINING" -> {
                var run=(ObjectNode)modeling.run(id).deepCopy();authorizeDataset(run.path("datasetSnapshot"),true);
                if(rawMaterials!=null)rawMaterials.authorizeSnapshot(run.path("materialDossiers"));run.remove("datasetSnapshot");
                run.set("registration",registration.registration(ActorContext.required().organizationId(),id));run.set("executionStatus",run.path("status"));yield run;
            }
            case "PREDICTION" -> (ObjectNode)predictions.record(id).body().deepCopy();
            case "FORMULA_DESIGN" -> json.valueToTree(formulas.get(id));
            case "EXPERIMENT_OPTIMIZATION" -> json.valueToTree(optimizations.get(id));
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST);
        };
        if(activities!=null)body.set("activity",activities.authorizedContext(kind,id));
        if (body.path("frozenRequest").path("datasetSnapshot").isObject()) authorizeDataset(body.path("frozenRequest").path("datasetSnapshot"), true);
        if(rawMaterials!=null)rawMaterials.authorizeSnapshot(body.path("frozenRequest").path("materialDossiers"));
        // Workbook/editor and model weight snapshots stay with the authoritative service.
        var lineage = json.createObjectNode();
        var frozen = body.path("frozenRequest");
        for (var key : List.of("datasetId", "datasetIds", "datasetName", "target", "targetName", "features", "selectedRowIdsByDataset", "projectionVersion", "parentRunId", "materialDossiers", "observationManifest"))
            if (frozen.has(key)) lineage.set(key, frozen.path(key));
        body = (ObjectNode)clean(body); body.remove(List.of("frozenRequest", "frozenBaseline", "frozenEvidence"));
        if (!lineage.isEmpty()) body.set("data_lineage", lineage);
        body.put("id", id.toString()).put("kind", kind).put("result_semantics", "模型估计与候选方案，实测值另行记录");
        String path = switch (kind) { case "MODEL_TRAINING" -> "/assistant/model-center"; case "PREDICTION" -> "/assistant/performance-prediction"; case "FORMULA_DESIGN" -> "/assistant/formula-prediction"; default -> "/assistant/experiment-optimization"; };
        var evidenceBody=body.deepCopy();
        if("MODEL_TRAINING".equals(kind)) {
            // Activation is a separate operation. It must not change an already cited training result.
            evidenceBody.putNull("registration");
            if(activities!=null)evidenceBody.set("activity",activities.context(kind,id));
        }
        var record = view(type, id, null, "研发计算 · " + kind, evidenceBody, path + "?runId=" + id, List.of());
        var candidate = data.candidate(record); var anchor = (ObjectNode)candidate.path("source").path("anchor");
        anchor.put("compute_kind", kind).put("read_path", "/api/v1/agents/business/compute/" + kind + "/" + id + "?revision=" + record.ref().path("revision").asText());
        candidate.put("model_text", "固定计算结果及数值见result；当前启用状态见result.registration。预测和候选方案与实测结果分开。");
        compactMaterials(body);
        var result = json.createObjectNode(); result.set("result", body); var evidence = result.putArray("candidates");
        if (!Set.of("QUEUED", "RUNNING", "ACCEPTED").contains(body.path("executionStatus").asText())) evidence.add(candidate);
        return result;
    }
    private void compactMaterials(ObjectNode body) {
        for(var parent:List.of(body,body.path("data_lineage"))) {
            if(!(parent instanceof ObjectNode object)||!object.path("materialDossiers").isArray())continue;
            var briefs=json.createArrayNode();
            for(var material:object.path("materialDossiers")) {
                var brief=json.createObjectNode();
                for(var key:List.of("id","code","versionId","versionNo"))if(material.has(key))brief.set(key,material.path(key));
                for(var key:List.of("grade","manufacturer","category","roles"))if(material.path("facts").has(key))brief.set(key,material.path("facts").path(key));
                briefs.add(brief);
            }
            object.set("materialDossiers",briefs);
            object.put("material_detail_tool","business_read · RAW_MATERIAL");
        }
    }
    @org.springframework.transaction.annotation.Transactional
    public ObjectNode receipt(ObjectNode args) {
        String tool = args.path("tool").asText();
        String kind = switch (tool) { case "run_model_training", "activate_trained_model" -> "MODEL_TRAINING"; case "run_prediction" -> "PREDICTION"; case "run_formula_design" -> "FORMULA_DESIGN"; case "run_experiment_optimization", "continue_experiment_optimization" -> "EXPERIMENT_OPTIMIZATION"; default -> throw new ApiException(ApiErrorCode.BAD_REQUEST); };
        data.require(TYPES.get(kind), null, "READ", args);
        var actor = ActorContext.required(); String key = args.path("operation_key").asText();
        if (!key.matches("[a-f0-9]{64}")) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        boolean close=args.path("close_uncommitted").asBoolean();
        if(close)lockOperation(tool,key);
        var closed=closedReceipt(tool,key);if(closed.has("result"))return closed;
        if("activate_trained_model".equals(tool)) {
            var receipt=activationReceipt(key);
            if(receipt.has("result"))result(kind,uuid(receipt.path("result"),"run_id"));
            if(!receipt.has("result")&&close)return closeOperation(tool,key);
            return receipt.put("operation_key",key).put("status",receipt.has("result")?"SUCCEEDED":"UNKNOWN");
        }
        UUID id = switch (kind) {
            case "MODEL_TRAINING" -> jdbc.queryForList("SELECT id FROM ai.modeling_workbench_run WHERE organization_id=? AND created_by=? AND idempotency_key=?",UUID.class,actor.organizationId(),actor.userId(),key).stream().findFirst().orElse(null);
            case "PREDICTION" -> predictionRecords.byKey(actor.organizationId(), actor.userId(), key).map(PredictionRepository.PredictionRow::id).orElse(null);
            case "FORMULA_DESIGN" -> formulaRecords.byKey(actor.organizationId(), actor.userId(), key).map(FormulaDesignRepository.Row::id).orElse(null);
            default -> optimizationRecords.byKey(actor.organizationId(), actor.userId(), key).map(OptimizationRepository.RunRow::id).orElse(null);
        };
        var response = json.createObjectNode().put("status", id == null ? "UNKNOWN" : "SUCCEEDED").put("operation_key", key);
        if(id==null&&close)return closeOperation(tool,key);
        if (id != null) {
            data.require(TYPES.get(kind), id, "READ", args);
            // Durable acceptance proves submission; it does not claim computation succeeded.
            response.set("result", json.createObjectNode().put("id", id.toString()).put("kind", kind).put("executionStatus", "ACCEPTED").put("poll_tool", "get_compute_result"));
        }
        return response;
    }
    private void lockOperation(String tool,String key) {
        var actor=ActorContext.required();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,43))",actor.organizationId()+":"+actor.userId()+":"+tool+":"+key);
    }
    private ObjectNode closedReceipt(String tool,String key) {
        if(jdbc==null)return json.createObjectNode();
        var actor=ActorContext.required();
        return jdbc.query("SELECT detail_jsonb::text FROM ops.audit_log WHERE organization_id=? AND actor_id=? AND action='AI_ASSISTANT_COMPUTE_CLOSED' AND detail_jsonb->>'operation_key'=? AND detail_jsonb->>'tool'=?",
            (rs,n)->{try{return (ObjectNode)json.readTree(rs.getString(1));}catch(java.io.IOException e){throw new IllegalStateException(e);}},actor.organizationId(),actor.userId(),key,tool)
            .stream().findFirst().orElseGet(json::createObjectNode);
    }
    private ObjectNode closeOperation(String tool,String key) {
        var actor=ActorContext.required();var id=UUID.nameUUIDFromBytes((actor.organizationId()+":"+actor.userId()+":"+tool+":"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var receipt=json.createObjectNode().put("status","FAILED").put("operation_key",key).put("tool",tool);
        receipt.putObject("result").put("id",id.toString()).put("error","COMPUTE_NOT_COMMITTED").put("message","原计算未提交，操作已关闭");
        audit.append(actor.organizationId(),actor.userId(),"AI_ASSISTANT_COMPUTE_CLOSED","AI_COMPUTE_OPERATION",id,receipt);
        return receipt;
    }
    private ObjectNode activationReceipt(String key) {
        var actor=ActorContext.required();
        return jdbc.query("SELECT detail_jsonb::text FROM ops.audit_log WHERE organization_id=? AND actor_id=? AND action='AI_ASSISTANT_MODEL_ACTIVATED' AND detail_jsonb->>'operation_key'=?",
            (rs,n)->{try{return (ObjectNode)json.readTree(rs.getString(1));}catch(java.io.IOException e){throw new IllegalStateException(e);}},actor.organizationId(),actor.userId(),key)
            .stream().findFirst().orElseGet(json::createObjectNode);
    }
    public void validateSource(JsonNode source, String excerpt) {
        String kind = source.path("anchor").path("compute_kind").asText();
        var actual = result(kind, uuid(source, "resource_id")).path("candidates").path(0);
        BusinessDataService.validateCandidate(source, excerpt, actual);
    }
}
