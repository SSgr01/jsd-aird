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
                Map.of(), "不限", false);

        assertThat(QueryRewriteService.isUsable(plan)).isTrue();
    }

    @Test
    void rejectsMissingRewriteQueryOrOversizedLists() {
        assertThat(QueryRewriteService.isUsable(new QueryRewriteService.QueryPlan(
                "原问题", "", List.of(), List.of(), List.of(), Map.of(), "不限", false))).isFalse();
        assertThat(QueryRewriteService.isUsable(new QueryRewriteService.QueryPlan(
                "原问题", "检索问题", List.of("1", "2", "3", "4", "5", "6", "7"),
                List.of(), List.of(), Map.of(), "不限", false))).isFalse();
        assertThat(QueryRewriteService.isUsable(new QueryRewriteService.QueryPlan(
                "原问题", "检索问题", List.of(),
                java.util.stream.IntStream.range(0, 25).mapToObj(index ->
                        new QueryRewriteService.RetrievalTerm("term-" + index, List.of(), "PHRASE")).toList(),
                List.of(), Map.of(), "不限", false))).isFalse();
    }
}
