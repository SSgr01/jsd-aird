package com.jsd.aird.tpl.infrastructure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Batch semantic protocol. Geometry is supplied as immutable region context. */
final class RegionSemanticBatchProtocol {

    static final int VERSION = 3;
    private final ObjectMapper objectMapper;

    /**
     * v3 is intentionally a semantic patch protocol.  Geometry and physical
     * facts are supplied by the deterministic context and must never be
     * accepted from the model response.
     */
    private static final Set<String> V3_RELATION_KEYS = Set.of(
            "candidateRef", "fieldName", "businessName", "valueType", "unit", "groupName");

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
                        "recognitionProtocolVersion":{"const":3},
                        "regions":{"type":"array","items":{
                          "type":"object","additionalProperties":false,
                          "required":["regionId","businessName","fieldRelations","qualityIssues"],
                          "properties":{
                            "regionId":{"type":"string","minLength":1},
                            "businessName":{"type":"string"},
                            "fieldRelations":{"type":"array","items":{"type":"object","additionalProperties":false,
                              "required":["candidateRef","valueType","unit"],
                              "properties":{
                                "candidateRef":{"type":"string","minLength":1},"fieldName":{"type":"string"},"businessName":{"type":"string"},"valueType":{"enum":["string","number","integer","boolean","date","datetime","time","duration"]},"unit":{"type":"string"},"groupName":{"type":"string"}
                              }
                            }},
                            "qualityIssues":{"type":"array","items":{"type":"object","additionalProperties":true}}
                          }
                        }},
                        "qualityIssues":{"type":"array","items":{"type":"object","additionalProperties":true}}
                      }
                    }
                    """);
            var relation = schema.path("properties").path("regions").path("items")
                    .path("properties").path("fieldRelations").path("items").path("properties");
            ((ObjectNode) relation.path("valueType")).set("enum", enumValues(SemanticProtocolTypes.VALUE_TYPES));
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
                "批量区域语义响应 recognitionProtocolVersion 必须为 3");
        require(root.path("regions").isArray(), "regions 必须是数组");
        require(root.path("qualityIssues").isArray(), "qualityIssues 必须是数组");

        var contexts = contexts(context);
        var valid = objectMapper.createArrayNode();
        var issues = (ArrayNode) root.path("qualityIssues").deepCopy();
        for (var item : root.path("regions")) {
            try {
                if (!item.isObject()) throw new IllegalArgumentException("区域语义项必须是对象");
                exactKeys(item, Set.of("regionId", "businessName", "fieldRelations", "qualityIssues"), "区域语义项");
                var id = required(item, "regionId");
                var geometry = contexts.get(id);
                if (geometry == null) throw new IllegalArgumentException("regionId 未引用语义区域: " + id);
                for (var key : List.of("fieldRelations", "qualityIssues")) {
                    require(item.path(key).isArray(), key + " 必须是数组");
                }
                var copy = (ObjectNode) item.deepCopy();
                var relations = objectMapper.createArrayNode();
                var returnedCandidates = new java.util.HashSet<String>();
                for (var relation : item.path("fieldRelations")) {
                    var relationCopy = relation instanceof ObjectNode object
                            ? object.deepCopy() : null;
                    if (relationCopy == null) {
                        issues.add(objectMapper.createObjectNode().put("issueType", "INVALID_FIELD_RELATION")
                                .put("severity", "WARNING").put("regionId", id)
                                .put("description", "字段关系必须是对象"));
                        continue;
                    }
                    if (relationCopy.path("businessName").asText("").isBlank()
                            && relationCopy.path("fieldName").isTextual()) {
                        relationCopy.put("businessName", relationCopy.path("fieldName").asText());
                    }
                    if (!validV3RelationShape(relationCopy)
                            || !candidateAllowed(relationCopy, geometry)
                            || !validRelation(relationCopy, geometry)
                            || !returnedCandidates.add(relationCopy.path("candidateRef").asText(""))) {
                        issues.add(objectMapper.createObjectNode().put("issueType", "INVALID_FIELD_RELATION")
                                .put("severity", "WARNING").put("regionId", id)
                                .put("description", "已忽略不符合枚举或区域几何约束的字段关系"));
                        continue;
                    }
                    normalizePhysicalFields(relationCopy, geometry);
                    relationCopy.put("valueType", SemanticProtocolTypes.normalizeValueType(
                            relation.path("valueType").asText("UNKNOWN")));
                    relations.add(relationCopy);
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

    private boolean validV3RelationShape(JsonNode relation) {
        var fields = new java.util.HashSet<String>();
        relation.fieldNames().forEachRemaining(fields::add);
        if (!V3_RELATION_KEYS.containsAll(fields)) return false;
        var candidateRef = relation.path("candidateRef").asText("").strip();
        if (candidateRef.isBlank() || !relation.path("valueType").isTextual()
                || !relation.path("unit").isTextual()) return false;
        var name = relation.path("fieldName").asText(
                relation.path("businessName").asText("")).strip();
        return !name.isBlank();
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
        if (!relation.path("businessName").isTextual() || relation.path("businessName").asText().isBlank()) return false;
        return candidateFor(relation, geometry) != null
                && SemanticProtocolTypes.VALUE_TYPES.contains(SemanticProtocolTypes.normalizeValueType(
                relation.path("valueType").asText("UNKNOWN")))
                && relation.path("unit").isTextual();
    }

    private boolean candidateAllowed(JsonNode relation, JsonNode geometry) {
        var candidateRef = relation.path("candidateRef").asText("").strip();
        var candidates = geometry.path("fieldCandidates");
        if (!candidates.isArray() || candidateRef.isBlank() || candidates.isEmpty()) return false;
        for (var candidate : candidates) {
            if (candidateRef.equals(candidate.path("candidateRef").asText(
                    candidate.path("relationId").asText(candidate.path("fieldId").asText(""))))) {
                return true;
            }
        }
        return false;
    }

    private void normalizePhysicalFields(ObjectNode relation, JsonNode geometry) {
        var candidate = candidateFor(relation, geometry);
        if (candidate == null) return;
        relation.put("temporaryId", relation.path("temporaryId").asText(
                candidate.path("relationId").asText(candidate.path("fieldId").asText(
                        relation.path("candidateRef").asText()))));
        relation.put("labelRange", candidate.path("labelRange").asText())
                .put("valueRange", candidate.path("valueRange").asText())
                .put("condition", candidate.path("condition").asText(""))
                .put("required", candidate.path("required").asBoolean(false))
                .put("editability", SemanticProtocolTypes.normalizeEditability(
                        candidate.path("editability").asText("UNKNOWN")))
                .put("valueSource", SemanticProtocolTypes.normalizeValueSource(
                        candidate.path("valueSource").asText("UNKNOWN")));
    }

    private JsonNode candidateFor(JsonNode relation, JsonNode geometry) {
        var ref = relation.path("candidateRef").asText("");
        for (var candidate : geometry.path("fieldCandidates")) {
            var id = candidate.path("candidateRef").asText(
                    candidate.path("relationId").asText(candidate.path("fieldId").asText("")));
            if (!ref.isBlank() && ref.equals(id)) return candidate;
        }
        return null;
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
