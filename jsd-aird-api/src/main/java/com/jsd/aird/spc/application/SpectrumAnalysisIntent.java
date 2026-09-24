package com.jsd.aird.spc.application;

import java.util.Locale;
import java.util.Optional;

public enum SpectrumAnalysisIntent {
    AUTO,
    OVERVIEW,
    PEAK_INTERPRETATION,
    SAMPLE_COMPARISON,
    CONCENTRATION_CHECK,
    PURITY_CHECK,
    IDENTIFICATION,
    REFERENCE_MAPPING,
    METHOD_VALIDATION,
    ANOMALY_DIAGNOSIS;

    public static Optional<SpectrumAnalysisIntent> parse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
