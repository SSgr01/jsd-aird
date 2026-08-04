package com.jsd.aird.tpl.infrastructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.GroupNameNormalizer;
import com.jsd.aird.tpl.application.RecognitionIdentity;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;

/** Converts a validated sparse semantic response into persistence-compatible candidates. */
final class GlobalSemanticSuggestionCompiler {

    private static final Set<String> STATIC_BLOCK_TYPES = Set.of(
            "DOCUMENT_HEADER", "INSTRUCTION_LIST", "NOTE_BLOCK", "LOOKUP_TABLE"
    );

    private final ObjectMapper objectMapper;

    GlobalSemanticSuggestionCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Compiled compile(ObjectNode response, JsonNode physicalFacts) {
        var suggestions = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        var sheetNames = sheetNames(physicalFacts);
        var blocks = stableBlocks(response.path("businessBlocks"));

        var semanticModel = objectMapper.createObjectNode()
                .put("kind", "SEMANTIC_MODEL")
                .put("recognitionMode", "FAITHFUL")
                .put("recognitionProtocolVersion", GlobalSemanticRecognitionProtocol.VERSION);
        semanticModel.set("semanticAnnotations", remapAnnotations(response.path("semanticAnnotations"), blocks));
        var stableBlockArray = objectMapper.createArrayNode();
        blocks.values().forEach(block -> stableBlockArray.add(block.deepCopy()));
        semanticModel.set("businessBlocks", stableBlockArray);
        suggestions.add(new RecognitionModelClient.ModelSuggestion(
                "SEMANTIC_MODEL", semanticModel, 1, objectMapper.createArrayNode()
        ));

        var units = new ArrayList<Unit>();
        for (var relation : response.path("fieldRelations")) {
            if (!isFormalRelation(relation, response.path("tables"), blocks)) continue;
            units.add(new Unit("RELATION", relation, relation.path("sheetId").asText(),
                    relation.path("valueRange").asText(), groupName(relation, blocks)));
        }
        for (var table : response.path("tables")) {
            units.add(new Unit("TABLE", table, table.path("sheetId").asText(),
                    table.path("range").asText(), groupName(table, blocks)));
        }
        units.sort(Comparator.comparing(Unit::sheetId).thenComparingInt(unit -> row(unit.range()))
                .thenComparingInt(unit -> column(unit.range())));
        var groupOrdinals = new HashMap<String, Integer>();
        for (var unit : units) {
            var groupCode = GroupNameNormalizer.code(unit.groupName());
            var ordinal = groupOrdinals.merge(groupCode, 1, Integer::sum);
            if ("RELATION".equals(unit.type())) {
                suggestions.add(relation(unit.value(), unit.groupName(), groupCode, ordinal, blocks, sheetNames));
            } else {
                suggestions.add(table(unit.value(), unit.groupName(), groupCode, ordinal, blocks, sheetNames));
            }
        }

        var qualityIssues = new ArrayList<RecognitionModelClient.QualityIssueSuggestion>();
        for (var issue : response.path("qualityIssues")) {
            var rootTemporaryId = issue.path("rootBlockTemporaryId").asText("");
            var rootBlockId = blocks.containsKey(rootTemporaryId)
                    ? blocks.get(rootTemporaryId).path("blockId").asText() : "sheet-root";
            qualityIssues.add(new RecognitionModelClient.QualityIssueSuggestion(
                    issue.path("category").asText(), issue.path("severity").asText(),
                    issue.path("sheetId").asText(), sheetNames.getOrDefault(
                            issue.path("sheetId").asText(), issue.path("sheetId").asText()),
                    issue.path("range").asText(), issue.path("title").asText(),
                    issue.path("description").asText(), issue.path("businessImpact").asText(),
                    1, false, objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                    objectMapper.createArrayNode(), "DETECTED", rootBlockId, null
            ));
        }
        return new Compiled(List.copyOf(suggestions), List.copyOf(qualityIssues));
    }

