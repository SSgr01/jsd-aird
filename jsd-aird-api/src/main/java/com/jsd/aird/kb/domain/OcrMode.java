package com.jsd.aird.kb.domain;

import java.util.Locale;

public enum OcrMode {
    AUTO,
    ON,
    OFF;

    public static OcrMode fromNullable(String value) {
        if (value == null || value.isBlank()) return AUTO;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("ocrMode 必须是 AUTO、ON 或 OFF");
        }
    }
}
