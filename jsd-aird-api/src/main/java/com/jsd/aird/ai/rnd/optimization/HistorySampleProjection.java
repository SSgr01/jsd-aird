package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.data.MeasurementValueParser;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** A report is a source document; samples, not documents, are learning observations. */
public final class HistorySampleProjection {
    public static final int VERSION = 2;
    private final ObjectMapper json;
    public HistorySampleProjection(ObjectMapper json) { this.json = json; }

    public ObjectNode dataset(UUID org, UUID datasetId, String name, List<OptimizationRepository.HistoryRecordRow> records,
                              Map<String,String> fieldNames, Map<String,String> materialIds) {
        var out=json.createObjectNode().put("id",datasetId.toString()).put("name",name).put("projectionVersion",VERSION);
        out.set("sourceDatasetIds",json.valueToTree(List.of(datasetId.toString())));
        var rows=out.putArray("rows");var issues=out.putArray("issues");
        var columns=new LinkedHashMap<String,ObjectNode>();
        for(var record:records) {
            var model=record.editModel();var groups=groups(model);
            for(var group:groups) {
                var groupKey=text(group,"sampleKey","sourceGroupKey");
                var context=text(group,"sourceContextKey");
                var sample=text(group,"sourceIdentity");
                var row=rows.addObject().put("id",record.versionId()+":"+(!groupKey.isBlank()?groupKey:"sample"))
                        .put("sourceExperimentId",record.experimentId().toString()).put("versionId",record.versionId().toString())
                        .put("sampleKey",groupKey).put("sourceContextKey",context).put("sampleNo",sample)
                        .put("label",sample.isBlank()?Objects.toString(record.experimentNo(),record.title()):sample)
                        .put("title",record.title()).put("sourceDatasetId",datasetId.toString()).put("sourceDatasetName",name);
                var values=row.putObject("values");var refs=row.putObject("sourceRefs");var states=row.putObject("cellStates");var conflicts=new HashSet<String>();
                for(var domain:List.of("formulaItems","processSteps","testResults"))for(var item:observations(model,domain)) {
                    if(!belongs(item,group,groups.size()))continue;
                    var label=text(item,"targetName","testItem","materialName","labelPath","operation","name","field");
                    if(label.isBlank()||metadata(label))continue;
                    var unit=cleanUnit(text(item,"unit","rawUnit"));
                    boolean formula=domain.equals("formulaItems");
                    var raw=raw(model,item,formula?"ratio":"value");
                    if(unit.isBlank())unit=MeasurementValueParser.inferredUnit(raw.asText());
                    if(unit.isBlank()&&label.matches(".*[/／][µμu]m$"))unit="μm";
                    String code;
                    var column=json.createObjectNode().put("name",label).put("unit",unit);
                    if(formula) {
                        var identity=text(item,"materialId","materialCode","code","materialName","name");
                        if(identity.isBlank())continue;
                        var materialId=text(item,"materialId");
                        if(!materialId.isBlank())try { UUID.fromString(materialId); } catch(IllegalArgumentException ignored) { materialId=""; }
                        if(materialId.isBlank())materialId=materialIds.getOrDefault(text(item,"materialCode","code"),"");
                        if(materialId.isBlank())materialId=UUID.nameUUIDFromBytes((org+":material:"+
                                (text(item,"materialCode","code").isBlank()?datasetId+":"+normal(identity):normal(identity)))
                                .getBytes(StandardCharsets.UTF_8)).toString();
                        code="formula:"+materialId;
                        column.put("materialId",materialId).put("materialCode",text(item,"materialCode","code","materialName","name"))
                                .put("compositionBasis",text(item,"compositionBasis","basis"));
                        if(column.path("compositionBasis").asText().isBlank())column.put("compositionBasis",unit.equals("%")?"MASS_PERCENT":"UNSPECIFIED");
                        column.put("name",label.endsWith("比例")?label:label+"比例");
                    } else {
                        var method=text(item,"testMethod");var stage=text(item,"testStage");
                        var semantic=normal(label)+"|"+normal(unit)+"|"+normal(method)+"|"+normal(stage);
                        code=(domain.equals("testResults")?"result:":"process:")+semantic;
                        column.put("testStage",stage);column.set("testMethods",json.valueToTree(method.isBlank()?List.of():List.of(method)));
                    }
                    column.put("code",code).put("semanticKey",code);
                    if(item.hasNonNull("measurementComponent"))column.set("measurementComponent",item.get("measurementComponent"));
                    if(!item.hasNonNull("measurementComponent") && label.contains("翘曲") && curvature(columns,values,refs,conflicts,issues,row,column,item,raw))continue;
                    boolean categorical=label.matches(".*(?:硬度|附着力|附着性|外观|表干|素材|基材|编号|内容|方式|光源|条件).* ".strip())
                            && !label.contains("邵氏") && !label.contains("固含");
                    if(unit.equals("H")||unit.equals("B")) { unit="";column.put("unit","");categorical=true; }
                    var number=categorical?null:MeasurementValueParser.number(raw.asText(),unit);
                    if(unit.isBlank()&&number!=null){unit=MeasurementValueParser.inferredUnit(raw.asText());column.put("unit",unit);}
                    boolean numeric=formula||number!=null||!unit.isBlank()&&!categorical||label.matches(".*(?:温度|湿度|膜厚|粘度|分子量|伸长率|耐磨|能量|光强|固含).* ".strip());
                    column.put("kind",numeric?"NUMERIC":"CATEGORICAL");
                    if(number!=null&&snapshotPercentage(model,item))number=number.movePointRight(2);
                    var prior=columns.putIfAbsent(code,column);
                    if(prior!=null&&numeric)prior.put("kind","NUMERIC");
                    var locations=refs.withArray(code);item.path("sourceRefs").forEach(ref -> locations.add(ref.deepCopy()));
                    if(MeasurementValueParser.missing(raw.asText())){states.put(code,"MISSING");continue;}
                    if(raw.asText().contains("估")||label.contains("估的")||raw.asText().matches("(?s).*[<>＜＞≤≥～~].*")) {
                        states.put(code,raw.asText().matches("(?s).*[<>＜＞≤≥].*")?"LIMIT":label.contains("估")||raw.asText().contains("估")?"ESTIMATED":"RANGE");
                        issue(issues,row,code,raw,"请确认并填写精确实测值；估计、范围和测试上限不能作为精确结果");continue;
                    }
                    JsonNode value=number!=null?json.getNodeFactory().numberNode(number):raw;
                    if(numeric&&number==null) {issue(issues,row,code,raw,"请将实测数值与观察描述分开填写，并确认单位和判定方法");continue;}
                    if(value.asText().contains("\n")&&categorical){issue(issues,row,code,raw,"包含多个等级或结果，请在指标映射中确认最终结果的判定规则");continue;}
                    put(values,conflicts,code,value,issues,row);
                }
                // Native single-sample fields remain usable. Imported layout keys require an explicit sample association.
                model.path("dynamicValues").fields().forEachRemaining(entry->{
                    var key=entry.getKey();String label=fieldNames.getOrDefault(key,key);JsonNode target=entry.getValue();
                    if(key.startsWith("IMPORT:")) {
                        int split=key.lastIndexOf(":TABLE.COLUMN.");
                        if(split<0)return;
                        var recordKey=key.substring(0,split);
                        boolean own=false;for(var k:group.path("sourceRecordKeys"))if(recordKey.equals(k.asText()))own=true;
                        if(!own)return;label=key.substring(split+14);
                    } else if(groups.size()>1)return;
                    if(metadata(label)||target.isContainerNode()||MeasurementValueParser.missing(target.asText()))return;
                    var n=MeasurementValueParser.number(target.asText(),"");var code="input:"+normal(label);
                    var c=json.createObjectNode().put("code",code).put("semanticKey",code).put("name",label)
                            .put("unit",MeasurementValueParser.inferredUnit(target.asText())).put("kind",n==null?"CATEGORICAL":"NUMERIC");
                    columns.putIfAbsent(code,c);put(values,conflicts,code,n==null?target:json.getNodeFactory().numberNode(n),issues,row);
                });
            }
            for(var domain:List.of("formulaItems","processSteps","testResults"))for(var item:observations(model,domain)) {
                if(groups.stream().anyMatch(group -> belongs(item,group,groups.size())))continue;
                var label=text(item,"targetName","testItem","materialName","labelPath","operation","name","field");
                if(label.isBlank()||metadata(label))continue;
                var unit=cleanUnit(text(item,"unit","rawUnit"));
                var code=(domain.equals("testResults")?"result:":"process:")+normal(label)+"|"+normal(unit)+"|"+normal(text(item,"testMethod"))+"|"+normal(text(item,"testStage"));
                var issue=issues.addObject().put("rowId", "").put("sampleNo",Objects.toString(record.experimentNo(),record.title()))
                        .put("columnCode",code).put("rawValue",raw(model,item,domain.equals("formulaItems")?"ratio":"value").asText())
                        .put("message","没有明确关联的样品或条件组，请在原记录中确认归属");
                issue.set("sourceRefs",item.path("sourceRefs"));
            }
        }
        // A bad scalar must not turn a numerical measurement into a classification target.
        for(var c:columns.values())if("NUMERIC".equals(c.path("kind").asText()))for(var r:rows){
            var v=r.path("values").path(c.path("code").asText());
            if(!v.isMissingNode()&&!v.isNumber()){issue(issues,(ObjectNode)r,c.path("code").asText(),v,"请填写数值，观察描述请单独保存");((ObjectNode)r.path("values")).remove(c.path("code").asText());}
        }
        var array=out.putArray("columns");for(var c:columns.values()) {
            int filled=0;for(var r:rows)if(r.path("values").hasNonNull(c.path("code").asText()))filled++;
            c.put("filledCount",filled);array.add(c);
        }
        return out.put("rowCount",rows.size()).put("sourceDocumentCount",records.size());
    }

