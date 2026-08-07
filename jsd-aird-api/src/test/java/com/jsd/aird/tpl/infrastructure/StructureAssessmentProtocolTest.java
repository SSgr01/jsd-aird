package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StructureAssessmentProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsValidProposalsAndPublishesInvalidGeometryAsDiagnostics() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"Sheet1","usedRange":"A1:I40"}]}
                """);
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":2,
                  "proposals":[
                    {"proposalId":"valid","sheetId":"Sheet1","type":"MATRIX","range":"A28:I37",
                     "cornerRange":"A28:C28","rowHeaderRange":"A29:C37","columnHeaderRange":"D28:I28",
                     "crossDataRange":"D29:I37","recordAxis":"COLUMN","confidence":0.78},
                    {"proposalId":"invalid","sheetId":"Sheet1","type":"MATRIX","range":"A28:I37",
                     "cornerRange":"A28:C28","rowHeaderRange":"A29:C37","columnHeaderRange":"NOT_A_RANGE",
                     "crossDataRange":"D29:I37","recordAxis":"COLUMN","confidence":0.5}
                  ],
                  "qualityIssues":[]
                }
                """);

        var result = new StructureAssessmentProtocol(objectMapper).validate(response, facts);

        assertThat(result.assessments()).singleElement()
                .satisfies(proposal -> assertThat(proposal.path("proposalId").asText()).isEqualTo("valid"));
        assertThat(result.qualityIssues()).anySatisfy(issue -> {
            assertThat(issue.path("issueType").asText()).isEqualTo("INVALID_STRUCTURE_PROPOSAL");
            assertThat(issue.path("proposal").path("proposalId").asText()).isEqualTo("invalid");
        });
    }

    @Test
    void validatesOrdinaryTableHeaderAndDataGeometry() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"Sheet1","usedRange":"A1:I40"}]}
                """);
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":2,
                  "proposals":[{"proposalId":"rows","sheetId":"Sheet1","type":"ROW_TABLE","range":"A20:I27",
                    "cornerRange":"","rowHeaderRange":"","columnHeaderRange":"","crossDataRange":"",
                    "headerRange":"D20:I20","dataRange":"D21:I27","totalRange":"D26:I26",
                    "recordAxis":"ROW","recordHeight":1,"recordWidth":6,"recordStride":1,"confidence":0.6}],
                  "qualityIssues":[]
                }
                """);

        var result = new StructureAssessmentProtocol(objectMapper).validate(response, facts);

        assertThat(result.assessments()).singleElement()
                .satisfies(proposal -> assertThat(proposal.path("dataRange").asText()).isEqualTo("D21:I27"));
        assertThat(result.qualityIssues()).isEmpty();
    }

    @Test
    void rejectsOrdinaryTableWithoutGeometryAndKeepsOtherProposals() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"Sheet1","usedRange":"A1:J30"}]}
                """);
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":2,
                  "proposals":[
                    {"proposalId":"form","sheetId":"Sheet1","type":"FORM_REGION","range":"A1:J2","recordAxis":"UNKNOWN","confidence":0.9},
                    {"proposalId":"table","sheetId":"Sheet1","type":"ROW_TABLE","range":"A6:J22","recordAxis":"ROW","confidence":0.95}
                  ],
                  "qualityIssues":[]
                }
                """);

        var result = new StructureAssessmentProtocol(objectMapper).validate(response, facts);

        assertThat(result.assessments()).singleElement()
                .satisfies(proposal -> assertThat(proposal.path("proposalId").asText()).isEqualTo("form"));
        assertThat(result.qualityIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.path("issueType").asText()).isEqualTo("MISSING_TABLE_GEOMETRY");
            assertThat(issue.path("proposal").path("proposalId").asText()).isEqualTo("table");
        });
    }

    @Test
    void schemaRequiresGeometryByStructureType() {
        var schema = new StructureAssessmentProtocol(objectMapper).responseSchema();
        var allOf = schema.path("properties").path("proposals").path("items").path("allOf");

        assertThat(allOf.size()).isEqualTo(2);
        assertThat(allOf.toString()).contains("ROW_TABLE", "COLUMN_TABLE", "headerRange", "dataRange",
                "cornerRange", "rowHeaderRange", "columnHeaderRange", "crossDataRange");
    }
}
