package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class M687GoldenSemanticTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validatesAndCompilesTheExpectedM687BusinessRanges() throws Exception {
        var source = Path.of("../chatgpt解析的标准模板/M-687_NT_标准化业务语义模板.xlsx")
                .toAbsolutePath().normalize();
        assertThat(source).exists();
        var parser = new XlsxStructureParser(objectMapper);
        var facts = parser.parse(new ByteArrayInputStream(Files.readAllBytes(source))).structureSummary();
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
                    {"sheetId":"sheet-1","range":"A27:L28","role":"SIGNATURE","temporaryBlockRef":"b9"},
                    {"sheetId":"sheet-2","range":"D4:E6","role":"TABLE_DATA","temporaryTableRef":"t2","temporaryBlockRef":"b10"}
                  ],
                  "businessBlocks":[
                    {"temporaryId":"b1","sheetId":"sheet-1","range":"A1:L4","type":"DOCUMENT_HEADER","businessName":"任务单标题与单据元数据"},
                    {"temporaryId":"b2","sheetId":"sheet-1","range":"A5:L6","type":"FORM_FIELDS","businessName":"基础信息","groupNameSuggestion":"基础信息"},
                    {"temporaryId":"b3","sheetId":"sheet-1","range":"A7:I22","type":"ROW_TABLE","businessName":"配方明细","groupNameSuggestion":"配方明细"},
                    {"temporaryId":"b4","sheetId":"sheet-1","range":"J7:L17","type":"INSTRUCTION_LIST","businessName":"生产操作流程"},
                    {"temporaryId":"b5","sheetId":"sheet-1","range":"J18:L22","type":"CONFIRMATION_BLOCK","businessName":"生产确认"},
                    {"temporaryId":"b6","sheetId":"sheet-1","range":"J18:L20","type":"SIGNATURE_BLOCK","parentTemporaryId":"b5","businessName":"确认签字"},
                    {"temporaryId":"b7","sheetId":"sheet-1","range":"J21:L22","type":"NOTE_BLOCK","parentTemporaryId":"b5","businessName":"确认提醒"},
                    {"temporaryId":"b8","sheetId":"sheet-1","range":"A23:L25","type":"FORM_FIELDS","businessName":"包装信息","groupNameSuggestion":"包装信息"},
                    {"temporaryId":"b9","sheetId":"sheet-1","range":"A27:L28","type":"SIGNATURE_BLOCK","businessName":"人员签字"},
                    {"temporaryId":"b10","sheetId":"sheet-2","range":"D3:E6","type":"ROW_TABLE","businessName":"预混配方明细","groupNameSuggestion":"配方明细"}
                  ],
                  "fieldRelations":[
                    {"temporaryId":"r1","sheetId":"sheet-1","labelRange":"A5","valueRange":"B5:D5","relationType":"LABEL_VALUE","businessName":"产品名称","blockTemporaryId":"b2","groupNameSuggestion":"基础信息","valueType":"string","required":true,"editability":"EDITABLE","valueSource":"USER_INPUT"}
                  ],
                  "tables":[
                    {"temporaryId":"t1","sheetId":"sheet-1","range":"A7:I22","tableKind":"ROW_TABLE","businessName":"配方明细","blockTemporaryId":"b3","groupNameSuggestion":"配方明细","headerRange":"A7:I8","dataRange":"A9:I21","totalRange":"A22:I22","columns":[
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
                    {"temporaryId":"t2","sheetId":"sheet-2","range":"D3:E6","tableKind":"ROW_TABLE","businessName":"预混配方明细","blockTemporaryId":"b10","groupNameSuggestion":"配方明细","headerRange":"D3:E3","dataRange":"D4:E6","columns":[
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

        assertThat(compiled.suggestions()).hasSize(4);
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
        var mainTable = compiled.suggestions().stream()
                .filter(item -> "配方明细".equals(item.payload().path("fieldName").asText()))
                .findFirst().orElseThrow().payload();
        assertThat(mainTable.path("columns")).anySatisfy(column -> {
            assertThat(column.path("valueRange").asText()).isEqualTo("I9:I21");
            assertThat(column.path("editability").asText()).isEqualTo("READ_ONLY");
            assertThat(column.path("valueSource").asText()).isEqualTo("FORMULA");
        });
    }
}
