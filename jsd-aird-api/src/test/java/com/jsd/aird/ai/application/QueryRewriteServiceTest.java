package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class QueryRewriteServiceTest {

    @Test
    void acceptsACompletePlannerResult() {
        var plan = new QueryRewriteService.QueryPlan("原问题", "检索问题",
                List.of("子问题"), List.of("关键词"), Map.of(), "不限", false);

        assertThat(QueryRewriteService.isUsable(plan)).isTrue();
    }

    @Test
    void rejectsMissingRewriteQueryOrOversizedLists() {
        assertThat(QueryRewriteService.isUsable(new QueryRewriteService.QueryPlan(
                "原问题", "", List.of(), List.of(), Map.of(), "不限", false))).isFalse();
        assertThat(QueryRewriteService.isUsable(new QueryRewriteService.QueryPlan(
                "原问题", "检索问题", List.of("1", "2", "3", "4"), List.of(), Map.of(), "不限", false))).isFalse();
        assertThat(QueryRewriteService.isUsable(new QueryRewriteService.QueryPlan(
                "原问题", "检索问题", List.of(),
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13"),
                Map.of(), "不限", false))).isFalse();
    }
}
