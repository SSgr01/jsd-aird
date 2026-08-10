package com.jsd.aird.tpl.infrastructure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Batch semantic protocol. Geometry is supplied as immutable region context. */
final class RegionSemanticBatchProtocol {

    static final int VERSION = 2;
    private static final Pattern RANGE = Pattern.compile(
            "^[A-Z]{1,4}[1-9][0-9]*(?::[A-Z]{1,4}[1-9][0-9]*)?$"
    );
    private final ObjectMapper objectMapper;

    RegionSemanticBatchProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode responseSchema() {
        try {
            var schema = (ObjectNode) objectMapper.readTree("""
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "type":"object","additionalProperties":false,
                      "required":["recognitionProtocolVersion","regions","qualityIssues"],
                      "properties":{
                        "recognitionProtocolVersion":{"const":2},
                        "regions":{"type":"array","items":{
                          "type":"object","additionalProperties":false,
                          "required":["regionId","businessName","rowDimensions","rowAttributes","fieldRelations","qualityIssues"],
                          "properties":{
                            "regionId":{"type":"string","minLength":1},
                            "businessName":{"type":"string"},
                            "rowDimensions":{"type":"array","items":{"$ref":"#/$defs/axis"}},
                            "rowAttributes":{"type":"array","items":{"$ref":"#/$defs/axis"}},
                            "fieldRelations":{"type":"array","items":{"type":"object","additionalProperties":false,
                              "required":["temporaryId","labelRange","valueRange","businessName","valueType","required","editability","valueSource","unit","condition"],
                              "properties":{
                                "temporaryId":{"type":"string","minLength":1},"labelRange":{"type":"string"},"valueRange":{"type":"string"},
                                "businessName":{"type":"string","minLength":1},"valueType":{"enum":["string","number","integer","boolean","date","datetime","time","duration"]},"required":{"type":"boolean"},
                                "editability":{"enum":["EDITABLE","READ_ONLY","CONDITIONAL","UNKNOWN"]},"valueSource":{"enum":["USER_INPUT","FORMULA","REFERENCE","STATIC","MIXED","UNKNOWN"]},"unit":{"type":"string"},"condition":{"type":"string"},
                                "relationType":{"type":"string"},"semanticKeySuggestion":{"type":"string"},"groupNameSuggestion":{"type":"string"}
                              }
                            }},
                            "columnMemberSemantic":{"type":"object","additionalProperties":true},
                            "measureSemantic":{"type":"object","additionalProperties":true},
                            "qualityIssues":{"type":"array","items":{"type":"object","additionalProperties":true}}
                          }
                        }},
                        "qualityIssues":{"type":"array","items":{"type":"object","additionalProperties":true}}
                      },
                      "$defs":{
                        "axis":{
                          "type":"object","additionalProperties":false,
                          "required":["sourceRange","name","fillMerged"],
                          "properties":{
                            "code":{"type":"string"},"name":{"type":"string"},
                            "sourceRange":{"type":"string"},"fillMerged":{"type":"boolean"},
                            "role":{"enum":["ROW_DIMENSION","ROW_ATTRIBUTE","DIMENSION","ATTRIBUTE"]},
                            "optional":{"type":"boolean"}
                          }
                        }
                      }
                    }
                    """);
            var relation = schema.path("properties").path("regions").path("items")
                    .path("properties").path("fieldRelations").path("items").path("properties");
            ((ObjectNode) relation.path("valueType")).set("enum", enumValues(SemanticProtocolTypes.VALUE_TYPES));
            ((ObjectNode) relation.path("editability")).set("enum", enumValues(SemanticProtocolTypes.EDITABILITY));
            ((ObjectNode) relation.path("valueSource")).set("enum", enumValues(SemanticProtocolTypes.VALUE_SOURCES));
            return schema;
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载批量区域语义协议", exception);
        }
    }

    private ArrayNode enumValues(Set<String> values) {
        var result = objectMapper.createArrayNode();
        values.stream().sorted().forEach(result::add);
        return result;
    }

