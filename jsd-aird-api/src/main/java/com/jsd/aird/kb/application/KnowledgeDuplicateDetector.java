package com.jsd.aird.kb.application;

import java.text.Normalizer;
import java.util.Locale;

/** Shared deterministic filename normalization for upload preflight and compatibility uploads. */
public final class KnowledgeDuplicateDetector {

    private KnowledgeDuplicateDetector() {
    }

    public static String normalizedStem(String fileName) {
        var value = fileName == null ? "" : Normalizer.normalize(fileName, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        var dot = value.lastIndexOf('.');
        if (dot > 0) value = value.substring(0, dot);
        value = value
                .replaceAll("[（(]\\s*\\d+\\s*[)）]", " ")
                .replaceAll("(?i)(?:^|[\\s_\\-.])(副本|copy|final|最终版|定稿)(?:$|[\\s_\\-.])", " ")
                .replaceAll("(?i)(?:^|[\\s_\\-.])v(?:ersion)?\\s*\\d+(?:\\.\\d+)*(?:$|[\\s_\\-.])", " ")
                .replaceAll("(?:19|20)\\d{6}", " ")
                .replaceAll("(?:19|20)\\d{2}[年_\\-.]?(?:1[0-2]|0?[1-9])[月_\\-.]?(?:3[01]|[12]\\d|0?[1-9])日?", " ")
                .replaceAll("[\\s_\\-.—–]+", "")
                .strip();
        return value;
    }

    public static double similarity(String left, String right) {
        var a = normalizedStem(left);
        var b = normalizedStem(right);
        if (a.equals(b)) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0;
        var previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            var current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                var cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return 1.0 - ((double) previous[b.length()] / Math.max(a.length(), b.length()));
    }
}
