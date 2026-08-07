package com.jsd.aird.tpl.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the version 6 physical-fact document shared by OOXML imports and
 * persisted Univer snapshots.  It deliberately describes only observable
 * workbook facts; business blocks and field kinds are produced by the global
 * semantic recognizer later in the pipeline.
 */
final class WorkbookPhysicalFactsBuilder {

    static final int STRUCTURE_VERSION = 6;

    private final ObjectMapper objectMapper;

    WorkbookPhysicalFactsBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void enrich(ObjectNode summary) {
        var layoutSpans = objectMapper.createArrayNode();
        var borderSegments = objectMapper.createArrayNode();
        var rowProfiles = objectMapper.createArrayNode();
        var columnProfiles = objectMapper.createArrayNode();

        for (var sheetNode : summary.withArray("sheets")) {
            if (!(sheetNode instanceof ObjectNode sheet)) continue;
            var sheetCells = sortedCells(sheet.path("candidateCells"));
            var sheetSemanticCells = semanticCells(sheet, sheetCells);
            var sheetLayoutSpans = layoutSpans(sheet, sheetCells);
            var sheetBorderSegments = borderSegments(sheet, sheetCells);
            var sheetRowProfiles = rowProfiles(sheet, sheetCells);
            var sheetColumnProfiles = columnProfiles(sheet, sheetCells);

            sheet.set("semanticCells", sheetSemanticCells);
            sheet.set("layoutSpans", sheetLayoutSpans);
            sheet.set("borderSegments", sheetBorderSegments);
            sheet.set("rowProfiles", sheetRowProfiles);
            sheet.set("columnProfiles", sheetColumnProfiles);
            sheet.put("semanticCellCount", sheetSemanticCells.size());
            sheet.put("layoutSpanCount", sheetLayoutSpans.size());

            appendCopies(layoutSpans, sheetLayoutSpans);
            appendCopies(borderSegments, sheetBorderSegments);
            appendCopies(rowProfiles, sheetRowProfiles);
            appendCopies(columnProfiles, sheetColumnProfiles);
        }

        summary.put("structureVersion", STRUCTURE_VERSION);
        summary.put("parserProtocol", "workbook-physical-facts-v6");
        summary.set("layoutSpans", layoutSpans);
        summary.set("borderSegments", borderSegments);
        summary.set("rowProfiles", rowProfiles);
        summary.set("columnProfiles", columnProfiles);
        summary.set("structureHints", structureHints(summary));
        // Kept as an empty compatibility field so old readers fail closed
        // instead of treating physical components as business regions.
        summary.set("regions", objectMapper.createArrayNode());
        summary.put("regionCount", 0);
    }

    private ArrayNode semanticCells(ObjectNode sheet, List<CellFact> cells) {
        var result = objectMapper.createArrayNode();
        var mergeAnchors = mergeAnchors(sheet.path("mergedRanges"));
        var byPosition = new HashMap<String, JsonNode>();
        for (var cell : cells) byPosition.put(position(cell.source()), cell.source());
        for (var cell : cells) {
            var source = cell.source();
            var formula = source.path("formula").asBoolean(false)
                    || source.path("value").asText("").startsWith("=");
            var mergeAnchor = mergeAnchors.get(source.path("mergedRange").asText(""));
            var hasValue = !source.path("empty").asBoolean(!source.has("value"));
            var mergeAnchorCell = mergeAnchor == null
                    || source.path("address").asText().equalsIgnoreCase(mergeAnchor);
            if (!mergeAnchorCell && !hasValue) continue;
            var inputCandidate = !hasValue && mergeAnchorCell && looksLikeInput(sheet, source, byPosition);
            if (!hasValue && !inputCandidate && mergeAnchor == null) continue;
            var fact = base(sheet, source)
                    .put("factType", formula ? "FORMULA" : hasValue ? "VALUE" : "INPUT_CANDIDATE")
                    .put("inputCandidate", inputCandidate)
                    .put("inputConfidence", inputCandidate ? inputConfidence(sheet, source, byPosition) : 0.0)
                    .put("styleRef", styleRef(source));
            if (inputCandidate) fact.set("inputEvidence", inputEvidence(sheet, source, byPosition));
            if (source.has("value")) fact.set("value", source.path("value").deepCopy());
            if (formula) fact.put("formula", source.path("value").asText(""));
            if (!source.path("valueType").asText("").isBlank()) {
                fact.put("physicalValueType", source.path("valueType").asText());
            }
            var mergedRange = source.path("mergedRange").asText("");
            if (!mergedRange.isBlank()) {
                fact.put("mergedRange", mergedRange);
                fact.put("mergeAnchor", mergeAnchor == null ? source.path("address").asText() : mergeAnchor);
                fact.put("mergeAnchorCell", mergeAnchorCell);
            }
            result.add(fact);
        }
        return result;
    }

