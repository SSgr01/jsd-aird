package com.jsd.aird.ai.formula.application;

import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade;
import com.jsd.aird.ai.formula.application.ResearchCaseLoader.ResearchCaseView;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SimilarCaseEngine {
    static final int MAX_CASES = 20;

    public List<ScoredCase> find(List<ResearchCaseView> source, Anchor anchor, String targetKey,
                                 Map<String, Object> requiredContext, Set<String> excludedLeakageKeys,
                                 String excludedAnalysisRowId) {
        return source.stream()
                .filter(item -> eligible(item.analysisRow(), targetKey))
                .filter(item -> excludedAnalysisRowId == null
                        || !excludedAnalysisRowId.equals(item.analysisRow().analysisRowId()))
                .filter(item -> excludedLeakageKeys == null || excludedLeakageKeys.isEmpty()
                        || item.analysisRow().leakageGroupKeys().stream().noneMatch(excludedLeakageKeys::contains))
                .filter(item -> matchesRequiredContext(item.analysisRow(), requiredContext))
                .filter(item -> compatibleWithAnchor(item.analysisRow(), anchor))
                .map(item -> score(item, anchor))
                .sorted(Comparator.comparingDouble(ScoredCase::similarity).reversed()
                        .thenComparing(Comparator.comparingInt(ScoredCase::completeness).reversed())
                        .thenComparing(item -> item.view().experimentDate(), Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(item -> item.view().analysisRow().analysisRowId()))
                .limit(MAX_CASES)
                .toList();
    }

    public Anchor anchor(ResearchCaseView view) {
        var row = view.analysisRow();
        return new Anchor(row.formula(), row.process(), row.context());
    }

    public Anchor anchor(List<ExperimentAnalysisFacade.FormulaComponent> formula,
                         Map<String, ExperimentAnalysisFacade.StandardFact> process,
                         Map<String, ExperimentAnalysisFacade.StandardFact> context) {
        return new Anchor(formula, process, context);
    }

    public boolean matchesContext(ResearchCaseView view, Map<String, Object> requiredContext) {
        return matchesRequiredContext(view.analysisRow(), requiredContext);
    }

    public double formulaL1(List<ExperimentAnalysisFacade.FormulaComponent> left,
                            List<ExperimentAnalysisFacade.FormulaComponent> right) {
        var a = formula(left);
        var b = formula(right);
        var keys = new java.util.HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys.stream().mapToDouble(key -> Math.abs(a.getOrDefault(key, BigDecimal.ZERO)
                .subtract(b.getOrDefault(key, BigDecimal.ZERO)).doubleValue())).sum();
    }

    private ScoredCase score(ResearchCaseView view, Anchor anchor) {
        var row = view.analysisRow();
        var formula = 1d - Math.min(formulaL1(anchor.formula(), row.formula()) / 200d, 1d);
        var process = factsScore(anchor.process(), row.process());
        var context = factsScore(anchor.context(), row.context());
        var total = formula * 0.60d + process * 0.25d + context * 0.15d;
        return new ScoredCase(view, total, formula, process, context, completeness(row));
    }

    private boolean compatibleWithAnchor(ExperimentAnalysisFacade.AnalysisRow row, Anchor anchor) {
        for (var key : List.of("substrate", "applicationMethod", "curingSource")) {
            var expected = anchor.context().get(key);
            var actual = row.context().get(key);
            if (expected != null && actual != null && !factEquals(expected, actual)) return false;
        }
        return true;
    }

    private boolean matchesRequiredContext(ExperimentAnalysisFacade.AnalysisRow row,
                                           Map<String, Object> required) {
        if (required == null || required.isEmpty()) return true;
        for (var entry : required.entrySet()) {
            var fact = row.context().get(entry.getKey());
            if (fact == null) fact = row.process().get(entry.getKey());
            if (fact == null || !matches(entry.getKey(), fact, entry.getValue())) return false;
        }
        return true;
    }

    private boolean matches(String key, ExperimentAnalysisFacade.StandardFact fact, Object expected) {
        if (expected == null) return true;
        if (expected instanceof Number number && fact.numericValue() != null) {
            return fact.numericValue().compareTo(new BigDecimal(number.toString())) == 0;
        }
        var actual = fact.textValue() != null ? fact.textValue()
                : fact.numericValue() == null ? "" : fact.numericValue().stripTrailingZeros().toPlainString();
        var expectedText = String.valueOf(expected).strip();
        if (actual.equalsIgnoreCase(expectedText)) return true;
        return "substrate".equals(key) && "PET".equalsIgnoreCase(expectedText)
                && actual.toUpperCase(java.util.Locale.ROOT).startsWith("PET_");
    }

    private double factsScore(Map<String, ExperimentAnalysisFacade.StandardFact> expected,
                              Map<String, ExperimentAnalysisFacade.StandardFact> actual) {
        if (expected == null || expected.isEmpty()) return 1d;
        double sum = 0d;
        var count = 0;
        for (var entry : expected.entrySet()) {
            var right = actual.get(entry.getKey());
            count++;
            if (right == null) continue;
            var left = entry.getValue();
            if (left.numericValue() != null && right.numericValue() != null) {
                var scale = Math.max(1d, Math.max(Math.abs(left.numericValue().doubleValue()),
                        Math.abs(right.numericValue().doubleValue())));
                sum += 1d - Math.min(Math.abs(left.numericValue().subtract(right.numericValue(),
                        MathContext.DECIMAL64).doubleValue()) / scale, 1d);
            } else if (factEquals(left, right)) sum += 1d;
        }
        return count == 0 ? 1d : sum / count;
    }

    private boolean factEquals(ExperimentAnalysisFacade.StandardFact left,
                               ExperimentAnalysisFacade.StandardFact right) {
        if (left.numericValue() != null && right.numericValue() != null) {
            return left.numericValue().compareTo(right.numericValue()) == 0;
        }
        return left.textValue() != null && right.textValue() != null
                && left.textValue().equalsIgnoreCase(right.textValue());
    }

    private Map<String, BigDecimal> formula(List<ExperimentAnalysisFacade.FormulaComponent> items) {
        var result = new HashMap<String, BigDecimal>();
        if (items == null) return result;
        for (var item : items) {
            if (item.materialCode() == null || item.materialCode().isBlank() || item.ratioPercent() == null) continue;
            result.merge(item.materialCode(), item.ratioPercent(), BigDecimal::add);
        }
        return result;
    }

    private int completeness(ExperimentAnalysisFacade.AnalysisRow row) {
        var count = (int) row.formula().stream().filter(item -> item.ratioPercent() != null).count();
        count += (int) row.process().values().stream().filter(this::present).count();
        count += (int) row.context().values().stream().filter(this::present).count();
        count += (int) row.targets().values().stream().filter(item -> "PARSED".equals(item.status())).count();
        return count;
    }

    private boolean present(ExperimentAnalysisFacade.StandardFact value) {
        return value.numericValue() != null || value.textValue() != null;
    }

    private boolean eligible(ExperimentAnalysisFacade.AnalysisRow row, String targetKey) {
        var eligibility = row.caseEligibilityByTarget().get(targetKey);
        var target = row.targets().get(targetKey);
        return eligibility != null && eligibility.eligible() && target != null && "PARSED".equals(target.status());
    }

    public record Anchor(List<ExperimentAnalysisFacade.FormulaComponent> formula,
                         Map<String, ExperimentAnalysisFacade.StandardFact> process,
                         Map<String, ExperimentAnalysisFacade.StandardFact> context) {
        public Anchor {
            formula = formula == null ? List.of() : List.copyOf(formula);
            process = process == null ? Map.of() : Map.copyOf(process);
            context = context == null ? Map.of() : Map.copyOf(context);
        }
    }

    public record ScoredCase(ResearchCaseView view, double similarity, double formulaSimilarity,
                             double processSimilarity, double contextSimilarity, int completeness) {}
}
