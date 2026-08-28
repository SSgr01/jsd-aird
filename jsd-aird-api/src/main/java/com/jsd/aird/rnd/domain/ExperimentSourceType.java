package com.jsd.aird.rnd.domain;

import java.util.Locale;

/**
 * Canonical origins accepted by the existing rnd.experiment database contract.
 */
public enum ExperimentSourceType {
    PROJECT,
    TEMPLATE,
    MANUAL,
    EXCEL_IMPORT,
    OCR_IMPORT;

    public static ExperimentSourceType fromNullable(String value) {
        if (value == null || value.isBlank()) return MANUAL;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "实验来源必须是 PROJECT、TEMPLATE、MANUAL、EXCEL_IMPORT 或 OCR_IMPORT",
                    exception
            );
        }
    }
}
