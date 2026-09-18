package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** Builds formula-matrix candidates from workbook geometry; it never assigns business data. */
public final class ExperimentMatrixCandidateDetector {

    private final ObjectMapper objectMapper;

    public ExperimentMatrixCandidateDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ArrayNode detect(JsonNode region, JsonNode facts) {
        var result = objectMapper.createArrayNode();
        if (!"COLUMN_TABLE".equals(region.path("type").asText(""))) return result;
        var sheetId = region.path("sheetId").asText("");
        var regionBounds = bounds(region.path("range").asText(""));
        if (sheetId.isBlank() || regionBounds == null) return result;
        var cells = cells(facts, sheetId);
        for (var group : cells) {
            var groupBounds = bounds(cellRange(group));
            var groupName = text(group);
            if (groupBounds == null || !containsFormulaGroup(groupName)
                    || !contains(regionBounds, groupBounds) || groupBounds[3] - groupBounds[1] < 2) continue;

            var identity = identityField(region);
            if (identity == null) identity = identityLabel(cells, groupBounds, regionBounds);
            if (identity == null) continue;
            var identityBounds = bounds(firstText(identity,
                    "labelRange", "locator.labelRange", "locator.labelAddress"));
            if (identityBounds == null) identityBounds = bounds(cellRange(identity));
            if (identityBounds == null) continue;
            var identityValueBounds = bounds(firstText(identity,
                    "valueRange", "locator.valueRange", "locator.address", "locator.range"));
            var identityRow = identityBounds[1];
            var labelColumn = identityBounds[2];
            var firstRecordColumn = identityValueBounds == null ? labelColumn + 1 : identityValueBounds[0];
            var recordColumnLimit = identityValueBounds == null
                    ? regionBounds[2] : Math.min(identityValueBounds[2], regionBounds[2]);

            var labels = new ArrayList<JsonNode>();
            var totalRow = -1;
            for (int row = identityRow + 1; row <= Math.min(groupBounds[3], regionBounds[3]); row++) {
                var label = cellAt(cells, labelColumn, row);
                var labelText = text(label);
                if (isTotal(labelText)) {
                    totalRow = row;
                    break;
                }
                if (!labelText.isBlank()) labels.add(label);
            }
            int firstRow;
            int lastRow;
            if (totalRow > identityRow + 1) {
                // A reusable blank template may intentionally clear dynamic
                // material names. The fixed formula section, identity row,
                // total row and real editable value surface still provide a
                // deterministic range. Items are created only at import time
                // when a material label is actually present.
                firstRow = identityRow + 1;
                lastRow = totalRow - 1;
            } else {
                if (labels.size() < 2) continue;
                labels.sort(Comparator.comparingInt(cell -> bounds(cellRange(cell))[1]));
                firstRow = bounds(cellRange(labels.get(0)))[1];
                lastRow = bounds(cellRange(labels.get(labels.size() - 1)))[3];
            }
            var lastRecordColumn = lastMatrixRecordColumn(
                    cells, firstRecordColumn, recordColumnLimit, firstRow, lastRow);
            if (lastRecordColumn < firstRecordColumn) {
                lastRecordColumn = lastRecordColumn(cells, identityRow, firstRecordColumn, recordColumnLimit);
            }
            if (lastRecordColumn - firstRecordColumn + 1 < 2) continue;
            if (lastRow < firstRow || !hasValueSurface(cells, firstRecordColumn, lastRecordColumn, firstRow, lastRow)) {
                continue;
            }

            var labelRange = address(labelColumn, firstRow, labelColumn, lastRow);
            var valueRange = address(firstRecordColumn, firstRow, lastRecordColumn, lastRow);
            var identityValueRange = address(firstRecordColumn, identityRow, lastRecordColumn, identityRow);
            var candidateRef = "matrix-" + RecognitionIdentity.shortHash(
                    sheetId + "|" + region.path("range").asText("") + "|" + labelRange + "|" + valueRange, 16);
            var candidate = objectMapper.createObjectNode()
                    .put("candidateRef", candidateRef)
                    .put("componentId", region.path("regionId").asText(region.path("blockId").asText("")))
                    .put("sheetId", sheetId)
                    .put("groupName", groupName)
                    .put("recordAxis", "COLUMN")
                    .put("itemAxis", "ROW")
                    .put("labelRange", labelRange)
                    .put("valueRange", valueRange)
                    .put("identityLabelRange", address(
                            identityBounds[0], identityBounds[1], identityBounds[2], identityBounds[3]))
                    .put("identityValueRange", identityValueRange)
                    .put("labelSemantic", "MATERIAL_NAME")
                    .put("valueSemantic", "RATIO")
                    .put("geometryConfidence", 0.98d);
            if (totalRow > 0) {
                candidate.put("totalRange", address(labelColumn, totalRow, lastRecordColumn, totalRow));
            }
            result.add(candidate);
        }
        return result;
    }

    private JsonNode identityLabel(List<JsonNode> cells, int[] group, int[] region) {
        JsonNode selected = null;
        var selectedColumn = -1;
        for (var cell : cells) {
            var cellBounds = bounds(cellRange(cell));
            if (cellBounds == null || cellBounds[1] < group[1] || cellBounds[1] > group[3]
                    || cellBounds[0] <= group[2] || cellBounds[2] >= region[2]) continue;
            var value = normalize(text(cell));
            if (!(value.contains("实验编号") || value.contains("试验编号") || value.contains("样品编号")
                    || value.contains("配方编号") || value.contains("批次编号"))) continue;
            if (cellBounds[2] > selectedColumn) {
                selected = cell;
                selectedColumn = cellBounds[2];
            }
        }
        return selected;
    }

