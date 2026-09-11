package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import org.junit.jupiter.api.Test;

class AssistantDataDetailTest {

    @Test
    void appendsExactDataValuesAsMarkdownInsteadOfRelyingOnTheModel() {
        var hit = hit("REC-001", "density", "密度", "1.08", "g/cm³", "NUMBER", "Sheet1", "C12");

        var answer = AssistantService.appendDataDetailTable("该记录的密度如下。", List.of(hit));

        assertThat(answer).contains("### 数据明细")
                .contains("| 记录 | 字段 | 值 | 单位 | 来源 |")
                .contains("|REC-001|密度|1.08|g/cm³|检测报告.xlsx / Sheet1!C12|")
                .doesNotContain("density");
    }

    @Test
    void rendersAFileOverviewOnceWithoutInternalIdentifiers() {
        var hit = hit("SYN-UVPU-APP-0031", "TABLE.COLUMN.粘度", "粘度", "208", null,
                "NUMBER", "Sheet1", "G13");
        var result = new DataSourceFileSearchFacade.DataDetailResult(
                DataSourceFileSearchFacade.DataQueryMode.FILE_OVERVIEW, UUID.randomUUID(), UUID.randomUUID(),
                "应用测试报告.xlsx", 14, 296, List.of(hit), false);

        var answer = AssistantService.dataDetailAnswer(result);

        assertThat(answer).contains("14 条来源记录、296 个非空可检索值")
                .contains("| 记录 | 字段 | 值 | 单位 | 位置 |")
                .contains("|SYN-UVPU-APP-0031|粘度|208|—|Sheet1!G13|")
                .doesNotContain("检测报告.xlsx / Sheet1!G13")
                .containsOnlyOnce("### 数据明细")
                .doesNotContain("IMPORT:STRUCT", "TABLE.COLUMN", "bindingId", "valuePath");
    }

    @Test
    void buildsACompactAnalysisPromptFromBusinessDataOnly() {
        var hit = hit("SYN-UVPU-APP-0031", "TABLE.COLUMN.粘度", "粘度", "208", null,
                "NUMBER", "Sheet1", "G13");
        var result = new DataSourceFileSearchFacade.DataDetailResult(
                DataSourceFileSearchFacade.DataQueryMode.FIELD_LOOKUP, UUID.randomUUID(), UUID.randomUUID(),
                "应用测试报告.xlsx", 14, 296, List.of(hit), false);

        var prompt = AssistantService.dataDetailAnalysisPrompt("SJ-230水洗后的粘度是多少？", result);
        var answer = AssistantService.mergeDataDetailAnswer("SJ-230水洗后的粘度为 208。", AssistantService.dataDetailAnswer(result));

        assertThat(prompt).contains("字段=粘度；值=208；单位=—；位置=Sheet1!G13")
                .doesNotContain("TABLE.COLUMN", "IMPORT:STRUCT", "bindingId", "valuePath");
        assertThat(answer).startsWith("### 简要分析\n\nSJ-230水洗后的粘度为 208。")
                .contains("|SYN-UVPU-APP-0031|粘度|208|—|Sheet1!G13|");
    }

    @Test
    void asksForClarificationWhenARequestedDisplayNameHasDifferentSemantics() {
        var massDensity = hit("REC-001", "mass_density", "密度", "1.08", "g/cm³", "NUMBER", "Sheet1", "C12");
        var opticalDensity = hit("REC-002", "optical_density", "密度", "0.42", null, "NUMBER", "Sheet2", "D8");

        var ambiguity = AssistantService.detectFieldAmbiguity("请问密度是什么？", List.of(massDensity, opticalDensity));

        assertThat(ambiguity).isNotNull();
        assertThat(ambiguity.fieldName()).isEqualTo("密度");
        assertThat(ambiguity.candidates()).extracting(AssistantService.FieldCandidate::code)
                .containsExactly("mass_density", "optical_density");
    }

    @Test
    void sameFieldAcrossRecordsIsNotAmbiguous() {
        var first = hit("REC-001", "density", "密度", "1.08", "g/cm³", "NUMBER", "Sheet1", "C12");
        var second = hit("REC-002", "density", "密度", "1.12", "g/cm³", "NUMBER", "Sheet1", "C13");

        assertThat(AssistantService.detectFieldAmbiguity("查询密度", List.of(first, second))).isNull();
    }

    private DataSourceFileSearchFacade.SourceFileHit hit(String recordKey, String fieldCode, String fieldName,
                                                          String value, String unit, String valueType,
                                                          String sheet, String cell) {
        return new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                12, "C", "检测报告.xlsx", "记录=" + recordKey + "；字段=" + fieldName + "；值=" + value,
                0.9, "DATA_CENTER:" + sheet + ":" + cell, recordKey, fieldCode, fieldName, value, unit,
                valueType, sheet, cell);
    }
}
