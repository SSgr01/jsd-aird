package com.jsd.aird.ai.rnd.eligibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jsd.aird.ai.rnd.eligibility.EligibilityContracts.State;
import static com.jsd.aird.ai.rnd.eligibility.EligibilityRepository.*;
import static org.assertj.core.api.Assertions.assertThat;

class EligibilityRuleEvaluatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EligibilityRuleEvaluator evaluator = new EligibilityRuleEvaluator(json);
    private final UUID targetVersion = UUID.randomUUID();
    private final UUID scheme = UUID.randomUUID();

    @Test
    void missingRequiredInputIsExcludedAndRecorded() throws Exception {
        var result = evaluator.evaluate(config(List.of(new FieldRow(UUID.randomUUID(), true, 0, "UV_ENERGY", "UV能量", "NUMBER", "mJ/cm²", "PRE_EXPERIMENT", json.createObjectNode(), json.createObjectNode())), null), sample("12.0", null));
        assertThat(result.state()).isEqualTo(State.EXCLUDED);
        assertThat(result.reasons()).extracting("code").contains("MISSING_REQUIRED_X");
    }

    @Test
    void nonHundredFormulaPreservesValueAsWarning() throws Exception {
        var composition = json.readTree("{\"items\":[{\"materialCode\":\"RESIN\",\"ratio\":98.8,\"amountKnown\":true}]}" );
        var result = evaluator.evaluate(config(List.of(new FieldRow(UUID.randomUUID(), true, 0, "FORMULA", "配方", "COMPOSITION", "%", "PRE_EXPERIMENT", json.createObjectNode(), json.createObjectNode())), null), sample("12.0", composition));
        assertThat(result.state()).withFailMessage("reasons=" + result.reasons()).isEqualTo(State.TRAINABLE);
        assertThat(result.warnings()).extracting("code").containsExactly("FORMULA_TOTAL_WARNING");
    }

    @Test
    void unknownMaterialRequiresReview() throws Exception {
        var composition = json.readTree("{\"items\":[{\"materialCode\":\"UNKNOWN\",\"ratio\":100,\"amountKnown\":true}]}" );
        var dictionary = json.readTree("[{\"materialId\":\"KNOWN\",\"token\":\"KNOWN\"}]");
        var result = evaluator.evaluate(config(List.of(new FieldRow(UUID.randomUUID(), true, 0, "FORMULA", "配方", "COMPOSITION", "%", "PRE_EXPERIMENT", json.createObjectNode(), json.createObjectNode())), dictionary), sample("12.0", composition));
        assertThat(result.state()).isEqualTo(State.EXCLUDED);
        assertThat(result.reasons()).extracting("code").contains("MATERIAL_NOT_IN_MODEL");
    }

    @Test
    void missingMaterialIdentityRequiresReviewEvenWhenDictionaryIsEmpty() throws Exception {
        var composition = json.readTree("{\"items\":[{\"ratio\":100,\"amountKnown\":true}]}" );
        var result = evaluator.evaluate(config(List.of(new FieldRow(UUID.randomUUID(), true, 0, "FORMULA", "配方", "COMPOSITION", "%", "PRE_EXPERIMENT", json.createObjectNode(), json.createObjectNode())), json.createArrayNode()), sample("12.0", composition));
        assertThat(result.state()).isEqualTo(State.REVIEW_REQUIRED);
        assertThat(result.reasons()).extracting("code").contains("UNKNOWN_MATERIAL");
    }

    @Test
    void multipleTargetObservationsAreAmbiguous() throws Exception {
        var observations = json.readTree("[{\"label\":\"gloss\",\"value\":12},{\"label\":\"gloss\",\"value\":13}]");
        var result = evaluator.evaluate(config(List.of(), null), sampleWithObservations(observations, null));
        assertThat(result.state()).isEqualTo(State.EXCLUDED);
        assertThat(result.reasons()).extracting("code").contains("TARGET_AMBIGUOUS");
        assertThat(result.primaryReason()).isEqualTo("TARGET_AMBIGUOUS");
    }

    @Test
    void lowerBoundObservationIsNotAContinuousValue() throws Exception {
        var observations = json.readTree("{\"gloss\":{\"value\":\">=20\",\"observationType\":\"LOWER_BOUND\"}}");
        var result = evaluator.evaluate(config(List.of(), null), sampleWithObservations(observations, null));
        assertThat(result.state()).isEqualTo(State.EXCLUDED);
        assertThat(result.reasons()).extracting("code").contains("UNSUPPORTED_OBSERVATION_TYPE");
    }

    @Test
    void primaryReasonUsesQualificationOrderRatherThanJsonInsertionOrder() throws Exception {
        var composition = json.readTree("{\"items\":[{\"materialCode\":\"UNKNOWN\",\"ratio\":100,\"amountKnown\":true}]}" );
        var field = new FieldRow(UUID.randomUUID(), true, 0, "MISSING_INPUT", "缺失输入", "NUMBER", "", "PRE_EXPERIMENT", json.createObjectNode(), json.createObjectNode());
        var result = evaluator.evaluate(config(List.of(field), json.readTree("[{\"materialId\":\"KNOWN\",\"token\":\"KNOWN\"}]")), sample("12.0", composition));
        assertThat(result.primaryReason()).isEqualTo("MISSING_REQUIRED_X");
    }

    private Configuration config(List<FieldRow> fields, com.fasterxml.jackson.databind.JsonNode dictionary) throws Exception {
        var mapping = new SourceMappingRow(UUID.randomUUID(), "DATA_CENTER", json.readTree("{\"targetFieldCode\":\"GLOSS\",\"sourceAliases\":[\"gloss\"]}"), 1);
        var policy = new PolicyRow(UUID.randomUUID(), json.createObjectNode(), json.createObjectNode(), json.createObjectNode(), "0123456789012345678901234567890123456789012345678901234567890123");
        return new Configuration(UUID.randomUUID(), targetVersion, scheme, "CONTINUOUS", "GU", List.of(), json.createObjectNode(), json.createObjectNode(), UUID.randomUUID(), "FROZEN", "PUBLISHED", fields, Map.of("DATA_CENTER", mapping), policy, dictionary, "hash");
    }

    private SampleRow sample(String gloss, com.fasterxml.jackson.databind.JsonNode composition) throws Exception {
        return sampleWithObservations(json.readTree("{\"gloss\":{\"value\":" + gloss + "}}"), composition);
    }

    private SampleRow sampleWithObservations(com.fasterxml.jackson.databind.JsonNode observations, com.fasterxml.jackson.databind.JsonNode composition) throws Exception {
        return new SampleRow(UUID.randomUUID(), "sample-1", "ACTIVE", "DATA_CENTER", UUID.randomUUID(), 1,
                composition == null ? json.createObjectNode() : composition, json.createObjectNode(), json.createObjectNode(),
                observations, json.createObjectNode(), json.createObjectNode(), "DATA_CENTER", "CURRENT", false);
    }
}
