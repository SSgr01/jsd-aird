package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GlobalSemanticSuggestionCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completesFormRegionFromPhysicalLabelsAndKeepsUnboundFieldsVisible() throws Exception {
        var context = objectMapper.readTree("""
                {"sheets":[{"id":"s1","name":"光引发剂对比测试","semanticCells":[
                  {"sheetId":"s1","address":"A1","value":"不同光引发剂耐黄变对比测试","mergedRange":"A1:H1"},
                  {"sheetId":"s1","address":"A2","value":"目的"},
                  {"sheetId":"s1","address":"B2","value":"测试不同光引发剂对黄变性的影响","mergedRange":"B2:D2"},
                  {"sheetId":"s1","address":"E2","value":"时间"},
                  {"sheetId":"s1","address":"F2","inputCandidate":true},
                  {"sheetId":"s1","address":"G2","value":"测试人"},
                  {"sheetId":"s1","address":"H2","inputCandidate":true},
                  {"sheetId":"s1","address":"A3","value":"固化条件"},
                  {"sheetId":"s1","address":"B3","inputCandidate":true,"mergedRange":"B3:E3"},
                  {"sheetId":"s1","address":"F3","value":"膜厚："},
                  {"sheetId":"s1","address":"G3","value":"素材：","mergedRange":"G3:H3"}
                ]}],"semanticRegions":[{"regionId":"form-1","sheetId":"s1","range":"A1:H3",
                  "type":"FORM_REGION","candidateRef":"form-candidate","structure":{}}]}
                """);
        var response = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {"recognitionProtocolVersion":2,"regions":[{"regionId":"form-1","businessName":"基本信息区域",
                  "rowDimensions":[],"rowAttributes":[],"fieldRelations":[],"qualityIssues":[]}],"qualityIssues":[]}
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compileRegionBatch(response, context);
        var fields = compiled.suggestions().stream()
                .filter(item -> "SCALAR_FIELD".equals(item.suggestionType())).toList();

        assertThat(fields).extracting(item -> item.payload().path("fieldName").asText())
                .containsExactlyInAnyOrder("目的", "时间", "测试人", "固化条件", "膜厚", "素材");
        var purpose = fields.stream().filter(item -> "目的".equals(item.payload().path("fieldName").asText()))
                .findFirst().orElseThrow().payload();
        assertThat(purpose.path("locator").path("address").asText()).isEqualTo("B2:D2");
        assertThat(purpose.path("editability").asText()).isEqualTo("READ_ONLY");
        var tester = fields.stream().filter(item -> "测试人".equals(item.payload().path("fieldName").asText()))
                .findFirst().orElseThrow().payload();
        assertThat(tester.path("locator").path("address").asText()).isEqualTo("H2");
        var thickness = fields.stream().filter(item -> "膜厚".equals(item.payload().path("fieldName").asText()))
                .findFirst().orElseThrow().payload();
        assertThat(thickness.path("positionPending").asBoolean()).isTrue();
        assertThat(thickness.path("locator").path("address").asText()).isBlank();
        assertThat(thickness.path("pendingReason").asText()).isEqualTo("FIELD_POSITION_REQUIRED");
    }

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
    void fallsBackToPhysicalLabelForUnnamedScalarRelation() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","name":"基础信息","semanticCells":[
                  {"sheetId":"s1","address":"A2","value":"客户名称"}
                ]}]}
                """);
        var response = objectMapper.readTree("""
                {
                  "businessBlocks":[{"temporaryId":"b1","sheetId":"s1","range":"A2:B2","type":"FORM_FIELDS","businessName":"基础信息"}],
                  "fieldRelations":[{"temporaryId":"r1","sheetId":"s1","labelRange":"A2","valueRange":"B2","relationType":"LABEL_VALUE","businessName":"","blockTemporaryId":"b1","valueType":"string","required":false,"editability":"EDITABLE","valueSource":"USER_INPUT","unit":"","condition":""}],
                  "tables":[],"semanticAnnotations":[],"qualityIssues":[]
                }
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compile(
                (com.fasterxml.jackson.databind.node.ObjectNode) response, facts);

        var field = compiled.suggestions().stream()
                .filter(item -> "SCALAR_FIELD".equals(item.suggestionType())).findFirst().orElseThrow();
        assertThat(field.payload().path("fieldName").asText()).isEqualTo("客户名称");
        assertThat(field.payload().path("nameSource").asText()).isEqualTo("PHYSICAL_HEADER_FALLBACK");
        assertThat(field.payload().path("semanticFallback").asBoolean()).isTrue();
        assertThat(field.payload().path("reviewRequired").asBoolean()).isTrue();
    }

    @Test
    void tableChildrenInheritCanonicalRegionAndParentBindingIdentity() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","name":"生产任务单","semanticCells":[]}]}
                """);
        var response = objectMapper.readTree("""
                {
                  "businessBlocks":[{"temporaryId":"region-row-1","sheetId":"s1","range":"A6:J22","type":"ROW_TABLE","businessName":"配方明细"}],
                  "fieldRelations":[],
                  "tables":[{"temporaryId":"table-row-1","blockTemporaryId":"region-row-1","sheetId":"s1","range":"A6:J22","tableKind":"ROW_TABLE",
                    "businessName":"配方明细","headerRange":"A6:J6","dataRange":"A7:J22","repeatAxis":"ROW","recordAxis":"ROW","semanticMode":"ROW_RECORDS","columns":[
                      {"code":"material","name":"原料名称","labelRange":"B6:B6","valueRange":"B7:B22","valueType":"string","editability":"EDITABLE","valueSource":"USER_INPUT"}
                    ]}],
                  "semanticAnnotations":[],"qualityIssues":[]
                }
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compile(
                (com.fasterxml.jackson.databind.node.ObjectNode) response, facts);
        var parent = compiled.suggestions().stream()
                .filter(item -> "ROW_TABLE".equals(item.suggestionType())).findFirst().orElseThrow();
        var child = compiled.suggestions().stream()
                .filter(item -> "TABLE_CHILD_FIELD".equals(item.suggestionType())).findFirst().orElseThrow();

        assertThat(child.payload().path("regionId").asText()).isEqualTo(parent.payload().path("regionId").asText());
        assertThat(child.payload().path("blockId").asText()).isEqualTo(parent.payload().path("blockId").asText());
        assertThat(child.payload().path("parentBlockId").asText()).isEqualTo(parent.payload().path("parentBlockId").asText());
        assertThat(child.payload().path("parentFieldId").asText()).isEqualTo(parent.payload().path("fieldId").asText());
        assertThat(child.payload().path("parentBindingId").asText()).isEqualTo(parent.payload().path("bindingId").asText());
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
                .containsExactly("SEMANTIC_MODEL", "MATRIX", "MATRIX_FIELD");
        var matrix = compiled.suggestions().stream()
                .filter(item -> "MATRIX".equals(item.suggestionType())).findFirst().orElseThrow().payload();
        assertThat(matrix.path("candidateOnly").asBoolean(false)).isFalse();
        assertThat(matrix.path("columns")).isEmpty();
        assertThat(matrix.path("matrixModel").path("columnSlots")).hasSize(10);
        assertThat(matrix.path("longTableModel").path("records")).hasSize(60);
    }

    @Test
    void usesNamedColumnTableRowAttributesWhenSemanticRelationsAreEmpty() throws Exception {
        var facts = objectMapper.readTree("""
                {"sheets":[{"id":"s1","name":"应用测试报告","semanticCells":[
                  {"sheetId":"s1","address":"A5","value":"测试项目"},
                  {"sheetId":"s1","address":"A9","value":"测试板编号"},
                  {"sheetId":"s1","address":"A13","value":"测试条件"}
                ]}]}
                """);
        var normalized = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":2,
                  "regions":[{"regionId":"r1","businessName":"应用测试数据",
                    "rowDimensions":[],
                    "rowAttributes":[
                      {"name":"测试项目","sourceRange":"A5:A8","fillMerged":true,"role":"ROW_ATTRIBUTE"},
                      {"name":"测试板编号","sourceRange":"A9:A12","fillMerged":true,"role":"ROW_ATTRIBUTE"},
                      {"name":"测试条件","sourceRange":"A13:A19","fillMerged":true,"role":"ROW_ATTRIBUTE"}
                    ],
                    "fieldRelations":[],"qualityIssues":[]}],
                  "qualityIssues":[]
                }
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"r1","sheetId":"s1","range":"A4:H19",
                  "type":"COLUMN_TABLE","candidateRef":"physical-1",
                  "structure":{"headerRange":"A4:H4","dataRange":"A5:H19","recordAxis":"COLUMN",
                    "recordProjection":{"mode":"COLUMN_RECORDS","recordAxis":"COLUMN",
                      "recordColumns":["C","D","E","F","G","H"]}}}]}
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compileRegionBatch(normalized, context);
        var table = compiled.suggestions().stream()
                .filter(item -> "COLUMN_TABLE".equals(item.suggestionType())).findFirst().orElseThrow();
        assertThat(table.payload().path("columns")).hasSize(3);
        assertThat(table.payload().path("columns")).extracting(column -> column.path("name").asText())
                .containsExactly("测试项目", "测试板编号", "测试条件");
        assertThat(table.payload().path("columns")).allSatisfy(column -> {
            assertThat(column.path("nameSource").asText()).isEqualTo("ROW_ATTRIBUTE_FALLBACK");
            assertThat(column.path("semanticFallback").asBoolean()).isTrue();
            assertThat(column.path("reviewRequired").asBoolean()).isTrue();
        });
        assertThat(compiled.suggestions()).filteredOn(item -> "TABLE_CHILD_FIELD".equals(item.suggestionType()))
                .allSatisfy(child -> {
                    assertThat(child.payload().path("fieldName").asText()).isNotBlank();
                    assertThat(child.payload().path("regionId").asText())
                            .isEqualTo(table.payload().path("regionId").asText());
                    assertThat(child.payload().path("candidateRef").asText()).isEqualTo("physical-1");
                });
    }

    @Test
    void recoversColumnTableFieldsFromPhysicalLabelBandWhenModelReturnsEmptySemantics() throws Exception {
        var normalized = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":2,
                  "regions":[{"regionId":"r1","businessName":"树脂物性",
                    "rowDimensions":[],"rowAttributes":[],"fieldRelations":[],"qualityIssues":[]}],
                  "qualityIssues":[]
                }
                """);
        var context = objectMapper.readTree("""
                {"sheets":[{"id":"s1","name":"应用测试报告","semanticCells":[
                  {"address":"A8","mergedRange":"A8:B15","value":"树脂物性"},
                  {"address":"C8","value":"树脂编号"},
                  {"address":"C9","value":"树脂内容"},
                  {"address":"C10","value":"外观"},
                  {"address":"C11","value":"实测固含"}
                ]}],
                "semanticRegions":[{"regionId":"r1","sheetId":"s1","range":"A8:I15",
                  "type":"COLUMN_TABLE","candidateRef":"model-r1",
                  "structure":{"headerRange":"A8:I8","dataRange":"A9:I15","recordAxis":"COLUMN"},
                  "resolution":{"suppressedPhysical":{"structure":{"recordProjection":{
                    "mode":"COLUMN_RECORDS","recordAxis":"COLUMN","recordColumns":["D","E","F","G","H","I"]
                  }}}}}]}
                """
        );

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compileRegionBatch(normalized, context);
        var table = compiled.suggestions().stream()
                .filter(item -> "COLUMN_TABLE".equals(item.suggestionType())).findFirst().orElseThrow();

        assertThat(table.payload().path("columns")).hasSize(4);
        assertThat(table.payload().path("columns")).extracting(column -> column.path("name").asText())
                .containsExactly("树脂编号", "树脂内容", "外观", "实测固含");
        assertThat(table.payload().path("columns")).allSatisfy(column -> {
            assertThat(column.path("name").asText()).isNotBlank();
            assertThat(column.path("nameSource").asText()).isEqualTo("ROW_ATTRIBUTE_FALLBACK");
            assertThat(column.path("valueRange").asText()).startsWith("D");
        });
    }

    @Test
    void infersRuntimeRecordColumnsAndHierarchicalLabelsFromMergedHeaderBand() throws Exception {
        var normalized = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":2,
                  "regions":[{"regionId":"r1","businessName":"光引发剂耐黄变对比测试数据表",
                    "rowDimensions":[],"rowAttributes":[],"fieldRelations":[],"qualityIssues":[]}],
                  "qualityIssues":[]
                }
                """);
        var context = objectMapper.readTree("""
                {"sheets":[{"id":"s1","name":"光引发剂","semanticCells":[
                  {"sheetId":"s1","address":"A4","mergedRange":"A4:B4","value":"引发剂"},
                  {"sheetId":"s1","address":"A5","mergedRange":"A5:A6","value":"黄变测试"},
                  {"sheetId":"s1","address":"B5","value":"耐油笔"},
                  {"sheetId":"s1","address":"B6","value":"耐芥末酱△E"},
                  {"sheetId":"s1","address":"A7","value":"初始附着力"},
                  {"sheetId":"s1","address":"B7","value":"附着力"},
                  {"sheetId":"s1","address":"C7","factType":"FORMULA","formula":"=AVERAGE(C5:C6)"}
                ],"rowProfiles":[{"row":7,"formulaCells":6}]}],
                "semanticRegions":[{"regionId":"r1","sheetId":"s1","range":"A4:H19",
                  "type":"COLUMN_TABLE","candidateRef":"model-r1",
                  "structure":{"headerRange":"A4:H6","dataRange":"A7:H19","recordAxis":"COLUMN"}}]}
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compileRegionBatch(normalized, context);
        var table = compiled.suggestions().stream()
                .filter(item -> "COLUMN_TABLE".equals(item.suggestionType())).findFirst().orElseThrow();

        assertThat(table.payload().path("recordProjection").path("recordColumns"))
                .extracting(JsonNode::asText).containsExactly("C", "D", "E", "F", "G", "H");
        assertThat(table.payload().path("columns")).extracting(column -> column.path("name").asText())
                .containsExactly("黄变测试 / 耐油笔", "黄变测试 / 耐芥末酱△E", "初始附着力 / 附着力");
        assertThat(table.payload().path("columns").get(2).path("editability").asText())
                .isEqualTo("READ_ONLY");
        assertThat(table.payload().path("columns").get(2).path("valueSource").asText())
                .isEqualTo("FORMULA");
        assertThat(compiled.suggestions()).filteredOn(item -> "TABLE_CHILD_FIELD".equals(item.suggestionType()))
                .hasSize(3).allSatisfy(child -> assertThat(child.payload().path("fieldName").asText())
                        .doesNotStartWith("引发剂"));
    }

    @Test
    void keepsDistinctHierarchicalNamesWhenSanitizedDataPathsCollide() throws Exception {
        var normalized = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":2,
                  "regions":[{"regionId":"r1","businessName":"耐黄变测试",
                    "rowDimensions":[],"rowAttributes":[
                      {"name":"65℃，90%湿度300h / 板②","sourceRange":"B12","role":"ROW_ATTRIBUTE"},
                      {"name":"65℃，90%湿度300h / 板③","sourceRange":"B13","role":"ROW_ATTRIBUTE"}
                    ],"fieldRelations":[],"qualityIssues":[]}],
                  "qualityIssues":[]
                }
                """);
        var context = objectMapper.readTree("""
                {"semanticRegions":[{"regionId":"r1","sheetId":"s1","range":"A4:H19",
                  "type":"COLUMN_TABLE","candidateRef":"model-r1",
                  "structure":{"headerRange":"A4:H4","dataRange":"A5:H19","recordAxis":"COLUMN",
                    "recordProjection":{"mode":"COLUMN_RECORDS","recordAxis":"COLUMN",
                      "recordColumns":["C","D","E","F","G","H"]}}}]}
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compileRegionBatch(normalized, context);
        var table = compiled.suggestions().stream()
                .filter(item -> "COLUMN_TABLE".equals(item.suggestionType())).findFirst().orElseThrow();

        assertThat(table.payload().path("columns")).extracting(column -> column.path("name").asText())
                .containsExactly("65℃，90%湿度300h / 板②", "65℃，90%湿度300h / 板③");
    }

    @Test
    void compilesCompleteProductionOrderFieldsAndVariableHeightStepRecords() throws Exception {
        var normalized = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {"recognitionProtocolVersion":2,"regions":[
                  {"regionId":"top","businessName":"基本信息","rowDimensions":[],"rowAttributes":[],"fieldRelations":[],"qualityIssues":[]},
                  {"regionId":"ingredients","businessName":"配方明细","rowDimensions":[],"rowAttributes":[],"fieldRelations":[],"qualityIssues":[]},
                  {"regionId":"steps","businessName":"操作步骤","rowDimensions":[],"rowAttributes":[],"fieldRelations":[],"qualityIssues":[]},
                  {"regionId":"bottom","businessName":"包装与签字","rowDimensions":[],"rowAttributes":[],"fieldRelations":[],"qualityIssues":[]}
                ],"qualityIssues":[]}
                """);
        var context = objectMapper.readTree("""
                {"sheets":[{"id":"s1","name":"生产任务单","semanticCells":[
                  {"address":"A1","value":"公司","mergedRange":"A1:J1"},
                  {"address":"A2","value":"任务单","mergedRange":"A2:J2"},
                  {"address":"H3","value":"表单编号:CODE-001","mergedRange":"H3:J3"},
                  {"address":"A4","value":"类   别"},{"address":"D4","value":"品名"},{"address":"H4","value":"订单号"},
                  {"address":"A5","value":"反应釜"},{"address":"D5","value":"包装批号"},{"address":"H5","value":"制造日期"},
                  {"address":"A6","value":"序号"},{"address":"B6","value":"原料编号"},
                  {"address":"C6","value":"配方比例(KG)"},{"address":"D6","value":"实际投料量(KG)"},
                  {"address":"E6","value":"批号","mergedRange":"E6:G6"},{"address":"H6","value":"操作步骤","mergedRange":"H6:J6"},
                  {"address":"A7","value":1},{"address":"A8","value":2},
                  {"address":"A23","value":"包装物料：","mergedRange":"A23:E23"},
                  {"address":"F23","value":"包装规格：","mergedRange":"F23:J23"},
                  {"address":"A24","value":"实际产量：","mergedRange":"A24:E24"},
                  {"address":"F24","value":"包装数量：","mergedRange":"F24:J24"},
                  {"address":"A25","value":"注：原料投料偏差不能大于1%,补损溶剂除外."},
                  {"address":"A27","value":"制单人："},{"address":"D27","value":"完成人："},{"address":"I27","value":"监管人："}
                ],"candidateCells":[
                  {"address":"B4","row":4,"column":2,"empty":true,"hasBorder":true,"mergedRange":"B4:C4"},
                  {"address":"E4","row":4,"column":5,"empty":true,"hasBorder":true,"mergedRange":"E4:G4"},
                  {"address":"I4","row":4,"column":9,"empty":true,"hasBorder":true,"mergedRange":"I4:J4"},
                  {"address":"B5","row":5,"column":2,"empty":true,"hasBorder":true},
                  {"address":"C5","row":5,"column":3,"empty":true,"hasBorder":true},
                  {"address":"E5","row":5,"column":5,"empty":true,"hasBorder":true,"mergedRange":"E5:G5"},
                  {"address":"I5","row":5,"column":9,"empty":true,"hasBorder":true,"mergedRange":"I5:J5"},
                  {"address":"C27","row":27,"column":3,"empty":true,"hasBorder":false}
                ]}],
                "semanticRegions":[
                  {"regionId":"top","sheetId":"s1","range":"A1:J5","type":"FORM_REGION","candidateRef":"top-c","canonicalStatus":"CONFIRMED","structure":{"recordAxis":"UNKNOWN"}},
                  {"regionId":"ingredients","sheetId":"s1","range":"A6:G22","type":"ROW_TABLE","candidateRef":"ingredients-c","canonicalStatus":"CONFIRMED","structure":{"headerRange":"A6:G6","dataRange":"A7:G21","totalRange":"A22:G22","recordAxis":"ROW"}},
                  {"regionId":"steps","sheetId":"s1","range":"H6:J15","type":"ROW_TABLE","candidateRef":"steps-c","canonicalStatus":"CONFIRMED","structure":{"headerRange":"H6:J6","dataRange":"H7:J15","recordAxis":"ROW","recordSlots":[
                    {"slotId":"record-1","recordKey":"record-1","order":1,"range":"H7:J8","identityAddress":"H7"},
                    {"slotId":"record-2","recordKey":"record-2","order":2,"range":"H9:J11","identityAddress":"H9"},
                    {"slotId":"record-3","recordKey":"record-3","order":3,"range":"H12:J13","identityAddress":"H12"},
                    {"slotId":"record-4","recordKey":"record-4","order":4,"range":"H14:J15","identityAddress":"H14"}
                  ]}},
                  {"regionId":"bottom","sheetId":"s1","range":"A23:J28","type":"FORM_REGION","candidateRef":"bottom-c","canonicalStatus":"CONFIRMED","structure":{"recordAxis":"UNKNOWN"}}
                ]}
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compileRegionBatch(normalized, context);
        var businessFields = compiled.suggestions().stream()
                .filter(item -> Set.of("SCALAR_FIELD", "TABLE_CHILD_FIELD").contains(item.suggestionType()))
                .toList();

        assertThat(businessFields).hasSize(20);
        assertThat(businessFields).extracting(item -> item.payload().path("fieldName").asText())
                .contains("表单编号", "类别", "品名", "订单号", "反应釜", "包装批号", "制造日期",
                        "序号", "原料编号", "配方比例", "实际投料量", "批号", "操作步骤",
                        "包装物料", "包装规格", "实际产量", "包装数量", "制单人", "完成人", "监管人");
        var ingredients = compiled.suggestions().stream()
                .filter(item -> "TABLE_CHILD_FIELD".equals(item.suggestionType()))
                .filter(item -> "ingredients-c".equals(item.payload().path("candidateRef").asText())).toList();
        assertThat(ingredients).hasSize(5);
        assertThat(ingredients).allSatisfy(field -> {
            assertThat(field.payload().path("locator").path("labelRange").asText()).contains("6");
            assertThat(field.payload().path("locator").path("address").asText()).doesNotContain("22");
        });
        var steps = compiled.suggestions().stream()
                .filter(item -> "ROW_TABLE".equals(item.suggestionType()))
                .filter(item -> "steps-c".equals(item.payload().path("candidateRef").asText()))
                .findFirst().orElseThrow().payload();
        assertThat(steps.path("recordSlots")).hasSize(4);
        assertThat(steps.path("longTableModel").path("records")).hasSize(4);
        assertThat(steps.path("longTableModel").path("records"))
                .extracting(record -> record.path("recordKey").asText())
                .containsExactly("record-1", "record-2", "record-3", "record-4");
        assertThat(businessFields).noneSatisfy(field ->
                assertThat(field.payload().path("fieldName").asText()).contains("原料投料偏差"));
        assertThat(businessFields).filteredOn(field ->
                        "bottom-c".equals(field.payload().path("candidateRef").asText()))
                .allSatisfy(field -> assertThat(field.payload().path("regionStaticContents"))
                        .anySatisfy(content -> {
                            assertThat(content.path("address").asText()).isEqualTo("A25");
                            assertThat(content.path("role").asText()).isEqualTo("NOTE");
                        }));
    }
}
