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
    }

}
