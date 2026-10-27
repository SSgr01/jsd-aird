package com.jsd.aird.shared.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.excel.WorkbookStructure;
import java.util.*;

/** Reads generic instance bindings into the legacy laboratory projector, preserving published versions. */
final class LabStructureAdapter {
    private LabStructureAdapter() {}
    /** Bind a versioned template definition to this file without rebuilding business groups. */
    static void seed(ObjectMapper json,JsonNode snapshot,ObjectNode instance,boolean fresh) {
        var sheet=LabWorkbook.sheet(snapshot);if(sheet==null)return;
        var definition=LabWorkbook.definition(snapshot);var model=instance.withObject("/model");
        var fields=model.withArray("/fields");var mappings=instance.withArray("/mapping");
        boolean legacy=false;
        for(var binding:mappings)if(binding.path("dataPath").asText().equals("/sourceGroups"))legacy=true;
        var byCode=new HashMap<String,ObjectNode>();var byBinding=new HashMap<String,ObjectNode>();
        for(var field:fields)if(field instanceof ObjectNode object) {
            byCode.put(field.path("fieldCode").asText(),object);
            if(field.path("displayRole").asText().equals("REGION") && legacy && field.path("id").asText().equals("comparison"))object.put("structuralOnly",true);
        }
        for(var binding:mappings)if(binding instanceof ObjectNode object)byBinding.put(binding.path("fieldCode").asText(binding.path("bindingId").asText()),object);
        var groups=new HashMap<String,String>();for(var group:model.path("groups"))groups.put(group.path("id").asText(),group.path("name").asText());
        int first=definition.path("firstSampleColumn").asInt(2),last=definition.path("lastSampleColumn").asInt(7);
        for(var column:iterable(sheet.path("columnData").fields()))if(column.getValue().path("custom").path("lab").has("sampleKey"))last=Math.max(last,Integer.parseInt(column.getKey()));
        for(var entry:iterable(definition.path("fields").fields())) {
            var code=entry.getKey();var def=entry.getValue();var at=LabWorkbook.findField(sheet,code);if(at==null)continue;
            boolean sample=def.path("scope").asText().equals("SAMPLE");
            var field=byCode.get(code);boolean added=field==null;
            if(added) {
                field=fields.addObject().put("id",code).put("bindingId",code).put("fieldCode",code).put("dataPath","/fields/"+code)
                    .put("groupId",def.path("groupId").asText()).put("name",def.path("label").asText()).put("kind","SCALAR")
                    .put("valueType",def.path("valueType").asText(def.path("resultKind").asText().equals("NUMBER")?"number":"string")).put("unit",def.path("unit").asText())
                    .put("required",def.path("required").asBoolean()).put("fieldOrigin",def.path("local").asBoolean()?"INSTANCE_LOCAL":"TEMPLATE_LOCAL")
                    .put("reviewStatus",def.path("local").asBoolean()&&!def.path("confirmed").asBoolean()?"NEEDS_CONFIRMATION":"CONFIRMED");
                field.set("experimentField",json.createObjectNode().put("domain",def.path("domain").asText("OTHER")).put("field",def.path("semantic").asText("DYNAMIC_VALUE")));
            }
            var binding=byBinding.get(code);
            if(binding==null) {
                binding=mappings.addObject().put("bindingId",field.path("bindingId").asText(code)).put("fieldCode",code).put("dataPath",field.path("dataPath").asText("/fields/"+code))
                    .put("role","FIELD").put("locatorType","CELL_RANGE").put("syncDirection","TWO_WAY").put("bindingStatus","VALID").put("primaryBinding",true);
            }
            binding.put("mappingKind",sample?"REPEAT_FIELD":"SCALAR");
            if(sample)binding.put("parentBindingId","comparison").put("componentId","comparison").put("repeatAxis","COLUMN");
            if(fresh||legacy||added) {
                int labelColumn=sample?(LabWorkbook.value(sheet,at[0],1).isBlank()?0:1):Math.max(0,at[1]-1);
                var valueRange=LabWorkbook.address(at[0],sample?first:at[1])+(sample?":"+LabWorkbook.address(at[0],last):"");
                var locator=binding.putObject("locator").put("sheetId",sheet.path("id").asText()).put("sheetName",sheet.path("name").asText())
                    .put("address",valueRange).put("valueRange",valueRange).put("labelRange",LabWorkbook.address(at[0],labelColumn)).put("valueMode",sample?"ARRAY_ROW":"SCALAR");
                field.set("locator",locator.deepCopy());
                binding.put("labelPath",groups.getOrDefault(field.path("groupId").asText(),field.path("groupId").asText())+" > "+field.path("name").asText());
                if(legacy)field.put("required",def.path("required").asBoolean());
            }
        }
        if(!fresh&&!legacy)return;
        for(var binding:mappings)if(binding.path("mappingKind").asText().equals("REPEAT_REGION")&&binding instanceof ObjectNode object) {
            var locator=object.withObject("/locator");var range=WorkbookStructure.range(WorkbookStructure.first(locator,"dataRange","range"));
            if(range!=null)locator.put("sheetId",sheet.path("id").asText()).put("sheetName",sheet.path("name").asText())
                .put("dataRange",LabWorkbook.address(range[0]-1,first)+":"+LabWorkbook.address(Math.max(range[2]-1,definition.path("lastRow").asInt()),last))
                .put("range",LabWorkbook.address(range[0]-1,1)+":"+LabWorkbook.address(Math.max(range[2]-1,definition.path("lastRow").asInt()),last));
        }
        var originalRecords=new HashMap<Integer,String>();for(var record:instance.path("records"))originalRecords.put(record.path("index").asInt(),record.path("id").asText());
        var records=instance.putArray("records");
        for(int col=first;col<=last;col++)records.addObject().put("id",sheet.path("columnData").path(""+col).path("custom").path("lab").path("sampleKey")
            .asText(originalRecords.getOrDefault(col+1,LabWorkbook.id(sheet.path("id").asText(),"sample:"+col))))
            .put("regionId","comparison").put("sheetId",sheet.path("id").asText()).put("axis","COLUMN").put("index",col+1).put("size",1);
        var originalItems=new HashMap<Integer,String>();for(var item:instance.path("items"))originalItems.put(item.path("index").asInt(),item.path("id").asText());
        var items=instance.putArray("items");
        int start=definition.path("formulaStartRow").asInt(),end=definition.path("formulaEndRow").asInt();
        for(int row=start;row<=end;row++)items.addObject().put("id",sheet.path("cellData").path(""+row).path("1").path("custom").path("lab").path("lineId")
            .asText(originalItems.getOrDefault(row+1,LabWorkbook.id(sheet.path("id").asText(),"material:"+row))))
            .put("regionId","formula-materials").put("sheetId",sheet.path("id").asText()).put("axis","ROW").put("index",row+1).put("size",1);
        for(var projection:instance.path("execution").path("listProjections"))if(projection instanceof ObjectNode object) {
            object.put("labelRange",LabWorkbook.address(start,1)+":"+LabWorkbook.address(end,1))
                .put("valueRange",LabWorkbook.address(start,first)+":"+LabWorkbook.address(end,last));
        }
    }
    private static <T> Iterable<T> iterable(Iterator<T> iterator){return ()->iterator;}
    static void apply(ObjectMapper json,JsonNode snapshot) {
        var instance=WorkbookStructure.instance(snapshot);if(!instance.isObject())return;
        instance=WorkbookStructure.validate(json,snapshot,instance);
        var sheet=LabWorkbook.sheet(snapshot);if(sheet==null)return;
        var definition=(ObjectNode)LabWorkbook.definition(snapshot);var fields=definition.withObject("/fields");
        var definitions=new HashMap<String,JsonNode>();
        for(var f:instance.path("model").path("fields"))definitions.put(f.path("bindingId").asText(),f);
        for(var row:sheet.path("cellData"))for(var c:row)if(c instanceof ObjectNode object&&object.path("custom").isObject()&&!object.path("custom").path("lab").path("role").asText().equals("GROUP"))((ObjectNode)object.path("custom")).remove("lab");
        // Only field/value markers are rebuilt; group markers are restored from confirmed group labels by prepare().
        var present=new HashSet<String>();
        for(var binding:instance.path("mapping")) {
            if(binding.path("mappingKind").asText().equals("REPEAT_REGION"))continue;
            var f=definitions.get(binding.path("bindingId").asText());if(f==null)continue;
            var locator=binding.path("locator");if(!locator.path("sheetId").asText().equals(sheet.path("id").asText()))continue;
            var r=WorkbookStructure.range(WorkbookStructure.first(locator,"valueRange","address"));
            var label=WorkbookStructure.range(WorkbookStructure.first(locator,"labelRange","labelAddress"));if(r==null)continue;
            var code=f.path("fieldCode").asText();if(code.isBlank())continue;present.add(code);
            boolean sample=binding.path("mappingKind").asText().equals("REPEAT_FIELD");
            var def=fields.path(code).isObject()?(ObjectNode)fields.path(code):fields.putObject(code).put("local",true).put("trainingEligible",false);
            var semantic=f.path("experimentField").isObject()?f.path("experimentField"):binding.path("experimentField");
            def.put("fieldCode",code).put("label",f.path("name").asText()).put("groupId",f.path("groupId").asText()).put("unit",f.path("unit").asText()).put("valueType",f.path("valueType").asText("string")).put("confirmed",f.path("reviewStatus").asText().equals("CONFIRMED")).put("scope",sample?"SAMPLE":"EXPERIMENT").put("row",r[0]-1).put("column",r[1]-1);
            // Instance-local fields may start as text and be changed in the shared properties panel.
            // Keep their parser in sync while retaining specialized built-in grade/mixed parsers.
            if(def.path("local").asBoolean()||f.path("fieldOrigin").asText().equals("INSTANCE_LOCAL")||!def.has("resultKind"))
                def.put("resultKind",java.util.Set.of("number","integer","duration").contains(f.path("valueType").asText())?"NUMBER":"TEXT");
            def.put("domain",semantic.path("domain").asText("OTHER")).put("semantic",semantic.path("field").asText("DYNAMIC_VALUE"));
            if(sample&&label!=null)LabWorkbook.cell(sheet,label[0]-1,label[1]-1).withObject("/custom/lab").put("role","LABEL").put("fieldCode",code);
            for(int row=r[0];row<=r[2];row++)for(int c=r[1];c<=r[3];c++)LabWorkbook.cell(sheet,row-1,c-1).withObject("/custom/lab").put("role","VALUE").put("fieldCode",code);
        }
        var remove=new ArrayList<String>();fields.fieldNames().forEachRemaining(code->{if(!present.contains(code))remove.add(code);});fields.remove(remove);
        int last=definition.path("firstSampleColumn").asInt(2);
        for(var record:instance.path("records"))if(record.path("sheetId").asText().equals(sheet.path("id").asText())&&record.path("axis").asText().equals("COLUMN")) {
            int col=record.path("index").asInt()-1;last=Math.max(last,col);
            sheet.withObject("/columnData/"+col+"/custom/lab").put("sampleKey",record.path("id").asText());
        }
        for(var item:instance.path("items"))if(item.path("sheetId").asText().equals(sheet.path("id").asText())) {
            int row=item.path("index").asInt()-1;
            LabWorkbook.cell(sheet,row,1).withObject("/custom/lab").put("role","MATERIAL_CODE").put("lineId",item.path("id").asText());
            for(int c=definition.path("firstSampleColumn").asInt(2);c<=last;c++)LabWorkbook.cell(sheet,row,c).withObject("/custom/lab").put("role","MATERIAL_QTY").put("lineId",item.path("id").asText());
        }
        definition.put("lastSampleColumn",last).put("initialLastSampleColumn",Math.min(last,definition.path("initialLastSampleColumn").asInt(7)));
    }
}
