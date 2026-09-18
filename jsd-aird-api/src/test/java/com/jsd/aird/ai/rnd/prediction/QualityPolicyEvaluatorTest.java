package com.jsd.aird.ai.rnd.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityPolicyEvaluatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final QualityPolicyEvaluator evaluator = new QualityPolicyEvaluator();

    @Test
    void usesTheFirstMatchingPublishedRuleAndLeavesFormulaWarningToPolicy() throws Exception {
        var policy = json.readTree("""
                {"defaultTrustLevel":"HIGH","defaultExplanation":"默认可信",
                 "rules":[
                   {"priority":20,"trustLevel":"LOW","explanation":"接近边界","when":{"domainStatuses":["NEAR_BOUNDARY"]}},
                   {"priority":10,"trustLevel":"MEDIUM","explanation":"配方合计提示","when":{"warningCodesAny":["FORMULA_TOTAL_WARNING"]}}
                 ]}
                """);
        var result = evaluator.evaluate(policy, json.readTree("{\"mae\":1.2}"), "IN_DOMAIN", 1.0,
                List.of("FORMULA_TOTAL_WARNING"), new BigDecimal("98.8"));

        assertThat(result.level()).isEqualTo("MEDIUM");
        assertThat(result.explanation()).isEqualTo("配方合计提示");
    }

    @Test
    void fallsBackWhenNoQualityRuleMatches() throws Exception {
        var policy = json.readTree("""
                {"defaultTrustLevel":"LOW","defaultExplanation":"需要更多证据",
                 "rules":[{"priority":1,"trustLevel":"HIGH","explanation":"范围内","when":{"domainStatuses":["IN_DOMAIN"]}}]}
                """);
        var result = evaluator.evaluate(policy, json.readTree("{\"mae\":1.2}"), "OUT_OF_DOMAIN", 0.4,
                List.of(), new BigDecimal("100"));

        assertThat(result.level()).isEqualTo("LOW");
        assertThat(result.explanation()).isEqualTo("需要更多证据");
    }

    @Test
    void checksMetricAndCoverageConditionsTogether() throws Exception {
        var policy = json.readTree("""
                {"defaultTrustLevel":"LOW","defaultExplanation":"默认",
                 "rules":[{"priority":1,"trustLevel":"HIGH","explanation":"证据充分且指标达标",
                   "when":{"minimumEvidenceCoverage":0.8,"validationMetrics":[{"metric":"mae","operator":"LTE","value":2.0}]}}]}
                """);
        var matching = evaluator.evaluate(policy, json.readTree("{\"mae\":1.5}"), "IN_DOMAIN", 0.9, List.of(), null);
        var nonMatching = evaluator.evaluate(policy, json.readTree("{\"mae\":1.5}"), "IN_DOMAIN", 0.7, List.of(), null);

        assertThat(matching.level()).isEqualTo("HIGH");
        assertThat(nonMatching.level()).isEqualTo("LOW");
    }
}
