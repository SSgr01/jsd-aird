package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.lab.LabWorkbook;
import com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog;
import com.jsd.aird.tpl.infrastructure.XlsxStructureParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ResearchDraftWorkbookTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void twoCandidatesKeepMaterialIdentitiesAndClearEveryMeasuredRegion() throws Exception {
        var pack=new UnifiedLabTemplateCatalog(json).compile(UnifiedLabTemplateCatalog.TEST,
            new XlsxStructureParser(json).parseInstance(getClass().getResourceAsStream("/templates/unified/test.xlsx"))).payload();
        var original=(ObjectNode)pack.path("snapshot").deepCopy();
        var sheet=LabWorkbook.sheet(original);int first=LabWorkbook.definition(original).path("formulaStartRow").asInt();
        LabWorkbook.cell(sheet,first,1).put("v","CM-R001");
        var gloss=LabWorkbook.findField(sheet,"optical.gloss60");LabWorkbook.cell(sheet,gloss[0],2).put("v",91).put("f","1+90");
        LabWorkbook.cell(sheet,gloss[0],2).withObject("/custom/measurementProtocol").put("method","CM-QC-006 光泽法").putObject("conditions").put("angle",60);
        var baseline=json.createObjectNode();baseline.set("sourceWorkbookSnapshot",original);
        var request=json.createObjectNode().put("target","gloss");request.putArray("features").addObject().put("code","resin");
        var row=request.putObject("datasetSnapshot").putArray("rows").addObject().put("id","sample-1");
        row.putObject("values").put("resin",70).put("gloss",91);
        row.putObject("sourceRefs").putArray("resin").addObject().put("sheetId",sheet.path("id").asText()).put("cellRange",LabWorkbook.address(first,2));
        row.withObject("sourceRefs").putArray("gloss").addObject().put("sheetId",sheet.path("id").asText()).put("cellRange",LabWorkbook.address(gloss[0],2));
        var a=json.createObjectNode().put("title","方案1");a.putObject("inputs").put("resin",71);
        var b=json.createObjectNode().put("title","方案2");b.putObject("inputs").put("resin",73);
        var built=ResearchDraftWorkbook.build(json,baseline,request,List.of(a,b));var actual=LabWorkbook.sheet(built);
        assertThat(LabWorkbook.value(actual,first,1)).isEqualTo("CM-R001");
        assertThat(LabWorkbook.value(actual,first,2)).isEqualTo("71");assertThat(LabWorkbook.value(actual,first,3)).isEqualTo("73");
        assertThat(LabWorkbook.value(actual,gloss[0],2)).isEmpty();assertThat(LabWorkbook.value(actual,gloss[0],3)).isEmpty();
        assertThat(actual.path("cellData").path(""+gloss[0]).path("2").has("f")).isFalse();
        assertThat(actual.path("cellData").path(""+gloss[0]).path("3").at("/custom/measurementProtocol/method").asText()).isEqualTo("CM-QC-006 光泽法");
        com.jsd.aird.shared.excel.WorkbookStructure.seed(json,built,pack.path("schema"),pack.path("mapping"),"draft");
        var extracted=LabWorkbook.extract(json,LabWorkbook.prepare(json,built,original,"draft"),json.createObjectNode(),"draft");
        assertThat(extracted.path("testResults")).anySatisfy(item->{
            assertThat(item.path("fieldCode").asText()).isEqualTo("optical.gloss60");
            assertThat(item.path("testMethod").asText()).isEqualTo("CM-QC-006 光泽法");
            assertThat(item.path("testCondition").path("angle").asInt()).isEqualTo(60);
        });
        assertThat(original.path("sheets")).isNotEqualTo(built.path("sheets"));
        row.withObject("sourceRefs").remove("resin");
        assertThatThrownBy(()->ResearchDraftWorkbook.build(json,baseline,request,List.of(a))).hasMessageContaining("原表位置");
    }
    @Test void spreadsheetCoordinatesAreUnambiguous() {
        assertThat(ResearchDraftWorkbook.position(json.createObjectNode().put("cellRange","$AA$12"))).containsExactly(11,26);
        assertThat(ResearchDraftWorkbook.position(json.createObjectNode().put("row",4).put("column",3))).containsExactly(3,2);
        assertThat(ResearchDraftWorkbook.position(json.createObjectNode().put("cellRange","C12:D12"))).isNull();
    }
}
