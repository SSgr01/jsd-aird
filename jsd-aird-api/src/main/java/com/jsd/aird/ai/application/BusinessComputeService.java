package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.api.AiRndContracts.PredictionRequest;
import com.jsd.aird.ai.rnd.prediction.*;
import com.jsd.aird.ai.rnd.research.*;
import com.jsd.aird.ai.rnd.optimization.*;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.*;

/** Calls the authoritative compute services; it never implements a second algorithm. */
@Service
public class BusinessComputeService {
    private static final Map<String,String> TYPES = Map.of("PREDICTION", "AI_PREDICTION", "FORMULA_DESIGN", "AI_FORMULA_DESIGN", "EXPERIMENT_OPTIMIZATION", "AI_EXPERIMENT_OPTIMIZATION");
    private final BusinessDataService data;
    private final BusinessExistingData existing;
    private final PredictionService predictions;
    private final FormulaDesignService formulas;
    private final OptimizationService optimizations;
    private final PredictionRepository predictionRecords;
    private final FormulaDesignRepository formulaRecords;
    private final OptimizationRepository optimizationRecords;
    private final ObjectMapper json;
    public BusinessComputeService(BusinessDataService data, BusinessExistingData existing, PredictionService predictions,
            FormulaDesignService formulas, OptimizationService optimizations, PredictionRepository predictionRecords,
            FormulaDesignRepository formulaRecords, OptimizationRepository optimizationRecords, ObjectMapper json) {
        this.data = data; this.existing = existing; this.predictions = predictions; this.formulas = formulas;
        this.optimizations = optimizations; this.predictionRecords = predictionRecords; this.formulaRecords = formulaRecords;
        this.optimizationRecords = optimizationRecords; this.json = json;
    }
    public ObjectNode invoke(String action, ObjectNode args) {
        String kind = switch (action) {
            case "run_prediction" -> "PREDICTION"; case "run_formula_design" -> "FORMULA_DESIGN";
            case "run_experiment_optimization" -> "EXPERIMENT_OPTIMIZATION"; default -> args.path("kind").asText();
        };
        var type = TYPES.get(kind); if (type == null) throw new ApiException(ApiErrorCode.BAD_REQUEST, "未知计算类型");
        UUID runId = optionalId(args, "run_id");
        data.require(type, runId, action.startsWith("run_") ? "EXECUTE" : "READ", args);
        if ("get_compute_context".equals(action)) {
            var response = context(kind, args); response.set("request_contract", contract(kind)); return response;
        }
        if ("get_compute_result".equals(action)) return result(kind, uuid(args, "run_id"));
        if (!(args.path("request") instanceof ObjectNode request)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "需要按计算上下文填写 request");
        String key = args.path("operation_key").asText();
        if (!key.matches("[a-f0-9]{64}")) throw new ApiException(ApiErrorCode.BAD_REQUEST, "计算调用缺少服务端操作身份");
        ObjectNode response;
        switch (kind) {
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
                contract.putArray("required").add("mode").add("datasetIds").add("selectedRowIdsByDataset").add("xColumns").add("yColumn");
                fields.put("mode", "HISTORICAL_GP_BO")
                    .put("datasetIds", "数组：get_compute_context返回的数据集id；也支持单个datasetId")
                    .put("selectedRowIdsByDataset", "对象：{数据集id:[实际row.id,...]}；仅明确选择的样品参与计算")
                    .put("xColumns", "数组：实际columns[].code；材料、单位和条件须可比，不选实验后才知道的输入")
                    .put("yColumn", "实际数值结果列的code；与X不同")
                    .put("bounds", "对象：{X列code:{minimum,maximum}}；不提供时由现有服务采用观测范围")
                    .put("direction", "MAXIMIZE或MINIMIZE")
                    .put("access_note", "当前底层服务会冻结整张数据集，包含未授权样品时阻止提交");
            }
        }
        return contract;
    }
    private ObjectNode context(String kind, ObjectNode args) {
        if (args.has("target_ids") && (!args.path("target_ids").isArray() || args.path("target_ids").size() > 20)) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var targets = new ArrayList<UUID>();
        for (var value : args.path("target_ids")) targets.add(uuid(json.createObjectNode().set("id", value), "id"));
        if ("PREDICTION".equals(kind)) return (ObjectNode)clean(predictions.context(targets.isEmpty() ? null : targets));
        if ("FORMULA_DESIGN".equals(kind)) return (ObjectNode)clean(formulas.context(targets.isEmpty() ? null : targets));
        if (args.hasNonNull("dataset_id")) return authorizeDataset(optimizations.historyDataset(uuid(args, "dataset_id")), false);
        var catalog = optimizations.historyDatasets(args.path("query").asText("")); var result = json.createObjectNode(); var items = result.putArray("items");
        for (var item : catalog.path("items")) {
            var allowed = authorizeDataset(optimizations.historyDataset(uuid(item, "id")), false);
            if (!allowed.path("rows").isEmpty()) items.add(json.createObjectNode().put("id", item.path("id").asText())
                .put("name", item.path("name").asText()).put("rowCount", allowed.path("rows").size()));
        }
        result.put("mode", "HISTORICAL_GP_BO").put("selection_required", true); return result;
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
                    var ref = json.createObjectNode().put("type", "EXPERIMENT").put("id", id).put("version_id", version);
                    existing.invoke("business_read", json.createObjectNode().set("ref", ref)); return true;
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
            case "PREDICTION" -> (ObjectNode)predictions.record(id).body().deepCopy();
            case "FORMULA_DESIGN" -> json.valueToTree(formulas.get(id));
            case "EXPERIMENT_OPTIMIZATION" -> json.valueToTree(optimizations.get(id));
            default -> throw new ApiException(ApiErrorCode.BAD_REQUEST);
        };
        if (body.path("frozenRequest").path("datasetSnapshot").isObject()) authorizeDataset(body.path("frozenRequest").path("datasetSnapshot"), true);
        // Workbook/editor and model weight snapshots stay with the authoritative service.
        var lineage = json.createObjectNode();
        var frozen = body.path("frozenRequest");
        for (var key : List.of("datasetId", "datasetIds", "datasetName", "target", "targetName", "features", "selectedRowIdsByDataset", "projectionVersion"))
            if (frozen.has(key)) lineage.set(key, frozen.path(key));
        body = (ObjectNode)clean(body); body.remove(List.of("frozenRequest", "frozenBaseline", "frozenEvidence"));
        if (!lineage.isEmpty()) body.set("data_lineage", lineage);
        body.put("id", id.toString()).put("kind", kind).put("result_semantics", "预测、模拟或候选方案；不能作为实测结果");
        String path = switch (kind) { case "PREDICTION" -> "/assistant/performance-prediction"; case "FORMULA_DESIGN" -> "/assistant/formula-prediction"; default -> "/assistant/experiment-optimization"; };
        var record = view(type, id, null, "研发计算 · " + kind, body, path + "?runId=" + id, List.of());
        var candidate = data.candidate(record); var anchor = (ObjectNode)candidate.path("source").path("anchor");
        anchor.put("compute_kind", kind).put("read_path", "/api/v1/agents/business/compute/" + kind + "/" + id + "?revision=" + record.ref().path("revision").asText());
        candidate.put("model_text", "权威计算状态及数值见result；预测和候选方案与实测结果分开。");
        var result = json.createObjectNode(); result.set("result", body); var evidence = result.putArray("candidates");
        if (!Set.of("QUEUED", "RUNNING", "ACCEPTED").contains(body.path("executionStatus").asText())) evidence.add(candidate);
        return result;
    }
    public ObjectNode receipt(ObjectNode args) {
        String tool = args.path("tool").asText();
        String kind = switch (tool) { case "run_prediction" -> "PREDICTION"; case "run_formula_design" -> "FORMULA_DESIGN"; case "run_experiment_optimization" -> "EXPERIMENT_OPTIMIZATION"; default -> throw new ApiException(ApiErrorCode.BAD_REQUEST); };
        data.require(TYPES.get(kind), null, "READ", args);
        var actor = ActorContext.required(); String key = args.path("operation_key").asText();
        if (!key.matches("[a-f0-9]{64}")) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        UUID id = switch (kind) {
            case "PREDICTION" -> predictionRecords.byKey(actor.organizationId(), actor.userId(), key).map(PredictionRepository.PredictionRow::id).orElse(null);
            case "FORMULA_DESIGN" -> formulaRecords.byKey(actor.organizationId(), actor.userId(), key).map(FormulaDesignRepository.Row::id).orElse(null);
            default -> optimizationRecords.byKey(actor.organizationId(), actor.userId(), key).map(OptimizationRepository.RunRow::id).orElse(null);
        };
        var response = json.createObjectNode().put("status", id == null ? "UNKNOWN" : "SUCCEEDED").put("operation_key", key);
        if (id != null) {
            data.require(TYPES.get(kind), id, "READ", args);
            // Durable acceptance proves submission; it does not claim computation succeeded.
            response.set("result", json.createObjectNode().put("id", id.toString()).put("kind", kind).put("executionStatus", "ACCEPTED").put("poll_tool", "get_compute_result"));
        }
        return response;
    }
    public void validateSource(JsonNode source, String excerpt) {
        String kind = source.path("anchor").path("compute_kind").asText();
        var actual = result(kind, uuid(source, "resource_id")).path("candidates").path(0);
        BusinessDataService.validateCandidate(source, excerpt, actual);
    }
}
