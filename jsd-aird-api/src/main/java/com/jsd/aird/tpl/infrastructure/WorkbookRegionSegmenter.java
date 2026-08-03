package com.jsd.aird.tpl.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds stable, style-aware business regions shared by XLSX import and snapshot re-recognition. */
final class WorkbookRegionSegmenter {

    static final int STRUCTURE_VERSION = 5;
    private static final int MAX_MODEL_CELLS = 160;
    private static final int MODEL_ROW_WINDOW = 10;
    private static final int MODEL_COLUMN_WINDOW = 16;

    private final ObjectMapper objectMapper;

    WorkbookRegionSegmenter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void enrich(ObjectNode summary) {
        var allRegions = objectMapper.createArrayNode();
        for (var sheet : summary.withArray("sheets")) {
            if (!(sheet instanceof ObjectNode sheetObject)) continue;
            var cells = cells(sheet.path("candidateCells"));
            var regions = segment(sheetObject, cells);
            sheetObject.set("regions", regions);
            for (var region : regions) allRegions.add(region.deepCopy());
        }
        summary.put("structureVersion", STRUCTURE_VERSION);
        summary.set("regions", allRegions);
        summary.put("regionCount", allRegions.size());
    }

    private ArrayNode segment(ObjectNode sheet, List<Cell> cells) {
        var result = objectMapper.createArrayNode();
        if (cells.isEmpty()) return result;
        var byPoint = new HashMap<String, Cell>();
        cells.forEach(cell -> byPoint.put(cell.row + ":" + cell.column, cell));
        var visited = new HashSet<String>();
        var components = new ArrayList<List<Cell>>();
        for (var seed : cells) {
            if (!visited.add(seed.point())) continue;
            var component = new ArrayList<Cell>();
            var queue = new ArrayDeque<Cell>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                var current = queue.removeFirst();
                component.add(current);
                for (var neighbor : neighbors(current, byPoint)) {
                    if (connected(current, neighbor) && visited.add(neighbor.point())) queue.add(neighbor);
                }
            }
            components.add(component);
        }

