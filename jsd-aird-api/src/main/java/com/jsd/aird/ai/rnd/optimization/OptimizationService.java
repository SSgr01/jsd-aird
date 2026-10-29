package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ai.rnd.compute.ModelComputeClient;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.ops.application.port.OpsAsyncFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.optimization.OptimizationContracts.*;

@Service
public class OptimizationService {
    private final OptimizationRepository repository;
    private final ModelComputeClient compute;
    private final ConfigurationHashing hashing;
    private final OpsAsyncFacade jobs;
    private final AuditLogFacade audit;
    private final AuthorizationService authorization;
    private final TemplateDataImportFacade templates;
    private final ObjectMapper json;
    private final boolean inlineWorker;

    public OptimizationService(OptimizationRepository repository, ModelComputeClient compute,
                                ConfigurationHashing hashing, OpsAsyncFacade jobs, AuditLogFacade audit,
                                AuthorizationService authorization,
                                TemplateDataImportFacade templates, ObjectMapper json,
                                @Value("${JSD_AIRD_R09_INLINE_ACCEPTANCE_WORKER:false}") boolean inlineWorker) {
        this.repository=repository;this.compute=compute;
        this.hashing=hashing;this.jobs=jobs;this.audit=audit;this.authorization=authorization;
        this.templates=templates;
        this.json=json;this.inlineWorker=inlineWorker;
    }

    public JsonNode historyDatasets(String keyword) {
        var a=actor();require(a,"ai.experiment.optimize","AI_EXPERIMENT_OPTIMIZATION",null,"READ");
        require(a,"experiment.view","EXPERIMENT",null,"READ");
        return historyDatasetList(a.organizationId(),keyword);
    }

    public JsonNode modelingHistoryDatasets(String keyword) {
        var a=actor();require(a,"ai.modeling.read","AI_MODELING",null,"READ");
        require(a,"experiment.view","EXPERIMENT",null,"READ");
        return historyDatasetList(a.organizationId(),keyword);
    }

    private JsonNode historyDatasetList(UUID organizationId,String keyword) {
        var root=json.createObjectNode();var items=root.putArray("items");
        repository.historyDatasets(organizationId,keyword).forEach(row->items.add(json.createObjectNode()
                .put("id",row.id().toString()).put("name",row.name()).put("rowCount",row.rowCount())
                .put("updatedAt",row.updatedAt().toString())));
        return root;
    }

    public JsonNode historyDataset(UUID templateVersionId) {
        var a=actor();require(a,"ai.experiment.optimize","AI_EXPERIMENT_OPTIMIZATION",templateVersionId,"READ");
        require(a,"experiment.view","EXPERIMENT",null,"READ");
        return historyDatasetData(a.organizationId(),templateVersionId);
    }

    public JsonNode modelingHistoryDataset(UUID templateVersionId) {
        var a=actor();require(a,"ai.modeling.read","AI_MODELING",templateVersionId,"READ");
        require(a,"experiment.view","EXPERIMENT",null,"READ");
        return historyDatasetData(a.organizationId(),templateVersionId);
    }

    public JsonNode modelingHistoryDatasetForTraining(UUID templateVersionId) {
        var a=actor();require(a,"ai.training.operate","AI_TRAINING",templateVersionId,"OPERATE");
        require(a,"experiment.view","EXPERIMENT",null,"READ");
        return historyDatasetData(a.organizationId(),templateVersionId);
    }

    public JsonNode modelingHistoryDatasetSetForTraining(List<UUID> templateVersionIds) {
        if (templateVersionIds == null || templateVersionIds.isEmpty()) throw validation("请选择至少一张实验数据表");
        if (templateVersionIds.size() > 20) throw validation("一次最多合并 20 张实验数据表");
        var ids = new LinkedHashSet<>(templateVersionIds);
        if (ids.size() != templateVersionIds.size() || ids.contains(null)) throw validation("数据表不能重复选择");
        var sources = ids.stream().map(this::modelingHistoryDatasetForTraining).toList();
        return mergeHistoryDatasets(sources);
    }

