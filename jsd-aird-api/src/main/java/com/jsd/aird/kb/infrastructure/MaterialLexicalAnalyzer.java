package com.jsd.aird.kb.infrastructure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public static final String VERSION = "material-smartcn-v2";

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

    private Analysis analyze(String source) {
        var normalized = TechnicalTextNormalizer.normalizeMarkup(source);
        if (normalized.isBlank()) return new Analysis(Map.of(), 0);
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
        var frequencies = new LinkedHashMap<String, Integer>();
        var length = 0;
        for (var token : tokens) {
            var value = TechnicalTextNormalizer.canonicalToken(token.value());
            if (value.isBlank() || STOP_WORDS.contains(value) || singleHan(value)) continue;
            frequencies.merge(value, 1, Integer::sum);
            length++;
        }
        return new Analysis(frequencies, length);
    }

    private List<Range> protectedRanges(String text) {
        var candidates = new ArrayList<Range>();
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
            result.add(new Range(value.start(), value.end(), TechnicalTextNormalizer.canonicalToken(value.value())));
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

    private record Range(int start, int end, String value) { }
    private record LocatedToken(int offset, String value) { }
}
