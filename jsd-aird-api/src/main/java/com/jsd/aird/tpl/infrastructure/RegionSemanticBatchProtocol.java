package com.jsd.aird.tpl.infrastructure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.ExperimentSemanticResolver;

/** Batch semantic protocol. Geometry is supplied as immutable region context. */
final class RegionSemanticBatchProtocol {

    static final int VERSION = 4;
    private static final Set<String> RESPONSE_KEYS = Set.of(
            "recognitionProtocolVersion", "experimentTemplateSuggestion", "regions", "qualityIssues");
    private static final Set<String> REGION_KEYS = Set.of(
            "regionId", "businessName", "fieldRelations", "matrixRelations", "qualityIssues");
    private static final Set<String> RELATION_KEYS = Set.of(
            "candidateRef", "fieldName", "businessName", "valueType", "unit", "groupName",
            "experimentFieldSuggestion");
    private static final Set<String> EXPERIMENT_SUGGESTION_KEYS = Set.of(
            "domain", "field", "itemLabel", "confidence", "alternatives");
    private static final Set<String> MATRIX_RELATION_KEYS = Set.of(
            "candidateRef", "matrixType", "confidence", "alternatives");
    private static final Set<String> TEMPLATE_SUGGESTION_KEYS = Set.of(
            "templateUsage", "recordMode", "confidence", "alternatives");

    private final ObjectMapper objectMapper;
    private final ExperimentSemanticResolver experimentResolver;

    RegionSemanticBatchProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.experimentResolver = new ExperimentSemanticResolver(objectMapper);
    }

    JsonNode responseSchema() {
        try {
            var schema = (ObjectNode) objectMapper.readTree("""
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "type":"object","additionalProperties":false,
                      "required":["recognitionProtocolVersion","experimentTemplateSuggestion","regions","qualityIssues"],
                      "properties":{
                        "recognitionProtocolVersion":{"const":4},
                        "experimentTemplateSuggestion":{"type":"object","additionalProperties":false,
                          "required":["templateUsage","recordMode","confidence","alternatives"],
                          "properties":{
                            "templateUsage":{"enum":["GENERAL_DATA","EXPERIMENT_DATA"]},
                            "recordMode":{"enum":["SINGLE_FILE","BY_IDENTITY","UNKNOWN"]},
                            "confidence":{"type":"number","minimum":0,"maximum":1},
                            "alternatives":{"type":"array","maxItems":2,"items":{"type":"string"}}
                          }
                        },
                        "regions":{"type":"array","items":{
                          "type":"object","additionalProperties":false,
                          "required":["regionId","businessName","fieldRelations","matrixRelations","qualityIssues"],
                          "properties":{
                            "regionId":{"type":"string","minLength":1},
                            "businessName":{"type":"string"},
                            "fieldRelations":{"type":"array","items":{"type":"object","additionalProperties":false,
                              "required":["candidateRef","fieldName","businessName","valueType","unit","groupName","experimentFieldSuggestion"],
                              "properties":{
                                "candidateRef":{"type":"string","minLength":1},
                                "fieldName":{"type":"string"},"businessName":{"type":"string"},
                                "valueType":{"enum":["string","number","integer","boolean","date","datetime","time","duration"]},
                                "unit":{"type":"string"},"groupName":{"type":"string"},
                                "experimentFieldSuggestion":{"type":"object","additionalProperties":false,
                                  "required":["domain","field","itemLabel","confidence","alternatives"],
                                  "properties":{
                                    "domain":{"enum":["BASIC","FORMULA","PROCESS","TEST","CONCLUSION","OTHER"]},
                                    "field":{"type":"string","minLength":1},
                                    "itemLabel":{"type":"string"},
                                    "confidence":{"type":"number","minimum":0,"maximum":1},
                                    "alternatives":{"type":"array","maxItems":2,"items":{"type":"object","additionalProperties":false,
                                      "required":["domain","field"],"properties":{"domain":{"type":"string"},"field":{"type":"string"}}
                                    }}
                                  }
                                }
                              }
                            }},
                            "matrixRelations":{"type":"array","items":{"type":"object","additionalProperties":false,
                              "required":["candidateRef","matrixType","confidence","alternatives"],
                              "properties":{
                                "candidateRef":{"type":"string","minLength":1},
                                "matrixType":{"enum":["FORMULA_MATRIX","OTHER"]},
                                "confidence":{"type":"number","minimum":0,"maximum":1},
                                "alternatives":{"type":"array","maxItems":2,"items":{"type":"string"}}
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
        exactKeys(root, RESPONSE_KEYS, "批量区域语义响应");
        if (!root.has("qualityIssues") || root.path("qualityIssues").isNull()) root.putArray("qualityIssues");
        if (!root.has("experimentTemplateSuggestion") || root.path("experimentTemplateSuggestion").isNull()) {
            root.set("experimentTemplateSuggestion", unknownTemplateSuggestion());
        }
        require(root.path("recognitionProtocolVersion").asInt(-1) == VERSION,
                "批量区域语义响应 recognitionProtocolVersion 必须为 4");
        require(root.path("regions").isArray(), "regions 必须是数组");
        require(root.path("qualityIssues").isArray(), "qualityIssues 必须是数组");
        validateTemplateSuggestion(root.path("experimentTemplateSuggestion"));

        var contexts = contexts(context);
        var valid = objectMapper.createArrayNode();
        var issues = (ArrayNode) root.path("qualityIssues").deepCopy();
        var identityCount = 0;
        var testCount = 0;
        var formulaCount = 0;
        var matrixCount = 0;
        for (var item : root.path("regions")) {
            try {
                if (!item.isObject()) throw new IllegalArgumentException("区域语义项必须是对象");
                exactKeys(item, REGION_KEYS, "区域语义项");
                var id = required(item, "regionId");
                var geometry = contexts.get(id);
                if (geometry == null) throw new IllegalArgumentException("regionId 未引用语义区域: " + id);
                for (var key : List.of("fieldRelations", "qualityIssues")) {
                    require(item.path(key).isArray(), key + " 必须是数组");
                }
                var copy = (ObjectNode) item.deepCopy();
                if (!copy.path("matrixRelations").isArray()) copy.putArray("matrixRelations");
                var relations = objectMapper.createArrayNode();
                var returnedCandidates = new java.util.HashSet<String>();
                var validModelRelations = new LinkedHashMap<String, ObjectNode>();
                for (var relation : item.path("fieldRelations")) {
                    var relationCopy = relation instanceof ObjectNode object ? object.deepCopy() : null;
                    if (relationCopy == null || !validV4RelationShape(relationCopy)
                            || !candidateAllowed(relationCopy, geometry)
                            || !returnedCandidates.add(relationCopy.path("candidateRef").asText(""))) {
                        invalidRelation(issues, id, "已忽略不符合V4枚举或物理候选约束的字段关系");
                        continue;
                    }
                    validModelRelations.put(relationCopy.path("candidateRef").asText(""), relationCopy);
                }
                // The physical candidates and their confirmed group paths are the
                // authoritative field set.  A malformed or incomplete model response
                // may remove only its own enrichment; it must never remove a real
                // field or downgrade an otherwise deterministic experiment template.
                for (var physical : geometry.path("fieldCandidates")) {
                    var candidateRef = candidateId(physical);
                    if (candidateRef.isBlank()) continue;
                    var relationCopy = validModelRelations.get(candidateRef);
                    if (relationCopy == null) relationCopy = physicalRelation(physical, candidateRef);
                    if (relationCopy.path("businessName").asText("").isBlank()) {
                        relationCopy.put("businessName", relationCopy.path("fieldName").asText());
                    }
                    normalizePhysicalFields(relationCopy, physical);
                    relationCopy.put("valueType", SemanticProtocolTypes.normalizeValueType(
                            relationCopy.path("valueType").asText(physical.path("valueType").asText("UNKNOWN"))));
                    experimentResolver.resolveField(relationCopy, physical, geometry);
                    var domain = relationCopy.path("experimentField").path("domain").asText("");
                    var field = relationCopy.path("experimentField").path("field").asText("");
                    if ("BASIC".equals(domain) && "SOURCE_IDENTITY".equals(field)) identityCount++;
                    if ("TEST".equals(domain)) testCount++;
                    if ("FORMULA".equals(domain)) formulaCount++;
                    relations.add(relationCopy);
                }
                copy.set("fieldRelations", relations);
                var matrices = validatedMatrices(copy.path("matrixRelations"), geometry, id, issues);
                if (matrices.isEmpty()) appendDeterministicFormulaMatrices(matrices, geometry);
                matrixCount += matrices.size();
                copy.set("matrixRelations", matrices);
                valid.add(copy);
            } catch (RuntimeException invalid) {
                issues.add(objectMapper.createObjectNode().put("issueType", "INVALID_REGION_SEMANTICS")
                        .put("severity", "WARNING")
                        .put("description", invalid.getMessage() == null ? "区域语义项非法" : invalid.getMessage())
                        .set("region", item.deepCopy()));
            }
        }

        var normalized = objectMapper.createObjectNode().put("recognitionProtocolVersion", VERSION);
        normalized.set("experimentTemplateSuggestion", resolveTemplateSuggestion(
                root.path("experimentTemplateSuggestion"), identityCount, formulaCount, testCount, matrixCount));
        normalized.set("regions", valid);
        normalized.set("qualityIssues", issues);
        return normalized;
    }

    private boolean validV4RelationShape(JsonNode relation) {
        var fields = new java.util.HashSet<String>();
        relation.fieldNames().forEachRemaining(fields::add);
        if (!RELATION_KEYS.containsAll(fields)) return false;
        var candidateRef = relation.path("candidateRef").asText("").strip();
        var name = relation.path("fieldName").asText(relation.path("businessName").asText("")).strip();
        if (candidateRef.isBlank() || name.isBlank() || !relation.path("valueType").isTextual()
                || !relation.path("unit").isTextual() || !relation.path("groupName").isTextual()) return false;
        var suggestion = relation.path("experimentFieldSuggestion");
        if (!suggestion.isObject()) return false;
        var keys = new java.util.HashSet<String>();
        suggestion.fieldNames().forEachRemaining(keys::add);
        return EXPERIMENT_SUGGESTION_KEYS.containsAll(keys)
                && experimentResolver.validSemantic(suggestion)
                && suggestion.path("itemLabel").isTextual()
                && validConfidence(suggestion.path("confidence"))
                && suggestion.path("alternatives").isArray()
                && suggestion.path("alternatives").size() <= 2;
    }

    private ArrayNode validatedMatrices(JsonNode values, JsonNode geometry, String regionId, ArrayNode issues) {
        var result = objectMapper.createArrayNode();
        var seen = new java.util.HashSet<String>();
        if (!values.isArray()) return result;
        for (var value : values) {
            var fields = new java.util.HashSet<String>();
            if (value.isObject()) value.fieldNames().forEachRemaining(fields::add);
            var candidate = candidateFor(value, geometry, "matrixCandidates");
            if (!value.isObject() || !MATRIX_RELATION_KEYS.containsAll(fields) || candidate == null
                    || !Set.of("FORMULA_MATRIX", "OTHER").contains(value.path("matrixType").asText(""))
                    || !validConfidence(value.path("confidence")) || !value.path("alternatives").isArray()
                    || value.path("alternatives").size() > 2
                    || !seen.add(value.path("candidateRef").asText(""))) {
                invalidRelation(issues, regionId, "已忽略未引用确定性矩阵候选的关系");
                continue;
            }
            var copy = (ObjectNode) value.deepCopy();
            for (var key : List.of("componentId", "sheetId", "groupName", "recordAxis", "itemAxis",
                    "labelRange", "valueRange", "identityLabelRange", "identityValueRange",
                    "labelSemantic", "valueSemantic", "totalRange", "geometryConfidence")) {
                if (candidate.has(key)) copy.set(key, candidate.path(key).deepCopy());
            }
            var auto = "FORMULA_MATRIX".equals(copy.path("matrixType").asText())
                    && copy.path("confidence").asDouble() >= ExperimentSemanticResolver.AUTO_CONFIRM_THRESHOLD
                    && structureConfirmed(geometry);
            copy.put("experimentSemanticStatus", auto ? "AUTO_CONFIRMED" : "NEEDS_REVIEW");
            copy.put("autoAccept", auto);
            result.add(copy);
        }
        return result;
    }

    private void appendDeterministicFormulaMatrices(ArrayNode result, JsonNode geometry) {
        if (!structureConfirmed(geometry)) return;
        for (var candidate : geometry.path("matrixCandidates")) {
            if (!experimentResolver.exactFormulaGroup(candidate)) continue;
            var copy = (ObjectNode) candidate.deepCopy();
            copy.put("matrixType", "FORMULA_MATRIX")
                    .put("confidence", 0.95d)
                    .put("experimentSemanticStatus", "AUTO_CONFIRMED")
                    .put("autoAccept", true)
                    .putArray("alternatives");
            result.add(copy);
        }
    }

    private ObjectNode resolveTemplateSuggestion(
            JsonNode model, int identities, int formulas, int tests, int matrices
    ) {
        var strongEvidence = identities > 0 && (formulas > 0 || tests > 0 || matrices > 0);
        var modelExperiment = "EXPERIMENT_DATA".equals(model.path("templateUsage").asText(""));
        var modelConfidence = model.path("confidence").asDouble(0d);
        // The model's template-level answer is only an enrichment.  Verified
        // identity plus formula/test geometry is stronger evidence and must
        // not be downgraded to GENERAL_DATA by a conflicting model guess.
        var experimental = strongEvidence;
        var result = objectMapper.createObjectNode()
                .put("templateUsage", experimental ? "EXPERIMENT_DATA" : "GENERAL_DATA")
                .put("recordMode", "SINGLE_FILE")
                .put("confidence", experimental ? Math.max(0.95d, modelExperiment ? modelConfidence : 0d) : modelConfidence)
                .put("experimentSemanticStatus", experimental ? "AUTO_CONFIRMED" : "NEEDS_REVIEW")
                .put("autoAccept", experimental)
                .put("identityCount", identities)
                .put("formulaFieldCount", formulas)
                .put("testFieldCount", tests)
                .put("matrixCount", matrices);
        result.set("alternatives", model.path("alternatives").isArray()
                ? model.path("alternatives").deepCopy() : objectMapper.createArrayNode());
        return result;
    }

    private void validateTemplateSuggestion(JsonNode value) {
        requireObject(value, "实验模板建议");
        exactKeys(value, TEMPLATE_SUGGESTION_KEYS, "实验模板建议");
        require(Set.of("GENERAL_DATA", "EXPERIMENT_DATA").contains(value.path("templateUsage").asText("")),
                "templateUsage 无效");
        require(Set.of("SINGLE_FILE", "BY_IDENTITY", "UNKNOWN").contains(value.path("recordMode").asText("")),
                "recordMode 无效");
        require(validConfidence(value.path("confidence")), "模板用途置信度无效");
        require(value.path("alternatives").isArray() && value.path("alternatives").size() <= 2,
                "模板用途备选最多两个");
    }

    private ObjectNode unknownTemplateSuggestion() {
        var result = objectMapper.createObjectNode().put("templateUsage", "GENERAL_DATA")
                .put("recordMode", "UNKNOWN").put("confidence", 0d);
        result.putArray("alternatives");
        return result;
    }

    private Map<String, JsonNode> contexts(JsonNode context) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (var region : context.path("semanticRegions")) {
            var id = region.path("regionId").asText(region.path("blockId").asText(""));
            if (!id.isBlank()) result.put(id, region);
        }
        return result;
    }

    private boolean candidateAllowed(JsonNode relation, JsonNode geometry) {
        return candidateFor(relation, geometry, "fieldCandidates") != null;
    }

    private JsonNode candidateFor(JsonNode relation, JsonNode geometry, String collection) {
        var ref = relation.path("candidateRef").asText("");
        for (var candidate : geometry.path(collection)) {
            var id = candidateId(candidate);
            if (!ref.isBlank() && ref.equals(id)) return candidate;
        }
        return null;
    }

    private String candidateId(JsonNode candidate) {
        return candidate.path("candidateRef").asText(
                candidate.path("relationId").asText(candidate.path("fieldId").asText("")));
    }

    private ObjectNode physicalRelation(JsonNode physical, String candidateRef) {
        var name = physical.path("fieldName").asText(
                physical.path("businessName").asText(physical.path("name").asText(
                        physical.path("fieldCode").asText("未命名字段"))));
        var relation = objectMapper.createObjectNode()
                .put("candidateRef", candidateRef)
                .put("fieldName", name)
                .put("businessName", name)
                .put("valueType", physical.path("valueType").asText("string"))
                .put("unit", physical.path("unit").asText(""))
                .put("groupName", physical.path("groupName").asText(""));
        return relation;
    }

    private void normalizePhysicalFields(ObjectNode relation, JsonNode candidate) {
        if (candidate == null) return;
        relation.put("temporaryId", candidate.path("relationId").asText(candidate.path("fieldId").asText(
                        relation.path("candidateRef").asText())))
                .put("labelRange", candidate.path("labelRange").asText())
                .put("valueRange", candidate.path("valueRange").asText())
                .put("condition", candidate.path("condition").asText(""))
                .put("required", candidate.path("required").asBoolean(false))
                .put("editability", SemanticProtocolTypes.normalizeEditability(
                        candidate.path("editability").asText("UNKNOWN")))
                .put("valueSource", SemanticProtocolTypes.normalizeValueSource(
                        candidate.path("valueSource").asText("UNKNOWN")));
        if (candidate.path("labelPathSegments").isArray()) {
            relation.set("labelPathSegments", candidate.path("labelPathSegments").deepCopy());
        }
        if (!candidate.path("labelPath").asText("").isBlank()) {
            relation.put("labelPath", candidate.path("labelPath").asText());
        }
    }

    private boolean structureConfirmed(JsonNode geometry) {
        return "CONFIRMED".equals(geometry.path("canonicalStatus").asText(""))
                && "CONFIRMED".equals(geometry.path("structureStatus").asText("CONFIRMED"))
                && !geometry.path("structureConflict").asBoolean(false);
    }

    private boolean validConfidence(JsonNode value) {
        return value.isNumber() && value.asDouble() >= 0d && value.asDouble() <= 1d;
    }

    private void invalidRelation(ArrayNode issues, String regionId, String description) {
        issues.add(objectMapper.createObjectNode().put("issueType", "INVALID_FIELD_RELATION")
                .put("severity", "WARNING").put("regionId", regionId).put("description", description));
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