    private List<JsonNode> observations(JsonNode model,String domain) {
        var observations=new ArrayList<JsonNode>();
        for(var item:model.path(domain)) {
            if(domain.equals("processSteps")&&!item.has("value")) {
                for(var key:List.of("temperature","duration","pressure","speed"))if(item.hasNonNull(key)) {
                    var copy=(ObjectNode)item.deepCopy();copy.set("value",item.get(key));
                    copy.put("name",text(item,"stepName","name")+"·"+key).put("field",key);observations.add(copy);
                }
            } else observations.add(item);
        }
        return observations;
    }

    public int sampleCount(JsonNode model) { return groups(model).size(); }

    private boolean curvature(Map<String,ObjectNode> columns,ObjectNode values,ObjectNode refs,Set<String> conflicts,
                              ArrayNode issues,ObjectNode row,ObjectNode original,JsonNode item,JsonNode raw) {
        var text=raw.asText().strip();
        var matcher=java.util.regex.Pattern.compile("^(反(?:向)?翘(?:曲)?|正(?:向)?翘(?:曲)?|卷曲)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(cm|mm)$").matcher(text);
        boolean flat=text.matches("平整[，,、]?不翘(?:曲)?|不翘(?:曲)?|平整");
        if(!flat&&!matcher.matches())return false;
        var heightCode=original.path("code").asText()+":height";var directionCode=original.path("code").asText()+":direction";
        var height=original.deepCopy().put("code",heightCode).put("semanticKey",heightCode).put("name",original.path("name").asText()+"高度")
                .put("kind","NUMERIC").put("unit","cm").put("measurementComponent","HEIGHT");
        var direction=original.deepCopy().put("code",directionCode).put("semanticKey",directionCode).put("name",original.path("name").asText()+"方向")
                .put("kind","CATEGORICAL").put("unit","").put("measurementComponent","DIRECTION");
        columns.putIfAbsent(heightCode,height);columns.putIfAbsent(directionCode,direction);
        var number=flat?java.math.BigDecimal.ZERO:new java.math.BigDecimal(matcher.group(2));
        if(!flat&&matcher.group(3).equals("mm"))number=number.movePointLeft(1);
        var label=flat?"平整":matcher.group(1).startsWith("反")?"反翘":matcher.group(1).startsWith("正")?"正翘":"卷曲";
        refs.set(heightCode,item.path("sourceRefs"));refs.set(directionCode,item.path("sourceRefs"));
        put(values,conflicts,heightCode,json.getNodeFactory().numberNode(number),issues,row);
        put(values,conflicts,directionCode,json.getNodeFactory().textNode(label),issues,row);return true;
    }

