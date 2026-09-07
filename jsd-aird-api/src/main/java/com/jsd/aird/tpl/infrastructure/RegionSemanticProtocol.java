package com.jsd.aird.tpl.infrastructure;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Protocol for the second recognition call. Geometry is supplied by the
 * backend context and is compiled into the internal envelope; the model only
 * contributes business meaning and row/field semantics.
 */
final class RegionSemanticProtocol {

    static final int VERSION = 1;
    private static final Pattern RANGE = Pattern.compile(
            "^[A-Z]{1,4}[1-9][0-9]*(?::[A-Z]{1,4}[1-9][0-9]*)?$"
    );
    private static final Set<String> VALUE_TYPES = SemanticProtocolTypes.VALUE_TYPES;
    private static final Set<String> EDITABILITY = SemanticProtocolTypes.EDITABILITY;
    private static final Set<String> VALUE_SOURCES = SemanticProtocolTypes.VALUE_SOURCES;
    private final ObjectMapper objectMapper;

    RegionSemanticProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode responseSchema() {
        try {
            return objectMapper.readTree("""
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "type":"object","additionalProperties":false,
                      "required":["recognitionProtocolVersion","businessName","rowDimensions","rowAttributes","fieldRelations","qualityIssues"],
                      "properties":{
                        "recognitionProtocolVersion":{"const":1},
                        "businessName":{"type":"string"},
                        "rowDimensions":{"type":"array","items":{"type":"object","additionalProperties":true}},
                        "rowAttributes":{"type":"array","items":{"type":"object","additionalProperties":true}},
                        "fieldRelations":{"type":"array","items":{"type":"object","additionalProperties":false,
                          "required":["temporaryId","labelRange","valueRange","businessName","valueType","required","editability","valueSource","unit","condition"],
                          "properties":{
                            "temporaryId":{"type":"string","minLength":1},"labelRange":{"type":"string"},"valueRange":{"type":"string"},
                             "businessName":{"type":"string","minLength":1},"valueType":{"enum":["string","number","integer","boolean","date","datetime","time","duration"]},"required":{"type":"boolean"},
                             "editability":{"enum":["EDITABLE","READ_ONLY","CONDITIONAL","UNKNOWN"]},"valueSource":{"enum":["USER_INPUT","FORMULA","REFERENCE","STATIC","MIXED","UNKNOWN"]},"unit":{"type":"string"},"condition":{"type":"string"},
                            "relationType":{"type":"string"},"semanticKeySuggestion":{"type":"string"},"groupNameSuggestion":{"type":"string"}
                          }
                        }},
                        "qualityIssues":{"type":"array","items":{"type":"object","additionalProperties":true}}
                      }
                    }
                    """);
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载区域语义协议", exception);
        }
    }

