package com.jsd.aird.spc.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Builds a compact, deterministic view from the validated model result.
 * The original structured fields stay untouched for audit and detailed review.
 */
@Component
public class SpectrumResultPresenter {

    private static final int MAX_FINDINGS = 3;
    private static final int MAX_VALIDATION_STEPS = 3;
    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?]+[。！？!?]?");
    private static final List<String> DETAIL_FIELDS = List.of(
            "observations", "comparisons", "peakMappings", "candidateInterpretations",
            "overlapCandidates", "unmatchedFeatures", "conflicts", "suggestedValidationExperiments",
            "testConditionLimitations", "aiReviewFocus");
    private static final List<String> TEXT_KEYS = List.of(
            "summary", "observation", "comparison", "description", "interpretation",
            "possibleInterpretation", "conclusion", "reason", "experiment", "purpose",
            "feature", "region", "peak", "peakPosition");

    private final ObjectMapper objectMapper;

    public SpectrumResultPresenter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode present(ObjectNode result, String question) {
        if (result == null) return objectMapper.createObjectNode();
        var intent = detectIntent(question);
        var presentation = objectMapper.createObjectNode();
        presentation.put("version", 2);
        presentation.put("primaryIntent", intent.name());

        var conclusion = conclusion(result, intent);
        presentation.put("conclusion", conclusion);

        var displayed = new ArrayList<String>();
        addIfUnique(displayed, conclusion, Integer.MAX_VALUE);
        var findings = presentation.putArray("keyFindings");
        for (var field : findingFields(intent)) {
            collect(result.path(field), displayed, findings, MAX_FINDINGS);
            if (findings.size() >= MAX_FINDINGS) break;
        }

        var validationSteps = presentation.putArray("validationSteps");
        if (requestsValidation(question)) {
            collect(result.path("suggestedValidationExperiments"), displayed, validationSteps, MAX_VALIDATION_STEPS);
        }

        var detailKeys = presentation.putArray("detailSectionKeys");
        DETAIL_FIELDS.stream().filter(field -> result.path(field).isArray() && !result.path(field).isEmpty())
                .forEach(detailKeys::add);
        result.set("presentation", presentation);
        return result;
    }

    private Intent detectIntent(String question) {
        var value = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (containsAny(value, "归因", "映射", "对应", "官能团", "成分", "鉴定", "attribution", "identify", "mapping")) {
            return Intent.ATTRIBUTION;
        }
        if (containsAny(value, "比较", "对比", "差异", "变化", "批次", "一致", "compare", "difference", "batch")) {
            return Intent.COMPARISON;
        }
        if (containsAny(value, "概览", "总结", "概括", "总体", "整体情况", "overview", "summarize", "summary")) {
            return Intent.OVERVIEW;
        }
        if (containsAny(value, "峰", "峰位", "波长", "谱带", "特征", "peak", "feature", "wavelength")) {
            return Intent.FEATURE_INTERPRETATION;
        }
        if (containsAny(value, "验证", "实验", "建议", "复测", "确认", "validate", "experiment", "verify")) {
            return Intent.VALIDATION;
        }
        return Intent.OVERVIEW;
    }

    private boolean requestsValidation(String question) {
        var value = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return containsAny(value, "验证", "实验", "建议", "复测", "确认", "validate", "experiment", "verify");
    }

    private boolean containsAny(String value, String... candidates) {
        for (var candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private List<String> findingFields(Intent intent) {
        return switch (intent) {
            case ATTRIBUTION -> List.of("comparisons", "candidateInterpretations", "observations", "conflicts");
            case COMPARISON -> List.of("comparisons", "observations", "conflicts", "candidateInterpretations");
            case FEATURE_INTERPRETATION -> List.of("candidateInterpretations", "peakMappings", "unmatchedFeatures", "observations");
            case VALIDATION -> List.of("conflicts", "testConditionLimitations", "observations", "comparisons");
            case OVERVIEW -> List.of("observations", "comparisons", "candidateInterpretations", "conflicts");
        };
    }

    private String conclusion(ObjectNode result, Intent intent) {
        var sufficiency = result.path("evidenceSufficiency").asText("");
        if (intent == Intent.ATTRIBUTION && sufficiency.startsWith("INSUFFICIENT")) {
            return "当前证据不足以支持明确归因，也不能建立可靠的峰位映射。";
        }
        if (intent == Intent.FEATURE_INTERPRETATION && sufficiency.startsWith("INSUFFICIENT")) {
            return "当前证据不足以对图谱特征作出可靠归因。";
        }
        var answer = conciseText(result.path("answerMarkdown").asText(""), 2);
        if (!answer.isBlank()) return answer;
        var boundary = result.path("conclusionBoundary").asText("");
        if (!boundary.isBlank() && !boundary.matches("[A-Z0-9_]+")) return conciseText(boundary, 2);
        return "已完成图谱分析，以下结论仍需结合原始数据和测试条件复核。";
    }

    private String conciseText(String value, int sentenceLimit) {
        if (value == null || value.isBlank()) return "";
        var cleaned = value.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*(?:[-*+] |\\d+[.)、]\\s*)", "")
                .replaceAll("\\s+", " ").trim();
        var matcher = SENTENCE.matcher(cleaned);
        var selected = new ArrayList<String>();
        while (matcher.find() && selected.size() < sentenceLimit) {
            var sentence = matcher.group().trim();
            if (!sentence.isBlank()) selected.add(sentence);
        }
        return selected.isEmpty() ? cleaned : String.join("", selected);
    }

    private void collect(JsonNode source, List<String> displayed, ArrayNode target, int limit) {
        if (!source.isArray()) return;
        for (var item : source) {
            if (target.size() >= limit) return;
            var text = itemText(item);
            if (addIfUnique(displayed, text, Integer.MAX_VALUE)) target.add(text);
        }
    }

    private String itemText(JsonNode item) {
        if (item == null || item.isNull()) return "";
        if (item.isValueNode()) return conciseText(item.asText(""), 2);
        if (!item.isObject()) return "";
        for (var key : TEXT_KEYS) {
            var value = item.path(key);
            if (value.isValueNode() && !value.asText("").isBlank()) return conciseText(value.asText(), 2);
        }
        return "";
    }

    private boolean addIfUnique(List<String> values, String candidate, int limit) {
        if (candidate == null || candidate.isBlank() || values.size() >= limit) return false;
        var normalized = normalize(candidate);
        if (normalized.isBlank()) return false;
        for (var value : values) {
            var existing = normalize(value);
            var shorter = normalized.length() <= existing.length() ? normalized : existing;
            var longer = normalized.length() <= existing.length() ? existing : normalized;
            if (normalized.equals(existing) || (shorter.length() >= 12 && longer.contains(shorter))) return false;
        }
        values.add(candidate.trim());
        return true;
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private enum Intent {
        ATTRIBUTION,
        COMPARISON,
        FEATURE_INTERPRETATION,
        VALIDATION,
        OVERVIEW
    }
}
