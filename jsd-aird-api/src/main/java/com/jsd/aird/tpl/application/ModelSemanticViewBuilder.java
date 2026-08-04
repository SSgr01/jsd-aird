package com.jsd.aird.tpl.application;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Creates the compact read-only view sent to the model. The complete v6 physical facts remain
 * persisted; this view removes merge-child blanks and repetitive style/layout detail.
 */
public final class ModelSemanticViewBuilder {

    private static final List<String> CELL_KEYS = List.of(
            "sheetId", "sheetName", "address", "factType", "value", "formula",
            "inputCandidate", "physicalValueType", "mergedRange", "mergeAnchor", "mergeAnchorCell",
            "styleRef"
    );
    private static final List<String> LAYOUT_KEYS = List.of(
            "sheetId", "sheetName", "range", "styleRef", "bold", "hasBorder",
            "horizontalAlignment", "verticalAlignment", "fill", "numberFormat"
    );
    private static final List<String> PROFILE_KEYS = List.of(
            "sheetId", "sheetName", "row", "column", "columnName", "height", "width",
            "hidden", "valueCells", "formulaCells", "firstColumn", "lastColumn", "firstRow", "lastRow"
    );

    private final ObjectMapper objectMapper;

    public ModelSemanticViewBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode build(JsonNode structure, String scope, String requestedSheetId, String requestedAddress) {
        var context = objectMapper.createObjectNode()
                .put("structureVersion", structure.path("structureVersion").asInt())
                .put("parserVersion", structure.path("parserVersion").asText())
                .put("sourceKind", structure.path("sourceKind").asText("XLSX"))
                .put("requestedScope", scope)
                .put("requestedSheetId", requestedSheetId == null ? "" : requestedSheetId)
                .put("requestedAddress", requestedAddress == null ? "" : requestedAddress);
        var sheets = objectMapper.createArrayNode();
        var allCells = objectMapper.createArrayNode();
        var allMerges = objectMapper.createArrayNode();
        for (var sourceSheet : structure.path("sheets")) {
            var sheet = objectMapper.createObjectNode()
                    .put("id", sourceSheet.path("id").asText(sourceSheet.path("sheetId").asText("")))
                    .put("name", sourceSheet.path("name").asText())
                    .put("hidden", sourceSheet.path("hidden").asBoolean(false))
                    .put("usedRange", sourceSheet.path("usedRange").asText("A1"));
            var cells = compactCells(sourceSheet.path("semanticCells"));
            sheet.set("semanticCells", cells);
            appendCopies(allCells, cells);
            var merges = compactRanges(sourceSheet.path("mergedRanges"),
                    List.of("sheetId", "sheetName", "range", "anchor", "anchorAddress"));
            sheet.set("mergedRanges", merges);
            appendCopies(allMerges, merges);
            sheet.set("layoutSpans", compactObjects(sourceSheet.path("layoutSpans"), LAYOUT_KEYS, true));
            sheet.set("rowProfiles", compactProfiles(sourceSheet.path("rowProfiles")));
            sheet.set("columnProfiles", compactProfiles(sourceSheet.path("columnProfiles")));
            sheet.set("dataValidationRules", compactRanges(sourceSheet.path("dataValidationRules"),
                    List.of("sheetId", "sheetName", "range", "type", "formula1", "allowBlank")));
            sheets.add(sheet);
        }
        context.set("sheets", sheets);
        context.set("semanticCells", allCells);
        context.set("mergedRanges", allMerges);
        context.set("structureHints", compactHints(structure.path("structureHints")));
        context.set("namedRanges", compactRanges(structure.path("namedRanges"),
                List.of("name", "sheetId", "range", "formula")));
        return context;
    }

    private ArrayNode compactCells(JsonNode source) {
        var result = objectMapper.createArrayNode();
        for (var cell : source) {
            if (cell.path("mergedRange").isTextual()
                    && !cell.path("mergedRange").asText().isBlank()
                    && !cell.path("mergeAnchorCell").asBoolean(false)) continue;
            var copy = objectMapper.createObjectNode();
            copySelected(copy, cell, CELL_KEYS);
            result.add(copy);
        }
        return result;
    }

    private ArrayNode compactObjects(JsonNode source, List<String> keys, boolean keepOnlyMeaningful) {
        var result = objectMapper.createArrayNode();
        for (var item : source) {
            if (keepOnlyMeaningful && !item.path("bold").asBoolean(false)
                    && !item.path("hasBorder").asBoolean(false)
                    && item.path("fill").asText("").isBlank()
                    && item.path("horizontalAlignment").asText("").isBlank()) continue;
            var copy = objectMapper.createObjectNode();
            copySelected(copy, item, keys);
            result.add(copy);
        }
        return result;
    }

    private ArrayNode compactProfiles(JsonNode source) {
        var result = objectMapper.createArrayNode();
        for (var item : source) {
            if (item.path("valueCells").asInt(0) == 0 && item.path("formulaCells").asInt(0) == 0
                    && !item.path("hidden").asBoolean(false)) continue;
            var copy = objectMapper.createObjectNode();
            copySelected(copy, item, PROFILE_KEYS);
            result.add(copy);
        }
        return result;
    }

    private ArrayNode compactRanges(JsonNode source, List<String> keys) {
        var result = objectMapper.createArrayNode();
        for (var item : source) {
            var copy = objectMapper.createObjectNode();
            copySelected(copy, item, keys);
            result.add(copy);
        }
        return result;
    }

    private ArrayNode compactHints(JsonNode source) {
        var result = objectMapper.createArrayNode();
        for (var item : source) {
            var copy = objectMapper.createObjectNode();
            copySelected(copy, item, List.of("sheetId", "sheetName", "range", "hintType", "description"));
            result.add(copy);
        }
        return result;
    }

    private void copySelected(ObjectNode target, JsonNode source, List<String> keys) {
        for (var key : keys) if (source.has(key)) target.set(key, source.path(key).deepCopy());
    }

    private void appendCopies(ArrayNode target, JsonNode source) {
        for (var item : source) target.add(item.deepCopy());
    }
}
