package com.jsd.aird.tpl.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.shared.lab.LabValues;
import com.jsd.aird.shared.lab.LabWorkbook;
import com.jsd.aird.shared.excel.WorkbookInstanceParser;
import java.util.*;

/** Offline compilation of reviewed customer forms. No AI, sample data or filesystem dependency at runtime. */
public final class UnifiedLabTemplateCatalog {
    public static final String TEST="EXP_TEST_UNIFIED",SYNTHESIS="EXP_SYNTHESIS_UNIFIED";
    public static final int PACKAGE_VERSION=1;
    private final ObjectMapper json;
    public UnifiedLabTemplateCatalog(ObjectMapper json){this.json=json;}
    public Package compile(String code,WorkbookInstanceParser.ParsedWorkbook parsed) {
        boolean test=code.equals(TEST);if(!test&&!code.equals(SYNTHESIS))throw new IllegalArgumentException("Unknown unified template");
        var snapshot=(ObjectNode)parsed.snapshot().deepCopy();
        snapshot.put("id",LabWorkbook.id(code,"workbook")).put("name",test?"测试实验":"合成实验");
        var sheet=(ObjectNode)snapshot.path("sheets").path("sheet-1");
        var def=json.createObjectNode().put("version",1).put("templateCode",code).put("kind",test?"TEST":"SYNTHESIS").put("firstSampleColumn",2).put("initialLastSampleColumn",7).put("lastRow",test?137:53);
        var fields=def.putObject("fields");var regions=def.putArray("regions");
        region(regions,"basic","基础信息",0,5);
        if(test){region(regions,"conditions","实验条件",6,17);region(regions,"resin","树脂物性",18,47);region(regions,"plan","实验方案",48,49);region(regions,"formula","实验配方",50,59);region(regions,"coating","涂料物性",60,62);region(regions,"film","涂层性能",63,134);region(regions,"conclusion","实验结论",135,136);region(regions,"notes","备注",137,137);}
        else {region(regions,"plan","实验方案",6,8);region(regions,"formula","配方",9,22);region(regions,"design","设计参数",23,34);region(regions,"process","合成工艺",35,41);region(regions,"appearance","出料外观",42,44);region(regions,"results","测试结果",45,49);region(regions,"best","最优方案",50,50);region(regions,"notes","备注",51,51);region(regions,"conclusion","小结",52,53);}
        global(fields,sheet,"basic.title",0,1,"实验题目","TITLE",true);
        global(fields,sheet,"basic.owner",1,1,"实验人","OWNER",true);
        global(fields,sheet,"basic.date",1,3,"日期","EXPERIMENT_DATE",true);
        global(fields,sheet,"basic.temperature",1,5,"环境温度（℃）","DYNAMIC_VALUE",false);
        global(fields,sheet,"basic.humidity",1,7,"相对湿度（%）","DYNAMIC_VALUE",false);
        global(fields,sheet,"basic.project",2,1,"关联项目","DYNAMIC_VALUE",false);
        global(fields,sheet,"basic.projectNo",3,1,"项目编号","DYNAMIC_VALUE",false);
        global(fields,sheet,"basic.background",4,1,"实验背景","DYNAMIC_VALUE",false);
        global(fields,sheet,"basic.purpose",5,1,"实验目的","PURPOSE",false);
        if(test) {
            String[] condition={"sample.number","condition.substrate","condition.application","curing.mode","curing.equipment","curing.temperature","curing.time","curing.wavelength","curing.intensity","curing.energy","curing.ebEnergy","curing.ebDose"};
            rows(fields,sheet,6,condition,"conditions","PROCESS");
            String[] resin={"resin.materialCode","resin.composition","resin.appearance","resin.solids","resin.surfaceDry","resin.viscosity","resin.water","resin.molecularWeight","resin.irSimilarity","resin.gcPurity","resin.uvPeak","resin.refractiveIndex","resin.density","resin.fineness","resin.particleSize","resin.ph","resin.surfaceTension","resin.acidValue","resin.hydroxylValue","resin.nco","resin.conductivity","resin.flashPoint","resin.coldStability","resin.heatStability","resin.separation","resin.compatibility","resin.solubility","resin.fluorine","resin.amineEquivalent"};
            rows(fields,sheet,18,resin,"resin","TEST");
            rows(fields,sheet,48,new String[]{"plan.detail","plan.variable"},"plan","PROCESS");
            sample(fields,sheet,"sample.experimentNo",50,"formula","BASIC","SOURCE_IDENTITY");
            sample(fields,sheet,"formula.total",59,"formula","FORMULA","ACTUAL_QTY").put("valueSource","FORMULA");
            rows(fields,sheet,60,new String[]{"coating.appearance","coating.solids","coating.viscosity"},"coating","TEST");
            String[] film={"film.curing","film.appearance","film.thickness","optical.gloss20","optical.gloss60","optical.gloss85","optical.haze","optical.transmittance","hardness.1000g","hardness.700g","hardness.500g","adhesion.abs_pc","adhesion.abs","adhesion.pc","adhesion.pet","adhesion.pmma","adhesion.glass","adhesion.tinplate","adhesion.aluminum","adhesion.steel","adhesion.pp","adhesion.nylon","adhesion.tpu","adhesion.pvc","adhesion.afterBoiling.pc","adhesion.afterBoiling.pet","adhesion.afterBoiling.pmma","optical.deltaE","optical.b","optical.l","abrasion.steel300g1cm","abrasion.steel500g1cm","abrasion.steel1000g1cm","abrasion.steel300g2cm","abrasion.steel500g2cm","abrasion.steel1000g2cm","abrasion.rca","abrasion.taberHaze","abrasion.taberTransmittance","abrasion.eraser","contact.water","contact.hexadecane","contact.diiodomethane","boiling.appearance","boiling.contactWater","boiling.contactHexadecane","boiling.contactDiiodomethane","marker.blue","marker.black","marker.double","marker.fluorescent","marker.dyne","mechanical.flexibility","mechanical.curvature","chemical.rain","chemical.solvent","chemical.mustard","chemical.sunscreen","aging.deltaE","aging.quvDeltaE","mechanical.resistance","mechanical.tensile","mechanical.elongation","mechanical.peel","mechanical.coatingElongation","repair.time","friction.static","friction.dynamic","scratch.nail","scratch.copper"};
            rows(fields,sheet,63,film,"film","TEST");
            global(fields,sheet,"conclusion.main",135,1,"实验结论","MAIN_CONCLUSION",false);
            global(fields,sheet,"notes.text",137,1,"备注","DYNAMIC_VALUE",false);
        } else {
            global(fields,sheet,"plan.shared",6,1,"实验方案","PLAN",false);
            // Merged A:B label bands are independent of the recipe header.
            sample(fields,sheet,"plan.content",7,"plan","PROCESS","APPLICATION_CONDITION");
            sample(fields,sheet,"sample.experimentNo",8,"plan","BASIC","SOURCE_IDENTITY");
            sample(fields,sheet,"formula.total",23,"formula","FORMULA","ACTUAL_QTY").put("valueSource","FORMULA");
            rows(fields,sheet,24,new String[]{"design.solidMass","design.theoreticalHalfSolids","design.actualHalfSolids","design.theoreticalHydroxyl","design.conversion","design.theoreticalMass","design.productMass","design.recovery","design.fluorine","design.initiator"},"design","PROCESS");
            rows(fields,sheet,35,new String[]{"process.summary","process.temperature","process.vacuumTemperature","process.additionTemperature","process.time"},"process","PROCESS");
            rows(fields,sheet,42,new String[]{"appearance.beforePurification","appearance.afterPurification","appearance.afterCooling"},"appearance","TEST");
            rows(fields,sheet,45,new String[]{"product.viscosity","product.solids","product.refractiveIndex","product.water"},"results","TEST");
            sample(fields,sheet,"best.choice",50,"best","OTHER","DYNAMIC_VALUE");
            global(fields,sheet,"notes.text",51,1,"备注","DYNAMIC_VALUE",false);
            global(fields,sheet,"conclusion.main",52,1,"小结","MAIN_CONCLUSION",false);
        }
        def.set("adhesionGrades",json.valueToTree(LabValues.ADHESION));def.set("hardnessGrades",json.valueToTree(LabValues.HARDNESS));
        sheet.withObject("/custom").set(LabWorkbook.DEFINITION,def);
        var prepared=LabWorkbook.prepare(json,snapshot,snapshot,code);
        return describe(code,prepared,parsed.structureSummary());
    }
    public Package describe(String code,JsonNode source,JsonNode physicalLayout) {
        boolean test=code.equals(TEST);
        var prepared=(ObjectNode)LabWorkbook.blank(source);
        var sheet=LabWorkbook.sheet(prepared);var def=(ObjectNode)LabWorkbook.definition(prepared);
        var fields=(ObjectNode)def.path("fields");var regions=def.path("regions");
        var absent=new ArrayList<String>();
        for(var entry:iterable(fields.fields())) {
            var at=LabWorkbook.findField(sheet,entry.getKey());if(at==null){absent.add(entry.getKey());continue;}
            ((ObjectNode)entry.getValue()).put("row",at[0]).put("column",entry.getValue().path("scope").asText().equals("SAMPLE")?2:at[1]);
        }
        fields.remove(absent);
        var schema=json.createObjectNode().put("type","object");schema.set("x-jsd-lab-template",LabWorkbook.definition(prepared).deepCopy());
        var fm=schema.putObject("x-jsd-field-model").put("modelVersion",1);var fg=fm.putArray("groups");var ff=fm.putArray("fields");fm.putArray("blocks");fm.putArray("semanticAnnotations");
        int ordinal=0;for(var region:regions)fg.addObject().put("id",region.path("id").asText()).put("name",region.path("label").asText()).put("order",ordinal++);
        var mapping=json.createArrayNode();
        var root=mapping.addObject().put("bindingId","comparison").put("componentId","comparison").put("dataPath","/samples").put("role","REPEAT_REGION").put("mappingKind","REPEAT_REGION").put("repeatAxis","COLUMN").put("recordWidth",1).put("recordHeight",1).put("recordStride",1).put("locatorType","TABLE_REGION").put("syncDirection","TWO_WAY").put("primaryBinding",true).put("bindingStatus","VALID");
        int lastColumn=def.path("lastSampleColumn").asInt(7),sampleLastRow=6;
        for(var f:fields)if(f.path("scope").asText().equals("SAMPLE"))sampleLastRow=Math.max(sampleLastRow,f.path("row").asInt());
        root.set("locator",json.createObjectNode().put("sheetId",sheet.path("id").asText()).put("sheetName",sheet.path("name").asText()).put("range","B7:"+LabWorkbook.address(sampleLastRow,lastColumn)).put("dataRange","C7:"+LabWorkbook.address(sampleLastRow,lastColumn)).put("componentId","comparison").put("structureType","COLUMN_TABLE"));
        root.set("termination",json.createObjectNode().put("type","UNTIL_REGION_END"));
        root.set("diagnostic",json.createObjectNode().put("kind","COLUMN_TABLE").put("businessName","实验对照组"));
        for(var entry:iterable(fields.fields())) {
            var f=entry.getValue();var sample=f.path("scope").asText().equals("SAMPLE");int r=f.path("row").asInt(),c=f.path("column").asInt();var key=entry.getKey();
            var groupLabel=f.path("groupId").asText();
            var valueType=f.path("valueType").asText("string");
            for(var region:regions)if(region.path("id").asText().equals(groupLabel)){groupLabel=region.path("label").asText();break;}
            var binding=mapping.addObject().put("bindingId",key).put("fieldCode",key).put("dataPath","/fields/"+key).put("role","FIELD").put("mappingKind",sample?"REPEAT_FIELD":"SCALAR").put("locatorType","CELL_RANGE").put("syncDirection",f.path("valueSource").asText().equals("FORMULA")?"EDITOR_TO_DATA":"TWO_WAY").put("primaryBinding",true).put("bindingStatus","VALID").put("required",f.path("required").asBoolean()).put("valueType",valueType.equals("string")?"TEXT":valueType.toUpperCase(Locale.ROOT)).put("valueSource",f.path("valueSource").asText("USER_INPUT")).put("unit",f.path("unit").asText()).put("trainingEligible",sample&&!f.path("local").asBoolean()).put("ragEligible",true).put("labelPath",groupLabel+" > "+f.path("label").asText());
            if(sample)binding.put("parentBindingId","comparison").put("componentId","comparison").put("repeatAxis","COLUMN").put("recordWidth",1).put("recordHeight",1).put("recordStride",1);
            binding.set("experimentField",json.createObjectNode().put("domain",f.path("domain").asText()).put("field",f.path("semantic").asText()));
            int labelColumn=sample?(LabWorkbook.value(sheet,r,1).isBlank()?0:1):Math.max(0,c-1);
            binding.set("locator",json.createObjectNode().put("sheetId",sheet.path("id").asText()).put("sheetName",sheet.path("name").asText()).put("labelRange",LabWorkbook.address(r,labelColumn)).put("address",LabWorkbook.address(r,c)+(sample?":"+LabWorkbook.address(r,lastColumn):"")).put("valueRange",LabWorkbook.address(r,c)+(sample?":"+LabWorkbook.address(r,lastColumn):"")).put("valueMode",sample?"ARRAY_ROW":"SCALAR"));
            var businessField=ff.addObject().put("id",key).put("bindingId",key).put("fieldCode",key).put("dataPath","/fields/"+key).put("name",f.path("label").asText()).put("groupId",f.path("groupId").asText()).put("kind","SCALAR").put("valueType",valueType).put("unit",f.path("unit").asText()).put("required",f.path("required").asBoolean()).put("reviewStatus","CONFIRMED").put("fieldOrigin",f.path("local").asBoolean()?"INSTANCE_LOCAL":"TEMPLATE_LOCAL").put("labelStatus","RESOLVED").put("experimentSemanticStatus","CONFIRMED");
            businessField.set("experimentField",binding.path("experimentField").deepCopy());
        }
        var config=schema.putObject("x-jsd-experiment-import").put("templateUsage","EXPERIMENT_DATA").put("recordMode","SINGLE_FILE");
        config.putArray("identities").addObject().put("identityType","EXPERIMENT_NO").put("sourceKind","BINDING").put("componentId","comparison").put("bindingId","sample.experimentNo");
        var d=LabWorkbook.definition(prepared);config.putArray("listProjections").addObject().put("listProjectionId","formula-materials").put("domain","FORMULA").put("componentId","comparison").put("parentBindingId","comparison").put("recordAxis","COLUMN").put("itemAxis","ROW").put("labelRange","B"+(d.path("formulaStartRow").asInt()+1)+":B"+(d.path("formulaEndRow").asInt()+1)).put("valueRange","C"+(d.path("formulaStartRow").asInt()+1)+":"+LabWorkbook.address(d.path("formulaEndRow").asInt(),lastColumn)).put("labelSemantic","MATERIAL_CODE").put("valueSemantic","ACTUAL_QTY");
        var projection=(ObjectNode)config.path("listProjections").get(0);
        projection.put("displayName",test?"实验配方":"配方").put("unit","g");
        projection.putObject("labelField").put("name","物料编码");
        projection.putObject("valueField").put("name","用量");
        var layout=(ObjectNode)physicalLayout.deepCopy();layout.set("initialSnapshot",prepared);
        var canon=new JsonCanonicalizer(json);var contract=new TemplateImportContractCompiler(json,canon).compile(layout,schema,mapping);
        var payload=json.createObjectNode().put("templateCode",code).put("versionNo",PACKAGE_VERSION).put("name",test?"测试实验":"合成实验");payload.set("snapshot",prepared);payload.set("schema",schema);payload.set("mapping",mapping);payload.set("layout",layout);payload.set("contract",contract.contract());
        return new Package(code,payload,canon.hash(prepared),canon.hash(payload));
    }
    private void region(ArrayNode regions,String id,String label,int first,int last){regions.addObject().put("id",id).put("label",label).put("startRow",first).put("endRow",last);}
    private ObjectNode global(ObjectNode fields,ObjectNode sheet,String code,int row,int column,String label,String semantic,boolean required){
        var domain=semantic.equals("MAIN_CONCLUSION")?"CONCLUSION":semantic.equals("DYNAMIC_VALUE")?"OTHER":"BASIC";
        var group=code.startsWith("conclusion.")?"conclusion":code.startsWith("notes.")?"notes":code.startsWith("plan.")?"plan":"basic";
        return field(fields,sheet,code,row,column,label,group,domain,semantic,"EXPERIMENT").put("required",required);
    }
    private void rows(ObjectNode fields,ObjectNode sheet,int first,String[] codes,String group,String domain){for(int i=0;i<codes.length;i++)sample(fields,sheet,codes[i],first+i,group,domain,"TEST".equals(domain)?"VALUE":"APPLICATION_CONDITION");}
    private ObjectNode sample(ObjectNode fields,ObjectNode sheet,String code,int row,String group,String domain,String semantic){
        var label=LabWorkbook.value(sheet,row,1);if(label.isBlank())label=LabWorkbook.value(sheet,row,0);
        var f=field(fields,sheet,code,row,2,label,group,domain,semantic,"SAMPLE");
        LabWorkbook.cell(sheet,row,1).withObject("/custom/lab").put("role","LABEL").put("fieldCode",code);
        for(int c=2;c<8;c++)LabWorkbook.cell(sheet,row,c).withObject("/custom/lab").put("role","VALUE").put("fieldCode",code);
        return f;
    }
    private ObjectNode field(ObjectNode fields,ObjectNode sheet,String code,int row,int column,String label,String group,String domain,String semantic,String scope){
        var f=fields.putObject(code).put("fieldCode",code).put("label",label).put("groupId",group).put("domain",domain).put("semantic",semantic).put("scope",scope).put("row",row).put("column",column).put("required",false).put("valueSource","USER_INPUT");
        var unit=unit(label,code);var kind=code.startsWith("adhesion.")?"ADHESION":code.startsWith("hardness.")?"HARDNESS":unit.isEmpty()?"TEXT":"MIXED";
        if(code.matches(".*(?:refractiveIndex|ph|[.]static|[.]dynamic|deltaE|[.]b|[.]l)$"))kind="MIXED";
        f.put("unit",unit).put("resultKind",kind).put("trainingEligible",!domain.equals("OTHER")&&!semantic.equals("SOURCE_IDENTITY"));
        if(code.endsWith(".viscosity"))f.set("allowedUnits",json.valueToTree(List.of("cP","mPa·s","KU","s")));
        if(code.endsWith(".time"))f.set("allowedUnits",json.valueToTree(List.of("s","min","h")));
        if(code.endsWith(".particleSize"))f.set("allowedUnits",json.valueToTree(List.of("nm","μm")));
        if(code.equals("curing.ebEnergy"))f.set("allowedUnits",json.valueToTree(List.of("keV","MeV")));
        if(code.endsWith("curvature"))f.put("parser","CURVATURE");
        LabWorkbook.cell(sheet,row,column).withObject("/custom/lab").put("role","VALUE").put("fieldCode",code);
        return f;
    }
    private String unit(String label,String code){
        if(code.endsWith(".viscosity"))return "cP";if(code.endsWith(".time"))return "s";if(code.equals("curing.ebEnergy"))return "keV";if(code.endsWith("particleSize"))return "nm";
        for(var unit:List.of("mg KOH/g","mW/cm²","mJ/cm²","g/eq","g/mL","μS/cm","mN/m","MPa","kGy","GU","μm","nm","℃","%","cm","Ω","N","°","g","次"))if(label.contains("（"+unit+"）")||label.contains("（"+unit+"/")||label.contains("（"+unit+" /"))return unit;
        return "";
    }
    private static <T>Iterable<T> iterable(Iterator<T> it){return ()->it;}
    public record Package(String code,ObjectNode payload,String snapshotHash,String packageHash){}
}
