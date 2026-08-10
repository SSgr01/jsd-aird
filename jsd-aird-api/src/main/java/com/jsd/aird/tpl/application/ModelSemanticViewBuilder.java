package com.jsd.aird.tpl.application;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Creates the compact read-only view sent to the model. The complete physical facts remain
 * persisted; this view keeps structural topology and input evidence while dropping workbook
 * style identifiers and formatting payloads.
 */
public final class ModelSemanticViewBuilder {

    private static final List<String> CELL_KEYS = List.of(
            "address", "factType", "value", "formula", "inputCandidate", "inputConfidence",
            "inputEvidence", "physicalValueType", "mergedRange", "mergeAnchor", "mergeAnchorCell",
            "bold", "hasBorder"
    );
    private static final List<String> PROFILE_KEYS = List.of(
            "row", "column", "columnName", "height", "width",
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
        for (var sourceSheet : structure.path("sheets")) {
            var sheet = objectMapper.createObjectNode()
                    .put("id", sourceSheet.path("id").asText(sourceSheet.path("sheetId").asText("")))
                    .put("name", sourceSheet.path("name").asText())
                    .put("hidden", sourceSheet.path("hidden").asBoolean(false))
                    .put("usedRange", sourceSheet.path("usedRange").asText("A1"));
            var cells = compactCells(sourceSheet.path("semanticCells"));
            sheet.set("semanticCells", cells);
            var merges = compactRanges(sourceSheet.path("mergedRanges"),
                    List.of("range", "anchor"));
            sheet.set("mergedRanges", merges);
            sheet.set("borderSegments", compactBorderSegments(sourceSheet.path("borderSegments")));
            sheet.set("layoutSpans", compactLayoutSpans(sourceSheet.path("layoutSpans")));
            sheet.set("rowProfiles", compactProfiles(sourceSheet.path("rowProfiles")));
            sheet.set("columnProfiles", compactProfiles(sourceSheet.path("columnProfiles")));
            sheet.set("dataValidationRules", compactRanges(sourceSheet.path("dataValidationRules"),
                    List.of("sheetId", "sheetName", "range", "type", "formula1", "allowBlank")));
            sheet.set("nativeTables", compactRanges(sourceSheet.path("nativeTables"),
                    List.of("name", "displayName", "sheetId", "range", "geometryStatus", "semanticStatus")));
            sheets.add(sheet);
        }
        context.set("sheets", sheets);
        var originalFactChars = structure.path("sheets").toString().length();
        var compactFactChars = sheets.toString().length();
        context.put("factsViewVersion", 2);
        context.set("factsCompression", objectMapper.createObjectNode()
                .put("originalChars", originalFactChars)
                .put("compactChars", compactFactChars)
                .put("reductionRatio", originalFactChars == 0
                        ? 0.0
                        : 1.0 - compactFactChars / (double) originalFactChars)
                .put("mergeTopologyPreserved", true)
                .put("styleDetailsOmitted", true)
                .set("omittedKeys", objectMapper.createArrayNode()
                        .add("styleRef")
                        .add("alignment")
                        .add("fill")
                        .add("numberFormat")));
        context.set("structureHints", compactHints(structure.path("structureHints")));
        // Structure candidates are deliberately not included in the model view.
        // They are backend hypotheses and showing their interpreted geometry to
        // the structure model turns an independent proposal into a confirmation
        // task.  The resolver still computes them separately from the same raw
        // workbook facts after the model call.
        context.put("structureProposalInput", "PHYSICAL_FACTS_ONLY");
        context.set("namedRanges", compactRanges(structure.path("namedRanges"),
                List.of("name", "sheetId", "range", "formula", "geometryStatus", "semanticStatus")));
        return context;
    }

    /** Builds a second-stage view containing only one structure primitive's region. */
    public ObjectNode buildRegion(
            JsonNode structure, String scope, String requestedSheetId, String requestedAddress,
            String regionId, String regionSheetId, String regionRange
    ) {
        return buildRegion(structure, scope, requestedSheetId, requestedAddress,
                regionId, regionSheetId, regionRange, null);
    }

