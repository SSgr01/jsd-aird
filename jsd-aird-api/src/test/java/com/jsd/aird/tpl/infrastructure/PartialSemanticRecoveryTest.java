package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class PartialSemanticRecoveryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsValidRelationsWhenOneSameCellCandidateAndOneTableAreInvalid() throws Exception {
        var facts = objectMapper.readTree("""
                {
                  "structureVersion":6,
                  "sheets":[{"id":"s1","name":"M-687 NT","usedRange":"A1:L20"}],
                  "semanticCells":[
                    {"sheetId":"s1","address":"A1","value":"杰事达化工有限公司"},
                    {"sheetId":"s1","address":"J4","value":"表单编号：JSD-SC-001"},
                    {"sheetId":"s1","address":"A5","value":"品名"},
                    {"sheetId":"s1","address":"B5","value":"M-687 NT"}
                  ]
                }
                """);
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":1,
                  "semanticAnnotations":[
                    {"sheetId":"s1","range":"A1","role":"FIELD_VALUE","temporaryRelationRef":"r-company","temporaryBlockRef":"b-head","temporaryTableRef":""},
                    {"sheetId":"s1","range":"J4","role":"INLINE_METADATA","temporaryRelationRef":"r-number","temporaryBlockRef":"b-meta","temporaryTableRef":""},
                    {"sheetId":"s1","range":"A5","role":"FIELD_LABEL","temporaryRelationRef":"r-product","temporaryBlockRef":"b-form","temporaryTableRef":""},
                    {"sheetId":"s1","range":"B5","role":"FIELD_VALUE","temporaryRelationRef":"r-product","temporaryBlockRef":"b-form","temporaryTableRef":""},
                    {"sheetId":"s1","range":"A7:D7","role":"TABLE_HEADER","temporaryRelationRef":"","temporaryBlockRef":"b-table","temporaryTableRef":"t-invalid"}
                  ],
                  "businessBlocks":[
                    {"temporaryId":"b-head","sheetId":"s1","range":"A1:D2","type":"DOCUMENT_HEADER","parentTemporaryId":"","businessName":"文档标题","groupNameSuggestion":"","semanticKeySuggestion":"documentHeader"},
                    {"temporaryId":"b-meta","sheetId":"s1","range":"J4:L4","type":"FORM_FIELDS","parentTemporaryId":"","businessName":"单据信息","groupNameSuggestion":"基础信息","semanticKeySuggestion":"documentMeta"},
                    {"temporaryId":"b-form","sheetId":"s1","range":"A5:D6","type":"FORM_FIELDS","parentTemporaryId":"","businessName":"基础信息","groupNameSuggestion":"基础信息","semanticKeySuggestion":"basicInformation"},
                    {"temporaryId":"b-table","sheetId":"s1","range":"A7:D11","type":"ROW_TABLE","parentTemporaryId":"","businessName":"配方明细","groupNameSuggestion":"配方明细","semanticKeySuggestion":"formulaItems"}
                  ],
                  "fieldRelations":[
                    {"temporaryId":"r-company","sheetId":"s1","labelRange":"A1","valueRange":"A1","relationType":"LABEL_VALUE","businessName":"公司名称","blockTemporaryId":"","groupNameSuggestion":"基础信息","semanticKeySuggestion":"companyName","valueType":"string","required":false,"editability":"READ_ONLY","valueSource":"STATIC","unit":"","condition":""},
                    {"temporaryId":"r-number","sheetId":"s1","labelRange":"J4","valueRange":"J4","relationType":"LABEL_VALUE","businessName":"表单编号","blockTemporaryId":"","groupNameSuggestion":"基础信息","semanticKeySuggestion":"formNumber","valueType":"string","required":false,"editability":"READ_ONLY","valueSource":"STATIC","unit":"","condition":""},
                    {"temporaryId":"r-product","sheetId":"s1","labelRange":"A5","valueRange":"B5","relationType":"LABEL_VALUE","businessName":"品名","blockTemporaryId":"","groupNameSuggestion":"基础信息","semanticKeySuggestion":"productName","valueType":"string","required":true,"editability":"EDITABLE","valueSource":"USER_INPUT","unit":"","condition":""}
                  ],
                  "tables":[{
                    "temporaryId":"t-invalid","sheetId":"s1","range":"A7:D10","tableKind":"ROW_TABLE","businessName":"配方明细","blockTemporaryId":"b-table","groupNameSuggestion":"配方明细","semanticKeySuggestion":"formulaItems","headerRange":"A7:D7","dataRange":"A8:D10","totalRange":"A11:D11","semanticMode":"ROW_RECORDS","rowHeaderRange":"","columnHeaderRange":"","crossDataRange":"","headerTree":[],
                    "columns":[{"temporaryId":"c1","name":"原料","labelRange":"A7","valueRange":"A8:A10","valueType":"string","editability":"EDITABLE","valueSource":"USER_INPUT","unit":"","condition":"","semanticKeySuggestion":"material"}]
                  }],
                  "qualityIssues":[]
                }
                """);

        var validated = new GlobalSemanticRecognitionProtocol(objectMapper).validate(response, facts);

        assertThat(validated.path("fieldRelations")).hasSize(2);
        assertThat(validated.path("fieldRelations")).anySatisfy(relation -> {
            assertThat(relation.path("temporaryId").asText()).isEqualTo("r-number");
            assertThat(relation.path("relationType").asText()).isEqualTo("INLINE_TEXT");
            assertThat(relation.path("blockTemporaryId").asText()).isEqualTo("b-meta");
        });
        assertThat(validated.path("fieldRelations")).noneSatisfy(relation ->
                assertThat(relation.path("temporaryId").asText()).isEqualTo("r-company"));
        assertThat(validated.path("tables")).isEmpty();
        assertThat(validated.path("semanticAnnotations")).noneSatisfy(annotation ->
                assertThat(annotation.path("temporaryRelationRef").asText()).isEqualTo("r-company"));
        assertThat(validated.path("qualityIssues")).hasSize(2);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compile(validated, facts);
        assertThat(compiled.suggestions()).hasSize(3);
        assertThat(compiled.suggestions().stream().map(item -> item.payload().path("fieldName").asText()))
                .contains("表单编号", "品名").doesNotContain("公司名称", "配方明细");
    }

    @Test
    void stillRejectsAnInvalidRootProtocol() throws Exception {
        var facts = objectMapper.readTree("""
                {"structureVersion":6,"sheets":[{"id":"s1","name":"Sheet1","usedRange":"A1"}]}
                """);
        ObjectNode response = (ObjectNode) objectMapper.readTree("""
                {"recognitionProtocolVersion":99,"semanticAnnotations":[],"businessBlocks":[],"fieldRelations":[],"tables":[],"qualityIssues":[]}
                """);

        assertThatThrownBy(() -> new GlobalSemanticRecognitionProtocol(objectMapper).validate(response, facts))
                .isInstanceOf(GlobalSemanticRecognitionProtocol.ProtocolViolationException.class)
                .hasMessageContaining("recognitionProtocolVersion 必须为 1");
    }
}
