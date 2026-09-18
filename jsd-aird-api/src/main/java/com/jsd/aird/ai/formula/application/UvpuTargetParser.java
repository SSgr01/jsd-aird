package com.jsd.aird.ai.formula.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade.EligibilityReason;
import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade.TargetObservation;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class UvpuTargetParser {
    private static final Pattern NUMBER = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern WARP_HEIGHT = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*(?:CM|厘米)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURL_ANGLE = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*(?:°|度)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HARDNESS_GRADE = Pattern.compile("(?<!\\d)(\\d?H)(?![A-Z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADHESION_GRADE = Pattern.compile("(?<!\\d)([0-5])B(?![A-Z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT_RANGE = Pattern.compile(
            "(-?\\d+(?:\\.\\d+)?)\\s*(?:-|~|～|—|至)\\s*(-?\\d+(?:\\.\\d+)?)\\s*%",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*%", Pattern.CASE_INSENSITIVE);
    private final UvpuAnalysisProfile profile;
    private final ObjectMapper json;

    public UvpuTargetParser(UvpuAnalysisProfile profile, ObjectMapper json) {
        this.profile = profile;
        this.json = json;
    }

    public Map<String, TargetObservation> parse(List<JsonNode> testFacts) {
        var result = new LinkedHashMap<String, TargetObservation>();
        for (var target : profile.definition().targets()) {
            var candidates = testFacts.stream().filter(fact -> profile.matchingTargets(label(fact)).stream()
                    .anyMatch(item -> item.targetKey().equals(target.targetKey()))).toList();
            if (candidates.size() > 1) {
                result.put(target.targetKey(), missing(target, "AMBIGUOUS", "TARGET_AMBIGUOUS",
                        "多个测试事实同时匹配该目标", candidates.getFirst()));
            } else if (candidates.isEmpty()) {
                var related = testFacts.stream().filter(fact -> relatedConcept(target.targetKey(), label(fact))).findFirst();
                result.put(target.targetKey(), related
                        .map(fact -> missing(target, "UNSUPPORTED", "TEST_CONTEXT_MISSING",
                                "测试事实缺少唯一匹配目标所需的方法、基材或条件", fact))
                        .orElseGet(() -> missing(target, "MISSING", "TARGET_VALUE_MISSING", "未记录该目标结果", null)));
            } else {
                result.put(target.targetKey(), parse(target, candidates.getFirst()));
            }
        }
        return Map.copyOf(result);
    }

    private TargetObservation parse(UvpuAnalysisProfile.TargetDefinition target, JsonNode fact) {
        var raw = raw(fact);
        if (missingText(raw)) return missing(target, "MISSING", "TARGET_VALUE_MISSING", "目标结果为空或未测试", fact);
        return switch (target.parserCode()) {
            case "WARP_HEIGHT_CM_V1" -> warpingHeight(target, fact, raw);
            case "WARP_CURL_ANGLE_DEG_V1" -> curlAngle(target, fact, raw);
            case "HARDNESS_H_GRADE_V1" -> hardness(target, fact, raw);
            case "ABRASION_CYCLES_V1" -> abrasion(target, fact, raw);
            case "ADHESION_B_GRADE_V1" -> adhesion(target, fact, raw);
            case "ELONGATION_PERCENT_V1" -> elongation(target, fact, raw);
            case "SURFACE_DRYNESS_BINARY_V1" -> surfaceDryness(target, fact, raw);
            default -> missing(target, "UNSUPPORTED", "TARGET_UNSUPPORTED",
                    "目标没有可执行的确定性解析规则", fact);
        };
    }

    private TargetObservation warpingHeight(UvpuAnalysisProfile.TargetDefinition target, JsonNode fact, String raw) {
        var compact = raw.replaceAll("\\s+", "");
        if (compact.contains("不翘") || compact.contains("平整")) {
            return observation(target, fact, raw, BigDecimal.ZERO, null, "NONE", "EXACT", "PARSED", List.of());
        }
        var matcher = WARP_HEIGHT.matcher(raw.toUpperCase(Locale.ROOT));
        if (!matcher.find()) return missing(target, "UNSUPPORTED", "TARGET_UNSUPPORTED", "翘曲结果缺少可确认的厘米数值", fact);
        var direction = compact.contains("反翘") || compact.contains("反向") ? "REVERSE"
                : compact.contains("正翘") || compact.contains("正向") ? "FORWARD" : "UNKNOWN";
        var value = new BigDecimal(matcher.group(1)).abs().stripTrailingZeros();
        return observation(target, fact, raw, value, null, direction, "EXACT", "PARSED", List.of());
    }

    private TargetObservation curlAngle(UvpuAnalysisProfile.TargetDefinition target, JsonNode fact, String raw) {
        var compact = raw.replaceAll("\\s+", "");
        if (compact.contains("不翘") || compact.contains("平整")) {
            return observation(target, fact, raw, BigDecimal.ZERO, null, "NONE", "EXACT", "PARSED", List.of());
        }
        var matcher = CURL_ANGLE.matcher(raw);
        if (!matcher.find()) return missing(target, "UNSUPPORTED", "TARGET_UNSUPPORTED",
                "卷曲角度结果缺少可确认的角度值", fact);
        var direction = compact.contains("反翘") || compact.contains("反向") ? "REVERSE"
                : compact.contains("正翘") || compact.contains("正向") ? "FORWARD" : "UNKNOWN";
        return observation(target, fact, raw, new BigDecimal(matcher.group(1)).abs().stripTrailingZeros(),
                null, direction, "EXACT", "PARSED", List.of());
    }

    private TargetObservation hardness(UvpuAnalysisProfile.TargetDefinition target, JsonNode fact, String raw) {
        var matches = new ArrayList<Grade>();
        var matcher = HARDNESS_GRADE.matcher(raw.toUpperCase(Locale.ROOT));
        while (matcher.find()) matches.add(new Grade(matcher.group(1).toUpperCase(Locale.ROOT), matcher.start(), matcher.end()));
        BigDecimal best = null;
        String label = null;
        for (var index = 0; index < matches.size(); index++) {
            var current = matches.get(index);
            var end = index + 1 < matches.size() ? matches.get(index + 1).start() : raw.length();
            var segment = raw.substring(current.start(), end).toUpperCase(Locale.ROOT);
            var passed = segment.contains("OK") || segment.contains("通过") || segment.contains("无痕");
            var failed = segment.contains("失败") || segment.contains("有痕") || segment.contains("明显痕") || segment.contains("浅痕");
            if (passed && !failed) {
                var value = grade(current.label());
                if (best == null || value.compareTo(best) > 0) { best = value; label = current.label(); }
            }
        }
        if (best == null) return missing(target, "UNSUPPORTED", "TARGET_UNSUPPORTED", "硬度结果没有明确的通过等级", fact);
        return observation(target, fact, raw, best, label, null, "EXACT", "PARSED", List.of());
    }

    private TargetObservation abrasion(UvpuAnalysisProfile.TargetDefinition target, JsonNode fact, String raw) {
        var matcher = NUMBER.matcher(raw);
        if (!matcher.find()) return observation(target, fact, raw, null, null, null,
                "QUALITATIVE", "UNSUPPORTED", List.of(reason("TARGET_UNSUPPORTED", "钢丝绒结果没有次数", fact)));
        var value = new BigDecimal(matcher.group(1)).abs().stripTrailingZeros();
        var compact = raw.replaceAll("\\s+", "");
        if (compact.contains("无丝痕") || compact.contains("无痕") || compact.contains("未失效") || compact.contains("通过")) {
            return observation(target, fact, raw, value, null, null, "LOWER_BOUND", "PARSED", List.of());
        }
        if (compact.contains("失效") || compact.contains("开始出现") || compact.contains("出现丝痕")
                || compact.contains("出现痕") || compact.contains("破坏")) {
            return observation(target, fact, raw, value, null, null, "EXACT", "PARSED", List.of());
        }
        return observation(target, fact, raw, value, null, null, "AT_OBSERVATION", "PARSED", List.of());
    }

    private TargetObservation adhesion(UvpuAnalysisProfile.TargetDefinition target, JsonNode fact, String raw) {
        var matcher = ADHESION_GRADE.matcher(raw.toUpperCase(Locale.ROOT));
        if (!matcher.find()) return missing(target, "UNSUPPORTED", "TARGET_UNSUPPORTED",
                "附着力结果不是明确的0B至5B等级", fact);
        var label = matcher.group(1) + "B";
        return observation(target, fact, raw, new BigDecimal(matcher.group(1)), label,
                null, "EXACT", "PARSED", List.of());
    }

    private TargetObservation elongation(UvpuAnalysisProfile.TargetDefinition target, JsonNode fact, String raw) {
        var range = PERCENT_RANGE.matcher(raw);
        if (range.find()) {
            var minimum = new BigDecimal(range.group(1)).stripTrailingZeros();
            var maximum = new BigDecimal(range.group(2)).stripTrailingZeros();
            if (minimum.compareTo(maximum) > 0) {
                var swap = minimum;
                minimum = maximum;
                maximum = swap;
            }
            return observation(target, fact, raw, null, minimum, maximum, null, null,
                    "RANGE", "SOURCE_RANGE", "PARSED", List.of());
        }
        var exact = PERCENT.matcher(raw);
        if (exact.find()) {
            return observation(target, fact, raw, new BigDecimal(exact.group(1)).stripTrailingZeros(),
                    null, null, "EXACT", "SOURCE_PERCENT", "PARSED", List.of());
        }
        var fraction = fractionEvidence(fact);
        if (fraction != null) {
            return observation(target, fact, raw, fraction.multiply(new BigDecimal("100")).stripTrailingZeros(),
                    null, null, "EXACT", "EXCEL_FRACTION_PERCENT", "PARSED", List.of());
        }
        return missing(target, "UNSUPPORTED", "PERCENT_REPRESENTATION_UNKNOWN",
                "拉伸率数值缺少百分比格式或单位证据", fact);
    }

    private TargetObservation surfaceDryness(UvpuAnalysisProfile.TargetDefinition target, JsonNode fact, String raw) {
        var normalized = UvpuAnalysisProfile.normalize(raw);
        var positive = Set.of("OK", "表干", "不粘", "不粘手", "干爽", "完全表干");
        var negative = Set.of("NOTOK", "NOK", "未表干", "不表干", "轻微粘手", "粘手", "明显粘手", "未干");
        if (positive.contains(normalized)) {
            return observation(target, fact, raw, BigDecimal.ONE, "OK", null,
                    "EXACT", "CONTROLLED_BINARY_TERM", "PARSED", List.of());
        }
        if (negative.contains(normalized)) {
            return observation(target, fact, raw, BigDecimal.ZERO, "NOT_OK", null,
                    "EXACT", "CONTROLLED_BINARY_TERM", "PARSED", List.of());
        }
        return missing(target, "UNSUPPORTED", "TARGET_UNSUPPORTED",
                "UV固化后表干性不是受控的OK/NOT_OK表达", fact);
    }

    private TargetObservation missing(UvpuAnalysisProfile.TargetDefinition target, String status, String code,
                                      String message, JsonNode fact) {
        return observation(target, fact, fact == null ? "" : raw(fact), null, null, null,
                "MISSING", status, List.of(new EligibilityReason(code, message, fact == null ? "" : itemId(fact))));
    }

    private TargetObservation observation(UvpuAnalysisProfile.TargetDefinition target, JsonNode fact, String raw,
                                          BigDecimal numeric, String ordinal, String direction, String observationType,
                                          String status, List<EligibilityReason> reasons) {
        return observation(target, fact, raw, numeric, null, null, ordinal, direction, observationType,
                "SOURCE_REPORTED", status, reasons);
    }

    private TargetObservation observation(UvpuAnalysisProfile.TargetDefinition target, JsonNode fact, String raw,
                                          BigDecimal numeric, String ordinal, String direction, String observationType,
                                          String derivationRule, String status, List<EligibilityReason> reasons) {
        return observation(target, fact, raw, numeric, null, null, ordinal, direction, observationType,
                derivationRule, status, reasons);
    }

    private TargetObservation observation(UvpuAnalysisProfile.TargetDefinition target, JsonNode fact, String raw,
                                          BigDecimal numeric, BigDecimal minimum, BigDecimal maximum,
                                          String ordinal, String direction, String observationType,
                                          String derivationRule, String status, List<EligibilityReason> reasons) {
        return new TargetObservation(target.targetKey(), target.valueType(), numeric, minimum, maximum,
                ordinal, direction, target.unit(), observationType, derivationRule, raw,
                fact == null ? "" : itemId(fact),
                fact == null ? json.createArrayNode() : refs(fact), status, reasons);
    }

    private boolean relatedConcept(String targetKey, String label) {
        var normalized = UvpuAnalysisProfile.normalize(label);
        var target = profile.definition().targets().stream()
                .filter(item -> item.targetKey().equals(targetKey)).findFirst().orElse(null);
        if (target == null) return false;
        if (target.parserCode().startsWith("WARP_")) return normalized.contains("翘曲") || normalized.contains("卷曲");
        if (target.parserCode().startsWith("HARDNESS_")) return normalized.contains("硬度");
        if (target.parserCode().startsWith("ABRASION_")) return normalized.contains("钢丝绒");
        if (target.parserCode().startsWith("ADHESION_")) return normalized.contains("附着力");
        if (target.parserCode().startsWith("ELONGATION_")) return normalized.contains("拉伸率");
        if (target.parserCode().startsWith("SURFACE_DRYNESS_")) return normalized.contains("表干");
        return false;
    }

    private BigDecimal grade(String label) {
        return label.equals("H") ? BigDecimal.ONE : new BigDecimal(label.substring(0, label.length() - 1));
    }

    private String label(JsonNode fact) {
        var parts = new LinkedHashSet<String>();
        if (fact.path("labelPathSegments").isArray()) {
            fact.path("labelPathSegments").forEach(value -> {
                var text = value.asText("").strip();
                if (!text.isBlank()) parts.add(text);
            });
        }
        var label = fact.path("testItem").asText("").trim();
        if (!label.isBlank()) parts.add(label);
        var itemLabel = fact.path("itemLabel").asText("").trim();
        if (!itemLabel.isBlank()) parts.add(itemLabel);
        if (parts.isEmpty()) {
            var path = fact.path("labelPath").asText("").trim();
            if (!path.isBlank()) parts.add(path);
        }
        return String.join(" > ", parts);
    }

    private BigDecimal fractionEvidence(JsonNode fact) {
        if (!fact.path("sourceRefs").isArray()) return null;
        for (var ref : fact.path("sourceRefs")) {
            if (!ref.path("fractionRepresentation").asBoolean(false)) continue;
            var rawNumeric = ref.path("rawNumericValue").asText("").strip();
            if (rawNumeric.isBlank()) continue;
            try { return new BigDecimal(rawNumeric); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private String raw(JsonNode fact) {
        var raw = fact.get("rawValue");
        if (raw == null || raw.isNull()) raw = fact.get("value");
        return raw == null || raw.isNull() ? "" : raw.asText("").trim();
    }

    private String itemId(JsonNode fact) {
        var value = fact.path("itemId").asText("").trim();
        return value.isBlank() ? fact.path("bindingId").asText("").trim() : value;
    }

    private JsonNode refs(JsonNode fact) {
        return fact.path("sourceRefs").isArray() ? fact.path("sourceRefs").deepCopy() : json.createArrayNode();
    }

    private boolean missingText(String value) {
        return value == null || value.isBlank() || "/".equals(value.trim()) || "未测试".equals(value.trim());
    }

    private EligibilityReason reason(String code, String message, JsonNode fact) {
        return new EligibilityReason(code, message, itemId(fact));
    }

    private record Grade(String label, int start, int end) {
    }
}
