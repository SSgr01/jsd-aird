package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StructureAssessmentProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void defaultsMissingFormRecordAxisButKeepsTableAxisStrict() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"sheet-1","usedRange":"A1:J28"}]}
                """);
        var response = objectMapper.readTree("""
                {"recognitionProtocolVersion":2,"proposals":[
                  {"proposalId":"top","sheetId":"sheet-1","type":"FORM_REGION","range":"A1:J5","confidence":0.94},
                  {"proposalId":"table","sheetId":"sheet-1","type":"ROW_TABLE","range":"A6:G22",
                   "headerRange":"A6:G6","dataRange":"A7:G21","confidence":0.91}
                ],"qualityIssues":[]}
                """);

        var validated = new StructureAssessmentProtocol(objectMapper).validate(response, facts);

        assertThat(validated.assessments()).hasSize(1);
        assertThat(validated.assessments().get(0).path("proposalId").asText()).isEqualTo("top");
        assertThat(validated.assessments().get(0).path("recordAxis").asText()).isEqualTo("UNKNOWN");
        assertThat(validated.qualityIssues()).anySatisfy(issue -> {
            assertThat(issue.path("issueType").asText()).isEqualTo("PROTOCOL_DEFAULT_APPLIED");
            assertThat(issue.path("proposalId").asText()).isEqualTo("top");
        });
        assertThat(validated.qualityIssues()).anySatisfy(issue -> {
            assertThat(issue.path("title").asText()).isEqualTo("模型结构提议已忽略");
            assertThat(issue.path("proposal").path("proposalId").asText()).isEqualTo("table");
        });
    }

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

    @Test
    void normalizesModelSeverityToTheDatabaseVocabulary() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"sheet-1","usedRange":"A1:J30"}]}
                """);
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":2,
                  "proposals":[],
                  "qualityIssues":[
                    {"issueType":"OTHER","severity":"MEDIUM","title":"中等问题"},
                    {"issueType":"OTHER","severity":"critical","title":"严重问题"},
                    {"issueType":"OTHER","severity":"unexpected","title":"未知级别"}
                  ]
                }
                """);

        var result = new StructureAssessmentProtocol(objectMapper).validate(response, facts);

        assertThat(result.qualityIssues()).extracting(issue -> issue.path("severity").asText())
                .containsExactly("WARNING", "BLOCKER", "WARNING");
    }
}