    /**
     * Builds a region view with the canonical primitive selected by stage one.
     * Stage two may add semantic fields, but it is not allowed to change the
     * physical region kind (especially MATRIX versus ROW_TABLE).
     */
    public ObjectNode buildRegion(
            JsonNode structure, String scope, String requestedSheetId, String requestedAddress,
            String regionId, String regionSheetId, String regionRange, JsonNode canonicalRegion
    ) {
        var context = build(structure, scope, requestedSheetId, requestedAddress);
        context.put("recognitionMode", "REGION_FIELDS");
        context.put("regionId", regionId == null ? "" : regionId);
        context.put("regionSheetId", regionSheetId == null ? "" : regionSheetId);
        context.put("regionRange", regionRange == null ? "" : regionRange);
        if (canonicalRegion != null && canonicalRegion.isObject()) {
            context.put("canonicalBlockType", canonicalRegion.path("type").asText("UNKNOWN"));
            context.put("canonicalBlockName", canonicalRegion.path("businessName").asText(""));
            context.put("canonicalBlockRange", canonicalRegion.path("range").asText(regionRange));
            context.put("canonicalStatus", canonicalRegion.path("canonicalStatus").asText("PROVISIONAL"));
            context.put("canonicalBlockId", canonicalRegion.path("blockId")
                    .asText(canonicalRegion.path("temporaryId").asText(regionId == null ? "" : regionId)));
            context.put("canonicalStructureRequired", false);
            context.put("canonicalStructureMayReopen",
                    !"CONFIRMED".equals(canonicalRegion.path("canonicalStatus").asText(""))
                            || !"CONFIRMED".equals(canonicalRegion.path("structureStatus").asText("")));
            context.set("canonicalStructure", canonicalStructure(canonicalRegion));
        }

        var sheets = context.withArray("sheets");
        for (int index = sheets.size() - 1; index >= 0; index--) {
            var sheet = (ObjectNode) sheets.get(index);
            if (!regionSheetId.equals(sheet.path("id").asText())) {
                sheets.remove(index);
                continue;
            }
            filterSheet(sheet, regionRange);
        }
        filterArray(context, "structureHints", regionSheetId, regionRange);
        return context;
    }

    private JsonNode canonicalStructure(JsonNode block) {
        var result = objectMapper.createObjectNode();
        result.put("blockType", block.path("type").asText("UNKNOWN"));
        result.put("range", block.path("range").asText(""));
        result.put("businessName", block.path("businessName").asText(""));
        // A model proposal is not a resolved canonical structure. It becomes
        // CONFIRMED only after the structure resolver (or explicit human review)
        // accepts it.
        result.put("canonicalStatus", block.path("canonicalStatus").asText("PROVISIONAL"));
        if (block.path("structure").isObject()) {
            result.set("modelStructure", block.path("structure").deepCopy());
        }
        return result;
    }

    private void filterSheet(ObjectNode sheet, String regionRange) {
        for (var key : List.of("semanticCells", "mergedRanges", "borderSegments", "layoutSpans",
                "dataValidationRules", "rowProfiles", "columnProfiles")) {
            var values = sheet.path(key);
            if (!values.isArray()) continue;
            var filtered = objectMapper.createArrayNode();
            for (var value : values) {
                var range = value.path("range").asText(value.path("address").asText(""));
                if ("rowProfiles".equals(key)) {
                    var row = value.path("row").asInt(0);
                    if (row > 0 && intersects(regionRange, "A" + row + ":XFD" + row)) filtered.add(value);
                } else if ("columnProfiles".equals(key)) {
                    var column = value.path("column").asInt(0);
                    if (column > 0 && intersects(regionRange, "A1:XFD" + Integer.MAX_VALUE)
                            && columnIntersects(regionRange, column)) filtered.add(value);
                } else if (range.isBlank() || intersects(regionRange, range)) {
                    filtered.add(value);
                }
            }
            sheet.set(key, filtered);
        }
    }

    private void filterArray(ObjectNode context, String key, String sheetId, String regionRange) {
        var source = context.path(key);
        if (!source.isArray()) return;
        var filtered = objectMapper.createArrayNode();
        for (var value : source) {
            if (!sheetId.equals(value.path("sheetId").asText(""))) continue;
            var range = value.path("range").asText(value.path("address").asText(""));
            if (range.isBlank() || intersects(regionRange, range)) filtered.add(value);
        }
        context.set(key, filtered);
    }

