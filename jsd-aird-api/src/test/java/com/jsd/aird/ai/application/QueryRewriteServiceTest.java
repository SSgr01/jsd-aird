package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class QueryRewriteServiceTest {

    @Test
    void acceptsACompletePlannerResult() {
        var plan = new QueryRewriteService.QueryPlan("原问题", "检索问题",
                List.of("子问题"), List.of(new QueryRewriteService.RetrievalTerm(
                        "dynamic term", List.of("动态术语"), "PHRASE")),
                List.of(new QueryRewriteService.RequiredFact("所需字段", "字段检索问题")),
                Map.of(), "不限", List.of(new QueryRewriteService.WebQuery(
                        "公开资料检索", "GENERAL", "ALL")));

        assertThat(QueryRewriteService.isUsable(plan)).isTrue();
    }

    @Test
    void rejectsMissingRewriteQueryOrOversizedLists() {
        assertThat(QueryRewriteService.isUsable(new QueryRewriteService.QueryPlan(
                "原问题", "", List.of(), List.of(), List.of(), Map.of(), "不限", List.of()))).isFalse();
        assertThat(QueryRewriteService.isUsable(new QueryRewriteService.QueryPlan(
                "原问题", "检索问题", List.of("1", "2", "3", "4", "5", "6", "7"),
                List.of(), List.of(), Map.of(), "不限", List.of()))).isFalse();
        assertThat(QueryRewriteService.isUsable(new QueryRewriteService.QueryPlan(
                "原问题", "检索问题", List.of(),
                java.util.stream.IntStream.range(0, 25).mapToObj(index ->
                        new QueryRewriteService.RetrievalTerm("term-" + index, List.of(), "PHRASE")).toList(),
                List.of(), Map.of(), "不限", List.of()))).isFalse();

        assertThat(QueryRewriteService.isUsable(new QueryRewriteService.QueryPlan(
                "原问题", "检索问题", List.of(), List.of(), List.of(), Map.of(), "不限",
                List.of(new QueryRewriteService.WebQuery("x".repeat(301), "GENERAL", "ALL"))))).isFalse();
    }

    @Test
    void reportsEveryInvalidPlannerFieldWithItsPathRuleAndBoundedValue() {
        var invalid = new QueryRewriteService.QueryPlan("原问题", "x".repeat(1_001),
                List.of("1", "2", "3", "4", "5", "6", "7"),
                List.of(new QueryRewriteService.RetrievalTerm("", List.of("1", "2", "3", "4", "5", "6", "7"), "PHRASE")),
                List.of(new QueryRewriteService.RequiredFact("", "q".repeat(1_001))),
                Map.of(), "不限", List.of(new QueryRewriteService.WebQuery("", "GENERAL", "ALL")),
                new QueryRewriteService.DataRequest("BAD_INTENT", "", List.of(), List.of()));

        var issues = QueryRewriteService.validationIssues(invalid);

        assertThat(issues).extracting(QueryRewriteService.ValidationIssue::path).contains(
                "rewrittenQuery", "subQueries", "retrievalTerms[0].text", "retrievalTerms[0].aliases",
                "requiredFacts[0].label", "requiredFacts[0].retrievalQuery", "dataRequest.intent",
                "webQueries[0].query");
        assertThat(issues).filteredOn(issue -> issue.path().equals("rewrittenQuery"))
                .singleElement().satisfies(issue -> {
                    assertThat(issue.rule()).isEqualTo("MAX_LENGTH_1000");
                    assertThat(issue.actualValuePreview()).hasSize(161).endsWith("…");
                });
        assertThat(QueryRewriteService.isUsable(invalid)).isFalse();
    }

    @Test
    void acceptsAnExplicitStructuredDataRequest() {
        var plan = new QueryRewriteService.QueryPlan("SJ-230水洗后的粘度", "SJ-230水洗后的粘度",
                List.of(), List.of(), List.of(), Map.of(), "不限", List.of(),
                new QueryRewriteService.DataRequest("FIELD_LOOKUP", "应用测试报告.xlsx",
                        List.of("SJ-230水洗后"), List.of("粘度")));

        assertThat(QueryRewriteService.isUsable(plan)).isTrue();
    }

    @Test
    void keepsTheRecentDataFileForAContextualFollowUp() {
        var request = QueryRewriteService.sanitizeDataRequest(
                new QueryRewriteService.DataRequest("FIELD_LOOKUP",
                        "基于文件《应用测试报告_Synthetic_10_T07B真实页面验收_V1.xlsx》",
                        List.of("TEST-NOT-FOUND-999"), List.of("粘度")),
                "TEST-NOT-FOUND-999 的粘度是多少？\n请概览文件 应用测试报告_Synthetic_10_T07B真实页面验收_V1.xlsx 的数据明细",
                "TEST-NOT-FOUND-999 的粘度是多少？");

        assertThat(request.fileName()).isEqualTo("应用测试报告_Synthetic_10_T07B真实页面验收_V1.xlsx");
    }

    @Test
    void forcesAnObviousDataFollowUpThroughStructuredRetrievalWhenPlannerSaysNone() {
        var request = QueryRewriteService.sanitizeDataRequest(
                new QueryRewriteService.DataRequest("NONE", "", List.of(), List.of()),
                "SJ-230水洗后的粘度是多少？请用自然语言简单分析\n当前数据文件上下文：应用测试报告.xlsx",
                "SJ-230水洗后的粘度是多少？请用自然语言简单分析");

        assertThat(request.intent()).isEqualTo("FIELD_LOOKUP");
        assertThat(request.fileName()).isEqualTo("应用测试报告.xlsx");
        assertThat(request.fileScope()).isEqualTo("CONTEXT");
    }

    @Test
    void allowsAnObviousDataQuestionWithoutAFileName() {
        var request = QueryRewriteService.sanitizeDataRequest(
                new QueryRewriteService.DataRequest("NONE", "", List.of(), List.of()),
                "SJ-230水洗后的粘度是多少？",
                "SJ-230水洗后的粘度是多少？");

        assertThat(request.intent()).isEqualTo("FIELD_LOOKUP");
        assertThat(request.fileName()).isEmpty();
        assertThat(request.fileScope()).isEqualTo("AUTO");
    }

    @Test
    void marksAFileNamedInTheCurrentQuestionAsExplicit() {
        var request = QueryRewriteService.sanitizeDataRequest(
                new QueryRewriteService.DataRequest("FIELD_LOOKUP", "", List.of("SJ-230水洗后"), List.of("粘度")),
                "在数据文件《应用测试报告.xlsx》中查询：SJ-230水洗后的粘度是多少？",
                "在数据文件《应用测试报告.xlsx》中查询：SJ-230水洗后的粘度是多少？");

        assertThat(request.fileName()).isEqualTo("应用测试报告.xlsx");
        assertThat(request.fileScope()).isEqualTo("EXPLICIT");
    }

    @Test
    void extractsThePublishedDataCenterFileNameWithoutTheFollowingChineseText() {
        var fileName = "2026年09月_应用配方性能评估_数据中心导入_240条.xlsx";
        var question = "正式数据中心文件《" + fileName + "》中 APP-UV-0001 的粘度是多少？";
        var request = QueryRewriteService.sanitizeDataRequest(
                new QueryRewriteService.DataRequest("FIELD_LOOKUP", "", List.of("APP-UV-0001"), List.of("粘度")),
                question, question);

        assertThat(request.fileName()).isEqualTo(fileName);
        assertThat(request.fileScope()).isEqualTo("EXPLICIT");
    }

    @Test
    void sourceScopeSelectorDoesNotInheritThePreviousSourceFile() {
        var request = QueryRewriteService.sanitizeDataRequest(
                new QueryRewriteService.DataRequest("FIELD_LOOKUP",
                        "应用测试报告.xlsx", List.of("配方-001-01"), List.of("粘度")),
                "当前数据文件上下文：应用测试报告.xlsx",
                "只查实验本：实验编号 配方-001-01 的粘度是多少？");

        assertThat(request.fileName()).isEmpty();
        assertThat(request.fileScope()).isEqualTo("AUTO");
    }

    @Test
    void overridesPlannerFileOverviewForAnObviousRecordComparison() {
        var question = "只查数据中心：比较 APP-UV-0001 和 APP-UV-0002 的粘度、硬度和钢丝绒耐磨观察值，不要展示数据明细。";
        var request = QueryRewriteService.sanitizeDataRequest(
                new QueryRewriteService.DataRequest("FILE_OVERVIEW", "", List.of(), List.of()),
                question, question);

        assertThat(request.intent()).isEqualTo("FIELD_LOOKUP");
        assertThat(request.fileName()).isEmpty();
        assertThat(request.fileScope()).isEqualTo("AUTO");
    }

    @Test
    void doesNotTreatAFilelessSemanticQuestionAsAFileOverview() {
        var question = "实验本里最近哪个配方性能最好？";
        var request = QueryRewriteService.sanitizeDataRequest(
                new QueryRewriteService.DataRequest("FIELD_LOOKUP", "", List.of(), List.of()),
                question, question);

        assertThat(request.intent()).isEqualTo("NONE");
    }

    @Test
    void routesABareRecordIdentifierToStructuredRecordLookup() {
        var request = QueryRewriteService.sanitizeDataRequest(
                new QueryRewriteService.DataRequest("NONE", "", List.of(), List.of()),
                "SJ-231", "SJ-231");

        assertThat(request.intent()).isEqualTo("RECORD_DETAIL");
        assertThat(request.recordTerms()).containsExactly("SJ-231");
        assertThat(request.fieldTerms()).isEmpty();
    }

    @Test
    void bareRecordIdentifierIgnoresAPlannerFileInheritedFromConversationContext() {
        var fileName = "2026年09月_应用配方性能评估_数据中心导入_240条.xlsx";
        var request = QueryRewriteService.sanitizeDataRequest(
                new QueryRewriteService.DataRequest("RECORD_DETAIL", fileName,
                        List.of("SJ-231"), List.of("粘度")),
                "当前数据文件上下文：" + fileName + "\n用户本轮问题：SJ-231",
                "SJ-231");

        assertThat(request.intent()).isEqualTo("RECORD_DETAIL");
        assertThat(request.fileName()).isEmpty();
        assertThat(request.fileScope()).isEqualTo("AUTO");
        assertThat(request.recordTerms()).containsExactly("SJ-231");
        assertThat(request.fieldTerms()).isEmpty();
    }

    @Test
    void routesAnIdentifierInsideANaturalQuestionToDataRetrievalWithoutChangingItsQuestion() {
        var fileName = "PET光学膜UV固化应用配方-001.xlsx";
        var question = "SJ-231 是什么用途？";
        var request = QueryRewriteService.sanitizeDataRequest(
                new QueryRewriteService.DataRequest("NONE", fileName,
                        List.of("配方-001-01"), List.of("粘度")),
                question + "\n当前数据文件上下文：" + fileName
                        + "；上一轮对象：配方-001-01；上一轮字段：粘度",
                question);

        assertThat(request.intent()).isEqualTo("RECORD_DETAIL");
        assertThat(request.fileName()).isEmpty();
        assertThat(request.fileScope()).isEqualTo("AUTO");
        assertThat(request.recordTerms()).containsExactly("SJ-231");
        assertThat(request.fieldTerms()).isEmpty();
    }

    @Test
    void keepsAnExplicitFieldQuestionAsFieldLookupAndUsesTheCurrentIdentifier() {
        var question = "SJ-231 的粘度是多少？";
        var request = QueryRewriteService.sanitizeDataRequest(
                new QueryRewriteService.DataRequest("NONE", "旧数据.xlsx",
                        List.of("配方-001-01"), List.of("硬度")),
                question + "\n旧数据.xlsx 配方-001-01 硬度", question);

        assertThat(request.intent()).isEqualTo("FIELD_LOOKUP");
        assertThat(request.fileName()).isEmpty();
        assertThat(request.recordTerms()).containsExactly("SJ-231");
        assertThat(request.fieldTerms()).doesNotContain("硬度");
    }

}
