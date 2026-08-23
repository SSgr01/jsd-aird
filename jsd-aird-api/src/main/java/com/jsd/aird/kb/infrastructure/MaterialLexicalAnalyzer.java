package com.jsd.aird.kb.infrastructure;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.jsd.aird.kb.domain.LexicalAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.springframework.stereotype.Component;

/** Chinese material-domain analyzer. Synonyms are deliberately query-only. */
@Component
public final class MaterialLexicalAnalyzer implements LexicalAnalyzer {

    public static final String VERSION = "material-smartcn-v1";

    private static final Set<String> TERMS = Set.of(
            "乙酸乙酯", "高官能度", "光引发剂", "紫外光固化", "热固性树脂", "聚氨酯丙烯酸酯",
            "环氧丙烯酸酯", "丙烯酸树脂", "固体含量", "玻璃化转变温度", "拉伸强度", "断裂伸长率",
            "粘度", "黏度", "涂布量", "固化能量", "表面张力", "耐化学性", "附着力", "官能度"
    );
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "和", "与", "或", "及", "在", "为", "是", "对", "中", "由", "将", "可", "该", "本",
            "一种", "进行", "具有", "通过", "以及", "其中", "然后", "分别", "如下"
    );
    private static final Set<String> SINGLE_CHARACTER_WHITELIST = Set.of(
            "酸", "酯", "醇", "胶", "膜", "光", "热", "水", "油", "树", "脂", "粉", "盐", "铜", "铝", "铁"
    );
    private static final Map<String, List<String>> QUERY_SYNONYMS = Map.ofEntries(
            Map.entry("粘度", List.of("黏度")), Map.entry("黏度", List.of("粘度")),
            Map.entry("uv", List.of("紫外", "紫外光固化")), Map.entry("紫外", List.of("uv", "紫外光固化")),
            Map.entry("固含", List.of("固体含量")), Map.entry("固体含量", List.of("固含")),
            Map.entry("拉力", List.of("拉伸强度")), Map.entry("延伸率", List.of("断裂伸长率"))
    );
    private static final Pattern PROTECTED = Pattern.compile(
            "(?iu)(?:[a-z]{1,12}(?:[-_/][a-z0-9]+)+|\\d+(?:\\.\\d+)?(?:[-~～—–]\\d+(?:\\.\\d+)?)?\\s*(?:mj/cm2|j/cm2|mj|j|cps|mpa|kpa|pa·s|mpa·s|°c|℃|%|wt%|rpm|nm|μm|um|mm|cm|kg|g)|(?:[a-z][a-z0-9]*\\d[a-z0-9]*))");

    private final SmartChineseAnalyzer smartCn = new SmartChineseAnalyzer();

    @Override
    public String version() { return VERSION; }

    @Override
    public Analysis analyzeDocument(String text) { return analyze(text, false); }

    @Override
    public Analysis analyzeQuery(String text) { return analyze(text, true); }

    private Analysis analyze(String source, boolean query) {
        var normalized = normalize(source);
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
            var value = token.value().strip();
            if (value.isBlank() || STOP_WORDS.contains(value)) continue;
            if (singleHan(value) && !SINGLE_CHARACTER_WHITELIST.contains(value)) continue;
            frequencies.merge(value, 1, Integer::sum);
            length++;
        }
        if (query) {
            var originalTerms = List.copyOf(frequencies.keySet());
            for (var value : originalTerms) {
                for (var synonym : QUERY_SYNONYMS.getOrDefault(value, List.of())) {
                    frequencies.putIfAbsent(synonym, 1);
                }
            }
        }
        return new Analysis(frequencies, length);
    }

    private List<Range> protectedRanges(String text) {
        var candidates = new ArrayList<Range>();
        for (var term : TERMS) {
            var start = 0;
            while ((start = text.indexOf(term, start)) >= 0) {
                candidates.add(new Range(start, start + term.length(), term));
                start += term.length();
            }
        }
        var matcher = PROTECTED.matcher(text);
        while (matcher.find()) candidates.add(new Range(matcher.start(), matcher.end(), compactUnit(matcher.group())));
        candidates.sort(Comparator.comparingInt(Range::start).thenComparing((left, right) ->
                Integer.compare(right.end() - right.start(), left.end() - left.start())));
        var result = new ArrayList<Range>();
        var occupied = new HashSet<Integer>();
        for (var value : candidates) {
            var overlaps = false;
            for (var index = value.start(); index < value.end(); index++) if (occupied.contains(index)) { overlaps = true; break; }
            if (overlaps) continue;
            result.add(value);
            for (var index = value.start(); index < value.end(); index++) occupied.add(index);
        }
        result.sort(Comparator.comparingInt(Range::start));
        return result;
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

    private String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('⁻', '-').replaceAll("\\s+", " ").strip();
    }

    private String compactUnit(String value) { return value.replaceAll("\\s+", ""); }

    private boolean singleHan(String value) {
        return value.codePointCount(0, value.length()) == 1
                && Character.UnicodeScript.of(value.codePointAt(0)) == Character.UnicodeScript.HAN;
    }

    private record Range(int start, int end, String value) { }
    private record LocatedToken(int offset, String value) { }
}
