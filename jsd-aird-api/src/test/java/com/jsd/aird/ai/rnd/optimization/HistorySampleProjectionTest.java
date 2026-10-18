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
    @Test void conditionsStayWithinTheirSampleContextAndUnscopedValuesAreNotBroadcast() throws Exception {
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
           "dynamicValues":{"环境湿度":60,"备注":"说明","合计":100}}
          """);
        assertThat(data.path("rowCount").asInt()).isEqualTo(2);
        assertThat(data.path("columns").findValuesAsText("name")).containsExactly("温度","60°光泽");
        var temperature=columnCode(data,"温度");
        var gloss=columnCode(data,"60°光泽");
        assertThat(data.path("rows").get(0).path("values").path(temperature).asDouble()).isEqualTo(27.5);
        assertThat(data.path("rows").get(1).path("values").path(temperature).asDouble()).isEqualTo(29);
        assertThat(data.path("rows").get(1).path("values").path(gloss).asDouble()).isEqualTo(80);
        assertThat(data.path("rows").get(0).path("id")).isNotEqualTo(data.path("rows").get(1).path("id"));
    }
    @Test void materialIdentitiesStayStableAndOriginalUnitsArePreserved() throws Exception {
        var materialId=UUID.randomUUID();
        var data=project("""
          {"formulaItems":[{"materialId":"%s","materialCode":"SJ-230","materialName":"SJ-230","unit":"份","ratio":0},
                           {"materialId":"not-a-uuid","materialCode":"RESIN","materialName":"旧树脂","unit":"g","ratio":100},
                           {"materialId":"1-1-1-1-1","materialCode":"SHORT","materialName":"短 UUID","ratio":5}],
           "testResults":[{"testItem":"膜厚","unit":"μm","value":8.91}]}
          """.formatted(materialId));
        var material=data.path("columns").get(0);
        assertThat(material.path("name").asText()).isEqualTo("SJ-230比例");
        assertThat(material.path("code").asText()).startsWith("formula:"+materialId+"|");
        assertThat(material.path("materialId").asText()).isEqualTo(materialId.toString());
        assertThat(material.path("semanticType").asText()).isEqualTo("MATERIAL_RATIO");
        assertThat(material.path("mappingStatus").asText()).isEqualTo("MAPPED");
        assertThat(material.path("unit").asText()).isEqualTo("份");
        assertThat(material.path("compositionBasis").asText()).isEqualTo("UNSPECIFIED");
        assertThat(data.path("rows").get(0).path("values").path(material.path("code").asText()).asInt()).isZero();
        var legacy=data.path("columns").get(1);
        assertThat(legacy.path("code").asText()).startsWith("formula:");
        assertThat(legacy.path("name").asText()).isEqualTo("旧树脂比例");
        assertThat(legacy.path("mappingStatus").asText()).isEqualTo("MAPPED");
        assertThat(legacy.path("unit").asText()).isEqualTo("g");
        assertThat(legacy.path("compositionBasis").asText()).isEqualTo("UNSPECIFIED");
        assertThat(UUID.fromString(legacy.path("materialId").asText()).toString()).isNotEqualTo("not-a-uuid");
        var shortened=data.path("columns").get(2);
        assertThat(shortened.path("code").asText()).startsWith("formula:");
        assertThat(shortened.path("mappingStatus").asText()).isEqualTo("MAPPED");
        assertThat(shortened.path("materialId").asText()).isNotEqualTo(UUID.fromString("1-1-1-1-1").toString());
        var again=project("""
          {"formulaItems":[{"materialCode":"RESIN","materialName":"旧树脂","unit":"g","ratio":90}]}
          """);
        assertThat(again.path("columns").get(0).path("materialId")).isEqualTo(legacy.path("materialId"));
    }
    @Test void logicalSampleKeysKeepFormulaConditionsAndResultsAligned() throws Exception {
        var resin=UUID.randomUUID();var additive=UUID.randomUUID();
        var data=project("""
          {"processSteps":[{"name":"环境温度","value":25,"sampleKey":"sample-1"},
                           {"name":"环境温度","value":25,"sampleKey":"sample-2"}],"formulaItems":[
            {"materialId":"%s","materialName":"树脂","unit":"%%","ratio":70,"logicalSampleKey":"sample-1","sourceIdentity":"S-1"},
            {"materialId":"%s","materialName":"助剂","unit":"%%","ratio":30,"logicalSampleKey":"sample-1","sourceIdentity":"S-1"},
            {"materialId":"%s","materialName":"树脂","unit":"%%","ratio":60,"logicalSampleKey":"sample-2","sourceIdentity":"S-2"},
            {"materialId":"%s","materialName":"助剂","unit":"%%","ratio":40,"logicalSampleKey":"sample-2","sourceIdentity":"S-2"}],
           "testResults":[
            {"testItem":"光泽","value":80,"logicalSampleKey":"sample-1","sourceIdentity":"S-1"},
            {"testItem":"光泽","value":90,"logicalSampleKey":"sample-2","sourceIdentity":"S-2"}]}
          """.formatted(resin,additive,resin,additive));
        assertThat(data.path("rows")).hasSize(2);
        var temperature=columnCode(data,"环境温度");var gloss=columnCode(data,"光泽");
        assertThat(data.path("rows").get(0).path("values").path("formula:"+resin).asDouble()).isEqualTo(70);
        assertThat(data.path("rows").get(0).path("values").path(gloss).asDouble()).isEqualTo(80);
        assertThat(data.path("rows").get(1).path("values").path("formula:"+resin).asDouble()).isEqualTo(60);
        assertThat(data.path("rows").get(1).path("values").path(gloss).asDouble()).isEqualTo(90);
        assertThat(data.path("rows").get(0).path("values").path(temperature).asDouble()).isEqualTo(25);
        assertThat(data.path("rows").get(1).path("values").path(temperature).asDouble()).isEqualTo(25);
    }
    @Test void sampleGroupKeyFallsBackFromLogicalToSampleToSourceGroupToRecordKey() throws Exception {
        var data=project("""
          {"sampleGroups":[
             {"logicalSampleKey":"logical-1","sampleKey":"sample-1","sourceGroupKey":"group-1","sourceRecordKey":"record-1"},
             {"sampleKey":"sample-2","sourceGroupKey":"group-2","sourceRecordKey":"record-2"},
             {"sourceGroupKey":"group-3","sourceRecordKey":"record-3"},
             {"sourceRecordKey":"record-4"}],
           "testResults":[
             {"testItem":"光泽","value":81,"logicalSampleKey":"logical-1","sampleKey":"sample-other","sourceGroupKey":"group-other","sourceRecordKey":"record-other"},
             {"testItem":"光泽","value":82,"sampleKey":"sample-2","sourceGroupKey":"group-other","sourceRecordKey":"record-other"},
             {"testItem":"光泽","value":83,"sourceGroupKey":"group-3","sourceRecordKey":"record-other"},
             {"testItem":"光泽","value":84,"sourceRecordKey":"record-4"}]}
          """);
        assertThat(data.path("rows")).hasSize(4);
        var gloss=columnCode(data,"光泽");
        assertThat(data.path("rows").get(0).path("values").path(gloss).asInt()).isEqualTo(81);
        assertThat(data.path("rows").get(1).path("values").path(gloss).asInt()).isEqualTo(82);
        assertThat(data.path("rows").get(2).path("values").path(gloss).asInt()).isEqualTo(83);
        assertThat(data.path("rows").get(3).path("values").path(gloss).asInt()).isEqualTo(84);
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
    @Test void explicitMaterialCodesMatchMasterDataAndNamesStayScopedToTheirTemplateAndOrganization() throws Exception {
        var master=UUID.randomUUID();
        var model=json.readTree("""
          {"formulaItems":[{"materialCode":"R-1","materialName":"树脂","unit":"%","ratio":80},
                           {"materialCode":"","materialName":"助剂","unit":"%","ratio":20}]}
          """);
        var records=List.of(new OptimizationRepository.HistoryRecordRow(UUID.randomUUID(),UUID.randomUUID(),"R1","实验",model,Instant.EPOCH));
        var projection=new HistorySampleProjection(json);
        var first=projection.dataset(org,dataset,"实验",records,Map.of(),Map.of("R-1",master.toString()));
        var again=projection.dataset(org,dataset,"实验",records,Map.of(),Map.of("R-1",master.toString()));
        var anotherTemplate=projection.dataset(org,UUID.randomUUID(),"实验",records,Map.of(),Map.of("R-1",master.toString()));
        var anotherOrg=projection.dataset(UUID.randomUUID(),dataset,"实验",records,Map.of(),Map.of());
        assertThat(first.path("columns").get(0).path("materialId").asText()).isEqualTo(master.toString());
        assertThat(first.path("columns").get(1).path("materialId")).isEqualTo(again.path("columns").get(1).path("materialId"));
        assertThat(first.path("columns").get(1).path("materialId")).isNotEqualTo(anotherTemplate.path("columns").get(1).path("materialId"));
        assertThat(first.path("columns").get(1).path("materialId")).isNotEqualTo(anotherOrg.path("columns").get(1).path("materialId"));
    }
    @Test void sharedSourceRowsApplyOnlyToTheirContextAndNeverBecomeExtraSamples() throws Exception {
        var data=project("""
          {"sampleGroups":[{"sampleKey":"a","sourceIdentity":"A","sourceContextKey":"sheet-1","sourceRecordKeys":["sample-a-record"]},
                           {"sampleKey":"b","sourceIdentity":"B","sourceContextKey":"sheet-1"},
                           {"sampleKey":"c","sourceIdentity":"C","sourceContextKey":"sheet-2"}],
           "processSteps":[{"name":"温度","value":25,"unit":"°C","sourceRecordKey":"shared-row","sourceContextKey":"sheet-1"}],
           "testResults":[{"testItem":"光泽","value":80,"sampleKey":"a"},
                          {"testItem":"光泽","value":90,"sampleKey":"b"},
                          {"testItem":"光泽","value":85,"sampleKey":"c"},
                          {"testItem":"含水率","value":0.1,"unit":"%","sourceRecordKey":"sample-a-record","sourceContextKey":"sheet-1"}]}
          """);
        assertThat(data.path("rows")).hasSize(3);
        var temperature=columnCode(data,"温度");
        assertThat(data.path("rows").get(0).path("values").path(temperature).asInt()).isEqualTo(25);
        assertThat(data.path("rows").get(1).path("values").path(temperature).asInt()).isEqualTo(25);
        assertThat(data.path("rows").get(2).path("values").has(temperature)).isFalse();
        var water=columnCode(data,"含水率");
        assertThat(data.path("rows").get(0).path("values").path(water).asDouble()).isEqualTo(0.1);
        assertThat(data.path("rows").get(1).path("values").has(water)).isFalse();
    }
    @Test void importedContextWithoutAnySampleDoesNotCreateALearningObservation() throws Exception {
        var data=project("""
          {"sourceImportJobId":"import","processSteps":[{"name":"温度","value":25,"sourceRecordKey":"shared-row","sourceContextKey":"sheet-1"}]}
          """);
        assertThat(data.path("rows")).isEmpty();
        assertThat(data.path("issues")).hasSize(1);
    }
    @Test void percentageFormattingIsAppliedOnceAndDifferentCompositionUnitsRemainSeparate() throws Exception {
        var material=UUID.randomUUID();
        var data=project("""
          {"sampleGroups":[{"sampleKey":"a"},{"sampleKey":"b"}],"formulaItems":[
            {"materialId":"%s","materialName":"树脂","unit":"%%","ratio":70,"sampleKey":"a","sourceRefs":[{"rawNumericValue":0.7,"numberFormat":"0.0%%","displayValue":"70%%"}]},
            {"materialId":"%s","materialName":"树脂","unit":"份","ratio":70,"sampleKey":"b"}]}
          """.formatted(material,material));
        assertThat(data.path("columns")).hasSize(2);
        assertThat(data.path("columns").get(0).path("unit").asText()).isEqualTo("%");
        assertThat(data.path("columns").get(1).path("unit").asText()).isEqualTo("份");
        assertThat(data.path("columns").get(0).path("code")).isNotEqualTo(data.path("columns").get(1).path("code"));
        assertThat(data.path("rows").get(0).path("values").path("formula:"+material).asDouble()).isEqualTo(70);
    }
    @Test void conflictingPercentFormattingAndMassUnitsAreReportedInsteadOfConverted() throws Exception {
        var data=project("""
          {"formulaItems":[{"materialName":"树脂","unit":"g","ratio":30,
            "sourceRefs":[{"rawNumericValue":0.3,"numberFormat":"0.0%","displayValue":"30%"}]}]}
          """);
        assertThat(data.path("rows").get(0).path("values")).isEmpty();
        assertThat(data.path("columns").get(0).path("unit").asText()).isEqualTo("g");
        assertThat(data.path("issues").get(0).path("message").asText()).contains("百分比格式").contains("g");
    }
    private String columnCode(com.fasterxml.jackson.databind.JsonNode data,String name) {
        return java.util.stream.StreamSupport.stream(data.path("columns").spliterator(),false)
                .filter(column->name.equals(column.path("name").asText())).findFirst().orElseThrow().path("code").asText();
    }
}
