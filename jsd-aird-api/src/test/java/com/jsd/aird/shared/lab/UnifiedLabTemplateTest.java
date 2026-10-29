package com.jsd.aird.shared.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog;
import com.jsd.aird.tpl.infrastructure.XlsxStructureParser;
import com.jsd.aird.shared.office.SnapshotWorkbookExporter;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class UnifiedLabTemplateTest {
    private final ObjectMapper json=new ObjectMapper();
    private UnifiedLabTemplateCatalog.Package template(String kind)throws Exception {
        try(var input=getClass().getResourceAsStream("/templates/unified/"+kind+".xlsx")) {
            return new UnifiedLabTemplateCatalog(json).compile(kind.equals("test")?UnifiedLabTemplateCatalog.TEST:UnifiedLabTemplateCatalog.SYNTHESIS,new XlsxStructureParser(json).parseInstance(input));
        }
    }
    private ObjectNode filled(String kind)throws Exception {
        var t=template(kind);try(var input=getClass().getResourceAsStream("/unified-lab/"+kind+"-example.xlsx")) {
            var source=new XlsxStructureParser(json).parse(input).initialEditorSnapshot();
            return LabWorkbook.prepare(json,source,t.payload().path("snapshot"),"fixture-"+kind);
        }
    }
    @Test void compilesBothReviewedFormsAsExecutableV9Contracts()throws Exception {
        for(var kind:List.of("test","synthesis")) {
            var t=template(kind);
            assertThat(t.payload().path("contract").path("importContractVersion").asInt()).isEqualTo(9);
            assertThat(t.payload().path("contract").path("experimentImport").path("recordMode").asText()).isEqualTo("SINGLE_FILE");
            assertThat(LabWorkbook.supported(t.payload().path("snapshot"))).isTrue();
            assertThat(t.packageHash()).isEqualTo(template(kind).packageHash());
        }
    }
    @Test void recordsSixGroupsAndGramsWithoutTurningThemIntoRatios()throws Exception {
        for(var kind:List.of("test","synthesis")) {
            var snapshot=filled(kind);var model=LabWorkbook.extract(json,snapshot,json.createObjectNode(),kind);
            assertThat(model.path("sourceGroups")).hasSize(6);
            for(var group:model.path("sourceGroups")) {
                var key=group.path("sampleKey").asText();double total=0;
                for(var item:model.path("formulaItems"))if(key.equals(item.path("sampleKey").asText())) {
                    assertThat(item.has("ratio")).isFalse();total+=item.path("actualQty").asDouble();
                }
                assertThat(total).isCloseTo(kind.equals("test")?100:1000,within(0.000001));
            }
            assertThat(model.path("templateInstance").path("issues")).isEmpty();
        }
    }
    @Test void distinguishesStatesAndPreservesGradesTextLimitsAndUnits()throws Exception {
        var number=json.createObjectNode().put("resultKind","NUMBER").put("unit","g").put("semantic","ACTUAL_QTY");
        assertThat(LabValues.parse(json,json.getNodeFactory().textNode("90g"),number,"").path("value").asInt()).isEqualTo(90);
        assertThat(LabValues.parse(json,json.getNodeFactory().textNode("90kg"),number,"").path("parseStatus").asText()).isEqualTo("CONFLICT");
        assertThat(LabValues.state("/",true)).isEqualTo("ABSENT");assertThat(LabValues.state("/",false)).isEqualTo("NOT_APPLICABLE");
        assertThat(LabValues.state("",false)).isEqualTo("NOT_MEASURED");assertThat(LabValues.recorded("0")).isTrue();assertThat(LabValues.recorded("不合格")).isTrue();assertThat(LabValues.recorded("8 s / 2#杯")).isTrue();
        var time=json.createObjectNode().put("resultKind","MIXED").put("unit","s");time.putArray("allowedUnits").add("min").add("h");
        assertThat(LabValues.parse(json,json.getNodeFactory().textNode("30 min"),time,"").path("value").asInt()).isEqualTo(1800);
        assertThat(LabValues.parse(json,json.getNodeFactory().textNode(">600 s"),time,"").path("parseStatus").asText()).isEqualTo("LIMIT");
        var percent=json.createObjectNode().put("resultKind","MIXED").put("unit","%");
        assertThat(LabValues.parse(json,json.getNodeFactory().numberNode(0.6),percent,"0.00%").path("value").asDouble()).isEqualTo(60);
        var grade=json.createObjectNode().put("resultKind","ADHESION");assertThat(LabValues.parse(json,json.getNodeFactory().textNode("0"),grade,"").path("parseStatus").asText()).isEqualTo("GRADE");
    }
    @Test void compactsOnCopyKeepsZeroAndObservationsAndRestoresExportMetadata()throws Exception {
        var snapshot=filled("test");var before=snapshot.deepCopy();var sheet=LabWorkbook.sheet(snapshot);
        for(int c=2;c<=7;c++){LabWorkbook.cell(sheet,74,c).put("v","/");LabWorkbook.cell(sheet,75,c).put("v","");}
        LabWorkbook.cell(sheet,75,2).put("v","0");
        var copy=snapshot.deepCopy();var presentation=LabWorkbook.present(json,snapshot);
        assertThat(snapshot).isEqualTo(copy);
        var projected=LabWorkbook.sheet(presentation.snapshot());
        assertThat(projected.toString()).doesNotContain("附着力：ABS/PC");
        assertThat(projected.toString()).contains("附着力：ABS");
        assertThat(projected.path("rowCount").asInt()).isLessThan(sheet.path("rowCount").asInt());
        var bytes=new SnapshotWorkbookExporter(json).export(presentation.snapshot(),json.createArrayNode(),json.createObjectNode(),null).content();
        var restored=new XlsxStructureParser(json).parse(new ByteArrayInputStream(bytes)).initialEditorSnapshot();
        assertThat(LabWorkbook.supported(restored)).isTrue();
        assertThat(LabWorkbook.definition(restored).path("templateCode").asText()).isEqualTo(UnifiedLabTemplateCatalog.TEST);
        var reopened=LabWorkbook.prepare(json,restored,template("test").payload().path("snapshot"),"reopen");
        assertThat(LabWorkbook.sheet(reopened).path("cellData").path("74").path("1").path("v").asText()).isEqualTo("附着力：ABS/PC");
        var facts=LabWorkbook.extract(json,reopened,json.createObjectNode(),"reopen");
        assertThat(facts.path("sourceGroups")).hasSize(6);
        assertThat(facts.path("formulaItems").findValues("actualQty").stream().mapToDouble(n->n.asDouble()).sum()).isEqualTo(600);
        assertThat(before).isNotEqualTo(snapshot);
    }

    @Test void browserExpandedFormsKeepEightGroupsAcrossExportAndReimport()throws Exception {
        var folder=System.getProperty("lab.browser.snapshots");
        org.junit.jupiter.api.Assumptions.assumeTrue(folder!=null,"Run smoke-unified-lab.mjs before this acceptance test");
        for(var kind:List.of("test","synthesis")) {
            var input=json.readTree(java.nio.file.Path.of(folder,kind+"-expanded.json").toFile());
            var prepared=LabWorkbook.prepare(json,input,template(kind).payload().path("snapshot"),"expanded-"+kind);
            var model=LabWorkbook.extract(json,prepared,json.createObjectNode(),"expanded-"+kind);
            assertThat(model.path("sourceGroups")).hasSize(8);
            assertThat(model.path("templateInstance").path("issues")).isEmpty();
            var group=model.path("sourceGroups").get(7).path("sampleKey").asText();
            assertThat(model.path("testResults").findValuesAsText("value")).contains("无变化");
            var projection=LabWorkbook.present(json,prepared).snapshot();
            var bytes=new SnapshotWorkbookExporter(json).export(projection,json.createArrayNode(),json.createObjectNode(),null).content();
            try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                assertThat(workbook.getSheetAt(0).getColumnBreaks()).contains(7);
                assertThat(workbook.getSheetAt(0).getRepeatingColumns().getLastColumn()).isEqualTo(1);
                assertThat(workbook.getSheetAt(0).getPrintSetup().getScale()).isEqualTo((short)100);
            }
            var restored=new XlsxStructureParser(json).parse(new ByteArrayInputStream(bytes)).initialEditorSnapshot();
            var reopened=LabWorkbook.prepare(json,restored,template(kind).payload().path("snapshot"),"expanded-"+kind);
            var facts=LabWorkbook.extract(json,reopened,json.createObjectNode(),"expanded-"+kind);
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/unified-lab/"+kind+"-roundtrip.json"),facts.toString());
            assertThat(facts.path("sourceGroups")).hasSize(8);
            assertThat(facts.path("templateInstance").path("issues")).isEmpty();
            assertThat(facts.path("sourceGroups").get(7).path("sampleKey").asText()).isEqualTo(group);
            assertThat(facts.path("testResults").findValuesAsText("value")).contains("无变化");
            var pack=new UnifiedLabTemplateCatalog(json).describe(kind.equals("test")?UnifiedLabTemplateCatalog.TEST:UnifiedLabTemplateCatalog.SYNTHESIS,reopened,json.createObjectNode());
            assertThat(pack.payload().path("mapping").get(0).path("locator").path("dataRange").asText()).contains(":J");
        }
    }

    @Test void excelInsertedMaterialsRebindByLabelsInsteadOfStaleHiddenCoordinates()throws Exception {
        var source=filled("synthesis");var originalFacts=LabWorkbook.extract(json,source,json.createObjectNode(),"file-edit");
        var bytes=new SnapshotWorkbookExporter(json).export(source,json.createArrayNode(),json.createObjectNode(),null).content();
        var output=new java.io.ByteArrayOutputStream();
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet=workbook.getSheetAt(0);sheet.shiftRows(9,sheet.getLastRowNum(),2);
            for(int r=9;r<11;r++){var row=sheet.createRow(r);row.createCell(1).setCellValue("MAT-FILE-"+r);for(int c=2;c<8;c++)row.createCell(c).setCellValue(r==9?"90g":"/");}
            workbook.write(output);
        }
        var restored=new XlsxStructureParser(json).parse(new ByteArrayInputStream(output.toByteArray())).initialEditorSnapshot();
        var full=LabWorkbook.prepare(json,restored,template("synthesis").payload().path("snapshot"),"file-edit");
        var facts=LabWorkbook.extract(json,full,json.createObjectNode(),"file-edit");
        assertThat(facts.path("templateInstance").path("issues")).isEmpty();
        assertThat(facts.path("sourceGroups").findValuesAsText("sampleKey")).isEqualTo(originalFacts.path("sourceGroups").findValuesAsText("sampleKey"));
        assertThat(facts.path("formulaItems").get(0).path("materialCode").asText()).isEqualTo("MAT-FILE-9");
        assertThat(facts.path("formulaItems").get(0).path("feedSequence").asInt()).isEqualTo(1);
        assertThat(facts.path("formulaItems").findValues("actualQty").stream().mapToDouble(n->n.asDouble()).sum()).isEqualTo(6540);
    }

    @Test void refusesSilentLossOfUnboundValuesAndIncompleteFormulaTotals()throws Exception {
        var snapshot=filled("test");var sheet=LabWorkbook.sheet(snapshot);
        LabWorkbook.cell(sheet,47,2).put("v",0);
        LabWorkbook.cell(sheet,51,2).put("v","");
        var model=LabWorkbook.extract(json,snapshot,json.createObjectNode(),"incomplete");
        assertThat(model.path("templateInstance").path("issues").findValuesAsText("code")).contains("UNBOUND_VALUE").doesNotContain("FORMULA_INCOMPLETE");
        assertThat(LabWorkbook.value(LabWorkbook.sheet(model.path("documentSnapshot")),59,2)).isEmpty();
        assertThat(model.path("formulaItems").findValues("massPercent")).hasSize(40);
        var incompleteSample=model.path("sourceGroups").get(0).path("sampleKey");
        for(var item:model.path("formulaItems"))if(item.path("sampleKey").equals(incompleteSample)) {
            assertThat(item.path("formulaComplete").asBoolean(true)).isFalse();
            assertThat(item.path("trainingEligible").asBoolean(true)).isFalse();
        }
        assertThat(LabWorkbook.present(json,snapshot).locationMap().path("rows").has("47")).isTrue();
    }

    @Test void malformedMaterialAmountsStillPreserveTheirInputAndRequireCorrection()throws Exception {
        var snapshot=filled("test");var sheet=LabWorkbook.sheet(snapshot);
        LabWorkbook.cell(sheet,51,2).put("v","90 kg");
        var model=LabWorkbook.extract(json,snapshot,json.createObjectNode(),"invalid-unit");
        assertThat(model.path("templateInstance").path("issues").findValuesAsText("code")).contains("FORMULA_INCOMPLETE");
        assertThat(LabWorkbook.value(LabWorkbook.sheet(model.path("documentSnapshot")),59,2)).isEmpty();
        assertThat(model.path("formulaItems").get(0).path("rawValue").asText()).isEqualTo("90 kg");
    }

    @Test void oneSampleDoesNotKeepEmptySampleColumnsForSharedHeaderValues()throws Exception {
        var snapshot=filled("test");var sheet=LabWorkbook.sheet(snapshot);
        for(var row:sheet.path("cellData"))for(int c=3;c<=7;c++)if(row.path(""+c).path("custom").path("lab").path("role").asText().matches("VALUE|MATERIAL_QTY")) {
            var field=LabWorkbook.definition(snapshot).path("fields").path(row.path(""+c).path("custom").path("lab").path("fieldCode").asText());
            if(!field.path("scope").asText().equals("EXPERIMENT"))((ObjectNode)row.path(""+c)).remove(List.of("v","p","f"));
        }
        var projected=LabWorkbook.present(json,snapshot).snapshot();var compact=LabWorkbook.sheet(projected);
        assertThat(compact.path("columnCount").asInt()).isEqualTo(3);
        for(var code:List.of("basic.date","basic.temperature","basic.humidity")) {
            var at=LabWorkbook.findField(compact,code);assertThat(at).isNotNull();assertThat(LabWorkbook.value(compact,at[0],at[1])).isNotBlank();
        }
        var bytes=new SnapshotWorkbookExporter(json).export(projected,json.createArrayNode(),json.createObjectNode(),null).content();
        var restored=new XlsxStructureParser(json).parse(new ByteArrayInputStream(bytes)).initialEditorSnapshot();
        var full=LabWorkbook.prepare(json,restored,template("test").payload().path("snapshot"),"one-sample");
        assertThat(LabWorkbook.extract(json,full,json.createObjectNode(),"one-sample").path("sourceGroups")).hasSize(1);
    }
}