    private JsonNode mergeHistoryDatasets(List<JsonNode> sources) {
        if (sources.size() == 1) {
            var only = (ObjectNode) sources.getFirst().deepCopy();
            var sourceId = only.path("id").asText();
            var sourceName = only.path("name").asText("实验数据");
            only.set("sourceDatasetIds", json.valueToTree(List.of(sourceId)));
            only.path("rows").forEach(row -> {
                if (row instanceof ObjectNode object) object.put("sourceDatasetId", sourceId).put("sourceDatasetName", sourceName);
            });
            return only;
        }

        var first = sources.getFirst();
        var common = commonHistoryColumns(sources);

        var root = json.createObjectNode().put("id", first.path("id").asText());
        var names = new ArrayList<String>();
        var ids = json.createArrayNode();
        var rows = root.putArray("rows");
        int rowCount = 0;
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            var source = sources.get(sourceIndex);
            var sourceId = source.path("id").asText();
            var sourceName = source.path("name").asText("实验数据");
            names.add(sourceName);
            ids.add(sourceId);
            for (var row : source.path("rows")) {
                var merged = (ObjectNode) row.deepCopy(); rows.add(merged);
                merged.put("sourceDatasetId",sourceId).put("sourceDatasetName",sourceName);
                var values = merged.putObject("values");
                for (var mapping : common.sourceCodes().get(sourceIndex).entrySet()) {
                    var value = row.path("values").path(mapping.getValue());
                    if (!value.isMissingNode()) values.set(mapping.getKey(), value.deepCopy());
                }
                for(var metadata:List.of("sourceRefs","cellStates")) {
                    var mapped=merged.putObject(metadata);
                    for(var mapping:common.sourceCodes().get(sourceIndex).entrySet())if(row.path(metadata).has(mapping.getValue()))
                        mapped.set(mapping.getKey(),row.path(metadata).get(mapping.getValue()).deepCopy());
                }
                rowCount++;
            }
        }
        root.put("name", String.join("、", names)).put("rowCount", rowCount).put("projectionVersion",HistorySampleProjection.VERSION);
        var issues=root.putArray("issues");
        for(int index=0;index<sources.size();index++)for(var issue:sources.get(index).path("issues")) {
            var copy=(ObjectNode)issue.deepCopy();
            for(var mapping:common.sourceCodes().get(index).entrySet())if(mapping.getValue().equals(issue.path("columnCode").asText()))copy.put("columnCode",mapping.getKey());
            issues.add(copy);
        }
        root.set("sourceDatasetIds", ids);
        var columns = root.putArray("columns");
        common.columns().forEach(column -> {
            var code = column.path("code").asText();
            var copy = (ObjectNode) column.deepCopy();
            int filled = 0;
            for (var row : rows) if (!row.path("values").path(code).isMissingNode()) filled++;
            copy.put("filledCount", filled);
            columns.add(copy);
        });
        return root;
    }

    private JsonNode historyDatasetData(UUID organizationId,UUID templateVersionId) {
        var dataset=repository.historyDatasets(organizationId,"").stream()
                .filter(item->item.id().equals(templateVersionId)).findFirst()
                .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"历史实验数据不存在或无权查看"));
        var records=repository.historyRecords(organizationId,templateVersionId);
        var fieldNames=repository.historyFieldNames(organizationId);
        return new HistorySampleProjection(json).dataset(dataset.id(),dataset.name(),records,fieldNames,repository.materialCatalog(organizationId));
    }

    private CommonHistoryColumns commonHistoryColumns(List<JsonNode> sources) {
        var firstColumns = new ArrayList<JsonNode>();
        sources.getFirst().path("columns").forEach(firstColumns::add);
        var commonColumns = new ArrayList<JsonNode>();
        var sourceCodes = new ArrayList<Map<String, String>>();
        for (int i = 0; i < sources.size(); i++) sourceCodes.add(new LinkedHashMap<>());

        for (var canonical : firstColumns) {
            var code = canonical.path("code").asText();
            var name = normalizeFieldName(canonical.path("name").asText());
            long firstNameCount = firstColumns.stream().filter(item -> name.equals(normalizeFieldName(item.path("name").asText()))
                    && compatibleHistoryColumn(canonical, item)).count();
            var matchedCodes = new ArrayList<String>();
            matchedCodes.add(code);
            boolean common = true;
            for (int sourceIndex = 1; sourceIndex < sources.size(); sourceIndex++) {
                var columns = new ArrayList<JsonNode>();
                sources.get(sourceIndex).path("columns").forEach(columns::add);
                var sameCode = columns.stream().filter(item -> code.equals(item.path("code").asText())
                        && compatibleHistoryColumn(canonical, item)).toList();
                JsonNode match = sameCode.size() == 1 ? sameCode.getFirst() : null;
                if (match == null && !name.isBlank() && firstNameCount == 1 && !unmappedMaterialRatio(canonical)) {
                    var sameName = columns.stream().filter(item -> name.equals(normalizeFieldName(item.path("name").asText()))
                            && compatibleHistoryColumn(canonical, item)).toList();
                    if (sameName.size() == 1) match = sameName.getFirst();
                }
                if (match == null) { common = false; break; }
                matchedCodes.add(match.path("code").asText());
            }
            if (!common) continue;
            commonColumns.add(canonical.deepCopy());
            for (int i = 0; i < matchedCodes.size(); i++) sourceCodes.get(i).put(code, matchedCodes.get(i));
        }
        return new CommonHistoryColumns(commonColumns, sourceCodes);
    }

    private boolean compatibleHistoryColumn(JsonNode left, JsonNode right) {
        return left.path("kind").asText().equals(right.path("kind").asText())
                && normalizeFieldUnit(left.path("unit").asText()).equals(normalizeFieldUnit(right.path("unit").asText()))
                && methods(left).equals(methods(right))
                && left.path("testStage").asText().equals(right.path("testStage").asText())
                && left.path("compositionBasis").asText().equals(right.path("compositionBasis").asText())
                && left.path("materialId").asText().equals(right.path("materialId").asText())
                && left.path("measurementComponent").asText().equals(right.path("measurementComponent").asText())
                && left.path("semanticType").asText().equals(right.path("semanticType").asText())
                && left.path("mappingStatus").asText().equals(right.path("mappingStatus").asText());
    }

    private boolean unmappedMaterialRatio(JsonNode column) {
        return Set.of("MATERIAL_RATIO","MATERIAL_AMOUNT").contains(column.path("semanticType").asText())
                && !"MAPPED".equals(column.path("mappingStatus").asText());
    }

    private List<String> methods(JsonNode column) {
        var values=new ArrayList<String>();column.path("testMethods").forEach(method -> values.add(normalizeFieldName(method.asText())));
        Collections.sort(values);return values;
    }

    private String normalizeFieldName(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String normalizeFieldUnit(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private boolean mappedFormulaColumn(JsonNode column,Set<String> knownMaterialIds) {
        if(!Set.of("MATERIAL_RATIO","MATERIAL_AMOUNT").contains(column.path("semanticType").asText())
                ||!"MAPPED".equals(column.path("mappingStatus").asText())
                ||!"NUMERIC".equals(column.path("kind").asText()))return false;
        var id=column.path("materialId").asText();
        try {
            if(!UUID.fromString(id).toString().equals(id)||!knownMaterialIds.contains(id))return false;
            var code=column.path("code").asText();var prefix="formula:"+id;
            return code.equals(prefix)||code.startsWith(prefix+"|");
        } catch(IllegalArgumentException ignored) { return false; }
    }

    private JsonNode mergedHistoryDataset(List<UUID> datasetIds) {
        return mergeHistoryDatasets(datasetIds.stream().map(this::historyDataset).toList());
    }

    private List<UUID> requestedHistoryDatasetIds(JsonNode body) {
        var ids = new ArrayList<UUID>();
        var requested = body.path("datasetIds");
        if (requested.isArray()) {
            for (var value : requested) try { ids.add(UUID.fromString(value.asText())); }
            catch (IllegalArgumentException error) { throw validation("所选数据表无效，请重新选择"); }
        }
        if (ids.isEmpty()) try { ids.add(UUID.fromString(body.path("datasetId").asText())); }
        catch (IllegalArgumentException error) { throw validation("请选择历史实验数据"); }
        if (ids.size() > 20) throw validation("一次最多合并 20 张实验数据表");
        if (new LinkedHashSet<>(ids).size() != ids.size()) throw validation("数据表不能重复选择");
        return List.copyOf(ids);
    }

    private List<JsonNode> selectedHistoryRows(JsonNode dataset, JsonNode body) {
        var available = new LinkedHashMap<String, Set<String>>();
        dataset.path("sourceDatasetIds").forEach(id -> available.put(id.asText(), new LinkedHashSet<>()));
        if (available.isEmpty()) available.put(dataset.path("id").asText(), new LinkedHashSet<>());
        dataset.path("rows").forEach(row -> {
            var sourceId = row.path("sourceDatasetId").asText(dataset.path("id").asText());
            if (available.containsKey(sourceId)) available.get(sourceId).add(row.path("id").asText());
        });
        var requested = body.path("selectedRowIdsByDataset");
        if (requested.isMissingNode() || requested.isNull()) return streamRows(dataset.path("rows"));
        if (!requested.isObject()) throw validation("请重新勾选要使用的实验记录");
        requested.fieldNames().forEachRemaining(key -> {
            if (!available.containsKey(key)) throw validation("勾选记录和数据表不匹配，请重新选择");
        });
        var selected = new LinkedHashMap<String, Set<String>>();
        for (var entry : available.entrySet()) {
            var values = requested.path(entry.getKey());
            if (!values.isArray()) throw validation("请确认每张数据表中要使用的记录");
            var ids = new LinkedHashSet<String>();
            values.forEach(value -> ids.add(value.asText()));
            if (ids.size() != values.size()) throw validation("同一条实验记录不能重复选择");
            if (!entry.getValue().containsAll(ids)) throw validation("勾选的实验记录已变化，请刷新数据后重试");
            selected.put(entry.getKey(), ids);
        }
        var result = new ArrayList<JsonNode>();
        dataset.path("rows").forEach(row -> {
            var sourceId = row.path("sourceDatasetId").asText(dataset.path("id").asText());
            if (selected.getOrDefault(sourceId, Set.of()).contains(row.path("id").asText())) result.add(row);
        });
        return result;
    }

    private List<JsonNode> streamRows(JsonNode rows) {
        var result = new ArrayList<JsonNode>(); rows.forEach(result::add); return result;
    }

    @Transactional
    public OptimizationAccepted submit(JsonNode body,String idempotencyKey){
        var a=actor();require(a,"ai.experiment.optimize","AI_EXPERIMENT_OPTIMIZATION",null,"CREATE");
        if(idempotencyKey==null||idempotencyKey.isBlank())throw validation("必须提供幂等键");
        if(body==null||!body.isObject()||!"HISTORICAL_GP_BO".equals(body.path("mode").asText()))throw validation("请先选择实验数据和 X/Y");
        return submitHistorical(body,idempotencyKey,a,List.of(),null,hashing.hash(hashing.canonical(body)));
    }
    private OptimizationAccepted submitHistorical(JsonNode body,String idempotencyKey,Actor actor,List<JsonNode> supplementalRows,OptimizationRepository.RunRow previous,String submissionFingerprint){
        var key=idempotencyKey.strip();
        repository.jdbc().queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) AS optimization_lock", Integer.class,
                actor.organizationId()+":"+actor.userId()+":optimization:"+key);
        var accepted=repository.byKey(actor.organizationId(),actor.userId(),key).orElse(null);
        if(accepted!=null){
            if(!submissionFingerprint.equals(accepted.request().path("submissionFingerprint").asText()))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            return accepted(accepted);
        }
        require(actor,"experiment.view","EXPERIMENT",null,"READ");
        var datasetIds=requestedHistoryDatasetIds(body);var datasetId=datasetIds.getFirst();
        boolean useFrozenRows=previous!=null;
        var dataset=useFrozenRows?previous.request().path("datasetSnapshot"):mergedHistoryDataset(datasetIds);
        if(!dataset.path("rows").isArray())throw validation("本任务没有保存样品数据，请重新选择数据开始优化");
        var selectedRows=selectedHistoryRows(dataset,body);
        if(selectedRows.isEmpty())throw validation("请至少勾选一条实验记录");
        var selectedIdsByDataset=json.createObjectNode();
        for(var sourceId:datasetIds){var ids=selectedIdsByDataset.putArray(sourceId.toString());for(var row:selectedRows)if(sourceId.toString().equals(row.path("sourceDatasetId").asText(datasetId.toString())))ids.add(row.path("id").asText());}
        var columns=new LinkedHashMap<String,JsonNode>();dataset.path("columns").forEach(column->columns.put(column.path("code").asText(),column));
        var knownMaterialIds=repository.materialCatalog(actor.organizationId()).validIds();
        var ratioColumns=columns.values().stream().filter(column->"MATERIAL_RATIO".equals(column.path("semanticType").asText())).toList();
        var materialColumns=columns.values().stream().filter(column->Set.of("MATERIAL_RATIO","MATERIAL_AMOUNT").contains(column.path("semanticType").asText())).toList();
        var formulaCodes=columns.values().stream().filter(column->mappedFormulaColumn(column,knownMaterialIds)).map(column->column.path("code").asText()).toList();
        var requestedX=body.path("xColumns").deepCopy();var targetCode=body.path("yColumn").asText();
        if(requestedX.isArray()&&java.util.stream.StreamSupport.stream(requestedX.spliterator(),false).anyMatch(x->formulaCodes.contains(x.asText()))) {
            var requestedCodes=new LinkedHashSet<String>();requestedX.forEach(x->requestedCodes.add(x.asText()));
            for(var column:columns.values())if(mappedFormulaColumn(column,knownMaterialIds)&&!requestedCodes.contains(column.path("code").asText())) {
                var observed=new LinkedHashSet<String>();int filled=0;
                for(var row:selectedRows){var v=row.path("values").path(column.path("code").asText());if(v.isNumber()){observed.add(v.asText());filled++;}}
                if(observed.size()==1&&filled>=4)((ArrayNode)requestedX).add(column.path("code").asText());
            }
        }
        if(!requestedX.isArray()||requestedX.isEmpty())throw validation("至少选择一个 X 因素");
        for(var selected:requestedX) {
            var column=columns.get(selected.asText());
            if(column!=null&&Set.of("MATERIAL_RATIO","MATERIAL_AMOUNT").contains(column.path("semanticType").asText())
                    &&!mappedFormulaColumn(column,knownMaterialIds))
                throw validation("材料“"+column.path("name").asText(selected.asText())+"”的身份尚未确认，请补充材料编号后再优化");
        }
        boolean formulaSelected=false;
        for(var selected:requestedX)if(formulaCodes.contains(selected.asText())){formulaSelected=true;break;}
        if(formulaSelected&&materialColumns.stream().anyMatch(column->!mappedFormulaColumn(column,knownMaterialIds)))
            throw validation("配方中有材料身份尚未确认，请补充材料编号后再优化");
        if(!columns.containsKey(targetCode)||!"NUMERIC".equals(columns.get(targetCode).path("kind").asText()))throw validation("Y 指标需要是有数值记录的实验结果列");
        var featureNodes=json.createArrayNode();var featureCodes=new LinkedHashSet<String>();
        for(var selected:requestedX){
            var code=selected.asText();if(code.isBlank()||code.equals(targetCode)||!featureCodes.add(code))throw validation("X 因素不能重复，也不能与 Y 指标相同");
            var column=columns.get(code);if(column==null)throw validation("所选 X 因素已不在这份数据中，请刷新数据");
            var feature=featureNodes.addObject().put("code",code).put("name",column.path("name").asText(code)).put("kind",column.path("kind").asText("CATEGORICAL"));
            if("NUMERIC".equals(column.path("kind").asText())){
                var observed=new ArrayList<Double>();selectedRows.forEach(row->{var value=row.path("values").path(code);if(value.isNumber())observed.add(value.asDouble());});
                if(observed.size()<4)throw validation("X 因素“"+feature.path("name").asText()+"”有效记录不足4条");
                double defaultMin=observed.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
                double defaultMax=observed.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
                var supplied=body.path("bounds").path(code);double min=supplied.path("minimum").asDouble(defaultMin);double max=supplied.path("maximum").asDouble(defaultMax);
                if(!Double.isFinite(min)||!Double.isFinite(max)||min>max)throw validation("X 因素“"+feature.path("name").asText()+"”的最小值不能大于最大值");
                if(defaultMin==defaultMax&&(min!=defaultMin||max!=defaultMax))throw validation("X 因素“"+feature.path("name").asText()+"”历史值固定，本轮请保持原值");
                if(formulaCodes.contains(code)&&min<0)throw validation("材料比例不能为负数："+feature.path("name").asText());
                feature.put("minimum",min).put("maximum",max);
                if(min==max)feature.put("fixedValue",min);
            }
        }
        var selectedFormulaCodes=featureCodes.stream().filter(formulaCodes::contains).toList();
        for(var code:selectedFormulaCodes) {
            var column=columns.get(code);var unit=column.path("unit").asText();
            if("MATERIAL_AMOUNT".equals(column.path("semanticType").asText())
                    && "MASS_AMOUNT".equals(column.path("compositionBasis").asText()) && "g".equalsIgnoreCase(unit)) continue;
            if(!"MASS_PERCENT".equals(column.path("compositionBasis").asText())||!Set.of("","%","PERCENT").contains(unit))
                throw validation("材料“"+column.path("name").asText()+"”的计量基准不是质量百分比，当前材料优化只支持质量百分比；请先确认原始记录的单位和计量基准");
        }
        if(!selectedFormulaCodes.isEmpty()&&!featureCodes.containsAll(formulaCodes))
            throw validation("配方还缺少材料："+formulaCodes.stream().filter(code->!featureCodes.contains(code)).map(code->columns.get(code).path("name").asText(code)).collect(java.util.stream.Collectors.joining("、")));
        var compositionGroups=json.createArrayNode();
        if(!ratioColumns.isEmpty() && formulaCodes.size()>=2&&featureCodes.containsAll(formulaCodes)){
            for(var row:selectedRows){
                // Incomplete X/Y rows are excluded below, as shown in the selection preview.
                if(featureCodes.stream().anyMatch(code -> !row.path("values").hasNonNull(code)) || !row.path("values").path(targetCode).isNumber())continue;
                var sum=java.math.BigDecimal.ZERO;boolean complete=true;
                for(var code:formulaCodes){var v=row.path("values").path(code);if(!v.isNumber()||v.asDouble()<0){complete=false;break;}sum=sum.add(v.decimalValue());}
                if(!complete||sum.subtract(new java.math.BigDecimal("100")).abs().compareTo(new java.math.BigDecimal("0.001"))>0)
                    throw validation("样品“"+row.path("label").asText()+"”的配方比例缺失或合计不是100%，请检查原始记录");
            }
            double groupTotal=100;
            boolean materialFixed=true;double fixedTotal=0;
            for(var feature:featureNodes)if(formulaCodes.contains(feature.path("code").asText())) {
                if(feature.path("minimum").asDouble()!=feature.path("maximum").asDouble())materialFixed=false;
                fixedTotal+=feature.path("minimum").asDouble();
            }
            if(materialFixed)groupTotal=fixedTotal;
            var group=compositionGroups.addObject().put("total",groupTotal);group.set("codes",json.valueToTree(formulaCodes));
        }

        boolean adjustable=false;
        for(var feature:featureNodes) {
            var code=feature.path("code").asText();
            if(!"NUMERIC".equals(feature.path("kind").asText())) {
                var distinct=new HashSet<String>();selectedRows.forEach(row -> { var value=row.path("values").path(code);if(!value.isMissingNode()&&!value.isNull())distinct.add(value.asText()); });
                if(distinct.size()>1)adjustable=true;
            } else if((!selectedFormulaCodes.contains(code) || "MATERIAL_AMOUNT".equals(columns.get(code).path("semanticType").asText()))
                    && feature.path("minimum").asDouble()<feature.path("maximum").asDouble())adjustable=true;
        }
        for(var group:compositionGroups) {
            double minimum=0,maximum=0;
            for(var feature:featureNodes)if(formulaCodes.contains(feature.path("code").asText())) { minimum+=feature.path("minimum").asDouble();maximum+=feature.path("maximum").asDouble(); }
            var total=group.path("total").asDouble();
            if(minimum>total+1e-8||maximum<total-1e-8)throw validation("材料比例上下限无法合计为100%，请调整对应材料的范围或固定值");
            for(var feature:featureNodes)if(formulaCodes.contains(feature.path("code").asText())) {
                var low=feature.path("minimum").asDouble();var high=feature.path("maximum").asDouble();
                if(Math.min(high,total-minimum+low)-Math.max(low,total-maximum+high)>1e-9)adjustable=true;
            }
        }
        if(!adjustable)throw validation("当前范围与配方总量约束没有留下可调整空间，请调整因素范围");

        var rows=json.createArrayNode();
        if(useFrozenRows){
            supplementalRows.forEach(row->appendHistoryRow(rows,row,targetCode,featureNodes));
        }else for(var source:selectedRows){
            var value=source.path("values");var y=value.path(targetCode);if(!y.isNumber())continue;
            var point=json.createObjectNode();value.fields().forEachRemaining(entry->point.set(entry.getKey(),entry.getValue().deepCopy()));
            appendHistoryRow(rows,point,targetCode,featureNodes);
        }
        if(!useFrozenRows)for(var supplemental:supplementalRows)appendHistoryRow(rows,supplemental,targetCode,featureNodes);
        if(rows.size()<4)throw validation("所选 X/Y 组合只有"+rows.size()+"条完整记录，至少需要4条");
        var outcomes=new HashSet<Double>();rows.forEach(row -> outcomes.add(row.path(targetCode).asDouble()));
        if(outcomes.size()<2)throw validation("所选记录的Y结果未变化，无法学习改进方向，请补充不同结果的记录");
        var direction=body.path("direction").asText("MAXIMIZE");if(!List.of("MAXIMIZE","MINIMIZE").contains(direction))throw validation("请选择提高或降低的目标方向");
        OptimizationRepository.BaselineRow anchor;
        if(useFrozenRows)anchor=new OptimizationRepository.BaselineRow(previous.baselineType(),previous.baselineEntityId(),previous.baselineVersionId(),
                previous.baseline().path("title").asText(),"历史实验数据",json.createObjectNode(),datasetId,
                previous.baseline().path("templateSnapshotHash").asText(),previous.baseline().path("templateSnapshot"),previous.updatedAt());
        else {
            var sample=selectedRows.stream().filter(row -> row.path("values").path(targetCode).isNumber() && featureCodes.stream().allMatch(code -> row.path("values").hasNonNull(code))).findFirst().orElseThrow();
            anchor=repository.historyBaseline(actor.organizationId(),UUID.fromString(sample.path("versionId").asText()))
                    .orElseThrow(()->new ApiException(ApiErrorCode.BASELINE_NOT_CURRENT));
        }
        var frozen=json.createObjectNode().put("type","HISTORICAL_DATASET").put("entityId",anchor.entityId().toString())
                .put("versionId",anchor.versionId().toString()).put("title",dataset.path("name").asText("历史实验数据"))
                .put("datasetId",datasetId.toString()).put("templateVersionId",useFrozenRows?previous.baseline().path("templateVersionId").asText(null):anchor.templateVersionId()==null?null:anchor.templateVersionId().toString())
                .put("rowCount",selectedRows.size()).put("sourceDatasetCount",datasetIds.size()).put("contentHash",hashing.hash(dataset));
        frozen.put("templateSnapshotHash",anchor.templateSnapshotHash()==null?"":anchor.templateSnapshotHash());
        frozen.set("templateSnapshot",anchor.templateSnapshot());
        var sourceWorkbook=useFrozenRows?previous.baseline().path("sourceWorkbookSnapshot"):anchor.payload().path("documentSnapshot");
        if(sourceWorkbook.isObject())frozen.set("sourceWorkbookSnapshot",sourceWorkbook.deepCopy());
        var request=json.createObjectNode().put("mode","HISTORICAL_GP_BO").put("datasetId",datasetId.toString())
                .put("datasetName",dataset.path("name").asText()).put("target",targetCode)
                .put("targetName",columns.get(targetCode).path("name").asText(targetCode)).put("direction",direction)
                .put("candidateCount",4).put("selectedRowCount",selectedRows.size());
        request.set("datasetIds",json.valueToTree(datasetIds));request.set("selectedRowIdsByDataset",selectedIdsByDataset);
        request.put("submissionFingerprint",submissionFingerprint);
        if (isNonNegativeCountUnit(targetUnit(dataset.path("columns"), targetCode)))
            request.putObject("resultConstraints").put("nonNegative", true);
        request.set("datasetSnapshot",dataset.deepCopy());request.put("projectionVersion",HistorySampleProjection.VERSION);
        request.set("features",featureNodes);request.set("rows",rows);request.set("columns",dataset.path("columns").deepCopy());
        if(!compositionGroups.isEmpty())request.set("compositionGroups",compositionGroups);
        var normalized=hashing.canonical(request);var requestHash=hashing.hash(normalized);var requestId=RequestIdHolder.currentOrUnknown();var seed=seed(requestHash);var runId=UUID.randomUUID();
        var config=json.createObjectNode().put("algorithm","GAUSSIAN_PROCESS_EI").put("candidateCount",4);
        var optimization=json.createObjectNode().put("datasetId",datasetId.toString()).put("sourceDatasetCount",datasetIds.size())
                .put("selectedRows",selectedRows.size()).put("trainingRows",rows.size()).put("target",targetCode);
        boolean inserted;
        try{inserted=repository.insertRun(runId,actor.organizationId(),actor.userId(),idempotencyKey.strip(),requestId,requestHash,normalized,json.createObjectNode(),seed,config,anchor,hashing.hash(frozen),frozen,optimization);}
        catch(DataIntegrityViolationException e){throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"当前组织已有实验优化任务执行中",e);}
        if(!inserted){var old=repository.byKey(actor.organizationId(),actor.userId(),idempotencyKey).orElseThrow();if(!submissionFingerprint.equals(old.request().path("submissionFingerprint").asText()))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);return accepted(old);}
        var payload=json.createObjectNode().put("organizationId",actor.organizationId().toString()).put("actorId",actor.userId().toString()).put("runId",runId.toString()).put("requestId",requestId);
        var jobId=jobs.enqueue(actor.organizationId(),"AI_EXPERIMENT_OPTIMIZATION_V2",payload,"ai-experiment-optimization:"+runId,45,3);repository.attachJob(actor.organizationId(),runId,jobId);
        audit.append(actor.organizationId(),actor.userId(),"AI_EXPERIMENT_OPTIMIZATION_STARTED","AI_RESEARCH_RUN",runId,normalized);
        if(inlineWorker)Thread.startVirtualThread(()->{try{execute(actor.organizationId(),runId);}catch(Exception ignored){}});
        return new OptimizationAccepted(runId,jobId,"QUEUED",Map.of(),1000);
    }

    private void appendHistoryRow(ArrayNode rows,JsonNode source,String targetCode,ArrayNode features){
        var y=source.path(targetCode);if(!y.isNumber()||!Double.isFinite(y.asDouble()))return;
        var point=rows.addObject().put(targetCode,y.asDouble());boolean complete=true;
        for(var feature:features){var code=feature.path("code").asText();var x=source.path(code);
            if(x.isMissingNode()||x.isNull()||x.isObject()||x.isArray()||x.isTextual()&&x.asText().isBlank()||("NUMERIC".equals(feature.path("kind").asText())&&(!x.isNumber()||!Double.isFinite(x.asDouble())))){complete=false;break;}
            point.set(code,x.deepCopy());
        }
        if(!complete)rows.remove(rows.size()-1);
    }

    @Transactional
    public OptimizationAccepted continueHistorical(UUID previousRunId,String idempotencyKey){
        var actor=actor();require(actor,"ai.experiment.optimize","AI_RESEARCH_RUN",previousRunId,"UPDATE");
        if(idempotencyKey==null||idempotencyKey.isBlank())throw validation("必须提供幂等键");
        var key=idempotencyKey.strip();
        repository.jdbc().queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) AS optimization_lock", Integer.class,
                actor.organizationId()+":"+actor.userId()+":optimization:"+key);
        var fingerprint=hashing.hash(json.createObjectNode().put("operation","NEXT_ROUND").put("parentRunId",previousRunId.toString()));
        var accepted=repository.byKey(actor.organizationId(),actor.userId(),key).orElse(null);
        if(accepted!=null){
            if(!fingerprint.equals(accepted.request().path("submissionFingerprint").asText()))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            return accepted(accepted);
        }
        var previous=repository.byId(actor.organizationId(),actor.userId(),previousRunId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
        if(!"HISTORICAL_GP_BO".equals(previous.request().path("mode").asText()))throw validation("只有历史数据优化任务可以继续下一轮");
        if(!"SUCCEEDED".equals(previous.executionStatus()))throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"请等待本轮候选方案生成完成");
        var supplemental=new ArrayList<JsonNode>();
        previous.request().path("rows").forEach(row->supplemental.add(row.deepCopy()));
        supplemental.addAll(repository.jdbc().query("""
            SELECT c.fixed_inputs_jsonb,o.observed_value_jsonb
            FROM ai.research_candidate_history_observation_v2 o
            JOIN ai.research_candidate_v2 c ON c.organization_id=o.organization_id
              AND c.research_run_id=o.research_run_id AND c.id=o.research_candidate_id
            WHERE o.organization_id=? AND o.research_run_id=? AND o.field_code=?
            ORDER BY c.candidate_no
            """,(rs,n)->{
                var row=json.createObjectNode();var inputs=readJson(rs.getString("fixed_inputs_jsonb"));
                if(inputs.isObject())row.setAll((ObjectNode)inputs);
                row.set(previous.request().path("target").asText(),readJson(rs.getString("observed_value_jsonb")));return (JsonNode)row;
            },actor.organizationId(),previousRunId,previous.request().path("target").asText()));
        if(supplemental.size()==previous.request().path("rows").size())throw validation("先填写并保存至少一个方案的 Y 实测值，再开始下一轮");
        var body=json.createObjectNode().put("mode","HISTORICAL_GP_BO").put("datasetId",previous.request().path("datasetId").asText())
                .put("yColumn",previous.request().path("target").asText()).put("direction",previous.request().path("direction").asText())
                .put("candidateCount",previous.request().path("candidateCount").asInt(4));
        if(previous.request().path("datasetIds").isArray())body.set("datasetIds",previous.request().path("datasetIds").deepCopy());
        if(previous.request().path("selectedRowIdsByDataset").isObject())body.set("selectedRowIdsByDataset",previous.request().path("selectedRowIdsByDataset").deepCopy());
        var x=body.putArray("xColumns");var bounds=body.putObject("bounds");
        for(var feature:previous.request().path("features")){
            x.add(feature.path("code").asText());if(feature.path("kind").asText().equals("NUMERIC")){
                bounds.putObject(feature.path("code").asText()).put("minimum",feature.path("minimum").asDouble()).put("maximum",feature.path("maximum").asDouble());
            }
        }
        return submitHistorical(body,idempotencyKey,actor,supplemental,previous,fingerprint);
    }

    public OptimizationRun get(UUID runId){var a=actor();require(a,"ai.experiment.optimize","AI_RESEARCH_RUN",runId,"READ");return view(a.organizationId(),repository.byId(a.organizationId(),a.userId(),runId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"实验优化运行不存在")));}

    public JsonNode execute(UUID org,UUID runId){
        var run=repository.any(org,runId).orElseThrow();if(!repository.markRunning(org,runId))return run.result();
        try{return executeHistorical(org,run);}
        catch(Exception e){repository.fail(org,runId,e instanceof ApiException a?a.errorCode().code():"COMPUTE_UNAVAILABLE",e.getMessage());throw e instanceof RuntimeException r?r:new IllegalStateException(e);}
    }
    private JsonNode executeHistorical(UUID org,OptimizationRepository.RunRow run){
        repository.progress(org,run.id(),20,"FITTING_GAUSSIAN_PROCESS");
        var payload=(ObjectNode)run.request().deepCopy();payload.remove("submissionFingerprint");payload.put("seed",run.seed());
        var response=compute.recommendHistoricalOptimization(payload);repository.progress(org,run.id(),78,"SELECTING_EXPERIMENTS");
        var saved=0;var targetCode=run.request().path("target").asText();var targetName=run.request().path("targetName").asText(targetCode);
        for(var proposal:response.path("candidates")){
            if(!proposal.isObject())continue;
            var values=proposal.path("values");if(!values.isObject()||values.isEmpty())continue;
            var no=++saved;var formula=json.createObjectNode().put("type","HISTORICAL_XY");formula.set("values",values.deepCopy());
            var result=json.createObjectNode();var target=result.putArray("targets").addObject().put("targetId",targetCode).put("targetName",targetName).put("status","SUCCEEDED");
            var predicted=proposal.path("predictedValue").asDouble();var uncertainty=proposal.path("uncertainty").asDouble();
            target.set("result",json.createObjectNode().put("resultType","CONTINUOUS").put("value",predicted)
                    .put("lower",predicted-1.96*uncertainty).put("upper",predicted+1.96*uncertainty)
                    .put("unit",targetUnit(run.request().path("columns"),targetCode)));
            var quality=json.createObjectNode().put("expectedImprovement",proposal.path("expectedImprovement").asDouble());
            var evidence=json.createObjectNode().put("algorithm","Gaussian Process + Expected Improvement")
                    .put("trainingRowCount",response.path("searchEvidence").path("trainingRowCount").asInt());
            var hash=hashing.hash(values);
            repository.saveCandidate(org,run.id(),no,"HISTORICAL_BO","候选方案 "+no,formula,values,result,quality,
                    json.createObjectNode(),json.createObjectNode(),evidence,proposal.path("expectedImprovement").asDouble(),hash,
                    0,0,json.createObjectNode(),json.createObjectNode().put("expectedImprovement",proposal.path("expectedImprovement").asDouble()),json.createArrayNode());
        }
        var summary=json.createObjectNode().put("requestedCandidateCount",run.request().path("candidateCount").asInt(4))
                .put("actualCandidateCount",saved).put("simulation",false).put("searchEngine","GAUSSIAN_PROCESS_EI")
                .put("searchStrategy","EXPECTED_IMPROVEMENT");
        summary.set("searchEvidence",response.path("searchEvidence").deepCopy());
        summary.put("missingStrategies",0);
        var outcome=saved==0?"BLOCKED":saved<run.request().path("candidateCount").asInt(4)?"PARTIAL":"SUCCEEDED";
        repository.complete(org,run.id(),outcome,summary);return summary;
    }

    private String targetUnit(JsonNode columns,String code){if(columns.isArray())for(var column:columns)if(code.equals(column.path("code").asText()))return column.path("unit").asText("");return "";}
    private boolean isNonNegativeCountUnit(String raw){var unit=Normalizer.normalize(raw==null?"":raw,Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);return Set.of("次","cycle","cycles").contains(unit);}

    public void fail(UUID org,UUID run,String code,String message){repository.fail(org,run,code,message);}

    private OptimizationRun view(UUID org,OptimizationRepository.RunRow run){
        var candidates=repository.candidates(org,run.id()).stream().map(c->new OptimizationCandidate(c.id(),c.candidateNo(),c.strategy(),c.title(),c.formula(),c.inputs(),c.results(),c.quality(),c.applicability(),c.ruleCheck(),c.score(),c.targetTotal(),c.distance(),c.changes(),c.strategyEvidence(),c.risks())).toList();
        var result=run.result();return new OptimizationRun(run.id(),run.executionStatus(),run.outcomeStatus(),run.progress(),run.stage(),run.request().path("candidateCount").asInt(4),candidates.size(),List.of(),json.createObjectNode(),candidates,run.error(),run.baseline(),run.request(),Map.of(),run.requestId(),run.updatedAt(),result.path("searchEngine").asText(null),result.path("searchStrategy").asText(null),result.path("searchEvidence"),false,"FORMAL");
    }
    private OptimizationAccepted accepted(OptimizationRepository.RunRow run){var map=new LinkedHashMap<UUID,UUID>();run.bindings().fields().forEachRemaining(e->map.put(UUID.fromString(e.getKey()),UUID.fromString(e.getValue().asText())));var status=List.of("QUEUED","RUNNING").contains(run.executionStatus())?"RUNNING":run.executionStatus();return new OptimizationAccepted(run.id(),run.opsJobId(),status,map,1000);}


    private long seed(String hash){return ByteBuffer.wrap(java.util.HexFormat.of().parseHex(hash.substring(0,16))).getLong();}
    private void require(Actor a,String permission,String type,UUID id,String operation){authorization.require(new PermissionCheck(a.organizationId(),a.userId(),permission,type,id,operation));}
    private Actor actor(){return ActorContext.required();}
    private ApiException validation(String m){return new ApiException(ApiErrorCode.VALIDATION_ERROR,m);}
    private record CommonHistoryColumns(List<JsonNode> columns, List<Map<String, String>> sourceCodes) {}
    private JsonNode readJson(String raw){try{return raw==null?json.createObjectNode():json.readTree(raw);}catch(Exception error){throw new IllegalStateException("保存的优化实测值无法读取",error);}}
}
