package com.jsd.aird.rnd.domain;

import java.util.Set;

public enum ExperimentStatus {
    DRAFT, PENDING, IN_PROGRESS, PENDING_REVIEW, RETURNED, COMPLETED, VOIDED;

    public boolean canTransitionTo(ExperimentStatus target) {
        return switch (this) {
            case DRAFT -> Set.of(PENDING, IN_PROGRESS, VOIDED).contains(target);
            case PENDING -> Set.of(IN_PROGRESS, VOIDED).contains(target);
            case IN_PROGRESS, RETURNED -> Set.of(PENDING_REVIEW, VOIDED).contains(target);
            case PENDING_REVIEW -> Set.of(COMPLETED, RETURNED).contains(target);
            case COMPLETED, VOIDED -> false;
        };
    }
}
