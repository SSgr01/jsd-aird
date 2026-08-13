package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.junit.jupiter.api.Test;

class RuleBasedRecognitionEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuleBasedRecognitionEngine engine = new RuleBasedRecognitionEngine(
            objectMapper, new JsonCanonicalizer(objectMapper)
    );

    @Test
    void versionSixDoesNotGuessBusinessFieldsFromKeywordsOrExistingValues() throws Exception {
        var structure = objectMapper.readTree("""
                {
                  "structureVersion":6,
                  "sheets":[{"id":"sheet-1","semanticCells":[
                    {"sheetId":"sheet-1","address":"A1","value":"UV树脂"},
                    {"sheetId":"sheet-1","address":"B1","value":"M-687 NT"},
                    {"sheetId":"sheet-1","address":"A2","value":"原料名称"},
                    {"sheetId":"sheet-1","address":"B2","value":"UA-306"}
                  ]}]
                }
                """);

        var result = engine.recognize(TemplateFormat.XLSX, "生产单.xlsx", structure);

        assertThat(result.suggestions()).isEmpty();
        assertThat(result.model()).isEqualTo("conservative-label-value-v6");
    }

    @Test
    void turnsAHeaderOnlyLongTableIntoARepeatRegionWithoutUsingAnAiModel() throws Exception {
        var structure = objectMapper.readTree("""
                {
                  "structureVersion":6,
                  "sheets":[{"id":"sheet-1","name":"原料","lastRow":1,"semanticCells":[
                    {"sheetId":"sheet-1","sheetName":"原料","address":"A1","row":1,"column":1,"value":"产品名称","factType":"VALUE"},
                    {"sheetId":"sheet-1","sheetName":"原料","address":"B1","row":1,"column":2,"value":"批号","factType":"VALUE"},
                    {"sheetId":"sheet-1","sheetName":"原料","address":"C1","row":1,"column":3,"value":"固含率","factType":"VALUE"}
                  ],"mergedRanges":[],"formulaCount":0}]
                }
                """);

        var result = engine.recognize(TemplateFormat.XLSX, "原料数据模板.xlsx", structure);

        assertThat(engine.isSimpleLongTableWorkbook(structure)).isTrue();
        assertThat(result.suggestions()).hasSize(4);
        var roots = result.suggestions().stream()
                .filter(suggestion -> "TABLE_REGION".equals(suggestion.suggestionType()))
                .toList();
        assertThat(roots).hasSize(1);
        var root = roots.getFirst();
        assertThat(root).satisfies(suggestion -> {
            assertThat(suggestion.suggestionType()).isEqualTo("TABLE_REGION");
            assertThat(suggestion.payload().path("autoAccept").asBoolean()).isTrue();
            assertThat(suggestion.payload().path("canonicalStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(suggestion.payload().path("repeatAxis").asText()).isEqualTo("ROW");
            assertThat(suggestion.payload().path("locator").path("headerRange").asText()).isEqualTo("A1:C1");
            assertThat(suggestion.payload().path("locator").path("dataRange").asText()).isEqualTo("A2:C200");
            assertThat(suggestion.payload().path("locator").path("logicalInputRange").asText())
                    .isEqualTo("A1:C200");
            assertThat(suggestion.payload().path("columns").get(0).path("valueRange").asText())
                    .isEqualTo("A2:A200");
            assertThat(suggestion.payload().path("columns").get(1).path("valueRange").asText())
                    .isEqualTo("B2:B200");
        });
        assertThat(result.suggestions()).filteredOn(suggestion ->
                        "TABLE_CHILD_FIELD".equals(suggestion.suggestionType()))
                .allSatisfy(suggestion -> {
                    assertThat(suggestion.payload().path("suggestionLevel").asText()).isEqualTo("CHILD");
                    assertThat(suggestion.payload().path("reviewRequired").asBoolean()).isTrue();
                    assertThat(suggestion.payload().path("locator").path("valueMode").asText())
                            .isEqualTo("ARRAY_COLUMN");
                });
    }

    @Test
    void createsOnlyALowConfidenceCandidateForAnExplicitColonLabelAndAdjacentValue() throws Exception {
        var structure = objectMapper.readTree("""
                {
                  "structureVersion":6,
                  "sheets":[{"id":"sheet-1","semanticCells":[
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"A2","row":2,"column":1,"value":"产品名称：","factType":"VALUE"},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"B2","row":2,"column":2,"value":"M-687 NT","factType":"VALUE"},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"A3","row":3,"column":1,"value":"UV树脂","factType":"VALUE"},
                    {"sheetId":"sheet-1","sheetName":"生产单","address":"B3","row":3,"column":2,"value":"UA-306","factType":"VALUE"}
                  ]}]
                }
                """);

        var result = engine.recognize(TemplateFormat.XLSX, "生产单.xlsx", structure);

        assertThat(result.suggestions()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.confidence()).isEqualTo(0.58);
            assertThat(suggestion.payload().path("fieldName").asText()).isEqualTo("产品名称");
            assertThat(suggestion.payload().path("locator").path("address").asText()).isEqualTo("B2");
        });
    }

    @Test
    void doesNotBindAnExplicitLabelToTheNextNonWritableLabelRow() throws Exception {
        var structure = objectMapper.readTree("""
                {"structureVersion":6,"sheets":[{"id":"sheet-1","semanticCells":[
                  {"sheetId":"sheet-1","address":"A2","row":2,"column":1,"value":"实验目的：","factType":"VALUE","mergedRange":"A2:N2"},
                  {"sheetId":"sheet-1","address":"A3","row":3,"column":1,"value":"实验人员","factType":"VALUE","mergedRange":"A3:C3"}
                ]}]}
                """);

        var result = engine.recognize(TemplateFormat.XLSX, "综合测试报告.xlsx", structure);

        assertThat(result.suggestions()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.payload().path("fieldName").asText()).isEqualTo("实验目的");
            assertThat(suggestion.payload().path("locator").path("logicalInputRange").asText())
                    .isEqualTo("A2:N2");
        });
    }

    @Test
    void doesNotTurnAPrintedInlineFormNumberIntoAnEditableField() throws Exception {
        var structure = objectMapper.readTree("""
                {"structureVersion":6,"sheets":[{"id":"sheet-1","semanticCells":[
                  {"sheetId":"sheet-1","address":"H3","row":3,"column":8,
                   "value":"表单编号:JSD-QF-SC-001","factType":"VALUE","mergedRange":"H3:J3"}
                ]}]}
                """);

        var result = engine.recognize(TemplateFormat.XLSX, "生产任务单.xlsx", structure);

        assertThat(result.suggestions()).isEmpty();
    }

    @Test
    void rejectsLegacyPhysicalStructures() throws Exception {
        var legacy = objectMapper.readTree("{\"structureVersion\":5}");
        assertThatThrownBy(() -> engine.recognize(TemplateFormat.XLSX, "旧模板.xlsx", legacy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须为 6");
    }

    @Test
    void doesNotTurnDocxContentControlsIntoBusinessFieldSuggestions() throws Exception {
        var structure = objectMapper.readTree("""
                {"documentIR":{"contentControls":[
                  {"nodeId":"content-control-1","contentControlId":"17","markerId":"marker-order-no","alias":"订单号","tag":"FIELD.ORDER_NO","text":""}
                ],"blocks":[{"id":"paragraph-1","type":"PARAGRAPH","text":"说明文字"}]}}
                """);
        var result = engine.recognize(TemplateFormat.DOCX, "模板.docx", structure);
        assertThat(result.suggestions()).isEmpty();
    }

    @Test
    void keepsDocxControlsAsDocumentFactsInsteadOfMapping() throws Exception {
        var structure = objectMapper.readTree("""
                {"documentIR":{"contentControls":[
                  {"nodeId":"content-control-1","contentControlId":"17","alias":"订单号","tag":"FIELD.ORDER_NO","text":""}
                ]}}
                """);
        var result = engine.recognize(TemplateFormat.DOCX, "模板.docx", structure);
        assertThat(result.suggestions()).isEmpty();
    }

    @Test
    void doesNotTreatNumberedDocxHeadingsAsFieldCandidates() throws Exception {
        var structure = objectMapper.readTree("""
                {"documentIR":{"blocks":[
                  {"id":"paragraph-1","type":"PARAGRAPH","text":"1. 客户名称"},
                  {"id":"paragraph-2","type":"PARAGRAPH","text":"2. 联系电话"},
                  {"id":"paragraph-3","type":"PARAGRAPH","text":"说明文字"}
                ]}}
                """);
        var result = engine.recognize(TemplateFormat.DOCX, "模板.docx", structure);
        assertThat(result.suggestions()).isEmpty();
    }
}