        components.sort(Comparator.comparingInt((List<Cell> component) -> bounds(component).startRow)
                .thenComparingInt(component -> bounds(component).startColumn));
        for (var component : components) appendRegionTree(result, sheet, component);
        return result;
    }

    private void appendRegionTree(ArrayNode target, ObjectNode sheet, List<Cell> component) {
        var sectionBands = splitAtSectionBoundaries(component);
        if (sectionBands.size() > 1) {
            sectionBands.forEach(section -> appendRegionTree(target, sheet, section));
            return;
        }
        var parent = region(sheet, component, null, false);
        target.add(parent);
        if (component.size() <= MAX_MODEL_CELLS) return;
        parent.put("requiresModel", false);
        parent.put("hasChildren", true);
        var bounds = bounds(component);
        for (int row = bounds.startRow; row <= bounds.endRow; row += MODEL_ROW_WINDOW) {
            for (int column = bounds.startColumn; column <= bounds.endColumn; column += MODEL_COLUMN_WINDOW) {
                var startRow = row;
                var startColumn = column;
                var endRow = Math.min(bounds.endRow, startRow + MODEL_ROW_WINDOW - 1);
                var endColumn = Math.min(bounds.endColumn, startColumn + MODEL_COLUMN_WINDOW - 1);
                var child = component.stream()
                        .filter(cell -> cell.row >= startRow && cell.row <= endRow
                                && cell.column >= startColumn && cell.column <= endColumn)
                        .toList();
                if (!child.isEmpty()) {
                    var childRegion = region(sheet, child, parent.path("regionId").asText(), true);
                    childRegion.put("parentAddress", parent.path("address").asText());
                    childRegion.set("parentHeaderBands", parent.path("headerBands").deepCopy());
                    childRegion.set("parentMergedRanges", parent.path("mergedRanges").deepCopy());
                    target.add(childRegion);
                }
            }
        }
    }

    private List<List<Cell>> splitAtSectionBoundaries(List<Cell> component) {
        var bounds = bounds(component);
        var starts = new java.util.TreeSet<Integer>();
        starts.add(bounds.startRow);
        for (var cell : component) {
            var merge = parseRange(cell.mergedRange);
            if (merge == null || merge.startColumn != bounds.startColumn) continue;
            var height = merge.endRow - merge.startRow + 1;
            var width = merge.endColumn - merge.startColumn + 1;
            var componentWidth = bounds.endColumn - bounds.startColumn + 1;
            if (height >= 2 || width >= Math.max(2, componentWidth / 2)) starts.add(merge.startRow);
        }
        if (starts.size() <= 1) return List.of(component);
        var boundaries = new ArrayList<>(starts);
        var result = new ArrayList<List<Cell>>();
        for (int index = 0; index < boundaries.size(); index++) {
            var start = boundaries.get(index);
            var end = index + 1 < boundaries.size() ? boundaries.get(index + 1) - 1 : bounds.endRow;
            var band = component.stream().filter(cell -> cell.row >= start && cell.row <= end).toList();
            if (!band.isEmpty()) result.add(band);
        }
        return result.size() <= 1 ? List.of(component) : result;
    }

    private ObjectNode region(ObjectNode sheet, List<Cell> cells, String parentRegionId, boolean analysisChild) {
        var bounds = bounds(cells);
        var address = a1(bounds);
        var sheetId = sheet.path("id").asText(sheet.path("sheetId").asText());
        var sheetName = sheet.path("name").asText(sheet.path("sheetName").asText());
        var regionId = stableId(sheetId + "|" + address + "|" + STRUCTURE_VERSION);
        var type = classify(cells, bounds);
        var scores = scores(cells, bounds, type);
        var confidence = scores.path("total").asDouble();
        var candidateCells = objectMapper.createArrayNode();
        if (analysisChild || cells.size() <= MAX_MODEL_CELLS) {
            cells.stream().sorted(Comparator.comparingInt((Cell cell) -> cell.row)
                            .thenComparingInt(cell -> cell.column))
                    .forEach(cell -> candidateCells.add(compactCandidate(cell)));
        }
        var result = objectMapper.createObjectNode()
                .put("regionId", regionId)
                .put("sheetId", sheetId)
                .put("sheetName", sheetName)
                .put("address", address)
                .put("startRow", bounds.startRow)
                .put("endRow", bounds.endRow)
                .put("startColumn", bounds.startColumn)
                .put("endColumn", bounds.endColumn)
                .put("cellCount", cells.size())
                .put("valueCellCount", cells.stream().filter(cell -> !cell.empty).count())
                .put("styledBlankCount", cells.stream().filter(cell -> cell.empty && cell.styled).count())
                .put("kindCandidate", type)
                .put("classificationConfidence", confidence)
                .put("requiresModel", analysisChild || "MATRIX".equals(type)
                        || "ROW_TABLE".equals(type) || confidence < 0.85)
                .put("analysisChild", analysisChild);
        if (parentRegionId != null) result.put("parentRegionId", parentRegionId);
        result.set("scores", scores);
        result.set("styleProfile", styleProfile(cells));
        result.set("candidateCells", candidateCells);
        result.set("mergedRanges", mergedRanges(cells));
        result.set("visualSpans", visualSpans(cells));
        result.set("headerBands", headerBands(cells, bounds));
        result.set("inputBands", inputBands(cells));
        return result;
    }

    private ObjectNode compactCandidate(Cell cell) {
        var result = objectMapper.createObjectNode()
                .put("sheetId", cell.source.path("sheetId").asText())
                .put("sheetName", cell.source.path("sheetName").asText())
                .put("address", cell.source.path("address").asText())
                .put("row", cell.row).put("column", cell.column)
                .put("empty", cell.empty).put("bold", cell.bold).put("hasBorder", cell.hasBorder);
        if (cell.source.has("value")) result.set("value", cell.source.path("value").deepCopy());
        if (!cell.mergedRange.isBlank()) result.put("mergedRange", cell.mergedRange);
        var numberFormat = cell.source.path("style").path("n").path("pattern").asText("");
        if (!numberFormat.isBlank()) {
            result.set("style", objectMapper.createObjectNode()
                    .set("n", objectMapper.createObjectNode().put("pattern", numberFormat)));
        }
        return result;
    }

    private List<Cell> neighbors(Cell cell, Map<String, Cell> byPoint) {
        var result = new ArrayList<Cell>(4);
        add(result, byPoint.get(cell.row + ":" + (cell.column - 1)));
        add(result, byPoint.get(cell.row + ":" + (cell.column + 1)));
        add(result, byPoint.get((cell.row - 1) + ":" + cell.column));
        add(result, byPoint.get((cell.row + 1) + ":" + cell.column));
        return result;
    }

    private void add(List<Cell> target, Cell value) {
        if (value != null) target.add(value);
    }

    private boolean connected(Cell left, Cell right) {
        if (!left.mergedRange.isBlank() && left.mergedRange.equals(right.mergedRange)) return true;
        var horizontal = left.row == right.row;
        if (touchingBorder(left, right, horizontal)) return true;
        if (!left.fill.isBlank() && left.fill.equals(right.fill)) return true;
        if (left.styleSignature.equals(right.styleSignature) && left.styled && right.styled) return true;
        if (horizontal && ((!left.empty && right.empty && right.styled)
                || (!right.empty && left.empty && left.styled))) return true;
        return !horizontal && left.hasBorder && right.hasBorder && left.column == right.column;
    }

    private boolean touchingBorder(Cell left, Cell right, boolean horizontal) {
        if (horizontal) {
            var first = left.column < right.column ? left : right;
            var second = first == left ? right : left;
            return first.borderRight > 0 && second.borderLeft > 0;
        }
        var first = left.row < right.row ? left : right;
        var second = first == left ? right : left;
        return first.borderBottom > 0 && second.borderTop > 0;
    }

    private String classify(List<Cell> cells, Bounds bounds) {
        var width = bounds.endColumn - bounds.startColumn + 1;
        var height = bounds.endRow - bounds.startRow + 1;
        var firstRowTexts = cells.stream().filter(cell -> cell.row == bounds.startRow && cell.text).count();
        var firstColumnTexts = cells.stream().filter(cell -> cell.column == bounds.startColumn && cell.text).count();
        var styledBlanks = cells.stream().filter(cell -> cell.empty && cell.styled).count();
        var merged = cells.stream().filter(cell -> !cell.mergedRange.isBlank()).count();
        if (width >= 3 && height >= 3 && firstRowTexts >= 2 && firstColumnTexts >= 2
                && styledBlanks >= Math.max(2, width - 1)) return "MATRIX";
        if (width >= 3 && height >= 2 && firstRowTexts >= 3) return "ROW_TABLE";
        if (height <= 2 && merged > 0 && cells.stream().anyMatch(cell -> cell.bold && cell.text)) {
            return "SECTION_TITLE";
        }
        if (cells.stream().anyMatch(cell -> cell.text)
                && cells.stream().anyMatch(cell -> cell.empty && cell.styled)) return "FIELD_GROUP";
        return cells.stream().anyMatch(cell -> cell.text) ? "NOTE" : "INPUT_AREA";
    }

    private ObjectNode scores(List<Cell> cells, Bounds bounds, String type) {
        var area = Math.max(1, (bounds.endRow - bounds.startRow + 1)
                * (bounds.endColumn - bounds.startColumn + 1));
        var border = borderContinuity(cells);
        var repetition = styleRepetition(cells);
        var header = clamp((cells.stream().filter(cell -> cell.bold || !cell.mergedRange.isBlank()).count()
                / (double) Math.max(1, cells.size())) * 2);
        var distribution = clamp(cells.size() / (double) area);
        var spatial = clamp(cells.stream().filter(cell -> cell.empty && cell.styled).count()
                / (double) Math.max(1, cells.size()) * 2);
        var semantic = switch (type) {
            case "MATRIX", "ROW_TABLE", "FIELD_GROUP" -> 0.9;
            case "SECTION_TITLE" -> 0.8;
            default -> 0.55;
        };
        var total = border * 0.25 + repetition * 0.20 + header * 0.20
                + distribution * 0.15 + spatial * 0.10 + semantic * 0.10;
        return objectMapper.createObjectNode()
                .put("borderContinuity", round(border))
                .put("styleRepetition", round(repetition))
                .put("headerHierarchy", round(header))
                .put("valueDistribution", round(distribution))
                .put("labelInputAdjacency", round(spatial))
                .put("businessSemantics", round(semantic))
                .put("total", round(total));
    }

    private double styleRepetition(List<Cell> cells) {
        var counts = new HashMap<String, Integer>();
        for (var cell : cells) if (cell.styled) counts.merge(cell.styleSignature, 1, Integer::sum);
        var maximum = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return clamp(maximum / (double) Math.max(1, cells.size()));
    }

    private double borderContinuity(List<Cell> cells) {
        var byPoint = new HashMap<String, Cell>();
        cells.forEach(cell -> byPoint.put(cell.point(), cell));
        var connected = 0;
        var possible = 0;
        for (var cell : cells) {
            var right = byPoint.get(cell.row + ":" + (cell.column + 1));
            var below = byPoint.get((cell.row + 1) + ":" + cell.column);
            if (right != null) {
                possible++;
                if (touchingBorder(cell, right, true)) connected++;
            }
            if (below != null) {
                possible++;
                if (touchingBorder(cell, below, false)) connected++;
            }
        }
        return possible == 0 ? (cells.stream().anyMatch(cell -> cell.hasBorder) ? 0.5 : 0)
                : clamp(connected / (double) possible);
    }

    private ObjectNode styleProfile(List<Cell> cells) {
        return objectMapper.createObjectNode()
                .put("bordered", cells.stream().filter(cell -> cell.hasBorder).count())
                .put("bold", cells.stream().filter(cell -> cell.bold).count())
                .put("filled", cells.stream().filter(cell -> !cell.fill.isBlank()).count())
                .put("merged", cells.stream().filter(cell -> !cell.mergedRange.isBlank()).count())
                .put("numeric", cells.stream().filter(cell -> cell.numeric).count())
                .put("text", cells.stream().filter(cell -> cell.text).count());
    }

    private ArrayNode mergedRanges(List<Cell> cells) {
        var result = objectMapper.createArrayNode();
        cells.stream().map(cell -> cell.mergedRange).filter(value -> !value.isBlank()).distinct()
                .sorted().forEach(result::add);
        return result;
    }

    /**
     * Adjacent cells can visually form one business area without being physically merged.
     * These spans are evidence for the semantic recognizer; they never mutate mergeData.
     */
    private ArrayNode visualSpans(List<Cell> cells) {
        var result = objectMapper.createArrayNode();
        var rows = new LinkedHashMap<Integer, List<Cell>>();
        cells.forEach(cell -> rows.computeIfAbsent(cell.row, ignored -> new ArrayList<>()).add(cell));
        rows.forEach((row, rowCells) -> {
            rowCells.sort(Comparator.comparingInt(cell -> cell.column));
            var index = 0;
            while (index < rowCells.size()) {
                var first = rowCells.get(index);
                var last = first;
                var nextIndex = index + 1;
                while (nextIndex < rowCells.size()) {
                    var next = rowCells.get(nextIndex);
                    if (next.column != last.column + 1 || !visuallyContinuous(last, next)) break;
                    last = next;
                    nextIndex++;
                }
                if (last.column > first.column && first.mergedRange.isBlank() && last.mergedRange.isBlank()) {
                    var valued = rowCells.subList(index, nextIndex).stream().filter(cell -> !cell.empty).count();
                    result.add(objectMapper.createObjectNode()
                            .put("address", a1(new Bounds(row, row, first.column, last.column)))
                            .put("anchorAddress", columnName(first.column) + row)
                            .put("cellCount", last.column - first.column + 1)
                            .put("valueCellCount", valued)
                            .put("reason", "相邻单元格样式连续且不存在可见内边界"));
                }
                index = nextIndex;
            }
        });
        return result;
    }

    private boolean visuallyContinuous(Cell left, Cell right) {
        if (!left.mergedRange.isBlank() || !right.mergedRange.isBlank()) return false;
        var noInternalBorder = left.borderRight == 0 && right.borderLeft == 0;
        var sameStyle = left.styleSignature.equals(right.styleSignature)
                || (!left.fill.isBlank() && left.fill.equals(right.fill));
        var labelFollowedByBlank = !left.empty && right.empty && right.styled;
        return noInternalBorder && (sameStyle || labelFollowedByBlank);
    }

    private ArrayNode headerBands(List<Cell> cells, Bounds bounds) {
        var result = objectMapper.createArrayNode();
        var byRow = new LinkedHashMap<Integer, List<Cell>>();
        cells.forEach(cell -> byRow.computeIfAbsent(cell.row, ignored -> new ArrayList<>()).add(cell));
        for (var entry : byRow.entrySet()) {
            if (entry.getKey() > bounds.startRow + 3) break;
            var text = entry.getValue().stream().filter(cell -> cell.text).count();
            if (text >= 2 || entry.getValue().stream().anyMatch(cell -> cell.bold)) {
                result.add(a1(new Bounds(
                        entry.getKey(), entry.getKey(),
                        entry.getValue().stream().mapToInt(cell -> cell.column).min().orElse(bounds.startColumn),
                        entry.getValue().stream().mapToInt(cell -> cell.column).max().orElse(bounds.endColumn)
                )));
            }
        }
        return result;
    }

    private ArrayNode inputBands(List<Cell> cells) {
        var result = objectMapper.createArrayNode();
        var rows = new LinkedHashMap<Integer, List<Cell>>();
        cells.stream().filter(cell -> cell.empty && cell.styled)
                .forEach(cell -> rows.computeIfAbsent(cell.row, ignored -> new ArrayList<>()).add(cell));
        rows.forEach((row, rowCells) -> {
            rowCells.sort(Comparator.comparingInt(cell -> cell.column));
            var start = rowCells.getFirst().column;
            var end = start;
            for (int index = 1; index < rowCells.size(); index++) {
                var column = rowCells.get(index).column;
                if (column > end + 1) {
                    result.add(a1(new Bounds(row, row, start, end)));
                    start = column;
                }
                end = column;
            }
            result.add(a1(new Bounds(row, row, start, end)));
        });
        return result;
    }

    private List<Cell> cells(JsonNode source) {
        var result = new ArrayList<Cell>();
        if (!source.isArray()) return result;
        for (var node : source) {
            var row = node.path("row").asInt();
            var column = node.path("column").asInt();
            if (row <= 0 || column <= 0) continue;
            var style = node.path("style");
            var borders = style.path("bd");
            var fill = style.path("bg").path("rgb").asText("");
            var hasValue = node.has("value") && !node.path("value").asText("").isBlank();
            result.add(new Cell(
                    node, row, column, !hasValue, style.isObject() && !style.isEmpty(),
                    hasValue && node.path("value").isTextual(), hasValue && node.path("value").isNumber(),
                    node.path("bold").asBoolean(style.path("bl").asInt() > 0),
                    node.path("hasBorder").asBoolean(borders.isObject() && !borders.isEmpty()),
                    border(borders.path("t")), border(borders.path("r")),
                    border(borders.path("b")), border(borders.path("l")), fill,
                    style.toString(), node.path("mergedRange").asText("").toUpperCase(Locale.ROOT)
            ));
        }
        return result;
    }

    private int border(JsonNode node) {
        return node.path("s").asInt(0);
    }

    private Bounds parseRange(String value) {
        if (value == null || value.isBlank()) return null;
        var parts = value.toUpperCase(Locale.ROOT).split(":", 2);
        var start = point(parts[0]);
        var end = point(parts.length == 2 ? parts[1] : parts[0]);
        return start == null || end == null ? null : new Bounds(
                Math.min(start[0], end[0]), Math.max(start[0], end[0]),
                Math.min(start[1], end[1]), Math.max(start[1], end[1])
        );
    }

    private int[] point(String value) {
        var matcher = java.util.regex.Pattern.compile("^([A-Z]{1,4})([1-9][0-9]*)$").matcher(value);
        if (!matcher.matches()) return null;
        var column = 0;
        for (var letter : matcher.group(1).toCharArray()) column = column * 26 + letter - 'A' + 1;
        return new int[]{Integer.parseInt(matcher.group(2)), column};
    }

    private Bounds bounds(List<Cell> cells) {
        return new Bounds(
                cells.stream().mapToInt(cell -> cell.row).min().orElse(1),
                cells.stream().mapToInt(cell -> cell.row).max().orElse(1),
                cells.stream().mapToInt(cell -> cell.column).min().orElse(1),
                cells.stream().mapToInt(cell -> cell.column).max().orElse(1)
        );
    }

    private String a1(Bounds bounds) {
        var start = columnName(bounds.startColumn) + bounds.startRow;
        var end = columnName(bounds.endColumn) + bounds.endRow;
        return start.equals(end) ? start : start + ":" + end;
    }

    private String columnName(int column) {
        var value = column;
        var result = new StringBuilder();
        while (value > 0) {
            value--;
            result.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }
        return result.toString();
    }

    private String stableId(String value) {
        try {
            var bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder("region-");
            for (int index = 0; index < 8; index++) result.append(String.format("%02x", bytes[index]));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot calculate region id", exception);
        }
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private record Cell(
            JsonNode source, int row, int column, boolean empty, boolean styled,
            boolean text, boolean numeric, boolean bold, boolean hasBorder,
            int borderTop, int borderRight, int borderBottom, int borderLeft,
            String fill, String styleSignature, String mergedRange
    ) {
        String point() { return row + ":" + column; }
    }

    private record Bounds(int startRow, int endRow, int startColumn, int endColumn) {
    }
}
