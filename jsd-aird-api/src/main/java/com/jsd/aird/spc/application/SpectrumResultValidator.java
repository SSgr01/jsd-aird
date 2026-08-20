package com.jsd.aird.spc.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.spc.application.port.SpectrumPromptPort.SpectrumAnalysisPromptContext;
import org.springframework.stereotype.Component;

/**
 * Applies server-owned evidence boundaries after the model has responded.
 * The model may explain evidence, but it cannot grant itself reference roles
 * or invent relationships between samples.
 */
@Component
public class SpectrumResultValidator {

    private static final List<String> ARRAY_FIELDS = List.of(
            "observations", "comparisons", "peakMappings", "candidateInterpretations",
            "unmatchedFeatures", "overlapCandidates", "conflicts", "suggestedValidationExperiments",
            "evidence", "uncertainty", "testConditionLimitations", "aiReviewFocus");
    private static final List<String> RELATION_WORDS = List.of(
            "原料", "中间体", "母体", "衍生物", "成品", "同配方", "上下游", "单体", "二聚体", "三聚体");

    private final ObjectMapper objectMapper;

    public SpectrumResultValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(ObjectNode source, SpectrumAnalysisPromptContext context) {
        var result = source == null ? objectMapper.createObjectNode() : source;
        var warnings = new ArrayList<String>();
        ARRAY_FIELDS.forEach(field -> ensureArray(result, field));
        result.remove("professionalReviewOpinion");
        result.remove("needsHumanReview");
        if (!result.has("conclusionBoundary")) {
            result.put("conclusionBoundary", "POSSIBLE_INTERPRETATIONS_ONLY_NO_DEFINITIVE_FORMULA");
        }

        var competitor = "COMPETITOR_DECOMPOSITION".equals(context.scenarioTemplate());
        if (competitor) {
            var referenceAvailability = result.path("referenceAvailability").isObject()
                    ? (ObjectNode) result.path("referenceAvailability") : objectMapper.createObjectNode();
            result.set("referenceAvailability", referenceAvailability);
            referenceAvailability.put("hasSinglePeakReferences", context.hasSinglePeakReferences());
            var referenceIds = referenceAvailability.putArray("referenceChartIds");
            context.referenceChartIds().forEach(referenceIds::add);
            referenceAvailability.put("statement", context.hasSinglePeakReferences()
                    ? "系统已识别明确的单峰参考图谱，可在证据充分时建立候选映射。"
                    : "当前材料不足以建立样品峰与单峰参考峰的映射，只能描述谱形相似性。");

            var mappings = (ArrayNode) result.path("peakMappings");
            var safeMappings = objectMapper.createArrayNode();
            if (!context.hasSinglePeakReferences()) {
                if (!mappings.isEmpty()) warnings.add("已清除模型生成的无参考峰位映射");
                result.set("peakMappings", safeMappings);
                result.put("evidenceSufficiency", "INSUFFICIENT_FOR_MAPPING");
            } else {
                mappings.forEach(item -> {
                    if (isSupportedMapping(item, context)) safeMappings.add(item);
                    else warnings.add("已清除缺少可信参考图谱或证据 ID 的峰位映射");
                });
                result.set("peakMappings", safeMappings);
                if (!result.has("evidenceSufficiency") || result.path("evidenceSufficiency").isNull()) {
                    result.put("evidenceSufficiency", "SUFFICIENT_FOR_POSSIBLE_INTERPRETATION_ONLY");
                }
            }
        } else {
            result.remove("referenceAvailability");
            result.remove("evidenceSufficiency");
        }

        if (hasUnauthorizedSampleRelation(result.path("answerMarkdown").asText(""), context)) {
            result.put("answerMarkdown", "当前结果已按证据边界收敛，未保留无明确依据的跨样品关系推断。");
            warnings.add("已过滤无明确样品关系的跨样品推断");
        }
        filterUnauthorizedItems(result, "candidateInterpretations", context, warnings);
        filterUnauthorizedItems(result, "comparisons", context, warnings);
        filterUnauthorizedItems(result, "observations", context, warnings);
        filterUnauthorizedItems(result, "overlapCandidates", context, warnings);

        if (!warnings.isEmpty()) {
            result.put("analysisStatus", "PARTIAL");
            var conflicts = (ArrayNode) result.withArray("conflicts");
            conflicts.add("部分模型输出未通过证据边界校验，已被过滤。");
        } else if (!result.has("analysisStatus")) {
            result.put("analysisStatus", "SUCCEEDED");
        }
        return new ValidationResult(result, List.copyOf(new LinkedHashSet<>(warnings)));
    }

    private boolean isSupportedMapping(JsonNode item, SpectrumAnalysisPromptContext context) {
        if (item == null || !item.isObject()) return false;
        var referenceChartId = item.path("referenceChartId").asText("");
        var referenceKnown = context.referenceChartIds().contains(referenceChartId);
        var evidenceKnown = false;
        var evidenceIds = item.path("evidenceIds");
        if (evidenceIds.isArray()) {
            for (var evidenceId : evidenceIds) {
                var value = evidenceId.asText("");
                if (context.availableEvidenceIds().contains(value)
                        || context.referenceChartIds().stream().anyMatch(value::startsWith)) {
                    evidenceKnown = true;
                    break;
                }
            }
        }
        return referenceKnown && evidenceKnown;
    }

    private void filterUnauthorizedItems(ObjectNode result, String field,
                                         SpectrumAnalysisPromptContext context,
                                         List<String> warnings) {
        var source = result.path(field);
        if (!source.isArray() || !context.explicitSampleRelations().isEmpty()) return;
        var safe = objectMapper.createArrayNode();
        source.forEach(item -> {
            if (hasUnauthorizedSampleRelation(item.toString(), context)) {
                warnings.add("已过滤无明确样品关系的跨样品推断");
            } else safe.add(item);
        });
        result.set(field, safe);
    }

    private boolean hasUnauthorizedSampleRelation(String value, SpectrumAnalysisPromptContext context) {
        if (!context.explicitSampleRelations().isEmpty() || value == null || value.isBlank()) return false;
        var normalized = value.toLowerCase(Locale.ROOT);
        var sampleMatches = context.sampleNames().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.toLowerCase(Locale.ROOT))
                .filter(normalized::contains)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return sampleMatches.size() >= 2 && RELATION_WORDS.stream().anyMatch(normalized::contains);
    }

    private void ensureArray(ObjectNode result, String field) {
        if (!result.path(field).isArray()) result.set(field, objectMapper.createArrayNode());
    }

    public record ValidationResult(ObjectNode result, List<String> warnings) { }
}
