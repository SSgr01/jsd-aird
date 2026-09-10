package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade;
import com.jsd.aird.ai.formula.api.FormulaResearchFacade.Constraints;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class FormulaRuleEngine {
    private static final BigDecimal TOTAL = new BigDecimal("100");
    private static final BigDecimal TOTAL_TOLERANCE = new BigDecimal("0.02");
    private static final BigDecimal FIXED_TOLERANCE = new BigDecimal("0.001");
    private final ObjectMapper json;

    public FormulaRuleEngine(ObjectMapper json) { this.json = json; }

    public RuleResult check(List<ExperimentAnalysisFacade.FormulaComponent> formula,
                            Map<String, ExperimentAnalysisFacade.StandardFact> process,
                            Constraints constraints) {
        var values = new HashMap<String, BigDecimal>();
        for (var item : formula) {
            if (item.ratioPercent() != null && item.materialCode() != null && !item.materialCode().isBlank()) {
                values.merge(code(item.materialCode()), item.ratioPercent(), BigDecimal::add);
            }
        }
        var checks = json.createArrayNode();
        var violations = json.createArrayNode();
        var total = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        check(checks, violations, "FORMULA_TOTAL", total.subtract(TOTAL).abs().compareTo(TOTAL_TOLERANCE) <= 0,
                "配方合计必须为100±0.02", total.toPlainString());
        var explicitMainResins = formula.stream().filter(item -> "MAIN_RESIN".equals(item.materialRole()))
                .filter(item -> item.ratioPercent() != null && item.ratioPercent().signum() > 0)
                .map(item -> code(item.materialCode())).distinct().toList();
        if (formula.stream().anyMatch(item -> "MAIN_RESIN".equals(item.materialRole()))) {
            check(checks, violations, "SINGLE_MAIN_RESIN", explicitMainResins.size() == 1,
                    "UV/PU候选必须且只能包含一种主体树脂", String.join(",", explicitMainResins));
        }
        for (var material : constraints.requiredMaterials()) {
            var value = values.getOrDefault(code(material), BigDecimal.ZERO);
            check(checks, violations, "REQUIRED_MATERIAL", value.signum() > 0,
                    "必选材料未加入：" + material, material);
        }
        for (var material : constraints.forbiddenMaterials()) {
            var value = values.getOrDefault(code(material), BigDecimal.ZERO);
            check(checks, violations, "FORBIDDEN_MATERIAL", value.signum() == 0,
                    "候选包含禁用材料：" + material, material);
        }
        constraints.fixedMaterials().forEach((material, expected) -> {
            var actual = values.getOrDefault(code(material), BigDecimal.ZERO);
            check(checks, violations, "FIXED_MATERIAL", actual.subtract(expected).abs().compareTo(FIXED_TOLERANCE) <= 0,
                    "固定材料比例不符合要求：" + material, actual.toPlainString());
        });
        constraints.materialRanges().forEach((material, range) -> {
            var actual = values.getOrDefault(code(material), BigDecimal.ZERO);
            var valid = (range.minimum() == null || actual.compareTo(range.minimum()) >= 0)
                    && (range.maximum() == null || actual.compareTo(range.maximum()) <= 0);
            check(checks, violations, "MATERIAL_RANGE", valid,
                    "材料比例超出允许范围：" + material, actual.toPlainString());
        });
        for (var combination : constraints.forbiddenMaterialCombinations()) {
            var normalized = combination.stream().filter(item -> item != null && !item.isBlank())
                    .map(this::code).distinct().toList();
            if (normalized.size() < 2) continue;
            var present = normalized.stream().allMatch(material ->
                    values.getOrDefault(material, BigDecimal.ZERO).signum() > 0);
            check(checks, violations, "FORBIDDEN_MATERIAL_COMBINATION", !present,
                    "候选包含禁忌材料组合：" + String.join(" + ", normalized),
                    String.join(",", normalized));
        }
        if (constraints.maxMaterialCount() != null) {
            var count = values.values().stream().filter(value -> value.signum() > 0).count();
            check(checks, violations, "MAX_MATERIAL_COUNT", count <= constraints.maxMaterialCount(),
                    "材料数量超过上限", Long.toString(count));
        }
        constraints.processRanges().forEach((code, range) -> {
            var fact = process.get(code);
            var valid = fact != null && fact.numericValue() != null
                    && (range.minimum() == null || fact.numericValue().compareTo(range.minimum()) >= 0)
                    && (range.maximum() == null || fact.numericValue().compareTo(range.maximum()) <= 0);
            check(checks, violations, "PROCESS_RANGE", valid,
                    "工艺参数缺失或超出范围：" + code,
                    fact == null || fact.numericValue() == null ? "" : fact.numericValue().toPlainString());
        });
        var cost = constraints.requireCostCheck() != null && constraints.requireCostCheck();
        var inventory = constraints.requireInventoryCheck() != null && constraints.requireInventoryCheck();
        external(checks, violations, "COST", cost, "当前候选尚未配置可核验的成本数据");
        external(checks, violations, "INVENTORY", inventory, "当前候选尚未配置可核验的库存数据");
        var summary = json.createObjectNode().put("eligible", violations.isEmpty())
                .put("ruleVersion", "T06_FORMULA_RULE.v1");
        summary.set("checks", checks);
        summary.set("violations", violations);
        return new RuleResult(violations.isEmpty(), summary);
    }

    private void check(ArrayNode checks, ArrayNode violations, String code, boolean passed,
                       String message, String actual) {
        var item = checks.addObject().put("code", code).put("status", passed ? "PASSED" : "FAILED")
                .put("message", message).put("actual", actual);
        if (!passed) violations.add(item.deepCopy());
    }

    private void external(ArrayNode checks, ArrayNode violations, String code, boolean required, String message) {
        var item = checks.addObject().put("code", code).put("status", "NOT_VERIFIED").put("message", message);
        if (required) violations.add(item.deepCopy().put("status", "FAILED"));
    }

    private String code(String value) { return value == null ? "" : value.strip().toUpperCase(Locale.ROOT); }

    public record RuleResult(boolean eligible, ObjectNode detail) {}
}
