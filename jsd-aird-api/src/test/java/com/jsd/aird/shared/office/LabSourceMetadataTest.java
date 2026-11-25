package com.jsd.aird.shared.office;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.lab.LabSourceMetadata;
import com.jsd.aird.shared.lab.LabValues;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LabSourceMetadataTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void recordsOnlyBlankSourceMetadataAndMatchesItsExactPhysicalCell() throws Exception {
        var snapshot=json.readTree("""
          {"sheetOrder":["s"],"sheets":{"s":{"id":"s","name":"原页","custom":{"labTemplate":{"fields":{
          "basic.owner":{"scope":"EXPERIMENT","semantic":"OWNER","label":"实验人"},
          "basic.date":{"scope":"EXPERIMENT","semantic":"EXPERIMENT_DATE","label":"日期"},
          "metric":{"scope":"SAMPLE","semantic":"VALUE","label":"黏度"}}}},"cellData":{
          "1":{"1":{"v":"","custom":{"lab":{"fieldCode":"basic.owner"}}},
               "3":{"v":"2026-10-01","custom":{"lab":{"fieldCode":"basic.date"}}}},
          "2":{"2":{"v":"","custom":{"lab":{"fieldCode":"metric"}}}}}}}}
          """);
        var gaps=LabSourceMetadata.gaps(json,snapshot);
        assertThat(gaps).hasSize(1);assertThat(gaps.get(0).path("semantic").asText()).isEqualTo("OWNER");
        assertThat(LabSourceMetadata.sourceOmitted(gaps,json.createObjectNode().put("sheetId","s").put("address","B2"))).isTrue();
        assertThat(LabSourceMetadata.sourceOmitted(gaps,json.createObjectNode().put("sheetId","another").put("address","B2"))).isFalse();
        assertThat(LabSourceMetadata.sourceOmitted(gaps,json.createObjectNode().put("sheetId","s").put("address","C3"))).isFalse();
    }

    @Test void retainsDegreeNotationForHistoricalGlossWithoutConvertingItToGu() {
        var field=json.createObjectNode().put("fieldCode","optical.gloss60").put("resultKind","MIXED").put("unit","GU");
        for(var text:java.util.List.of("3.5°","2.6-2.7°")) {
            var value=LabValues.parse(json,json.getNodeFactory().textNode(text),field,"");
            assertThat(value.path("value").asText()).isEqualTo(text);assertThat(value.path("rawValue").asText()).isEqualTo(text);
            assertThat(value.path("unit").asText()).isEqualTo("°");
            assertThat(value.path("unitStatus").asText()).isEqualTo("SOURCE_REPORTED_NONSTANDARD");
            assertThat(value.path("trainingEligible").asBoolean()).isFalse();
        }
        assertThat(LabValues.parse(json,json.getNodeFactory().textNode("3.5 GU"),field,"").path("value").decimalValue()).isEqualByComparingTo("3.5");
        assertThat(LabValues.parse(json,json.getNodeFactory().textNode("3.5 g"),field,"").path("parseStatus").asText()).isEqualTo("CONFLICT");
    }
}
