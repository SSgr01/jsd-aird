package com.jsd.aird.spc.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Passes through model-authored Markdown and presents legacy structured results
 * for historical analyses. Legacy source results remain untouched for audit.
 */
@Component
public class SpectrumResultPresenter {

    private static final String PUBLIC_FAILURE_MESSAGE = "图谱分析未完成，请稍后重试。";
    private static final int MAX_FINDINGS = 3;
    private static final int MAX_VALIDATION_STEPS = 3;
    private static final int MAX_PUBLIC_NEXT_STEPS = 4;
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
        if (result.path("schemaVersion").asInt() >= 4) {
            var publicResult = result.deepCopy();
            if ("FAILED".equals(publicResult.path("analysisStatus").asText())) {
                publicResult.put("errorMessage", PUBLIC_FAILURE_MESSAGE);
            }
            return publicResult;
        }
        if (result.path("schemaVersion").asInt() == 3) return presentV3(result);
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

    private ObjectNode presentV3(ObjectNode internal) {
        var result = objectMapper.createObjectNode();
        result.put("schemaVersion", 3);
        result.put("analysisStatus", internal.path("analysisStatus").asText("SUCCEEDED"));
        if ("FAILED".equals(internal.path("analysisStatus").asText())) {
            result.put("errorMessage", PUBLIC_FAILURE_MESSAGE);
        }

        var confirmed = new ArrayList<String>();
        var inferred = new ArrayList<String>();
        var metadata = new ArrayList<String>();
        var limitations = new ArrayList<String>();
        var missingEvidence = new ArrayList<String>();
        var nextSteps = new ArrayList<String>();
        var assessment = internal.path("assessment");

        var claims = internal.path("claims");
        if (claims.isArray()) for (var item : claims) {
            if (!Set.of("VALID", "DEMOTED").contains(item.path("status").asText())) continue;
            var text = publicClaimText(item, assessment);
            if (text.isBlank()) continue;
            if (isMetadataClaim(item)) {
                addUnique(metadata, text);
                continue;
            }
            var level = item.path("effectiveLevel").asText();
            if ("L1".equals(level) || "L4".equals(level)) addUnique(confirmed, text);
            else if ("L2".equals(level) || "L3".equals(level)) addUnique(inferred, text);
        }

        var limitationNodes = internal.path("limitations");
        if (limitationNodes.isArray()) for (var item : limitationNodes) {
            addUnique(limitations, item.path("statement").asText(""));
            var missing = item.path("missingEvidence");
            if (missing.isArray()) missing.forEach(value -> addUnique(missingEvidence,
                    value.isTextual() ? value.asText() : value.path("label").asText("")));
            var steps = item.path("nextSteps");
            if (steps.isArray() && nextSteps.size() < MAX_PUBLIC_NEXT_STEPS) {
                var before = nextSteps.size();
                for (var step : steps) {
                    addUnique(nextSteps, step.asText(""));
                    if (nextSteps.size() > before) break;
                }
            }
        }

        var answer = result.putObject("answer");
        answer.put("summary", publicSummary(assessment, claims));
        answer.set("confirmed", objectMapper.valueToTree(confirmed));
        answer.set("metadata", objectMapper.valueToTree(metadata));
        answer.set("notConfirmed", objectMapper.valueToTree(limitations));
        answer.set("reasonableInference", objectMapper.valueToTree(inferred));
        answer.set("missingEvidence", objectMapper.valueToTree(missingEvidence));
        answer.set("nextStep", objectMapper.valueToTree(nextSteps));

        var details = result.putObject("details");
        details.set("confirmed", objectMapper.valueToTree(confirmed));
        details.set("metadata", objectMapper.valueToTree(metadata));
        details.set("reasonableInference", objectMapper.valueToTree(inferred));
        details.set("limitations", objectMapper.valueToTree(limitations));
        result.set("assessment", publicAssessment(assessment));
        result.put("confidence", confidenceLabel(internal.path("confidence").asText()));
        return result;
    }

    private String publicClaimText(JsonNode claim, JsonNode assessment) {
        var statement = claim.path("effectiveStatement").asText("").trim();
        if (statement.isBlank()) return "";
        if (isMetadataClaim(claim)) {
            var nominal = assessment.path("nominalConcentration").path("value").asText("");
            if (!nominal.isBlank()) return "文件或报告将样品标记为" + nominal + "。";
            return "文档记录：" + statement.replaceAll("[。；;]+$", "") + "。";
        }
        return statement;
    }

    private boolean isMetadataClaim(JsonNode claim) {
        if ("REPORTED_METADATA".equals(claim.path("claimType").asText())) return true;
        var refs = claim.path("evidenceRefs");
        if (!refs.isArray() || refs.isEmpty()) return false;
        for (var ref : refs) {
            if (!"DOCUMENT_METADATA".equals(ref.path("sourceType").asText())) return false;
        }
        return true;
    }

    private String publicSummary(JsonNode assessment, JsonNode claims) {
        var focus = assessment.path("focus").asText("").trim();
        if (!focus.isBlank() && hasValidSupportingClaim(assessment, claims) && !looksLikeTaskScope(focus)) {
            return firstSentences(focus, 2);
        }
        if (claims.isArray()) {
            for (var claim : claims) {
                if (!validPublicClaim(claim) || isMetadataClaim(claim)) continue;
                if (hasStructuredFact(claim, "CURVE_COMPARISON")) {
                    return firstSentences(claim.path("effectiveStatement").asText(""), 2);
                }
            }
            for (var claim : claims) {
                if (validPublicClaim(claim) && !isMetadataClaim(claim)
                        && "OBSERVATION".equals(claim.path("claimType").asText())) {
                    return firstSentences(claim.path("effectiveStatement").asText(""), 2);
                }
            }
        }
        return "";
    }

