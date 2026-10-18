package com.jsd.aird.ai.rnd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticTestAuthorizationTest {
    @Test
    void syntheticModelsNeedBothExplicitTestEnvironmentAndAuthorization() {
        var test = new SyntheticTestAuthorization("test", true);
        var productionFlagOnly = new SyntheticTestAuthorization("production", true);
        var testFlagOff = new SyntheticTestAuthorization("test", false);

        assertThat(test.allows("SYNTHETIC", false)).isTrue();
        assertThat(productionFlagOnly.allows("SYNTHETIC", false)).isFalse();
        assertThat(testFlagOff.allows("SYNTHETIC", false)).isFalse();
    }

    @Test
    void authorizationNeverTurnsSyntheticDataIntoProductionEligibleData() {
        var test = new SyntheticTestAuthorization("test", true);

        assertThat(test.allows("SYNTHETIC", false)).isTrue();
        assertThat(test.allows("REAL", false)).isFalse();
        assertThat(test.allows("REAL", true)).isTrue();
    }
}
