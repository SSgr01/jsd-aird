package com.jsd.aird.ai.rnd.formula;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * The single formula policy used by performance prediction, formula design and
 * experiment optimization. Raw experiment facts are never changed; the
 * returned formula is an analysis copy with an explicit normalization audit.
 */
@Service
public class FormulaNormalizationService {
    public static final String RULE_VERSION = "SUM_TO_100_V1";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ObjectMapper json;

    public FormulaNormalizationService(ObjectMapper json) {
        this.json = json;
    }

    public Result normalize(JsonNode input) {
        if (input == null || !input.isObject()) {
            return invalid("FORMULA_INCOMPLETE", "配方组成为空，请先填写材料和比例");
        }
        var components = input.path("components");
        if (!components.isArray() || components.isEmpty()) {
            return invalid("FORMULA_INCOMPLETE", "配方中至少需要一种材料");
        }

        var issues = new ArrayList<Issue>();
        var rawTotal = BigDecimal.ZERO;
        for (int i = 0; i < components.size(); i++) {
            var component = components.get(i);
            var material = component.path("materialId").asText("").strip();
            if (material.isBlank()) {
                issues.add(new Issue("FORMULA_MATERIAL_MISSING", "第" + (i + 1) + "项材料未选择", i));
                continue;
            }
            var ratio = component.get("ratio");
            if (ratio == null || ratio.isNull() || !component.path("amountKnown").asBoolean(ratio.isNumber())) {
                issues.add(new Issue("FORMULA_RATIO_MISSING", "第" + (i + 1) + "项材料比例为空；如明确未添加请填0，空白表示未知", i));
                continue;
            }
            if (!ratio.isNumber() || !Double.isFinite(ratio.asDouble())) {
                issues.add(new Issue("FORMULA_RATIO_NOT_NUMERIC", "第" + (i + 1) + "项材料比例必须是数字", i));
                continue;
            }
            var value = ratio.decimalValue();
            if (value.signum() < 0) {
                issues.add(new Issue("FORMULA_RATIO_NEGATIVE", "第" + (i + 1) + "项材料比例不能为负数", i));
                continue;
            }
            rawTotal = rawTotal.add(value);
        }
        if (!issues.isEmpty()) return invalid(issues);
        if (rawTotal.signum() <= 0) {
            return invalid("FORMULA_TOTAL_NON_POSITIVE", "配方合计必须大于0，当前无法归一化");
        }

        if ("ABSOLUTE_MASS".equals(input.path("basis").asText())) {
            var mass = (ObjectNode) input.deepCopy();
            mass.put("recordedTotal", rawTotal);
            mass.put("normalizationApplied", false);
            mass.put("normalizationFactor", BigDecimal.ONE);
            return new Result(mass, rawTotal, rawTotal, false, BigDecimal.ONE, List.of());
        }

        var factor = HUNDRED.divide(rawTotal, MathContext.DECIMAL128);
        var normalized = (ObjectNode) input.deepCopy();
        var normalizedComponents = normalized.withArray("components");
        for (int i = 0; i < normalizedComponents.size(); i++) {
            var component = normalizedComponents.get(i);
            if (!(component instanceof ObjectNode object)) continue;
            var raw = component.path("ratio").decimalValue();
            object.put("rawRatio", raw);
            object.put("ratio", raw.multiply(factor, MathContext.DECIMAL128).stripTrailingZeros());
            object.put("amountKnown", true);
        }
        normalized.put("rawFormulaTotal", rawTotal);
        normalized.put("normalizedFormulaTotal", HUNDRED);
        normalized.put("recordedTotal", HUNDRED);
        normalized.put("normalizationApplied", rawTotal.compareTo(HUNDRED) != 0);
        normalized.put("normalizationFactor", factor);
        normalized.put("normalizationRuleVersion", RULE_VERSION);
        return new Result(normalized, rawTotal, HUNDRED, rawTotal.compareTo(HUNDRED) != 0, factor, List.of());
    }

    public Result requireValid(JsonNode input) {
        var result = normalize(input);
        if (!result.valid()) {
            var issue = result.issues().isEmpty()
                    ? new Issue(result.errorCode(), result.message(), -1)
                    : result.issues().get(0);
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, issue.message());
        }
        return result;
    }

    /**
     * Build the strict formula payload accepted by formula-model.v2.
     *
     * The analysis formula intentionally carries audit fields such as the raw
     * total and normalization factor.  Those fields are useful to the UI and
     * to the persisted request, but they are not part of the Python scoring
     * contract (which rejects unknown JSON properties).  Keep this conversion
     * in one place so prediction, optimization and formula design cannot drift.
     */
    public ObjectNode forModelCompute(JsonNode input) {
        var output = json.createObjectNode();
        if (input == null || !input.isObject()) return output;
        var basis = input.path("basis").asText("MASS_PERCENT");
        output.put("basis", "PERCENT_SUM_100".equals(basis) ? "MASS_PERCENT" : basis);
        output.put("compositionComplete", input.path("compositionComplete").asBoolean(true));
        if (input.has("recordedTotal") && input.path("recordedTotal").isNumber()) {
            output.set("recordedTotal", input.path("recordedTotal").deepCopy());
        }
        var components = output.putArray("components");
        var source = input.path("components");
        if (!source.isArray()) source = input.path("items");
        if (source.isArray()) {
            for (var item : source) {
                if (!item.isObject()) continue;
                var component = components.addObject();
                component.put("materialId", item.path("materialId").asText(item.path("materialCode").asText()));
                if (item.has("ratio") && item.path("ratio").isNumber()) {
                    component.set("ratio", item.path("ratio").deepCopy());
                }
                component.put("unit", item.path("unit").asText("PERCENT"));
                component.put("amountKnown", item.path("amountKnown").asBoolean(item.has("ratio") && !item.path("ratio").isNull()));
            }
        }
        return output;
    }

    private Result invalid(String code, String message) {
        return invalid(List.of(new Issue(code, message, -1)));
    }

    private Result invalid(List<Issue> issues) {
        var issue = issues.get(0);
        return new Result(json.createObjectNode(), null, null, false, null, issues);
    }

    public record Issue(String code, String message, int componentIndex) { }

    public record Result(ObjectNode formula, BigDecimal rawTotal, BigDecimal normalizedTotal,
                         boolean normalizationApplied, BigDecimal normalizationFactor,
                         List<Issue> issues) {
        public boolean valid() { return issues == null || issues.isEmpty(); }
        public String errorCode() { return valid() ? null : issues.get(0).code(); }
        public String message() { return valid() ? null : issues.get(0).message(); }
    }
}