    private ArrayNode layoutSpans(ObjectNode sheet, List<CellFact> cells) {
        var result = objectMapper.createArrayNode();
        var byRow = new java.util.TreeMap<Integer, List<CellFact>>();
        cells.stream().filter(cell -> cell.styled()).forEach(cell ->
                byRow.computeIfAbsent(cell.row(), ignored -> new ArrayList<>()).add(cell));
        byRow.forEach((row, rowCells) -> {
            rowCells.sort(Comparator.comparingInt(CellFact::column));
            var start = rowCells.getFirst();
            var previous = start;
            for (int index = 1; index < rowCells.size(); index++) {
                var current = rowCells.get(index);
                if (current.column() != previous.column() + 1
                        || !styleRef(current.source()).equals(styleRef(start.source()))) {
                    result.add(layoutSpan(sheet, start, previous));
                    start = current;
                }
                previous = current;
            }
            result.add(layoutSpan(sheet, start, previous));
        });
        return result;
    }

    private ObjectNode layoutSpan(ObjectNode sheet, CellFact start, CellFact end) {
        var source = start.source();
        var result = objectMapper.createObjectNode()
                .put("sheetId", sheetId(sheet))
                .put("sheetName", sheetName(sheet))
                .put("range", range(start.column(), start.row(), end.column(), end.row()))
                .put("styleRef", styleRef(source))
                .put("cellCount", end.column() - start.column() + 1)
                .put("hasBorder", source.path("hasBorder").asBoolean(false));
        var style = source.path("style");
        result.put("bold", source.path("bold").asBoolean(false));
        result.put("horizontalAlignment", style.path("ht").asText(""));
        result.put("verticalAlignment", style.path("vt").asText(""));
        result.put("fill", style.path("bg").asText(style.path("fill").asText("")));
        result.put("numberFormat", style.path("n").path("pattern").asText(""));
        return result;
    }

    private ArrayNode borderSegments(ObjectNode sheet, List<CellFact> cells) {
        var result = objectMapper.createArrayNode();
        var byRow = new java.util.TreeMap<Integer, List<CellFact>>();
        cells.stream().filter(cell -> cell.source().path("hasBorder").asBoolean(false))
                .forEach(cell -> byRow.computeIfAbsent(cell.row(), ignored -> new ArrayList<>()).add(cell));
        byRow.forEach((row, rowCells) -> {
            rowCells.sort(Comparator.comparingInt(CellFact::column));
            var startColumn = rowCells.getFirst().column();
            var endColumn = startColumn;
            for (int index = 1; index < rowCells.size(); index++) {
                var column = rowCells.get(index).column();
                if (column > endColumn + 1) {
                    result.add(borderSegment(sheet, startColumn, row, endColumn));
                    startColumn = column;
                }
                endColumn = column;
            }
            result.add(borderSegment(sheet, startColumn, row, endColumn));
        });
        return result;
    }

    private ObjectNode borderSegment(ObjectNode sheet, int startColumn, int row, int endColumn) {
        return objectMapper.createObjectNode()
                .put("sheetId", sheetId(sheet))
                .put("sheetName", sheetName(sheet))
                .put("range", range(startColumn, row, endColumn, row))
                .put("orientation", "HORIZONTAL_CELL_BAND");
    }

    private ArrayNode rowProfiles(ObjectNode sheet, List<CellFact> cells) {
        var result = objectMapper.createArrayNode();
        var profiles = new java.util.TreeMap<Integer, Profile>();
        cells.forEach(cell -> profiles.computeIfAbsent(cell.row(), ignored -> new Profile())
                .accept(cell.source()));
        var rowData = sheet.path("rowData");
        profiles.forEach((row, profile) -> {
            var physical = rowData.path(String.valueOf(row - 1));
            result.add(objectMapper.createObjectNode()
                    .put("sheetId", sheetId(sheet)).put("sheetName", sheetName(sheet))
                    .put("row", row).put("height", physical.path("h").asInt(0))
                    .put("hidden", physical.path("hd").asInt(0) > 0)
                    .put("valueCells", profile.valueCells).put("formulaCells", profile.formulaCells)
                    .put("styledBlankCells", profile.styledBlankCells)
                    .put("firstColumn", profile.firstColumn).put("lastColumn", profile.lastColumn));
        });
        return result;
    }