    ObjectNode validateAndBuildEnvelope(JsonNode response, JsonNode context) {
        requireObject(response, "区域语义响应");
        var root = (ObjectNode) response.deepCopy();
        exactKeys(root, Set.of("recognitionProtocolVersion", "businessName", "rowDimensions",
                "rowAttributes", "fieldRelations", "qualityIssues"), "区域语义响应");
        require(root.path("recognitionProtocolVersion").asInt(-1) == VERSION,
                "区域语义响应 recognitionProtocolVersion 必须为 1");
        for (var key : List.of("rowDimensions", "rowAttributes", "fieldRelations", "qualityIssues")) {
            require(root.path(key).isArray(), key + " 必须是数组");
        }
        var sheetId = context.path("regionSheetId").asText("");
        var range = context.path("regionRange").asText("");
        var type = context.path("canonicalBlockType").asText("UNKNOWN");
        var canonical = context.path("canonicalStructure").path("modelStructure");
        require(!sheetId.isBlank() && RANGE.matcher(range.toUpperCase(java.util.Locale.ROOT)).matches(),
                "区域上下文缺少合法 sheetId/regionRange");
        require(Set.of("ROW_TABLE", "COLUMN_TABLE", "MATRIX", "FORM_REGION", "UNKNOWN").contains(type),
                "区域上下文 canonicalBlockType 不合法");

        var envelope = objectMapper.createObjectNode().put("recognitionProtocolVersion", VERSION);
        envelope.putArray("semanticAnnotations");
        var blocks = envelope.putArray("businessBlocks");
        var relations = envelope.putArray("fieldRelations");
        var tables = envelope.putArray("tables");
        var issues = envelope.putArray("qualityIssues");
        var blockId = context.path("canonicalBlockId").asText(context.path("regionId").asText("region"));
        var name = root.path("businessName").asText("").strip();
        if (name.isBlank()) name = context.path("canonicalBlockName").asText("待确认区域");
        var temporaryBlockId = "region-" + blockId;
        var legacyBlockType = "FORM_FIELDS".equals(type) ? "FORM_REGION" : type;
        blocks.add(objectMapper.createObjectNode()
                .put("temporaryId", temporaryBlockId).put("sheetId", sheetId).put("range", range)
                .put("type", legacyBlockType).put("parentTemporaryId", "").put("businessName", name)
                .put("groupNameSuggestion", "").put("semanticKeySuggestion", ""));

        var relationIndex = 0;
        for (var relation : root.path("fieldRelations")) {
            if (!relation.isObject()) continue;
            var object = (ObjectNode) relation.deepCopy();
            for (var key : List.of("temporaryId", "labelRange", "valueRange", "businessName", "unit", "condition")) {
                if (!object.path(key).isTextual()) {
                    object = null;
                    break;
                }
            }
            if (object == null) continue;
            if (object.path("businessName").asText("").strip().isBlank()) continue;
            var labelRange = object.path("labelRange").asText("");
            var valueRange = object.path("valueRange").asText("");
            if (!RANGE.matcher(labelRange.toUpperCase(java.util.Locale.ROOT)).matches()
                    || !RANGE.matcher(valueRange.toUpperCase(java.util.Locale.ROOT)).matches()) continue;
            var valueType = SemanticProtocolTypes.normalizeValueType(object.path("valueType").asText("string"));
            var editability = SemanticProtocolTypes.normalizeEditability(object.path("editability").asText("UNKNOWN"));
            var valueSource = SemanticProtocolTypes.normalizeValueSource(object.path("valueSource").asText("UNKNOWN"));
            if (!VALUE_TYPES.contains(valueType) || !EDITABILITY.contains(editability)
                    || !VALUE_SOURCES.contains(valueSource)) continue;
            object.put("valueType", valueType).put("editability", editability).put("valueSource", valueSource);
            object.put("blockTemporaryId", temporaryBlockId)
                    .put("relationType", object.path("relationType").asText("LABEL_VALUE"))
                    .put("groupNameSuggestion", object.path("groupNameSuggestion").asText(""))
                    .put("semanticKeySuggestion", object.path("semanticKeySuggestion").asText(""));
            if (isMatrix(type)) continue;
            relations.add(object);
            relationIndex++;
        }

        addCanonicalTable(tables, canonical, type, sheetId, range, name, temporaryBlockId, root);
        for (var issue : root.path("qualityIssues")) {
            if (!issue.isObject()) continue;
            var issueObject = (ObjectNode) issue.deepCopy();
            var category = issueObject.path("issueType").asText("OTHER");
            if (!Set.of("FIELD_RELATION_UNCLEAR", "BUSINESS_BLOCK_UNCLEAR", "TABLE_STRUCTURE_UNCLEAR",
                    "EDITABILITY_UNCLEAR", "LAYOUT_INCONSISTENT", "DUPLICATE_MEANING", "OTHER").contains(category)) {
                category = "OTHER";
            }
            var severity = Set.of("INFO", "WARNING", "BLOCKER").contains(issueObject.path("severity").asText())
                    ? issueObject.path("severity").asText() : "WARNING";
            issues.add(objectMapper.createObjectNode()
                    .put("temporaryId", "region-issue-" + issues.size()).put("sheetId", sheetId).put("range", range)
                    .put("category", category).put("severity", severity)
                    .put("title", issueObject.path("title").asText("区域语义需要核对"))
                    .put("description", issueObject.path("description").asText("模型无法确定区域语义"))
                    .put("businessImpact", issueObject.path("businessImpact").asText("可能影响字段映射"))
                    .put("rootBlockTemporaryId", temporaryBlockId));
        }
        return envelope;
    }

