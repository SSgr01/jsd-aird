package com.jsd.aird.tpl.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.port.TemplateImportRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Turns reviewed recognition results into the canonical JSON Schema + mapping pair.
 * The customer-facing UI edits the business field model stored in the schema extension;
 * it never needs to understand JSON Pointer or locator details.
 */
@Component
public class TemplateRecognitionCompiler {

    public static final String FIELD_MODEL_KEY = "x-jsd-field-model";

    private final ObjectMapper objectMapper;

    public TemplateRecognitionCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CompiledRecognition compile(
            ObjectNode baseSchema,
            List<TemplateImportRepository.RecognitionSuggestionView> suggestions,
            TemplateFormat format
    ) {
        var mapping = objectMapper.createArrayNode();
        var fieldModel = objectMapper.createObjectNode();
        var groups = objectMapper.createArrayNode();
        var fields = objectMapper.createArrayNode();
        var groupIds = new LinkedHashMap<String, String>();
        var seenPaths = new java.util.HashSet<String>();
        var seenLocations = new java.util.HashMap<String, String>();
        var seenStructuredRegions = new java.util.HashMap<String, String>();

        suggestions.stream()
                .filter(suggestion -> !"REJECTED".equals(suggestion.decision()))
                .sorted(java.util.Comparator
                        .comparingDouble(TemplateImportRepository.RecognitionSuggestionView::confidence)
                        .reversed()
                        .thenComparingInt(item -> "MODEL".equals(item.source()) ? 0 : 1))
                .forEach(suggestion -> {
                    var payload = suggestion.payload();
                    var dataPath = payload.path("dataPath").asText("");
                    var locator = payload.path("locator");
                    var sheet = locator.path("sheetId").asText(locator.path("sheetName").asText(""));
                    var address = locator.path("address").asText(locator.path("range").asText(""));
                    var location = sheet + "|" + address;
                    var kind = recognitionKind(suggestion.suggestionType(), payload);
                    var regionId = payload.path("regionId").asText("");
                    var duplicateStructuredRegion = !"SCALAR".equals(kind) && StringUtils.hasText(regionId)
                            && seenStructuredRegions.containsKey(regionId);
                    if (!StringUtils.hasText(dataPath) || !seenPaths.add(dataPath)
                            || duplicateStructuredRegion
                            || (StringUtils.hasText(sheet) && StringUtils.hasText(address)
                            && seenLocations.containsKey(location)
                            && !suggestion.source().equals(seenLocations.get(location)))) {
                        return;
                    }
                    if (StringUtils.hasText(sheet) && StringUtils.hasText(address)) {
                        seenLocations.putIfAbsent(location, suggestion.source());
                    }
                    if (!"SCALAR".equals(kind) && StringUtils.hasText(regionId)) {
                        seenStructuredRegions.putIfAbsent(regionId, suggestion.source());
                    }
                    var fieldName = payload.path("fieldName").asText("业务字段");
                    var groupName = payload.path("groupName").asText("").strip();
                    if (groupName.isBlank()) {
                        groupName = inferGroup(fieldName);
                    }
                    groupName = GroupNameNormalizer.normalize(groupName);
                    var groupId = groupIds.computeIfAbsent(
                            groupName,
                            ignored -> "group-" + (groupIds.size() + 1)
                    );
                    var bindingId = UUID.randomUUID().toString();
                    var field = objectMapper.createObjectNode()
                            .put("id", bindingId)
                            .put("bindingId", bindingId)
                            .put("dataPath", dataPath)
                            .put("groupId", groupId)
                            .put("name", fieldName)
                            .put("kind", kind)
                            .put("valueType", payload.path("valueType").asText("string"))
                            .put("required", payload.path("required").asBoolean(false))
                            .put("unit", payload.path("unit").asText(""))
                            .put("description", payload.path("reason").asText(""))
                            .put("interpretation", businessInterpretation(kind, fieldName, payload))
                            .put("confidence", suggestion.confidence())
                            .put("recognitionItemId", suggestion.id().toString())
                            .put("reviewStatus", "ACCEPTED".equals(suggestion.decision())
                                    ? "CONFIRMED"
                                    : "NEEDS_CONFIRMATION");
                    if (payload.path("columns").isArray()) {
                        field.set("columns", payload.path("columns").deepCopy());
                    }
                    if (payload.path("tableModel").isObject()) {
                        field.set("tableModel", payload.path("tableModel").deepCopy());
                    }
                    if (payload.path("matrixModel").isObject()) {
                        field.set("matrixModel", payload.path("matrixModel").deepCopy());
                    }
                    fields.add(field);

                    applySchema(baseSchema, payload, kind);
                    if (format == TemplateFormat.XLSX) {
                        mapping.add(toBinding(bindingId, suggestion.id(), payload, kind));
                    }
                });

        groupIds.forEach((name, id) -> groups.add(objectMapper.createObjectNode()
                .put("id", id)
                .put("name", name)
                .put("groupCode", GroupNameNormalizer.code(name))
                .put("order", groups.size())));
        if (groups.isEmpty()) {
            groups.add(objectMapper.createObjectNode()
                    .put("id", "group-other")
                    .put("name", "其他信息")
                    .put("groupCode", "OTHER_INFORMATION")
                    .put("order", 0));
        }
        fieldModel.set("groups", groups);
        fieldModel.set("fields", fields);
        fieldModel.put("modelVersion", 3);
        baseSchema.set(FIELD_MODEL_KEY, fieldModel);
        return new CompiledRecognition(baseSchema, mapping, fieldModel);
    }

