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
                  "sheets":[{"id":"s1","name":"M-687 NT","usedRange":"A1:L20","semanticCells":[
                    {"sheetId":"s1","address":"A1","value":"杰事达化工有限公司"},
                    {"sheetId":"s1","address":"J4","value":"表单编号：JSD-SC-001"},
                    {"sheetId":"s1","address":"A5","value":"品名"},
                    {"sheetId":"s1","address":"B5","value":"M-687 NT"}
                  ]}]
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
        assertThat(validated.path("_rejectedTables")).singleElement()
                .satisfies(table -> assertThat(table.path("temporaryId").asText()).isEqualTo("t-invalid"));
        assertThat(validated.path("semanticAnnotations")).noneSatisfy(annotation ->
                assertThat(annotation.path("temporaryRelationRef").asText()).isEqualTo("r-company"));
        assertThat(validated.path("qualityIssues")).hasSize(2);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compile(validated, facts);
        // 同一单元格的 INLINE_TEXT 只保留为结构证据，不能凭空生成一个独立字段；
        // 被协议拒绝的字段和表格只能出现在 diagnostics，不能进入确认列表。
        assertThat(compiled.suggestions()).hasSize(2);
        assertThat(compiled.suggestions().stream().map(item -> item.payload().path("fieldName").asText()))
                .contains("品名")
                .doesNotContain("表单编号", "配方明细", "公司名称");
        assertThat(compiled.suggestions().get(0).payload().path("diagnostics")).hasSize(2);
        assertThat(compiled.suggestions().get(0).payload().path("diagnostics"))
                .anySatisfy(diagnostic -> assertThat(diagnostic.path("reasonCode").asText())
                        .isEqualTo("REJECTED_FIELD_RELATIONS"));
        assertThat(compiled.suggestions().get(0).payload().path("diagnostics"))
                .anySatisfy(diagnostic -> assertThat(diagnostic.path("reasonCode").asText())
                        .isEqualTo("REJECTED_TABLE_CANDIDATES"));
    }

    @Test
    void normalizesRealWorkbookRegionShapesWithoutLosingFields() throws Exception {
        var facts = objectMapper.readTree("""
                {
                  "structureVersion":6,
                  "sheets":[{"id":"s1","name":"生产记录","usedRange":"A1:J28","mergedRanges":[
                    {"address":"A24:E24"},{"address":"F24:J24"},{"address":"A25:E25"},{"address":"F25:J25"}
                  ]}]
                }
                """);
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":1,"semanticAnnotations":[],
                  "businessBlocks":[
                    {"temporaryId":"header","sheetId":"s1","range":"A4:H5","type":"FORM_FIELDS","parentTemporaryId":"","businessName":"基本信息","groupNameSuggestion":"基础信息","semanticKeySuggestion":"header"},
                    {"temporaryId":"table-block","sheetId":"s1","range":"A6:G23","type":"ROW_TABLE","parentTemporaryId":"","businessName":"原料投料明细","groupNameSuggestion":"配方明细","semanticKeySuggestion":"materials"},
                    {"temporaryId":"output","sheetId":"s1","range":"A24:F25","type":"FORM_FIELDS","parentTemporaryId":"","businessName":"产出汇总","groupNameSuggestion":"产出信息","semanticKeySuggestion":"output"},
                    {"temporaryId":"signatures","sheetId":"s1","range":"A28:I28","type":"FORM_FIELDS","parentTemporaryId":"","businessName":"人员确认","groupNameSuggestion":"签字确认","semanticKeySuggestion":"signatures"}
                  ],
                  "fieldRelations":[
                    {"temporaryId":"order","sheetId":"s1","labelRange":"H4","valueRange":"I4:J4","relationType":"LABEL_VALUE","businessName":"订单号","blockTemporaryId":"header","groupNameSuggestion":"基础信息","semanticKeySuggestion":"order_no","valueType":"string","required":false,"editability":"EDITABLE","valueSource":"USER_INPUT","unit":"","condition":""},
                    {"temporaryId":"yield","sheetId":"s1","labelRange":"A25:E25","valueRange":"B25:D25","relationType":"INLINE_TEXT","businessName":"实际产量","blockTemporaryId":"output","groupNameSuggestion":"产出信息","semanticKeySuggestion":"actual_yield","valueType":"number","required":false,"editability":"EDITABLE","valueSource":"USER_INPUT","unit":"","condition":""}
                  ],
                  "tables":[{
                    "temporaryId":"materials","sheetId":"s1","range":"A6:G23","tableKind":"ROW_TABLE","businessName":"原料投料明细","blockTemporaryId":"table-block","groupNameSuggestion":"配方明细","semanticKeySuggestion":"materials","headerRange":"A6:G7","dataRange":"A8:G22","totalRange":"A23:G23",
                    "columns":[{"temporaryId":"material-code","name":"原料编号","labelRange":"B6","valueRange":"B8:B22","valueType":"string","editability":"EDITABLE","valueSource":"USER_INPUT","unit":"","condition":"","semanticKeySuggestion":"material_code"}],
                    "semanticMode":"RECORD_SET","rowHeaderRange":"","columnHeaderRange":"A6:G7","crossDataRange":"","headerTree":[],"repeatAxis":"ROW","recordHeight":1,"recordWidth":7,"recordStride":1,"terminationRule":{"type":"FIXED_COUNT","count":15}
                  }],
                  "qualityIssues":[]
                }
                """);

        var validated = new GlobalSemanticRecognitionProtocol(objectMapper).validate(response, facts);

        assertThat(validated.path("businessBlocks")).anySatisfy(block -> {
            if ("header".equals(block.path("temporaryId").asText())) {
                assertThat(block.path("range").asText()).isEqualTo("A4:J5");
            }
        });
        assertThat(validated.path("businessBlocks")).anySatisfy(block -> {
            if ("output".equals(block.path("temporaryId").asText())) {
                assertThat(block.path("range").asText()).isEqualTo("A24:J25");
            }
        });
        assertThat(validated.path("fieldRelations")).anySatisfy(relation -> {
            assertThat(relation.path("temporaryId").asText()).isEqualTo("yield");
            assertThat(relation.path("relationType").asText()).isEqualTo("INLINE_TEXT");
            assertThat(relation.path("labelRange").asText()).isEqualTo("A25:E25");
            assertThat(relation.path("valueRange").asText()).isEqualTo("A25:E25");
        });
        assertThat(validated.path("tables")).singleElement().satisfies(table -> {
            assertThat(table.path("semanticMode").asText()).isEqualTo("ROW_RECORDS");
            assertThat(table.path("columnHeaderRange").asText()).isEmpty();
            assertThat(table.path("terminationRule").path("maxRecords").asInt()).isEqualTo(15);
            assertThat(table.path("columns")).hasSize(1);
        });
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

    @Test
    void keepsTheLargerOverlappingBlockAndMigratesContainedRelations() throws Exception {
        var facts = objectMapper.readTree("""
                {"structureVersion":6,"sheets":[{"id":"s1","name":"UV-507E","usedRange":"A1:L12"}]}
                """);
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":1,"semanticAnnotations":[],
                  "businessBlocks":[
                    {"temporaryId":"small","sheetId":"s1","range":"A1:F5","type":"FORM_FIELDS","parentTemporaryId":"","businessName":"表单元信息","groupNameSuggestion":"基础信息","semanticKeySuggestion":"meta"},
                    {"temporaryId":"large","sheetId":"s1","range":"C3:L12","type":"FORM_FIELDS","parentTemporaryId":"","businessName":"产品基础信息","groupNameSuggestion":"基础信息","semanticKeySuggestion":"basic"}
                  ],
                  "fieldRelations":[
                    {"temporaryId":"r1","sheetId":"s1","labelRange":"D4","valueRange":"E4","relationType":"LABEL_VALUE","businessName":"产品名称","blockTemporaryId":"small","groupNameSuggestion":"基础信息","semanticKeySuggestion":"productName","valueType":"string","required":false,"editability":"EDITABLE","valueSource":"USER_INPUT","unit":"","condition":""}
                  ],
                  "tables":[],"qualityIssues":[]
                }
                """);

        var validated = new GlobalSemanticRecognitionProtocol(objectMapper).validate(response, facts);

        assertThat(validated.path("businessBlocks")).singleElement()
                .satisfies(block -> assertThat(block.path("temporaryId").asText()).isEqualTo("large"));
        assertThat(validated.path("fieldRelations")).singleElement()
                .satisfies(relation -> assertThat(relation.path("blockTemporaryId").asText()).isEqualTo("large"));
        assertThat(validated.path("qualityIssues")).anySatisfy(issue ->
                assertThat(issue.path("category").asText()).isEqualTo("BUSINESS_BLOCK_UNCLEAR"));
    }

    @Test
    void rejectsMisalignedTableColumnsAndDoesNotPromoteThemToScalarFields() throws Exception {
        var facts = objectMapper.readTree("""
                {"structureVersion":6,"sheets":[{"id":"s1","name":"M-687 NT","usedRange":"A1:I10"}]}
                """);
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":1,"semanticAnnotations":[],
                  "businessBlocks":[
                    {"temporaryId":"table-block","sheetId":"s1","range":"A7:I10","type":"ROW_TABLE","parentTemporaryId":"","businessName":"配方明细","groupNameSuggestion":"配方明细","semanticKeySuggestion":"formulaItems"}
                  ],
                  "fieldRelations":[],
                  "tables":[{
                    "temporaryId":"t1","sheetId":"s1","range":"A7:I10","tableKind":"ROW_TABLE","businessName":"配方明细","blockTemporaryId":"table-block","groupNameSuggestion":"配方明细","semanticKeySuggestion":"formulaItems",
                    "headerRange":"A7:I7","dataRange":"A9:I10","totalRange":"","semanticMode":"ROW_RECORDS","rowHeaderRange":"","columnHeaderRange":"","crossDataRange":"","headerTree":[],
                    "columns":[{"temporaryId":"c1","name":"配方比例","labelRange":"D7","valueRange":"C9:C10","valueType":"number","editability":"EDITABLE","valueSource":"USER_INPUT","unit":"","condition":"","semanticKeySuggestion":"ratio"}]
                  }],
                  "qualityIssues":[]
                }
                """);

        var validated = new GlobalSemanticRecognitionProtocol(objectMapper).validate(response, facts);
        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compile(validated, facts);

        assertThat(validated.path("tables")).isEmpty();
        assertThat(validated.path("_rejectedTables")).singleElement()
                .satisfies(table -> assertThat(table.path("temporaryId").asText()).isEqualTo("t1"));
        assertThat(compiled.suggestions().stream().map(item -> item.payload().path("fieldName").asText()))
                .doesNotContain("配方比例");
    }
}
