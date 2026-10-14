package com.jsd.aird.spc.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class SpectrumKnowledgeRuleRegistry {

    private static final Pattern NUMERIC_RANGE_VALUE = Pattern.compile("\\d+(?:\\.\\d+)?");

    private final Map<String, KnowledgeRule> rules;

    public SpectrumKnowledgeRuleRegistry() {
        var values = List.of(
                new KnowledgeRule("UV_METHOD_001", "UV",
                        "当报告吸光度达到较高水平时，应关注当前条件下的定量可靠性，并结合仪器方法、标准曲线和实验室规范判断。",
                        List.of("METHOD_ASSESSMENT", "DATA_QUALITY_ASSESSMENT"), "L2",
                        List.of("CONCENTRATION_CONFIRMATION", "PURITY_CONFIRMATION", "IDENTITY_CONFIRMATION"),
                        new Applicability(List.of("CONCENTRATION_CHECK", "METHOD_VALIDATION", "SAMPLE_COMPARISON", "OVERVIEW"),
                                List.of("UV"), true, List.of("OBSERVATION"), List.of(
                                new Predicate("UV_ABSORBANCE_POINT", "absorbance.value", "GTE", 2.0, "AU")))),
                existsRule("IR_METHOD_001", "IR", "红外峰位和强度解释需要结合测试模式、分辨率和样品制备条件。",
                        "IR_PEAK", List.of("PEAK_INTERPRETATION", "IDENTIFICATION", "OVERVIEW")),
                existsRule("HPLC_METHOD_001", "HPLC", "色谱峰面积和保留时间的解释需要结合色谱柱、流动相、检测器及方法适用性。",
                        "CHROMATOGRAPHY_PEAK", List.of("PURITY_CHECK", "METHOD_VALIDATION", "OVERVIEW")),
                existsRule("GPC_METHOD_001", "GPC", "分子量和分布判断需要结合校准体系、检测器和积分范围。",
                        "MOLECULAR_WEIGHT_RESULT", List.of("METHOD_VALIDATION", "OVERVIEW")),
                existsRule("GC_METHOD_001", "GC", "气相峰面积和保留时间的解释需要结合色谱柱、程序升温和响应因子。",
                        "CHROMATOGRAPHY_PEAK", List.of("PURITY_CHECK", "METHOD_VALIDATION", "OVERVIEW")),
                existsRule("PARTICLE_METHOD_001", "PARTICLE_SIZE", "粒径分布判断需要结合分散条件、算法和重复测量。",
                        "PARTICLE_SIZE_RESULT", List.of("SAMPLE_COMPARISON", "ANOMALY_DIAGNOSIS", "OVERVIEW")),
                existsRule("MECHANICAL_METHOD_001", "MECHANICAL", "力学结果判断需要结合试样尺寸、测试速率和重复次数。",
                        "MECHANICAL_RESULT", List.of("SAMPLE_COMPARISON", "METHOD_VALIDATION", "OVERVIEW"))
        );
        var map = new LinkedHashMap<String, KnowledgeRule>();
        values.forEach(rule -> map.put(rule.id(), rule));
        rules = Map.copyOf(map);
    }

    public Optional<KnowledgeRule> find(String id) {
        return Optional.ofNullable(rules.get(id));
    }

    public List<KnowledgeRule> forProtocols(List<String> protocols) {
        return rules.values().stream().filter(rule -> protocols.contains(rule.protocol())).toList();
    }

    public boolean applicable(KnowledgeRule rule, String intent, JsonNode claim,
                              Map<String, JsonNode> validDependencies) {
        if (rule == null || claim == null) return false;
        var applicability = rule.applicability();
        if (!applicability.applicableIntents().isEmpty() && !applicability.applicableIntents().contains(intent)) return false;
        if (!applicability.applicableProtocols().isEmpty()
                && !applicability.applicableProtocols().contains(rule.protocol())) return false;
        if (applicability.requiresSupportingClaim() && validDependencies.isEmpty()) return false;
        for (var requiredType : applicability.requiredSupportingClaimTypes()) {
            if (validDependencies.values().stream()
                    .noneMatch(item -> requiredType.equals(item.path("claimType").asText()))) return false;
        }
        for (var predicate : applicability.requiredObservedMetrics()) {
            if (!matchesAnyFact(validDependencies, predicate)) return false;
        }
        return true;
    }

    private boolean matchesAnyFact(Map<String, JsonNode> dependencies, Predicate predicate) {
        for (var dependency : dependencies.values()) {
            var facts = dependency.path("structuredFacts");
            if (!facts.isArray()) continue;
            for (var fact : facts) {
                if (!predicate.factType().equals(fact.path("factType").asText())
                        && !isEquivalentMetricFact(predicate, fact.path("factType").asText())) continue;
                var value = atPath(fact, predicate.field());
                if (matches(value, predicate)) return true;
                if (matchesRangeLowerBound(fact, predicate)) return true;
            }
        }
        return false;
    }

    private boolean matchesRangeLowerBound(JsonNode fact, Predicate predicate) {
        if (!"GTE".equals(predicate.operator())) return false;
        var separator = predicate.field().lastIndexOf('.');
        if (separator < 0) return false;
        var range = atPath(fact, predicate.field().substring(0, separator) + ".range");
        if (!range.isTextual()) return false;
        Matcher matcher = NUMERIC_RANGE_VALUE.matcher(range.asText());
        BigDecimal lowerBound = null;
        while (matcher.find()) {
            var value = new BigDecimal(matcher.group());
            if (lowerBound == null || value.compareTo(lowerBound) < 0) lowerBound = value;
        }
        return lowerBound != null && lowerBound.compareTo(BigDecimal.valueOf(predicate.value())) >= 0;
    }

    private boolean isEquivalentMetricFact(Predicate predicate, String factType) {
        // A peak is also an absorbance point. Models commonly encode peak height
        // as UV_PEAK rather than duplicating it as UV_ABSORBANCE_POINT.
        return "UV_ABSORBANCE_POINT".equals(predicate.factType())
                && "UV_PEAK".equals(factType)
                && "absorbance.value".equals(predicate.field());
    }

    private JsonNode atPath(JsonNode node, String path) {
        var current = node;
        for (var part : path.split("\\.")) current = current.path(part);
        return current;
    }

    private boolean matches(JsonNode value, Predicate predicate) {
        return switch (predicate.operator()) {
            case "EXISTS" -> !value.isMissingNode() && !value.isNull();
            case "EQ" -> value.isNumber() && compare(value, predicate) == 0;
            case "GT" -> value.isNumber() && compare(value, predicate) > 0;
            case "GTE" -> value.isNumber() && compare(value, predicate) >= 0;
            case "LT" -> value.isNumber() && compare(value, predicate) < 0;
            case "LTE" -> value.isNumber() && compare(value, predicate) <= 0;
            default -> false;
        };
    }

    private int compare(JsonNode value, Predicate predicate) {
        return BigDecimal.valueOf(value.asDouble()).compareTo(BigDecimal.valueOf(predicate.value()));
    }

    private static KnowledgeRule existsRule(String id, String protocol, String description, String factType,
                                            List<String> intents) {
        return new KnowledgeRule(id, protocol, description,
                List.of("METHOD_ASSESSMENT", "DATA_QUALITY_ASSESSMENT"), "L2", List.of(),
                new Applicability(intents, List.of(protocol), true, List.of("OBSERVATION"),
                        List.of(new Predicate(factType, "factType", "EXISTS", 0, ""))));
    }

    public record KnowledgeRule(String id, String protocol, String description, List<String> allowedClaimTypes,
                                String maxLevel, List<String> forbiddenClaimTypes,
                                Applicability applicability) { }

    public record Applicability(List<String> applicableIntents, List<String> applicableProtocols,
                                boolean requiresSupportingClaim, List<String> requiredSupportingClaimTypes,
                                List<Predicate> requiredObservedMetrics) { }

    public record Predicate(String factType, String field, String operator, double value, String unit) { }
}
