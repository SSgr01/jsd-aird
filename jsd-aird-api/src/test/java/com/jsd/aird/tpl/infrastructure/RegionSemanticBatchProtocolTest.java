package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RegionSemanticBatchProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsRowTableRelationsWhoseLabelsAreInsideTheDataBody() throws Exception {
        var response = objectMapper.readTree("""
                {"recognitionProtocolVersion":2,"regions":[{
                  "regionId":"ingredients","businessName":"配方明细",
                  "fieldRelations":[
                    {"temporaryId":"valid","labelRange":"E6:G6","valueRange":"E7:G21","businessName":"批号",
                     "unit":"","condition":"","valueType":"string","required":false,"editability":"EDITABLE","valueSource":"USER_INPUT"},
                    {"temporaryId":"bad-label","labelRange":"E7:G7","valueRange":"E7:G21","businessName":"批号错误",
                     "unit":"","condition":"","valueType":"string","required":false,"editability":"EDITABLE","valueSource":"USER_INPUT"},
                    {"temporaryId":"bad-total","labelRange":"C6","valueRange":"C22","businessName":"小计错误",
                     "unit":"","condition":"","valueType":"number","required":false,"editability":"READ_ONLY","valueSource":"FORMULA"}
                  ],"rowDimensions":[],"rowAttributes":[],"qualityIssues":[]
                }],"qualityIssues":[]}
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"ingredients","blockId":"ingredients",
                  "candidateRef":"physical-ingredients","sheetId":"sheet-1","range":"A6:G22","type":"ROW_TABLE",
                  "structure":{"headerRange":"A6:G6","dataRange":"A7:G21","totalRange":"A22:G22","recordAxis":"ROW"}}]}
                """);

        var normalized = new RegionSemanticBatchProtocol(objectMapper).validate(response, context);

        assertThat(normalized.path("regions").get(0).path("fieldRelations"))
                .extracting(node -> node.path("temporaryId").asText())
                .containsExactly("valid");
        assertThat(normalized.path("qualityIssues"))
                .extracting(node -> node.path("issueType").asText())
                .contains("INVALID_FIELD_RELATION");
    }

    @Test
    void normalizesRangeOnlyAxisShorthandWithoutChangingGeometry() throws Exception {
        var protocol = new RegionSemanticBatchProtocol(objectMapper);
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion": 2,
                  "regions": [{
                    "regionId": "region-1",
                    "businessName": "测试表",
                    "rowDimensions": ["A5:A19"],
                    "rowAttributes": ["B5:B19"],
                    "fieldRelations": [],
                    "qualityIssues": []
                  }],
                  "qualityIssues": []
                }
                """);
        var context = objectMapper.readTree("""
                {
                  "semanticRegions": [{
                    "regionId": "region-1",
                    "range": "A4:H19",
                    "type": "COLUMN_TABLE",
                    "structure": {"rowHeaderRange": "A4:B19"}
                  }]
                }
                """);

        var normalized = protocol.validate(response, context);

        assertThat(normalized.path("regions")).hasSize(1);
        assertThat(normalized.path("regions").get(0).path("rowDimensions").get(0)
                .path("sourceRange").asText()).isEqualTo("A5:A19");
        assertThat(normalized.path("regions").get(0).path("rowAttributes").get(0)
                .path("role").asText()).isEqualTo("ROW_ATTRIBUTE");
    }

    @Test
    void treatsMissingTopLevelQualityIssuesAsEmptyCollection() throws Exception {
        var protocol = new RegionSemanticBatchProtocol(objectMapper);
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion": 2,
                  "regions": [{
                    "regionId": "region-1",
                    "businessName": "测试表",
                    "rowDimensions": [],
                    "rowAttributes": [],
                    "fieldRelations": [],
                    "qualityIssues": []
                  }]
                }
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"region-1","range":"A1:B2","type":"COLUMN_TABLE"}]}
                """);

        var normalized = protocol.validate(response, context);

        assertThat(normalized.path("regions")).hasSize(1);
        assertThat(normalized.path("qualityIssues").isArray()).isTrue();
        assertThat(normalized.path("qualityIssues")).isEmpty();
    }

    @Test
    void keepsRegionWhenProviderReturnsAxisMetadataObjectInsteadOfArray() throws Exception {
        var protocol = new RegionSemanticBatchProtocol(objectMapper);
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion": 2,
                  "regions": [{
                    "regionId": "region-1",
                    "businessName": "重复记录区域",
                    "rowDimensions": ["表头行"],
                    "rowAttributes": {"structureType":"COLUMN_TABLE","recordAxis":"COLUMN"},
                    "fieldRelations": [],
                    "qualityIssues": []
                  }],
                  "qualityIssues": []
                }
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"region-1","sheetId":"sheet-1",
                  "range":"A4:H19","type":"COLUMN_TABLE",
                  "structure":{"headerRange":"A4:H4","dataRange":"A5:H19","recordAxis":"COLUMN"}}]}
                """);

        var normalized = protocol.validate(response, context);

        assertThat(normalized.path("regions")).hasSize(1);
        assertThat(normalized.path("regions").get(0).path("rowAttributes")).isEmpty();
        assertThat(normalized.path("qualityIssues")).anySatisfy(issue ->
                assertThat(issue.path("issueType").asText()).isEqualTo("INVALID_AXIS_COLLECTION"));
    }

    @Test
    void rejectsColumnTableRelationsThatOnlyOccupyTheLeftLabelBand() throws Exception {
        var protocol = new RegionSemanticBatchProtocol(objectMapper);
        var response = objectMapper.readTree("""
                {"recognitionProtocolVersion":2,"regions":[{
                  "regionId":"region-1","businessName":"光引发剂测试",
                  "rowDimensions":[],"rowAttributes":[],
                  "fieldRelations":[
                    {"temporaryId":"wrong","labelRange":"A5","valueRange":"A6:A19",
                     "businessName":"错误字段","valueType":"string","editability":"EDITABLE",
                     "valueSource":"USER_INPUT","unit":"","condition":""},
                    {"temporaryId":"right","labelRange":"B5","valueRange":"C5:H5",
                     "businessName":"耐油笔","valueType":"string","editability":"EDITABLE",
                     "valueSource":"USER_INPUT","unit":"","condition":""}],
                  "qualityIssues":[]}],"qualityIssues":[]}
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"region-1","range":"A4:H19","type":"COLUMN_TABLE",
                  "structure":{"recordProjection":{"mode":"COLUMN_RECORDS","recordAxis":"COLUMN",
                    "recordColumns":["C","D","E","F","G","H"]}}}]}
                """);

        var normalized = protocol.validate(response, context);

        assertThat(normalized.path("regions").get(0).path("fieldRelations")).hasSize(1);
        assertThat(normalized.path("regions").get(0).path("fieldRelations").get(0)
                .path("businessName").asText()).isEqualTo("耐油笔");
        assertThat(normalized.path("qualityIssues")).anySatisfy(issue ->
                assertThat(issue.path("issueType").asText()).isEqualTo("INVALID_FIELD_RELATION"));
    }
}
