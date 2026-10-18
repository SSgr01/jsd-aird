package com.jsd.aird.ai.rnd;

import java.util.Locale;

/**
 * Keeps synthetic training data eligible only in explicitly enabled test
 * environments. This policy does not rewrite or infer a model's data origin.
 */
public record SyntheticTestAuthorization(String environment, boolean syntheticTestEnabled) {

    public boolean allows(String dataOrigin, boolean explicitlyAllowProductionRealData) {
        var origin = dataOrigin == null ? "" : dataOrigin.trim().toUpperCase(Locale.ROOT);
        if ("REAL".equals(origin)) return explicitlyAllowProductionRealData;
        if (!"SYNTHETIC".equals(origin)) return false;
        return "test".equalsIgnoreCase(environment == null ? "" : environment.trim())
                && syntheticTestEnabled;
    }
}
