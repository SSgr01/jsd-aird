package com.jsd.aird.ai.rnd.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityPolicyEvaluatorTest {
    private final QualityPolicyEvaluator evaluator = new QualityPolicyEvaluator();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void evaluatesPredictionWithoutCustomerFacingPolicyConfiguration() {
        var result = evaluator.evaluate(null, json.createObjectNode(), "IN_DOMAIN", 1d, List.of(), BigDecimal.valueOf(100));

        assertThat(result.level()).isEqualTo("MEDIUM");
        assertThat(result.explanation()).contains("模型验证结果");
    }
}