    private RecognitionModelClient.ModelSuggestion relation(
            JsonNode source, String groupName, String groupCode, int ordinal,
            Map<String, ObjectNode> blocks, Map<String, String> sheetNames
    ) {
        var sheetId = source.path("sheetId").asText();
        var relationId = RecognitionIdentity.relationId(
                sheetId, source.path("labelRange").asText(), source.path("valueRange").asText(),
                source.path("relationType").asText()
        );
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var inlineText = "INLINE_TEXT".equals(source.path("relationType").asText());
        var locatorType = inlineText ? "INLINE_TEXT" : "CELL_RANGE";
        var bindingId = RecognitionIdentity.bindingId(
                fieldId, locatorType, sheetId + "|" + source.path("valueRange").asText()
        );
        var payload = objectMapper.createObjectNode()
                .put("kind", "SCALAR")
                .put("relationId", relationId)
                .put("fieldId", fieldId.toString())
                .put("bindingId", bindingId.toString())
                .put("temporaryRelationId", source.path("temporaryId").asText())
                .put("fieldCode", fieldCode(groupCode, "FIELD", relationId))
                .put("dataPath", dataPath(groupCode, "field", relationId))
                .put("fieldName", source.path("businessName").asText())
                .put("groupName", groupName)
                .put("valueType", source.path("valueType").asText())
                .put("required", source.path("required").asBoolean(false))
                .put("role", "FIELD").put("locatorType", locatorType)
                .put("editability", source.path("editability").asText())
                .put("valueSource", source.path("valueSource").asText())
                .put("unit", source.path("unit").asText(""))
                .put("condition", source.path("condition").asText(""))
                .put("reason", "根据完整工作簿的标签和值关系识别")
                .put("interpretation", "系统认为这里用于填写或读取“"
                        + source.path("businessName").asText() + "”。");
        var locator = locator(sheetId, sheetNames.getOrDefault(sheetId, sheetId),
                source.path("labelRange").asText(), source.path("valueRange").asText(),
                inlineText ? "INLINE_TEXT" : "ANCHOR");
        if (inlineText) {
            locator.put("valuePart", "AFTER_DELIMITER");
            locator.put("labelPrefix", source.path("businessName").asText());
        }
        payload.set("locator", locator);
        attachBlock(payload, source.path("blockTemporaryId").asText(""), blocks);
        return new RecognitionModelClient.ModelSuggestion(
                "SCALAR_FIELD", payload, confidence(source), objectMapper.createArrayNode()
        );
    }

    private RecognitionModelClient.ModelSuggestion table(
            JsonNode source, String groupName, String groupCode, int ordinal,
            Map<String, ObjectNode> blocks, Map<String, String> sheetNames
    ) {
        var sheetId = source.path("sheetId").asText();
        var kind = source.path("tableKind").asText();
        var relationId = RecognitionIdentity.relationId(
                sheetId, source.path("headerRange").asText(), source.path("dataRange").asText(), kind
        );
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var locatorType = "MATRIX".equals(kind) ? "MATRIX_REGION" : "TABLE_REGION";
        var bindingId = RecognitionIdentity.bindingId(
                fieldId, locatorType, sheetId + "|" + source.path("range").asText()
        );
        var payload = objectMapper.createObjectNode()
                .put("kind", kind).put("relationId", relationId)
                .put("fieldId", fieldId.toString()).put("bindingId", bindingId.toString())
                .put("temporaryRelationId", source.path("temporaryId").asText())
                .put("fieldCode", fieldCode(groupCode, "TABLE", relationId))
                .put("dataPath", dataPath(groupCode, "table", relationId))
                .put("fieldName", source.path("businessName").asText())
                .put("groupName", groupName).put("valueType", "array")
                .put("required", false).put("role", "REPEAT_REGION")
                .put("locatorType", locatorType)
                .put("reason", "根据完整工作簿的表头、数据区和业务上下文识别")
                .put("interpretation", "ROW_TABLE".equals(kind)
                        ? "系统认为这里逐行填写或读取“" + source.path("businessName").asText() + "”记录。"
                        : "系统认为这里按两个业务维度记录“" + source.path("businessName").asText() + "”。");
        var locator = locator(sheetId, sheetNames.getOrDefault(sheetId, sheetId),
                source.path("headerRange").asText(), source.path("range").asText(), "ARRAY");
        locator.put("headerRange", source.path("headerRange").asText());
        locator.put("dataRange", source.path("dataRange").asText());
        locator.put("logicalInputRange", source.path("dataRange").asText());
        if (source.has("totalRange")) locator.put("totalRange", source.path("totalRange").asText());
        if ("MATRIX".equals(kind)) {
            locator.put("rowHeaderRange", source.path("rowHeaderRange").asText());
            locator.put("columnHeaderRange", source.path("columnHeaderRange").asText());
            locator.put("crossDataRange", source.path("crossDataRange").asText());
        }
        payload.set("locator", locator);
        var columns = objectMapper.createArrayNode();
        var columnOrdinal = 0;
        var tableEditability = "READ_ONLY";
        var tableValueSource = "STATIC";
        for (var sourceColumn : source.path("columns")) {
            columnOrdinal++;
            var editability = sourceColumn.path("editability").asText();
            var valueSource = sourceColumn.path("valueSource").asText();
            if ("EDITABLE".equals(editability) || "CONDITIONAL".equals(editability)) tableEditability = "EDITABLE";
            if ("USER_INPUT".equals(valueSource) || "MIXED".equals(valueSource)) tableValueSource = "USER_INPUT";
            columns.add(objectMapper.createObjectNode()
                    .put("code", "column" + String.format(Locale.ROOT, "%02d", columnOrdinal))
                    .put("name", sourceColumn.path("name").asText())
                    .put("labelRange", sourceColumn.path("labelRange").asText())
                    .put("valueRange", sourceColumn.path("valueRange").asText())
                    .put("valueType", sourceColumn.path("valueType").asText())
                    .put("editability", editability).put("valueSource", valueSource)
                    .put("unit", sourceColumn.path("unit").asText(""))
                    .put("condition", sourceColumn.path("condition").asText("")));
        }
        payload.put("editability", tableEditability).put("valueSource", tableValueSource);
        payload.set("columns", columns);
        payload.set("tableModel", objectMapper.createObjectNode()
                .put("headerRange", source.path("headerRange").asText())
                .put("dataRange", source.path("dataRange").asText())
                .set("columns", columns.deepCopy()));
        if ("MATRIX".equals(kind)) {
            payload.set("matrixModel", objectMapper.createObjectNode()
                    .put("semanticMode", source.path("semanticMode").asText())
                    .put("headerRange", source.path("headerRange").asText())
                    .put("dataRange", source.path("dataRange").asText())
                    .put("rowHeaderRange", source.path("rowHeaderRange").asText())
                    .put("columnHeaderRange", source.path("columnHeaderRange").asText())
                    .put("crossDataRange", source.path("crossDataRange").asText())
                    .set("headerTree", source.path("headerTree").deepCopy()));
        }
        attachBlock(payload, source.path("blockTemporaryId").asText(""), blocks);
        return new RecognitionModelClient.ModelSuggestion(
                kind, payload, confidence(source), objectMapper.createArrayNode()
        );
    }

