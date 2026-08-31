package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AssistantEvidenceSelectionTest {

    @Test
    void notFoundAnswersNeverExposeRetrievedSources() {
        var selected = AssistantService.selectEvidenceRefs("NOT_FOUND", List.of("K1", "K2"), Set.of("K1", "K2"));

        assertThat(selected).isEmpty();
    }

    @Test
    void keepsOnlyEvidenceReferencesPresentInTheDeliveredContext() {
        var selected = AssistantService.selectEvidenceRefs("PARTIAL",
                List.of("k2", "K9", "K2", "D1", "e1"), Set.of("K1", "K2", "D1", "E1"));

        assertThat(selected).containsExactly("K2", "D1", "E1");
    }

}