    private ArrayNode columnProfiles(ObjectNode sheet, List<CellFact> cells) {
        var result = objectMapper.createArrayNode();
        var profiles = new java.util.TreeMap<Integer, Profile>();
        cells.forEach(cell -> profiles.computeIfAbsent(cell.column(), ignored -> new Profile())
                .accept(cell.source()));
        var columnData = sheet.path("columnData");
        profiles.forEach((column, profile) -> {
            var physical = columnData.path(String.valueOf(column - 1));
            result.add(objectMapper.createObjectNode()
                    .put("sheetId", sheetId(sheet)).put("sheetName", sheetName(sheet))
                    .put("column", column).put("columnName", columnName(column))
                    .put("width", physical.path("w").asInt(0))
                    .put("hidden", physical.path("hd").asInt(0) > 0)
                    .put("valueCells", profile.valueCells).put("formulaCells", profile.formulaCells)
                    .put("styledBlankCells", profile.styledBlankCells)
                    .put("firstRow", profile.firstRow).put("lastRow", profile.lastRow));
        });
        return result;
    }

    private ArrayNode structureHints(ObjectNode summary) {
        var result = objectMapper.createArrayNode();
        for (var sheet : summary.withArray("sheets")) {
            var usedRange = sheet.path("usedRange").asText("A1");
            result.add(objectMapper.createObjectNode()
                    .put("sheetId", sheetId(sheet))
                    .put("sheetName", sheetName(sheet))
                    .put("range", usedRange)
                    .put("hintType", "SHEET_EXTENT")
                    .put("description", "工作表物理使用范围；不代表业务块边界"));
            if (sheet.path("hidden").asBoolean(false)) {
                result.add(objectMapper.createObjectNode()
                        .put("sheetId", sheetId(sheet)).put("sheetName", sheetName(sheet))
                        .put("range", usedRange).put("hintType", "HIDDEN_SHEET")
                        .put("description", "隐藏工作表；需结合业务上下文判断是否为辅助数据"));
            }
        }
        return result;
    }

    private boolean looksLikeInput(ObjectNode sheet, JsonNode source, Map<String, JsonNode> byPosition) {
        if (!source.path("empty").asBoolean(true) || source.path("formula").asBoolean(false)) return false;
        if (source.path("hasComment").asBoolean(false) || source.path("hasHyperlink").asBoolean(false)) return false;
        if (isHidden(sheet, source)) return false;
        var style = source.path("style");
        var fill = !style.path("bg").asText(style.path("fill").asText("")).isBlank();
        var numberFormat = !style.path("n").path("pattern").asText("").isBlank();
        var border = source.path("hasBorder").asBoolean(false);
        var unlocked = source.path("locked").isBoolean() && !source.path("locked").asBoolean();
        if (!(unlocked || (border && (fill || numberFormat)))) return false;
        return hasValidation(sheet, source) || hasAdjacentLabel(source, byPosition)
                || hasRepeatedDataPattern(source, byPosition);
    }

    private double inputConfidence(ObjectNode sheet, JsonNode source, Map<String, JsonNode> byPosition) {
        var score = 0.38;
        if (source.path("locked").isBoolean() && !source.path("locked").asBoolean()) score += 0.25;
        if (source.path("hasBorder").asBoolean(false)) score += 0.12;
        var style = source.path("style");
        if (!style.path("n").path("pattern").asText("").isBlank()) score += 0.10;
        if (!style.path("bg").asText(style.path("fill").asText("")).isBlank()) score += 0.08;
        if (hasValidation(sheet, source)) score += 0.18;
        if (hasAdjacentLabel(source, byPosition)) score += 0.14;
        if (hasRepeatedDataPattern(source, byPosition)) score += 0.09;
        if (!source.path("mergedRange").asText("").isBlank()) score += 0.04;
        return Math.min(0.99, score);
    }

