package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Resolves experiment semantics from an already verified physical field.
 *
 * <p>The existing group/path is the primary business context.  Field names
 * handle explicit exceptions and a model suggestion may fill a remaining
 * gap, but it can never create a physical field or override geometry.</p>
 */
public final class ExperimentSemanticResolver {

    public static final double AUTO_CONFIRM_THRESHOLD = 0.90d;

    private static final Set<String> DOMAINS = Set.of(
            "BASIC", "FORMULA", "PROCESS", "TEST", "CONCLUSION", "OTHER");
    private static final Set<String> BASIC_FIELDS = Set.of(
            "SOURCE_IDENTITY", "TITLE", "PURPOSE", "PLAN", "EXPERIMENT_DATE", "OWNER");
    private static final Set<String> FORMULA_FIELDS = Set.of(
            "MATERIAL_ID", "MATERIAL_CODE", "MATERIAL_NAME", "RATIO", "ACTUAL_QTY",
            "UNIT", "RAW_VALUE", "RAW_UNIT");
    private static final Set<String> PROCESS_FIELDS = Set.of(
            "STEP_NO", "OPERATION", "TEMPERATURE", "DURATION", "APPLICATION_CONDITION", "OTHER");
    private static final Set<String> TEST_FIELDS = Set.of(
            "TEST_ITEM", "VALUE", "UNIT", "JUDGEMENT", "TEST_METHOD", "TEST_CONDITION", "SUBSTRATE");
    private static final Set<String> CONCLUSION_FIELDS = Set.of(
            "RESULT_STATUS", "MAIN_CONCLUSION", "FAILURE_CATEGORY");

    private final ObjectMapper objectMapper;

    public ExperimentSemanticResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Adds a backend-owned semantic resolution to a validated field relation. */
    public void resolveField(ObjectNode relation, JsonNode physicalCandidate, JsonNode region) {
        var path = labelPath(physicalCandidate, relation);
        var name = firstText(relation, "fieldName", "businessName");
        if (name.isBlank()) name = firstText(physicalCandidate, "fieldName", "name");
        var deterministic = deterministic(path, name, relation.path("unit").asText(""));
        var model = modelSuggestion(relation.path("experimentFieldSuggestion"));

        Resolution selected;
        var conflict = false;
        if (deterministic != null && model != null && !sameSemantic(deterministic, model)
                && model.confidence() >= AUTO_CONFIRM_THRESHOLD) {
            selected = deterministic;
            // A controlled field-name rule is deterministic domain knowledge.
            // The model may challenge a group-only inference, but it cannot
            // turn a known field such as coating appearance into another role.
            conflict = !"FIELD_RULE".equals(deterministic.source());
        } else if (deterministic != null) {
            selected = deterministic;
        } else if (model != null && model.confidence() >= AUTO_CONFIRM_THRESHOLD) {
            selected = model;
        } else {
            selected = dynamic(name, 0.60d);
        }

        var structureConfirmed = "CONFIRMED".equals(region.path("canonicalStatus").asText(""))
                && "CONFIRMED".equals(region.path("structureStatus").asText("CONFIRMED"))
                && !region.path("structureConflict").asBoolean(false);
        var ambiguousName = ambiguous(name);
        var autoConfirmed = structureConfirmed && !conflict && !ambiguousName
                && selected.confidence() >= AUTO_CONFIRM_THRESHOLD;

        relation.set("experimentField", semantic(selected.domain(), selected.field()));
        relation.put("experimentItemLabel", resolvedItemLabel(path, name, selected));
        relation.put("experimentSemanticConfidence", selected.confidence());
        relation.put("experimentSemanticStatus", autoConfirmed ? "AUTO_CONFIRMED" : "NEEDS_REVIEW");
        relation.put("experimentSemanticSource", selected.source());
        relation.put("autoAccept", autoConfirmed);
        relation.put("reviewRequired", !autoConfirmed);
        if (!path.isEmpty()) {
            var segments = relation.putArray("labelPathSegments");
            path.forEach(segments::add);
            relation.put("labelPath", String.join(" > ", path));
        }
        if (conflict || ambiguousName) {
            relation.set("experimentSemanticAlternatives", alternatives(deterministic, model, name));
            relation.put("experimentSemanticIssue", conflict
                    ? "分组与模型建议不一致" : "字段名称存在多种业务含义");
        }
    }

