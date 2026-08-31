package com.jsd.aird.kb.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cross-domain normalization for markup, formulae and measurements. */
public final class TechnicalTextNormalizer {

    private static final Pattern MATH_ROMAN = Pattern.compile("\\\\(?:mathrm|text)\\s*\\{([^{}]*)}");
    private static final Pattern LATEX_SCRIPT = Pattern.compile("\\s*[_^]\\s*\\{\\s*([+\\-−]?\\s*\\d+)\\s*}");
    private static final String NUMBER = "[≈~]?[+\\-]?\\d+(?:[.,]\\d+)?(?:\\s*[\\-~]\\s*\\d+(?:[.,]\\d+)?)?";
    private static final String UNIT = "[\\p{L}μµΩ°%℃℉]{1,16}(?:(?:\\^)?[+\\-]?\\d+)?";
    private static final Pattern MEASUREMENT = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d])" + NUMBER + "\\s*(?:[%°℃℉]|" + UNIT
                    + "(?:\\s*(?:[/·⋅*]\\s*" + UNIT + "|\\s+[\\p{L}μµΩ]{1,4}[+\\-]?\\d+)){0,3})(?![\\p{L}])");

    private TechnicalTextNormalizer() { }

    public static String normalizeMarkup(String value) {
        if (value == null || value.isBlank()) return "";
        var result = replaceMathRoman(value);
        result = result.replace("<sub>", "_").replace("</sub>", "")
                .replace("<sup>", "^").replace("</sup>", "");
        result = replaceLatexScripts(result);
        result = unicodeScripts(result);
        return Normalizer.normalize(result, Normalizer.Form.NFKC)
                .replace('−', '-').replace('–', '-')
                .replace('—', '-').replace('⋅', '·').replace('µ', 'μ')
                .replaceAll("\\s+", " ").strip();
    }

    public static String canonicalToken(String value) {
        var normalized = normalizeMarkup(value).toLowerCase(Locale.ROOT).strip();
        if (normalized.isBlank()) return "";
        if (normalized.matches("^[≈~]?[+\\-]?\\d.*")) {
            normalized = normalized.replaceAll("\\s+", "/")
                    .replaceFirst("(?<=\\d)/(?=[\\p{L}μ°%℃℉])", "")
                    .replace('·', '/');
            normalized = normalized.replaceAll("/([\\p{L}μ]+)-1(?!\\d)", "/$1")
                    .replaceAll("/([\\p{L}μ]+)-([2-9])(?!\\d)", "/$1$2");
        } else {
            normalized = normalized.replaceAll("\\s+", "");
        }
        return normalized;
    }

    public static Pattern measurementPattern() {
        return MEASUREMENT;
    }

    public static int measurementCount(String value) {
        var matcher = MEASUREMENT.matcher(normalizeMarkup(value));
        var count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static String replaceMathRoman(String source) {
        var matcher = MATH_ROMAN.matcher(source);
        var result = new StringBuffer();
        while (matcher.find()) {
            var compact = matcher.group(1).replaceAll("\\s+", "");
            matcher.appendReplacement(result, Matcher.quoteReplacement(compact));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String replaceLatexScripts(String source) {
        var matcher = LATEX_SCRIPT.matcher(source);
        var result = new StringBuffer();
        while (matcher.find()) {
            var operator = matcher.group().contains("^") ? "^" : "_";
            var replacement = operator + matcher.group(1).replaceAll("\\s+", "");
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String unicodeScripts(String source) {
        var superscript = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿ";
        var superscriptPlain = "0123456789+-=()n";
        var subscript = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₒₓₕₖₗₘₙₚₛₜ";
        var subscriptPlain = "0123456789+-=()aeoxhklmnpst";
        var result = new StringBuilder();
        Character mode = null;
        for (var index = 0; index < source.length(); index++) {
            var value = source.charAt(index);
            var mapped = superscript.indexOf(value);
            var nextMode = mapped >= 0 ? '^' : null;
            if (mapped < 0) {
                mapped = subscript.indexOf(value);
                if (mapped >= 0) nextMode = '_';
            }
            if (mapped < 0) {
                mode = null;
                result.append(value);
                continue;
            }
            if (!java.util.Objects.equals(mode, nextMode)) result.append(nextMode);
            mode = nextMode;
            result.append(nextMode == '^' ? superscriptPlain.charAt(mapped) : subscriptPlain.charAt(mapped));
        }
        return result.toString();
    }
}
