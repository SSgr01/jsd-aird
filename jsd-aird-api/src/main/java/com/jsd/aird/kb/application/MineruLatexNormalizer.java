package com.jsd.aird.kb.application;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Narrow, deterministic cleanup for spacing artifacts introduced by MinerU. */
public final class MineruLatexNormalizer {

    private static final Pattern ROMAN_GROUP = Pattern.compile("\\\\mathrm\\s*\\{([^{}]*)}");
    private static final Pattern SPACED_DIGITS = Pattern.compile("(?<![\\p{L}\\d])(?:\\d\\s+){1,}\\d(?![\\p{L}\\d])");

    private MineruLatexNormalizer() { }

    public static String normalizeForRender(String latexRaw) {
        if (latexRaw == null || latexRaw.isBlank()) return "";
        var value = latexRaw.replace("\u0000", "").strip();
        value = replaceRomanSpacing(value);
        var matcher = SPACED_DIGITS.matcher(value);
        var result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group().replaceAll("\\s+", "")));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String replaceRomanSpacing(String value) {
        var matcher = ROMAN_GROUP.matcher(value);
        var result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    "\\mathrm{" + matcher.group(1).replaceAll("\\s+", "") + "}"));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