    private List<JsonNode> groups(JsonNode model) {
        var groups=new LinkedHashMap<String,JsonNode>();
        for(var group:model.path("sampleGroups"))groups.put(text(group,"sampleKey","sourceGroupKey"),group);
        for(var domain:List.of("formulaItems","processSteps","testResults"))for(var item:model.path(domain)) {
            var key=text(item,"sampleKey","sourceGroupKey");
            if(key.isBlank()&&!text(item,"sourceIdentity").isBlank())key=text(item,"sourceRecordKey");
            if(!key.isBlank()&&!groups.containsKey(key)) {
                var g=json.createObjectNode().put("sampleKey",key).put("sourceContextKey",text(item,"sourceContextKey"))
                        .put("sourceIdentity",text(item,"sourceIdentity"));
                g.putArray("sourceRecordKeys").add(text(item,"sourceRecordKey"));groups.put(key,g);
            }
        }
        return groups.isEmpty()?List.of(json.createObjectNode()):new ArrayList<>(groups.values());
    }
    private boolean belongs(JsonNode item,JsonNode group,int count) {
        var key=text(item,"sampleKey","sourceGroupKey");var selected=text(group,"sampleKey","sourceGroupKey");
        if(!key.isBlank())return key.equals(selected);
        var record=text(item,"sourceRecordKey");
        for(var k:group.path("sourceRecordKeys"))if(!record.isBlank()&&record.equals(k.asText()))return true;
        if(!text(item,"sourceIdentity").isBlank())return false;
        var context=text(item,"sourceContextKey");
        if(!context.isBlank())return context.equals(text(group,"sourceContextKey"));
        return count==1;
    }
    private JsonNode raw(JsonNode model,JsonNode item,String key) {
        for(var ref:item.path("sourceRefs")) {
            if(ref.hasNonNull("rawNumericValue"))return ref.get("rawNumericValue");
            var cell=snapshotCell(model,ref);if(cell.path("v").isNumber())return cell.get("v");
            if(ref.hasNonNull("displayValue"))return ref.get("displayValue");
            if(cell.hasNonNull("v"))return cell.get("v");
        }
        for(var candidate:List.of("rawValue",key,"parsedValue","result","amount"))if(item.hasNonNull(candidate)&&!item.get(candidate).isContainerNode())return item.get(candidate);
        return json.nullNode();
    }
    private JsonNode snapshotCell(JsonNode model,JsonNode ref) {
        return model.path("documentSnapshot").path("sheets").path(ref.path("sheetId").asText())
                .path("cellData").path(Integer.toString(ref.path("row").asInt()-1)).path(Integer.toString(ref.path("column").asInt()-1));
    }
    private boolean snapshotPercentage(JsonNode model,JsonNode item) {
        for(var ref:item.path("sourceRefs")) {
            if(ref.hasNonNull("rawNumericValue"))return ref.path("numberFormat").asText().contains("%");
            if(snapshotCell(model,ref).path("v").isNumber())return ref.path("numberFormat").asText().contains("%");
            if(ref.hasNonNull("displayValue"))return false;
        }return false;
    }
    private void put(ObjectNode values,Set<String> conflicts,String code,JsonNode value,ArrayNode issues,ObjectNode row) {
        if(conflicts.contains(code))return;
        if(values.has(code)&&!values.get(code).equals(value)) {issue(issues,row,code,json.getNodeFactory().textNode(values.get(code).asText()+" / "+value.asText()),"同一样品存在冲突记录，请确认原始结果");values.remove(code);conflicts.add(code);}
        else values.set(code,value.deepCopy());
    }
    private void issue(ArrayNode issues,ObjectNode row,String code,JsonNode raw,String message) {
        var issue=issues.addObject().put("rowId",row.path("id").asText()).put("sampleNo",row.path("label").asText())
                .put("columnCode",code).put("rawValue",raw.asText()).put("message",message);
        issue.set("sourceRefs",row.path("sourceRefs").path(code));
    }
    private boolean metadata(String label) { return label.matches(".*(?:备注|补充说明|合计|总计|实验编号|样品编号|配方编号).* ".strip()); }
    private String cleanUnit(String unit) {
        var value=MeasurementValueParser.unit(unit);
        return value.matches("[%°℃A-Za-z0-9μ²/·.Ω]+")?value:"";
    }
    static String normal(String text) { return Normalizer.normalize(text,Normalizer.Form.NFKC).strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT); }
    static String text(JsonNode node,String...keys) {for(var key:keys){var value=node.path(key);if(value.isValueNode()&&!value.isNull()&&!value.asText().isBlank())return value.asText().strip();}return "";}
}
