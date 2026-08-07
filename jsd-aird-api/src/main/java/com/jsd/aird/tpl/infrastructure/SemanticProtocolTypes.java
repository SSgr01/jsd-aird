package com.jsd.aird.tpl.infrastructure;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared vocabulary for model schemas, protocol validation and compiler payloads. */
final class SemanticProtocolTypes {

    static final Set<String> VALUE_TYPES = Set.of(
            "string", "number", "integer", "boolean", "date", "datetime", "time", "duration"
    );
    static final Set<String> EDITABILITY = Set.of(
            "EDITABLE", "READ_ONLY", "CONDITIONAL", "UNKNOWN"
    );
    static final Set<String> VALUE_SOURCES = Set.of(
            "USER_INPUT", "FORMULA", "REFERENCE", "STATIC", "MIXED", "UNKNOWN"
    );

    private static final Map<String, String> VALUE_TYPE_ALIASES = Map.ofEntries(
            Map.entry("text", "string"),
            Map.entry("string", "string"),
            Map.entry("文本", "string"),
            Map.entry("numeric", "number"),
            Map.entry("number", "number"),
            Map.entry("float", "number"),
            Map.entry("decimal", "number"),
            Map.entry("数值", "number"),
            Map.entry("int", "integer"),
            Map.entry("integer", "integer"),
            Map.entry("整数", "integer"),
            Map.entry("bool", "boolean"),
            Map.entry("boolean", "boolean"),
            Map.entry("布尔", "boolean"),
            Map.entry("date", "date"),
            Map.entry("datetime", "datetime"),
            Map.entry("time", "time"),
            Map.entry("duration", "duration"
            )
    );

    private static final Map<String, String> EDITABILITY_ALIASES = Map.ofEntries(
            Map.entry("EDITABLE", "EDITABLE"), Map.entry("READ_WRITE", "EDITABLE"),
            Map.entry("可编辑", "EDITABLE"), Map.entry("READ_ONLY", "READ_ONLY"),
            Map.entry("READONLY", "READ_ONLY"), Map.entry("只读", "READ_ONLY"),
            Map.entry("CONDITIONAL", "CONDITIONAL"), Map.entry("条件", "CONDITIONAL"),
            Map.entry("UNKNOWN", "UNKNOWN")
    );

    private static final Map<String, String> VALUE_SOURCE_ALIASES = Map.ofEntries(
            Map.entry("USER_INPUT", "USER_INPUT"), Map.entry("MANUAL_INPUT", "USER_INPUT"),
            Map.entry("MANUAL", "USER_INPUT"), Map.entry("用户输入", "USER_INPUT"),
            Map.entry("FORMULA", "FORMULA"), Map.entry("公式", "FORMULA"),
            Map.entry("REFERENCE", "REFERENCE"), Map.entry("引用", "REFERENCE"),
            Map.entry("STATIC", "STATIC"), Map.entry("静态", "STATIC"),
            Map.entry("MIXED", "MIXED"), Map.entry("UNKNOWN", "UNKNOWN")
    );

    private SemanticProtocolTypes() {
    }

    static String normalizeValueType(String value) {
        return normalize(value, VALUE_TYPE_ALIASES);
    }

    static String normalizeEditability(String value) {
        return normalize(value, EDITABILITY_ALIASES);
    }

    static String normalizeValueSource(String value) {
        return normalize(value, VALUE_SOURCE_ALIASES);
    }

    private static String normalize(String value, Map<String, String> aliases) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        var key = value.strip().toUpperCase(Locale.ROOT);
        return aliases.getOrDefault(key, aliases.getOrDefault(value.strip(), "UNKNOWN"));
    }
}
