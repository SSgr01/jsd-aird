package com.jsd.aird.spc.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import java.util.*;

/** Shared field contract for forms, persisted snapshots and AI context. No measurement values are inferred. */
public final class SpectrumTestMethods {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ArrayNode SCHEMA;
    static {
        try (var input = SpectrumTestMethods.class.getResourceAsStream("/spectrum-methods.json")) {
            SCHEMA = (ArrayNode) JSON.readTree(Objects.requireNonNull(input));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private SpectrumTestMethods() {}
    public static ArrayNode schema() { return SCHEMA.deepCopy(); }
    public static JsonNode method(String code) {
        for (var entry : SCHEMA) if (entry.path("code").asText().equals(code)) return entry;
        throw invalid("不支持的测试方法");
    }
    public static ObjectNode snapshot(JsonNode input) {
        if (input == null || !input.isObject() || !input.path("schemaVersion").isIntegralNumber() || input.path("schemaVersion").asInt() != 1) throw invalid("测试条件版本无效");
        var definition = method(input.path("methodType").asText());
        var out = JSON.createObjectNode().put("schemaVersion", 1).put("methodType", definition.path("code").asText());
        for (var key : List.of("presetId", "presetRevision", "presetName")) {
            if (input.hasNonNull(key)) out.put(key, text(input.get(key), key, 260));
        }
        for (var target : List.of("instrument", "conditions")) {
            if (input.has(target) && !input.get(target).isObject()) throw invalid("测试条件必须是对象");
            var values = out.putObject(target);
            var allowed = new HashSet<String>();
            for (var field : definition.path("fields")) {
                if (!field.path("target").asText("conditions").equals(target)) continue;
                var key = field.path("key").asText(); allowed.add(key);
                var value = input.path(target).path(key);
                if (value.isMissingNode() || value.isNull() || value.isTextual() && value.asText().isBlank()) continue;
                var label = field.path("label").asText();
                switch (field.path("kind").asText("text")) {
                    case "number", "integer" -> {
                        number(value, field, label);
                        values.set(key, value.deepCopy());
                    }
                    case "program", "specimens" -> {
                        if (!value.isArray() || value.size() > 50) throw invalid(label + "最多 50 行");
                        var rows = values.putArray(key);
                        for (var item : value) {
                            if (!item.isObject()) throw invalid(label + "格式无效");
                            var row = JSON.createObjectNode();
                            var keys = field.path("kind").asText().equals("program")
                                    ? List.of("temperature", "rate", "hold") : List.of("name", "width", "thickness");
                            var iterator = item.fieldNames();
                            while (iterator.hasNext()) if (!keys.contains(iterator.next())) throw invalid(label + "含未知字段");
                            for (var column : keys) if (item.hasNonNull(column)) {
                                if (column.equals("name")) row.put(column, text(item.get(column), label, 160));
                                else {
                                    var rule = JSON.createObjectNode().put("min", column.equals("temperature") ? -273.15 : 0);
                                    number(item.get(column), rule, label);
                                    row.set(column, item.get(column));
                                }
                            }
                            if (!row.isEmpty()) rows.add(row);
                        }
                    }
                    default -> {
                        var string = text(value, label, 500);
                        if (field.path("options").isArray()) {
                            boolean valid = false;
                            for (var option : field.get("options")) if (option.asText().equals(string)) valid = true;
                            if (!valid) throw invalid(label + "选项无效");
                        }
                        values.put(key, string);
                    }
                }
            }
            var fields = input.path(target).fieldNames();
            while (fields.hasNext()) if (!allowed.contains(fields.next())) throw invalid("测试条件含不适用于当前方法的字段");
        }
        var conditions = out.path("conditions");
        if (conditions.has("rangeMin") && conditions.has("rangeMax")
                && conditions.get("rangeMin").asDouble() >= conditions.get("rangeMax").asDouble())
            throw invalid("扫描下限必须小于上限");
        if (input.hasNonNull("notes")) out.put("notes", text(input.get("notes"), "方法详情", 6000));
        if (input.hasNonNull("legacyText")) out.put("legacyText", text(input.get("legacyText"), "原测试条件", 10000));
        return out;
    }
    public static String summary(JsonNode metadata, String legacy) {
        if (metadata == null || !metadata.hasNonNull("testMethodSnapshot")) return legacy;
        var snap = snapshot(metadata.get("testMethodSnapshot"));
        var definition = method(snap.path("methodType").asText());
        var lines = new ArrayList<String>(); lines.add("测试方法：" + definition.path("name").asText());
        if (snap.hasNonNull("presetName")) lines.add("采用预设：" + snap.path("presetName").asText() + "（" + snap.path("presetRevision").asText("未记录版本") + "）");
        for (var field : definition.path("fields")) {
            var value = snap.path(field.path("target").asText("conditions")).path(field.path("key").asText());
            if (value.isMissingNode()) continue;
            String rendered;
            if (value.isArray()) {
                var rows = new ArrayList<String>();
                for (int i = 0; i < value.size(); i++) {
                    var row = value.get(i);
                    if (field.path("kind").asText().equals("program")) rows.add("第" + (i + 1) + "段：目标 " + row.path("temperature").asText("未填") + "℃，升温速率 " + row.path("rate").asText("未填") + "℃/min，保持 " + row.path("hold").asText("未填") + " min");
                    else rows.add(row.path("name").asText("试样" + (i + 1)) + "：宽 " + row.path("width").asText("未填") + " mm，厚 " + row.path("thickness").asText("未填") + " mm");
                }
                rendered = String.join("；", rows);
            } else rendered = value.asText() + (field.has("unit") ? " " + field.path("unit").asText() : "");
            if (!rendered.isBlank()) lines.add(field.path("label").asText() + "：" + rendered);
        }
        if (snap.hasNonNull("notes") && !snap.path("notes").asText().isBlank()) lines.add("方法详情／补充说明：" + snap.path("notes").asText());
        if (snap.hasNonNull("legacyText") && !snap.path("legacyText").asText().isBlank()) lines.add("原登记测试条件（未拆解）：" + snap.path("legacyText").asText());
        var result = String.join("\n", lines);
        if (result.length() > 10000) throw invalid("测试条件摘要超过 10000 字，请精简补充说明");
        return result;
    }
    public static JsonNode normalizeMetadata(JsonNode metadata) {
        if (metadata == null || metadata.isNull()) return JSON.createObjectNode();
        if (!metadata.isObject()) throw invalid("图谱元数据必须是对象");
        ObjectNode result = metadata.deepCopy();
        if (result.hasNonNull("testMethodSnapshot")) result.set("testMethodSnapshot", snapshot(result.get("testMethodSnapshot")));
        return result;
    }
    public static ArrayNode warnings(JsonNode snap) {
        var result = JSON.createArrayNode();
        if (snap == null || !snap.isObject()) return result;
        var definition = method(snap.path("methodType").asText());
        var missing = new ArrayList<String>();
        for (var key : definition.path("comparisonKeys")) {
            if (snap.path("methodType").asText().equals("MECHANICAL") && List.of("width", "thickness").contains(key.asText())) {
                var specimens = snap.path("conditions").path("specimens");
                boolean complete = specimens.isArray() && !specimens.isEmpty();
                for (var specimen : specimens) if (!specimen.path(key.asText()).isNumber() || specimen.path(key.asText()).asDouble() <= 0) complete = false;
                if (complete) continue;
            }
            var value = snap.path("conditions").path(key.asText());
            if (value.isMissingNode() || value.isNull() || value.isTextual() && (value.asText().isBlank() || value.asText().equals("未确认")) || value.isArray() && value.isEmpty()) {
                for (var field : definition.path("fields")) if (field.path("key").equals(key)) missing.add(field.path("label").asText());
            }
        }
        if (!missing.isEmpty()) result.add("尚缺／未确认：" + String.join("、", missing) + "。按当前问题限制比较或计算，不影响已有观察。");
        if (snap.path("methodType").asText().equals("UV") && snap.path("conditions").path("rangeMin").asDouble(999) < 190)
            result.add("紫外起始波长低于常规 UV-Vis 范围，请核对原始方法；不得自动纠正。");
        if (snap.path("conditions").path("solvent").asText().toUpperCase(Locale.ROOT).contains("PM")) result.add("PM 的化学全名及适用波段需核对，不能猜测成分。");
        var concentration = snap.path("conditions").path("concentration").asText();
        if (!concentration.isBlank() && (concentration.matches("^[−-].*|^0(?:\\.0+)?(?:\\s.*)?$") || concentration.matches("^[+]?\\d+(?:\\.\\d+)?$")))
            result.add("浓度／稀释倍数需核对正值和单位（或倍数说明），未据此计算。");
        if (snap.path("methodType").asText().equals("GC")) {
            var program = snap.path("conditions").path("ovenProgram");
            boolean incomplete = !program.isArray() || program.isEmpty();
            for (int i = 0; i < program.size(); i++) if (!program.get(i).has("temperature") || !program.get(i).has("hold") || i > 0 && !program.get(i).has("rate")) incomplete = true;
            if (incomplete) result.add("升温程序不完整，不能根据起终温及总时间推算升温速率。");
        }
        return result;
    }
    private static String text(JsonNode value, String label, int max) {
        if (!value.isTextual() || value.asText().length() > max) throw invalid(label + "文本格式或长度无效");
        return value.asText().trim();
    }
    private static void number(JsonNode value, JsonNode rule, String label) {
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < rule.path("min").asDouble(-Double.MAX_VALUE)
                || rule.has("max") && value.asDouble() > rule.get("max").asDouble()
                || rule.path("kind").asText().equals("integer") && value.asDouble() != Math.rint(value.asDouble())) throw invalid(label + "数值无效");
        if (rule.path("min").asDouble(-1) == 0 && value.asDouble() == 0 && !List.of("相对湿度", "超声时间", "升温程序").contains(label)) throw invalid(label + "必须大于 0");
    }
    private static ApiException invalid(String message) { return new ApiException(ApiErrorCode.VALIDATION_ERROR, message); }
}
