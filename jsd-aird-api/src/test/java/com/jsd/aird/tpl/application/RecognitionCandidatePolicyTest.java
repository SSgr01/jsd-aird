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

    @Test
    void acceptsDeterministicSimpleLongTableFieldForOneClickConfirmation() {
        var payload = objectMapper.createObjectNode()
                .put("kind", "SCALAR").put("suggestionLevel", "CHILD")
                .put("mappingKind", "REPEAT_FIELD").put("repeatAxis", "ROW")
                .put("recognitionOrigin", "RULE_DETERMINISTIC")
                .put("reasonCode", "SIMPLE_LONG_TABLE_FIELD")
                .put("candidateOnly", true).put("reviewRequired", true)
                .put("physicalStructureOnly", true).put("editability", "EDITABLE")
                .put("valueSource", "USER_INPUT");

        assertThat(RecognitionCandidatePolicy.isFormallyConfirmable(payload)).isTrue();
    }

    @Test
    void doesNotTreatAnAmbiguousTableChildAsDeterministic() {
        var payload = objectMapper.createObjectNode()
                .put("kind", "SCALAR").put("suggestionLevel", "CHILD")
                .put("mappingKind", "REPEAT_FIELD").put("repeatAxis", "ROW")
                .put("recognitionOrigin", "MODEL")
                .put("reasonCode", "SIMPLE_LONG_TABLE_FIELD")
                .put("candidateOnly", true).put("reviewRequired", true);

        assertThat(RecognitionCandidatePolicy.isFormallyConfirmable(payload)).isFalse();
    }

    @Test
    void formulaExpressionCanNeverBecomeAConfirmableBusinessField() {
        var payload = objectMapper.createObjectNode()
                .put("kind", "SCALAR").put("fieldName", "=IF(COUNT(C9:C11)=0,,AVERAGE(C9:C11))")
                .put("candidateOnly", false).put("reviewRequired", false)
                .put("editability", "READ_ONLY").put("valueSource", "FORMULA");

        assertThat(RecognitionCandidatePolicy.isFormallyConfirmable(payload)).isFalse();
        assertThat(RecognitionCandidatePolicy.isFormalCandidate(payload)).isFalse();
        assertThat(RecognitionCandidatePolicy.isOneClickFieldConfirmable(payload)).isFalse();
    }

    @Test
    void oneClickConfirmationAcceptsAReviewedFieldInsideConfirmedStructure() {
        var payload = objectMapper.createObjectNode()
                .put("kind", "SCALAR").put("suggestionLevel", "CHILD")
                .put("fieldName", "粘度").put("bindingId", "binding-viscosity")
                .put("mappingKind", "REPEAT_FIELD")
                .put("candidateOnly", false).put("reviewRequired", true)
                .put("physicalStructureOnly", false).put("structureConflict", false)
                .put("canonicalStatus", "CONFIRMED").put("structureStatus", "CONFIRMED")
                .put("valueSource", "USER_INPUT");

        assertThat(RecognitionCandidatePolicy.isFormallyConfirmable(payload)).isFalse();
        assertThat(RecognitionCandidatePolicy.isOneClickFieldConfirmable(payload)).isTrue();
    }

    @Test
    void oneClickConfirmationStillRejectsAmbiguousOrUnboundFields() {
        var ambiguous = objectMapper.createObjectNode()
                .put("kind", "SCALAR").put("suggestionLevel", "CHILD")
                .put("fieldName", "粘度").put("bindingId", "binding-viscosity")
                .put("mappingKind", "REPEAT_FIELD")
                .put("candidateOnly", true).put("reviewRequired", true);
        var unbound = ambiguous.deepCopy()
                .put("candidateOnly", false).put("bindingId", "");

        assertThat(RecognitionCandidatePolicy.isOneClickFieldConfirmable(ambiguous)).isFalse();
        assertThat(RecognitionCandidatePolicy.isOneClickFieldConfirmable(unbound)).isFalse();
    }
}
