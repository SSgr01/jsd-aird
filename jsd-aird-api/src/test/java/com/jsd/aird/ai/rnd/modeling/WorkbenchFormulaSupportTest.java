package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchFormulaSupportTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String A="f44a3459-05c1-4958-a246-b5736c0aa682", B="fa09039b-f0a2-48bd-9acc-71ed839ce94d";

    @Test void derivesMaterialIdentityFromFrozenColumnCodesAndChecksCompleteProportions() {
        var scheme=json.createObjectNode().put("id","scheme");var fields=scheme.putArray("fields");
        fields.addObject().put("code","formula:"+A).put("name","树脂 A比例").put("valueType","NUMBER").put("unit","%");
        fields.addObject().put("code","formula:"+B).put("name","树脂 B比例").put("valueType","NUMBER").put("unit","%");
        var rows=json.createArrayNode();rows.addObject().putObject("values").put("formula:"+A,40).put("formula:"+B,60);
        var dictionary=WorkbenchFormulaSupport.dictionary(json,scheme,rows);
        assertThat(dictionary.path("materials").size()).isEqualTo(2);
        assertThat(dictionary.path("materials").get(0).path("materialId").asText()).isEqualTo(A);
        assertThat(dictionary.path("compositionStatus").asText()).isEqualTo("COMPLETE");
        rows.get(0).withObject("values").put("formula:"+B,50);
        assertThat(WorkbenchFormulaSupport.dictionary(json,scheme,rows).path("compositionStatus").asText()).isEqualTo("INCOMPLETE");
    }

    @Test void neverInfersMaterialIdentityFromNamesOrNonPercentageAmounts() {
        var ordinary=json.createObjectNode().put("code","resin-ratio").put("name","树脂 A比例").put("valueType","NUMBER").put("unit","%");
        assertThat(WorkbenchFormulaSupport.materialId(ordinary)).isNull();
        ordinary.put("code","formula:"+A).put("unit","g");
        assertThat(WorkbenchFormulaSupport.materialId(ordinary)).isNull();
        ordinary.put("unit","%").put("valueType","CATEGORY");
        assertThat(WorkbenchFormulaSupport.materialId(ordinary)).isNull();
        ordinary.put("code","formula:not-a-uuid").put("unit","%").put("valueType","NUMBER");
        assertThat(WorkbenchFormulaSupport.materialId(ordinary)).isNull();
    }

    @Test void retainsFixedZeroAndAcceptsOnlySmallDecimalRoundingDifferences() {
        var scheme=json.createObjectNode().put("id","scheme");var fields=scheme.putArray("fields");
        fields.addObject().put("code","formula:"+A).put("name","助剂比例").put("valueType","NUMBER").put("unit","%");
        fields.addObject().put("code","formula:"+B).put("name","树脂比例").put("valueType","NUMBER").put("unit","%");
        var rows=json.createArrayNode();
        for(int i=0;i<6;i++)rows.addObject().putObject("values").put("formula:"+A,0).put("formula:"+B,new java.math.BigDecimal(i%2==0?"99.9999":"100.0001"));
        var dictionary=WorkbenchFormulaSupport.dictionary(json,scheme,rows);
        assertThat(dictionary.path("compositionStatus").asText()).isEqualTo("COMPLETE");
        assertThat(dictionary.path("materials").get(0).path("fixedValue").decimalValue()).isEqualByComparingTo("0");
        rows.get(0).withObject("values").put("formula:"+B,98.8);
        assertThat(WorkbenchFormulaSupport.dictionary(json,scheme,rows).path("compositionStatus").asText()).isEqualTo("INCOMPLETE");
    }
}