    private boolean columnIntersects(String regionRange, int column) {
        var bounds = rangeBounds(regionRange);
        return bounds == null || bounds[0] <= column && column <= bounds[2];
    }

    private boolean intersects(String first, String second) {
        if (first == null || first.isBlank() || second == null || second.isBlank()) return true;
        var left = rangeBounds(first);
        var right = rangeBounds(second);
        return left == null || right == null
                || left[0] <= right[2] && right[0] <= left[2]
                && left[1] <= right[3] && right[1] <= left[3];
    }

    private int[] rangeBounds(String address) {
        var matcher = java.util.regex.Pattern.compile(
                "^\\$?([A-Z]+)\\$?([1-9][0-9]*)(?::\\$?([A-Z]+)\\$?([1-9][0-9]*))?$"
        ).matcher(address == null ? "" : address.toUpperCase(java.util.Locale.ROOT));
        if (!matcher.matches()) return null;
        var firstColumn = columnIndex(matcher.group(1));
        var firstRow = Integer.parseInt(matcher.group(2));
        var lastColumn = matcher.group(3) == null ? firstColumn : columnIndex(matcher.group(3));
        var lastRow = matcher.group(4) == null ? firstRow : Integer.parseInt(matcher.group(4));
        return new int[]{Math.min(firstColumn, lastColumn), Math.min(firstRow, lastRow),
                Math.max(firstColumn, lastColumn), Math.max(firstRow, lastRow)};
    }

    private int columnIndex(String letters) {
        var result = 0;
        for (var letter : letters.toCharArray()) result = result * 26 + letter - 'A' + 1;
        return result;
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

    private ArrayNode compactLayoutSpans(JsonNode source) {
        var result = objectMapper.createArrayNode();
        for (var item : source) {
            var meaningful = item.path("bold").asBoolean(false)
                    || item.path("inputSurface").asBoolean(false)
                    || item.path("blankInputSurface").asBoolean(false)
                    || item.path("formulaSurface").asBoolean(false)
                    || item.path("textSurface").asBoolean(false);
            if (!meaningful) continue;

            var copy = objectMapper.createObjectNode();
            copySelected(copy, item, List.of("range", "bold",
                    "inputSurface", "blankInputSurface", "formulaSurface", "textSurface",
                    "topology"));
            result.add(copy);
        }
        return result;
    }

    private ArrayNode compactBorderSegments(JsonNode source) {
        var result = objectMapper.createArrayNode();
        for (var item : source) {
            var range = item.path("range").asText("");
            var current = rangeBounds(range);
            var previous = result.size() == 0 ? null : result.get(result.size() - 1);
            var previousBounds = previous == null ? null : rangeBounds(previous.path("range").asText(""));
            if (current != null && previous != null && previousBounds != null
                    && item.path("orientation").asText("").equals(previous.path("orientation").asText(""))
                    && current[0] == previousBounds[0]
                    && current[2] == previousBounds[2]
                    && current[1] == previousBounds[3] + 1) {
                ((ObjectNode) previous).put("range", excelRange(
                        previousBounds[0], previousBounds[1], current[2], current[3]));
                continue;
            }
            var copy = objectMapper.createObjectNode();
            if (!range.isBlank()) copy.put("range", range);
            var orientation = item.path("orientation").asText("");
            if (!orientation.isBlank()) copy.put("orientation", orientation);
            result.add(copy);
        }
        return result;
    }

    private String excelRange(int startColumn, int startRow, int endColumn, int endRow) {
        return columnName(startColumn) + startRow + ":" + columnName(endColumn) + endRow;
    }

    private String columnName(int column) {
        var value = column;
        var result = new StringBuilder();
        while (value > 0) {
            result.append((char) ('A' + (value - 1) % 26));
            value = (value - 1) / 26;
        }
        return result.reverse().toString();
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
            var range = item.path("range").asText(item.path("address").asText(""));
            if (!range.isBlank()) copy.put("range", range);
            var anchor = item.path("anchor").asText(item.path("anchorAddress").asText(""));
            if (anchor.isBlank()) anchor = range.split(":", 2)[0];
            if (!anchor.isBlank()) copy.put("anchor", anchor);
            copySelected(copy, item, keys.stream()
                    .filter(key -> !"range".equals(key) && !"anchor".equals(key)).toList());
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
