package com.jsd.aird.tpl.infrastructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Strict validator and JSON Schema for recognitionProtocolVersion 1. */
final class GlobalSemanticRecognitionProtocol {

    static final int VERSION = 1;
    static final String PROMPT_VERSION = "template-global-semantic-v1";

    private static final Pattern ADDRESS = Pattern.compile(
            "^([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?$"
    );
    private static final Set<String> ANNOTATION_ROLES = Set.of(
            "DOCUMENT_TITLE", "INLINE_METADATA", "FIELD_LABEL", "FIELD_VALUE",
            "TABLE_HEADER", "TABLE_DATA", "TABLE_TOTAL", "INSTRUCTION", "CONFIRMATION",
            "SIGNATURE", "NOTE", "LOOKUP_DATA", "STATIC_REFERENCE", "UNKNOWN"
    );
    private static final Set<String> BLOCK_TYPES = Set.of(
            "DOCUMENT_HEADER", "FORM_FIELDS", "ROW_TABLE", "MATRIX", "INSTRUCTION_LIST",
            "CONFIRMATION_BLOCK", "SIGNATURE_BLOCK", "NOTE_BLOCK", "LOOKUP_TABLE", "UNKNOWN"
    );
    private static final Set<String> RELATION_TYPES = Set.of("LABEL_VALUE", "INLINE_TEXT");
    private static final Set<String> VALUE_TYPES = Set.of(
            "string", "number", "integer", "boolean", "date", "datetime", "time", "duration"
    );
    private static final Set<String> EDITABILITY = Set.of(
            "EDITABLE", "READ_ONLY", "CONDITIONAL", "UNKNOWN"
    );
    private static final Set<String> VALUE_SOURCES = Set.of(
            "USER_INPUT", "FORMULA", "REFERENCE", "STATIC", "MIXED", "UNKNOWN"
    );
    private static final Set<String> TABLE_KINDS = Set.of("ROW_TABLE", "MATRIX");
    private static final Set<String> ISSUE_CATEGORIES = Set.of(
            "FIELD_RELATION_UNCLEAR", "BUSINESS_BLOCK_UNCLEAR", "TABLE_STRUCTURE_UNCLEAR",
            "EDITABILITY_UNCLEAR", "LAYOUT_INCONSISTENT", "DUPLICATE_MEANING", "OTHER"
    );
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "BLOCKER");

    private final ObjectMapper objectMapper;

    GlobalSemanticRecognitionProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode responseSchema() {
        try {
            return objectMapper.readTree(SCHEMA);
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载全局语义识别协议", exception);
        }
    }

    ObjectNode validate(JsonNode response, JsonNode physicalFacts) {
        requireObject(response, "响应根节点");
        var candidate = (ObjectNode) response.deepCopy();
        exactKeys(candidate, "响应根节点", Set.of(
                "recognitionProtocolVersion", "semanticAnnotations", "businessBlocks",
                "fieldRelations", "tables", "qualityIssues"
        ));
        require(candidate.path("recognitionProtocolVersion").isIntegralNumber()
                        && candidate.path("recognitionProtocolVersion").asInt() == VERSION,
                "recognitionProtocolVersion 必须为 1");
        for (var key : List.of("semanticAnnotations", "businessBlocks", "fieldRelations", "tables", "qualityIssues")) {
            require(candidate.path(key).isArray(), key + " 必须是数组");
        }

        var sheets = sheets(physicalFacts);
        var blockIds = uniqueIds(candidate.path("businessBlocks"), "businessBlocks");
        repairUniquelyContainedRelationBlocks(candidate, blockIds);
        var relationIds = uniqueIds(candidate.path("fieldRelations"), "fieldRelations");
        var tableIds = uniqueIds(candidate.path("tables"), "tables");
        uniqueIds(candidate.path("qualityIssues"), "qualityIssues");

        validateBlocks(candidate.path("businessBlocks"), sheets, blockIds);
        validateRelations(candidate.path("fieldRelations"), sheets, blockIds);
        validateTables(candidate.path("tables"), sheets, blockIds);
        validateAnnotations(candidate.path("semanticAnnotations"), sheets, blockIds, relationIds, tableIds);
        validateQualityIssues(candidate.path("qualityIssues"), sheets, blockIds);
        return candidate;
    }

    /**
     * Repairs only a missing relation-to-block reference that is physically unambiguous.
     * Any absent, nested or otherwise ambiguous match is left untouched and rejected by
     * the normal strict reference validation below.
     */
    private void repairUniquelyContainedRelationBlocks(ObjectNode response, Set<String> blockIds) {
        var blocks = response.path("businessBlocks");
        for (var relation : response.withArray("fieldRelations")) {
            if (!(relation instanceof ObjectNode object)) continue;
            var reference = object.path("blockTemporaryId").asText("");
            if (reference.isBlank() || blockIds.contains(reference)) continue;
            var sheetId = object.path("sheetId").asText("");
            var labelRange = bounds(object.path("labelRange").asText(""));
            var valueRange = bounds(object.path("valueRange").asText(""));
            if (labelRange == null || valueRange == null) continue;
            String match = null;
            var matches = 0;
            for (var block : blocks) {
                if (!sheetId.equals(block.path("sheetId").asText(""))) continue;
                var blockRange = bounds(block.path("range").asText(""));
                if (blockRange == null) continue;
                if (!blockRange.contains(labelRange) || !blockRange.contains(valueRange)) continue;
                match = block.path("temporaryId").asText("");
                matches++;
            }
            if (matches == 1) object.put("blockTemporaryId", match);
        }
    }

    private void validateAnnotations(
            JsonNode values, Map<String, SheetBounds> sheets, Set<String> blockIds,
            Set<String> relationIds, Set<String> tableIds
    ) {
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            var path = "semanticAnnotations[" + index + "]";
            requireObject(value, path);
            exactKeys(value, path, Set.of(
                    "sheetId", "range", "role", "temporaryRelationRef", "temporaryBlockRef", "temporaryTableRef"
            ));
            validateRange(value, path, sheets);
            enumValue(value, "role", ANNOTATION_ROLES, path);
            optionalRef(value, "temporaryRelationRef", relationIds, path);
            optionalRef(value, "temporaryBlockRef", blockIds, path);
            optionalRef(value, "temporaryTableRef", tableIds, path);
            if (Set.of("FIELD_LABEL", "FIELD_VALUE").contains(value.path("role").asText())) {
                require(value.path("temporaryRelationRef").isTextual(),
                        path + " 的字段标签/值必须引用 fieldRelations");
            }
            if (Set.of("TABLE_HEADER", "TABLE_DATA", "TABLE_TOTAL").contains(value.path("role").asText())) {
                require(value.path("temporaryTableRef").isTextual(),
                        path + " 的表格范围必须引用 tables");
            }
        }
    }

    private void validateBlocks(JsonNode values, Map<String, SheetBounds> sheets, Set<String> blockIds) {
        var blocks = new LinkedHashMap<String, JsonNode>();
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            var path = "businessBlocks[" + index + "]";
            requireObject(value, path);
            exactKeys(value, path, Set.of(
                    "temporaryId", "sheetId", "range", "type", "parentTemporaryId",
                    "businessName", "groupNameSuggestion", "semanticKeySuggestion"
            ));
            requiredText(value, "temporaryId", path);
            requiredText(value, "businessName", path);
            validateRange(value, path, sheets);
            enumValue(value, "type", BLOCK_TYPES, path);
            optionalRef(value, "parentTemporaryId", blockIds, path);
            blocks.put(value.path("temporaryId").asText(), value);
        }
        for (var entry : blocks.entrySet()) {
            var child = entry.getValue();
            var parentId = child.path("parentTemporaryId").asText("");
            if (parentId.isBlank()) continue;
            require(!parentId.equals(entry.getKey()), "业务块不能引用自身作为父块");
            var parent = blocks.get(parentId);
            require(parent != null && parent.path("sheetId").asText().equals(child.path("sheetId").asText()),
                    "父子业务块必须位于同一工作表");
            require(bounds(parent.path("range").asText()).contains(bounds(child.path("range").asText())),
                    "父业务块必须完整包含子业务块：" + entry.getKey());
            var visited = new HashSet<String>();
            var cursor = entry.getKey();
            while (cursor != null && visited.add(cursor)) {
                var current = blocks.get(cursor);
                cursor = current == null ? null : blankToNull(current.path("parentTemporaryId").asText(""));
            }
            require(cursor == null, "业务块父子关系不能形成循环");
        }
        var list = new ArrayList<>(blocks.values());
        for (int left = 0; left < list.size(); left++) {
            for (int right = left + 1; right < list.size(); right++) {
                var first = list.get(left);
                var second = list.get(right);
                if (!first.path("sheetId").asText().equals(second.path("sheetId").asText())) continue;
                if (!first.path("parentTemporaryId").asText("")
                        .equals(second.path("parentTemporaryId").asText(""))) continue;
                require(!bounds(first.path("range").asText()).overlaps(bounds(second.path("range").asText())),
                        "同一父块下的兄弟业务块不能重叠");
            }
        }
    }

    private void validateRelations(JsonNode values, Map<String, SheetBounds> sheets, Set<String> blockIds) {
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            var path = "fieldRelations[" + index + "]";
            requireObject(value, path);
            exactKeys(value, path, Set.of(
                    "temporaryId", "sheetId", "labelRange", "valueRange", "relationType",
                    "businessName", "blockTemporaryId", "groupNameSuggestion", "semanticKeySuggestion",
                    "valueType", "required", "editability", "valueSource", "unit", "condition"
            ));
            requiredText(value, "temporaryId", path);
            requiredText(value, "businessName", path);
            validateNamedRange(value, "labelRange", path, sheets);
            validateNamedRange(value, "valueRange", path, sheets);
            enumValue(value, "relationType", RELATION_TYPES, path);
            enumValue(value, "valueType", VALUE_TYPES, path);
            enumValue(value, "editability", EDITABILITY, path);
            enumValue(value, "valueSource", VALUE_SOURCES, path);
            require(value.path("required").isBoolean(), path + ".required 必须是布尔值");
            optionalRef(value, "blockTemporaryId", blockIds, path);
            validateCondition(value, path);
        }
    }

    private void validateTables(JsonNode values, Map<String, SheetBounds> sheets, Set<String> blockIds) {
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            var path = "tables[" + index + "]";
            requireObject(value, path);
            exactKeys(value, path, Set.of(
                    "temporaryId", "sheetId", "range", "tableKind", "businessName",
                    "blockTemporaryId", "groupNameSuggestion", "semanticKeySuggestion",
                    "headerRange", "dataRange", "totalRange", "columns"
            ));
            requiredText(value, "temporaryId", path);
            requiredText(value, "businessName", path);
            validateRange(value, path, sheets);
            validateNamedRange(value, "headerRange", path, sheets);
            validateNamedRange(value, "dataRange", path, sheets);
            if (value.has("totalRange") && !value.path("totalRange").asText("").isBlank()) {
                validateNamedRange(value, "totalRange", path, sheets);
            }
            enumValue(value, "tableKind", TABLE_KINDS, path);
            optionalRef(value, "blockTemporaryId", blockIds, path);
            require(value.path("columns").isArray() && !value.path("columns").isEmpty(),
                    path + ".columns 必须是非空数组");
            var columnIds = new HashSet<String>();
            for (int columnIndex = 0; columnIndex < value.path("columns").size(); columnIndex++) {
                var column = value.path("columns").get(columnIndex);
                var columnPath = path + ".columns[" + columnIndex + "]";
                requireObject(column, columnPath);
                exactKeys(column, columnPath, Set.of(
                        "temporaryId", "name", "labelRange", "valueRange", "valueType",
                        "editability", "valueSource", "unit", "condition", "semanticKeySuggestion"
                ));
                requiredText(column, "temporaryId", columnPath);
                requiredText(column, "name", columnPath);
                require(columnIds.add(column.path("temporaryId").asText()),
                        columnPath + ".temporaryId 重复");
                validateNamedRange(columnWithSheet(column, value.path("sheetId").asText()),
                        "labelRange", columnPath, sheets);
                validateNamedRange(columnWithSheet(column, value.path("sheetId").asText()),
                        "valueRange", columnPath, sheets);
                enumValue(column, "valueType", VALUE_TYPES, columnPath);
                enumValue(column, "editability", EDITABILITY, columnPath);
                enumValue(column, "valueSource", VALUE_SOURCES, columnPath);
                validateCondition(column, columnPath);
            }
        }
    }

    private void validateQualityIssues(JsonNode values, Map<String, SheetBounds> sheets, Set<String> blockIds) {
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            var path = "qualityIssues[" + index + "]";
            requireObject(value, path);
            exactKeys(value, path, Set.of(
                    "temporaryId", "sheetId", "range", "category", "severity", "title",
                    "description", "businessImpact", "rootBlockTemporaryId"
            ));
            requiredText(value, "temporaryId", path);
            requiredText(value, "title", path);
            requiredText(value, "description", path);
            requiredText(value, "businessImpact", path);
            validateRange(value, path, sheets);
            enumValue(value, "category", ISSUE_CATEGORIES, path);
            enumValue(value, "severity", SEVERITIES, path);
            optionalRef(value, "rootBlockTemporaryId", blockIds, path);
        }
    }

    private void validateCondition(JsonNode value, String path) {
        var editability = value.path("editability").asText();
        if ("CONDITIONAL".equals(editability)) {
            require(value.path("condition").isTextual() && !value.path("condition").asText().isBlank(),
                    path + " 条件可编辑时必须提供 condition");
        } else {
            require(!value.has("condition") || value.path("condition").isNull()
                            || value.path("condition").asText("").isBlank(),
                    path + " 仅 CONDITIONAL 可提供 condition");
        }
    }

    private ObjectNode columnWithSheet(JsonNode column, String sheetId) {
        var copy = (ObjectNode) column.deepCopy();
        copy.put("sheetId", sheetId);
        return copy;
    }

    private void validateRange(JsonNode value, String path, Map<String, SheetBounds> sheets) {
        requiredText(value, "sheetId", path);
        validateNamedRange(value, "range", path, sheets);
    }

    private void validateNamedRange(
            JsonNode value, String key, String path, Map<String, SheetBounds> sheets
    ) {
        requiredText(value, "sheetId", path);
        requiredText(value, key, path);
        var sheetId = value.path("sheetId").asText();
        var sheet = sheets.get(sheetId);
        require(sheet != null, path + ".sheetId 引用了不存在的工作表");
        var range = bounds(value.path(key).asText());
        require(range != null && sheet.bounds().contains(range), path + "." + key + " 超出工作表使用范围");
    }

    private Map<String, SheetBounds> sheets(JsonNode physicalFacts) {
        var result = new HashMap<String, SheetBounds>();
        for (var sheet : physicalFacts.path("sheets")) {
            var id = sheet.path("id").asText(sheet.path("sheetId").asText(""));
            var used = bounds(sheet.path("usedRange").asText("A1"));
            require(!id.isBlank() && used != null, "物理事实中的工作表范围无效");
            result.put(id, new SheetBounds(id, used));
        }
        require(!result.isEmpty(), "物理事实中没有可识别工作表");
        return result;
    }

    private Set<String> uniqueIds(JsonNode values, String path) {
        var result = new HashSet<String>();
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            requireObject(value, path + "[" + index + "]");
            requiredText(value, "temporaryId", path + "[" + index + "]");
            require(result.add(value.path("temporaryId").asText()), path + " 中 temporaryId 重复");
        }
        return result;
    }

    private void optionalRef(JsonNode value, String key, Set<String> ids, String path) {
        if (!value.has(key) || value.path(key).isNull() || value.path(key).asText("").isBlank()) return;
        require(value.path(key).isTextual() && ids.contains(value.path(key).asText()),
                path + "." + key + " 引用了不存在的临时标识");
    }

    private void enumValue(JsonNode value, String key, Set<String> allowed, String path) {
        require(value.path(key).isTextual() && allowed.contains(value.path(key).asText()),
                path + "." + key + " 包含未知枚举值");
    }

    private void requiredText(JsonNode value, String key, String path) {
        require(value.path(key).isTextual() && !value.path(key).asText().isBlank(),
                path + "." + key + " 必须是非空文本");
    }

    private void requireObject(JsonNode value, String path) {
        require(value != null && value.isObject(), path + " 必须是对象");
    }

    private void exactKeys(JsonNode value, String path, Set<String> allowed) {
        var fields = value.fieldNames();
        while (fields.hasNext()) {
            var key = fields.next();
            require(allowed.contains(key), path + " 包含协议未定义字段：" + key);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new ProtocolViolationException(message);
    }

    private Range bounds(String value) {
        if (value == null) return null;
        var match = ADDRESS.matcher(value.replace("$", "").toUpperCase(Locale.ROOT));
        if (!match.matches()) return null;
        var startColumn = column(match.group(1));
        var startRow = Integer.parseInt(match.group(2));
        var endColumn = match.group(3) == null ? startColumn : column(match.group(3));
        var endRow = match.group(4) == null ? startRow : Integer.parseInt(match.group(4));
        return new Range(Math.min(startColumn, endColumn), Math.min(startRow, endRow),
                Math.max(startColumn, endColumn), Math.max(startRow, endRow));
    }

    private int column(String letters) {
        var result = 0;
        for (var letter : letters.toCharArray()) result = result * 26 + letter - 'A' + 1;
        return result;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static final class ProtocolViolationException extends IllegalArgumentException {
        ProtocolViolationException(String message) {
            super("全局语义识别协议校验失败：" + message);
        }
    }

    private record SheetBounds(String id, Range bounds) {
    }

    private record Range(int startColumn, int startRow, int endColumn, int endRow) {
        boolean contains(Range other) {
            return other != null && startColumn <= other.startColumn && startRow <= other.startRow
                    && endColumn >= other.endColumn && endRow >= other.endRow;
        }

        boolean overlaps(Range other) {
            return other != null && startColumn <= other.endColumn && endColumn >= other.startColumn
                    && startRow <= other.endRow && endRow >= other.startRow;
        }
    }

    private static final String SCHEMA = """
            {
              "$schema":"https://json-schema.org/draft/2020-12/schema",
              "type":"object","additionalProperties":false,
              "required":["recognitionProtocolVersion","semanticAnnotations","businessBlocks","fieldRelations","tables","qualityIssues"],
              "properties":{
                "recognitionProtocolVersion":{"const":1},
                "semanticAnnotations":{"type":"array","items":{"$ref":"#/$defs/annotation"}},
                "businessBlocks":{"type":"array","items":{"$ref":"#/$defs/block"}},
                "fieldRelations":{"type":"array","items":{"$ref":"#/$defs/relation"}},
                "tables":{"type":"array","items":{"$ref":"#/$defs/table"}},
                "qualityIssues":{"type":"array","items":{"$ref":"#/$defs/issue"}}
              },
              "$defs":{
                "range":{"type":"string","pattern":"^[A-Z]{1,4}[1-9][0-9]*(?::[A-Z]{1,4}[1-9][0-9]*)?$"},
                "editability":{"enum":["EDITABLE","READ_ONLY","CONDITIONAL","UNKNOWN"]},
                "valueSource":{"enum":["USER_INPUT","FORMULA","REFERENCE","STATIC","MIXED","UNKNOWN"]},
                "valueType":{"enum":["string","number","integer","boolean","date","datetime","time","duration"]},
                "annotation":{"type":"object","additionalProperties":false,"required":["sheetId","range","role","temporaryRelationRef","temporaryBlockRef","temporaryTableRef"],"properties":{"sheetId":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"role":{"enum":["DOCUMENT_TITLE","INLINE_METADATA","FIELD_LABEL","FIELD_VALUE","TABLE_HEADER","TABLE_DATA","TABLE_TOTAL","INSTRUCTION","CONFIRMATION","SIGNATURE","NOTE","LOOKUP_DATA","STATIC_REFERENCE","UNKNOWN"]},"temporaryRelationRef":{"type":"string"},"temporaryBlockRef":{"type":"string"},"temporaryTableRef":{"type":"string"}}},
                "block":{"type":"object","additionalProperties":false,"required":["temporaryId","sheetId","range","type","parentTemporaryId","businessName","groupNameSuggestion","semanticKeySuggestion"],"properties":{"temporaryId":{"type":"string","minLength":1},"sheetId":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"type":{"enum":["DOCUMENT_HEADER","FORM_FIELDS","ROW_TABLE","MATRIX","INSTRUCTION_LIST","CONFIRMATION_BLOCK","SIGNATURE_BLOCK","NOTE_BLOCK","LOOKUP_TABLE","UNKNOWN"]},"parentTemporaryId":{"type":"string"},"businessName":{"type":"string","minLength":1},"groupNameSuggestion":{"type":"string"},"semanticKeySuggestion":{"type":"string"}}},
                "relation":{"type":"object","additionalProperties":false,"required":["temporaryId","sheetId","labelRange","valueRange","relationType","businessName","blockTemporaryId","groupNameSuggestion","semanticKeySuggestion","valueType","required","editability","valueSource","unit","condition"],"properties":{"temporaryId":{"type":"string","minLength":1},"sheetId":{"type":"string","minLength":1},"labelRange":{"$ref":"#/$defs/range"},"valueRange":{"$ref":"#/$defs/range"},"relationType":{"enum":["LABEL_VALUE","INLINE_TEXT"]},"businessName":{"type":"string","minLength":1},"blockTemporaryId":{"type":"string"},"groupNameSuggestion":{"type":"string"},"semanticKeySuggestion":{"type":"string"},"valueType":{"$ref":"#/$defs/valueType"},"required":{"type":"boolean"},"editability":{"$ref":"#/$defs/editability"},"valueSource":{"$ref":"#/$defs/valueSource"},"unit":{"type":"string"},"condition":{"type":"string"}}},
                "column":{"type":"object","additionalProperties":false,"required":["temporaryId","name","labelRange","valueRange","valueType","editability","valueSource","unit","condition","semanticKeySuggestion"],"properties":{"temporaryId":{"type":"string","minLength":1},"name":{"type":"string","minLength":1},"labelRange":{"$ref":"#/$defs/range"},"valueRange":{"$ref":"#/$defs/range"},"valueType":{"$ref":"#/$defs/valueType"},"editability":{"$ref":"#/$defs/editability"},"valueSource":{"$ref":"#/$defs/valueSource"},"unit":{"type":"string"},"condition":{"type":"string"},"semanticKeySuggestion":{"type":"string"}}},
                "table":{"type":"object","additionalProperties":false,"required":["temporaryId","sheetId","range","tableKind","businessName","blockTemporaryId","groupNameSuggestion","semanticKeySuggestion","headerRange","dataRange","totalRange","columns"],"properties":{"temporaryId":{"type":"string","minLength":1},"sheetId":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"tableKind":{"enum":["ROW_TABLE","MATRIX"]},"businessName":{"type":"string","minLength":1},"blockTemporaryId":{"type":"string"},"groupNameSuggestion":{"type":"string"},"semanticKeySuggestion":{"type":"string"},"headerRange":{"$ref":"#/$defs/range"},"dataRange":{"$ref":"#/$defs/range"},"totalRange":{"type":"string"},"columns":{"type":"array","minItems":1,"items":{"$ref":"#/$defs/column"}}}},
                "issue":{"type":"object","additionalProperties":false,"required":["temporaryId","sheetId","range","category","severity","title","description","businessImpact","rootBlockTemporaryId"],"properties":{"temporaryId":{"type":"string","minLength":1},"sheetId":{"type":"string","minLength":1},"range":{"$ref":"#/$defs/range"},"category":{"enum":["FIELD_RELATION_UNCLEAR","BUSINESS_BLOCK_UNCLEAR","TABLE_STRUCTURE_UNCLEAR","EDITABILITY_UNCLEAR","LAYOUT_INCONSISTENT","DUPLICATE_MEANING","OTHER"]},"severity":{"enum":["INFO","WARNING","BLOCKER"]},"title":{"type":"string","minLength":1},"description":{"type":"string","minLength":1},"businessImpact":{"type":"string","minLength":1},"rootBlockTemporaryId":{"type":"string"}}}
              }
            }
            """;
}
