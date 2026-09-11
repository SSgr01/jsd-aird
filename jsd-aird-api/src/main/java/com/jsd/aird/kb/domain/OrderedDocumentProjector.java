package com.jsd.aird.kb.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Projects neutral parser blocks into ordered paragraphs and tables without
 * persistence or editor-specific state. Knowledge ingestion and business
 * imports can therefore share exactly the same block ordering and cell model.
 */
public class OrderedDocumentProjector {
    public Projection project(List<DocumentParser.TextBlock> source) {
        var sections = new ArrayList<Section>();
        var issues = new LinkedHashSet<String>();
        var blocks = normalizeBlocks(source);
        TableBuilder table = null;
        for (var block : blocks) {
            var group = tableGroup(block);
            if (group != null) {
                if (table == null || !table.group.equals(group)) {
                    if (table != null) sections.add(table.build(issues));
                    table = new TableBuilder(group);
                }
                table.add(block);
            } else {
                if (table != null) {
                    sections.add(table.build(issues));
                    table = null;
                }
                sections.add(new Paragraph(block.content(), block.section(), block.confidence()));
            }
        }
        if (table != null) sections.add(table.build(issues));
        var tables = sections.stream().filter(Table.class::isInstance).map(Table.class::cast).toList();
        var score = tables.isEmpty() ? 0d : tables.stream().mapToDouble(Table::structureScore).average().orElse(0d);
        return new Projection(List.copyOf(sections), score, List.copyOf(issues));
    }

    public static List<DocumentParser.TextBlock> normalizeBlocks(List<DocumentParser.TextBlock> blocks) {
        if (blocks == null) return List.of();
        var result = new ArrayList<DocumentParser.TextBlock>();
        for (var block : blocks) {
            if (block == null) continue;
            var text = block.content() == null ? "" : block.content().replace("\u0000", "")
                    .replaceAll("[\\t\\r]+", " ").strip();
            if (text.isBlank()) continue;
            result.add(new DocumentParser.TextBlock(block.pageNo(), block.section(), text, block.sheetName(),
                    block.cellRange(), block.paragraphId(), block.bbox(), block.startTimeMs(), block.endTimeMs(),
                    block.confidence(), block.attributes()));
        }
        return List.copyOf(result);
    }

    private String tableGroup(DocumentParser.TextBlock block) {
        if (block.section() == null || !block.section().toLowerCase().endsWith("table-row")) return null;
        var value = block.attributes().get("tableGroup");
        return value == null ? null : String.valueOf(value);
    }

    public sealed interface Section permits Paragraph, Table { }
    public record Paragraph(String text, String type, Double confidence) implements Section { }
    public record Cell(String text, int row, int column, int rowSpan, int columnSpan, boolean header,
                       Map<String, Object> geometry) {
        public Cell { geometry = geometry == null ? Map.of() : Map.copyOf(geometry); }
        public Cell(String text, int row, int column, int rowSpan, int columnSpan, boolean header) {
            this(text, row, column, rowSpan, columnSpan, header, Map.of());
        }
    }
    public record Table(String group, List<Cell> cells, int rowCount, int columnCount,
                        double structureScore, List<Integer> abnormalRows) implements Section { }
    public record Projection(List<Section> sections, double structureScore, List<String> issues) { }

    private static final class TableBuilder {
        private final String group;
        private final List<DocumentParser.TextBlock> rows = new ArrayList<>();

        private TableBuilder(String group) { this.group = group; }
        private void add(DocumentParser.TextBlock block) { rows.add(block); }

        private Table build(LinkedHashSet<String> issues) {
            rows.sort(java.util.Comparator.comparingInt(TableBuilder::rowOrder));
            var placements = new ArrayList<Cell>();
            var occupied = new ArrayList<Integer>();
            var widths = new ArrayList<Integer>();
            var rawRows = rows.stream().map(TableBuilder::cells).toList();
            var expectedWidth = rows.stream().mapToInt(TableBuilder::declaredWidth).max().orElse(0);
            if (expectedWidth == 0) expectedWidth = rawRows.stream()
                    .mapToInt(TableBuilder::width).max().orElse(0);
            var maximumWidth = 0;
            for (var rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                var column = 0;
                for (var cell : inferPairedColumns(rawRows.get(rowIndex), expectedWidth)) {
                    while (!free(occupied, column, cell.columnSpan, rowIndex)) column++;
                    while (occupied.size() < column + cell.columnSpan) occupied.add(-1);
                    for (var offset = 0; offset < cell.columnSpan; offset++) {
                        occupied.set(column + offset, Math.max(occupied.get(column + offset), rowIndex + cell.rowSpan - 1));
                    }
                    var requestedColumn = integer(cell.geometry.get("logicalColumn"), -1);
                    if (requestedColumn >= 0) column = requestedColumn;
                    placements.add(new Cell(cell.text, rowIndex, column, cell.rowSpan, cell.columnSpan, cell.header,
                            cell.geometry));
                    column += cell.columnSpan;
                }
                for (var occupiedColumn = 0; occupiedColumn < occupied.size(); occupiedColumn++) {
                    if (occupied.get(occupiedColumn) >= rowIndex) column = Math.max(column, occupiedColumn + 1);
                }
                widths.add(column);
                maximumWidth = Math.max(maximumWidth, column);
            }
            if (expectedWidth == 0) expectedWidth = maximumWidth;
            var abnormal = new ArrayList<Integer>();
            for (var index = 0; index < widths.size(); index++) {
                if (!widths.get(index).equals(expectedWidth)) abnormal.add(index);
            }
            if (!abnormal.isEmpty()) issues.add("TABLE_WIDTH_INCONSISTENT:" + group);
            var consistent = rows.isEmpty() ? 0d : (rows.size() - abnormal.size()) / (double) rows.size();
            var score = Math.max(0d, Math.min(1d, consistent * 0.8d + (expectedWidth > 1 ? 0.2d : 0d)));
            return new Table(group, List.copyOf(placements), rows.size(), Math.max(expectedWidth, maximumWidth),
                    score, List.copyOf(abnormal));
        }

