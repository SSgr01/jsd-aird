package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GlobalSemanticSuggestionCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsUnnamedColumnsAsRuntimeSlotsWithoutCreatingBlankChildren() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","name":"实验记录表","semanticCells":[
                  {"sheetId":"s1","address":"A5","value":"物性测试"},
                  {"sheetId":"s1","address":"C5","value":"外观"},
                  {"sheetId":"s1","address":"D5","value":"目测"}
                ]}]}
                """);
        var response = objectMapper.readTree("""
                {
                  "businessBlocks":[{"temporaryId":"b1","sheetId":"s1","range":"A5:F26","type":"ROW_TABLE","businessName":"物性测试","groupNameSuggestion":"测试数据"}],
                  "fieldRelations":[],
                  "tables":[{"temporaryId":"t1","sheetId":"s1","range":"A5:F26","tableKind":"ROW_TABLE","businessName":"物性测试","blockTemporaryId":"b1","headerRange":"A5:D26","dataRange":"E5:F26","columns":[
                    {"name":"","valueRange":"E5:E26","labelRange":"E5:E5","valueType":"string","editability":"EDITABLE","valueSource":"USER_INPUT"},
                    {"name":"","valueRange":"F5:F26","labelRange":"F5:F5","valueType":"string","editability":"EDITABLE","valueSource":"USER_INPUT"}
                  ]}],
                  "semanticAnnotations":[],
                  "qualityIssues":[]
                }
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compile(
                (com.fasterxml.jackson.databind.node.ObjectNode) response, facts);

        assertThat(compiled.suggestions()).extracting(item -> item.suggestionType())
                .containsExactly("SEMANTIC_MODEL", "ROW_TABLE");
        var table = compiled.suggestions().get(1).payload();
        assertThat(table.path("runtimeInputOnly").asBoolean()).isTrue();
        assertThat(table.path("columnSlots")).hasSize(2);
        assertThat(table.path("longTableModel").path("records")).hasSize(22);
        assertThat(table.path("longTableModel").path("records").get(0).path("trainingEligible").asBoolean())
                .isFalse();
        assertThat(table.path("longTableModel").path("records").get(0).path("measures")).hasSize(2);
    }

    @Test
    void doesNotPromoteInlineTextWithNoSeparateValueRangeToAField() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","name":"实验记录表","semanticCells":[]}]}
                """);
        var response = objectMapper.readTree("""
                {
                  "businessBlocks":[{"temporaryId":"b1","sheetId":"s1","range":"B76","type":"FORM_FIELDS","businessName":"测试信息","groupNameSuggestion":"基础信息"}],
                  "fieldRelations":[{"temporaryId":"r1","sheetId":"s1","labelRange":"B76","valueRange":"B76","relationType":"INLINE_TEXT","businessName":"水煮后附着力测试","blockTemporaryId":"b1","valueType":"string","required":false,"editability":"EDITABLE","valueSource":"USER_INPUT","unit":"","condition":""}],
                  "_rejectedRelations":[{"temporaryId":"r2","sheetId":"s1","labelRange":"B76","valueRange":"B76","relationType":"INLINE_TEXT","businessName":"同格文本候选","blockTemporaryId":"b1","valueType":"string","required":false,"editability":"EDITABLE","valueSource":"USER_INPUT","unit":"","condition":""}],
                  "tables":[],
                  "semanticAnnotations":[],
                  "qualityIssues":[]
                }
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compile(
                (com.fasterxml.jackson.databind.node.ObjectNode) response, facts);

        assertThat(compiled.suggestions()).extracting(item -> item.suggestionType())
                .containsExactly("SEMANTIC_MODEL");
    }

    @Test
    void compilesMatrixAsOneRegionAndDoesNotDowngradeBlankRuntimeHeaders() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","name":"综合测试","semanticCells":[]}]}
                """);
        var response = objectMapper.readTree("""
                {
                  "businessBlocks":[{"temporaryId":"b1","sheetId":"s1","range":"A4:N10","type":"MATRIX","businessName":"综合测试矩阵"}],
                  "fieldRelations":[],
                  "tables":[{"temporaryId":"t1","blockTemporaryId":"b1","sheetId":"s1","range":"A4:N10","tableKind":"MATRIX",
                    "businessName":"综合测试矩阵","headerRange":"E4:N4","dataRange":"E5:N10",
                    "cornerRange":"A4:D4","rowHeaderRange":"A5:D10","columnHeaderRange":"E4:N4","crossDataRange":"E5:N10",
                    "recordAxis":"COLUMN","semanticMode":"CROSS_TAB","columns":[]}],
                  "semanticAnnotations":[],"qualityIssues":[]
                }
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compile(
                (com.fasterxml.jackson.databind.node.ObjectNode) response, facts);

        assertThat(compiled.suggestions()).extracting(item -> item.suggestionType())
                .containsExactly("SEMANTIC_MODEL", "MATRIX");
        var matrix = compiled.suggestions().get(1).payload();
        assertThat(matrix.path("candidateOnly").asBoolean(false)).isFalse();
        assertThat(matrix.path("columns")).isEmpty();
        assertThat(matrix.path("matrixModel").path("columnSlots")).hasSize(10);
        assertThat(matrix.path("longTableModel").path("records")).hasSize(60);
    }
}
