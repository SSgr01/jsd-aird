package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ai.rnd.formula.FormulaNormalizationService;
import com.jsd.aird.ai.rnd.catalog.RegisteredUvpuTargetCatalog;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.optimization.OptimizationContracts.*;

@Service
public class OptimizationService {
    private static final Logger log = LoggerFactory.getLogger(OptimizationService.class);
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
    private final FormulaNormalizationService formulaNormalization;
    private final boolean inlineWorker;

    public OptimizationService(OptimizationRepository repository, PredictionRepository predictions,
                               FormulaDesignService formulaDesign, FormulaModelV2Client compute,
                                ConfigurationHashing hashing, OpsAsyncFacade jobs, AuditLogFacade audit,
                                AuthorizationService authorization, ExperimentDraftFacade drafts,
                                TemplateDataImportFacade templates, ObjectMapper json,
                                FormulaNormalizationService formulaNormalization,
                                @Value("${JSD_AIRD_R09_INLINE_ACCEPTANCE_WORKER:false}") boolean inlineWorker) {
        this.repository=repository;this.predictions=predictions;this.formulaDesign=formulaDesign;this.compute=compute;
        this.hashing=hashing;this.jobs=jobs;this.audit=audit;this.authorization=authorization;this.drafts=drafts;
        this.templates=templates;
        this.json=json;this.formulaNormalization=formulaNormalization;this.inlineWorker=inlineWorker;
    }

