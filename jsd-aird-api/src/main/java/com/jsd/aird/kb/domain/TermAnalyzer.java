package com.jsd.aird.kb.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class TermAnalyzer {

    public static final String VERSION = "term-v1";

    private TermAnalyzer() { }

    public static Map<String, Integer> frequencies(String input) {
        var text = input == null ? "" : input.toLowerCase(Locale.ROOT);
        var result = new LinkedHashMap<String, Integer>();
        var latin = new StringBuilder();
        var cjk = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            var codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                flushLatin(latin, result);
                cjk.appendCodePoint(codePoint);
            } else if (Character.isLetterOrDigit(codePoint)) {
                flushCjk(cjk, result);
                latin.appendCodePoint(codePoint);
            } else {
                flushLatin(latin, result);
                flushCjk(cjk, result);
            }
        }
        flushLatin(latin, result);
        flushCjk(cjk, result);
        return Map.copyOf(result);
    }

    private static void flushLatin(StringBuilder token, Map<String, Integer> result) {
        if (token.length() >= 2) add(result, token.toString());
        token.setLength(0);
    }

    private static void flushCjk(StringBuilder token, Map<String, Integer> result) {
        if (token.isEmpty()) return;
        var value = token.toString();
        for (int i = 0; i < value.length(); i++) add(result, value.substring(i, i + 1));
        for (int i = 0; i + 1 < value.length(); i++) add(result, value.substring(i, i + 2));
        token.setLength(0);
    }

    private static void add(Map<String, Integer> result, String term) {
        if (!term.isBlank()) result.merge(term, 1, Integer::sum);
    }

    private static boolean isCjk(int codePoint) {
        return codePoint >= 0x4E00 && codePoint <= 0x9FFF;
    }
}