    private LinkedHashMap<String, ObjectNode> stableBlocks(JsonNode source) {
        var raw = new LinkedHashMap<String, JsonNode>();
        for (var block : source) raw.put(block.path("temporaryId").asText(), block);
        var result = new LinkedHashMap<String, ObjectNode>();
        for (var entry : raw.entrySet()) stableBlock(entry.getKey(), raw, result);
        return result;
    }

    private ObjectNode stableBlock(
            String temporaryId, Map<String, JsonNode> raw, Map<String, ObjectNode> result
    ) {
        if (result.containsKey(temporaryId)) return result.get(temporaryId);
        var source = raw.get(temporaryId);
        var parentTemporaryId = source.path("parentTemporaryId").asText("");
        var parent = parentTemporaryId.isBlank() ? null : stableBlock(parentTemporaryId, raw, result);
        var blockId = RecognitionIdentity.blockId(
                source.path("sheetId").asText(), source.path("range").asText(),
                source.path("type").asText(), parent == null ? "" : parent.path("blockId").asText()
        );
        var suggestedGroup = source.path("groupNameSuggestion").asText("");
        var block = objectMapper.createObjectNode()
                .put("blockId", blockId).put("temporaryId", temporaryId)
                .put("sheetId", source.path("sheetId").asText())
                .put("range", source.path("range").asText())
                .put("type", source.path("type").asText())
                .put("businessName", source.path("businessName").asText())
                .put("groupName", suggestedGroup.isBlank() ? "" : GroupNameNormalizer.normalize(suggestedGroup));
        if (parent != null) block.put("parentBlockId", parent.path("blockId").asText());
        result.put(temporaryId, block);
        return block;
    }

    private ArrayNode remapAnnotations(JsonNode source, Map<String, ObjectNode> blocks) {
        var result = objectMapper.createArrayNode();
        for (var annotation : source) {
            var copy = (ObjectNode) annotation.deepCopy();
            var temporaryBlockId = copy.path("temporaryBlockRef").asText("");
            if (blocks.containsKey(temporaryBlockId)) {
                copy.put("blockId", blocks.get(temporaryBlockId).path("blockId").asText());
            }
            result.add(copy);
        }
        return result;
    }

    private ObjectNode locator(
            String sheetId, String sheetName, String labelRange, String valueRange, String valueMode
    ) {
        return objectMapper.createObjectNode()
                .put("sheetId", sheetId).put("sheetName", sheetName)
                .put("labelAddress", firstCell(labelRange)).put("labelRange", labelRange)
                .put("address", valueRange).put("anchorAddress", firstCell(valueRange))
                .put("logicalInputRange", valueRange).put("valueMode", valueMode);
    }

