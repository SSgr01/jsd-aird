package com.jsd.aird.tpl.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TableTopologyClassifierTest {

    private final TableTopologyClassifier classifier = new TableTopologyClassifier();

    @Test
    void leavesTwoVisibleAxesUnresolved() {
        assertThat(classifier.classify(new TableTopologyClassifier.Evidence(
                "UNKNOWN", false, 4, 5, 4, 5, true
        ))).isEqualTo(TableTopologyClassifier.Topology.UNKNOWN);
    }

    @Test
    void treatsBlankRuntimeIdentityBandAsColumnEvidence() {
        assertThat(classifier.classify(new TableTopologyClassifier.Evidence(
                "COLUMN", true, 0, 12, 6, 15, true,
                true, false, "C4:H4", "", "C5:H19", 12, false
        ))).isEqualTo(TableTopologyClassifier.Topology.COLUMN_TABLE);
    }

    @Test
    void retainsBlankAlternateAxisEvidence() {
        var evidence = new TableTopologyClassifier.Evidence(
                "COLUMN", true, 0, 12, 6, 15, true,
                true, false, "C4:H4", "", "C5:H19", 12, false
        );
        assertThat(classifier.analyze(evidence).evidence().blankColumnHeaderBand()).isTrue();
        assertThat(classifier.analyze(evidence).topology()).isEqualTo(TableTopologyClassifier.Topology.COLUMN_TABLE);
    }

    @Test
    void recognizesFilledSampleIdentityBandAsColumnRecords() {
        assertThat(classifier.classify(new TableTopologyClassifier.Evidence(
                "COLUMN", false, 6, 15, 6, 15, true,
                false, false, "C4:H4", "", "C5:H19", 15, true
        ))).isEqualTo(TableTopologyClassifier.Topology.COLUMN_TABLE);
    }

    @Test
    void leavesAmbiguousGridUnknown() {
        assertThat(classifier.classify(new TableTopologyClassifier.Evidence(
                "UNKNOWN", false, 1, 4, 6, 15, true
        ))).isEqualTo(TableTopologyClassifier.Topology.UNKNOWN);
    }

    @Test
    void recognizesColumnRecordsOnlyWhenTheCompetingAxisIsAbsent() {
        assertThat(classifier.classify(new TableTopologyClassifier.Evidence(
                "COLUMN", false, 0, 4, 4, 5, true
        ))).isEqualTo(TableTopologyClassifier.Topology.COLUMN_TABLE);
    }
}