    public ObjectNode semantic(String domain, String field) {
        return objectMapper.createObjectNode().put("domain", domain).put("field", field);
    }

    public boolean validSemantic(JsonNode value) {
        if (!value.isObject()) return false;
        var domain = value.path("domain").asText("").toUpperCase(Locale.ROOT);
        var field = value.path("field").asText("").toUpperCase(Locale.ROOT);
        if (!DOMAINS.contains(domain)) return false;
        return switch (domain) {
            case "BASIC" -> BASIC_FIELDS.contains(field);
            case "FORMULA" -> FORMULA_FIELDS.contains(field);
            case "PROCESS" -> PROCESS_FIELDS.contains(field);
            case "TEST" -> TEST_FIELDS.contains(field);
            case "CONCLUSION" -> CONCLUSION_FIELDS.contains(field);
            case "OTHER" -> "DYNAMIC_VALUE".equals(field);
            default -> false;
        };
    }

    public boolean exactFormulaGroup(JsonNode candidate) {
        if (containsAny(normalize(candidate.path("groupName").asText("")),
                "实验配方", "配方明细", "配方", "原料信息")) return true;
        return labelPath(candidate, null).stream().anyMatch(value -> containsAny(normalize(value),
                "实验配方", "配方明细", "配方", "原料信息"));
    }

    private Resolution deterministic(List<String> path, String fieldName, String unit) {
        var joined = normalize(String.join("/", path));
        var name = normalize(fieldName);

        if (containsAny(name, "实验编号", "试验编号")) return basic("SOURCE_IDENTITY", fieldName, 0.99d);
        if (containsAny(name, "样品编号", "样品号", "样本编号")) return basic("SOURCE_IDENTITY", fieldName, 0.98d);
        if (containsAny(name, "配方编号", "配方号", "批次编号", "批号")) {
            return basic("SOURCE_IDENTITY", fieldName, 0.96d);
        }
        if (containsAny(name, "实验目的", "试验目的", "目的")) return basic("PURPOSE", fieldName, 0.97d);
        if (containsAny(name, "实验方案", "试验方案", "方案", "计划")) return basic("PLAN", fieldName, 0.95d);
        if (containsAny(name, "实验日期", "试验日期", "日期")) return basic("EXPERIMENT_DATE", fieldName, 0.94d);
        if (containsAny(name, "实验人", "试验人", "负责人", "测试人")) return basic("OWNER", fieldName, 0.94d);
        if (name.equals(normalize("项目")) || name.equals(normalize("项目名称"))) {
            return basic("TITLE", fieldName, 0.95d);
        }
        if (name.equals(normalize("备注")) || name.equals(normalize("补充说明"))) {
            return result("OTHER", "DYNAMIC_VALUE", fieldName, 0.98d, "FIELD_RULE");
        }
        if (containsAny(name, "主要结论", "结果/结论/小结", "结论", "小结")) {
            return result("CONCLUSION", "MAIN_CONCLUSION", fieldName, 0.96d, "FIELD_RULE");
        }
        if (name.equals(normalize("树脂内容"))) {
            return result("OTHER", "DYNAMIC_VALUE", fieldName, 0.94d, "FIELD_RULE");
        }

        // Explicit test facts win over generic process words.  The baking
        // phrase is a test condition attached to measured solid content.
        if (containsAny(name, "实测固含", "粘度", "含水率", "分子量", "翘曲", "硬度",
                "钢丝绒", "耐磨", "附着力", "拉伸率", "膜厚", "表干性", "漆膜外观", "涂料外观")
                || containsAny(joined, "实测固含", "粘度", "含水率", "分子量", "翘曲", "硬度",
                "钢丝绒", "耐磨", "附着力", "拉伸率", "膜厚", "表干性", "漆膜外观", "涂料外观")) {
            return result("TEST", "VALUE", fieldName, 0.96d, "FIELD_RULE");
        }
        if (name.equals(normalize("树脂编号"))) {
            return result("OTHER", "DYNAMIC_VALUE", fieldName, 0.94d, "FIELD_RULE");
        }
        if (containsAny(name, "测试方法", "试验方法", "检测方法")) {
            return result("TEST", "TEST_METHOD", fieldName, 0.97d, "FIELD_RULE");
        }
        if (containsAny(name, "判定", "是否合格")) {
            return result("TEST", "JUDGEMENT", fieldName, 0.96d, "FIELD_RULE");
        }
        if (containsAny(name, "基材", "素材", "底材")) {
            return result("TEST", "SUBSTRATE", fieldName, 0.94d, "FIELD_RULE");
        }
        if (containsAny(name, "涂料固含", "施工方式", "制膜", "固化条件", "固化/测试条件", "uv能量",
                "uva能量", "uv光强", "uva光强", "湿度")) {
            return result("PROCESS", "APPLICATION_CONDITION", fieldName, 0.96d, "FIELD_RULE");
        }
        if (containsAny(name, "温度") && !containsAny(name, "烘烤", "测试")) {
            return result("PROCESS", "TEMPERATURE", fieldName, 0.93d, "FIELD_RULE");
        }
        if (containsAny(name, "时间", "时长") && !containsAny(joined, "性能测试", "树脂物性")) {
            return result("PROCESS", "DURATION", fieldName, 0.91d, "FIELD_RULE");
        }

        var domain = domainFromGroup(joined);
        return switch (domain) {
            case "FORMULA" -> containsAny(name, "合计", "总计")
                    ? dynamic(fieldName, 0.98d)
                    : result("FORMULA", "RATIO", fieldName, 0.95d, "GROUP_RULE");
            case "TEST" -> result("TEST", "VALUE", fieldName, 0.94d, "GROUP_RULE");
            case "PROCESS" -> result("PROCESS", "APPLICATION_CONDITION", fieldName, 0.92d, "GROUP_RULE");
            case "CONCLUSION" -> result("CONCLUSION", "MAIN_CONCLUSION", fieldName, 0.94d, "GROUP_RULE");
            case "BASIC" -> dynamic(fieldName, 0.92d);
            default -> null;
        };
    }

