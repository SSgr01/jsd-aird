package com.jsd.aird.shared.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Shared deterministic execution of the two unified templates. All coordinates are zero based. */
public final class LabWorkbook {
    public static final String META_SHEET="_JSD_LAB_META";
    public static final String DEFINITION="labTemplate";
    private LabWorkbook() {}
    public static JsonNode definition(JsonNode snapshot) {
        if(snapshot==null)return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        for(var sheet:snapshot.path("sheets"))if(sheet.path("custom").path(DEFINITION).isObject())return sheet.path("custom").path(DEFINITION);
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
    public static boolean supported(JsonNode snapshot) {return definition(snapshot).path("version").asInt()==1;}
    public static void seedStructure(ObjectMapper json,JsonNode snapshot,ObjectNode instance,boolean fresh) {
        if(supported(snapshot))LabStructureAdapter.seed(json,snapshot,instance,fresh);
    }
    public static ObjectNode sheet(JsonNode snapshot) {
        for(var s:snapshot.path("sheets"))if(s.path("custom").path(DEFINITION).isObject())return (ObjectNode)s;
        return null;
    }
    public static String value(JsonNode sheet,int row,int col) {
        var cell=sheet.path("cellData").path(""+row).path(""+col);
        if(cell.hasNonNull("v"))return LabValues.text(cell.get("v"));
        return cell.path("p").path("body").path("dataStream").asText("").strip();
    }
    public static ObjectNode cell(ObjectNode sheet,int row,int col) {
        var data=sheet.withObject("/cellData");
        var cells=data.path(""+row) instanceof ObjectNode existing?existing:data.putObject(""+row);
        return cells.path(""+col) instanceof ObjectNode existing?existing:cells.putObject(""+col);
    }
    private static String normalized(String s) {return s.replaceAll("[\\s　]","").replace('：',':');}
    public static String id(String seed,String key) {return UUID.nameUUIDFromBytes((seed+":"+key).getBytes(StandardCharsets.UTF_8)).toString();}
    public static String address(int row,int col) {var b=new StringBuilder();for(int n=col+1;n>0;n=(n-1)/26)b.insert(0,(char)('A'+(n-1)%26));return b.append(row+1).toString();}
    private static ObjectNode mark(ObjectNode cell) {return cell.withObject("/custom/lab");}
    private static ObjectNode configuration(ObjectNode sheet,JsonNode def) {sheet.withObject("/custom").set(DEFINITION,def.deepCopy());return (ObjectNode)sheet.path("custom").path(DEFINITION);}

    /** Rebinds ordinary or compacted Excel by confirmed label/section evidence, never by filename. */
    public static ObjectNode prepare(ObjectMapper json,JsonNode source,JsonNode baseline,String seed) {
        var copy=(ObjectNode)source.deepCopy();
        LabStructureAdapter.apply(json,copy);
        var authoritative=definition(baseline);
        if(!authoritative.isObject())return copy;
        var incoming=definition(copy);
        if(incoming.isObject()&&!incoming.path("templateCode").asText().equals(authoritative.path("templateCode").asText()))throw invalid("上传文件与选择的实验模板不一致");
        if(incoming.path("presentation").asText().equals("RESULT"))return prepare(json,expand(json,copy,baseline,seed),baseline,seed);
        ObjectNode target=sheet(copy);
        if(target==null) {
            for(var s:copy.path("sheets"))if(s instanceof ObjectNode obj&&!s.path("name").asText().startsWith("_JSD")){target=obj;break;}
        }
        if(target==null)throw invalid("实验工作表不存在");
        var def=(ObjectNode)authoritative.deepCopy();
        var fields=(ObjectNode)def.path("fields");
        if(incoming.path("fields").isObject())incoming.path("fields").fields().forEachRemaining(entry->{
            if(fields.has(entry.getKey())||!entry.getKey().matches("(?:LOCAL_|INSTANCE_LOCAL[.])[A-Za-z0-9_.]{1,160}"))return;
            var local=(ObjectNode)entry.getValue().deepCopy();
            if(!Set.of("TEXT","NUMBER","MIXED","HARDNESS","ADHESION").contains(local.path("resultKind").asText()))throw invalid("临时字段结果类型无效");
            local.put("local",true).put("trainingEligible",false);
            fields.set(entry.getKey(),local);
        });
        configuration(target,def);
        int maxRow=Math.max(def.path("lastRow").asInt(),target.path("rowCount").asInt());
        var cells=target.path("cellData");
        var sectionByRow=new HashMap<Integer,String>();var current="";
        for(int r=0;r<maxRow;r++) {
            var a=value(target,r,0);var meta=cells.path(""+r).path("0").path("custom").path("lab");
            if(meta.path("role").asText().equals("GROUP"))current=meta.path("groupId").asText();
            else for(var region:def.path("regions"))if(!a.isBlank()&&normalized(a).equals(normalized(region.path("label").asText()))) {
                current=region.path("id").asText();mark(cell(target,r,0)).put("role","GROUP").put("groupId",current);break;
            }
            sectionByRow.put(r,current);
        }
        // Import fallback: anchors are labels + business group, not absolute row numbers.
        for(int r=0;r<maxRow;r++) {
            var label=normalized(value(target,r,1));
            if(label.isBlank())label=normalized(value(target,r,0));
            var previousLabel=target.path("cellData").path(""+r).path("1").path("custom").path("lab");
            if(previousLabel.path("role").asText().equals("LABEL")) {
                var previousField=fields.path(previousLabel.path("fieldCode").asText());
                if(label.equals(normalized(previousField.path("label").asText())))continue;
                if(label.isBlank()) {
                    if(previousField.path("supplemental").asBoolean())continue;
                    for(var c:target.path("cellData").path(""+r))if(c instanceof ObjectNode obj&&obj.path("custom").isObject())((ObjectNode)obj.path("custom")).remove("lab");
                    continue;
                }
                if(!Set.of("resin","coating","film","appearance","results").contains(sectionByRow.get(r)))continue;
            }
            if(label.isBlank())continue;
            boolean matched=false;
            var iterator=fields.fields();
            while(iterator.hasNext()) {
                var e=iterator.next();var f=e.getValue();
                if(!"SAMPLE".equals(f.path("scope").asText())||!normalized(f.path("label").asText()).equals(label))continue;
                if(!f.path("groupId").asText().equals(sectionByRow.get(r))&&!f.path("semantic").asText().equals("SOURCE_IDENTITY")&&!e.getKey().equals("formula.total"))continue;
                mark(cell(target,r,1)).put("role","LABEL").put("fieldCode",e.getKey());
                matched=true;
                break;
            }
            var group=sectionByRow.get(r);
            if(!matched&&Set.of("resin","coating","film","appearance","results").contains(group)&&!value(target,r,1).isBlank()) {
                var code="LOCAL_"+id(seed,"field:"+group+":"+label).replace("-","");
                var local=fields.putObject(code).put("fieldCode",code).put("label",value(target,r,1)).put("groupId",group).put("scope","SAMPLE").put("domain","TEST").put("semantic","VALUE").put("resultKind","TEXT").put("unit","").put("local",true).put("confirmed",false).put("trainingEligible",false);
                describeLocalLabel(local,value(target,r,1),fields);
                mark(cell(target,r,1)).put("role","LABEL").put("fieldCode",code);
            }
        }
        // Distinct physical rows must never share a binding, even when their
        // visible labels are identical. Preserve the standard code separately.
        var seenLabels=new HashSet<String>();
        for(int r=0;r<maxRow;r++) {
            var meta=target.path("cellData").path(""+r).path("1").path("custom").path("lab");
            if(!meta.path("role").asText().equals("LABEL"))continue;
            var code=meta.path("fieldCode").asText();var original=fields.path(code);
            if(!seenLabels.add(code)&&original.isObject()) {
                ((ObjectNode)original).put("trainingEligible",false);
                var localCode="LOCAL_"+id(seed,"duplicate:"+code+":"+r).replace("-","");
                var duplicate=(ObjectNode)original.deepCopy();
                duplicate.put("fieldCode",localCode).put("local",true).put("confirmed",false).put("trainingEligible",false).put("duplicateOf",code);
                fields.set(localCode,duplicate);
                mark(cell(target,r,1)).put("role","LABEL").put("fieldCode",localCode);
            }
        }
        for(var field:fields)if(field.hasNonNull("duplicateOf")&&fields.path(field.path("duplicateOf").asText()) instanceof ObjectNode original)original.put("trainingEligible",false);
        // Unlabelled sample-column text is retained as a per-column note.
        // The blank title cell remains untouched in the user's workbook.
        int noteFirst=def.path("firstSampleColumn").asInt(2);
        for(int r=6;r<maxRow;r++) {
            if(!Set.of("resin","coating","film","appearance","results").contains(sectionByRow.get(r)))continue;
            if(!value(target,r,1).isBlank()||target.path("cellData").path(""+r).path("1").path("custom").path("lab").has("role"))continue;
            boolean recorded=false;
            for(var col:iterable(target.path("cellData").path(""+r).fields()))if(Integer.parseInt(col.getKey())>=noteFirst&&!col.getValue().hasNonNull("f")&&LabValues.recorded(LabValues.text(col.getValue().path("v"))))recorded=true;
            if(!recorded)continue;
            var code="LOCAL_"+id(seed,"note:"+sectionByRow.get(r)+":"+r).replace("-","");
            fields.putObject(code).put("fieldCode",code).put("label","补充说明").put("groupId",sectionByRow.get(r)).put("scope","SAMPLE").put("domain","OTHER").put("semantic","DYNAMIC_VALUE").put("resultKind","TEXT").put("unit","").put("local",true).put("confirmed",true).put("trainingEligible",false).put("supplemental",true);
            mark(cell(target,r,1)).put("role","LABEL").put("fieldCode",code);
        }
        // Global anchors also survive inserted columns via native cell custom metadata.
        for(var entry:iterable(fields.fields())) {
            var f=entry.getValue();if(!f.path("scope").asText().equals("EXPERIMENT"))continue;
            if(findField(target,entry.getKey())!=null)continue;
            int r=f.path("row").asInt(), c=f.path("column").asInt();
            if(normalized(value(target,r,Math.max(0,c-1))).equals(normalized(f.path("label").asText())))mark(cell(target,r,c)).put("role","VALUE").put("fieldCode",entry.getKey());
            if(findField(target,entry.getKey())==null) {
                int found=-1;
                for(int row=0;row<maxRow;row++)if(normalized(value(target,row,0)).equals(normalized(f.path("label").asText()))) {
                    if(found>=0){found=-2;break;}found=row;
                }
                if(found>=0)mark(cell(target,found,1)).put("role","VALUE").put("fieldCode",entry.getKey());
            }
        }
        int first=def.path("firstSampleColumn").asInt(2),end=first;
        for(var row:iterable(target.path("cellData").fields()))for(var c:iterable(row.getValue().fields())) {
            int column=Integer.parseInt(c.getKey());
            if(Integer.parseInt(row.getKey())>5&&column>=first&&!LabValues.text(c.getValue().path("v")).isBlank())end=Math.max(end,column);
        }
        // Keep the physical editing surface, including explicitly added blank groups.
        for(var c:iterable(target.path("columnData").fields()))if(c.getValue().path("custom").path("lab").has("sampleKey"))end=Math.max(end,Integer.parseInt(c.getKey()));
        end=Math.max(end,def.path("initialLastSampleColumn").asInt(7));
        var seenKeys=new HashSet<String>();
        for(int c=first;c<=end;c++) {
            var cm=target.withObject("/columnData/"+c+"/custom/lab");
            var key=cm.path("sampleKey").asText(id(seed,"sample:"+c));
            if(!seenKeys.add(key)){key=id(seed,"new-sample:"+c+":"+key);seenKeys.add(key);}
            cm.put("sampleKey",key);
        }
        for(var row:iterable(target.path("cellData").fields())) {
            int r=Integer.parseInt(row.getKey());
            var labelMeta=row.getValue().path("1").path("custom").path("lab");
            var code=labelMeta.path("fieldCode").asText();var f=fields.path(code);
            if(f.isMissingNode()||!f.path("scope").asText().equals("SAMPLE"))continue;
            for(int c=first;c<=end;c++)mark(cell(target,r,c)).put("role","VALUE").put("fieldCode",code);
        }
        var identity=findField(target,"sample.experimentNo");
        var total=findField(target,"formula.total");
        if(identity==null||total==null||identity[0]>=total[0])throw invalid("无法确认实验编号与配方合计边界，请恢复对应行或确认选择的模板");
        var lineIds=new HashSet<String>();
        for(int r=identity[0]+1;r<total[0];r++) {
            var m=mark(cell(target,r,1));
            var line=m.path("lineId").asText(id(seed,"line:"+r));
            if(!lineIds.add(line)){line=id(seed,"new-line:"+r+":"+line);lineIds.add(line);}
            m.put("role","MATERIAL_CODE").put("lineId",line);
            for(int c=first;c<=end;c++)mark(cell(target,r,c)).put("role","MATERIAL_QTY").put("lineId",line);
        }
        def.put("lastSampleColumn",end).put("formulaStartRow",identity[0]+1).put("formulaEndRow",total[0]-1).put("formulaTotalRow",total[0]);
        var last=0;for(var row:iterable(target.path("cellData").fields()))last=Math.max(last,Integer.parseInt(row.getKey()));
        def.put("lastRow",last);
        for(var entry:iterable(fields.fields()))if(entry.getValue() instanceof ObjectNode field) {
            var at=findField(target,entry.getKey());
            if(at!=null)field.put("row",at[0]).put("column",field.path("scope").asText().equals("SAMPLE")?first:at[1]);
        }
        configuration(target,def);
        var print=target.withObject("/custom/print");print.put("lastRow",last).put("lastColumn",end);
        for(int c=first;c<=end;c++) {
            var result=cell(target,total[0],c);
            if(!result.hasNonNull("f")&&value(target,total[0],c).isBlank())result.put("f",massFormula(c,identity[0]+1,total[0]-1));
        }
        LabStructureAdapter.apply(json,copy);
        return copy;
    }
    public static int[] findField(JsonNode sheet,String code) {
        for(var row:iterable(sheet.path("cellData").fields()))for(var c:iterable(row.getValue().fields()))if(c.getValue().path("custom").path("lab").path("fieldCode").asText().equals(code))return new int[]{Integer.parseInt(row.getKey()),Integer.parseInt(c.getKey())};
        return null;
    }
    private static void describeLocalLabel(ObjectNode local,String title,JsonNode fields) {
        var matcher=Pattern.compile("^(.*?)\\s*[（(]([^（）()]*)[）)]\\s*$").matcher(title.strip());
        String stem=title.strip(),condition="",unit="";
        if(matcher.matches()) {
            stem=matcher.group(1).strip();var suffix=matcher.group(2).strip();
            if(Set.of("°","℃","%","g","s","min","h","nm","μm","mN/m","cP","GU").contains(suffix))unit=suffix;
            else condition=suffix;
        }
        String base=stem;
        for(var field:fields) {
            if(field==local||field.path("local").asBoolean()||!field.path("groupId").equals(local.path("groupId")))continue;
            var standard=field.path("label").asText().replaceAll("\\s*[（(][^（）()]*[）)]\\s*$","").strip();
            if(!standard.isBlank()&&stem.endsWith(standard)&&standard.length()<stem.length()) {
                base=standard;var prefix=stem.substring(0,stem.length()-standard.length()).strip();
                condition=condition.isBlank()?prefix:prefix+"；"+condition;break;
            }
        }
        local.put("baseMetric",base).put("testCondition",condition).put("unit",unit);
    }
    private static <T> Iterable<T> iterable(Iterator<T> iterator){return ()->iterator;}
    private static String pattern(JsonNode snapshot,JsonNode cell) {return cell.path("s").isTextual()?snapshot.path("styles").path(cell.path("s").asText()).path("n").path("pattern").asText(""):cell.path("s").path("n").path("pattern").asText("");}

    /** Snapshot is the editing source; all facts are rebuilt together on the server. */
    public static ObjectNode extract(ObjectMapper json,JsonNode snapshot,JsonNode previous,String seed) {
        var model=(ObjectNode)previous.deepCopy();var sheet=sheet(snapshot);if(sheet==null)return model;
        var def=definition(snapshot);var fields=def.path("fields");
        var groups=json.createArrayNode();var formula=json.createArrayNode();var processes=json.createArrayNode();var tests=json.createArrayNode();var issues=json.createArrayNode();
        var dynamic=json.createObjectNode();var instance=json.createObjectNode().put("version",1).put("templateCode",def.path("templateCode").asText());
        var uniqueFields=new HashMap<String,Integer>();
        for(var row:iterable(sheet.path("cellData").fields())) {
            var meta=row.getValue().path("1").path("custom").path("lab");var code=meta.path("fieldCode").asText();var field=fields.path(code);int r=Integer.parseInt(row.getKey());
            if(!meta.path("role").asText().equals("LABEL"))continue;
            if(uniqueFields.putIfAbsent(code,r)!=null)issues.addObject().put("code","DUPLICATE_FIELD").put("address",address(r,1)).put("message","同一指标出现多行，请确认不同的测试条件或删除重复行");
            if(field.hasNonNull("duplicateOf"))issues.addObject().put("code","DUPLICATE_FIELD").put("address",address(r,1)).put("message","同名测试项目已分别保留，请核对测试条件");
            if(field.path("local").asBoolean()&&!field.path("confirmed").asBoolean())issues.addObject().put("code","FIELD_UNCONFIRMED").put("address",address(r,1)).put("message","新增测试项目待确认类型和单位");
            var label=value(sheet,r,1);if(!label.isBlank()&&!normalized(label).equals(normalized(field.path("label").asText())))issues.addObject().put("code","FIELD_LABEL_CHANGED").put("address",address(r,1)).put("message","项目名称已变化，请按新项目确认字段含义");
        }
        var order=instance.putArray("sampleOrder");var lines=instance.putArray("materialLines");var bindings=instance.putArray("bindings");
        var first=def.path("firstSampleColumn").asInt(2);var end=def.path("lastSampleColumn").asInt(7);var sid=sheet.path("id").asText();
        for(var row:iterable(sheet.path("cellData").fields()))for(var column:iterable(row.getValue().fields())) {
            int r=Integer.parseInt(row.getKey()),c=Integer.parseInt(column.getKey());var cell=column.getValue();var role=cell.path("custom").path("lab").path("role").asText();
            if(r>=6&&c>=first&&LabValues.recorded(value(sheet,r,c))&&!cell.hasNonNull("f")&&!Set.of("VALUE","MATERIAL_QTY").contains(role))issues.addObject().put("code","UNBOUND_VALUE").put("address",address(r,c)).put("message","此处有内容但没有对应项目，请填写项目名称或确认字段位置");
        }
        var active=new HashSet<Integer>();
        for(int c=first;c<=end;c++) {
            boolean populated=false;
            for(var row:iterable(sheet.path("cellData").fields())) {
                int r=Integer.parseInt(row.getKey());var cell=row.getValue().path(""+c);var meta=cell.path("custom").path("lab");
                var field=fields.path(meta.path("fieldCode").asText());
                if(!cell.hasNonNull("f")&&!value(sheet,r,c).isBlank()&&(meta.path("role").asText().equals("MATERIAL_QTY")||meta.path("role").asText().equals("VALUE")&&field.path("scope").asText().equals("SAMPLE")&&!field.path("semantic").asText().equals("SOURCE_IDENTITY")))populated=true;
            }
            if(!populated)continue;
            active.add(c);var key=sheet.path("columnData").path(""+c).path("custom").path("lab").path("sampleKey").asText();
            var no=findField(sheet,"sample.experimentNo");var label=no==null?"":value(sheet,no[0],c);
            groups.addObject().put("sampleKey",key).put("sourceGroupKey",key).put("logicalSampleKey",key).put("sourceIdentity",label).put("sourceIdentityType","EXPERIMENT_NO").put("sourceContextKey",seed).put("column",c);
            order.add(key);
        }
        for(var row:iterable(sheet.path("cellData").fields())) {
            int r=Integer.parseInt(row.getKey());
            for(var col:iterable(row.getValue().fields())) {
                int c=Integer.parseInt(col.getKey());var cell=col.getValue();var meta=cell.path("custom").path("lab");
                if(!meta.path("role").asText().equals("VALUE"))continue;
                var code=meta.path("fieldCode").asText();var field=fields.path(code);if(!field.isObject())continue;
                var sample=field.path("scope").asText().equals("SAMPLE");
                var raw=cell.path("v");
                var binding=json.createObjectNode().put("bindingId",id(seed,"binding:"+code+":"+c)).put("fieldCode",code).put("dataPath","/fields/"+code).put("role","FIELD").put("mappingKind","SCALAR").put("locatorType","CELL_RANGE").put("syncDirection","TWO_WAY").put("primaryBinding",true).put("bindingStatus","VALID");
                binding.set("locator",json.createObjectNode().put("sheetId",sid).put("sheetName",sheet.path("name").asText()).put("address",address(r,c)).put("valueRange",address(r,c)));
                if(!sample||active.contains(c))bindings.add(binding);
                if(!sample) {
                    dynamic.withObject("/fields").set(code,raw.isMissingNode()?json.nullNode():raw.deepCopy());
                    if(Set.of("basic.temperature","basic.humidity").contains(code))for(var group:groups) {
                        var common=LabValues.parse(json,raw,field,pattern(snapshot,cell));var key=group.path("sampleKey").asText();
                        common.put("itemId",id(seed,code+":"+key)).put("sampleKey",key).put("logicalSampleKey",key).put("operation",field.path("label").asText()).put("sourceContextKey",seed).put("fieldCode",code);
                        common.set("sourceRefs",json.createArrayNode().add(json.createObjectNode().put("sheetId",sid).put("cellRange",address(r,c))));processes.add(common);
                    }
                    continue;
                }
                if(!active.contains(c)||field.path("valueSource").asText().equals("FORMULA"))continue;
                var parsed=LabValues.parse(json,raw,field,pattern(snapshot,cell));
                if(parsed.path("parseStatus").asText().equals("CONFLICT")||value(sheet,r,c).matches("#(?:REF!|DIV/0!|VALUE!|N/A|NAME\\?|NUM!|SPILL!)"))issues.addObject().put("code","VALUE_CONFLICT").put("address",address(r,c)).put("message","单位冲突或公式错误，请核对原始填写内容");
                var key=sheet.path("columnData").path(""+c).path("custom").path("lab").path("sampleKey").asText();
                boolean duplicate=uniqueFields.getOrDefault(code,r)!=r;
                parsed.put("itemId",id(seed,code+":"+key+(duplicate?":duplicate:"+r:""))).put("sampleKey",key).put("sourceGroupKey",key).put("logicalSampleKey",key).put("sourceContextKey",seed).put("fieldCode",code).put("testItem",field.path("label").asText()).put("operation",field.path("label").asText()).put("testMethod",field.path("method").asText("")).put("testStage",field.path("groupId").asText()).put("trainingEligible",!field.path("local").asBoolean()&&!duplicate&&field.path("trainingEligible").asBoolean(true));
                for(var property:List.of("baseMetric","testCondition","duplicateOf","supplemental"))if(field.has(property))parsed.set(property,field.path(property).deepCopy());
                parsed.set("sourceRefs",json.createArrayNode().add(json.createObjectNode().put("sheetId",sid).put("cellRange",address(r,c)).put("sampleKey",key)));
                if(field.path("domain").asText().equals("TEST"))tests.add(parsed);else processes.add(parsed);
            }
        }
        int start=def.path("formulaStartRow").asInt(),last=def.path("formulaEndRow").asInt();
        var materialField=json.createObjectNode().put("semantic","ACTUAL_QTY").put("resultKind","NUMBER").put("unit","g");
        var totals=new HashMap<Integer,java.math.BigDecimal>();var complete=new HashMap<Integer,Boolean>();
        active.forEach(c->{totals.put(c,java.math.BigDecimal.ZERO);complete.put(c,true);});
        for(int r=start;r<=last;r++) {
            var code=value(sheet,r,1);var lineId=sheet.path("cellData").path(""+r).path("1").path("custom").path("lab").path("lineId").asText();
            boolean any=false;for(int c:active)any|=LabValues.recorded(value(sheet,r,c));
            if(code.isBlank()&&!any)continue;
            lines.addObject().put("lineId",lineId).put("materialCode",code).put("sequence",r-start+1);
            if(code.isBlank())issues.addObject().put("code","MATERIAL_CODE_MISSING").put("address",address(r,1)).put("message","用量对应的物料编码未填写");
            for(int c:active) {
                var entry=LabValues.parse(json,sheet.path("cellData").path(""+r).path(""+c).path("v"),materialField,"");
                var key=sheet.path("columnData").path(""+c).path("custom").path("lab").path("sampleKey").asText();
                var valid=code.length()>0&&entry.path("value").isNumber()&&!entry.path("parseStatus").asText().equals("CONFLICT");
                if(!valid){
                    complete.put(c,false);
                    // A bound blank cell is an unfinished observation, not a parsing error.
                    if(!code.isBlank()&&entry.path("valueStatus").asText().equals("RECORDED"))
                        issues.addObject().put("code","FORMULA_INCOMPLETE").put("address",address(r,c)).put("message","物料编码或g用量待确认");
                }
                else totals.put(c,totals.get(c).add(entry.path("value").decimalValue()));
                entry.set("actualQty",entry.path("value").deepCopy());
                entry.put("itemId",id(seed,lineId+":"+key)).put("lineId",lineId).put("feedSequence",r-start+1).put("materialCode",code).put("materialName",code).put("sampleKey",key).put("sourceGroupKey",key).put("logicalSampleKey",key).put("sourceContextKey",seed).put("compositionBasis","MASS_AMOUNT");
                entry.set("sourceRefs",json.createArrayNode().add(json.createObjectNode().put("sheetId",sid).put("cellRange",address(r,c)).put("sampleKey",key)));
                formula.add(entry);
            }
        }
        for(var f:formula) {
            for(var group:groups)if(group.path("sampleKey").equals(f.path("sampleKey"))) {
                int c=group.path("column").asInt();
                ((ObjectNode)f).put("formulaComplete",complete.get(c));
                if(!complete.get(c))((ObjectNode)f).put("trainingEligible",false);
                if(complete.get(c)&&totals.get(c).signum()>0&&f.path("actualQty").isNumber())((ObjectNode)f).put("massPercent",f.path("actualQty").decimalValue().multiply(java.math.BigDecimal.valueOf(100)).divide(totals.get(c),12,java.math.RoundingMode.HALF_UP));
            }
        }
        for(var group:groups) {
            int c=group.path("column").asInt();
            var summary=(ObjectNode)group;
            summary.put("formulaComplete",complete.get(c));
            summary.put("filledAmountSubtotal",totals.get(c));
            if(complete.get(c)&&lines.size()>0)summary.put("calculatedTotal",totals.get(c));
        }
        int totalRow=def.path("formulaTotalRow").asInt();
        for(int c=first;c<=end;c++) {
            var result=cell(sheet,totalRow,c);
            if(result.hasNonNull("f")) {
                if(active.contains(c)&&complete.get(c)&&lines.size()>0)result.put("v",totals.get(c));else result.put("v","");
            }
        }
        instance.set("definition",def.deepCopy());instance.set("issues",issues);
        var generic=com.jsd.aird.shared.excel.WorkbookStructure.instance(snapshot);
        if(generic.isObject()) {
            var merged=(ObjectNode)generic.deepCopy();
            var allIssues=(ArrayNode)merged.path("issues").deepCopy();allIssues.addAll(issues);merged.set("issues",allIssues);
            merged.set("definition",def.deepCopy());merged.set("sampleOrder",order);merged.set("materialLines",lines);merged.set("businessBindings",bindings);
            instance=merged;
        }
        model.set("templateInstance",instance);model.set("sourceGroups",groups);model.set("sampleGroups",groups.deepCopy());model.set("formulaItems",formula);model.set("processSteps",processes);model.set("testResults",tests);model.set("dynamicValues",dynamic);model.set("documentSnapshot",snapshot.deepCopy());
        model.put("documentFormat","excel");return model;
    }

    /** Physically compacts a disposable projection. Stored input is never edited. */
    public static Presentation present(ObjectMapper json,JsonNode source) {
        if(!supported(source))return new Presentation(source.deepCopy(),json.createObjectNode());
        var out=(ObjectNode)source.deepCopy();var sheet=sheet(out);var def=definition(out);var fields=def.path("fields");
        int first=def.path("firstSampleColumn").asInt(2),end=def.path("lastSampleColumn").asInt(7);
        var keepColumns=new TreeSet<Integer>();keepColumns.add(0);keepColumns.add(1);
        var keepRows=new TreeSet<Integer>();var rows=sheet.path("cellData");
        for(var row:iterable(rows.fields()))for(var col:iterable(row.getValue().fields())) {
            int r=Integer.parseInt(row.getKey()),c=Integer.parseInt(col.getKey());var field=fields.path(col.getValue().path("custom").path("lab").path("fieldCode").asText());
            if(r>=6&&c>=first&&c<=end&&!col.getValue().hasNonNull("f")&&!field.path("scope").asText().equals("EXPERIMENT")&&!field.path("semantic").asText().equals("SOURCE_IDENTITY")&&LabValues.recorded(value(sheet,r,c)))keepColumns.add(c);
        }
        if(!def.path("stackedHeader").asBoolean()&&keepColumns.size()<8) {
            // The original second row holds four shared fields across A:H.
            // Fewer sample columns must not delete the shared date/ambient values.
            var original=sheet.path("cellData").path("1").deepCopy();
            insertRows(json,sheet,2,3);
            for(int r=1;r<=4;r++) {
                var row=json.createObjectNode();var label=original.path(""+((r-1)*2));var val=original.path(""+((r-1)*2+1));
                if(label.isObject())row.set("0",label.deepCopy());if(val.isObject())row.set("1",val.deepCopy());
                sheet.withObject("/cellData").set(""+r,row);
                if(sheet.path("rowData").has("1"))sheet.withObject("/rowData").set(""+r,sheet.path("rowData").path("1").deepCopy());
                if(end>1)((ArrayNode)sheet.path("mergeData")).addObject().put("startRow",r).put("endRow",r).put("startColumn",1).put("endColumn",end);
            }
            ((ObjectNode)def).put("stackedHeader",true);
            sheet.withObject("/custom/print").put("repeatFirstRow",0).put("repeatLastRow",4);
            var projected=present(json,out);var locations=(ObjectNode)projected.locationMap();var mapped=json.createObjectNode();var rowsAfter=locations.path("rows");
            for(var row:iterable(rowsAfter.fields())){int r=Integer.parseInt(row.getKey());if(r<2)mapped.set(row.getKey(),row.getValue());else if(r>=5)mapped.set(""+(r-3),row.getValue());}
            locations.set("rows",mapped);return projected;
        }
        for(var row:iterable(rows.fields())) {
            int r=Integer.parseInt(row.getKey());boolean keep=false;
            for(var col:iterable(row.getValue().fields())) {
                int c=Integer.parseInt(col.getKey());var cell=col.getValue();var meta=cell.path("custom").path("lab");var role=meta.path("role").asText();
                if(role.equals("VALUE")) {
                    var field=fields.path(meta.path("fieldCode").asText());
                    if(field.path("scope").asText().equals("EXPERIMENT"))keep|=LabValues.recorded(value(sheet,r,c))||field.path("required").asBoolean();
                    else if(keepColumns.contains(c))keep|=LabValues.recorded(value(sheet,r,c));
                } else if(role.equals("MATERIAL_QTY")&&keepColumns.contains(c))keep|=LabValues.recorded(value(sheet,r,c));
                else if(c>=first&&keepColumns.contains(c)&&!cell.hasNonNull("f"))keep|=LabValues.recorded(value(sheet,r,c));
            }
            // Identity labels remain when a group contains real content.
            if(keepColumns.size()>2&&row.getValue().path("1").path("custom").path("lab").path("fieldCode").asText().equals("sample.experimentNo"))keep=true;
            if(keep)keepRows.add(r);
        }
        // A name alone is unused layout; nonempty malformed quantities already remain visible above.
        int a=def.path("formulaStartRow").asInt(),b=def.path("formulaEndRow").asInt();
        for(int r=a;r<=b;r++)if(!value(sheet,r,1).isBlank()) {
            boolean used=false;for(int c:keepColumns)if(c>=first)used|=LabValues.recorded(value(sheet,r,c));
            if(used)keepRows.add(r);
        }
        var rowMap=index(keepRows);var colMap=index(keepColumns);var newCells=json.createObjectNode();var newRows=json.createObjectNode();var newColumns=json.createObjectNode();
        var usedFields=new HashSet<String>();
        for(int r:keepRows) {
            if(sheet.path("rowData").has(""+r))newRows.set(""+rowMap.get(r),sheet.path("rowData").path(""+r).deepCopy());
            for(int c:keepColumns) {
                var original=rows.path(""+r).path(""+c);if(!original.isObject())continue;
                var cell=(ObjectNode)original.deepCopy();var role=cell.path("custom").path("lab").path("role").asText();
                if((role.equals("VALUE")||role.equals("MATERIAL_QTY"))&&!LabValues.recorded(value(sheet,r,c))) {
                    mark(cell).put("valueStatus",LabValues.state(value(sheet,r,c),role.equals("MATERIAL_QTY")));
                    cell.remove(List.of("v","p","f"));
                }
                if(cell.hasNonNull("f"))cell.put("f",formula(cell.path("f").asText(),rowMap,colMap));
                var code=cell.path("custom").path("lab").path("fieldCode").asText();if(!code.isBlank())usedFields.add(code);
                newCells.withObject("/"+rowMap.get(r)).set(""+colMap.get(c),cell);
            }
        }
        for(int c:keepColumns)if(sheet.path("columnData").has(""+c))newColumns.set(""+colMap.get(c),sheet.path("columnData").path(""+c).deepCopy());
        var merges=json.createArrayNode();
        for(var merge:sheet.path("mergeData")) {
            var rr=keepRows.subSet(merge.path("startRow").asInt(),true,merge.path("endRow").asInt(),true);
            var cc=keepColumns.subSet(merge.path("startColumn").asInt(),true,merge.path("endColumn").asInt(),true);
            if(rr.isEmpty()||cc.isEmpty())continue;
            int r0=rowMap.get(rr.first()),r1=rowMap.get(rr.last()),c0=colMap.get(cc.first()),c1=colMap.get(cc.last());
            if(r0!=r1||c0!=c1)merges.addObject().put("startRow",r0).put("endRow",r1).put("startColumn",c0).put("endColumn",c1);
            var anchor=rows.path(merge.path("startRow").asText()).path(merge.path("startColumn").asText());
            if(!keepRows.contains(merge.path("startRow").asInt())&&anchor.isObject()&&!LabValues.text(anchor.path("v")).isBlank())newCells.withObject("/"+r0).set(""+c0,anchor.deepCopy());
        }
        sheet.set("cellData",newCells);sheet.set("rowData",newRows);sheet.set("columnData",newColumns);sheet.set("mergeData",merges);
        if(sheet.path("freeze") instanceof ObjectNode freeze) {
            int rowSplit=(int)keepRows.stream().filter(r->r<freeze.path("ySplit").asInt()).count();
            int columnSplit=(int)keepColumns.stream().filter(c->c<freeze.path("xSplit").asInt()).count();
            freeze.put("ySplit",rowSplit).put("startRow",rowSplit).put("xSplit",columnSplit).put("startColumn",columnSplit);
        }
        sheet.put("rowCount",Math.max(1,keepRows.size())).put("columnCount",Math.max(2,keepColumns.size()));
        var filtered=(ObjectNode)def.deepCopy();var keptDefs=json.createObjectNode();usedFields.forEach(code->{if(fields.has(code))keptDefs.set(code,fields.path(code).deepCopy());});filtered.set("fields",keptDefs);filtered.put("presentation","RESULT");configuration(sheet,filtered);
        // Layout identities contain no observations, but preserve the position of a
        // local field relative to standard fields omitted from a printed result.
        var fieldOrder=filtered.putArray("fieldOrder");
        fields.fields().forEachRemaining(entry->fieldOrder.addObject().put("fieldCode",entry.getKey()).put("row",entry.getValue().path("row").asInt()).put("groupId",entry.getValue().path("groupId").asText()));
        configuration(sheet,filtered);
        var print=sheet.withObject("/custom/print");print.put("lastRow",Math.max(0,keepRows.size()-1)).put("lastColumn",Math.max(1,keepColumns.size()-1));
        if(print.has("repeatFirstRow")) {
            var repeated=keepRows.subSet(print.path("repeatFirstRow").asInt(),true,print.path("repeatLastRow").asInt(),true);
            if(repeated.isEmpty())print.remove(List.of("repeatFirstRow","repeatLastRow"));
            else print.put("repeatFirstRow",rowMap.get(repeated.first())).put("repeatLastRow",rowMap.get(repeated.last()));
        }
        var map=json.createObjectNode();map.set("rows",json.valueToTree(rowMap));map.set("columns",json.valueToTree(colMap));
        // Unknown resources can include full original values; the unified forms have no drawings.
        var only=json.createObjectNode();only.set(sheet.path("id").asText(),sheet);out.set("sheets",only);out.set("sheetOrder",json.createArrayNode().add(sheet.path("id").asText()));
        sheet.withObject("/custom").remove("templateInstance");
        out.remove("resources");return new Presentation(out,map);
    }

    /** A compact printed form reopens as a full editing form, with only its recorded values applied. */
    private static ObjectNode expand(ObjectMapper json,ObjectNode source,JsonNode baseline,String seed) {
        var out=(ObjectNode)blank(baseline);var target=sheet(out);var original=sheet(source);
        if(original==null)throw invalid("无法定位实验数据工作表");
        var def=(ObjectNode)definition(out).deepCopy();var sourceDef=definition(source);
        var cols=new ArrayList<Integer>();
        original.path("columnData").fields().forEachRemaining(c->{if(c.getValue().path("custom").path("lab").has("sampleKey"))cols.add(Integer.parseInt(c.getKey()));});Collections.sort(cols);
        int end=Math.max(7,1+cols.size());
        for(int c=8;c<=end;c++) {
            for(var row:iterable(target.path("cellData").fields()))if(Integer.parseInt(row.getKey())>=6&&row.getValue().path("7").isObject()) {
                var cloned=(ObjectNode)row.getValue().path("7").deepCopy();cloned.remove(List.of("v","p","f"));((ObjectNode)row.getValue()).set(""+c,cloned);
            }
            target.withObject("/columnData").set(""+c,target.path("columnData").path("7").deepCopy());
        }
        for(var merge:target.path("mergeData"))if(merge.path("startColumn").asInt()<2&&merge.path("endColumn").asInt()==7)((ObjectNode)merge).put("endColumn",end);
        for(int i=0;i<cols.size();i++)target.withObject("/columnData").set(""+(i+2),original.path("columnData").path(""+cols.get(i)).deepCopy());
        var materialRows=new ArrayList<Integer>();
        for(var row:iterable(original.path("cellData").fields()))if(row.getValue().path("1").path("custom").path("lab").path("role").asText().equals("MATERIAL_CODE"))materialRows.add(Integer.parseInt(row.getKey()));Collections.sort(materialRows);
        var total=findField(target,"formula.total");var identity=findField(target,"sample.experimentNo");
        if(total==null||identity==null)throw invalid("模板缺少配方边界");
        int capacity=total[0]-identity[0]-1;
        if(materialRows.size()>capacity)insertRows(json,target,total[0],materialRows.size()-capacity);
        // Restore confirmed local field definitions, while keeping all standard fields from the baseline.
        var localFields=new ArrayList<Map.Entry<String,JsonNode>>();sourceDef.path("fields").fields().forEachRemaining(localFields::add);
        localFields.sort(Comparator.comparingInt(f->f.getValue().path("row").asInt()));
        for(var f:localFields)if(!def.path("fields").has(f.getKey())&&f.getValue().path("local").asBoolean()&&f.getKey().matches("(?:LOCAL_|INSTANCE_LOCAL[.])[A-Za-z0-9_.]{1,160}")) {
            ((ObjectNode)def.path("fields")).set(f.getKey(),f.getValue().deepCopy());
            boolean sample=f.getValue().path("scope").asText().equals("SAMPLE");
            int at=6;for(var row:iterable(target.path("cellData").fields())) {
                var code=row.getValue().path("1").path("custom").path("lab").path("fieldCode").asText();
                if(!sample||def.path("fields").path(code).path("groupId").equals(f.getValue().path("groupId")))at=Math.max(at,Integer.parseInt(row.getKey())+1);
            }
            int following=Integer.MAX_VALUE,followingRow=Integer.MAX_VALUE;
            for(var candidate:localFields) {
                var candidateField=candidate.getValue();int sourceRow=candidateField.path("row").asInt();
                if(sourceRow<=f.getValue().path("row").asInt()||sourceRow>=following||!candidateField.path("groupId").equals(f.getValue().path("groupId")))continue;
                var anchor=findField(target,candidate.getKey());if(anchor!=null){following=sourceRow;followingRow=anchor[0];}
            }
            for(var candidate:sourceDef.path("fieldOrder")) {
                int sourceRow=candidate.path("row").asInt();
                if(sourceRow<=f.getValue().path("row").asInt()||sourceRow>=following||!candidate.path("groupId").equals(f.getValue().path("groupId")))continue;
                var anchor=findField(target,candidate.path("fieldCode").asText());if(anchor!=null){following=sourceRow;followingRow=anchor[0];}
            }
            if(followingRow!=Integer.MAX_VALUE)at=followingRow;
            else {
                int firstInGroup=at;for(var row:iterable(target.path("cellData").fields())) {
                    var code=row.getValue().path("1").path("custom").path("lab").path("fieldCode").asText();
                    if(def.path("fields").path(code).path("groupId").equals(f.getValue().path("groupId")))firstInGroup=Math.min(firstInGroup,Integer.parseInt(row.getKey()));
                }
                at=Math.max(firstInGroup,Math.min(at,f.getValue().path("row").asInt(at)));
            }
            insertRows(json,target,at,1);
            var prototype=target.path("cellData").path(""+(at-1));
            for(int c=1;c<=end;c++) {
                var cloned=prototype.path(""+c).isObject()?(ObjectNode)prototype.path(""+c).deepCopy():json.createObjectNode();cloned.remove(List.of("v","p","f"));
                if(c==1&&!f.getValue().path("supplemental").asBoolean())cloned.put("v",f.getValue().path("label").asText());
                if(sample||c==2)mark(cloned).put("role",c==1?"LABEL":"VALUE").put("fieldCode",f.getKey());
                else if(cloned.path("custom") instanceof ObjectNode custom)custom.remove("lab");
                target.withObject("/cellData/"+at).set(""+c,cloned);
            }
        }
        configuration(target,def);
        identity=findField(target,"sample.experimentNo");total=findField(target,"formula.total");
        var sourceRowMap=new LinkedHashMap<Integer,Integer>();var sourceColumnMap=new LinkedHashMap<Integer,Integer>();sourceColumnMap.put(0,0);sourceColumnMap.put(1,1);for(int i=0;i<cols.size();i++)sourceColumnMap.put(cols.get(i),i+2);
        for(int i=0;i<materialRows.size();i++) {
            int r=identity[0]+1+i,sr=materialRows.get(i);sourceRowMap.put(sr,r);
            cell(target,r,1).put("v",value(original,sr,1));
            cell(target,r,1).set("custom",original.path("cellData").path(""+sr).path("1").path("custom").deepCopy());
            for(int j=0;j<cols.size();j++)copyValue(original,sr,cols.get(j),target,r,j+2,source.path("styles"));
        }
        for(var row:iterable(original.path("cellData").fields()))for(var column:iterable(row.getValue().fields())) {
            var meta=column.getValue().path("custom").path("lab");if(!meta.path("role").asText().equals("VALUE"))continue;
            var code=meta.path("fieldCode").asText();var field=def.path("fields").path(code);var at=findField(target,code);if(at==null)continue;
            int sr=Integer.parseInt(row.getKey()),sc=Integer.parseInt(column.getKey());sourceRowMap.put(sr,at[0]);
            if(field.path("valueSource").asText().equals("FORMULA")) {
                // A customer-entered total is an observation, not a formula
                // cache. Keep it verbatim even when the recipe is incomplete.
                if(!column.getValue().hasNonNull("f")&&LabValues.recorded(value(original,sr,sc))&&sourceColumnMap.containsKey(sc))
                    copyValue(original,sr,sc,target,at[0],sourceColumnMap.get(sc),source.path("styles"));
                continue;
            }
            if(field.path("scope").asText().equals("EXPERIMENT"))copyValue(original,sr,sc,target,at[0],at[1],source.path("styles"));
            else if(sourceColumnMap.containsKey(sc))copyValue(original,sr,sc,target,at[0],sourceColumnMap.get(sc),source.path("styles"));
        }
        for(var row:target.path("cellData"))for(var c:row)if(c.hasNonNull("f")&&!c.path("custom").path("lab").path("fieldCode").asText().equals("formula.total"))((ObjectNode)c).put("f",formula(c.path("f").asText(),sourceRowMap,sourceColumnMap));
        for(int c=2;c<=end;c++)if(!LabValues.recorded(value(target,total[0],c)))cell(target,total[0],c).put("f",massFormula(c,identity[0]+1,total[0]-1));
        target.put("columnCount",Math.max(50,end+10));
        return out;
    }
    private static void copyValue(JsonNode from,int row,int column,ObjectNode target,int targetRow,int targetColumn,JsonNode styles) {
        var src=from.path("cellData").path(""+row).path(""+column);var cell=cell(target,targetRow,targetColumn);
        for(var key:List.of("v","p","f"))if(src.has(key))cell.set(key,src.path(key).deepCopy());else cell.remove(key);
        var style=src.path("s").isTextual()?styles.path(src.path("s").asText()):src.path("s");
        if(style.isObject())cell.set("s",style.deepCopy());
        if(from.path("rowData").path(""+row).isObject())target.withObject("/rowData").set(""+targetRow,from.path("rowData").path(""+row).deepCopy());
        if(!src.hasNonNull("v")&&Set.of("ABSENT","NOT_APPLICABLE").contains(src.path("custom").path("lab").path("valueStatus").asText()))cell.put("v","/");
    }
    private static void insertRows(ObjectMapper json,ObjectNode sheet,int at,int count) {
        var cells=json.createObjectNode();var rowData=json.createObjectNode();var mapping=new LinkedHashMap<Integer,Integer>();
        int last=sheet.path("rowCount").asInt(200);
        for(int r=0;r<last;r++)mapping.put(r,r>=at?r+count:r);
        sheet.path("cellData").fields().forEachRemaining(row->{int r=Integer.parseInt(row.getKey());cells.set(""+mapping.getOrDefault(r,r),row.getValue().deepCopy());});
        sheet.path("rowData").fields().forEachRemaining(row->{int r=Integer.parseInt(row.getKey());rowData.set(""+mapping.getOrDefault(r,r),row.getValue().deepCopy());});
        var cols=new LinkedHashMap<Integer,Integer>();for(int c=0;c<sheet.path("columnCount").asInt(50);c++)cols.put(c,c);
        for(var row:cells)for(var cell:row)if(cell.hasNonNull("f"))((ObjectNode)cell).put("f",formula(cell.path("f").asText(),mapping,cols));
        for(var merge:sheet.path("mergeData")) {
            var m=(ObjectNode)merge;int start=m.path("startRow").asInt(),end=m.path("endRow").asInt();
            if(start>=at)m.put("startRow",start+count);if(end>=at)m.put("endRow",end+count);
        }
        sheet.set("cellData",cells);sheet.set("rowData",rowData);sheet.put("rowCount",last+count);
    }
    public static String massFormula(int column,int first,int last) {
        var range=address(first,column)+":"+address(last,column);var labels="$B$"+(first+1)+":$B$"+(last+1);
        var numbers="IFERROR(--SUBSTITUTE(SUBSTITUTE(LOWER(TRIM("+range+")),\"g\",\"\"),\"/\",\"0\"),-1)";
        return "=IF(COUNTA("+range+")=0,\"\",IF(OR(SUMPRODUCT(("+labels+"<>\"\")*("+range+"=\"\"))>0,SUMPRODUCT(("+labels+"=\"\")*("+range+"<>\"\")*("+range+"<>\"/\"))>0,SUMPRODUCT(--("+numbers+"<0))>0,SUMPRODUCT(--ISNUMBER(SEARCH(\"%\","+range+")))>0,SUMPRODUCT(("+range+"<>\"/\")*ISNUMBER(SEARCH(\"/\","+range+")))>0),\"\",SUMPRODUCT("+numbers+")))";
    }
    private static Map<Integer,Integer> index(SortedSet<Integer> source){var result=new LinkedHashMap<Integer,Integer>();int i=0;for(int n:source)result.put(n,i++);return result;}
    private static String formula(String source,Map<Integer,Integer> rows,Map<Integer,Integer> columns) {
        var parts=source.split("(?=\\\")|(?<=\\\")",-1);var out=new StringBuilder();boolean quoted=false;
        for(var part:parts) {if(part.equals("\"")){quoted=!quoted;out.append(part);}else out.append(quoted?part:formulaReferences(part,rows,columns));}
        return out.toString();
    }
    private static String formulaReferences(String source,Map<Integer,Integer> rows,Map<Integer,Integer> columns) {
        var p=Pattern.compile("(\\$?)([A-Z]{1,3})(\\$?)([1-9][0-9]*)(?::(\\$?)([A-Z]{1,3})(\\$?)([1-9][0-9]*))?");
        var result=new StringBuilder();var matcher=p.matcher(source);int offset=0;
        while(matcher.find()) {
            result.append(source,offset,matcher.start());
            int r=Integer.parseInt(matcher.group(4))-1,c=column(matcher.group(2));
            if(matcher.group(8)!=null) {
                int re=Integer.parseInt(matcher.group(8))-1,ce=column(matcher.group(6));
                var rr=rows.keySet().stream().filter(n->n>=r&&n<=re).toList();var cc=columns.keySet().stream().filter(n->n>=c&&n<=ce).toList();
                if(rr.isEmpty()||cc.isEmpty())result.append('0');else result.append(ref(rows.get(rr.getFirst()),columns.get(cc.getFirst()),matcher.group(1),matcher.group(3))).append(':').append(ref(rows.get(rr.getLast()),columns.get(cc.getLast()),matcher.group(5),matcher.group(7)));
            } else if(rows.containsKey(r)&&columns.containsKey(c))result.append(ref(rows.get(r),columns.get(c),matcher.group(1),matcher.group(3)));else result.append('0');
            offset=matcher.end();
        }
        return result.append(source.substring(offset)).toString();
    }
    private static String ref(int row,int col,String ac,String ar){var a=address(row,col);int i=0;while(i<a.length()&&Character.isLetter(a.charAt(i)))i++;return ac+a.substring(0,i)+ar+a.substring(i);}
    private static int column(String letters){int c=0;for(char x:letters.toCharArray())c=c*26+x-'A'+1;return c-1;}
    public static JsonNode metadata(ObjectMapper json,JsonNode snapshot) {
        var sheet=sheet(snapshot);var meta=json.createObjectNode().put("version",1);if(sheet==null)return meta;
        meta.set("custom",sheet.path("custom").deepCopy());
        var cellMetadata=meta.putObject("cells");
        sheet.path("cellData").fields().forEachRemaining(r->r.getValue().fields().forEachRemaining(c->{if(c.getValue().path("custom").has("lab"))cellMetadata.withObject("/"+r.getKey()).set(c.getKey(),c.getValue().path("custom").deepCopy());}));
        var columns=meta.putObject("columns");sheet.path("columnData").fields().forEachRemaining(c->{if(c.getValue().has("custom"))columns.set(c.getKey(),c.getValue().path("custom").deepCopy());});
        var identities=meta.putObject("groupLabels");String identityCode="sample.experimentNo";var identity=findField(sheet,identityCode);
        var sampleIdentity=findField(sheet,"sample.number");
        if(identity!=null&&value(sheet,identity[0],2).isBlank()&&sampleIdentity!=null&&!value(sheet,sampleIdentity[0],2).isBlank()){identity=sampleIdentity;identityCode="sample.number";}
        meta.put("groupIdentityFieldCode",identityCode);
        if(identity!=null){int identityRow=identity[0];columns.fieldNames().forEachRemaining(c->identities.put(c,value(sheet,identityRow,Integer.parseInt(c))));}
        var materials=meta.putObject("materialLabels");
        sheet.path("cellData").fields().forEachRemaining(r->{if(r.getValue().path("1").path("custom").path("lab").path("role").asText().equals("MATERIAL_CODE"))materials.put(r.getKey(),value(sheet,Integer.parseInt(r.getKey()),1));});
        return meta;
    }
    public static void restoreMetadata(ObjectMapper json,ObjectNode snapshot) {
        var sheets=(ObjectNode)snapshot.path("sheets");String metaId=null;ObjectNode target=null;
        for(var e:iterable(sheets.fields()))if(e.getValue().path("name").asText().equals(META_SHEET))metaId=e.getKey();else if(!e.getValue().path("name").asText().startsWith("_JSD"))if(target==null)target=(ObjectNode)e.getValue();
        if(metaId==null||target==null)return;
        var encoded=new StringBuilder();for(var row:sheets.path(metaId).path("cellData"))encoded.append(row.path("0").path("v").asText(""));
        try {
            var meta=json.readTree(encoded.toString());if(meta.path("version").asInt()!=1)throw invalid("实验文件定位信息版本不受支持");
            target.set("custom",meta.path("custom").deepCopy());
            // External Excel insertions update visible anchors, not embedded JSON coordinates.
            // Rebuild the generic instance from the relocated legacy metadata on next open.
            target.withObject("/custom").remove("templateInstance");
            var restoredRows=new HashMap<Integer,Integer>();var claimedRows=new HashSet<Integer>();var defs=definition(snapshot).path("fields");
            int max=target.path("rowCount").asInt(200);
            // Excel updates visible cells on insertion, but cannot update our hidden locator JSON.
            // Relocate label anchors first, then restore metadata through that mapping.
            for(var row:iterable(meta.path("cells").fields())) {
                int old=Integer.parseInt(row.getKey());var labelMeta=row.getValue().path("1").path("lab");
                String expected="";int labelColumn=1;
                if(labelMeta.path("role").asText().equals("LABEL"))expected=defs.path(labelMeta.path("fieldCode").asText()).path("label").asText();
                else if(labelMeta.path("role").asText().equals("MATERIAL_CODE"))expected=meta.path("materialLabels").path(row.getKey()).asText();
                else if(row.getValue().path("0").path("lab").path("role").asText().equals("GROUP")) {
                    labelColumn=0;var gid=row.getValue().path("0").path("lab").path("groupId").asText();for(var region:definition(snapshot).path("regions"))if(region.path("id").asText().equals(gid))expected=region.path("label").asText();
                }
                if(expected.isBlank())for(var entry:iterable(row.getValue().fields())) {
                    var field=defs.path(entry.getValue().path("lab").path("fieldCode").asText());
                    if(field.path("scope").asText().equals("EXPERIMENT")&&!field.path("label").asText().isBlank()){expected=field.path("label").asText();labelColumn=Math.max(0,Integer.parseInt(entry.getKey())-1);break;}
                }
                if(expected.isBlank()){restoredRows.put(old,old);continue;}
                int found=-1;
                for(int r=0;r<max;r++)if(!claimedRows.contains(r)&&(normalized(value(target,r,labelColumn)).equals(normalized(expected))||labelColumn==1&&value(target,r,1).isBlank()&&normalized(value(target,r,0)).equals(normalized(expected)))){found=r;break;}
                if(found>=0){restoredRows.put(old,found);claimedRows.add(found);}
            }
            // A supplemental row deliberately has no visible title. Restore
            // it only when its surrounding anchors moved by the same amount;
            // using a fabricated label would lose the original observations.
            for(var row:iterable(meta.path("cells").fields())) {
                int old=Integer.parseInt(row.getKey());if(restoredRows.containsKey(old))continue;
                var code=row.getValue().path("1").path("lab").path("fieldCode").asText();
                if(!defs.path(code).path("supplemental").asBoolean())continue;
                Integer before=null,after=null;
                for(int anchor:restoredRows.keySet()) {
                    if(anchor<old&&(before==null||anchor>before))before=anchor;
                    if(anchor>old&&(after==null||anchor<after))after=anchor;
                }
                if(before==null||after==null)continue;
                int leftDelta=restoredRows.get(before)-before,rightDelta=restoredRows.get(after)-after;
                int found=old+leftDelta;
                if(leftDelta!=rightDelta||found<0||found>=max||claimedRows.contains(found)||!value(target,found,1).isBlank())continue;
                boolean hasValue=false;for(var cell:target.path("cellData").path(""+found))if(LabValues.recorded(LabValues.text(cell.path("v"))))hasValue=true;
                if(hasValue){restoredRows.put(old,found);claimedRows.add(found);}
            }
            var identityCode=meta.path("groupIdentityFieldCode").asText("sample.experimentNo");
            var identity=defs.path(identityCode);int oldIdentity=identity.path("row").asInt(-1);
            // Compact exports retain current label metadata even though definition rows refer to the full form.
            for(var row:iterable(meta.path("cells").fields()))if(row.getValue().path("1").path("lab").path("fieldCode").asText().equals(identityCode))oldIdentity=Integer.parseInt(row.getKey());
            int identityRow=restoredRows.getOrDefault(oldIdentity,oldIdentity);var columnMap=new HashMap<Integer,Integer>();columnMap.put(0,0);columnMap.put(1,1);var claimedColumns=new HashSet<Integer>();
            for(var c:iterable(meta.path("columns").fields())) {
                int old=Integer.parseInt(c.getKey()),found=old;var label=meta.path("groupLabels").path(c.getKey()).asText();
                if(!label.isBlank()&&identityRow>=0){found=-1;for(int col=2;col<target.path("columnCount").asInt(50);col++)if(!claimedColumns.contains(col)&&value(target,identityRow,col).equals(label)){found=col;break;}}
                if(found>=0){columnMap.put(old,found);claimedColumns.add(found);target.withObject("/columnData/"+found).set("custom",c.getValue().deepCopy());}
            }
            for(var r:iterable(meta.path("cells").fields()))if(restoredRows.containsKey(Integer.parseInt(r.getKey())))for(var c:iterable(r.getValue().fields())) {
                int col=Integer.parseInt(c.getKey());var field=defs.path(c.getValue().path("lab").path("fieldCode").asText());
                if(field.path("scope").asText().equals("EXPERIMENT"))cell(target,restoredRows.get(Integer.parseInt(r.getKey())),col).set("custom",c.getValue().deepCopy());
                else if(columnMap.containsKey(col))cell(target,restoredRows.get(Integer.parseInt(r.getKey())),columnMap.get(col)).set("custom",c.getValue().deepCopy());
            }
            sheets.remove(metaId);var order=(ArrayNode)snapshot.path("sheetOrder");for(int i=order.size()-1;i>=0;i--)if(order.get(i).asText().equals(metaId))order.remove(i);
        }catch(Exception ex){throw invalid("实验文件定位信息损坏，请核对导入文件");}
    }
    public record Presentation(JsonNode snapshot,JsonNode locationMap) {}
    public static JsonNode blank(JsonNode source) {
        var copy=(ObjectNode)source.deepCopy();var sheet=sheet(copy);if(sheet==null)return copy;
        for(var row:sheet.path("cellData"))for(var c:row) {
            var role=c.path("custom").path("lab").path("role").asText();
            if(Set.of("VALUE","MATERIAL_CODE","MATERIAL_QTY").contains(role)) {
                ((ObjectNode)c).remove(List.of("v","p"));
                if(c.path("custom").path("lab") instanceof ObjectNode metadata)metadata.remove("valueStatus");
            }
        }
        return copy;
    }
    private static ApiException invalid(String message){return new ApiException(ApiErrorCode.VALIDATION_ERROR,message);}
}
