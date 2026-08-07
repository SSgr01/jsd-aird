package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MatrixSemanticProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void requiresAndCompilesCompleteMatrixAxes() throws Exception {
        var facts = objectMapper.readTree("""
                {"structureVersion":6,"sheets":[{"id":"s1","name":"实验矩阵","usedRange":"A1:D5"}]}
                """);
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":1,
                  "semanticAnnotations":[],
                  "businessBlocks":[
                    {"temporaryId":"b1","sheetId":"s1","range":"A1:D5","type":"MATRIX","parentTemporaryId":"","businessName":"性能矩阵","groupNameSuggestion":"性能信息","semanticKeySuggestion":"performanceMatrix"}
                  ],
                  "fieldRelations":[],
                  "tables":[{
                    "temporaryId":"t1","sheetId":"s1","range":"A1:D5","tableKind":"MATRIX","businessName":"性能矩阵","blockTemporaryId":"b1","groupNameSuggestion":"性能信息","semanticKeySuggestion":"performanceMatrix",
                    "headerRange":"B1:D1","dataRange":"B2:D5","totalRange":"","semanticMode":"RECORD_SET","rowHeaderRange":"A2:A5","columnHeaderRange":"B1:D1","crossDataRange":"B2:D5",
                    "headerTree":[
                      {"temporaryId":"h1","parentTemporaryId":"","name":"实验记录","range":"B1:D1","axis":"COLUMN"},
                      {"temporaryId":"h2","parentTemporaryId":"","name":"性能指标","range":"A2:A5","axis":"ROW"}
                    ],
                    "columns":[]
                  }],
                  "qualityIssues":[]
                }
                """);

        var protocol = new GlobalSemanticRecognitionProtocol(objectMapper);
        var validated = protocol.validate(response, facts);
        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compile(validated, facts);

        var matrix = compiled.suggestions().get(1).payload().path("matrixModel");
        assertThat(matrix.path("semanticMode").asText()).isEqualTo("RECORD_SET");
        assertThat(matrix.path("rowHeaderRange").asText()).isEqualTo("A2:A5");
        assertThat(matrix.path("columnHeaderRange").asText()).isEqualTo("B1:D1");
        assertThat(matrix.path("crossDataRange").asText()).isEqualTo("B2:D5");
        assertThat(matrix.path("headerTree")).hasSize(2);

        var emptyTerminationRule = response.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) emptyTerminationRule.path("tables").get(0))
                .putObject("terminationRule");
        var normalized = protocol.validate(emptyTerminationRule, facts);
        assertThat(normalized.path("tables")).singleElement()
                .satisfies(table -> assertThat(table.path("terminationRule").path("type").asText())
                        .isEqualTo("UNTIL_REGION_END"));

        ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("tables").get(0))
                .put("rowHeaderRange", "B2:B5");
        var recovered = protocol.validate(response, facts);
        // The malformed candidate remains diagnostic-only.  It must not be
        // promoted back into the reviewable table list.
        assertThat(recovered.path("tables")).isEmpty();
        assertThat(recovered.path("_rejectedTables")).singleElement()
                .satisfies(table -> assertThat(table.path("temporaryId").asText()).isEqualTo("t1"));
        assertThat(recovered.path("qualityIssues")).singleElement().satisfies(issue ->
                assertThat(issue.path("category").asText()).isEqualTo("TABLE_STRUCTURE_UNCLEAR"));
    }
}
