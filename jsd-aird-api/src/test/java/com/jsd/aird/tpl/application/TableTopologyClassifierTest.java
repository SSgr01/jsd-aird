package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TableTopologyClassifierTest {

    private final TableTopologyClassifier classifier = new TableTopologyClassifier();

    @Test
    void requiresTwoVisibleMemberAxesForMatrix() {
        assertThat(classifier.classify(new TableTopologyClassifier.Evidence(
                "UNKNOWN", false, 4, 5, 4, 5, true
        ))).isEqualTo(TableTopologyClassifier.Topology.MATRIX);
    }

    @Test
    void treatsBlankRuntimeIdentityBandAsRuntimeEvidenceNotColumnTruth() {
        assertThat(classifier.classify(new TableTopologyClassifier.Evidence(
                "COLUMN", true, 0, 12, 6, 15, true,
                true, false, "C4:H4", "", "C5:H19", 12, false
        ))).isEqualTo(TableTopologyClassifier.Topology.UNKNOWN);
    }

    @Test
    void retainsRuntimeMemberSurfaceEvidence() {
        var evidence = new TableTopologyClassifier.Evidence(
                "COLUMN", true, 0, 12, 6, 15, true,
                true, false, "C4:H4", "", "C5:H19", 12, false
        );
        assertThat(classifier.analyze(evidence).evidence().runtimeColumnMemberSurface()).isTrue();
        assertThat(classifier.analyze(evidence).topology()).isEqualTo(TableTopologyClassifier.Topology.UNKNOWN);
    }

    @Test
    void leavesAmbiguousGridUnknown() {
        assertThat(classifier.classify(new TableTopologyClassifier.Evidence(
                "UNKNOWN", false, 1, 4, 6, 15, true
        ))).isEqualTo(TableTopologyClassifier.Topology.UNKNOWN);
    }

    @Test
    void recognizesColumnRecordsOnlyWhenTheSecondMemberAxisIsAbsent() {
        assertThat(classifier.classify(new TableTopologyClassifier.Evidence(
                "COLUMN", false, 0, 4, 4, 5, true
        ))).isEqualTo(TableTopologyClassifier.Topology.COLUMN_TABLE);
    }
}