    ObjectNode validate(JsonNode response, JsonNode context) {
        requireObject(response, "批量区域语义响应");
        var root = (ObjectNode) response.deepCopy();
        exactKeys(root, Set.of("recognitionProtocolVersion", "regions", "qualityIssues"), "批量区域语义响应");
        // Several OpenAI-compatible providers omit an optional-looking empty
        // top-level collection even when the supplied JSON schema marks it as
        // required. Missing means no workbook-level issue and can be repaired
        // deterministically; a present non-array value is still rejected.
        if (!root.has("qualityIssues") || root.path("qualityIssues").isNull()) {
            root.putArray("qualityIssues");
        }
        require(root.path("recognitionProtocolVersion").asInt(-1) == VERSION,
                "批量区域语义响应 recognitionProtocolVersion 必须为 2");
        require(root.path("regions").isArray(), "regions 必须是数组");
        require(root.path("qualityIssues").isArray(), "qualityIssues 必须是数组");

        var contexts = contexts(context);
        var valid = objectMapper.createArrayNode();
        var issues = (ArrayNode) root.path("qualityIssues").deepCopy();
        for (var item : root.path("regions")) {
            try {
                if (!item.isObject()) throw new IllegalArgumentException("区域语义项必须是对象");
                exactKeys(item, Set.of("regionId", "businessName", "rowDimensions", "rowAttributes",
                        "fieldRelations", "qualityIssues", "columnMemberSemantic", "measureSemantic"), "区域语义项");
                var id = required(item, "regionId");
                var geometry = contexts.get(id);
                if (geometry == null) throw new IllegalArgumentException("regionId 未引用语义区域: " + id);
                for (var key : List.of("fieldRelations", "qualityIssues")) {
                    require(item.path(key).isArray(), key + " 必须是数组");
                }
                var copy = (ObjectNode) item.deepCopy();
                normalizeAxisCollection(copy, "rowDimensions", issues, id);
                normalizeAxisCollection(copy, "rowAttributes", issues, id);
                copy.set("rowDimensions", validateAxes(copy.path("rowDimensions"), geometry,
                        "rowDimensions", issues, id));
                copy.set("rowAttributes", validateAxes(copy.path("rowAttributes"), geometry,
                        "rowAttributes", issues, id));
                var relations = objectMapper.createArrayNode();
                for (var relation : item.path("fieldRelations")) {
                    if (!validRelation(relation, geometry)) {
                        issues.add(objectMapper.createObjectNode().put("issueType", "INVALID_FIELD_RELATION")
                                .put("severity", "WARNING").put("regionId", id)
                                .put("description", "已忽略不符合枚举或区域几何约束的字段关系"));
                        continue;
                    }
                    var relationCopy = (ObjectNode) relation.deepCopy();
                    relationCopy.put("valueType", SemanticProtocolTypes.normalizeValueType(
                                    relation.path("valueType").asText("UNKNOWN")))
                            .put("editability", SemanticProtocolTypes.normalizeEditability(
                                    relation.path("editability").asText("UNKNOWN")))
                            .put("valueSource", SemanticProtocolTypes.normalizeValueSource(
                                    relation.path("valueSource").asText("UNKNOWN")));
                    relations.add(relationCopy);
                }
                if ("MATRIX".equals(geometry.path("type").asText("")) && !relations.isEmpty()) {
                    issues.add(objectMapper.createObjectNode().put("issueType", "MATRIX_FIELD_RELATIONS_IGNORED")
                            .put("severity", "WARNING").put("regionId", id)
                            .put("description", "矩阵列成员由 Compiler 生成，不能作为普通字段"));
                    relations.removeAll();
                }
                copy.set("fieldRelations", relations);
                valid.add(copy);
            } catch (RuntimeException invalid) {
                issues.add(objectMapper.createObjectNode().put("issueType", "INVALID_REGION_SEMANTICS")
                        .put("severity", "WARNING")
                        .put("description", invalid.getMessage() == null ? "区域语义项非法" : invalid.getMessage())
                        .set("region", item.deepCopy()));
            }
        }
        var normalized = objectMapper.createObjectNode().put("recognitionProtocolVersion", VERSION);
        normalized.set("regions", valid);
        normalized.set("qualityIssues", issues);
        return normalized;
    }