    private boolean isMatrix(String type) {
        return "MATRIX".equals(type);
    }

    private void addCanonicalTable(
            ArrayNode tables, JsonNode structure, String type, String sheetId, String range,
            String name, String blockId, JsonNode semanticResponse
    ) {
        if (!Set.of("ROW_TABLE", "COLUMN_TABLE", "MATRIX").contains(type)) return;
        var isMatrix = "MATRIX".equals(type);
        var headerRange = structure.path(isMatrix ? "columnHeaderRange" : "headerRange").asText("");
        var dataRange = structure.path(isMatrix ? "crossDataRange" : "dataRange").asText("");
        var rowHeaderRange = structure.path("rowHeaderRange").asText("");
        var columnHeaderRange = structure.path("columnHeaderRange").asText("");
        var crossDataRange = structure.path("crossDataRange").asText("");
        if (headerRange.isBlank()) headerRange = range;
        if (dataRange.isBlank()) dataRange = range;
        var table = objectMapper.createObjectNode()
                .put("temporaryId", "region-table-" + blockId).put("sheetId", sheetId).put("range", range)
                .put("tableKind", type).put("businessName", semanticResponse.path("businessName").asText(name))
                .put("blockTemporaryId", blockId).put("groupNameSuggestion", "").put("semanticKeySuggestion", "")
                .put("headerRange", headerRange).put("dataRange", dataRange).put("totalRange", "")
                .put("semanticMode", isMatrix ? "CROSS_TAB"
                        : "COLUMN_TABLE".equals(type) ? "COLUMN_RECORDS" : "ROW_RECORDS")
                .put("rowHeaderRange", rowHeaderRange).put("columnHeaderRange", columnHeaderRange)
                .put("crossDataRange", crossDataRange);
        var columns = table.putArray("columns");
        if (!isMatrix) {
            for (var relation : semanticResponse.path("fieldRelations")) {
                columns.add(objectMapper.createObjectNode()
                        .put("temporaryId", relation.path("temporaryId").asText("column-" + columns.size()))
                        .put("name", relation.path("businessName").asText("待确认列"))
                        .put("labelRange", relation.path("labelRange").asText(headerRange))
                        .put("valueRange", relation.path("valueRange").asText(dataRange))
                        .put("valueType", relation.path("valueType").asText("string"))
                        .put("editability", relation.path("editability").asText("UNKNOWN"))
                        .put("valueSource", relation.path("valueSource").asText("UNKNOWN"))
                        .put("unit", relation.path("unit").asText(""))
                        .put("condition", relation.path("condition").asText(""))
                        .put("semanticKeySuggestion", relation.path("semanticKeySuggestion").asText("")));
            }
            // Keep the table column list empty when the model did not provide
            // a usable name. The compiler can then recover physical headers or
            // row attributes; a synthetic "待确认列" must never become a
            // formal field mapping.
        }
        table.set("headerTree", structure.path("headerTree").isArray()
                ? structure.path("headerTree").deepCopy() : objectMapper.createArrayNode());
        if (structure.has("cornerRange")) table.put("cornerRange", structure.path("cornerRange").asText());
        if (structure.has("recordAxis")) table.put("recordAxis", structure.path("recordAxis").asText("UNKNOWN"));
        if (!isMatrix) {
            table.put("repeatAxis", "COLUMN_TABLE".equals(type) ? "COLUMN" : "ROW")
                    .put("recordHeight", structure.path("recordHeight").asInt(1))
                    .put("recordWidth", structure.path("recordWidth").asInt(1))
                    .put("recordStride", structure.path("recordStride").asInt(1));
            if (structure.path("recordProjection").isObject()) {
                table.set("recordProjection", structure.path("recordProjection").deepCopy());
            }
        }
        tables.add(table);
    }

    private void requireObject(JsonNode node, String name) {
        require(node != null && node.isObject(), name + " 必须是对象");
    }

    private void exactKeys(JsonNode node, Set<String> allowed, String name) {
        node.fieldNames().forEachRemaining(key -> require(allowed.contains(key), name + "包含未定义字段: " + key));
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new GlobalSemanticRecognitionProtocol.ProtocolViolationException(message);
    }
}
