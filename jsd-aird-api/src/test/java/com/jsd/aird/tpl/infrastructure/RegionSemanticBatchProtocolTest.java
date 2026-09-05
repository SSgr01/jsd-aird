package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RegionSemanticBatchProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsRemovedV2ProtocolInsteadOfReadingItAsLegacyInput() throws Exception {
        var response = objectMapper.readTree("""
                {"recognitionProtocolVersion":2,"regions":[],"qualityIssues":[]}
                """);

        assertThatThrownBy(() -> new RegionSemanticBatchProtocol(objectMapper).validate(
                response, objectMapper.readTree("{\"semanticRegions\":[]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须为 3");
    }

    @Test
    void rejectsRemovedAxisCollectionsInV3() throws Exception {
        var response = objectMapper.readTree("""
                {"recognitionProtocolVersion":3,"regions":[{
                  "regionId":"region-1","businessName":"重复记录区域",
                  "rowDimensions":["A5:A19"],"fieldRelations":[],"qualityIssues":[]
                }],"qualityIssues":[]}
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"region-1","type":"COLUMN_TABLE",
                  "fieldCandidates":[]}]}
                """);

        var normalized = new RegionSemanticBatchProtocol(objectMapper).validate(response, context);

        assertThat(normalized.path("regions")).isEmpty();
        assertThat(normalized.path("qualityIssues"))
                .extracting(node -> node.path("issueType").asText())
                .contains("INVALID_REGION_SEMANTICS");
    }

    @Test
    void acceptsOnlySemanticPatchForAnExistingPhysicalCandidate() throws Exception {
        var protocol = new RegionSemanticBatchProtocol(objectMapper);
        var response = objectMapper.readTree("""
                {"recognitionProtocolVersion":3,"regions":[{
                  "regionId":"form-1","businessName":"基本信息","fieldRelations":[
                    {"candidateRef":"field-1","fieldName":"粘度","valueType":"number","unit":"mPa·s","groupName":"基础性能"},
                    {"candidateRef":"field-1","fieldName":"重复候选","valueType":"string","unit":""},
                    {"candidateRef":"missing","fieldName":"新字段","valueType":"string","unit":""}
                  ],"qualityIssues":[]}],"qualityIssues":[]}
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"form-1","sheetId":"sheet-1","range":"A1:D4","type":"FORM_REGION",
                  "fieldCandidates":[{"candidateRef":"field-1","labelRange":"A2","valueRange":"B2:D2",
                    "valueType":"string","editability":"EDITABLE","valueSource":"USER_INPUT","required":false}]}]}
                """);

        var normalized = protocol.validate(response, context);

        assertThat(normalized.path("regions").get(0).path("fieldRelations")).hasSize(1);
        var relation = normalized.path("regions").get(0).path("fieldRelations").get(0);
        assertThat(relation.path("candidateRef").asText()).isEqualTo("field-1");
        assertThat(relation.path("labelRange").asText()).isEqualTo("A2");
        assertThat(relation.path("valueRange").asText()).isEqualTo("B2:D2");
        assertThat(relation.path("editability").asText()).isEqualTo("EDITABLE");
        assertThat(relation.path("valueSource").asText()).isEqualTo("USER_INPUT");
        assertThat(normalized.path("qualityIssues"))
                .extracting(node -> node.path("issueType").asText())
                .contains("INVALID_FIELD_RELATION");
    }

    @Test
    void rejectsGeometryPropertiesReturnedByTheModel() throws Exception {
        var response = objectMapper.readTree("""
                {"recognitionProtocolVersion":3,"regions":[{
                  "regionId":"region-1","businessName":"测试表","fieldRelations":[
                    {"candidateRef":"field-1","fieldName":"名称","valueType":"string","unit":"","labelRange":"Z99"}
                  ],"qualityIssues":[]}],"qualityIssues":[]}
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"region-1","type":"FORM_REGION",
                  "fieldCandidates":[{"candidateRef":"field-1","labelRange":"A1","valueRange":"B1"}]}]}
                """);

        var normalized = new RegionSemanticBatchProtocol(objectMapper).validate(response, context);

        assertThat(normalized.path("regions").get(0).path("fieldRelations")).isEmpty();
        assertThat(normalized.path("qualityIssues"))
                .extracting(node -> node.path("issueType").asText())
                .contains("INVALID_FIELD_RELATION");
    }

    @Test
    void allowsAnEmptyV3RegionWithoutLegacyMetadata() throws Exception {
        var response = objectMapper.readTree("""
                {"recognitionProtocolVersion":3,"regions":[{
                  "regionId":"region-1","businessName":"基本信息","fieldRelations":[],"qualityIssues":[]
                }],"qualityIssues":[]}
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"region-1","type":"FORM_REGION","fieldCandidates":[]}]}
                """);

        var normalized = new RegionSemanticBatchProtocol(objectMapper).validate(response, context);

        assertThat(normalized.path("regions")).hasSize(1);
        assertThat(normalized.path("regions").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("regionId", "businessName", "fieldRelations", "qualityIssues");
    }
}
