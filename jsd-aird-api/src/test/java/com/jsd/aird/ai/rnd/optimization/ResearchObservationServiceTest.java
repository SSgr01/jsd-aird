package com.jsd.aird.ai.rnd.optimization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResearchObservationServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode request() {
        var r=json.createObjectNode().put("target","gloss");
        r.putArray("features").addObject().put("code","resin").put("kind","NUMERIC");
        r.putObject("comparisonContext").put("process:substrate","PC");return r;
    }
    private ObjectNode row() {
        var r=json.createObjectNode();r.putObject("values").put("resin",71).put("gloss",0).put("process:substrate","PC");return r;
    }
    private ObjectNode prediction() {
        var p=json.createObjectNode();p.putArray("targets").addObject().put("targetId","gloss").putObject("result").put("value",86);return p;
    }
    @Test void zeroIsMeasuredAndTheActualRecipeIsRetainedWithItsDeviation() {
        var actual=ResearchObservationService.assess(json,request(),row(),json.createObjectNode().put("resin",70),prediction());
        assertThat(actual.path("status").asText()).isEqualTo("COMPARABLE");
        assertThat(actual.at("/metrics/residual").asInt()).isEqualTo(-86);
        assertThat(actual.at("/metrics/inputChanges/resin/actual").asInt()).isEqualTo(71);
    }
    @Test void aMissingResultOrChangedMethodNeverBecomesZero() {
        var row=row();row.withObject("values").remove("gloss");
        row.withObject("values").put("gloss:other-method",86);
        var actual=ResearchObservationService.assess(json,request(),row,json.createObjectNode(),prediction());
        assertThat(actual.path("status").asText()).isEqualTo("EXCLUDED");
        assertThat(actual.path("reason").asText()).isEqualTo("RESULT_NOT_COMPARABLE");
        assertThat(actual.at("/metrics/residual").isMissingNode()).isTrue();
    }
    @Test void changedFixedConditionsAndMissingActualInputsAreExcluded() {
        var row=row();row.withObject("values").put("process:substrate","PET");
        assertThat(ResearchObservationService.assess(json,request(),row,json.createObjectNode(),prediction()).path("reason").asText()).isEqualTo("CONDITIONS_CHANGED");
        row.withObject("values").put("process:substrate","PC").remove("resin");
        assertThat(ResearchObservationService.assess(json,request(),row,json.createObjectNode(),prediction()).path("reason").asText()).isEqualTo("ACTUAL_INPUT_MISSING");
    }
    @Test void unspecifiedMethodsRemainPendingEvenWhenBothValuesExist() {
        var request=request();request.put("target","result:gloss|gu||optical");
        var column=request.putArray("columns").addObject().put("code",request.path("target").asText());column.putArray("testMethods");
        var row=row();row.withObject("values").put(request.path("target").asText(),86);
        var actual=ResearchObservationService.assess(json,request,row,json.createObjectNode(),prediction());
        assertThat(actual.path("reason").asText()).isEqualTo("MEASUREMENT_METHOD_MISSING");
        assertThat(actual.path("status").asText()).isEqualTo("EXCLUDED");
    }
    @Test void proposalNumbersDoNotMakeOtherwiseMatchingCoatingConditionsIncompatible() {
        var request=request();var key="process:样品/方案编号|||conditions";
        request.withObject("comparisonContext").put(key,"CM-C-001-2");
        var row=row();row.withObject("values").put(key,"候选方案 1");
        var inputs=json.createObjectNode().put("resin",71);
        assertThat(ResearchObservationService.assess(json,request,row,inputs,prediction()).path("status").asText()).isEqualTo("COMPARABLE");
        row.withObject("values").put("process:substrate","PET");
        assertThat(ResearchObservationService.assess(json,request,row,inputs,prediction())
            .at("/metrics/changedConditions/process:substrate/actual").asText()).isEqualTo("PET");
    }
    @Test void databaseNumericTypesAndRoundoffAreNotReportedAsRecipeOrConditionChanges() throws Exception {
        var request=request();request.withObject("comparisonContext").put("temperature",25.0);
        var row=row();row.withObject("values").put("temperature",new java.math.BigDecimal("25.0000000000000"))
            .put("resin",new java.math.BigDecimal("71.00000000000001"));
        var result=ResearchObservationService.assess(json,request,row,json.createObjectNode().put("resin",71.0),prediction());
        assertThat(result.path("status").asText()).isEqualTo("COMPARABLE");
        assertThat(result.at("/metrics/inputChanges")).isEmpty();
        row.withObject("values").put("resin",70.8);
        assertThat(ResearchObservationService.assess(json,request,row,json.createObjectNode().put("resin",71.0),prediction())
            .at("/metrics/inputChanges/resin/actual").asDouble()).isEqualTo(70.8);
    }
}