        private static List<RawCell> cells(DocumentParser.TextBlock row) {
            var result = new ArrayList<RawCell>();
            var raw = row.attributes().get("cells");
            if (raw instanceof List<?> values) for (var value : values) {
                if (value instanceof Map<?, ?> cell) result.add(new RawCell(
                        string(cell.get("text")), positive(cell.get("rowSpan")), positive(cell.get("columnSpan")),
                        Boolean.parseBoolean(string(cell.get("header"))), stringMap(cell)));
                else result.add(new RawCell(string(value), 1, 1, false, Map.of()));
            }
            if (result.isEmpty()) for (var value : row.content().split("\\s*\\|\\s*", -1)) {
                result.add(new RawCell(value, 1, 1, false, Map.of()));
            }
            return result;
        }

        /**
         * OCR table HTML frequently drops colspan on a sample header/value row.
         * When the dominant table width is `label + N*2`, a `label + N` row is
         * unambiguous: every sample cell spans its material/value pair.
         */
        private static List<RawCell> inferPairedColumns(List<RawCell> cells, int expectedWidth) {
            if (cells.size() < 3 || expectedWidth < 5 || width(cells) == expectedWidth) return cells;
            if (cells.stream().anyMatch(cell -> cell.columnSpan != 1 || cell.rowSpan != 1)) return cells;
            var remainingColumns = expectedWidth - 1;
            var remainingCells = cells.size() - 1;
            if (remainingColumns != remainingCells * 2) return cells;
            var inferred = new ArrayList<RawCell>();
            inferred.add(cells.getFirst());
            cells.subList(1, cells.size()).forEach(cell ->
                    inferred.add(new RawCell(cell.text, cell.rowSpan, 2, cell.header, cell.geometry)));
            return List.copyOf(inferred);
        }

        private static int width(List<RawCell> cells) {
            return cells.stream().mapToInt(RawCell::columnSpan).sum();
        }

        private static int rowOrder(DocumentParser.TextBlock row) {
            for (var key : List.of("tableRowNo", "tableRowIndex", "logicalRowNo")) {
                var value = row.attributes().get(key);
                if (value instanceof Number number) return number.intValue();
                try { if (value != null) return Integer.parseInt(String.valueOf(value)); }
                catch (NumberFormatException ignored) { }
            }
            return Integer.MAX_VALUE;
        }

        private static int declaredWidth(DocumentParser.TextBlock row) {
            var value = row.attributes().get("tableColumnCount");
            return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
        }

        private static boolean free(List<Integer> occupied, int start, int span, int row) {
            for (var column = start; column < start + span; column++) {
                if (column < occupied.size() && occupied.get(column) >= row) return false;
            }
            return true;
        }

        private static int positive(Object value) {
            if (value instanceof Number number) return Math.max(1, number.intValue());
            try { return Math.max(1, Integer.parseInt(String.valueOf(value))); }
            catch (RuntimeException ignored) { return 1; }
        }

        private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
        private static int integer(Object value, int fallback) {
            if (value instanceof Number number) return number.intValue();
            try { return Integer.parseInt(String.valueOf(value)); } catch (RuntimeException ignored) { return fallback; }
        }
        private static Map<String, Object> stringMap(Map<?, ?> source) {
            var result = new LinkedHashMap<String, Object>();
            source.forEach((key, value) -> { if (key != null && value != null) result.put(String.valueOf(key), value); });
            return Map.copyOf(result);
        }
        private record RawCell(String text, int rowSpan, int columnSpan, boolean header,
                               Map<String, Object> geometry) { }
    }
}
