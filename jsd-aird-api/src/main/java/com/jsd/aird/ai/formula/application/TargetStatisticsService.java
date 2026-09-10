package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade.TargetObservation;
import com.jsd.aird.ai.formula.application.SimilarCaseEngine.ScoredCase;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class TargetStatisticsService {
    private final ObjectMapper json;
    private final UvpuResearchProfile profile;

    public TargetStatisticsService(ObjectMapper json, UvpuResearchProfile profile) {
        this.json = json;
        this.profile = profile;
    }

    public Statistics calculate(String targetKey, List<ScoredCase> cases) {
        var target = profile.target(targetKey).orElseThrow();
        var exact = cases.stream().filter(item -> isExact(observation(item, targetKey))).toList();
        var lowerBounds = cases.stream().filter(item -> "LOWER_BOUND".equals(observation(item, targetKey).observationType())).toList();
        var comparableCount = cases.size();
        var level = level(comparableCount, exact.size());
        var confidence = comparableCount == 0 ? "NONE" : level.equals("FULL_STATISTICS") ? "MEDIUM" : "LOW";
        var summary = json.createObjectNode();
        summary.put("targetKey", targetKey).put("name", target.name()).put("valueType", target.valueType())
                .put("unit", target.unit()).put("caseCount", comparableCount).put("exactCount", exact.size())
                .put("lowerBoundCount", lowerBounds.size()).put("statisticsLevel", level)
                .put("confidence", confidence);
        if ("CONTINUOUS".equals(target.valueType())) continuous(summary, exact, targetKey, level);
        else if ("ORDINAL".equals(target.valueType())) ordinal(summary, exact, targetKey, level);
        else if ("BINARY".equals(target.valueType())) binary(summary, exact, targetKey, level);
        lowerBounds(summary.putArray("lowerBoundEvidence"), lowerBounds, targetKey);
        var evidence = evidence(cases, targetKey);
        summary.set("cases", evidence);
        summary.set("trend", trend(cases, targetKey));
        return new Statistics(targetKey, level, confidence, comparableCount, exact.size(), summary, evidence);
    }

    private void continuous(ObjectNode result, List<ScoredCase> exact, String key, String level) {
        var values = weightedNumeric(exact, key);
        if (values.isEmpty() || "CASES_ONLY".equals(level) || "NONE".equals(level)) return;
        result.put("minimum", values.stream().map(WeightedValue::value).min(BigDecimal::compareTo).orElseThrow());
        result.put("maximum", values.stream().map(WeightedValue::value).max(BigDecimal::compareTo).orElseThrow());
        if ("FULL_STATISTICS".equals(level)) {
            result.put("pointEstimate", weightedQuantile(values, 0.50));
            result.put("q25", weightedQuantile(values, 0.25));
            result.put("q75", weightedQuantile(values, 0.75));
        }
    }

    private void ordinal(ObjectNode result, List<ScoredCase> exact, String key, String level) {
        var distribution = new LinkedHashMap<String, Integer>();
        var values = new ArrayList<WeightedValue>();
        for (var item : exact) {
            var observation = observation(item, key);
            var grade = grade(observation.ordinalValue());
            if (grade == null) continue;
            distribution.merge(observation.ordinalValue(), 1, Integer::sum);
            values.add(new WeightedValue(grade, weight(item)));
        }
        if (!distribution.isEmpty() && !"CASES_ONLY".equals(level) && !"NONE".equals(level)) {
            result.set("distribution", json.valueToTree(distribution));
        }
        if ("FULL_STATISTICS".equals(level) && !values.isEmpty()) {
            result.put("pointEstimate", formatGrade(weightedQuantile(values, 0.50), key));
        }
    }

    private void binary(ObjectNode result, List<ScoredCase> exact, String key, String level) {
        if ("CASES_ONLY".equals(level) || "NONE".equals(level)) return;
        var positiveWeight = 0d;
        var totalWeight = 0d;
        var positive = 0;
        for (var item : exact) {
            var value = observation(item, key).ordinalValue();
            var current = weight(item);
            totalWeight += current;
            if (isPositive(value)) { positiveWeight += current; positive++; }
        }
        result.put("positiveCount", positive).put("negativeCount", exact.size() - positive);
        if ("FULL_STATISTICS".equals(level) && totalWeight > 0) {
            result.put("positiveRate", positiveWeight / totalWeight);
            result.put("pointEstimate", positiveWeight / totalWeight >= 0.5 ? "OK" : "NOT_OK");
        }
    }

    private ObjectNode trend(List<ScoredCase> cases, String targetKey) {
        var result = json.createObjectNode();
        result.put("available", false);
        result.put("reason", cases.size() < 3 ? "INSUFFICIENT_INDEPENDENT_PAIRS" : "NO_CONTROLLED_SINGLE_FACTOR_PAIRS");
        result.put("minimumPairCount", 3);
        result.put("requiredDirectionConsistency", 2d / 3d);
        return result;
    }

    private ArrayNode evidence(List<ScoredCase> cases, String targetKey) {
        var result = json.createArrayNode();
        for (var item : cases) {
            var row = item.view().analysisRow();
            var target = observation(item, targetKey);
            var node = result.addObject();
            node.put("analysisRowId", row.analysisRowId()).put("experimentId", row.experimentId().toString())
                    .put("experimentVersionId", row.experimentVersionId().toString())
                    .put("experimentNo", row.experimentNo()).put("title", item.view().title())
                    .put("sourceIdentity", row.sourceIdentity()).put("similarity", item.similarity())
                    .put("formulaSimilarity", item.formulaSimilarity()).put("processSimilarity", item.processSimilarity())
                    .put("contextSimilarity", item.contextSimilarity()).put("observationType", target.observationType())
                    .put("rawValue", target.rawValue()).put("unit", target.standardUnit());
            if (target.numericValue() != null) node.put("numericValue", target.numericValue());
            if (target.ordinalValue() != null) node.put("ordinalValue", target.ordinalValue());
            if (item.view().experimentDate() != null) node.put("experimentDate", item.view().experimentDate().toString());
        }
        return result;
    }

    private void lowerBounds(ArrayNode result, List<ScoredCase> items, String key) {
        for (var item : items) {
            var target = observation(item, key);
            var node = result.addObject().put("analysisRowId", item.view().analysisRow().analysisRowId())
                    .put("rawValue", target.rawValue());
            if (target.numericValue() != null) node.put("minimum", target.numericValue());
        }
    }

    private String level(int all, int exact) {
        if (all == 0) return "NONE";
        if (exact >= 5) return "FULL_STATISTICS";
        if (exact >= 3) return "SIMPLE_RANGE";
        return "CASES_ONLY";
    }

    private List<WeightedValue> weightedNumeric(List<ScoredCase> cases, String key) {
        return cases.stream().map(item -> new WeightedValue(observation(item, key).numericValue(), weight(item)))
                .filter(item -> item.value() != null).sorted(Comparator.comparing(WeightedValue::value)).toList();
    }

    private BigDecimal weightedQuantile(List<WeightedValue> values, double quantile) {
        var total = values.stream().mapToDouble(WeightedValue::weight).sum();
        var threshold = total * quantile;
        var current = 0d;
        for (var item : values) {
            current += item.weight();
            if (current >= threshold) return item.value().stripTrailingZeros();
        }
        return values.getLast().value().stripTrailingZeros();
    }

    private boolean isExact(TargetObservation target) {
        return target != null && "EXACT".equals(target.observationType());
    }

    private TargetObservation observation(ScoredCase item, String key) {
        return item.view().analysisRow().targets().get(key);
    }

    private double weight(ScoredCase item) { return Math.max(0.000001d, item.similarity() * item.similarity()); }

    private BigDecimal grade(String value) {
        if (value == null) return null;
        var normalized = value.toUpperCase(Locale.ROOT).strip();
        if (normalized.equals("H")) return BigDecimal.ONE;
        if (normalized.matches("[0-5]B") || normalized.matches("\\d+(?:\\.\\d+)?H")) {
            return new BigDecimal(normalized.substring(0, normalized.length() - 1));
        }
        return null;
    }

    private String formatGrade(BigDecimal value, String targetKey) {
        return value.stripTrailingZeros().toPlainString() + (targetKey.contains("ADHESION") ? "B" : "H");
    }

    private boolean isPositive(String value) {
        if (value == null) return false;
        return Set.of("OK", "PASS", "TRUE", "合格", "表干", "干爽").contains(value.toUpperCase(Locale.ROOT));
    }

    private record WeightedValue(BigDecimal value, double weight) {}

    public record Statistics(String targetKey, String level, String confidence, int caseCount, int exactCount,
                             ObjectNode summary, ArrayNode evidence) {}
}