    public BaselinePage baselines(String type,String keyword,int page,int size){
        var a=actor();require(a,"ai.experiment.optimize","AI_EXPERIMENT_OPTIMIZATION",null,"READ");
        var p=Math.max(0,page);var s=Math.min(100,Math.max(1,size));
        // A baseline catalog is allowed to contain historical/incomplete rows.
        // One malformed formula must not make the whole source selector fail;
        // only rows that can be normalized into an optimization formula are
        // selectable. The detailed baseline view still reports the actionable
        // error when a caller tries to open an invalid row directly.
        var rows=repository.baselines(a.organizationId(),a.userId(),type,keyword,p,s).stream()
                .filter(row->canReadBaseline(a,row.type())).map(row->safeSummary(a.organizationId(),row))
                .filter(Objects::nonNull).toList();
        return new BaselinePage(rows,p,s);
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

    public JsonNode context(String baselineType,UUID baselineId,List<UUID> targetIds){
        var a=actor();require(a,"ai.experiment.optimize","AI_EXPERIMENT_OPTIMIZATION",baselineId,"READ");
        var baseline=resolveBaseline(a,baselineType,baselineId);var snapshot=baselineSnapshot(a.organizationId(),baseline);var root=json.createObjectNode();
        root.set("baseline",json.valueToTree(summary(a.organizationId(),baseline)));root.set("frozenBaseline",snapshot);
        root.put("simulationAuthorized",formulaDesign.simulationAuthorized());
        var catalog=json.createArrayNode();predictions.targetCatalog(a.organizationId()).forEach(t->catalog.add(json.createObjectNode()
                .put("targetId",t.id().toString()).put("code",t.code()).put("name",t.name()).put("category",Objects.toString(t.category(),"未分类"))
                .put("valueType",t.valueType()).put("available",formulaDesign.modelAvailable(t))
                .put("simulation",formulaDesign.isSimulation(t.sourceDataNature(),t.testOnlySimulation()))
                .put("testOnlySimulation",t.testOnlySimulation())));
        root.set("targets",catalog);
        RegisteredUvpuTargetCatalog.append(json, catalog);
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
        for(var input:inputs.values()){
            if(!(input instanceof ObjectNode field)||!"NUMBER".equals(input.path("valueType").asText()))continue;
            var ranges=json.createArrayNode();var feature="input:"+input.path("code").asText();
            for(var binding:bindings){var domain=binding.modelDomain();if(domain==null)continue;var names=domain.path("features");
                for(int index=0;index<names.size();index++)if(feature.equals(names.path(index).asText())&&domain.path("minimum").path(index).isNumber()&&domain.path("maximum").path(index).isNumber()){
                    var range=json.createObjectNode().put("targetId",binding.targetId().toString()).put("targetName",binding.targetName());
                    range.set("minimum",domain.path("minimum").path(index));range.set("maximum",domain.path("maximum").path(index));
                    if(input.path("unit").isTextual())range.set("unit",input.path("unit"));
                    ranges.add(range);
                }
            }
            field.set("supportedRanges",ranges);
        }
        intersection.values().forEach(materials::add);root.set("materialIntersection",materials);root.set("adjustableInputs",json.valueToTree(inputs.values()));
        root.put("controlTotal",snapshot.path("formula").path("recordedTotal").asDouble());root.put("candidateCount",4);return root;
    }

    public OptimizationAccepted submit(JsonNode body,String idempotencyKey){
        var a=actor();require(a,"ai.experiment.optimize","AI_EXPERIMENT_OPTIMIZATION",null,"CREATE");
        if(idempotencyKey==null||idempotencyKey.isBlank())throw validation("必须提供幂等键");
        if(body==null||!body.isObject())throw validation("实验优化请求不能为空");
        if("HISTORICAL_GP_BO".equals(body.path("mode").asText()))return submitHistorical(body,idempotencyKey,a,List.of(),false);
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
        // Freeze every other usable performance model as read-only output. These models
        // enrich the candidate cards and draft; they never affect optimization scoring.
        var displayBindings=json.createArrayNode();var selectedIds=new LinkedHashSet<>(targetIds);
        var additionalIds=predictions.targetCatalog(a.organizationId()).stream().map(PredictionRepository.TargetCatalog::id)
                .filter(id->!selectedIds.contains(id)).toList();
        for(var display:predictions.targetBindings(a.organizationId(),additionalIds)){
            if(!formulaDesign.modelAvailable(display))continue;
            try{displayBindings.add(formulaDesign.frozenModelBinding(display));bindingMap.put(display.targetId(),display.modelId());}
            catch(RuntimeException e){log.warn("Skipping an unavailable supplementary performance model for target {}",display.targetId(),e);}
        }
        var requestWithDisplay=(ObjectNode)body.deepCopy();requestWithDisplay.set("displayModelBindings",displayBindings);
        var normalized=hashing.canonical(requestWithDisplay);var requestHash=hashing.hash(normalized);var requestId=RequestIdHolder.currentOrUnknown();var seed=seed(requestHash);var runId=UUID.randomUUID();
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
        try{
            if("HISTORICAL_GP_BO".equals(run.request().path("mode").asText()))return executeHistorical(org,run);
            repository.progress(org,runId,15,"BASELINE_CHECK");var payload=recommendPayload(org,run);repository.progress(org,runId,35,"BUILDING_SPACE");
            var response=compute.recommend(payload);repository.progress(org,runId,75,"STRATEGY_SELECTION");
            if("FAILED".equals(response.path("executionStatus").asText())){repository.fail(org,runId,response.path("code").asText("COMPUTE_UNAVAILABLE"),response.path("message").asText("计算服务执行失败"));return response;}
            var savedStrategies=new LinkedHashSet<String>();var no=0;for(var c:response.path("candidates")){if(!c.isObject())continue;var strategy=c.path("strategy").asText();if(!STRATEGIES.contains(strategy)||!savedStrategies.add(strategy))continue;var formula=c.path("formula");var hash=hashing.hash(json.createObjectNode().put("strategy",strategy).set("formula",formula));var outputs=candidateResults(org,run,c);repository.saveCandidate(org,runId,++no,strategy,strategyTitle(strategy),formula,c.path("inputs"),resultsObject(outputs,org,run.bindings()),c.path("quality"),c.path("applicability"),c.path("ruleCheck"),c.path("evidence"),c.path("score").asDouble(),hash,formula.path("recordedTotal").asDouble(),c.path("baselineDistance").asDouble(),c.path("changeSummary"),c.path("strategyEvidence"),c.path("riskFlags"));}
            var missing=STRATEGIES.stream().filter(s->!savedStrategies.contains(s)).toList();var simulation=formulaDesign.simulationForBindings(org,run.bindings());var summary=json.createObjectNode().put("requestedCandidateCount",4).put("actualCandidateCount",savedStrategies.size()).put("simulation",simulation);if(simulation)summary.put("predictionMode","SIMULATION");summary.set("missingStrategies",json.valueToTree(missing));
            if(response.has("missingStrategyReasons"))summary.set("missingStrategyReasons",response.path("missingStrategyReasons"));
            if(response.has("shortfallReason"))summary.set("shortfallReason",response.path("shortfallReason"));
            if(response.has("searchEngine"))summary.set("searchEngine",response.path("searchEngine"));
            if(response.has("searchStrategy"))summary.set("searchStrategy",response.path("searchStrategy"));
            if(response.has("searchEvidence"))summary.set("searchEvidence",response.path("searchEvidence"));
            var outcome=savedStrategies.isEmpty()?"BLOCKED":missing.isEmpty()&&!("PARTIAL".equals(response.path("status").asText()))?"SUCCEEDED":"PARTIAL";repository.complete(org,runId,outcome,summary);return summary;
        }catch(Exception e){repository.fail(org,runId,e instanceof ApiException a?a.errorCode().code():"COMPUTE_UNAVAILABLE",e.getMessage());throw e instanceof RuntimeException r?r:new IllegalStateException(e);}
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

    @Transactional
    public DraftCreationResult createDrafts(UUID runId,DraftCommand command,String key){
        var a=actor();require(a,"ai.experiment.optimize","AI_RESEARCH_RUN",runId,"UPDATE");require(a,"experiment.create","EXPERIMENT",null,"CREATE");
        if(key==null||key.isBlank())throw validation("必须提供幂等键");if(command==null||command.candidateIds()==null||command.candidateIds().isEmpty())throw validation("至少选择一个实验方案");if(command.candidateIds().size()>4)throw validation("一次最多创建4个实验草稿");
        var run=repository.byId(a.organizationId(),a.userId(),runId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));if(!"SUCCEEDED".equals(run.executionStatus()))throw new ApiException(ApiErrorCode.DRAFT_CREATION_CONFLICT,"实验优化尚未完成");
        var links=new ArrayList<DraftLink>();
        for(var candidateId:new LinkedHashSet<>(command.candidateIds())){
            var itemKey=key.strip()+":"+candidateId;var old=repository.linkByKey(a.organizationId(),a.userId(),itemKey);if(old.isPresent()){links.add(linkView(old.get()));continue;}
            var candidate=repository.candidate(a.organizationId(),runId,candidateId).orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND,"候选方案不存在"));
            if("CONTROL".equals(candidate.strategy()))throw validation("当前配方已在上方列出，不需要重复创建实验草稿");
            var template=resolveExperimentTemplate(a, run, command.templateVersionId());
            var edit=editModel(candidate,run,templateBindings(a.organizationId(),template.versionId()));
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

    /**
     * The experiment editor needs the published template's field coordinates
     * to place an AI candidate into the customer's existing workbook layout.
     * Keep only the coordinates and semantics needed by the browser renderer;
     * the workbook itself remains the published template snapshot.
     */
    private ArrayNode templateBindings(UUID organizationId, UUID templateVersionId) {
        var bindings = json.createArrayNode();
        try {
            for (var binding : templates.getBindings(organizationId, templateVersionId)) {
                if (binding.targetPath() == null || binding.targetPath().isBlank()) continue;
                var item = bindings.addObject()
                        .put("fieldCode", Objects.toString(binding.fieldCode(), ""))
                        .put("targetPath", binding.targetPath())
                        .put("mappingKind", Objects.toString(binding.mappingKind(), "SCALAR"))
                        .put("repeatAxis", Objects.toString(binding.repeatAxis(), "ROW"))
                        .put("recordHeight", binding.recordHeight())
                        .put("recordWidth", binding.recordWidth())
                        .put("recordStride", binding.recordStride())
                        .put("valueSource", Objects.toString(binding.valueSource(), "USER_INPUT"));
                if (binding.locator() != null) item.set("locator", binding.locator().deepCopy());
            }
        } catch (RuntimeException error) {
            // Draft creation still has a usable fallback recipe sheet when a
            // published template has no readable field contract.
            log.warn("Could not load field coordinates for experiment template {}", templateVersionId, error);
        }
        return bindings;
    }

    private OptimizationRepository.BaselineRow resolveBaseline(Actor a,String type,UUID id){if(type==null||type.isBlank()||id==null)throw new ApiException(ApiErrorCode.BASELINE_REQUIRED);if(!canReadBaseline(a,type))throw new ApiException(ApiErrorCode.PERMISSION_DENIED);return repository.baseline(a.organizationId(),a.userId(),type,id).orElseThrow(()->new ApiException(ApiErrorCode.BASELINE_NOT_CURRENT));}
    private boolean canReadBaseline(Actor a,String type){var permission=switch(type){case "EXPERIMENT_VERSION"->"experiment.view";case "DATA_SAMPLE_REVISION"->"data.view";case "RESEARCH_CANDIDATE"->"ai.formula.predict";default->null;};return permission!=null&&authorization.check(new PermissionCheck(a.organizationId(),a.userId(),permission,"AI_OPTIMIZATION_BASELINE",null,"READ")).allowed();}
    private BaselineSummary safeSummary(UUID org,OptimizationRepository.BaselineRow row){
        try{return summary(org,row);}
        catch(ApiException ignored){
            log.debug("Skipping incomplete optimization baseline type={} id={} title={} reason={}",row.type(),row.entityId(),row.title(),ignored.getMessage());
            return null;
        }
    }
    private BaselineSummary summary(UUID org,OptimizationRepository.BaselineRow row){var snap=baselineSnapshot(org,row);var formula=snap.path("formula");var rawTotal=formula.path("rawFormulaTotal").isNumber()?formula.path("rawFormulaTotal").asDouble():formula.path("recordedTotal").asDouble();return new BaselineSummary(row.type(),row.entityId(),row.versionId(),row.title(),row.sourceLabel(),rawTotal,formula.path("components"),snap.path("inputs"),snap.path("results"),hashing.hash(snap),row.updatedAt());}
    private ObjectNode baselineSnapshot(UUID org,OptimizationRepository.BaselineRow row){var out=json.createObjectNode().put("type",row.type()).put("entityId",row.entityId().toString()).put("versionId",row.versionId().toString()).put("title",row.title());var payload=row.payload();JsonNode formula;JsonNode inputs;JsonNode results;
        if("EXPERIMENT_VERSION".equals(row.type())){formula=formulaFromExperiment(payload.path("formulaItems"));inputs=payload.path("dynamicValues");results=payload.path("testResults");}else{formula=payload.path("formula");inputs=payload.path("inputs");results=payload.has("predictions")?payload.path("predictions"):observationsForBaseline(org,payload.path("observations"));}
        try{out.set("formula",normalizeFormula(formula));}catch(ApiException ignored){throw new ApiException(ApiErrorCode.BASELINE_DATA_INCOMPLETE,"该基线的材料配方尚未结构化，暂不能用于实验优化");}out.set("inputs",inputs.isObject()?inputs.deepCopy():json.createObjectNode());out.set("results",results.deepCopy());if(row.templateVersionId()!=null)out.put("templateVersionId",row.templateVersionId().toString());if(row.templateSnapshotHash()!=null)out.put("templateSnapshotHash",row.templateSnapshotHash());out.set("templateSnapshot",row.templateSnapshot());return out;}
    private JsonNode observationsForBaseline(UUID org,JsonNode raw){
        if(raw==null||raw.isMissingNode()||raw.isNull())return json.createObjectNode().putArray("targets");
        if(raw.isArray()||(raw.isObject()&&raw.path("targets").isArray()))return raw;
        var targets=json.createArrayNode();var names=new LinkedHashMap<String,String>();predictions.targetCatalog(org).forEach(t->names.put(t.code(),t.name()));
        if(raw.isObject())raw.fields().forEachRemaining(entry->{var value=entry.getValue();if(!value.isObject())return;var item=(ObjectNode)value.deepCopy();var code=item.path("targetCode").asText("");if(code.isBlank()&&!item.has("targetId"))return;if(!item.has("value")&&item.has("rawValue"))item.set("value",item.get("rawValue"));if(!code.isBlank()&&names.containsKey(code)&&!item.has("targetName"))item.put("targetName",names.get(code));targets.add(item);});
        return json.createObjectNode().set("targets",targets);
    }
    private ObjectNode formulaFromExperiment(JsonNode items){var f=json.createObjectNode().put("basis","MASS_PERCENT").put("compositionComplete",true);var components=f.putArray("components");double total=0;if(items.isArray())for(var item:items){var ratio=number(item,"ratio","value","rawValue","amount");if(ratio==null)continue;var c=components.addObject().put("materialId",item.path("materialId").asText(item.path("materialCode").asText())).put("materialCode",item.path("materialCode").asText()).put("ratio",ratio).put("unit","PERCENT").put("amountKnown",true);total+=ratio;}f.put("recordedTotal",total);return f;}
    private ObjectNode normalizeFormula(JsonNode value){
        ObjectNode copy;
        if(value != null && value.isObject() && (value.path("components").isArray() || value.path("items").isArray())){
            copy=(ObjectNode)value.deepCopy();
            if(!copy.has("components"))copy.set("components",copy.path("items").deepCopy());
            copy.remove("items");
            if(!copy.has("basis"))copy.put("basis","MASS_PERCENT");
            if(!copy.has("compositionComplete"))copy.put("compositionComplete",true);
            for(var component:copy.withArray("components"))if(component instanceof ObjectNode c){if(!c.has("unit"))c.put("unit","PERCENT");if(!c.has("amountKnown"))c.put("amountKnown",c.hasNonNull("ratio"));}
        } else copy=formulaFromExperiment(value);
        return formulaNormalization.requireValid(copy).formula();
    }
    private void precheckBaseline(JsonNode snapshot,ArrayNode bindings,String requestId){var p=json.createObjectNode().put("contractVersion","formula-model.v2").put("requestId",requestId+":baseline").put("seed",7).put("runId",UUID.randomUUID().toString());p.set("formula",formulaForCompute(snapshot.path("formula")));p.set("inputs",alignedInputs(snapshot.path("inputs"),bindings));p.set("modelBindings",bindings);JsonNode result;try{result=compute.score(p);}catch(FormulaModelV2Client.ComputeTransportException e){throw new ApiException(ApiErrorCode.COMPUTE_UNAVAILABLE,"formula-model.v2计算服务暂不可用",e);}if("FAILED".equals(result.path("executionStatus").asText())){var failed=result.path("results").isArray()&&!result.path("results").isEmpty()?result.path("results").get(0):json.createObjectNode();throw new ApiException(ApiErrorCode.MODEL_ARTIFACT_INVALID,failed.path("code").asText("MODEL_LOAD_FAILED")+"："+failed.path("message").asText("基线模型评分失败"));}for(var item:result.path("results")){if("BLOCKED".equals(item.path("status").asText())&&"OUT_OF_DOMAIN".equals(item.path("code").asText()))throw new ApiException(ApiErrorCode.BASELINE_OUT_OF_DOMAIN);if(!"SUCCEEDED".equals(item.path("status").asText()))throw new ApiException(ApiErrorCode.BASELINE_DATA_INCOMPLETE,item.path("message").asText("基线输入不完整"));}}
    private void validateSpace(JsonNode body,JsonNode snapshot,List<PredictionRepository.TargetBinding> bindings){var formula=snapshot.path("formula");if(!formula.path("components").isArray()||formula.path("components").isEmpty()||!formula.path("recordedTotal").isNumber())throw new ApiException(ApiErrorCode.BASELINE_DATA_INCOMPLETE);var targetTotal="MASS_PERCENT".equals(formula.path("basis").asText())?formula.path("recordedTotal").asDouble():body.path("targetTotal").asDouble(formula.path("recordedTotal").asDouble());if(!Double.isFinite(targetTotal)||targetTotal<=0)throw new ApiException(ApiErrorCode.TARGET_TOTAL_CONFLICT);var materials=body.path("variableMaterials");var inputs=body.path("variableInputs");if((!materials.isArray()||materials.isEmpty())&&(!inputs.isArray()||inputs.isEmpty()))throw new ApiException(ApiErrorCode.NO_ADJUSTABLE_INPUT);for(var v:materials){var minimum=v.path("minimum");var maximum=v.path("maximum");if(!minimum.isNumber()||!maximum.isNumber()||!Double.isFinite(minimum.asDouble())||!Double.isFinite(maximum.asDouble())||minimum.asDouble()>=maximum.asDouble())throw new ApiException(ApiErrorCode.OPTIMIZATION_SPACE_CONFLICT,"请为每个可调整材料填写有效的最小值和最大值");}for(var v:inputs){var timing=v.path("availabilityTiming").asText(v.path("acquisitionTiming").asText());if("POST_EXPERIMENT".equals(timing))throw new ApiException(ApiErrorCode.INPUT_SPACE_CONFLICT,"实验后字段不能作为优化变量");}}
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
        p.remove("baselineRef");p.remove("targetTotal");p.remove("variableMaterials");p.remove("variableInputs");p.remove("displayModelBindings");
        return p;
    }
    private JsonNode formulaForCompute(JsonNode value){return formulaNormalization.forModelCompute(value);}
    private ObjectNode alignedInputs(JsonNode raw,ArrayNode bindings){
        var out=json.createObjectNode();
        for(var binding:bindings)for(var field:binding.path("inputScheme").path("fields")){
            var code=field.path("code").asText();if(code.isBlank()||out.hasNonNull(code))continue;
            var wanted=normalizeKey(code);var fields=raw==null?java.util.Collections.<Map.Entry<String,JsonNode>>emptyIterator():raw.fields();
            while(fields.hasNext()){
                var entry=fields.next();var actual=normalizeKey(entry.getKey());
                if(actual.equals(wanted)||wanted.endsWith(actual)||actual.endsWith(wanted)){
                    out.set(code,canonicalInputValue(code,entry.getValue()));break;
                }
            }
        }
        return out;
    }
    /** Convert customer-facing labels from older experiment versions to the
     * immutable category codes used by the model bundles. New imports already
     * carry codes and pass through unchanged. */
    private JsonNode canonicalInputValue(String code,JsonNode value){
        if(value==null||!value.isTextual())return value;
        var raw=value.asText().strip();var normalized=normalizeKey(raw);var field=normalizeKey(code);
        if(field.contains("SUBSTRATE")){
            if(normalized.contains("PMMAPC")||raw.contains("复合板"))return json.getNodeFactory().textNode("PMMA_PC_COMPOSITE_064");
            if(normalized.contains("PC")&&!normalized.contains("PET"))return json.getNodeFactory().textNode("PC_170UM");
            if(normalized.contains("PET"))return json.getNodeFactory().textNode("PET_100UM_OPTICAL");
        }
        if(field.contains("APPLICATIONMETHOD")){
            if(normalized.contains("WIREBAR")||raw.contains("绕丝棒"))return json.getNodeFactory().textNode("WIRE_BAR_ROLL_COAT_40UM");
            if(normalized.contains("SPRAY")||raw.contains("喷涂"))return json.getNodeFactory().textNode("SPRAY_COATING");
        }
        if(field.contains("CURINGSOURCE")){
            if(normalized.contains("MERCURY")||raw.contains("汞灯"))return json.getNodeFactory().textNode("MERCURY_LAMP_UV");
            if(normalized.contains("LED"))return json.getNodeFactory().textNode("LED_UV");
        }
        return value;
    }
    private String normalizeKey(String value){return value==null?"":value.toUpperCase(java.util.Locale.ROOT).replace("TEST_","").replace("PROCESS_","").replaceAll("[^A-Z0-9]","");}
    private OptimizationRun view(UUID org,OptimizationRepository.RunRow run){var candidates=repository.candidates(org,run.id()).stream().map(c->new OptimizationCandidate(c.id(),c.candidateNo(),c.strategy(),c.title(),c.formula(),c.inputs(),c.results(),c.quality(),c.applicability(),c.ruleCheck(),c.score(),c.targetTotal(),c.distance(),c.changes(),c.strategyEvidence(),c.risks())).toList();var missing=STRATEGIES.stream().filter(s->candidates.stream().noneMatch(c->s.equals(c.strategy()))).toList();var map=new LinkedHashMap<UUID,UUID>();run.bindings().fields().forEachRemaining(e->map.put(UUID.fromString(e.getKey()),UUID.fromString(e.getValue().asText())));var result=run.result();var simulation=formulaDesign.simulationForBindings(org,run.bindings());return new OptimizationRun(run.id(),run.executionStatus(),run.outcomeStatus(),run.progress(),run.stage(),4,candidates.size(),missing,result.path("missingStrategyReasons"),candidates,run.error(),run.baseline(),run.request(),map,run.requestId(),run.updatedAt(),result.path("searchEngine").asText(null),result.path("searchStrategy").asText(null),result.path("searchEvidence"),simulation,simulation?"SIMULATION":"FORMAL");}
    private OptimizationAccepted accepted(OptimizationRepository.RunRow run){var map=new LinkedHashMap<UUID,UUID>();run.bindings().fields().forEachRemaining(e->map.put(UUID.fromString(e.getKey()),UUID.fromString(e.getValue().asText())));var status=List.of("QUEUED","RUNNING").contains(run.executionStatus())?"RUNNING":run.executionStatus();return new OptimizationAccepted(run.id(),run.opsJobId(),status,map,1000);}
    private ObjectNode targetView(PredictionRepository.TargetBinding b){
        var n=json.createObjectNode().put("targetId",b.targetId().toString()).put("name",b.targetName())
                .put("valueType",b.valueType()).put("available",formulaDesign.modelAvailable(b))
                .put("simulation",formulaDesign.isSimulation(b)).put("testOnlySimulation",b.testOnlySimulation());
        if(b.modelId()!=null)n.put("modelVersionId",b.modelId().toString());
        n.set("classes",b.classes()==null?json.createArrayNode():b.classes());
        if(b.definition()!=null&&b.definition().isObject()){
            var direction=b.definition().path("optimizationDirection").asText("");
            if(!direction.isBlank())n.put("direction",direction);
            var positive=b.definition().path("positiveClass").asText("");
            if(!positive.isBlank())n.put("positiveClass",positive);
            var threshold=b.observationSemantics()==null?null:b.observationSemantics().path("decisionThreshold");
            if(threshold!=null&&threshold.isNumber())n.put("decisionThreshold",threshold.asDouble());
        }
        return n;
    }
    private ArrayNode candidateResults(UUID org,OptimizationRepository.RunRow run,JsonNode candidate){
        var outcomes=json.createArrayNode();var original=candidate.path("results").isObject()&&candidate.path("results").path("targets").isArray()?candidate.path("results").path("targets"):candidate.path("results");
        if(original.isArray())original.forEach(item->outcomes.add(item.deepCopy()));
        var displayBindings=run.request().path("displayModelBindings");if(!displayBindings.isArray()||displayBindings.isEmpty())return outcomes;
        try{
            var p=json.createObjectNode().put("contractVersion","formula-model.v2").put("requestId",run.requestId()+":display:"+candidate.path("strategy").asText()).put("seed",run.seed()).put("runId",run.id().toString());
            p.set("formula",formulaForCompute(candidate.path("formula")));p.set("inputs",alignedInputs(candidate.path("inputs"), (ArrayNode)displayBindings));p.set("modelBindings",displayBindings.deepCopy());
            var scored=compute.score(p);if(scored.path("results").isArray())scored.path("results").forEach(item->outcomes.add(item.deepCopy()));
        }catch(RuntimeException e){
            log.warn("Supplementary performance predictions could not be calculated for optimization run {}",run.id(),e);
            displayBindings.forEach(binding->{var item=json.createObjectNode().put("status","FAILED").put("modelVersionId",binding.path("modelVersionId").asText()).put("code","SUPPLEMENTARY_PREDICTION_UNAVAILABLE").put("message","该性能暂时无法预测");outcomes.add(item);});
        }
        return outcomes;
    }
    private ObjectNode editModel(OptimizationRepository.CandidateRow c,OptimizationRepository.RunRow run,ArrayNode templateBindings){
        var m=json.createObjectNode().put("title","AI实验优化-"+strategyTitle(c.strategy())).put("purpose","验证"+strategyTitle(c.strategy())+"方案的实际表现").put("plan","先确定本次试样总量，再按“配方数据”中的比例换算投料；按“实验条件”完成测试后，把实测结果填写在“测试结果”页。");
        m.set("dynamicValues",c.inputs().deepCopy());m.set("tables",json.createArrayNode());var items=m.putArray("formulaItems");
        for(var component:c.formula().path("components")){
            var materialId=component.path("materialId").asText();var material=predictions.material(materialId).orElse(null);var code=component.path("materialCode").asText(component.path("code").asText(materialId));
            items.add(json.createObjectNode().put("materialId",materialId).put("materialCode",code).put("materialName",component.path("materialName").asText(component.path("name").asText(material==null?code:material.name())))
                    .put("rawValue",component.path("ratio").asDouble()).put("rawUnit","%").put("ratio",component.path("ratio").asDouble()).put("unit","%"));
        }
        m.set("processSteps",json.createArrayNode());m.set("testResults",json.createArrayNode());m.set("events",json.createArrayNode());m.set("conclusion",json.createObjectNode().put("resultStatus","").put("mainConclusion","").put("failureCategory",""));
        var aiSource=json.createObjectNode().put("runId",run.id().toString()).put("candidateId",c.id().toString()).put("strategy",c.strategy());aiSource.set("predictions",c.results());aiSource.set("plannedInputs",c.inputs().deepCopy());aiSource.set("templateBindings",templateBindings);m.set("aiSource",aiSource);return m;
    }
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
    private record CommonHistoryColumns(List<JsonNode> columns, List<Map<String, String>> sourceCodes) {}
    private JsonNode readJson(String raw){try{return raw==null?json.createObjectNode():json.readTree(raw);}catch(Exception error){throw new IllegalStateException("保存的优化实测值无法读取",error);}}
}