    public String domainFromGroup(String value) {
        var normalized = normalize(value);
        if (containsAny(normalized, "基本信息", "基础信息", "项目信息")) return "BASIC";
        if (containsAny(normalized, "实验配方", "配方明细", "配方", "原料信息")) return "FORMULA";
        if (containsAny(normalized, "施工", "工艺", "固化条件", "应用条件")) return "PROCESS";
        if (containsAny(normalized, "性能测试", "干膜性能", "树脂物性", "测试数据", "测试结果", "试验结果")) return "TEST";
        if (containsAny(normalized, "结果", "结论", "小结")) return "CONCLUSION";
        return "OTHER";
    }

    private Resolution modelSuggestion(JsonNode value) {
        if (!value.isObject() || !validSemantic(value)) return null;
        var domain = value.path("domain").asText().toUpperCase(Locale.ROOT);
        var field = value.path("field").asText().toUpperCase(Locale.ROOT);
        var label = value.path("itemLabel").asText("").strip();
        if ("BASIC".equals(domain) && "SOURCE_IDENTITY".equals(field)
                && !sourceIdentityLabel(label)) return null;
        var confidence = Math.max(0d, Math.min(1d, value.path("confidence").asDouble(0d)));
        return result(domain, field, label, confidence, "MODEL_SUGGESTION");
    }

    private boolean sourceIdentityLabel(String value) {
        return Set.of("实验编号", "试验编号", "样品编号", "样品号", "样本编号",
                "配方编号", "配方号", "批次编号", "批号").contains(normalize(value));
    }

