package com.jsd.aird.shared.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import java.util.*;
import java.util.regex.Pattern;

/** Generic instance geometry and identities. Domain projections belong to their owning modules. */
public final class WorkbookStructure {
    public static final String META_SHEET="_JSD_STRUCTURE";
    private WorkbookStructure() {}
    private static final Pattern RANGE = Pattern.compile("([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?", Pattern.CASE_INSENSITIVE);
    public static JsonNode instance(JsonNode snapshot) {
        if (snapshot != null) for (var sheet : snapshot.path("sheets")) {
            var value = sheet.path("custom").path("templateInstance");
            if (value.path("version").asInt() == 2) return value;
        }
        return MissingNode.getInstance();
    }
    public static void attach(JsonNode snapshot, JsonNode instance) {
        if (instance == null || instance.path("version").asInt() != 2) return;
        for (var sheet : snapshot.path("sheets")) if (sheet instanceof ObjectNode object) {
            object.withObject("/custom").set("templateInstance", instance.deepCopy()); return;
        }
    }
    public static ObjectNode seed(ObjectMapper json,JsonNode snapshot,JsonNode schema,JsonNode mappings,String seed) {
        if(schema==null)schema=json.createObjectNode();if(mappings==null||!mappings.isArray())mappings=json.createArrayNode();
        if(instance(snapshot).isObject()) {
            var existing=(ObjectNode)instance(snapshot).deepCopy();
            for(var field:existing.path("model").path("fields"))if(field instanceof ObjectNode object) {
                var bindingId=field.path("bindingId").asText();
                if(field.path("id").asText().equals("region:"+bindingId))object.put("structuralOnly",true);
            }
            com.jsd.aird.shared.lab.LabWorkbook.seedStructure(json,snapshot,existing,false);
            attach(snapshot,existing);return existing;
        }
        var out=json.createObjectNode().put("version",2);
        var model=schema.path("x-jsd-field-model").isObject()?(ObjectNode)schema.path("x-jsd-field-model").deepCopy():json.createObjectNode().put("modelVersion",4);
        for(var key:List.of("groups","fields","blocks","semanticAnnotations"))if(!model.path(key).isArray())model.putArray(key);
        out.set("model",model);out.set("mapping",mappings.deepCopy());
        for(var binding:out.path("mapping"))if(binding.path("locator") instanceof ObjectNode locator) {
            for(var sheet:snapshot.path("sheets"))if(sheet.path("id").equals(locator.path("sheetId"))||sheet.path("name").equals(locator.path("sheetName"))) {
                locator.put("sheetId",sheet.path("id").asText()).put("sheetName",sheet.path("name").asText());break;
            }
        }
        if(schema.path("x-jsd-experiment-import").isObject())out.set("execution",schema.path("x-jsd-experiment-import").deepCopy());
        var records=out.putArray("records");var items=out.putArray("items");out.putArray("issues");
        for(var b:out.path("mapping"))if("REPEAT_REGION".equals(b.path("mappingKind").asText())) {
            boolean hasRoot=false;for(var f:model.path("fields"))if(f.path("bindingId").equals(b.path("bindingId"))){hasRoot=true;break;}
            if(!hasRoot) {
                var root=model.withArray("/fields").addObject().put("id","region:"+b.path("bindingId").asText()).put("structuralOnly",true).put("bindingId",b.path("bindingId").asText()).put("dataPath",b.path("dataPath").asText()).put("groupId",model.path("groups").path(0).path("id").asText("other")).put("name",first(b.path("diagnostic"),"businessName","displayName").isBlank()?"明细记录":first(b.path("diagnostic"),"businessName","displayName")).put("kind",b.path("repeatAxis").asText().equals("COLUMN")?"COLUMN_TABLE":"ROW_TABLE").put("displayRole","REGION").put("fieldType","REGION").put("mappingKind","REPEAT_REGION").put("repeatAxis",b.path("repeatAxis").asText("ROW")).put("valueType","array").put("required",false).put("reviewStatus","CONFIRMED");root.set("locator",b.path("locator").deepCopy());
            }
            var r=range(first(b.path("locator"),"dataRange","address","range"));if(r==null)continue;
            boolean row=!b.path("repeatAxis").asText().equals("COLUMN");int size=Math.max(1,b.path(row?"recordHeight":"recordWidth").asInt(1)),stride=Math.max(size,b.path("recordStride").asInt(1));
            for(int n=row?r[0]:r[1];n+size-1<=(row?r[2]:r[3]);n+=stride)records.addObject().put("id",UUID.nameUUIDFromBytes((seed+":"+b.path("bindingId").asText()+":"+n).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()).put("regionId",b.path("bindingId").asText()).put("sheetId",b.path("locator").path("sheetId").asText()).put("axis",row?"ROW":"COLUMN").put("index",n).put("size",size);
        }
        for(var p:out.path("execution").path("listProjections")) {
            var r=range(p.path("labelRange").asText());if(r==null)continue;
            JsonNode parent=MissingNode.getInstance();for(var b:out.path("mapping"))if(b.path("bindingId").equals(p.path("parentBindingId"))||b.path("componentId").equals(p.path("componentId"))){parent=b;break;}
            boolean row=!p.path("itemAxis").asText().equals("COLUMN");
            for(int n=row?r[0]:r[1];n<=(row?r[2]:r[3]);n++)items.addObject().put("id",UUID.nameUUIDFromBytes((seed+":"+p.path("listProjectionId").asText()+":"+n).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()).put("regionId",p.path("listProjectionId").asText()).put("sheetId",parent.path("locator").path("sheetId").asText()).put("axis",row?"ROW":"COLUMN").put("index",n).put("size",1);
        }
        com.jsd.aird.shared.lab.LabWorkbook.seedStructure(json,snapshot,out,true);
        attach(snapshot,out);return out;
    }
    public static JsonNode metadata(ObjectMapper json,JsonNode snapshot) {
        var out=json.createObjectNode().put("version",1);out.set("instance",instance(snapshot).deepCopy());var sheets=out.putArray("sheets");
        for(var sheet:snapshot.path("sheets"))sheets.addObject().put("id",sheet.path("id").asText()).put("name",sheet.path("name").asText());return out;
    }
    public static void restoreMetadata(ObjectMapper json,ObjectNode snapshot) {
        var sheets=snapshot.withObject("/sheets");String metaId=null;
        var iterator=sheets.fields();while(iterator.hasNext()){var entry=iterator.next();if(entry.getValue().path("name").asText().equals(META_SHEET)){metaId=entry.getKey();break;}}
        if(metaId==null)return;
        var text=new StringBuilder();for(var row:sheets.path(metaId).path("cellData"))text.append(row.path("0").path("v").asText());
        try {
            var meta=json.readTree(text.toString());if(meta.path("version").asInt()!=1)throw invalid("工作簿定位信息版本不受支持");
            var idMap=new HashMap<String,String>();for(var old:meta.path("sheets"))for(var actual:sheets)if(actual.path("name").equals(old.path("name")))idMap.put(old.path("id").asText(),actual.path("id").asText());
            var instance=meta.path("instance").deepCopy();replaceSheetIds(instance,idMap);attach(snapshot,instance);
            sheets.remove(metaId);var order=json.createArrayNode();for(var id:snapshot.path("sheetOrder"))if(!id.asText().equals(metaId))order.add(id);snapshot.set("sheetOrder",order);
        }catch(ApiException ex){throw ex;}catch(Exception ex){throw invalid("工作簿定位信息损坏，请核对来源文件");}
    }
    private static void replaceSheetIds(JsonNode node,Map<String,String> ids){
        if(node instanceof ObjectNode object&&object.has("sheetId"))object.put("sheetId",ids.getOrDefault(object.path("sheetId").asText(),object.path("sheetId").asText()));
        if(node.isContainerNode())for(var child:node)replaceSheetIds(child,ids);
    }
    public static ObjectNode validate(ObjectMapper json, JsonNode snapshot, JsonNode provided) {
        var source = provided != null && provided.path("version").asInt() == 2 ? provided : instance(snapshot);
        if (!source.isObject()) throw invalid("工作簿缺少实例结构定义");
        var out = (ObjectNode) source.deepCopy();
        if (!out.path("model").path("fields").isArray() || !out.path("mapping").isArray()) throw invalid("实例字段与绑定定义无效");
        var sheetIds = new HashSet<String>();
        for (var sheet : snapshot.path("sheets")) sheetIds.add(sheet.path("id").asText());
        var ids = new HashSet<String>(); var codes = new HashSet<String>();
        var issues = json.createArrayNode();
        for (var issue : out.path("issues")) if (!Set.of("FIELD_UNCONFIRMED","UNBOUND_VALUE").contains(issue.path("code").asText())) issues.add(issue.deepCopy());
        var bindingById = new HashMap<String,JsonNode>();
        for (var binding : out.path("mapping")) {
            var id = binding.path("bindingId").asText();
            if (id.isBlank() || bindingById.putIfAbsent(id,binding) != null) throw invalid("字段绑定ID重复或为空");
            if (!Set.of("SCALAR","REPEAT_REGION","REPEAT_FIELD").contains(binding.path("mappingKind").asText("SCALAR"))) throw invalid("字段结构类型无效");
            var locator = binding.path("locator");
            if (!locator.path("sheetId").asText().isBlank() && !sheetIds.contains(locator.path("sheetId").asText())) throw invalid("字段指向不存在的工作表");
            for (var key : List.of("address","valueRange","dataRange","labelRange","logicalInputRange")) {
                var address = locator.path(key).asText();
                if (!address.isBlank() && range(address) == null) throw invalid("字段坐标无效："+address);
            }
        }
        for (var field : out.path("model").path("fields")) {
            if (!ids.add(field.path("id").asText())) throw invalid("字段ID重复");
            var code = field.path("fieldCode").asText();
            if (!code.isBlank() && !codes.add(code)) throw invalid("字段编码重复");
            if (field.path("displayRole").asText().equals("REGION")) continue;
            var binding = bindingById.get(field.path("bindingId").asText());
            if (field.path("name").asText().isBlank() || !field.path("reviewStatus").asText().equals("CONFIRMED") || (out.path("execution").path("templateUsage").asText().equals("EXPERIMENT_DATA") && field.path("experimentSemanticStatus").asText().equals("NEEDS_REVIEW")) || binding == null || !binding.path("bindingStatus").asText().equals("VALID") || first(binding.path("locator"),"valueRange","address").isBlank())
                issues.addObject().put("code","FIELD_UNCONFIRMED").put("fieldId",field.path("id").asText()).put("message","字段“"+field.path("name").asText()+"”的名称、含义或填写位置待确认");
        }
        for (var key : List.of("records","items")) {
            var seen = new HashSet<String>();
            for (var item : out.path(key)) if (item.path("id").asText().isBlank() || !seen.add(item.path("id").asText()) || !sheetIds.contains(item.path("sheetId").asText()) || item.path("index").asInt()<1 || !Set.of("ROW","COLUMN").contains(item.path("axis").asText())) throw invalid("重复区域身份或位置无效");
        }
        var unbound=new HashSet<String>();
        for(var old:source.path("issues"))if(old.path("code").asText().equals("UNBOUND_VALUE")) {
            var r=range(old.path("address").asText());if(r==null)continue;
            for(var sheet:snapshot.path("sheets"))if(sheet.path("id").equals(old.path("sheetId"))&&!cellValue(sheet,r[0],r[1]).asText("").isBlank()&&!covered(out,sheet.path("id").asText(),r[0],r[1])){issues.add(old.deepCopy());unbound.add(sheet.path("id").asText()+":"+old.path("address").asText());}
        }
        for(var sheet:snapshot.path("sheets")) {
            var iterator=sheet.path("cellData").fields();
            while(iterator.hasNext()){var row=iterator.next();int r=Integer.parseInt(row.getKey())+1;var columns=row.getValue().fields();
                while(columns.hasNext()){var cell=columns.next();int c=Integer.parseInt(cell.getKey())+1;if(cell.getValue().hasNonNull("f")||cell.getValue().path("v").asText("").isBlank())continue;
                    boolean data=false;for(var b:out.path("mapping"))if(b.path("mappingKind").asText().equals("REPEAT_REGION")&&b.path("locator").path("sheetId").equals(sheet.path("id"))&&contains(range(first(b.path("locator"),"dataRange","address","range")),r,c)){data=true;break;}
                    if(!data||covered(out,sheet.path("id").asText(),r,c))continue;
                    var address=address(r,c);if(unbound.add(sheet.path("id").asText()+":"+address))issues.addObject().put("code","UNBOUND_VALUE").put("sheetId",sheet.path("id").asText()).put("address",address).put("message","此处有内容但没有对应字段，请核对填写位置");
                }
            }
        }
        out.set("issues",issues); attach(snapshot,out); return out;
    }
    private static boolean contains(int[] range,int row,int col){return range!=null&&row>=range[0]&&row<=range[2]&&col>=range[1]&&col<=range[3];}
    private static boolean covered(JsonNode instance,String sid,int row,int col){
        for(var b:instance.path("mapping"))if(!b.path("mappingKind").asText().equals("REPEAT_REGION")&&b.path("locator").path("sheetId").asText().equals(sid))for(var key:List.of("valueRange","address","labelRange","labelAddress"))if(contains(range(b.path("locator").path(key).asText()),row,col))return true;
        for(var p:instance.path("execution").path("listProjections"))for(var key:List.of("labelRange","valueRange","unitRange"))if(contains(range(p.path(key).asText()),row,col))return true;
        return false;
    }
    private static String address(int row,int col){var text=new StringBuilder();for(int n=col;n>0;n=(n-1)/26)text.insert(0,(char)('A'+(n-1)%26));return text.append(row).toString();}
    public static int[] range(String value) {
        var m=RANGE.matcher(value); if(!m.matches())return null;
        try {
            int r1=Integer.parseInt(m.group(2)),c1=column(m.group(1)),r2=m.group(4)==null?r1:Integer.parseInt(m.group(4)),c2=m.group(3)==null?c1:column(m.group(3));
            return r1<=r2&&c1<=c2&&r2<=1048576&&c2<=16384?new int[]{r1,c1,r2,c2}:null;
        }catch(NumberFormatException ex){return null;}
    }
    private static int column(String text){int n=0;for(char c:text.toUpperCase(Locale.ROOT).toCharArray())n=n*26+c-'A'+1;return n;}
    public static String first(JsonNode node,String...keys){for(var key:keys)if(!node.path(key).asText().isBlank())return node.path(key).asText();return "";}
    public static JsonNode cellValue(JsonNode sheet,int row,int column){return sheet.path("cellData").path(""+(row-1)).path(""+(column-1)).path("v");}
    public static ObjectNode project(ObjectMapper json,JsonNode snapshot,JsonNode model) {
        var out=(ObjectNode)model.deepCopy();var instance=validate(json,snapshot,instance(snapshot));
        var existing=model.path("templateInstance");
        if(existing.path("version").asInt()==2&&existing.path("definition").isObject()) {
            for(var key:List.of("definition","sampleOrder","materialLines","businessBindings","issues"))if(existing.has(key))instance.set(key,existing.path(key).deepCopy());
        }
        out.set("templateInstance",instance);out.set("documentSnapshot",snapshot.deepCopy());
        var values=out.path("dynamicValues") instanceof ObjectNode object?object:out.putObject("dynamicValues");
        var fields=json.createObjectNode();
        for(var binding:instance.path("mapping")) {
            if(binding.path("mappingKind").asText().equals("REPEAT_REGION"))continue;
            var locator=binding.path("locator");var r=range(first(locator,"valueRange","address"));if(r==null)continue;
            JsonNode sheet=MissingNode.getInstance();for(var s:snapshot.path("sheets"))if(s.path("id").asText().equals(locator.path("sheetId").asText())){sheet=s;break;}
            if((long)(r[2]-r[0]+1)*(r[3]-r[1]+1)>100000)throw invalid("字段范围过大");
            var array=json.createArrayNode();for(int row=r[0];row<=r[2];row++)for(int col=r[1];col<=r[3];col++)array.add(cellValue(sheet,row,col).isMissingNode()?json.nullNode():cellValue(sheet,row,col));
            var value=array.size()==1?array.get(0):array;
            fields.set(binding.path("fieldCode").asText(binding.path("bindingId").asText()),value);
            var path=binding.path("dataPath").asText();
            if(path.startsWith("/")&&!path.contains("*")&&!path.matches(".*[\\[\\]].*"))setPath(values,path,value);
        }
        values.set("instanceFields",fields);return out;
    }
    private static void setPath(ObjectNode root,String path,JsonNode value) {
        var segments=path.substring(1).split("/");var target=root;
        for(int i=0;i<segments.length-1;i++){var key=segments[i].replace("~1","/").replace("~0","~");if(!(target.path(key) instanceof ObjectNode))target.putObject(key);target=(ObjectNode)target.path(key);}
        target.set(segments[segments.length-1].replace("~1","/").replace("~0","~"),value.deepCopy());
    }
    private static ApiException invalid(String message){return new ApiException(ApiErrorCode.VALIDATION_ERROR,message);}
}
