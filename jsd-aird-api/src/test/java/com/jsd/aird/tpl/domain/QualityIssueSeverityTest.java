package com.jsd.aird.tpl.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QualityIssueSeverityTest {

    @Test
    void normalizesKnownAliasesAndUsesWarningForUnknownValues() {
        assertThat(QualityIssueSeverity.normalize("LOW")).isEqualTo("INFO");
        assertThat(QualityIssueSeverity.normalize("MEDIUM")).isEqualTo("WARNING");
        assertThat(QualityIssueSeverity.normalize("HIGH")).isEqualTo("BLOCKER");
        assertThat(QualityIssueSeverity.normalize("something-new")).isEqualTo("WARNING");
        assertThat(QualityIssueSeverity.normalize(null)).isEqualTo("WARNING");
    }
}