    private ArrayNode alternatives(Resolution deterministic, Resolution model, String name) {
        var result = objectMapper.createArrayNode();
        addAlternative(result, deterministic);
        if (result.size() < 2) addAlternative(result, model);
        if (result.isEmpty() && ambiguous(name)) {
            if (containsAny(normalize(name), "备注", "说明")) {
                result.add(semantic("CONCLUSION", "MAIN_CONCLUSION"));
                result.add(semantic("OTHER", "DYNAMIC_VALUE"));
            } else {
                result.add(semantic("TEST", "JUDGEMENT"));
                result.add(semantic("CONCLUSION", "MAIN_CONCLUSION"));
            }
        }
        return result;
    }

    private void addAlternative(ArrayNode values, Resolution resolution) {
        if (resolution == null || values.size() >= 2) return;
        for (var value : values) {
            if (resolution.domain().equals(value.path("domain").asText())
                    && resolution.field().equals(value.path("field").asText())) return;
        }
        values.add(semantic(resolution.domain(), resolution.field()));
    }

    private List<String> labelPath(JsonNode candidate, JsonNode fallback) {
        var result = new ArrayList<String>();
        var segments = candidate == null ? null : candidate.path("labelPathSegments");
        if ((segments == null || !segments.isArray()) && fallback != null) {
            segments = fallback.path("labelPathSegments");
        }
        if (segments != null && segments.isArray()) {
            for (var segment : segments) {
                var text = segment.asText("").strip();
                if (!text.isBlank() && !result.contains(text)) result.add(text);
            }
        }
        if (result.isEmpty()) {
            var path = candidate == null ? "" : candidate.path("labelPath").asText("");
            if (path.isBlank() && fallback != null) path = fallback.path("labelPath").asText("");
            for (var segment : path.split("\\s*>\\s*")) if (!segment.isBlank()) result.add(segment.strip());
        }
        return List.copyOf(result);
    }

    private boolean ambiguous(String value) {
        var normalized = normalize(value);
        return Set.of("状态", "说明", "结果").contains(normalized);
    }

    private String resolvedItemLabel(List<String> path, String fieldName, Resolution selected) {
        var fallback = selected.itemLabel().isBlank() ? fieldName : selected.itemLabel();
        if (!("TEST".equals(selected.domain()) && "VALUE".equals(selected.field()))) return fallback;

        var meaningful = new ArrayList<String>();
        for (var value : path) {
            var text = value == null ? "" : value.strip();
            if (text.isBlank() || genericTestContainer(text) || meaningful.contains(text)) continue;
            meaningful.add(text);
        }
        if (meaningful.isEmpty()) return fallback;
        if (meaningful.size() == 1) return meaningful.getFirst();

        var normalizedFallback = normalize(fallback);
        var alreadyQualified = meaningful.stream()
                .allMatch(value -> normalizedFallback.contains(normalize(value)));
        if (alreadyQualified) return fallback;
        return meaningful.getFirst() + "（" + String.join("，", meaningful.subList(1, meaningful.size())) + "）";
    }

    private boolean genericTestContainer(String value) {
        return Set.of("性能测试", "干膜性能测试", "测试数据记录", "测试结果", "试验结果", "树脂物性")
                .contains(normalize(value));
    }

    private boolean sameSemantic(Resolution left, Resolution right) {
        return left.domain().equals(right.domain()) && left.field().equals(right.field());
    }

    private Resolution basic(String field, String label, double confidence) {
        return result("BASIC", field, label, confidence, "FIELD_RULE");
    }

    private Resolution dynamic(String label, double confidence) {
        return result("OTHER", "DYNAMIC_VALUE", label, confidence, "GROUP_RULE");
    }

    private Resolution result(String domain, String field, String label, double confidence, String source) {
        return new Resolution(domain, field, label == null ? "" : label.strip(), confidence, source);
    }

    private String firstText(JsonNode value, String... keys) {
        if (value == null) return "";
        for (var key : keys) {
            var text = value.path(key).asText("").strip();
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s　:：/\\\\()（）,，·._-]+", "");
    }

    private boolean containsAny(String value, String... needles) {
        for (var needle : needles) if (value.contains(normalize(needle))) return true;
        return false;
    }

    private record Resolution(
            String domain, String field, String itemLabel, double confidence, String source
    ) {}
}
