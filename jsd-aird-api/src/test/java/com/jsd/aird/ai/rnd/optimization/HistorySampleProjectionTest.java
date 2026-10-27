package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class HistorySampleProjectionTest {
    private final ObjectMapper json=new ObjectMapper();
    private final UUID dataset=UUID.randomUUID();
    @Test void unifiedMassesAggregateRepeatedFeedsAndUseCanonicalMeasurements()throws Exception {
        var materialId=UUID.randomUUID().toString();
        var data=project("""
          {"sourceGroups":[{"sampleKey":"a"}],
           "formulaItems":[
             {"sampleKey":"a","materialCode":"RESIN","materialName":"RESIN","actualQty":20,"rawValue":"20g","compositionBasis":"MASS_AMOUNT","unit":"g"},
             {"sampleKey":"a","materialCode":"RESIN","materialName":"RESIN","actualQty":30,"rawValue":30,"compositionBasis":"MASS_AMOUNT","unit":"g"}],
           "processSteps":[{"sampleKey":"a","operation":"固化时间","value":1800,"rawValue":"30 min","parseStatus":"EXACT","valueStatus":"RECORDED","unit":"s"}],
           "testResults":[{"sampleKey":"a","testItem":"临时项目","value":90,"trainingEligible":false},
                          {"sampleKey":"a","testItem":"未测项目","value":null,"valueStatus":"NOT_MEASURED"}]}
          """,catalog(Map.of("RESIN",materialId)));
        var mass=columnCode(data,"RESIN用量");var time=columnCode(data,"固化时间");
        assertThat(data.path("rows").get(0).path("values").path(mass).asInt()).isEqualTo(50);
        assertThat(data.path("rows").get(0).path("values").path(time).asInt()).isEqualTo(1800);
        assertThat(data.path("columns").findValuesAsText("semanticType")).contains("MATERIAL_AMOUNT");
        assertThat(data.path("columns").findValuesAsText("name")).doesNotContain("临时项目","未测项目");
    }
    private com.fasterxml.jackson.databind.JsonNode project(String value) throws Exception {
        return project(value,MaterialCatalogSnapshot.empty());
    }
    private com.fasterxml.jackson.databind.JsonNode project(String value,MaterialCatalogSnapshot catalog) throws Exception {
        return new HistorySampleProjection(json).dataset(dataset,"实验数据",List.of(
            new OptimizationRepository.HistoryRecordRow(UUID.randomUUID(),UUID.randomUUID(),"REPORT","实验",json.readTree(value),Instant.EPOCH)),Map.of(),catalog);
    }
    private MaterialCatalogSnapshot catalog(Map<String,String> codeToId) {
        var identities=new java.util.LinkedHashMap<String,String>();var codes=new java.util.LinkedHashMap<String,String>();
        codeToId.forEach((code,id)->{identities.put(com.jsd.aird.ai.rnd.modeling.ConfigurationHashing.normalizeAlias(code),id);codes.put(id,code);});
        return new MaterialCatalogSnapshot(identities,codes,new java.util.LinkedHashSet<>(codeToId.values()));
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
          """.formatted(materialId),catalog(Map.of("SJ-230",materialId.toString())));
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
        assertThat(legacy.path("code").asText()).startsWith("ratio-unmapped:");
        assertThat(legacy.path("name").asText()).isEqualTo("旧树脂比例");
        assertThat(legacy.path("mappingStatus").asText()).isEqualTo("UNMAPPED");
        assertThat(legacy.has("materialId")).isFalse();
        assertThat(legacy.path("unit").asText()).isEqualTo("g");
        assertThat(legacy.path("compositionBasis").asText()).isEqualTo("UNSPECIFIED");
        var shortened=data.path("columns").get(2);
        assertThat(shortened.path("code").asText()).startsWith("ratio-unmapped:");
        assertThat(shortened.path("mappingStatus").asText()).isEqualTo("UNMAPPED");
        assertThat(shortened.has("materialId")).isFalse();
        var again=project("""
          {"formulaItems":[{"materialCode":"RESIN","materialName":"旧树脂","unit":"g","ratio":90}]}
          """);
        assertThat(again.path("columns").get(0).path("code")).isEqualTo(legacy.path("code"));
    }
    @Test void logicalSampleKeysKeepFormulaConditionsAndResultsAligned() throws Exception {
        var resin=UUID.randomUUID();var additive=UUID.randomUUID();
        var knownMaterials=new MaterialCatalogSnapshot(Map.of(),Map.of(resin.toString(),"R-1",additive.toString(),"A-1"),Set.of(resin.toString(),additive.toString()));
        var data=project("""
          {"processSteps":[{"name":"环境温度","value":25,"sampleKey":"sample-1"},
                           {"name":"环境温度","value":25,"sampleKey":"sample-2"}],"formulaItems":[
            {"materialId":"%s","materialName":"树脂","unit":"%%","compositionBasis":"MASS_PERCENT","ratio":70,"logicalSampleKey":"sample-1","sourceIdentity":"S-1"},
            {"materialId":"%s","materialName":"助剂","unit":"%%","compositionBasis":"MASS_PERCENT","ratio":30,"logicalSampleKey":"sample-1","sourceIdentity":"S-1"},
            {"materialId":"%s","materialName":"树脂","unit":"%%","compositionBasis":"MASS_PERCENT","ratio":60,"logicalSampleKey":"sample-2","sourceIdentity":"S-2"},
            {"materialId":"%s","materialName":"助剂","unit":"%%","compositionBasis":"MASS_PERCENT","ratio":40,"logicalSampleKey":"sample-2","sourceIdentity":"S-2"}],
           "testResults":[
            {"testItem":"光泽","value":80,"logicalSampleKey":"sample-1","sourceIdentity":"S-1"},
            {"testItem":"光泽","value":90,"logicalSampleKey":"sample-2","sourceIdentity":"S-2"}]}
          """.formatted(resin,additive,resin,additive),knownMaterials);
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
        var data=new HistorySampleProjection(json).dataset(dataset,"实验数据",records,Map.of(),MaterialCatalogSnapshot.empty());
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
        var projection=new HistorySampleProjection(json);var known=catalog(Map.of("R-1",master.toString()));
        var first=projection.dataset(dataset,"实验",records,Map.of(),known);
        var again=projection.dataset(dataset,"实验",records,Map.of(),known);
        var anotherTemplate=projection.dataset(UUID.randomUUID(),"实验",records,Map.of(),known);
        assertThat(first.path("columns").get(0).path("materialId").asText()).isEqualTo(master.toString());
        assertThat(first.path("columns").get(0).path("mappingStatus").asText()).isEqualTo("MAPPED");
        var nameOnly=first.path("columns").get(1);
        assertThat(nameOnly.path("mappingStatus").asText()).isEqualTo("UNMAPPED");
        assertThat(nameOnly.has("materialId")).isFalse();
        assertThat(nameOnly.path("code")).isEqualTo(again.path("columns").get(1).path("code"));
        assertThat(nameOnly.path("code")).isNotEqualTo(anotherTemplate.path("columns").get(1).path("code"));
    }
    @Test void aliasesResolveToCanonicalMaterialAndConflictingOrUnknownIdentitiesStayUnmapped() throws Exception {
        var canonicalId=UUID.randomUUID();var otherId=UUID.randomUUID();
        var catalog=new MaterialCatalogSnapshot(
                Map.of("ss059 alias",canonicalId.toString(),"ss059",canonicalId.toString(),"resin-a",otherId.toString()),
                Map.of(canonicalId.toString(),"SS059",otherId.toString(),"RESIN-A"),
                Set.of(canonicalId.toString(),otherId.toString()));
        var data=project("""
                {"formulaItems":[
                  {"materialCode":"SS059 alias","materialName":"引发剂","unit":"%%","compositionBasis":"MASS_PERCENT","ratio":10},
                  {"materialId":"%s","materialCode":"RESIN-A","materialName":"树脂","unit":"%%","compositionBasis":"MASS_PERCENT","ratio":80},
                  {"materialCode":"只写名称","materialName":"未映射材料","unit":"%%","ratio":10}]}
                """.formatted(canonicalId),catalog);
        var alias=data.path("columns").get(0);
        assertThat(alias.path("materialId").asText()).isEqualTo(canonicalId.toString());
        assertThat(alias.path("materialCode").asText()).isEqualTo("SS059");
        assertThat(alias.path("mappingStatus").asText()).isEqualTo("MAPPED");
        for(int index=1;index<3;index++) {
            assertThat(data.path("columns").get(index).path("mappingStatus").asText()).isEqualTo("UNMAPPED");
            assertThat(data.path("columns").get(index).has("materialId")).isFalse();
            assertThat(data.path("columns").get(index).path("code").asText()).doesNotStartWith("formula:");
        }
    }
    @Test void materialCodeThatContainsTheSameKnownUuidAsMaterialIdIsNotAnIdentityConflict() throws Exception {
        var materialId=UUID.randomUUID();
        var catalog=new MaterialCatalogSnapshot(
                Map.of("additive",materialId.toString()),
                Map.of(materialId.toString(),"ADDITIVE"),
                Set.of(materialId.toString()));
        var data=project("""
                {"formulaItems":[{"materialId":"%s","materialCode":"%s","materialName":"ADDITIVE","rawUnit":"%%","ratio":54.3}]}
                """.formatted(materialId,materialId),catalog);
        var column=data.path("columns").get(0);
        assertThat(column.path("mappingStatus").asText()).isEqualTo("MAPPED");
        assertThat(column.path("materialId").asText()).isEqualTo(materialId.toString());
        assertThat(column.path("materialCode").asText()).isEqualTo("ADDITIVE");
        assertThat(column.path("code").asText()).startsWith("formula:"+materialId+"|");
        assertThat(column.path("compositionBasis").asText()).isEqualTo("UNSPECIFIED");
        assertThat(com.jsd.aird.ai.rnd.modeling.WorkbenchFormulaSupport.materialId(column)).isNull();

        var codeOnly=project("""
                {"formulaItems":[{"materialCode":"%s","materialName":"ADDITIVE","rawUnit":"%%","ratio":54.3}]}
                """.formatted(materialId),catalog);
        assertThat(codeOnly.path("columns").get(0).path("mappingStatus").asText()).isEqualTo("MAPPED");
        assertThat(codeOnly.path("columns").get(0).path("materialId").asText()).isEqualTo(materialId.toString());
    }
    @Test void massPercentageBasisMustBeExplicitEvenWhenUnitIsPercent() throws Exception {
        var id=UUID.randomUUID();
        var data=project("""
                {"formulaItems":[{"materialId":"%s","materialName":"树脂","unit":"%%","ratio":100}]}
                """.formatted(id),new MaterialCatalogSnapshot(Map.of(),Map.of(id.toString(),"RESIN"),Set.of(id.toString())));
        var column=data.path("columns").get(0);
        assertThat(column.path("mappingStatus").asText()).isEqualTo("MAPPED");
        assertThat(column.path("compositionBasis").asText()).isEqualTo("UNSPECIFIED");
        assertThat(column.path("code").asText()).isNotEqualTo("formula:"+id);
        assertThat(com.jsd.aird.ai.rnd.modeling.WorkbenchFormulaSupport.materialId(column)).isNull();
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
            {"materialId":"%s","materialName":"树脂","unit":"%%","compositionBasis":"MASS_PERCENT","ratio":70,"sampleKey":"a","sourceRefs":[{"rawNumericValue":0.7,"numberFormat":"0.0%%","displayValue":"70%%"}]},
            {"materialId":"%s","materialName":"树脂","unit":"份","ratio":70,"sampleKey":"b"}]}
          """.formatted(material,material),new MaterialCatalogSnapshot(Map.of(),Map.of(material.toString(),"R-1"),Set.of(material.toString())));
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
