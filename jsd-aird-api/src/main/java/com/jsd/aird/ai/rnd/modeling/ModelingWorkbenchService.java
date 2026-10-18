package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.application.FormulaModelArtifactStore;
import com.jsd.aird.ai.rnd.optimization.OptimizationService;
import com.jsd.aird.ai.rnd.compute.ModelComputeClient;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ModelingWorkbenchService {
    private static final int MINIMUM_ROWS = 6;
    private static final int MAXIMUM_ROWS = 20_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OptimizationService optimization;
    private final ConfigurationHashing hashing;
    private final OpsAsyncFacade async;
    private final ModelComputeClient compute;
    private final FormulaModelArtifactStore artifacts;

    public ModelingWorkbenchService(JdbcTemplate jdbc, ObjectMapper json, OptimizationService optimization,
                                    ConfigurationHashing hashing, OpsAsyncFacade async,
                                    ModelComputeClient compute, FormulaModelArtifactStore artifacts) {
        this.jdbc = jdbc;
        this.json = json;
        this.optimization = optimization;
        this.hashing = hashing;
        this.async = async;
        this.compute = compute;
        this.artifacts = artifacts;
    }

    public JsonNode datasets(String keyword) {
        return optimization.modelingHistoryDatasets(keyword);
    }

    public JsonNode dataset(UUID id) {
        return optimization.modelingHistoryDataset(id);
    }

    @Transactional
    public JsonNode train(TrainCommand command, String idempotencyKey) {
        var actor = ActorContext.required();
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw validation("训练请求缺少唯一标识，请重新点击开始训练");
        var datasetIds = requestedDatasetIds(command);

        var dataset = optimization.modelingHistoryDatasetSetForTraining(datasetIds);
        var frozenRequest = freezeRequest(dataset, command, datasetIds);
        var requestHash = hashing.hash(frozenRequest);
        var previous = jdbc.query("""
                SELECT id,request_hash FROM ai.modeling_workbench_run
                WHERE organization_id=? AND created_by=? AND idempotency_key=?
                """, (rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)},
                actor.organizationId(), actor.userId(), idempotencyKey.strip()).stream().findFirst().orElse(null);
        if (previous != null) {
            if (!requestHash.equals(previous[1])) throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            return accepted((UUID) previous[0]);
        }

        var runId = UUID.randomUUID();
        var x = (ArrayNode) frozenRequest.path("xColumns");
        var y = frozenRequest.path("yColumn");
        try {
            jdbc.update("""
                    INSERT INTO ai.modeling_workbench_run(
                        id,organization_id,created_by,dataset_id,dataset_name,idempotency_key,request_hash,
                        request_jsonb,selected_x_jsonb,selected_y_jsonb,status,progress,current_stage)
                    VALUES(?,?,?,?,?,?,?,?,?,?,'QUEUED',0,'QUEUED')
                    """, runId, actor.organizationId(), actor.userId(), datasetIds.getFirst(),
                    dataset.path("name").asText("实验数据"), idempotencyKey.strip(), requestHash,
                    pg(frozenRequest), pg(x), pg(y));
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            var raced = jdbc.queryForObject("SELECT id,request_hash FROM ai.modeling_workbench_run WHERE organization_id=? AND created_by=? AND idempotency_key=?",
                    (rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)},
                    actor.organizationId(), actor.userId(), idempotencyKey.strip());
            if (!requestHash.equals(raced[1])) throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            return accepted((UUID) raced[0]);
        }

        var payload = json.createObjectNode().put("organizationId", actor.organizationId().toString()).put("runId", runId.toString());
        var jobId = async.enqueue(actor.organizationId(), "AI_MODELING_WORKBENCH_TRAIN", payload,
                "modeling-workbench:" + runId, 50, 3);
        jdbc.update("UPDATE ai.modeling_workbench_run SET ops_job_id=?,updated_at=now() WHERE organization_id=? AND id=?",
                jobId, actor.organizationId(), runId);
        return accepted(runId);
    }

    public JsonNode run(UUID id) {
        var actor = ActorContext.required();
        return runView(actor.organizationId(), actor.userId(), id);
    }

    public JsonNode execute(UUID organizationId, UUID runId) {
        var existing = jdbc.query("SELECT status FROM ai.modeling_workbench_run WHERE organization_id=? AND id=?",
                (rs, n) -> rs.getString(1), organizationId, runId).stream().findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "建模任务不存在"));
        if ("SUCCEEDED".equals(existing)) return runView(organizationId, null, runId);
        jdbc.update("""
                UPDATE ai.modeling_workbench_run
                SET status='RUNNING',progress=8,current_stage='PREPARING',updated_at=now()
                WHERE organization_id=? AND id=? AND status IN ('QUEUED','RUNNING')
                """, organizationId, runId);
        async.updateProgress(organizationId, "modeling-workbench:" + runId, 8, "PREPARING");

        var request = jdbc.query("SELECT request_jsonb FROM ai.modeling_workbench_run WHERE organization_id=? AND id=?",
                (rs, n) -> read(rs, "request_jsonb"), organizationId, runId).stream().findFirst().orElseThrow();
        var modelKey = "ai/rnd/modeling-workbench/" + organizationId + "/" + runId + "/model-bundle.zip";
        var write = artifacts.writeRef(modelKey, "application/zip");
        var computeRequest = ((ObjectNode) request.deepCopy()).put("requestId", runId.toString())
                .put("output", write.url());
        async.updateProgress(organizationId, "modeling-workbench:" + runId, 35, "TRAINING");
        jdbc.update("UPDATE ai.modeling_workbench_run SET progress=35,current_stage='TRAINING',updated_at=now() WHERE organization_id=? AND id=? AND status='RUNNING'",
                organizationId, runId);
        var response = compute.trainWorkbench(computeRequest);
        if (!"SUCCEEDED".equals(response.path("status").asText()))
            throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "模型训练没有返回有效结果，请检查数据后重试");

        var expectedHash = response.path("modelBundle").path("sha256").asText();
        if (expectedHash.isBlank()) throw new ApiException(ApiErrorCode.AI_MODEL_ARTIFACT_INVALID, "模型文件校验信息缺失");
        var modelArtifact = artifacts.verify(modelKey, expectedHash, "application/zip");
        var result = json.createObjectNode();
        result.put("rowCount", response.path("rowCount").asInt());
        result.put("excludedRowCount", response.path("excludedRowCount").asInt());
        result.set("metrics", response.path("metrics").deepCopy());
        result.set("applicabilityDomain", response.path("applicabilityDomain").deepCopy());
        jdbc.update("""
                UPDATE ai.modeling_workbench_run
                SET status='SUCCEEDED',progress=100,current_stage='SUCCEEDED',result_jsonb=?::jsonb,
                    model_artifact_key=?,model_artifact_sha256=?,finished_at=now(),updated_at=now()
                WHERE organization_id=? AND id=?
                """, result.toString(), modelArtifact.objectKey(), modelArtifact.sha256(), organizationId, runId);
        async.updateProgress(organizationId, "modeling-workbench:" + runId, 100, "SUCCEEDED");
        return runView(organizationId, null, runId);
    }

    public void fail(UUID organizationId, UUID runId, Exception exception) {
        var message = exception == null ? null : exception.getMessage();
        if (message == null || message.isBlank() || message.length() > 500 || message.contains(" at "))
            message = "训练任务未能完成，请检查 X/Y 选择和实验记录后重试。";
        var error = json.createObjectNode().put("code", exception instanceof ApiException api ? api.errorCode().name() : "COMPUTE_UNAVAILABLE")
                .put("message", message);
        jdbc.update("""
                UPDATE ai.modeling_workbench_run SET status='FAILED',progress=100,current_stage='FAILED',
                    error_jsonb=?::jsonb,finished_at=now(),updated_at=now()
                WHERE organization_id=? AND id=? AND status IN ('QUEUED','RUNNING')
                """, error.toString(), organizationId, runId);
    }

    private List<UUID> requestedDatasetIds(TrainCommand command) {
        if (command == null) throw validation("请选择至少一张实验数据表");
        var supplied = command.datasetIds() == null || command.datasetIds().isEmpty()
                ? command.datasetId() == null ? List.<UUID>of() : List.of(command.datasetId())
                : command.datasetIds();
        if (supplied.isEmpty()) throw validation("请选择至少一张实验数据表");
        if (supplied.size() > 20) throw validation("一次最多合并 20 张实验数据表");
        if (new LinkedHashSet<>(supplied).size() != supplied.size() || supplied.stream().anyMatch(Objects::isNull))
            throw validation("数据表不能重复选择");
        return List.copyOf(supplied);
    }

    private ObjectNode freezeRequest(JsonNode dataset, TrainCommand command, List<UUID> datasetIds) {
        var columns = new LinkedHashMap<String, JsonNode>();
        dataset.path("columns").forEach(column -> columns.put(column.path("code").asText(), column));
        if (columns.isEmpty()) throw validation("这份数据没有可供选择的列");
        var ySource = columns.get(command.yColumn());
        if (ySource == null) throw validation("所选 Y 列已不存在，请刷新数据");
        var y = selectedColumn(ySource);

        if (command.xColumns() == null || command.xColumns().isEmpty()) throw validation("至少选择一列 X（用于预测的信息）");
        if (command.xColumns().size() > 100) throw validation("一次最多选择 100 列 X");
        var uniqueX = new LinkedHashSet<>(command.xColumns());
        if (uniqueX.size() != command.xColumns().size()) throw validation("X 列不能重复");
        if (uniqueX.contains(command.yColumn())) throw validation("Y 列不能同时作为 X 输入");
        var xColumns = json.createArrayNode();
        for (var code : uniqueX) {
            var source = columns.get(code);
            if (source == null) throw validation("所选 X 列已不存在，请刷新数据");
            xColumns.add(selectedColumn(source));
        }

        var rowSources = dataset.path("rows");
        if (!rowSources.isArray()) throw validation("所选数据表没有可用记录");
        var selectedIds = selectedRowIds(dataset, command.selectedRowIdsByDataset(), datasetIds);
        int selectedCount = selectedIds.values().stream().mapToInt(Set::size).sum();
        if (selectedCount == 0) throw validation("请至少勾选一条实验记录");
        if (selectedCount > MAXIMUM_ROWS)
            throw validation("所选记录超过单次建模上限（20,000 条），请减少勾选记录");
        var rows = json.createArrayNode();
        var selectedRows = json.createArrayNode();
        var distinct = new LinkedHashMap<String, LinkedHashSet<String>>();
        uniqueX.forEach(code -> distinct.put(code, new LinkedHashSet<>()));
        var targetValues = new LinkedHashMap<String, Integer>();
        for (var source : rowSources) {
            var sourceDatasetId = source.path("sourceDatasetId").asText(datasetIds.getFirst().toString());
            var sourceRowId = source.path("id").asText();
            if (!selectedIds.getOrDefault(sourceDatasetId, Set.of()).contains(sourceRowId)) continue;
            var sourceValues = source.path("values");
            var selectedRow = selectedRows.addObject().put("id", sourceDatasetId + ":" + sourceRowId);
            for (var metadata : List.of("label", "title", "versionId", "sourceDatasetName", "sourceExperimentId", "sampleKey", "sampleNo", "sourceContextKey", "sourceRefs"))
                if (source.has(metadata)) selectedRow.set(metadata, source.get(metadata));
            var checkedValues = selectedRow.putObject("values");
            for (var code : uniqueX) checkedValues.set(code, sourceValues.has(code) ? sourceValues.get(code).deepCopy() : json.nullNode());
            checkedValues.set(command.yColumn(), sourceValues.has(command.yColumn()) ? sourceValues.get(command.yColumn()).deepCopy() : json.nullNode());
            var selectedValues = json.createObjectNode();
            boolean complete = true;
            for (var code : uniqueX) {
                var value = sourceValues.path(code);
                var column = columns.get(code);
                if (!validValue(value, column.path("kind").asText())) { complete = false; break; }
                selectedValues.set(code, normalizedValue(value, column.path("kind").asText()));
            }
            var targetValue = sourceValues.path(command.yColumn());
            if (!complete || !validValue(targetValue, y.path("kind").asText())) continue;
            var row = rows.addObject().put("id", sourceDatasetId + ":" + sourceRowId);
            for(var metadata:List.of("label","title","versionId","sourceDatasetName","sourceExperimentId","sampleKey","sampleNo","sourceContextKey","sourceRefs")) if(source.has(metadata))row.set(metadata,source.get(metadata));
            selectedValues.set(command.yColumn(), normalizedValue(targetValue, y.path("kind").asText()));
            row.set("values", selectedValues);
            uniqueX.forEach(code -> distinct.get(code).add(selectedValues.path(code).asText()));
            targetValues.merge(selectedValues.path(command.yColumn()).asText(), 1, Integer::sum);
        }
        if (rows.size() < MINIMUM_ROWS)
            throw validation("所选 X/Y 只有 " + rows.size() + " 条完整记录，至少需要 6 条；请换列或补充实验记录。");
        if (distinct.values().stream().allMatch(values -> values.size() < 2))
            throw validation("所选 X 在完整记录中都没有变化，请选择有不同取值的输入列。");
        if ("NUMERIC".equals(y.path("kind").asText()) && targetValues.size() < 2)
            throw validation("Y 列只有一种结果，请选择有不同实测值的结果列。");
        if (!"NUMERIC".equals(y.path("kind").asText())) {
            if (targetValues.size() < 2) throw validation("Y 列只有一种结果，无法训练分类模型。");
            if (targetValues.values().stream().min(Integer::compareTo).orElse(0) < 2)
                throw validation("Y 列中每种结果至少需要 2 条记录；请补充少数类别数据或更换 Y 列。");
        }

        var request = json.createObjectNode().put("datasetId", datasetIds.getFirst().toString())
                .put("datasetName", dataset.path("name").asText("实验数据"))
                .put("seed", 17).put("rowCount", rows.size()).put("selectedRowCount", selectedCount);
        request.set("datasetIds", json.valueToTree(datasetIds));
        request.set("selectedRowIdsByDataset", json.valueToTree(selectedIds));
        request.set("xColumns", xColumns);
        request.set("yColumn", y);
        request.set("rows", rows);
        request.set("selectedRows", selectedRows);
        request.set("datasetSnapshot",dataset.deepCopy());
        var materials=request.putArray("materialColumns");
        dataset.path("columns").forEach(column->{if(mappedFormulaColumn(column))materials.add(selectedColumn(column));});
        request.set("projectionVersion",dataset.path("projectionVersion"));
        request.set("dataIssues",dataset.path("issues"));
        return request;
    }

    private Map<String, Set<String>> selectedRowIds(JsonNode dataset, Map<String, List<String>> requested,
                                                    List<UUID> datasetIds) {
        var available = new LinkedHashMap<String, Set<String>>();
        for (var id : datasetIds) available.put(id.toString(), new LinkedHashSet<>());
        dataset.path("rows").forEach(row -> {
            var sourceId = row.path("sourceDatasetId").asText(datasetIds.getFirst().toString());
            var rowId = row.path("id").asText();
            if (available.containsKey(sourceId) && !rowId.isBlank()) available.get(sourceId).add(rowId);
        });
        if (requested == null) return available;
        for (var key : requested.keySet()) if (!available.containsKey(key))
            throw validation("勾选记录和数据表不匹配，请重新选择");
        var selected = new LinkedHashMap<String, Set<String>>();
        for (var datasetId : datasetIds) {
            var key = datasetId.toString();
            var values = requested.get(key);
            if (values == null) throw validation("请先确认每张数据表中要使用的记录");
            var ids = new LinkedHashSet<>(values);
            if (ids.size() != values.size()) throw validation("同一条实验记录不能重复选择");
            if (!available.get(key).containsAll(ids)) throw validation("勾选的实验记录已变化，请刷新数据后重试");
            selected.put(key, ids);
        }
        return selected;
    }

    private ObjectNode selectedColumn(JsonNode source) {
        var result = json.createObjectNode().put("code", source.path("code").asText())
                .put("name", source.path("name").asText(source.path("code").asText()))
                .put("kind", source.path("kind").asText("CATEGORICAL"));
        for(var metadata:List.of("testMethod","testMethods","testStage","semanticKey","semanticType","materialId","materialCode","mappingStatus","compositionBasis","measurementComponent"))if(source.hasNonNull(metadata))result.set(metadata,source.get(metadata));
        if (source.hasNonNull("unit")) result.put("unit", source.path("unit").asText());
        else result.putNull("unit");
        return result;
    }

    private boolean mappedFormulaColumn(JsonNode column) {
        if("UNMAPPED".equals(column.path("mappingStatus").asText())
                || !"NUMERIC".equals(column.path("kind").asText()))return false;
        try {
            var materialId=UUID.fromString(column.path("materialId").asText()).toString();
            var prefix="formula:"+materialId;var code=column.path("code").asText();
            return code.equals(prefix)||code.startsWith(prefix+"|");
        } catch(IllegalArgumentException ignored) { return false; }
    }

    private boolean validValue(JsonNode value, String kind) {
        if (value == null || value.isMissingNode() || value.isNull()) return false;
        if ("NUMERIC".equals(kind)) {
            if (!value.isNumber() && !value.isTextual()) return false;
            if (value.isTextual() && !value.asText().strip().matches("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")) return false;
            try { return !value.asText().isBlank() && Double.isFinite(Double.parseDouble(value.asText().strip())); }
            catch (NumberFormatException ignored) { return false; }
        }
        return (value.isTextual() || value.isNumber()) && !value.asText().isBlank();
    }

    private JsonNode normalizedValue(JsonNode value, String kind) {
        return "NUMERIC".equals(kind) ? json.getNodeFactory().numberNode(Double.parseDouble(value.asText().strip()))
                : json.getNodeFactory().textNode(value.asText().strip());
    }

    private JsonNode accepted(UUID id) {
        var result = json.createObjectNode().put("runId", id.toString()).put("status", "QUEUED").put("pollAfterMs", 1200);
        return result;
    }

    private JsonNode runView(UUID organizationId, UUID userId, UUID id) {
        var values = userId == null
                ? jdbc.query("SELECT * FROM ai.modeling_workbench_run WHERE organization_id=? AND id=?", this::runRow, organizationId, id)
                : jdbc.query("SELECT * FROM ai.modeling_workbench_run WHERE organization_id=? AND created_by=? AND id=?", this::runRow, organizationId, userId, id);
        var row = values.stream().findFirst().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "建模任务不存在或无权查看"));
        row = syncTerminalJobFailure(organizationId, row);
        var result = json.createObjectNode().put("id", row.id().toString()).put("datasetName", row.datasetName())
                .put("status", row.status()).put("progress", row.progress()).put("currentStage", row.stage())
                .put("rowCount", row.request().path("rowCount").asInt())
                .put("selectedRowCount", row.request().path("selectedRowCount").asInt(row.request().path("rowCount").asInt()))
                .put("createdAt", row.createdAt().toString()).put("updatedAt", row.updatedAt().toString());
        result.set("xColumns", row.xColumns());
        result.set("yColumn", row.yColumn());
        result.set("result", row.result());
        result.set("datasetSnapshot",row.request().path("datasetSnapshot"));
        result.set("selectedRowIdsByDataset",row.request().path("selectedRowIdsByDataset"));
        result.set("error", row.error().isEmpty() ? json.nullNode() : row.error());
        return result;
    }

    private WorkbenchRun runRow(ResultSet rs, int n) throws SQLException {
        return new WorkbenchRun(rs.getObject("id", UUID.class), rs.getString("dataset_name"), rs.getString("status"),
                rs.getInt("progress"), rs.getString("current_stage"), read(rs, "request_jsonb"),
                read(rs, "selected_x_jsonb"), read(rs, "selected_y_jsonb"), read(rs, "result_jsonb"),
                read(rs, "error_jsonb"), rs.getObject("ops_job_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private WorkbenchRun syncTerminalJobFailure(UUID organizationId, WorkbenchRun row) {
        if (!"QUEUED".equals(row.status()) && !"RUNNING".equals(row.status())) return row;
        if (row.opsJobId() == null) return row;
        var jobStatuses = jdbc.query("SELECT status FROM ops.async_job WHERE organization_id=? AND id=?",
                (rs, n) -> rs.getString(1), organizationId, row.opsJobId());
        var jobStatus = jobStatuses.stream().findFirst().orElse(null);
        if (!"FAILED".equals(jobStatus) && !"CANCELLED".equals(jobStatus)) return row;
        var message = "CANCELLED".equals(jobStatus)
                ? "训练已取消。"
                : "训练没有完成。请检查 X/Y 选择和实验记录后重试。";
        var error = json.createObjectNode().put("code", "CANCELLED".equals(jobStatus) ? "JOB_CANCELLED" : "TRAINING_FAILED")
                .put("message", message);
        jdbc.update("""
                UPDATE ai.modeling_workbench_run
                SET status=?,progress=100,current_stage=?,error_jsonb=?::jsonb,
                    finished_at=COALESCE(finished_at,now()),updated_at=now()
                WHERE organization_id=? AND id=? AND status IN ('QUEUED','RUNNING')
                """, jobStatus, jobStatus, error.toString(), organizationId, row.id());
        return jdbc.query("SELECT * FROM ai.modeling_workbench_run WHERE organization_id=? AND id=?",
                this::runRow, organizationId, row.id()).stream().findFirst().orElse(row);
    }

    private JsonNode read(ResultSet rs, String column) throws SQLException {
        try { return json.readTree(rs.getString(column)); }
        catch (Exception exception) { throw new SQLException("建模任务记录无法读取", exception); }
    }

    private PGobject pg(JsonNode node) {
        try { var value = new PGobject(); value.setType("jsonb"); value.setValue(node == null ? "{}" : node.toString()); return value; }
        catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }

    private ApiException validation(String message) { return new ApiException(ApiErrorCode.VALIDATION_ERROR, message); }

    public record TrainCommand(UUID datasetId, List<UUID> datasetIds, List<String> xColumns, String yColumn,
                               Map<String, List<String>> selectedRowIdsByDataset) {}
    private record WorkbenchRun(UUID id, String datasetName, String status, int progress, String stage,
                                JsonNode request, JsonNode xColumns, JsonNode yColumn, JsonNode result,
                                JsonNode error, UUID opsJobId, Instant createdAt, Instant updatedAt) {}
}