    private ObjectNode toBinding(String bindingId, UUID recognitionItemId, JsonNode payload, String kind) {
        var binding = objectMapper.createObjectNode();
        binding.put("bindingId", bindingId);
        binding.put("fieldCode", payload.path("fieldCode").asText("AUTO.FIELD"));
        binding.put("dataPath", payload.path("dataPath").asText());
        binding.put("role", "SCALAR".equals(kind) ? "FIELD" : "REPEAT_REGION");
        binding.put("locatorType", payload.path("locatorType").asText("CELL_RANGE"));
        binding.set("locator", payload.path("locator").deepCopy());
        binding.put("syncDirection", "TWO_WAY");
        binding.put("primaryBinding", true);
        binding.put("bindingStatus", "VALID");
        binding.set("diagnostic", objectMapper.createObjectNode()
                .put("source", "AUTO_RECOGNIZED")
                .put("recognitionItemId", recognitionItemId.toString())
                .put("kind", kind)
                .put("groupName", payload.path("groupName").asText("")));
        if (payload.path("matrixModel").isObject()) {
            binding.withObject("diagnostic").set("matrixModel", payload.path("matrixModel").deepCopy());
        }
        if (payload.path("tableModel").isObject()) {
            binding.withObject("diagnostic").set("tableModel", payload.path("tableModel").deepCopy());
        }
        return binding;
    }

    private void applySchema(ObjectNode root, JsonNode payload, String kind) {
        var segments = payload.path("dataPath").asText("").split("/");
        ObjectNode current = root;
        for (int index = 1; index < segments.length; index++) {
            var segment = unescape(segments[index]);
            if (segment.isBlank()) {
                continue;
            }
            var properties = current.withObject("properties");
            var last = index == segments.length - 1;
            if (last) {
                properties.set(segment, schemaFor(payload, kind));
                if (payload.path("required").asBoolean(false)) {
                    var required = current.withArray("required");
                    var exists = false;
                    for (JsonNode item : required) {
                        exists |= segment.equals(item.asText());
                    }
                    if (!exists) {
                        required.add(segment);
                    }
                }
            } else {
                var child = properties.path(segment);
                if (!child.isObject()) {
                    child = objectMapper.createObjectNode()
                            .put("type", "object")
                            .set("properties", objectMapper.createObjectNode());
                    properties.set(segment, child);
                }
                current = (ObjectNode) child;
            }
        }
    }

