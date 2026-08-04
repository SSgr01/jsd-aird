package com.jsd.aird.tpl.application;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Keeps system-generated business groups stable without rewriting customer-defined groups. */
public final class GroupNameNormalizer {

    public static final String BASIC_INFORMATION = "基础信息";

    private static final Map<String, String> SYSTEM_ALIASES = Map.ofEntries(
            Map.entry("基本信息", BASIC_INFORMATION),
            Map.entry("基础信息", BASIC_INFORMATION),
            Map.entry("基础资料", BASIC_INFORMATION),
            Map.entry("通用信息", BASIC_INFORMATION),
            Map.entry("产品基础信息", BASIC_INFORMATION),
            Map.entry("任务单元信息", BASIC_INFORMATION),
            Map.entry("原料信息", "原料信息"),
            Map.entry("物料信息", "原料信息"),
            Map.entry("配方明细表", "配方明细"),
            Map.entry("配方明细", "配方明细"),
            Map.entry("包装与产量信息", "包装信息"),
            Map.entry("包装信息", "包装信息"),
            Map.entry("制单与完成人信息", "审核信息"),
            Map.entry("签字栏", "审核信息"),
            Map.entry("工艺条件", "工艺条件"),
            Map.entry("性能测试", "性能测试"),
            Map.entry("测试数据", "性能测试"),
            Map.entry("审核信息", "审核信息"),
            Map.entry("其他信息", "其他信息")
    );

    private GroupNameNormalizer() {
    }

    public static String normalize(String value) {
        var candidate = value == null ? "" : value.strip();
        if (candidate.isBlank()) return BASIC_INFORMATION;
        var direct = normalizeKnownAlias(candidate);
        if (direct != null) return direct;
        return candidate;
    }

    /** Normalizes a group explicitly supplied by a customer; English names remain valid here. */
    public static String normalizeCustomerDefined(String value) {
        return normalize(value);
    }

    /** Validates a group suggested by the model; internal keys are never customer-facing groups. */
    public static Optional<String> normalizeModelSuggestion(String value) {
        var candidate = value == null ? "" : value.strip();
        if (candidate.isBlank()) return Optional.empty();
        var known = normalizeKnownAlias(candidate);
        if (known != null) return Optional.of(known);
        if (!candidate.matches(".*\\p{IsHan}.*") || candidate.length() > 20
                || candidate.matches(".*[_\\-.].*") || candidate.matches(".*[A-Za-z].*")) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private static String normalizeKnownAlias(String candidate) {
        var direct = SYSTEM_ALIASES.get(candidate);
        if (direct != null) return direct;
        var compact = candidate.replaceAll("[\\s_-]+", "").toLowerCase(Locale.ROOT);
        for (var entry : SYSTEM_ALIASES.entrySet()) {
            if (entry.getKey().replaceAll("[\\s_-]+", "").toLowerCase(Locale.ROOT).equals(compact)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Compatibility helper for callers handling model output and a safe inferred fallback. */
    public static String normalizeCustomerGroup(String value, String fallback) {
        return normalizeModelSuggestion(value).orElseGet(() -> normalizeCustomerDefined(fallback));
    }

    public static boolean isCustomerGroup(String value) {
        if (value == null || value.isBlank() || value.length() > 32) return false;
        if (!value.matches(".*[\\u4e00-\\u9fff].*")) return false;
        return !value.matches("^[A-Za-z_][A-Za-z0-9_.-]*$");
    }

    /** Infers the safe customer group from the owning block and business name. */
    public static String inferFromBlock(String blockType, String businessName) {
        var text = businessName == null ? "" : businessName;
        if (text.contains("配方") || text.contains("原料")
                || "ROW_TABLE".equals(blockType) || "MATRIX".equals(blockType)) return "配方明细";
        if (text.contains("包装") || text.contains("产量")) return "包装信息";
        if (text.contains("制单") || text.contains("完成人") || text.contains("投料")
                || text.contains("监秤") || text.contains("监管") || text.contains("签字")) return "审核信息";
        if (text.contains("产品") || text.contains("基础") || text.contains("任务")
                || text.contains("类别") || text.contains("品名") || text.contains("订单")) return BASIC_INFORMATION;
        return switch (blockType == null ? "" : blockType) {
            case "LOOKUP_TABLE" -> "明细信息";
            case "SIGNATURE_BLOCK", "CONFIRMATION_BLOCK" -> "审核信息";
            default -> BASIC_INFORMATION;
        };
    }

    public static String infer(String blockType, String businessName) {
        return inferFromBlock(blockType, businessName);
    }

    public static String code(String normalizedName) {
        return switch (normalize(normalizedName)) {
            case BASIC_INFORMATION -> "BASIC_INFORMATION";
            case "原料信息" -> "MATERIAL_INFORMATION";
            case "工艺条件" -> "PROCESS_CONDITIONS";
            case "性能测试" -> "PERFORMANCE_TEST";
            case "审核信息" -> "REVIEW_INFORMATION";
            case "配方明细" -> "FORMULA_DETAIL";
            case "包装信息" -> "PACKAGING_INFORMATION";
            case "其他信息" -> "OTHER_INFORMATION";
            default -> "CUSTOM";
        };
    }
}