    private void attachBlock(ObjectNode payload, String temporaryId, Map<String, ObjectNode> blocks) {
        if (!blocks.containsKey(temporaryId)) return;
        var block = blocks.get(temporaryId);
        payload.put("blockId", block.path("blockId").asText());
        payload.put("parentBlockId", block.path("parentBlockId").asText(""));
        payload.put("blockType", block.path("type").asText());
        payload.put("regionId", block.path("blockId").asText());
    }

    /**
     * A table is one structured business object. Its column annotations must not be promoted to
     * duplicate top-level scalar fields, and static blocks never become a writable binding.
     */
    private boolean isFormalRelation(JsonNode relation, JsonNode tables, Map<String, ObjectNode> blocks) {
        var block = blocks.get(relation.path("blockTemporaryId").asText(""));
        if (block == null) return false;
        var allowedHeaderMetadata = "DOCUMENT_HEADER".equals(block.path("type").asText())
                && "INLINE_TEXT".equals(relation.path("relationType").asText());
        if (STATIC_BLOCK_TYPES.contains(block.path("type").asText()) && !allowedHeaderMetadata) return false;
        var sheetId = relation.path("sheetId").asText();
        var labelRange = relation.path("labelRange").asText();
        var valueRange = relation.path("valueRange").asText();
        for (var table : tables) {
            if (!sheetId.equals(table.path("sheetId").asText())) continue;
            for (var column : table.path("columns")) {
                if (labelRange.equals(column.path("labelRange").asText())
                        && valueRange.equals(column.path("valueRange").asText())) {
                    return false;
                }
            }
        }
        return true;
    }

    private String groupName(JsonNode value, Map<String, ObjectNode> blocks) {
        var suggested = value.path("groupNameSuggestion").asText("");
        if (!suggested.isBlank()) return GroupNameNormalizer.normalize(suggested);
        var block = blocks.get(value.path("blockTemporaryId").asText(""));
        if (block != null && !block.path("groupName").asText("").isBlank()) {
            return GroupNameNormalizer.normalize(block.path("groupName").asText());
        }
        if (block != null) {
            return switch (block.path("type").asText()) {
                case "SIGNATURE_BLOCK", "CONFIRMATION_BLOCK" -> "审核信息";
                case "ROW_TABLE", "MATRIX", "LOOKUP_TABLE" -> "明细信息";
                default -> GroupNameNormalizer.BASIC_INFORMATION;
            };
        }
        return GroupNameNormalizer.BASIC_INFORMATION;
    }

    private Map<String, String> sheetNames(JsonNode physicalFacts) {
        var result = new HashMap<String, String>();
        for (var sheet : physicalFacts.path("sheets")) {
            result.put(sheet.path("id").asText(), sheet.path("name").asText());
        }
        return result;
    }

    private double confidence(JsonNode value) {
        return "UNKNOWN".equals(value.path("editability").asText())
                || "UNKNOWN".equals(value.path("valueSource").asText()) ? 0.55 : 0.9;
    }

    private String fieldCode(String groupCode, String type, String relationId) {
        return "AUTO." + groupCode + "." + type + "_"
                + RecognitionIdentity.shortHash(relationId, 8).toUpperCase(Locale.ROOT);
    }

    private String dataPath(String groupCode, String type, String relationId) {
        var camel = toCamel(groupCode);
        return "/recognized/" + camel + "/" + type + "_"
                + RecognitionIdentity.shortHash(relationId, 12);
    }

    private String toCamel(String value) {
        var parts = value.toLowerCase(Locale.ROOT).split("_+");
        var result = new StringBuilder(parts.length == 0 ? "other" : parts[0]);
        for (int index = 1; index < parts.length; index++) {
            if (!parts[index].isBlank()) result.append(Character.toUpperCase(parts[index].charAt(0)))
                    .append(parts[index].substring(1));
        }
        return result.toString();
    }

    private int row(String range) {
        var cell = firstCell(range);
        return Integer.parseInt(cell.replaceAll("^[A-Z]+", ""));
    }

    private int column(String range) {
        var letters = firstCell(range).replaceAll("[0-9]+$", "");
        var result = 0;
        for (var letter : letters.toCharArray()) result = result * 26 + letter - 'A' + 1;
        return result;
    }

    private String firstCell(String range) {
        return range.split(":", 2)[0].toUpperCase(Locale.ROOT);
    }

    record Compiled(
            List<RecognitionModelClient.ModelSuggestion> suggestions,
            List<RecognitionModelClient.QualityIssueSuggestion> qualityIssues
    ) {
    }

    private record Unit(String type, JsonNode value, String sheetId, String range, String groupName) {
    }
}