    private boolean hasValidSupportingClaim(JsonNode assessment, JsonNode claims) {
        var supportingIds = assessment.path("supportingClaimIds");
        if (!supportingIds.isArray() || supportingIds.isEmpty() || !claims.isArray()) return false;
        for (var id : supportingIds) {
            for (var claim : claims) {
                if (id.asText().equals(claim.path("claimId").asText()) && validPublicClaim(claim)) return true;
            }
        }
        return false;
    }

    private boolean validPublicClaim(JsonNode claim) {
        return Set.of("VALID", "DEMOTED").contains(claim.path("status").asText())
                && !claim.path("effectiveStatement").asText("").isBlank();
    }

    private boolean hasStructuredFact(JsonNode claim, String factType) {
        var facts = claim.path("structuredFacts");
        if (!facts.isArray()) return false;
        for (var fact : facts) if (factType.equals(fact.path("factType").asText())) return true;
        return false;
    }

    private boolean looksLikeTaskScope(String value) {
        return value.startsWith("概览") || value.startsWith("分析") || value.startsWith("聚焦")
                || value.startsWith("本次分析") || value.startsWith("本次任务");
    }

    private String firstSentences(String value, int limit) {
        var matcher = SENTENCE.matcher(value == null ? "" : value.trim());
        var result = new StringBuilder();
        var count = 0;
        while (matcher.find() && count < limit) {
            result.append(matcher.group().trim());
            count++;
        }
        return result.isEmpty() ? value == null ? "" : value.trim() : result.toString();
    }

    private ObjectNode publicAssessment(JsonNode source) {
        var result = objectMapper.createObjectNode();
        var type = source.path("type").asText("OVERVIEW");
        result.put("title", switch (type) {
            case "CONCENTRATION_CHECK" -> "浓度与测量适用性";
            case "PURITY_CHECK" -> "纯度判断";
            case "IDENTIFICATION" -> "样品鉴定";
            case "REFERENCE_MAPPING" -> "参考谱对应";
            case "METHOD_VALIDATION" -> "方法适用性";
            case "ANOMALY_DIAGNOSIS" -> "异常分析";
            case "SAMPLE_COMPARISON" -> "样品比较";
            case "PEAK_INTERPRETATION" -> "峰与特征解释";
            default -> "图谱概览";
        });
        var items = result.putArray("items");
        if ("CONCENTRATION_CHECK".equals(type)) {
            var nominal = source.path("nominalConcentration");
            if (nominal.isObject() && !nominal.path("value").asText("").isBlank()) {
                addAssessmentItem(items, "标称浓度", nominal.path("value").asText(), "文档标记");
            }
            addAssessmentItem(items, "实际浓度", publicValue(source.path("actualConcentrationAssessment").asText()), null);
            addAssessmentItem(items, "当前测量适用性", publicValue(source.path("measurementSuitability").asText()), null);
            if (!source.path("suitabilityReason").asText("").isBlank()) {
                addAssessmentItem(items, "判断说明", source.path("suitabilityReason").asText(), null);
            }
        } else {
            var fields = switch (type) {
                case "PURITY_CHECK" -> List.of("purityAssessment", "quantificationStatus");
                case "IDENTIFICATION" -> List.of("identityAssessment");
                case "REFERENCE_MAPPING" -> List.of("mappingStatus");
                case "METHOD_VALIDATION" -> List.of("methodSuitability");
                case "SAMPLE_COMPARISON" -> List.of("comparability");
                case "PEAK_INTERPRETATION" -> List.of("attributionStatus");
                default -> List.<String>of();
            };
            for (var field : fields) {
                var value = source.path(field).asText("");
                if (!value.isBlank()) addAssessmentItem(items, assessmentLabel(field), publicValue(value), null);
            }
        }
        if (items.isEmpty() && "UNDETERMINED".equals(source.path("resultStatus").asText())) {
            addAssessmentItem(items, "当前结果", "暂无法判断", null);
        }
        return result;
    }

    private void addAssessmentItem(ArrayNode items, String label, String value, String source) {
        if (value == null || value.isBlank()) return;
        var item = items.addObject().put("label", label).put("value", value);
        if (source != null && !source.isBlank()) item.put("source", source);
    }

    private String assessmentLabel(String field) {
        return switch (field) {
            case "purityAssessment" -> "纯度判断";
            case "quantificationStatus" -> "定量状态";
            case "identityAssessment" -> "身份判断";
            case "mappingStatus" -> "对应状态";
            case "methodSuitability" -> "方法适用性";
            case "comparability" -> "可比性";
            case "attributionStatus" -> "特征归属";
            default -> "分析结果";
        };
    }

    private String publicValue(String value) {
        return switch (value) {
            case "VERIFIED" -> "已验证";
            case "UNVERIFIED", "UNDETERMINED" -> "暂无法验证";
            case "INCONSISTENT" -> "现有信息不一致";
            case "SUITABLE" -> "适合";
            case "CAUTION" -> "需要关注";
            case "UNSUITABLE" -> "不适合";
            default -> value.matches("[A-Z0-9_]+") ? "暂无法判断" : value;
        };
    }

    private String confidenceLabel(String value) {
        return switch (value) {
            case "HIGH" -> "较高";
            case "MEDIUM" -> "中等";
            default -> "较低";
        };
    }

    private void addUnique(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value.trim())) values.add(value.trim());
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
