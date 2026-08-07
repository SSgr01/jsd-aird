package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RecognitionCandidatePolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsPhysicalOnlyCandidateEvenWhenItHasHighConfidence() {
        var payload = objectMapper.createObjectNode()
                .put("kind", "MATRIX").put("candidateOnly", true)
                .put("physicalStructureOnly", true).put("canonicalStatus", "PROVISIONAL")
                .put("structureStatus", "PROVISIONAL");

        assertThat(RecognitionCandidatePolicy.isFormallyConfirmable(payload)).isFalse();
    }

    @Test
    void acceptsResolvedCanonicalStructureOnlyAfterBothStatusesAreConfirmed() {
        var payload = objectMapper.createObjectNode()
                .put("kind", "MATRIX").put("canonicalStatus", "CONFIRMED")
                .put("structureStatus", "CONFIRMED").put("editability", "EDITABLE")
                .put("valueSource", "USER_INPUT");

        assertThat(RecognitionCandidatePolicy.isFormallyConfirmable(payload)).isTrue();
    }
}