    private ArrayNode inputEvidence(ObjectNode sheet, JsonNode source, Map<String, JsonNode> byPosition) {
        var evidence = objectMapper.createArrayNode();
        if (source.path("locked").isBoolean() && !source.path("locked").asBoolean()) evidence.add("UNLOCKED");
        if (source.path("hasBorder").asBoolean(false)) evidence.add("BORDERED_INPUT_REGION");
        if (!source.path("style").path("n").path("pattern").asText("").isBlank()) {
            evidence.add("NUMBER_FORMAT");
        }
        if (!source.path("style").path("bg").asText(source.path("style").path("fill").asText(""))
                .isBlank()) evidence.add("FILLED_INPUT_REGION");
        if (source.path("mergedRange").isTextual() && !source.path("mergedRange").asText().isBlank()) {
            evidence.add("MERGED_INPUT_REGION");
        }
        if (hasValidation(sheet, source)) evidence.add("DATA_VALIDATION");
        if (hasAdjacentLabel(source, byPosition)) evidence.add("ADJACENT_LABEL");
        if (hasRepeatedDataPattern(source, byPosition)) evidence.add("REPEATED_DATA_REGION");
        return evidence;
    }

    private boolean hasValidation(ObjectNode sheet, JsonNode source) {
        for (var rule : sheet.path("dataValidationRules")) {
            if (rangesOverlap(source.path("address").asText(""), rule.path("range").asText(""))) return true;
        }
        return false;
    }

    private boolean hasAdjacentLabel(JsonNode source, Map<String, JsonNode> byPosition) {
        var sheetId = source.path("sheetId").asText("");
        var row = source.path("row").asInt();
        var column = source.path("column").asInt();
        return isLabelCell(byPosition.get(position(sheetId, row, column - 1)))
                || isLabelCell(byPosition.get(position(sheetId, row - 1, column)));
    }

    private boolean isLabelCell(JsonNode cell) {
        if (cell == null || cell.path("empty").asBoolean(true) || !cell.path("value").isTextual()) return false;
        var value = cell.path("value").asText("").strip();
        if (value.isBlank() || isStaticText(value) || isUnitText(value)) return false;
        return value.endsWith(":") || value.endsWith("：")
                || (value.length() <= 24 && cell.path("bold").asBoolean(false));
    }

    private boolean hasRepeatedDataPattern(JsonNode source, Map<String, JsonNode> byPosition) {
        var sheetId = source.path("sheetId").asText("");
        var row = source.path("row").asInt();
        var column = source.path("column").asInt();
        for (var delta = -3; delta <= 3; delta++) {
            if (delta == 0) continue;
            var neighbor = byPosition.get(position(sheetId, row + delta, column));
            if (neighbor != null && !neighbor.path("empty").asBoolean(true)) return true;
        }
        var sameRowInputs = 0;
        for (var delta = -3; delta <= 3; delta++) {
            if (delta == 0) continue;
            var neighbor = byPosition.get(position(sheetId, row, column + delta));
            if (neighbor != null && neighbor.path("empty").asBoolean(false)
                    && neighbor.path("hasBorder").asBoolean(false)) sameRowInputs++;
        }
        return sameRowInputs > 0;
    }

    private boolean isHidden(ObjectNode sheet, JsonNode source) {
        var row = source.path("row").asInt() - 1;
        var column = source.path("column").asInt() - 1;
        return sheet.path("rowData").path(String.valueOf(row)).path("hd").asInt(0) > 0
                || sheet.path("columnData").path(String.valueOf(column)).path("hd").asInt(0) > 0;
    }

    private boolean isStaticText(String value) {
        return value.startsWith("注") || value.startsWith("备注") || value.startsWith("注意")
                || value.startsWith("说明") || value.startsWith("提示") || value.startsWith("操作要求");
    }

    private boolean isUnitText(String value) {
        return value.matches("(?i)^(kg|g|mg|吨|t|ml|l|%|‰|个|件|箱|批|天|小时|元)$");
    }

    private String position(JsonNode cell) {
        return position(cell.path("sheetId").asText(""), cell.path("row").asInt(), cell.path("column").asInt());
    }

    private String position(String sheetId, int row, int column) {
        return sheetId + "|" + row + "|" + column;
    }

    private boolean rangesOverlap(String first, String second) {
        var left = rangeBounds(first);
        var right = rangeBounds(second);
        return left != null && right != null && left[0] <= right[2] && right[0] <= left[2]
                && left[1] <= right[3] && right[1] <= left[3];
    }

