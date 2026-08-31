package com.jsd.aird.kb.infrastructure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.jsd.aird.kb.domain.LexicalAnalyzer;
import com.jsd.aird.kb.domain.TechnicalTextNormalizer;
import jakarta.annotation.PostConstruct;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.springframework.stereotype.Component;

/**
 * Generic SmartCN analyzer. Domain terminology is supplied dynamically by the
 * query planner; this class only protects cross-domain technical token shapes.
 */
@Component
public final class MaterialLexicalAnalyzer implements LexicalAnalyzer {

    public static final String VERSION = "material-smartcn-v3";
    public static final String LEGACY_VERSION = "material-smartcn-v2";

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "和", "与", "或", "及", "在", "为", "是", "对", "中", "由", "将", "可", "该", "本",
            "一种", "进行", "具有", "通过", "以及", "其中", "然后", "分别", "如下",
            "the", "a", "an", "and", "or", "of", "to", "in", "for", "with", "by", "is", "are"
    );
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d])[\\p{L}]{1,16}(?:[-_/][\\p{L}\\d]+)+(?![\\p{L}\\d])");
    private static final Pattern ALPHANUMERIC = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d])[a-z][a-z0-9]*\\d[a-z0-9-]*(?![\\p{L}\\d])");
    private static final Pattern CHEMICAL_FORMULA = Pattern.compile(
            "(?<![A-Za-z0-9])(?:[A-Z][a-z]?\\d*){2,}(?![a-z])");
    private static final Pattern CHEMICAL_FORMULA_WHOLE = Pattern.compile("(?:[A-Z][a-z]?\\d*){2,}");
    private static final Pattern ELEMENT = Pattern.compile("([A-Z][a-z]?)(\\d*)");
    private static final Pattern SCRIPTED = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d])[\\p{L}][\\p{L}\\d]*(?:[_^][+\\-]?[\\p{L}\\d]+)+(?![\\p{L}\\d])");
    private static final Pattern ABBREVIATION = Pattern.compile(
            "(?<![A-Za-z0-9])[A-Z]{2,12}(?:-\\d+)?(?![A-Za-z0-9])");

    private final SmartChineseAnalyzer smartCn = new SmartChineseAnalyzer();

    @PostConstruct
    void warmUp() {
        analyze("文档检索 warmup CaCl2 1 mg/mL");
    }

    @Override
    public String version() { return VERSION; }

    @Override
    public Analysis analyzeDocument(String text) { return analyze(text); }

    @Override
    public Analysis analyzeQuery(String text) { return analyze(text); }

    @Override
    public List<QueryTermFamily> analyzeCompatibleQuery(String text) {
        var families = new ArrayList<java.util.LinkedHashSet<QueryAlternative>>();
        for (var token : significantTokens(text, false)) {
            var canonical = TechnicalTextNormalizer.canonicalToken(token.value());
            if (canonical.isBlank()) continue;
            var alternatives = new java.util.LinkedHashSet<QueryAlternative>();
            alternatives.add(new QueryAlternative(VERSION, canonical));
            compatibilityAliases(token.value(), canonical).forEach(alias ->
                    alternatives.add(new QueryAlternative(VERSION, alias)));
            mergeOverlappingFamily(families, alternatives);
        }
        for (var token : significantTokens(text, true)) {
            var legacy = TechnicalTextNormalizer.canonicalToken(token.value());
            if (legacy.isBlank()) continue;
            var target = families.stream()
                    .filter(family -> family.stream().anyMatch(value -> value.term().equals(legacy)))
                    .findFirst().orElseGet(() -> {
                        var family = new java.util.LinkedHashSet<QueryAlternative>();
                        families.add(family);
                        return family;
                    });
            target.add(new QueryAlternative(LEGACY_VERSION, legacy));
        }
        var result = new ArrayList<QueryTermFamily>();
        var ordinal = 0;
        for (var alternatives : families) {
            result.add(new QueryTermFamily(ordinal++, List.copyOf(alternatives)));
        }
        return List.copyOf(result);
    }

    private void mergeOverlappingFamily(List<java.util.LinkedHashSet<QueryAlternative>> families,
                                        java.util.LinkedHashSet<QueryAlternative> incoming) {
        java.util.LinkedHashSet<QueryAlternative> target = null;
        for (var iterator = families.iterator(); iterator.hasNext();) {
            var existing = iterator.next();
            if (java.util.Collections.disjoint(existing, incoming)) continue;
            if (target == null) {
                target = existing;
                target.addAll(incoming);
            } else {
                target.addAll(existing);
                iterator.remove();
            }
        }
        if (target == null) families.add(incoming);
    }

    private Analysis analyze(String source) {
        var frequencies = new LinkedHashMap<String, Integer>();
        var length = 0;
        for (var token : significantTokens(source, false)) {
            var value = TechnicalTextNormalizer.canonicalToken(token.value());
            frequencies.merge(value, 1, Integer::sum);
            length++;
        }
        return new Analysis(frequencies, length);
    }

    private List<LocatedToken> significantTokens(String source, boolean legacy) {
        var normalized = TechnicalTextNormalizer.normalizeMarkup(source);
        if (legacy) normalized = normalized.replace("_", "").replace("^", "");
        if (normalized.isBlank()) return List.of();
        var protectedRanges = protectedRanges(normalized);
        var tokens = new ArrayList<LocatedToken>();
        for (var range : protectedRanges) tokens.add(new LocatedToken(range.start(), range.value()));
        var cursor = 0;
        for (var range : protectedRanges) {
            if (cursor < range.start()) addSmartTokens(normalized.substring(cursor, range.start()), cursor, tokens);
            cursor = Math.max(cursor, range.end());
        }
        if (cursor < normalized.length()) addSmartTokens(normalized.substring(cursor), cursor, tokens);
        tokens.sort(Comparator.comparingInt(LocatedToken::offset));
        return tokens.stream().filter(token -> {
            var value = TechnicalTextNormalizer.canonicalToken(token.value());
            return !value.isBlank() && !STOP_WORDS.contains(value) && !singleHan(value);
        }).toList();
    }

    private List<Range> protectedRanges(String text) {
        var candidates = new ArrayList<Range>();
        collect(candidates, SCRIPTED, text);
        collect(candidates, TechnicalTextNormalizer.measurementPattern(), text);
        collect(candidates, IDENTIFIER, text);
        collect(candidates, ALPHANUMERIC, text);
        collect(candidates, CHEMICAL_FORMULA, text);
        collect(candidates, ABBREVIATION, text);
        candidates.sort(Comparator.comparingInt(Range::start).thenComparing((left, right) ->
                Integer.compare(right.end() - right.start(), left.end() - left.start())));
        var result = new ArrayList<Range>();
        var occupied = new HashSet<Integer>();
        for (var value : candidates) {
            var overlaps = false;
            for (var index = value.start(); index < value.end(); index++) {
                if (occupied.contains(index)) { overlaps = true; break; }
            }
            if (overlaps) continue;
            result.add(new Range(value.start(), value.end(), value.value()));
            for (var index = value.start(); index < value.end(); index++) occupied.add(index);
        }
        result.sort(Comparator.comparingInt(Range::start));
        return result;
    }

    private void collect(List<Range> values, Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        while (matcher.find()) values.add(new Range(matcher.start(), matcher.end(), matcher.group()));
    }

    private void addSmartTokens(String text, int baseOffset, List<LocatedToken> output) {
        if (text.isBlank()) return;
        try (var stream = smartCn.tokenStream("content", text)) {
            var term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            var ordinal = 0;
            while (stream.incrementToken()) output.add(new LocatedToken(baseOffset + ordinal++, term.toString()));
            stream.end();
        } catch (IOException exception) {
            throw new IllegalStateException("SmartCN 中文分析失败", exception);
        }
    }

    private boolean singleHan(String value) {
        return value.codePointCount(0, value.length()) == 1
                && Character.UnicodeScript.of(value.codePointAt(0)) == Character.UnicodeScript.HAN;
    }

    private List<String> compatibilityAliases(String raw, String canonical) {
        var result = new java.util.LinkedHashSet<String>();
        if (canonical.indexOf('_') >= 0 || canonical.indexOf('^') >= 0) {
            var plain = canonical.replace("_", "").replace("^", "");
            if (!plain.equals(canonical)) result.add(plain);
        }
        var compactRaw = raw.replaceAll("\\s+", "");
        if (CHEMICAL_FORMULA_WHOLE.matcher(compactRaw).matches() && compactRaw.chars().anyMatch(Character::isDigit)) {
            var matcher = ELEMENT.matcher(compactRaw);
            var scripted = new StringBuilder();
            while (matcher.find()) {
                scripted.append(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
                if (!matcher.group(2).isEmpty()) scripted.append('_').append(matcher.group(2));
            }
            if (!scripted.isEmpty() && !scripted.toString().equals(canonical)) result.add(scripted.toString());
        }
        return List.copyOf(result);
    }

    private record Range(int start, int end, String value) { }
    private record LocatedToken(int offset, String value) { }
}
