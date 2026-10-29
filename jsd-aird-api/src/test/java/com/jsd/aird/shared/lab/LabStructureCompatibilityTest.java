package com.jsd.aird.shared.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.excel.WorkbookStructure;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog;
import com.jsd.aird.tpl.infrastructure.XlsxStructureParser;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import static org.assertj.core.api.Assertions.*;

class LabStructureCompatibilityTest {
    @Test void duplicateAndUnlabelledSampleRowsRetainIndependentValues() throws Exception {
        var json=new ObjectMapper();var parser=new XlsxStructureParser(json);var catalog=new UnifiedLabTemplateCatalog(json);
        var pack=catalog.compile(UnifiedLabTemplateCatalog.TEST,parser.parseInstance(getClass().getResourceAsStream("/templates/unified/test.xlsx"))).payload();
        var source=pack.path("snapshot").deepCopy();var sheet=LabWorkbook.sheet(source);
        int first=LabWorkbook.findField(sheet,"contact.water")[0], second=LabWorkbook.findField(sheet,"contact.hexadecane")[0];
        LabWorkbook.cell(sheet,second,1).put("v",LabWorkbook.value(sheet,first,1));
        int note=133;LabWorkbook.cell(sheet,note,1).put("v","");
        LabWorkbook.cell(sheet,135,1).put("v","验收结论");
        for(int c=2;c<10;c++){LabWorkbook.cell(sheet,6,c).put("v","S"+c);LabWorkbook.cell(sheet,first,c).put("v",c);LabWorkbook.cell(sheet,second,c).put("v",c+10);}
        LabWorkbook.cell(sheet,note,7).put("v","方案甲说明");LabWorkbook.cell(sheet,note,8).put("v","方案乙说明");
        var prepared=LabWorkbook.prepare(json,source,pack.path("snapshot"),"duplicates");
        var model=LabWorkbook.extract(json,prepared,json.createObjectNode(),"duplicates");
        assertThat(model.path("sourceGroups")).hasSize(8);
        assertThat(java.util.stream.StreamSupport.stream(model.path("templateInstance").path("issues").spliterator(),false).map(i->i.path("code").asText())).contains("DUPLICATE_FIELD");
        assertThat(java.util.stream.StreamSupport.stream(model.path("testResults").spliterator(),false).filter(i->i.has("duplicateOf")&&i.path("valueStatus").asText().equals("RECORDED"))).hasSize(8);
        assertThat(java.util.stream.StreamSupport.stream(model.path("processSteps").spliterator(),false).filter(i->i.path("supplemental").asBoolean()&&i.path("valueStatus").asText().equals("RECORDED"))).hasSize(2);
        var described=catalog.describe(UnifiedLabTemplateCatalog.TEST,prepared,json.createObjectNode()).payload();
        assertThat(java.util.stream.StreamSupport.stream(described.path("mapping").spliterator(),false).filter(i->i.path("experimentField").path("supplemental").asBoolean()).findFirst().orElseThrow().path("locator").has("labelRange")).isFalse();
        var exported=new SnapshotWorkbookExporter(json).export(LabWorkbook.present(json,prepared).snapshot(),json.createArrayNode(),json.createObjectNode(),null).content();
        var restored=parser.parseInstance(new ByteArrayInputStream(exported)).snapshot();
        var rebound=LabWorkbook.prepare(json,restored,pack.path("snapshot"),"roundtrip");
        var roundtrip=LabWorkbook.extract(json,rebound,json.createObjectNode(),"roundtrip");
        assertThat(java.util.stream.StreamSupport.stream(roundtrip.path("processSteps").spliterator(),false).filter(i->i.path("supplemental").asBoolean()&&i.path("valueStatus").asText().equals("RECORDED"))).hasSize(2);
    }
    @Test void invalidQuantityNeverClaimsTheObservedUnitWasGrams() {
        var json=new ObjectMapper();var field=json.createObjectNode().put("semantic","ACTUAL_QTY").put("resultKind","NUMBER").put("unit","g");
        var parsed=LabValues.parse(json,json.getNodeFactory().textNode("10000 mg"),field,"");
        assertThat(parsed.path("rawUnit").asText()).isEqualTo("mg");
        assertThat(parsed.path("unit").asText()).isEmpty();
        assertThat(parsed.path("parseStatus").asText()).isEqualTo("CONFLICT");
    }
    @Test void preservesAnEnteredTotalWhenTheRecipeIsIncomplete() throws Exception {
        var json=new ObjectMapper();var parser=new XlsxStructureParser(json);
        var pack=new UnifiedLabTemplateCatalog(json).compile(UnifiedLabTemplateCatalog.TEST,parser.parseInstance(getClass().getResourceAsStream("/templates/unified/test.xlsx"))).payload();
        var snapshot=pack.path("snapshot").deepCopy();var sheet=LabWorkbook.sheet(snapshot);
        LabWorkbook.cell(sheet,6,2).put("v","S001");
        LabWorkbook.cell(sheet,51,1).put("v","MAT-A");LabWorkbook.cell(sheet,51,2).put("v",10);
        LabWorkbook.cell(sheet,52,1).put("v","MAT-B");
        var total=LabWorkbook.cell(sheet,59,2);total.remove("f");total.put("v",10.2);
        var prepared=LabWorkbook.prepare(json,snapshot,pack.path("snapshot"),"entered-total");
        var model=LabWorkbook.extract(json,prepared,json.createObjectNode(),"entered-total");
        assertThat(LabWorkbook.cell(LabWorkbook.sheet(prepared),59,2).path("v").asDouble()).isEqualTo(10.2);
        assertThat(model.path("formulaItems").get(0).path("formulaComplete").asBoolean()).isFalse();
        var exported=new SnapshotWorkbookExporter(json).export(LabWorkbook.present(json,prepared).snapshot(),json.createArrayNode(),json.createObjectNode(),null).content();
        var restored=parser.parseInstance(new ByteArrayInputStream(exported)).snapshot();
        var rebound=LabWorkbook.prepare(json,restored,pack.path("snapshot"),"entered-total-reimport");
        LabWorkbook.extract(json,rebound,json.createObjectNode(),"entered-total-reimport");
        var reboundSheet=LabWorkbook.sheet(rebound);var totalRow=LabWorkbook.findField(reboundSheet,"formula.total")[0];
        assertThat(LabWorkbook.cell(reboundSheet,totalRow,2).path("v").asDouble()).isEqualTo(10.2);
        assertThat(LabWorkbook.cell(reboundSheet,totalRow,2).hasNonNull("f")).isFalse();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"string","number","integer","date","datetime","time","duration","boolean"})
    void preservesEveryLocalFieldTypeThroughResultExcelAndReimport(String type)throws Exception {
        var json=new ObjectMapper();var parser=new XlsxStructureParser(json);var catalog=new UnifiedLabTemplateCatalog(json);
        var pack=catalog.compile(UnifiedLabTemplateCatalog.TEST,parser.parseInstance(getClass().getResourceAsStream("/templates/unified/test.xlsx"))).payload();
        var snapshot=pack.path("snapshot").deepCopy();var sheet=LabWorkbook.sheet(snapshot);
        sheet.withObject("/columnData/2").put("w",170);sheet.withObject("/rowData/138").put("h",36);
        LabWorkbook.cell(sheet,6,2).put("v","S001");LabWorkbook.cell(sheet,138,1).put("v","验收格式");
        LabWorkbook.cell(sheet,138,2).put("v",switch(type){case "date"->"2026-10-26";case "datetime"->"2026-10-26 12:34:56";case "time"->"12:34:56";case "duration"->"36:00:00";case "boolean"->"否";case "string"->"00123";default->"9";});
        double day=org.apache.poi.ss.usermodel.DateUtil.getExcelDate(java.time.LocalDate.of(2026,10,26));
        if(java.util.Set.of("date","datetime","time").contains(type)) {
            LabWorkbook.cell(sheet,138,2).put("v",type.equals("time")?0.5:day+(type.equals("datetime")?0.5:0));
            LabWorkbook.cell(sheet,138,2).withObject("/s/n").put("pattern",type.equals("date")?"yyyy-mm-dd":type.equals("time")?"hh:mm:ss":"yyyy-mm-dd hh:mm:ss");
        }
        var instance=WorkbookStructure.seed(json,snapshot,pack.path("schema"),pack.path("mapping"),"types");
        instance.withArray("/model/fields").addObject().put("id","typed").put("bindingId","typed").put("fieldCode","INSTANCE_LOCAL.typed").put("name","验收格式").put("groupId","film").put("kind","SCALAR").put("valueType",type).put("fieldOrigin","INSTANCE_LOCAL").put("reviewStatus","CONFIRMED").withObject("/experimentField").put("domain","TEST").put("field","VALUE");
        instance.withArray("/mapping").addObject().put("bindingId","typed").put("fieldCode","INSTANCE_LOCAL.typed").put("parentBindingId","comparison").put("componentId","comparison").put("dataPath","/customFields/typed").put("mappingKind","REPEAT_FIELD").put("repeatAxis","COLUMN").put("bindingStatus","VALID")
            .withObject("/locator").put("sheetId",sheet.path("id").asText()).put("sheetName",sheet.path("name").asText()).put("labelRange","B139").put("address","C139:H139").put("valueRange","C139:H139");
        WorkbookStructure.attach(snapshot,instance);
        var prepared=LabWorkbook.prepare(json,snapshot,pack.path("snapshot"),"types");
        var bytes=new SnapshotWorkbookExporter(json).export(LabWorkbook.present(json,prepared).snapshot(),json.createArrayNode(),json.createObjectNode(),null).content();
        var restored=parser.parseInstance(new ByteArrayInputStream(bytes)).snapshot();
        var rebound=LabWorkbook.prepare(json,restored,pack.path("snapshot"),"types-reimport");
        assertThat(LabWorkbook.sheet(rebound).path("columnData").path("2").path("w").asDouble()).isCloseTo(170,within(2d));
        assertThat(LabWorkbook.definition(rebound).path("fields").path("INSTANCE_LOCAL.typed").path("valueType").asText()).isEqualTo(type);
        var described=catalog.describe(UnifiedLabTemplateCatalog.TEST,rebound,json.createObjectNode()).payload();
        var field=java.util.stream.StreamSupport.stream(described.path("schema").path("x-jsd-field-model").path("fields").spliterator(),false).filter(f->f.path("fieldCode").asText().equals("INSTANCE_LOCAL.typed")).findFirst().orElseThrow();
        assertThat(field.path("valueType").asText()).isEqualTo(type);
        assertThat(field.path("fieldOrigin").asText()).isEqualTo("INSTANCE_LOCAL");
        var again=LabWorkbook.extract(json,rebound,json.createObjectNode(),"types-reimport");
        var value=java.util.stream.StreamSupport.stream(again.path("testResults").spliterator(),false).filter(f->f.path("fieldCode").asText().equals("INSTANCE_LOCAL.typed")).findFirst().orElseThrow();
        assertThat(value.path("parseStatus").asText()).isNotEqualTo("CONFLICT");
        if(type.equals("date"))assertThat(value.path("value").asText()).isEqualTo("2026-10-26");
        if(type.equals("datetime"))assertThat(value.path("value").asText()).isEqualTo("2026-10-26 12:00:00");
        if(type.equals("time"))assertThat(value.path("value").asText()).isEqualTo("12:00:00");
    }
    @Test void editingLocalFieldTypeAndUnitReplacesItsPreviouslyTextParser()throws Exception {
        var json=new ObjectMapper();var parser=new XlsxStructureParser(json);var catalog=new UnifiedLabTemplateCatalog(json);
        var pack=catalog.compile(UnifiedLabTemplateCatalog.TEST,parser.parseInstance(getClass().getResourceAsStream("/templates/unified/test.xlsx"))).payload();
        var snapshot=pack.path("snapshot").deepCopy();var sheet=LabWorkbook.sheet(snapshot);
        var instance=WorkbookStructure.seed(json,snapshot,pack.path("schema"),pack.path("mapping"),"unit-edit");
        var field=instance.withArray("/model/fields").addObject().put("id","water").put("bindingId","water").put("fieldCode","INSTANCE_LOCAL.water").put("name","涂料含水").put("groupId","coating").put("kind","SCALAR").put("valueType","number").put("unit","g").put("fieldOrigin","INSTANCE_LOCAL").put("reviewStatus","CONFIRMED");
        field.withObject("/experimentField").put("domain","TEST").put("field","VALUE");
        instance.withArray("/mapping").addObject().put("bindingId","water").put("fieldCode","INSTANCE_LOCAL.water").put("parentBindingId","comparison").put("componentId","comparison").put("dataPath","/customFields/water").put("mappingKind","REPEAT_FIELD").put("repeatAxis","COLUMN").put("bindingStatus","VALID")
            .withObject("/locator").put("sheetId",sheet.path("id").asText()).put("sheetName",sheet.path("name").asText()).put("labelRange","B139").put("address","C139:H139").put("valueRange","C139:H139");
        var definitions=(ObjectNode)LabWorkbook.definition(snapshot).path("fields");
        definitions.putObject("INSTANCE_LOCAL.water").put("local",true).put("resultKind","TEXT").put("unit","%");
        var gradeCode=java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(definitions.fields(),0),false).filter(entry->entry.getValue().path("resultKind").asText().equals("ADHESION")).findFirst().orElseThrow().getKey();
        WorkbookStructure.attach(snapshot,instance);LabStructureAdapter.apply(json,snapshot);
        var updated=definitions.path("INSTANCE_LOCAL.water");
        assertThat(updated.path("resultKind").asText()).isEqualTo("NUMBER");assertThat(updated.path("unit").asText()).isEqualTo("g");
        assertThat(LabValues.parse(json,json.getNodeFactory().numberNode(9),updated,"General").path("value").decimalValue()).isEqualByComparingTo("9");
        assertThat(definitions.path(gradeCode).path("resultKind").asText()).isEqualTo("ADHESION");
        field.put("valueType","string").put("unit","");WorkbookStructure.attach(snapshot,instance);LabStructureAdapter.apply(json,snapshot);
        assertThat(updated.path("resultKind").asText()).isEqualTo("TEXT");assertThat(updated.path("unit").asText()).isEmpty();
    }
    @Test void seedsTheBoundVersionWithoutChangingGroupsOrLosingSourceIdentities()throws Exception {
        var json=new ObjectMapper();var parser=new XlsxStructureParser(json);var catalog=new UnifiedLabTemplateCatalog(json);
        for(var kind:java.util.List.of("test","synthesis")) {
            var pack=catalog.compile(kind.equals("test")?UnifiedLabTemplateCatalog.TEST:UnifiedLabTemplateCatalog.SYNTHESIS,
                parser.parseInstance(getClass().getResourceAsStream("/templates/unified/"+kind+".xlsx"))).payload();
            var snapshot=LabWorkbook.prepare(json,parser.parseInstance(getClass().getResourceAsStream("/unified-lab/"+kind+"-example.xlsx")).snapshot(),pack.path("snapshot"),"acceptance");
            var source=LabWorkbook.extract(json,snapshot,json.createObjectNode(),"acceptance");
            var instance=WorkbookStructure.seed(json,snapshot,pack.path("schema"),pack.path("mapping"),"acceptance");
            assertThat(instance.path("model").path("groups")).isEqualTo(pack.path("schema").path("x-jsd-field-model").path("groups"));
            assertThat(instance.path("records")).hasSize(6);
            for(int i=0;i<6;i++)assertThat(instance.path("records").get(i).path("id")).isEqualTo(source.path("templateInstance").path("sampleOrder").get(i));
            var expected=pack.path("schema").path("x-jsd-field-model").path("fields");
            for(var field:expected) {
                var actual=java.util.stream.StreamSupport.stream(instance.path("model").path("fields").spliterator(),false)
                    .filter(f->f.path("id").equals(field.path("id"))).findFirst().orElseThrow();
                for(var key:java.util.List.of("name","groupId","unit","valueType","required","experimentField"))assertThat(actual.path(key)).as(key).isEqualTo(field.path(key));
            }
            var before=snapshot.deepCopy();var again=WorkbookStructure.seed(json,snapshot,pack.path("schema"),pack.path("mapping"),"another-seed");
            assertThat(again).isEqualTo(instance);assertThat(snapshot).isEqualTo(before);
        }
    }
    @Test void genericLocalFieldsKeepTheirMeaningAndStayOutOfTrainingAcrossExcelRoundTrip()throws Exception {
        var json=new ObjectMapper();var parser=new XlsxStructureParser(json);var catalog=new UnifiedLabTemplateCatalog(json);
        var pack=catalog.compile(UnifiedLabTemplateCatalog.TEST,parser.parseInstance(getClass().getResourceAsStream("/templates/unified/test.xlsx"))).payload();
        var snapshot=pack.path("snapshot").deepCopy();var sheet=LabWorkbook.sheet(snapshot);
        LabWorkbook.cell(sheet,0,1).put("v","Generic test");LabWorkbook.cell(sheet,1,1).put("v","Tester");LabWorkbook.cell(sheet,1,3).put("v","2026-10-25");
        LabWorkbook.cell(sheet,6,2).put("v","S001");LabWorkbook.cell(sheet,51,1).put("v","MAT-A");LabWorkbook.cell(sheet,51,2).put("v","100g");
        LabWorkbook.cell(sheet,138,1).put("v","临时耐湿热");LabWorkbook.cell(sheet,138,2).put("v","轻微发白");
        var instance=WorkbookStructure.seed(json,snapshot,pack.path("schema"),pack.path("mapping"),"instance");
        instance.withArray("/model/fields").addObject().put("id","new-field").put("bindingId","new-binding").put("fieldCode","INSTANCE_LOCAL.test.FIELD_generic").put("name","临时耐湿热").put("groupId","film").put("kind","SCALAR").put("valueType","string").put("fieldOrigin","INSTANCE_LOCAL").put("reviewStatus","CONFIRMED").withObject("/experimentField").put("domain","TEST").put("field","VALUE");
        var binding=instance.withArray("/mapping").addObject().put("bindingId","new-binding").put("fieldCode","INSTANCE_LOCAL.test.FIELD_generic").put("parentBindingId","comparison").put("componentId","comparison").put("dataPath","/customFields/new-field").put("mappingKind","REPEAT_FIELD").put("repeatAxis","COLUMN").put("bindingStatus","VALID");
        binding.withObject("/locator").put("sheetId",sheet.path("id").asText()).put("sheetName",sheet.path("name").asText()).put("labelRange","B139").put("address","C139:H139").put("valueRange","C139:H139");
        WorkbookStructure.attach(snapshot,instance);
        var prepared=LabWorkbook.prepare(json,snapshot,pack.path("snapshot"),"generic");var model=LabWorkbook.extract(json,prepared,json.createObjectNode(),"generic");
        assertThat(model.path("sourceGroups")).hasSize(1);assertThat(model.path("templateInstance").path("issues")).isEmpty();
        var description=catalog.describe(UnifiedLabTemplateCatalog.TEST,prepared,json.createObjectNode()).payload();
        var found=false;for(var b:description.path("mapping"))if(b.path("fieldCode").asText().equals("INSTANCE_LOCAL.test.FIELD_generic")){found=true;assertThat(b.path("trainingEligible").asBoolean()).isFalse();}
        assertThat(found).isTrue();
        var bytes=new SnapshotWorkbookExporter(json).export(LabWorkbook.present(json,prepared).snapshot(),json.createArrayNode(),json.createObjectNode(),null).content();
        var restored=parser.parseInstance(new ByteArrayInputStream(bytes)).snapshot();
        assertThat(WorkbookStructure.instance(restored).isObject()).isFalse();
        var rebound=LabWorkbook.prepare(json,restored,pack.path("snapshot"),"reimport");
        var at=LabWorkbook.findField(LabWorkbook.sheet(rebound),"INSTANCE_LOCAL.test.FIELD_generic");assertThat(at).isNotNull();
        assertThat(LabWorkbook.value(LabWorkbook.sheet(rebound),at[0],2)).isEqualTo("轻微发白");
        var again=LabWorkbook.extract(json,rebound,json.createObjectNode(),"reimport");
        assertThat(again.path("sourceGroups").get(0).path("sampleKey")).isEqualTo(model.path("sourceGroups").get(0).path("sampleKey"));
    }
}