    private int[] rangeBounds(String address) {
        var matcher = java.util.regex.Pattern.compile(
                "^([A-Z]+)([1-9][0-9]*)(?::([A-Z]+)([1-9][0-9]*))?$"
        ).matcher(address == null ? "" : address.toUpperCase(Locale.ROOT));
        if (!matcher.matches()) return null;
        var firstColumn = columnIndex(matcher.group(1));
        var firstRow = Integer.parseInt(matcher.group(2));
        var lastColumn = matcher.group(3) == null ? firstColumn : columnIndex(matcher.group(3));
        var lastRow = matcher.group(4) == null ? firstRow : Integer.parseInt(matcher.group(4));
        return new int[]{Math.min(firstColumn, lastColumn), Math.min(firstRow, lastRow),
                Math.max(firstColumn, lastColumn), Math.max(lastRow, firstRow)};
    }

    private int columnIndex(String letters) {
        var result = 0;
        for (var letter : letters.toCharArray()) result = result * 26 + letter - 'A' + 1;
        return result;
    }

    private ObjectNode base(ObjectNode sheet, JsonNode source) {
        return objectMapper.createObjectNode()
                .put("sheetId", sheetId(sheet)).put("sheetName", sheetName(sheet))
                .put("address", source.path("address").asText())
                .put("row", source.path("row").asInt())
                .put("column", source.path("column").asInt());
    }

    private Map<String, String> mergeAnchors(JsonNode ranges) {
        var result = new HashMap<String, String>();
        for (var range : ranges) {
            var address = range.path("range").asText(range.path("address").asText(""));
            var anchor = range.path("anchor").asText(range.path("anchorAddress").asText(""));
            if (anchor.isBlank() && !address.isBlank()) anchor = address.split(":", 2)[0];
            if (!address.isBlank()) result.put(address, anchor);
        }
        return result;
    }

    private List<CellFact> sortedCells(JsonNode source) {
        var result = new ArrayList<CellFact>();
        for (var cell : source) {
            result.add(new CellFact(
                    cell.path("row").asInt(), cell.path("column").asInt(),
                    cell.path("style").isObject() || cell.path("hasBorder").asBoolean(false), cell
            ));
        }
        result.sort(Comparator.comparingInt(CellFact::row).thenComparingInt(CellFact::column));
        return result;
    }

    private String styleRef(JsonNode source) {
        var material = source.path("style").toString() + "|"
                + source.path("bold").asBoolean(false) + "|"
                + source.path("hasBorder").asBoolean(false);
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成工作簿样式标识", exception);
        }
    }

    private void appendCopies(ArrayNode target, ArrayNode source) {
        for (var value : source) target.add(value.deepCopy());
    }

    private String sheetId(JsonNode sheet) {
        return sheet.path("id").asText(sheet.path("sheetId").asText());
    }

    private String sheetName(JsonNode sheet) {
        return sheet.path("name").asText(sheet.path("sheetName").asText());
    }

    private String range(int startColumn, int startRow, int endColumn, int endRow) {
        var start = columnName(startColumn) + startRow;
        var end = columnName(endColumn) + endRow;
        return start.equals(end) ? start : start + ":" + end;
    }

    private String columnName(int column) {
        var value = Math.max(1, column);
        var result = new StringBuilder();
        while (value > 0) {
            value--;
            result.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }
        return result.toString().toUpperCase(Locale.ROOT);
    }

    private record CellFact(int row, int column, boolean styled, JsonNode source) {
    }

    private static final class Profile {
        private int valueCells;
        private int formulaCells;
        private int styledBlankCells;
        private int firstRow = Integer.MAX_VALUE;
        private int lastRow;
        private int firstColumn = Integer.MAX_VALUE;
        private int lastColumn;

        private void accept(JsonNode source) {
            var empty = source.path("empty").asBoolean(!source.has("value"));
            var formula = source.path("formula").asBoolean(false)
                    || source.path("value").asText("").startsWith("=");
            if (formula) formulaCells++;
            else if (!empty) valueCells++;
            else styledBlankCells++;
            firstRow = Math.min(firstRow, source.path("row").asInt());
            lastRow = Math.max(lastRow, source.path("row").asInt());
            firstColumn = Math.min(firstColumn, source.path("column").asInt());
            lastColumn = Math.max(lastColumn, source.path("column").asInt());
        }
    }
}
