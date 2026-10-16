package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class HistorySampleProjectionTest {
    private final ObjectMapper json=new ObjectMapper();
    private final UUID org=UUID.randomUUID(), dataset=UUID.randomUUID();
    private com.fasterxml.jackson.databind.JsonNode project(String value) throws Exception {
        return new HistorySampleProjection(json).dataset(org,dataset,"实验数据",List.of(
            new OptimizationRepository.HistoryRecordRow(UUID.randomUUID(),UUID.randomUUID(),"REPORT","实验",json.readTree(value),Instant.EPOCH)),Map.of(),Map.of());
    }
    @Test void samplesAreRowsAndSharedConditionsStayInTheirOwnContext() throws Exception {
        var data=project("""
          {"sampleGroups":[
             {"sampleKey":"a","sourceIdentity":"SAME-NO","sourceContextKey":"sheet-a"},
             {"sampleKey":"b","sourceIdentity":"SAME-NO","sourceContextKey":"sheet-b"}],
           "processSteps":[
             {"field":"TEMPERATURE","labelPath":"温度","value":"27.5℃","sourceContextKey":"sheet-a"},
             {"field":"TEMPERATURE","labelPath":"温度","value":"29.0℃","sourceContextKey":"sheet-b"}],
           "testResults":[
             {"testItem":"60°光泽","value":78,"unit":"GU","sampleKey":"a"},
             {"testItem":"60°光泽","value":80,"unit":"GU","sampleKey":"b"}],
           "dynamicValues":{"备注":"说明","合计":100}}
          """);
        assertThat(data.path("rowCount").asInt()).isEqualTo(2);
        assertThat(data.path("columns").findValuesAsText("name")).containsExactly("温度","60°光泽");
        var temperature=data.path("columns").get(0).path("code").asText();
        var gloss=data.path("columns").get(1).path("code").asText();
        assertThat(data.path("rows").get(0).path("values").path(temperature).asDouble()).isEqualTo(27.5);
        assertThat(data.path("rows").get(1).path("values").path(temperature).asDouble()).isEqualTo(29);
        assertThat(data.path("rows").get(1).path("values").path(gloss).asDouble()).isEqualTo(80);
        assertThat(data.path("rows").get(0).path("id")).isNotEqualTo(data.path("rows").get(1).path("id"));
    }
    @Test void zeroMaterialsSurviveAnEmptyMaterialCodeAndIdentityIsStable() throws Exception {
        var data=project("""
          {"formulaItems":[{"materialCode":"","materialName":"SJ-230","unit":"%","ratio":0},
                           {"materialCode":"RESIN","materialName":"树脂","unit":"%","ratio":100}],
           "testResults":[{"testItem":"膜厚","unit":"μm","value":8.91}]}
          """);
        var material=data.path("columns").get(0);
        assertThat(material.path("name").asText()).isEqualTo("SJ-230比例");
        assertThat(UUID.fromString(material.path("materialId").asText())).isNotNull();
        assertThat(data.path("rows").get(0).path("values").path(material.path("code").asText()).asInt()).isZero();
    }
    @Test void methodsAndStagesDoNotMergeAndInvalidCellsDoNotChangeNumericType() throws Exception {
        var data=project("""
          {"sampleGroups":[{"sampleKey":"a"},{"sampleKey":"b"}],"testResults":[
           {"testItem":"耐磨","unit":"次","testMethod":"500g","sampleKey":"a","value":228},
           {"testItem":"耐磨","unit":"次","testMethod":"500g","sampleKey":"b","value":"/（未测试）"},
           {"testItem":"耐磨","unit":"次","testMethod":"1kg","sampleKey":"a","value":124},
           {"testItem":"光泽","unit":"GU","testStage":"12h","sampleKey":"a","value":"80-90"},
           {"testItem":"光泽","unit":"GU","sampleKey":"a","value":81}]}
          """);
        assertThat(data.path("columns")).hasSize(4);
        assertThat(data.path("columns").get(0).path("kind").asText()).isEqualTo("NUMERIC");
        assertThat(data.path("columns").get(0).path("filledCount").asInt()).isEqualTo(1);
        assertThat(data.path("issues")).hasSize(1);
        assertThat(data.path("issues").get(0).path("rawValue").asText()).isEqualTo("80-90");
    }
    @Test void conflictingResultsAreReportedRatherThanOverwritten() throws Exception {
        var data=project("""
          {"testResults":[{"testItem":"膜厚","unit":"μm","value":8},
                           {"testItem":"膜厚","unit":"μm","value":9},
                           {"testItem":"膜厚","unit":"μm","value":8}]}
          """);
        assertThat(data.path("rows").get(0).path("values")).isEmpty();
        assertThat(data.path("issues").get(0).path("message").asText()).contains("冲突");
    }
    @Test void unassignedObservationsAreReportedAndSubstrateRemainsAUsableCondition() throws Exception {
        var data=project("""
          {"sampleGroups":[{"sampleKey":"a","sourceContextKey":"a"},{"sampleKey":"b","sourceContextKey":"b"}],
           "processSteps":[{"name":"测试用素材","value":"PET","sourceContextKey":"a"}],
           "testResults":[{"testItem":"光泽","unit":"GU","value":88,"sourceContextKey":"unknown"}]}
          """);
        assertThat(data.path("columns").findValuesAsText("name")).contains("测试用素材");
        assertThat(data.path("issues").get(0).path("message").asText()).contains("归属");
        assertThat(data.path("rows").get(1).path("values")).isEmpty();
    }
    @Test void distinctReportsAreNotCollapsedBecauseTheirSampleLabelsAndOriginalFileMatch() throws Exception {
        var model=json.readTree("""
          {"sourceFileHash":"same-original","sampleGroups":[{"sampleKey":"sample","sourceIdentity":"A1","sourceRecordKeys":["row-1"]}],
           "testResults":[{"testItem":"光泽","unit":"GU","sampleKey":"sample","value":88}]}
          """);
        var records=List.of(new OptimizationRepository.HistoryRecordRow(UUID.randomUUID(),UUID.randomUUID(),"R1","实验",model,Instant.EPOCH),
                new OptimizationRepository.HistoryRecordRow(UUID.randomUUID(),UUID.randomUUID(),"R2","实验",model,Instant.EPOCH));
        var data=new HistorySampleProjection(json).dataset(org,dataset,"实验数据",records,Map.of(),Map.of());
        assertThat(data.path("rows")).hasSize(2);
        assertThat(data.path("rows").get(0).path("id")).isNotEqualTo(data.path("rows").get(1).path("id"));
    }
}