    private ObjectNode schemaFor(JsonNode payload, String kind) {
        var schema = objectMapper.createObjectNode()
                .put("title", payload.path("fieldName").asText("业务字段"))
                .put("x-field-code", payload.path("fieldCode").asText("AUTO.FIELD"))
                .put("x-region-kind", kind);
        if ("ROW_TABLE".equals(kind)) {
            schema.put("type", "array");
            var item = objectMapper.createObjectNode().put("type", "object");
            var properties = objectMapper.createObjectNode();
            var columns = payload.path("columns");
            if (columns.isArray()) {
                var number = 1;
                for (JsonNode column : columns) {
                    var code = column.path("code").asText("column" + number++);
                    properties.set(code, objectMapper.createObjectNode()
                            .put("type", normalizeType(column.path("valueType").asText("string")))
                            .put("title", column.path("name").asText(code)));
                }
            }
            item.set("properties", properties);
            schema.set("items", item);
        } else if ("MATRIX".equals(kind)) {
            schema.put("type", "array");
            if ("RECORD_SET".equals(payload.path("matrixModel").path("semanticMode").asText())) {
                schema.set("items", objectMapper.createObjectNode()
                        .put("type", "object")
                        .put("additionalProperties", true));
            } else {
                schema.set("items", objectMapper.createObjectNode()
                        .put("type", "array")
                        .set("items", objectMapper.createObjectNode()
                                .put("type", normalizeType(payload.path("valueType").asText("number")))));
            }
            if (payload.path("matrixModel").isObject()) {
                schema.set("x-semantic-model", payload.path("matrixModel").deepCopy());
            }
        } else {
            var valueType = payload.path("valueType").asText("string");
            schema.put("type", normalizeType(valueType));
            if ("date".equals(valueType)) {
                schema.put("format", "date");
            } else if ("datetime".equals(valueType)) {
                schema.put("format", "date-time");
            } else if ("time".equals(valueType)) {
                schema.put("format", "time");
            } else if ("duration".equals(valueType)) {
                schema.put("format", "duration");
            }
        }
        if (StringUtils.hasText(payload.path("unit").asText())) {
            schema.put("x-unit", payload.path("unit").asText());
        }
        return schema;
    }

    private String normalizeType(String valueType) {
        return switch (valueType.toLowerCase(Locale.ROOT)) {
            case "number", "integer", "boolean", "array", "object" -> valueType.toLowerCase(Locale.ROOT);
            case "date", "datetime", "time", "duration" -> "string";
            default -> "string";
        };
    }

    private String recognitionKind(String suggestionType, JsonNode payload) {
        var declared = payload.path("kind").asText("").toUpperCase(Locale.ROOT);
        var type = suggestionType == null ? "" : suggestionType.toUpperCase(Locale.ROOT);
        if ("MATRIX".equals(declared) || type.contains("MATRIX")) {
            return "MATRIX";
        }
        if ("ROW_TABLE".equals(declared) || type.contains("TABLE")
                || "REPEAT_REGION".equals(payload.path("role").asText())) {
            return "ROW_TABLE";
        }
        return "SCALAR";
    }

    private String businessInterpretation(String kind, String name, JsonNode payload) {
        if (StringUtils.hasText(payload.path("interpretation").asText())) {
            return payload.path("interpretation").asText();
        }
        return switch (kind) {
            case "ROW_TABLE" -> "系统认为“" + name + "”中每一行代表一条业务记录。";
            case "MATRIX" -> "系统认为“" + name + "”的行和列分别表示两类条件，交叉位置填写结果。";
            default -> "系统认为这里用于填写“" + name + "”。";
        };
    }

    private String inferGroup(String fieldName) {
        if (containsAny(fieldName, "原料", "物料", "配方", "树脂", "单体")) {
            return "原料信息";
        }
        if (containsAny(fieldName, "温度", "时间", "工艺", "搅拌", "反应", "压力")) {
            return "工艺条件";
        }
        if (containsAny(fieldName, "性能", "测试", "检测", "强度", "粘度", "结果")) {
            return "性能测试";
        }
        if (containsAny(fieldName, "审核", "批准", "签字", "确认")) {
            return "审核信息";
        }
        if (containsAny(fieldName, "名称", "编号", "日期", "产品", "项目", "批次")) {
            return GroupNameNormalizer.BASIC_INFORMATION;
        }
        return "其他信息";
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) {
            if (value.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String unescape(String value) {
        return value.replace("~1", "/").replace("~0", "~");
    }

    public record CompiledRecognition(ObjectNode schema, ArrayNode mapping, JsonNode fieldModel) {
    }
}
