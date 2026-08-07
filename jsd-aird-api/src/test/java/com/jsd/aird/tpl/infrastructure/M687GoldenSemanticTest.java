package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class M687GoldenSemanticTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validatesAndCompilesTheExpectedM687BusinessRanges() throws Exception {
        // Keep the semantic golden case self-contained. Parser fidelity has its own XLSX tests;
        // this test must not depend on a developer's Downloads/Desktop directory.
        var facts = objectMapper.readTree("""
                {"structureVersion":6,"sheets":[
                  {"id":"sheet-1","name":"M-687 NT","usedRange":"A1:L28"},
                  {"id":"sheet-2","name":"辅助配方","usedRange":"A1:E6"}
                ]}
                """);
        var response = objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":1,
                  "semanticAnnotations":[
                    {"sheetId":"sheet-1","range":"A1:L2","role":"DOCUMENT_TITLE","temporaryBlockRef":"b1"},
                    {"sheetId":"sheet-1","range":"J4:L4","role":"INLINE_METADATA","temporaryBlockRef":"b1"},
                    {"sheetId":"sheet-1","range":"A5","role":"FIELD_LABEL","temporaryRelationRef":"r1","temporaryBlockRef":"b2"},
                    {"sheetId":"sheet-1","range":"B5:D5","role":"FIELD_VALUE","temporaryRelationRef":"r1","temporaryBlockRef":"b2"},
                    {"sheetId":"sheet-1","range":"A7:I8","role":"TABLE_HEADER","temporaryTableRef":"t1","temporaryBlockRef":"b3"},
                    {"sheetId":"sheet-1","range":"A9:I21","role":"TABLE_DATA","temporaryTableRef":"t1","temporaryBlockRef":"b3"},
                    {"sheetId":"sheet-1","range":"A22:I22","role":"TABLE_TOTAL","temporaryTableRef":"t1","temporaryBlockRef":"b3"},
                    {"sheetId":"sheet-1","range":"J7:L17","role":"INSTRUCTION","temporaryBlockRef":"b4"},
                    {"sheetId":"sheet-1","range":"J18:L22","role":"CONFIRMATION","temporaryBlockRef":"b5"},
                    {"sheetId":"sheet-1","range":"A23:L25","role":"NOTE","temporaryBlockRef":"b8"},
                    {"sheetId":"sheet-1","range":"A27:L28","role":"NOTE","temporaryBlockRef":"b9"},
                    {"sheetId":"sheet-2","range":"D4:E6","role":"TABLE_DATA","temporaryTableRef":"t2","temporaryBlockRef":"b10"}
                  ],
                  "businessBlocks":[
                    {"temporaryId":"b1","sheetId":"sheet-1","range":"A1:L4","type":"DOCUMENT_HEADER","businessName":"任务单标题与单据元数据"},
                    {"temporaryId":"b2","sheetId":"sheet-1","range":"A5:L6","type":"FORM_FIELDS","businessName":"基础信息","groupNameSuggestion":"基础信息"},
                    {"temporaryId":"b3","sheetId":"sheet-1","range":"A7:I22","type":"ROW_TABLE","businessName":"配方明细","groupNameSuggestion":"配方明细"},
                    {"temporaryId":"b4","sheetId":"sheet-1","range":"J7:L17","type":"INSTRUCTION_LIST","businessName":"生产操作流程"},
                    {"temporaryId":"b5","sheetId":"sheet-1","range":"J18:L22","type":"CONFIRMATION_BLOCK","businessName":"生产确认"},
                    {"temporaryId":"b6","sheetId":"sheet-1","range":"J18:L20","type":"FREE_TEXT","parentTemporaryId":"b5","businessName":"确认签字"},
                    {"temporaryId":"b7","sheetId":"sheet-1","range":"J21:L22","type":"NOTE_BLOCK","parentTemporaryId":"b5","businessName":"确认提醒"},
                    {"temporaryId":"b8","sheetId":"sheet-1","range":"A23:L25","type":"FORM_FIELDS","businessName":"包装信息","groupNameSuggestion":"包装信息"},
                    {"temporaryId":"b9","sheetId":"sheet-1","range":"A27:L28","type":"FREE_TEXT","businessName":"人员签字"},
                    {"temporaryId":"b10","sheetId":"sheet-2","range":"D3:E6","type":"ROW_TABLE","businessName":"预混配方明细","groupNameSuggestion":"配方明细"}
                  ],
                  "fieldRelations":[
                    {"temporaryId":"r1","sheetId":"sheet-1","labelRange":"A5","valueRange":"B5:D5","relationType":"LABEL_VALUE","businessName":"产品名称","blockTemporaryId":"b2","groupNameSuggestion":"productName","valueType":"string","required":true,"editability":"EDITABLE","valueSource":"USER_INPUT"}
                  ],
                  "tables":[
                    {"temporaryId":"t1","sheetId":"sheet-1","range":"A7:I22","tableKind":"ROW_TABLE","businessName":"配方明细","blockTemporaryId":"b3","groupNameSuggestion":"配方明细","headerRange":"A7:I8","dataRange":"A9:I21","totalRange":"A22:I22","semanticMode":"ROW_RECORDS","rowHeaderRange":"","columnHeaderRange":"","crossDataRange":"","headerTree":[],"columns":[
                      {"temporaryId":"c1","name":"序号","labelRange":"A7:A8","valueRange":"A9:A21","valueType":"integer","editability":"READ_ONLY","valueSource":"FORMULA"},
                      {"temporaryId":"c2","name":"原料编号","labelRange":"B7:B8","valueRange":"B9:B21","valueType":"string","editability":"EDITABLE","valueSource":"USER_INPUT"},
                      {"temporaryId":"c3","name":"原料名称","labelRange":"C7:C8","valueRange":"C9:C21","valueType":"string","editability":"EDITABLE","valueSource":"USER_INPUT"},
                      {"temporaryId":"c4","name":"批次","labelRange":"D7:D8","valueRange":"D9:D21","valueType":"string","editability":"EDITABLE","valueSource":"USER_INPUT"},
                      {"temporaryId":"c5","name":"配方比例","labelRange":"E7:E8","valueRange":"E9:E21","valueType":"number","editability":"EDITABLE","valueSource":"USER_INPUT"},
                      {"temporaryId":"c6","name":"计划用量","labelRange":"F7:F8","valueRange":"F9:F21","valueType":"number","editability":"READ_ONLY","valueSource":"FORMULA"},
                      {"temporaryId":"c7","name":"实际用量","labelRange":"G7:G8","valueRange":"G9:G21","valueType":"number","editability":"EDITABLE","valueSource":"USER_INPUT"},
                      {"temporaryId":"c8","name":"复核","labelRange":"H7:H8","valueRange":"H9:H21","valueType":"string","editability":"EDITABLE","valueSource":"USER_INPUT"},
                      {"temporaryId":"c9","name":"差异","labelRange":"I7:I8","valueRange":"I9:I21","valueType":"number","editability":"READ_ONLY","valueSource":"FORMULA"}
                    ]},
                    {"temporaryId":"t2","sheetId":"sheet-2","range":"D3:E6","tableKind":"ROW_TABLE","businessName":"预混配方明细","blockTemporaryId":"b10","groupNameSuggestion":"配方明细","headerRange":"D3:E3","dataRange":"D4:E6","semanticMode":"ROW_RECORDS","rowHeaderRange":"","columnHeaderRange":"","crossDataRange":"","headerTree":[],"columns":[
                      {"temporaryId":"p1","name":"原料","labelRange":"D3","valueRange":"D4:D6","valueType":"string","editability":"EDITABLE","valueSource":"USER_INPUT"},
                      {"temporaryId":"p2","name":"配方比例","labelRange":"E3","valueRange":"E4:E6","valueType":"number","editability":"EDITABLE","valueSource":"USER_INPUT"}
                    ]}
                  ],
                  "qualityIssues":[
                    {"temporaryId":"q1","sheetId":"sheet-1","range":"A18:I21","category":"TABLE_STRUCTURE_UNCLEAR","severity":"WARNING","title":"一组配方比例含义需核对","description":"第二组配方比例缺少足够业务说明。","businessImpact":"确认后再决定是否纳入主配方数据。","rootBlockTemporaryId":"b3"}
                  ]
                }
                """);

        var validated = new GlobalSemanticRecognitionProtocol(objectMapper).validate(response, facts);
        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compile(validated, facts);

        // 语义模型 + 2 个父级重复区域 + 每个重复区域的独立子字段 Mapping。
        assertThat(compiled.suggestions()).hasSize(15);
        assertThat(compiled.qualityIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.title()).isEqualTo("一组配方比例含义需核对");
            assertThat(issue.regionId()).startsWith("blk-");
        });
        var semantic = compiled.suggestions().getFirst().payload();
        assertThat(semantic.path("businessBlocks")).hasSize(10);
        assertThat(semantic.path("semanticAnnotations")).extracting(node -> node.path("range").asText())
                .contains("A1:L2", "J4:L4", "A7:I8", "A9:I21", "A22:I22", "J7:L17",
                        "J18:L22", "A23:L25", "A27:L28", "D4:E6");
        assertThat(semantic.path("businessBlocks")).anySatisfy(block ->
                assertThat(block.path("range").asText()).isEqualTo("D3:E6"));
        assertThat(compiled.suggestions().stream().skip(1)
                .map(suggestion -> suggestion.payload().path("fieldName").asText()))
                .doesNotContainAnyElementsOf(Set.of(
                        "UV树脂", "M-687 NT", "UA-306", "USP-130", "USP-039", "USP-096"
                ));
        assertThat(compiled.suggestions().stream().skip(1)
                .map(suggestion -> suggestion.payload().path("groupName").asText()))
                .contains("基础信息", "配方明细").doesNotContain("formulaDetailTable", "category");
        var product = compiled.suggestions().stream()
                .filter(item -> "产品名称".equals(item.payload().path("fieldName").asText()))
                .findFirst().orElseThrow().payload();
        assertThat(product.path("groupName").asText()).isEqualTo("基础信息");
        assertThat(product.path("fieldCode").asText()).isEqualTo("PRODUCTION.PRODUCT_NAME");
        assertThat(product.path("dataPath").asText()).isEqualTo("/recognized/basicInformation/productName");
        var mainTable = compiled.suggestions().stream()
                .filter(item -> "配方明细".equals(item.payload().path("fieldName").asText()))
                .findFirst().orElseThrow().payload();
        assertThat(mainTable.path("columns")).anySatisfy(column -> {
            assertThat(column.path("valueRange").asText()).isEqualTo("I9:I21");
            assertThat(column.path("editability").asText()).isEqualTo("READ_ONLY");
            assertThat(column.path("valueSource").asText()).isEqualTo("FORMULA");
        });
        var materialCodeColumn = java.util.stream.StreamSupport.stream(
                mainTable.path("columns").spliterator(), false)
                .filter(column -> "原料编号".equals(column.path("name").asText()))
                .findFirst().orElseThrow();
        assertThat(materialCodeColumn.path("fieldCode").asText())
                .isEqualTo("FORMULA.ITEM.MATERIAL_CODE");
        assertThat(compiled.suggestions().stream().skip(1)
                .map(suggestion -> suggestion.payload().path("dataPath").asText())
                .filter(path -> !path.isBlank()).toList()).doesNotHaveDuplicates();
    }

    @Test
    void keepsRepeatedStandardLabelsAsIndependentPhysicalFields() throws Exception {
        var facts = objectMapper.readTree("""
                {"structureVersion":6,"sheets":[{"id":"sheet-1","name":"检测","usedRange":"A1:F4"}]}
                """);
        var response = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":1,
                  "semanticAnnotations":[],
                  "businessBlocks":[{"temporaryId":"b1","sheetId":"sheet-1","range":"A1:F4","type":"FORM_FIELDS","businessName":"检测信息"}],
                  "fieldRelations":[
                    {"temporaryId":"r1","sheetId":"sheet-1","labelRange":"A1","valueRange":"B1:C1","relationType":"LABEL_VALUE","businessName":"实际产量","blockTemporaryId":"b1","valueType":"number","editability":"EDITABLE","valueSource":"USER_INPUT"},
                    {"temporaryId":"r2","sheetId":"sheet-1","labelRange":"A3","valueRange":"B3:C3","relationType":"LABEL_VALUE","businessName":"实际产量","blockTemporaryId":"b1","valueType":"number","editability":"EDITABLE","valueSource":"USER_INPUT"}
                  ],
                  "tables":[],"qualityIssues":[]
                }
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compile(response, facts);
        var paths = compiled.suggestions().stream().skip(1)
                .map(suggestion -> suggestion.payload().path("dataPath").asText())
                .toList();
        assertThat(paths).hasSize(2).doesNotHaveDuplicates();
        assertThat(paths.get(0)).isEqualTo("/recognized/basicInformation/actualOutput");
        assertThat(paths.get(1)).startsWith("/recognized/basicInformation/actualOutput__");
    }

    @Test
    void keepsSimilarAppearanceLabelsMappedToTheirOwnStandardCodes() throws Exception {
        var facts = objectMapper.readTree(
                "{\"structureVersion\":6,\"sheets\":[{\"id\":\"sheet-1\",\"name\":\"检测\",\"usedRange\":\"A1:C4\"}]}"
        );
        var response = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {
                  "recognitionProtocolVersion":1,
                  "semanticAnnotations":[],
                  "businessBlocks":[{"temporaryId":"b1","sheetId":"sheet-1","range":"A1:C4","type":"ROW_TABLE","businessName":"外观检测"}],
                  "fieldRelations":[],
                  "tables":[{"temporaryId":"t1","sheetId":"sheet-1","range":"A1:C4","tableKind":"ROW_TABLE","businessName":"外观检测","blockTemporaryId":"b1","headerRange":"A1:C1","dataRange":"A2:C4","columns":[
                    {"temporaryId":"c1","name":"涂料外观","labelRange":"B1","valueRange":"B2:B4","valueType":"string","editability":"EDITABLE","valueSource":"USER_INPUT"},
                    {"temporaryId":"c2","name":"漆膜外观","labelRange":"C1","valueRange":"C2:C4","valueType":"string","editability":"EDITABLE","valueSource":"USER_INPUT"}
                  ]}],
                  "qualityIssues":[]
                }
                """);

        var compiled = new GlobalSemanticSuggestionCompiler(objectMapper).compile(response, facts);
        var table = compiled.suggestions().stream()
                .filter(item -> "ROW_TABLE".equals(item.payload().path("kind").asText()))
                .findFirst().orElseThrow().payload();
        var columns = java.util.stream.StreamSupport.stream(table.path("columns").spliterator(), false).toList();
        assertThat(columns).extracting(column -> column.path("fieldCode").asText())
                .containsExactly("COATING.PROPERTY.APPEARANCE", "FILM.PROPERTY.APPEARANCE");
        assertThat(columns).extracting(column -> column.path("dataPath").asText()).doesNotHaveDuplicates();
    }
}
