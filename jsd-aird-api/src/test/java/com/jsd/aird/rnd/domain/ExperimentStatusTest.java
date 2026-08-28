package com.jsd.aird.rnd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ExperimentStatusTest {

    @Test
    void exposesOnlyTheApprovedLifecycleTransitions() {
        var expected = Map.of(
                ExperimentStatus.DRAFT,
                Set.of(ExperimentStatus.PENDING, ExperimentStatus.IN_PROGRESS, ExperimentStatus.VOIDED),
                ExperimentStatus.PENDING, Set.of(ExperimentStatus.IN_PROGRESS, ExperimentStatus.VOIDED),
                ExperimentStatus.IN_PROGRESS,
                Set.of(ExperimentStatus.PENDING_REVIEW, ExperimentStatus.VOIDED),
                ExperimentStatus.RETURNED,
                Set.of(ExperimentStatus.PENDING_REVIEW, ExperimentStatus.VOIDED),
                ExperimentStatus.PENDING_REVIEW,
                Set.of(ExperimentStatus.COMPLETED, ExperimentStatus.RETURNED),
                ExperimentStatus.COMPLETED, Set.of(),
                ExperimentStatus.VOIDED, Set.of()
        );

        expected.forEach((current, allowed) -> {
            for (var target : ExperimentStatus.values()) {
                assertThat(current.canTransitionTo(target))
                        .as("%s -> %s", current, target)
                        .isEqualTo(allowed.contains(target));
            }
        });
    }

    @Test
    void locksSubmittedAndTerminalVersionsAgainstInPlaceEditing() {
        assertThat(ExperimentStatus.DRAFT.isEditable()).isTrue();
        assertThat(ExperimentStatus.PENDING.isEditable()).isTrue();
        assertThat(ExperimentStatus.IN_PROGRESS.isEditable()).isTrue();
        assertThat(ExperimentStatus.RETURNED.isEditable()).isTrue();
        assertThat(ExperimentStatus.PENDING_REVIEW.isEditable()).isFalse();
        assertThat(ExperimentStatus.COMPLETED.isEditable()).isFalse();
        assertThat(ExperimentStatus.VOIDED.isEditable()).isFalse();
    }
}