    private void normalizeAxisCollection(
            ObjectNode region,
            String key,
            ArrayNode issues,
            String regionId
    ) {
        if (region.path(key).isArray()) return;
        region.putArray(key);
        issues.add(objectMapper.createObjectNode()
                .put("issueType", "INVALID_AXIS_COLLECTION")
                .put("severity", "WARNING")
                .put("regionId", regionId)
                .put("description", key + " 不是数组，已降级为空集合并启用物理标签回退"));
    }

    private Map<String, JsonNode> contexts(JsonNode context) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (var region : context.path("semanticRegions")) {
            var id = region.path("regionId").asText(region.path("blockId").asText(""));
            if (!id.isBlank()) result.put(id, region);
        }
        return result;
    }

    private boolean validRelation(JsonNode relation, JsonNode geometry) {
        if (!relation.isObject()) return false;
        for (var key : List.of("temporaryId", "labelRange", "valueRange", "businessName", "unit", "condition")) {
            if (!relation.path(key).isTextual()) return false;
        }
        var label = relation.path("labelRange").asText("");
        var value = relation.path("valueRange").asText("");
        if (!RANGE.matcher(label.toUpperCase(java.util.Locale.ROOT)).matches()
                || !RANGE.matcher(value.toUpperCase(java.util.Locale.ROOT)).matches()) return false;
        if (!SemanticProtocolTypes.VALUE_TYPES.contains(SemanticProtocolTypes.normalizeValueType(
                relation.path("valueType").asText("UNKNOWN")))) return false;
        if (!SemanticProtocolTypes.EDITABILITY.contains(SemanticProtocolTypes.normalizeEditability(
                relation.path("editability").asText("UNKNOWN")))) return false;
        if (!SemanticProtocolTypes.VALUE_SOURCES.contains(SemanticProtocolTypes.normalizeValueSource(
                relation.path("valueSource").asText("UNKNOWN")))) return false;
        if (!contains(geometry.path("range").asText(""), label)
                || !contains(geometry.path("range").asText(""), value)) return false;
        if ("ROW_TABLE".equals(geometry.path("type").asText(""))) {
            var structure = geometry.path("structure");
            var headerRange = structure.path("headerRange").asText(
                    geometry.path("headerRange").asText(""));
            var dataRange = structure.path("dataRange").asText(
                    geometry.path("dataRange").asText(""));
            var totalRange = structure.path("totalRange").asText(
                    geometry.path("totalRange").asText(""));
            // A row-table field is defined by a header cell and a projection
            // into the repeat body. Treating a first data row (or a merged
            // operation slot) as a label silently shifts the whole mapping and
            // is therefore a protocol failure, not a fuzzy-match candidate.
            if (!contains(headerRange, label) || !contains(dataRange, value)) return false;
            if (!totalRange.isBlank() && overlaps(totalRange, value)) return false;
        }
        if ("COLUMN_TABLE".equals(geometry.path("type").asText(""))) {
            var valueBounds = bounds(value);
            var recordColumns = geometry.path("structure").path("recordProjection").path("recordColumns");
            if (valueBounds != null && recordColumns.isArray() && !recordColumns.isEmpty()) {
                var intersectsRecordSurface = false;
                for (var column : recordColumns) {
                    var columnBounds = bounds(column.asText("") + "1");
                    if (columnBounds != null && valueBounds[0] <= columnBounds[0]
                            && valueBounds[2] >= columnBounds[0]) {
                        intersectsRecordSurface = true;
                        break;
                    }
                }
                if (!intersectsRecordSurface) return false;
            }
        }
        return true;
    }

    private boolean overlaps(String first, String second) {
        var a = bounds(first);
        var b = bounds(second);
        return a != null && b != null && a[0] <= b[2] && b[0] <= a[2]
                && a[1] <= b[3] && b[1] <= a[3];
    }

    private ArrayNode validateAxes(JsonNode axes, JsonNode geometry, String key, ArrayNode issues, String regionId) {
        if (!axes.isArray()) throw new IllegalArgumentException(key + " 必须是数组");
        var rowHeader = geometry.path("structure").path("rowHeaderRange").asText(
                geometry.path("rowHeaderRange").asText(geometry.path("range").asText("")));
        var seen = new java.util.HashSet<String>();
        var valid = objectMapper.createArrayNode();
        for (var axis : axes) {
            // Some OpenAI-compatible providers accept the strict JSON schema
            // but still emit the unambiguous shorthand "A5:A19". Normalize
            // only that geometry-preserving form; arbitrary objects and
            // invalid ranges remain rejected by the checks below.
            JsonNode normalizedAxis = axis;
            if (axis.isTextual()) {
                normalizedAxis = objectMapper.createObjectNode()
                        .put("sourceRange", axis.asText(""))
                        .put("name", "")
                        .put("fillMerged", true)
                        .put("role", "rowDimensions".equals(key) ? "ROW_DIMENSION" : "ROW_ATTRIBUTE")
                        .put("optional", true);
            } else if (!axis.isObject()) {
                throw new IllegalArgumentException(key + " 项必须是对象或单元格范围");
            }
            var range = normalizedAxis.path("sourceRange").asText("");
            if (!RANGE.matcher(range.toUpperCase(java.util.Locale.ROOT)).matches()
                    || !contains(rowHeader, range)) {
                issues.add(objectMapper.createObjectNode().put("issueType", "INVALID_ROW_AXIS")
                        .put("severity", "WARNING").put("regionId", regionId)
                        .put("description", key + " 的 sourceRange 不在 canonical rowHeaderRange 内")
                        .set("axis", normalizedAxis.deepCopy()));
                continue;
            }
            if (!seen.add(range.toUpperCase(java.util.Locale.ROOT))) {
                issues.add(objectMapper.createObjectNode().put("issueType", "DUPLICATE_ROW_AXIS")
                        .put("severity", "WARNING").put("regionId", regionId)
                        .put("description", key + " 存在重复 sourceRange"));
                continue;
            }
            valid.add(normalizedAxis.deepCopy());
        }
        return valid;
    }

    private boolean contains(String outer, String inner) {
        var a = bounds(outer); var b = bounds(inner);
        return a != null && b != null && a[0] <= b[0] && a[1] <= b[1] && a[2] >= b[2] && a[3] >= b[3];
    }

    private int[] bounds(String value) {
        if (value == null || value.isBlank()) return null;
        var parts = value.toUpperCase(java.util.Locale.ROOT).split(":", 2);
        var first = cell(parts[0]); var last = cell(parts.length == 1 ? parts[0] : parts[1]);
        if (first == null || last == null) return null;
        return new int[]{Math.min(first[0], last[0]), Math.min(first[1], last[1]),
                Math.max(first[0], last[0]), Math.max(first[1], last[1])};
    }

    private int[] cell(String value) {
        var match = Pattern.compile("^([A-Z]{1,4})([1-9][0-9]*)$").matcher(value);
        if (!match.matches()) return null;
        var column = 0; for (var c : match.group(1).toCharArray()) column = column * 26 + c - 'A' + 1;
        return new int[]{column, Integer.parseInt(match.group(2))};
    }

    private String required(JsonNode node, String key) {
        require(node.path(key).isTextual() && !node.path(key).asText().isBlank(), key + " 必须是非空字符串");
        return node.path(key).asText();
    }

    private void exactKeys(JsonNode node, Set<String> allowed, String name) {
        node.fieldNames().forEachRemaining(key -> require(allowed.contains(key), name + "包含未定义字段: " + key));
    }

    private void requireObject(JsonNode node, String name) {
        require(node != null && node.isObject(), name + "必须是对象");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
