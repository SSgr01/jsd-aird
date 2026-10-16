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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.optimization.OptimizationContracts.*;

@Service
public class OptimizationService {
    private static final Logger log = LoggerFactory.getLogger(OptimizationService.class);
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
                var merged = rows.addObject().put("id", row.path("id").asText())
                        .put("versionId", row.path("versionId").asText())
                        .put("label", row.path("label").asText())
                        .put("title", row.path("title").asText())
                        .put("sourceDatasetId", sourceId).put("sourceDatasetName", sourceName);
                var values = merged.putObject("values");
                for (var mapping : common.sourceCodes().get(sourceIndex).entrySet()) {
                    var value = row.path("values").path(mapping.getValue());
                    if (!value.isMissingNode()) values.set(mapping.getKey(), value.deepCopy());
                }
                rowCount++;
            }
        }
        root.put("name", String.join("、", names)).put("rowCount", rowCount);
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
        var columns=new LinkedHashMap<String,HistoryColumnValue>();var rows=json.createArrayNode();
        for(var record:records){
            var values=historyValues(record.editModel(),fieldNames);var row=rows.addObject().put("id",record.experimentId().toString())
                    .put("versionId",record.versionId().toString()).put("label",record.experimentNo()==null?record.title():record.experimentNo())
                    .put("title",record.title()).put("sourceDatasetId",dataset.id().toString()).put("sourceDatasetName",dataset.name());var valueNode=row.putObject("values");
            values.forEach((code,value)->{
                valueNode.set(code,value.value());
                var column=columns.computeIfAbsent(code,ignored->new HistoryColumnValue(code,value.name(),value.unit()));
                column.add(value.value());column.methods.addAll(value.testMethods());
            });
        }
        var root=json.createObjectNode().put("id",dataset.id().toString()).put("name",dataset.name()).put("rowCount",records.size());
        root.set("sourceDatasetIds",json.valueToTree(List.of(dataset.id().toString())));
        var columnNodes=root.putArray("columns");columns.values().forEach(value->columnNodes.add(value.toJson(json)));
        root.set("rows",rows);return root;
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
                if (match == null && !name.isBlank() && firstNameCount == 1) {
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
                && left.path("testMethods").equals(right.path("testMethods"));
    }

    private String normalizeFieldName(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String normalizeFieldUnit(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private JsonNode mergedHistoryDataset(List<UUID> datasetIds) {
        var sources = datasetIds.stream().map(this::historyDataset).toList();
        if (sources.size() == 1) return sources.getFirst();
        var first = sources.getFirst();
        var common = commonHistoryColumns(sources);
        var root = json.createObjectNode().put("id", first.path("id").asText());
        var names = new ArrayList<String>();
        var ids = root.putArray("sourceDatasetIds");
        var rows = root.putArray("rows");
        int rowCount = 0;
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            var source = sources.get(sourceIndex);
            var id = source.path("id").asText();
            var name = source.path("name").asText("历史实验数据");
            names.add(name); ids.add(id);
            for (var row : source.path("rows")) {
                var merged = rows.addObject().put("id", row.path("id").asText())
                        .put("versionId", row.path("versionId").asText())
                        .put("label", row.path("label").asText()).put("title", row.path("title").asText())
                        .put("sourceDatasetId", id).put("sourceDatasetName", name);
                var values = merged.putObject("values");
                for (var mapping : common.sourceCodes().get(sourceIndex).entrySet()) {
                    var value = row.path("values").path(mapping.getValue());
                    if (!value.isMissingNode()) values.set(mapping.getKey(), value.deepCopy());
                }
                rowCount++;
            }
        }
        root.put("name", String.join("、", names)).put("rowCount", rowCount);
        var columns = root.putArray("columns");
        common.columns().forEach(column -> {
            var code = column.path("code").asText();
            var copy = (ObjectNode) column.deepCopy(); int filled = 0;
            for (var row : rows) if (!row.path("values").path(code).isMissingNode()) filled++;
            copy.put("filledCount", filled); columns.add(copy);
        });
        return root;
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

    private LinkedHashMap<String,HistoryCell> historyValues(JsonNode editModel,Map<String,String> fieldNames) {
        var values=new LinkedHashMap<String,HistoryCell>();
        JsonNode dynamic=editModel.path("dynamicValues");
        if(dynamic.isObject())dynamic.fields().forEachRemaining(entry->{
            var name=historyFieldName(entry.getKey(),fieldNames);
            if(name!=null)historyCell(values,"input:"+entry.getKey(),name,"",entry.getValue());
        });
        JsonNode formula=editModel.path("formulaItems");
        if(formula.isArray())for(var item:formula){
            var identity=item.path("materialCode").asText(item.path("materialId").asText(item.path("code").asText(item.path("name").asText(""))));
            if(identity.isBlank())continue;
            var materialName=item.path("materialName").asText(item.path("name").asText(""));
            if(materialName.isBlank())materialName=repository.materialName(item.path("materialId").asText(identity)).orElse(identity);
            var name=materialName+"比例";
            historyCell(values,"formula:"+identity,name,item.path("unit").asText("%"),firstCell(item,"ratio","rawValue","value","amount"));
        }
        JsonNode process=editModel.path("processSteps");
        if(process.isArray())for(var step:process){
            // Imported observations are field/value records. Their source IDs
            // identify where a value came from and must never become X/Y columns.
            if(step.hasNonNull("field")&&step.has("value")){
                var identity=step.path("field").asText();
                var name=step.path("labelPath").asText(step.path("operation").asText(fieldNames.getOrDefault(identity,identity)));
                var recordKey=step.path("sourceRecordKey").asText("");
                var sourceIdentity=step.path("sourceIdentity").asText("");
                var sheetName=step.path("sourceRefs").path(0).path("sheetName").asText("");
                if(!sheetName.isBlank())name+=" · "+sheetName;
                if(!sourceIdentity.isBlank())name+=" · "+sourceIdentity;
                historyCell(values,"process:"+(recordKey.isBlank()?sourceIdentity:recordKey)+":"+identity,name,step.path("unit").asText(""),step.path("value"));
                continue;
            }
            var stepName=step.path("stepName").asText(step.path("name").asText("工序"));
            if(step.isObject())step.fields().forEachRemaining(entry->{
                if(entry.getKey().startsWith("source")||List.of("stepName","name","id","itemId","field","unit","operation","sampleKey","logicalSampleKey","labelPath","labelPathSegments","itemSourceKey").contains(entry.getKey()))return;
                historyCell(values,"process:"+stepName+":"+entry.getKey(),stepName+"·"+entry.getKey(),"",entry.getValue());
            });
        }
        JsonNode results=editModel.path("testResults");
        if(results.isArray())for(var item:results){
            var identity=item.path("targetCode").asText(item.path("code").asText(item.path("targetName").asText(item.path("testItem").asText(item.path("name").asText("")))));
            if(identity.isBlank())continue;
            var name=item.path("targetName").asText(item.path("testItem").asText(item.path("name").asText(identity)));
            var recordKey=item.path("sourceRecordKey").asText("");
            if(!recordKey.isBlank()){
                identity=recordKey+":"+identity;
                var sheetName=item.path("sourceRefs").path(0).path("sheetName").asText("");
                var sourceIdentity=item.path("sourceIdentity").asText("");
                if(!sheetName.isBlank())name+=" · "+sheetName;
                if(!sourceIdentity.isBlank())name+=" · "+sourceIdentity;
            }
            historyCell(values,"result:"+identity,name,item.path("unit").asText(""),firstCell(item,"value","rawValue","parsedValue","result"));
            var cell=values.get("result:"+identity);
            var method=item.path("testMethod").asText("").strip();
            if(cell!=null&&!method.isBlank())values.put("result:"+identity,new HistoryCell(cell.name(),cell.unit(),cell.value(),List.of(method)));
        }
        return values;
    }

    private String historyFieldName(String code,Map<String,String> fieldNames){
        if(fieldNames.containsKey(code))return fieldNames.get(code);
        if(!code.startsWith("IMPORT:"))return code;
        var column=java.util.regex.Pattern.compile("^IMPORT:STRUCT:sheet-(\\d+):.*:COLUMN:(\\d+):TABLE\\.COLUMN\\.(.+)$").matcher(code);
        if(column.matches())return column.group(3)+" · 第"+column.group(1)+"页第"+column.group(2)+"列";
        var form=java.util.regex.Pattern.compile("^IMPORT:STRUCT:.*:FORM:\\d+:(.+)$").matcher(code);
        return form.matches()?form.group(1):null;
    }

    private JsonNode firstCell(JsonNode item,String...keys){for(var key:keys){var value=item.path(key);if(value!=null&&!value.isMissingNode()&&!value.isNull()&&!value.isObject()&&!value.isArray())return value;}return null;}
    private void historyCell(Map<String,HistoryCell> values,String code,String name,String unit,JsonNode raw){
        if(raw==null||raw.isNull()||raw.isObject()||raw.isArray())return;
        JsonNode value=raw.deepCopy();
        if(value.isTextual()){
            var text=value.asText().strip();
            if(text.isBlank()||"—".equals(text)||"N/A".equalsIgnoreCase(text))return;
            var matcher=java.util.regex.Pattern.compile("^([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*([%°\\p{L}µμ].*)?$").matcher(text.replace(",",""));
            if(matcher.matches()){
                try{value=json.getNodeFactory().numberNode(Double.parseDouble(matcher.group(1)));if((unit==null||unit.isBlank())&&matcher.group(2)!=null)unit=matcher.group(2).strip();}
                catch(NumberFormatException ignored){}
            }
        }
        if(value.isNumber()&&!Double.isFinite(value.asDouble()))return;
        values.put(code,new HistoryCell(name,unit,value,List.of()));
    }

    private static final class HistoryColumnValue {
        final String code;final String name;final String unit;boolean numeric=true;int count;final java.util.TreeSet<String> methods=new java.util.TreeSet<>();
        HistoryColumnValue(String code,String name,String unit){this.code=code;this.name=name;this.unit=unit;}
        void add(JsonNode value){count++;if(value==null||!value.isNumber())numeric=false;}
        ObjectNode toJson(ObjectMapper mapper){return mapper.createObjectNode().put("code",code).put("name",name).put("kind",numeric?"NUMERIC":"CATEGORICAL").put("unit",unit).put("filledCount",count).set("testMethods",mapper.valueToTree(methods));}
    }
    private record HistoryCell(String name,String unit,JsonNode value,List<String> testMethods){}

    public OptimizationAccepted submit(JsonNode body,String idempotencyKey){
        var a=actor();require(a,"ai.experiment.optimize","AI_EXPERIMENT_OPTIMIZATION",null,"CREATE");
        if(idempotencyKey==null||idempotencyKey.isBlank())throw validation("必须提供幂等键");
        if(body==null||!body.isObject()||!"HISTORICAL_GP_BO".equals(body.path("mode").asText()))throw validation("请先选择实验数据和 X/Y");
        return submitHistorical(body,idempotencyKey,a,List.of(),false);
    }
    private OptimizationAccepted submitHistorical(JsonNode body,String idempotencyKey,Actor actor,List<JsonNode> supplementalRows,boolean useFrozenRows){
        require(actor,"experiment.view","EXPERIMENT",null,"READ");
        var datasetIds=requestedHistoryDatasetIds(body);var datasetId=datasetIds.getFirst();
        var dataset=mergedHistoryDataset(datasetIds);var selectedRows=selectedHistoryRows(dataset,body);
        if(selectedRows.isEmpty())throw validation("请至少勾选一条实验记录");
        var selectedIdsByDataset=json.createObjectNode();
        for(var sourceId:datasetIds){var ids=selectedIdsByDataset.putArray(sourceId.toString());for(var row:selectedRows)if(sourceId.toString().equals(row.path("sourceDatasetId").asText(datasetId.toString())))ids.add(row.path("id").asText());}
        var columns=new LinkedHashMap<String,JsonNode>();dataset.path("columns").forEach(column->columns.put(column.path("code").asText(),column));
        var requestedX=body.path("xColumns");var targetCode=body.path("yColumn").asText();
        if(!requestedX.isArray()||requestedX.isEmpty())throw validation("至少选择一个 X 因素");
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
                if(!Double.isFinite(min)||!Double.isFinite(max)||min>=max)throw validation("X 因素“"+feature.path("name").asText()+"”的最小值必须小于最大值");
                feature.put("minimum",min).put("maximum",max);
            }
        }
        var formulaCodes=columns.keySet().stream().filter(code->code.startsWith("formula:")).toList();
        var selectedFormulaCodes=featureCodes.stream().filter(code->code.startsWith("formula:")).toList();
        if(!selectedFormulaCodes.isEmpty()&&!featureCodes.containsAll(formulaCodes))
            throw validation("配方比例需要保持总量一致；请把所有材料比例列都选为 X 因素");
        var compositionGroups=json.createArrayNode();
        if(formulaCodes.size()>=2&&featureCodes.containsAll(formulaCodes)){
            var totals=new ArrayList<Double>();
            for(var row:selectedRows){
                var values=row.path("values");double total=0;boolean complete=true;
                for(var code:formulaCodes){var value=values.path(code);if(!value.isNumber()){complete=false;break;}total+=value.asDouble();}
                if(complete&&Double.isFinite(total))totals.add(total);
            }
            if(totals.isEmpty())throw validation("历史数据中没有完整的配方比例，无法生成有效方案");
            totals.sort(Double::compareTo);double total=totals.get(totals.size()/2);
            if(totals.size()%2==0)total=(total+totals.get(totals.size()/2-1))/2.0;
            var group=compositionGroups.addObject().put("total",total);group.set("codes",json.valueToTree(formulaCodes));
        }
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
        var direction=body.path("direction").asText("MAXIMIZE");if(!List.of("MAXIMIZE","MINIMIZE").contains(direction))throw validation("请选择提高或降低的目标方向");
        var records=repository.historyRecords(actor.organizationId(),datasetId);if(records.isEmpty())throw new ApiException(ApiErrorCode.NOT_FOUND,"历史实验数据不存在");
        var anchor=repository.baseline(actor.organizationId(),actor.userId(),"EXPERIMENT_VERSION",records.getFirst().experimentId())
                .orElseThrow(()->new ApiException(ApiErrorCode.BASELINE_NOT_CURRENT));
        var frozen=json.createObjectNode().put("type","HISTORICAL_DATASET").put("entityId",anchor.entityId().toString())
                .put("versionId",anchor.versionId().toString()).put("title",dataset.path("name").asText("历史实验数据"))
                .put("datasetId",datasetId.toString()).put("templateVersionId",datasetId.toString())
                .put("rowCount",selectedRows.size()).put("sourceDatasetCount",datasetIds.size()).put("contentHash",hashing.hash(dataset));
        frozen.put("templateSnapshotHash",anchor.templateSnapshotHash()==null?"":anchor.templateSnapshotHash());
        frozen.set("templateSnapshot",anchor.templateSnapshot());
        var request=json.createObjectNode().put("mode","HISTORICAL_GP_BO").put("datasetId",datasetId.toString())
                .put("datasetName",dataset.path("name").asText()).put("target",targetCode)
                .put("targetName",columns.get(targetCode).path("name").asText(targetCode)).put("direction",direction)
                .put("candidateCount",4).put("selectedRowCount",selectedRows.size());
        request.set("datasetIds",json.valueToTree(datasetIds));request.set("selectedRowIdsByDataset",selectedIdsByDataset);
        request.set("features",featureNodes);request.set("rows",rows);request.set("columns",dataset.path("columns").deepCopy());
        if(!compositionGroups.isEmpty())request.set("compositionGroups",compositionGroups);
        var normalized=hashing.canonical(request);var requestHash=hashing.hash(normalized);var requestId=RequestIdHolder.currentOrUnknown();var seed=seed(requestHash);var runId=UUID.randomUUID();
        var config=json.createObjectNode().put("algorithm","GAUSSIAN_PROCESS_EI").put("candidateCount",4);
        var optimization=json.createObjectNode().put("datasetId",datasetId.toString()).put("sourceDatasetCount",datasetIds.size())
                .put("selectedRows",selectedRows.size()).put("trainingRows",rows.size()).put("target",targetCode);
        boolean inserted;
        try{inserted=repository.insertRun(runId,actor.organizationId(),actor.userId(),idempotencyKey.strip(),requestId,requestHash,normalized,json.createObjectNode(),seed,config,anchor,hashing.hash(frozen),frozen,optimization);}
        catch(DataIntegrityViolationException e){throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,"当前组织已有实验优化任务执行中",e);}
        if(!inserted){var old=repository.byKey(actor.organizationId(),actor.userId(),idempotencyKey).orElseThrow();if(!old.requestHash().equals(requestHash))throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);return accepted(old);}
        var payload=json.createObjectNode().put("organizationId",actor.organizationId().toString()).put("actorId",actor.userId().toString()).put("runId",runId.toString()).put("requestId",requestId);
        var jobId=jobs.enqueue(actor.organizationId(),"AI_EXPERIMENT_OPTIMIZATION_V2",payload,"ai-experiment-optimization:"+runId,45,3);repository.attachJob(actor.organizationId(),runId,jobId);
        audit.append(actor.organizationId(),actor.userId(),"AI_EXPERIMENT_OPTIMIZATION_STARTED","AI_RESEARCH_RUN",runId,normalized);
        if(inlineWorker)Thread.startVirtualThread(()->{try{execute(actor.organizationId(),runId);}catch(Exception ignored){}});
        return new OptimizationAccepted(runId,jobId,"QUEUED",Map.of(),1000);
    }

    private void appendHistoryRow(ArrayNode rows,JsonNode source,String targetCode,ArrayNode features){
        var y=source.path(targetCode);if(!y.isNumber())return;
        var point=rows.addObject().put(targetCode,y.asDouble());boolean complete=true;
        for(var feature:features){var code=feature.path("code").asText();var x=source.path(code);
            if(x.isMissingNode()||x.isNull()||x.isObject()||x.isArray()||("NUMERIC".equals(feature.path("kind").asText())&&!x.isNumber())){complete=false;break;}
            point.set(code,x.deepCopy());
        }
        if(!complete)rows.remove(rows.size()-1);
    }

    public OptimizationAccepted continueHistorical(UUID previousRunId,String idempotencyKey){
        var actor=actor();require(actor,"ai.experiment.optimize","AI_RESEARCH_RUN",previousRunId,"UPDATE");
        if(idempotencyKey==null||idempotencyKey.isBlank())throw validation("必须提供幂等键");
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
        return submitHistorical(body,idempotencyKey,actor,supplemental,true);
    }

    public OptimizationRun get(UUID runId){var a=actor();require(a,"ai.experiment.optimize","AI_RESEARCH_RUN",runId,"READ");return view(a.organizationId(),repository.byId(a.organizationId(),a.userId(),runId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"实验优化运行不存在")));}

    public JsonNode execute(UUID org,UUID runId){
        var run=repository.any(org,runId).orElseThrow();if(!repository.markRunning(org,runId))return run.result();
        try{return executeHistorical(org,run);}
        catch(Exception e){repository.fail(org,runId,e instanceof ApiException a?a.errorCode().code():"COMPUTE_UNAVAILABLE",e.getMessage());throw e instanceof RuntimeException r?r:new IllegalStateException(e);}
    }
    private JsonNode executeHistorical(UUID org,OptimizationRepository.RunRow run){
        repository.progress(org,run.id(),20,"FITTING_GAUSSIAN_PROCESS");
        var payload=(ObjectNode)run.request().deepCopy();payload.put("seed",run.seed());
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

    public void fail(UUID org,UUID run,String code,String message){repository.fail(org,run,code,message);}

    /**
     * R09 drafts must use a published customer experiment template. A baseline
     * template is kept when it was already frozen with the run; otherwise the
     * caller may select a published template explicitly. The first published
     * experiment template is the deterministic default for API clients that do
     * not send a selection.
     */


    /**
     * The experiment editor needs the published template's field coordinates
     * to place an AI candidate into the customer's existing workbook layout.
     * Keep only the coordinates and semantics needed by the browser renderer;
     * the workbook itself remains the published template snapshot.
     */

    /** Convert customer-facing labels from older experiment versions to the
     * immutable category codes used by the model bundles. New imports already
     * carry codes and pass through unchanged. */


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
