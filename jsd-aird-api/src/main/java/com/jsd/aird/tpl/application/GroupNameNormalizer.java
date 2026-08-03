package com.jsd.aird.tpl.application;

import java.util.Locale;
import java.util.Map;

/** Keeps system-generated business groups stable without rewriting customer-defined groups. */
public final class GroupNameNormalizer {

    public static final String BASIC_INFORMATION = "基础信息";

    private static final Map<String, String> SYSTEM_ALIASES = Map.ofEntries(
            Map.entry("基本信息", BASIC_INFORMATION),
            Map.entry("基础信息", BASIC_INFORMATION),
            Map.entry("基础资料", BASIC_INFORMATION),
            Map.entry("通用信息", BASIC_INFORMATION),
            Map.entry("原料信息", "原料信息"),
            Map.entry("物料信息", "原料信息"),
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
        var direct = SYSTEM_ALIASES.get(candidate);
        if (direct != null) return direct;
        var compact = candidate.replaceAll("[\\s_-]+", "").toLowerCase(Locale.ROOT);
        for (var entry : SYSTEM_ALIASES.entrySet()) {
            if (entry.getKey().replaceAll("[\\s_-]+", "").toLowerCase(Locale.ROOT).equals(compact)) {
                return entry.getValue();
            }
        }
        return candidate;
    }

    public static String code(String normalizedName) {
        return switch (normalize(normalizedName)) {
            case BASIC_INFORMATION -> "BASIC_INFORMATION";
            case "原料信息" -> "MATERIAL_INFORMATION";
            case "工艺条件" -> "PROCESS_CONDITIONS";
            case "性能测试" -> "PERFORMANCE_TEST";
            case "审核信息" -> "REVIEW_INFORMATION";
            case "其他信息" -> "OTHER_INFORMATION";
            default -> "CUSTOM";
        };
    }
}
