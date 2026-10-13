package com.jsd.aird.ai.rnd;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Explicitly authorizes generated-data workflows in a test deployment only.
 * Production eligibility remains an independent, unchanged qualification.
 */
@Component
public final class SyntheticTestAuthorization {
    private final boolean enabled;

    public SyntheticTestAuthorization(
            @Value("${JSD_AIRD_RUNTIME_ENVIRONMENT:production}") String runtimeEnvironment,
            @Value("${JSD_AIRD_AI_TEST_SYNTHETIC_WORKFLOW_ENABLED:false}") boolean enabled) {
        this.enabled = enabled && "test".equalsIgnoreCase(runtimeEnvironment == null ? "" : runtimeEnvironment.strip());
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean allows(String dataNature, boolean productionEligible) {
        return allows(dataNature, productionEligible, false);
    }

    public boolean allows(String dataNature, boolean productionEligible, boolean testOnlySimulation) {
        if (testOnlySimulation) return enabled;
        if (productionEligible && "REAL".equalsIgnoreCase(dataNature)) return true;
        return enabled && "SYNTHETIC".equalsIgnoreCase(dataNature);
    }

    public boolean isSimulation(String dataNature, boolean testOnlySimulation) {
        return enabled && (testOnlySimulation || "SYNTHETIC".equalsIgnoreCase(dataNature));
    }
}
