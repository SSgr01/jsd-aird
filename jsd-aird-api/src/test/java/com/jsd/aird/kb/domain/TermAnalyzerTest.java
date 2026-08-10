package com.jsd.aird.kb.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TermAnalyzerTest {

    @Test
    void emitsLatinWordsChineseNgramsAndFrequencies() {
        var terms = TermAnalyzer.frequencies("PA-66 耐热 材料");

        assertThat(terms).containsEntry("pa", 1).containsEntry("66", 1)
                .containsEntry("耐", 1).containsEntry("热", 1).containsEntry("耐热", 1)
                .containsEntry("材", 1).containsEntry("料", 1).containsEntry("材料", 1);
    }

    @Test
    void normalizesCaseAndCountsRepeatedTerms() {
        assertThat(TermAnalyzer.frequencies("Resin resin")).containsEntry("resin", 2);
    }
}
