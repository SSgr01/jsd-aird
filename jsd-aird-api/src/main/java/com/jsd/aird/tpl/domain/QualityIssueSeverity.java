package com.jsd.aird.tpl.domain;

import java.util.Locale;

/** Database-safe severity vocabulary for template quality diagnostics. */
public enum QualityIssueSeverity {
    INFO,
    WARNING,
    BLOCKER;

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return WARNING.name();
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "INFO", "LOW", "MINOR" -> INFO.name();
            case "BLOCKER", "HIGH", "CRITICAL", "ERROR", "FATAL" -> BLOCKER.name();
            case "WARNING", "WARN", "MEDIUM", "MODERATE" -> WARNING.name();
            default -> WARNING.name();
        };
    }
}