    private JsonNode identityField(JsonNode region) {
        for (var field : region.path("fieldCandidates")) {
            var name = normalize(field.path("fieldName").asText(
                    field.path("name").asText(field.path("labelPath").asText(""))));
            if (name.contains("实验编号") || name.contains("试验编号") || name.contains("样品编号")
                    || name.contains("配方编号") || name.contains("批次编号")) return field;
        }
        return null;
    }

    private int lastMatrixRecordColumn(
            List<JsonNode> cells, int start, int limit, int firstRow, int lastRow
    ) {
        var last = start - 1;
        for (var cell : cells) {
            var cellBounds = bounds(cellRange(cell));
            if (cellBounds == null || cellBounds[2] < start || cellBounds[0] > limit
                    || cellBounds[3] < firstRow || cellBounds[1] > lastRow) continue;
            if (!text(cell).isBlank() || surface(cell)) last = Math.max(last, Math.min(limit, cellBounds[2]));
        }
        return last;
    }

    private int lastRecordColumn(List<JsonNode> cells, int row, int start, int regionEnd) {
        var last = start - 1;
        for (int column = start; column <= regionEnd; column++) {
            var cell = cellAt(cells, column, row);
            if (cell != null && (!text(cell).isBlank() || surface(cell))) last = column;
            else if (last >= start) break;
        }
        return last;
    }

    private boolean hasValueSurface(List<JsonNode> cells, int firstColumn, int lastColumn, int firstRow, int lastRow) {
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                var cell = cellAt(cells, column, row);
                if (cell != null && (!text(cell).isBlank() || surface(cell))) return true;
            }
        }
        return false;
    }

    private boolean surface(JsonNode cell) {
        return cell.path("inputCandidate").asBoolean(false)
                || cell.path("hasBorder").asBoolean(false)
                || cell.path("bordered").asBoolean(false);
    }

    private JsonNode cellAt(List<JsonNode> cells, int column, int row) {
        for (var cell : cells) {
            var cellBounds = bounds(cellRange(cell));
            if (cellBounds != null && cellBounds[0] <= column && column <= cellBounds[2]
                    && cellBounds[1] <= row && row <= cellBounds[3]) return cell;
        }
        return null;
    }

    private List<JsonNode> cells(JsonNode facts, String sheetId) {
        var result = new ArrayList<JsonNode>();
        for (var sheet : facts.path("sheets")) {
            if (!sheetId.equals(sheet.path("id").asText(sheet.path("sheetId").asText("")))) continue;
            sheet.path("semanticCells").forEach(result::add);
            if (result.isEmpty()) sheet.path("candidateCells").forEach(result::add);
        }
        if (result.isEmpty()) facts.path("semanticCells").forEach(result::add);
        // The real XLSX parser stores deterministic workbook cells in the
        // top-level candidateCells array.  Older recognition fixtures used
        // semanticCells, so support both representations without changing
        // the immutable geometry or asking the model to invent ranges.
        if (result.isEmpty()) {
            for (var cell : facts.path("candidateCells")) {
                if (sheetId.equals(cell.path("sheetId").asText(""))) result.add(cell);
            }
        }
        return result;
    }

    private String text(JsonNode cell) {
        if (cell == null) return "";
        return cell.path("value").asText(cell.path("displayValue").asText(cell.path("text").asText(""))).strip();
    }

    private String cellRange(JsonNode cell) {
        if (cell == null) return "";
        return cell.path("mergedRange").asText(cell.path("range").asText(
                cell.path("address").asText(cell.path("labelRange").asText(""))));
    }

    private String firstText(JsonNode node, String... paths) {
        if (node == null) return "";
        for (var path : paths) {
            JsonNode current = node;
            for (var segment : path.split("\\.")) current = current.path(segment);
            var value = current.asText("").strip();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private boolean containsFormulaGroup(String value) {
        var normalized = normalize(value);
        return normalized.contains("实验配方") || normalized.contains("配方明细")
                || normalized.equals("配方") || normalized.contains("原料信息");
    }

    private boolean isTotal(String value) {
        var normalized = normalize(value);
        return normalized.equals("合计") || normalized.equals("总计") || normalized.equals("总量");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s　:：]+", "");
    }

    private int[] bounds(String value) {
        if (value == null || value.isBlank()) return null;
        var parts = value.replace("$", "").toUpperCase(Locale.ROOT).split(":", 2);
        var first = cell(parts[0]);
        var last = cell(parts.length == 1 ? parts[0] : parts[1]);
        if (first == null || last == null) return null;
        return new int[]{Math.min(first[0], last[0]), Math.min(first[1], last[1]),
                Math.max(first[0], last[0]), Math.max(first[1], last[1])};
    }

    private int[] cell(String value) {
        var matcher = java.util.regex.Pattern.compile("^([A-Z]+)([1-9][0-9]*)$").matcher(value);
        if (!matcher.matches()) return null;
        var column = 0;
        for (var letter : matcher.group(1).toCharArray()) column = column * 26 + letter - 'A' + 1;
        return new int[]{column, Integer.parseInt(matcher.group(2))};
    }

    private boolean contains(int[] outer, int[] inner) {
        return outer[0] <= inner[0] && outer[1] <= inner[1]
                && outer[2] >= inner[2] && outer[3] >= inner[3];
    }

    private String address(int startColumn, int startRow, int endColumn, int endRow) {
        var first = columnName(startColumn) + startRow;
        var last = columnName(endColumn) + endRow;
        return first.equals(last) ? first : first + ":" + last;
    }

    private String columnName(int column) {
        var result = new StringBuilder();
        var current = column;
        while (current > 0) {
            current--;
            result.insert(0, (char) ('A' + current % 26));
            current /= 26;
        }
        return result.toString();
    }
}
