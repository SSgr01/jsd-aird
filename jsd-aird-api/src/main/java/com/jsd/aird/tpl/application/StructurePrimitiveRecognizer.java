package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Produces conservative, reusable structure hints before semantic field matching.
 * It never assigns a standard field code and never promotes a hint to a formal mapping.
 */
public final class StructurePrimitiveRecognizer {

    private static final Set<String> STATIC_PREFIXES = Set.of("注：", "注:", "备注：", "备注:",
            "注意：", "注意:", "说明：", "说明:", "提示：", "提示:", "操作要求：", "操作要求:");

    private final ObjectMapper objectMapper;

    public StructurePrimitiveRecognizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ArrayNode recognize(JsonNode structure) {
        var result = objectMapper.createArrayNode();
        for (var sheet : structure.path("sheets")) {
            var sheetId = sheet.path("id").asText(sheet.path("sheetId").asText(""));
            var cells = new ArrayList<JsonNode>();
            sheet.path("semanticCells").forEach(cells::add);
            if (cells.isEmpty()) continue;
            addMatrix(result, sheetId, sheet, cells);
            // Discover scalar form fields after the matrix envelope is known. A
            // label immediately before a matrix member row must not become a
            // scalar field merely because the member cells are blank inputs.
            addFieldGroups(result, sheetId, cells);
            // A sheet may contain a matrix and one or more ordinary repeated
            // tables. Their detection is independent; overlap filtering keeps
            // a matrix axis from being emitted as a second row table.
            addRepeatedRows(result, sheetId, sheet, cells);
            addStaticAndTextRegions(result, sheetId, cells);
        }
        return result;
    }

    /** Detects all cross-filled regions before ordinary repeated-row detection. */
    private boolean addMatrix(ArrayNode result, String sheetId, JsonNode sheet, List<JsonNode> cells) {
        var grids = detectBlankGrids(sheet);
        if (grids.isEmpty()) return addMatrixForGrid(result, sheetId, sheet, cells, null);
        var detected = false;
        for (var grid : grids) detected |= addMatrixForGrid(result, sheetId, sheet, cells, grid);
        return detected;
    }

    private boolean addMatrixForGrid(
            ArrayNode result, String sheetId, JsonNode sheet, List<JsonNode> cells, GridSurface selectedGrid
    ) {
        var grid = selectedGrid;
        var matrixHeaderRow = findStructuralHeaderRow(cells, grid);
        if (grid != null && matrixHeaderRow != null
                && matrixHeaderRow > grid.startRow() && matrixHeaderRow < grid.endRow()) {
            // A bordered worksheet often starts with full-width title and metadata
            // rows. They are not the matrix identity row even though they look like
            // the same blank grid to a border-only detector.
            grid = new GridSurface(grid.startColumn(), grid.endColumn(), matrixHeaderRow, grid.endRow());
        }
        var active = cells.stream()
                .filter(cell -> cell.path("inputCandidate").asBoolean(false)
                        || "FORMULA".equals(cell.path("factType").asText())
                        || cell.path("formula").isTextual())
                .toList();
        // A styled/bordered surface by itself is not enough to turn an ordinary
        // table into a matrix. When no textual identity row is available, retain
        // only layouts with a physical merged corner or clear cross-filled input
        // evidence. This keeps legacy physical-only matrix detection working.
        if (grid != null && matrixHeaderRow == null
                && active.size() < 6 && !hasMergedMatrixCorner(cells, grid)) return false;
        if (active.size() < 6 && grid == null) return false;
        var minColumn = active.stream().mapToInt(cell -> cell.path("column").asInt()).min()
                .orElse(grid == null ? 1 : grid.startColumn());
        var maxColumn = active.stream().mapToInt(cell -> endColumn(cell, cell.path("column").asInt())).max()
                .orElse(grid == null ? minColumn : grid.endColumn());
        if (grid != null) {
            minColumn = Math.max(minColumn, dataColumnStart(cells, grid));
            maxColumn = Math.max(maxColumn, grid.endColumn());
        }
        var formulaCells = cells.stream()
                .filter(cell -> "FORMULA".equals(cell.path("factType").asText())
                        || cell.path("formula").isTextual())
                .toList();
        // Input candidates from the form header may be above the matrix. They can
        // prove that the workbook has editable cells, but must not move the matrix
        // start row. Formula rows are only a lower-bound anchor; the corner and
        // physical input surface still decide the final layout.
        var formulaMinRow = formulaCells.stream()
                .mapToInt(cell -> cell.path("row").asInt())
                .min()
                .orElse(active.stream().mapToInt(cell -> cell.path("row").asInt()).min()
                        .orElse(matrixHeaderRow == null ? 1 : matrixHeaderRow));
        var maxRow = grid == null
                ? usedRangeEndRow(sheet.path("usedRange").asText(""), cells, formulaMinRow)
                : grid.endRow();
        var corner = findCorner(cells, minColumn, grid == null ? formulaMinRow : grid.startRow());
        if (corner != null) corner = extendCornerAcrossHeader(cells, corner, minColumn);
        var blankHeaderRow = grid != null && blankGridRow(grid, minColumn, cells);
        var dataStartRow = corner == null
                ? grid == null ? formulaMinRow : blankHeaderRow ? grid.startRow() + 1 : grid.startRow()
                : corner[3] + 1;
        if (corner != null) minColumn = corner[2] + 1;
        var dataColumnStart = minColumn;
        var dataRowStart = dataStartRow;
        var dataRowEnd = maxRow;
        if (grid != null) {
            // Once a corner is found, its right edge is the authoritative split
            // between row labels and the runtime input surface.  Do not let a
            // populated result cell inside E:N move the split to F or later.
            dataColumnStart = corner == null
                    ? Math.max(dataColumnStart, dataColumnStart(cells, grid))
                    : corner[2] + 1;
            // A merged/static corner already accounts for the header row.  In that
            // layout the first bordered grid row is a real data row, even if it is
            // blank.  Only skip a bordered row when there is no explicit corner.
            dataRowStart = Math.max(dataRowStart,
                    grid.startRow() + (blankHeaderRow && corner == null ? 1 : 0));
            dataRowEnd = Math.max(dataRowEnd, grid.endRow());
            maxColumn = Math.max(maxColumn, grid.endColumn());
        }
        if (grid != null && grid.endColumn() - dataColumnStart + 1 < 2) return false;
        final var resolvedDataColumnStart = dataColumnStart;
        final var resolvedDataRowStart = dataRowStart;
        final var resolvedDataRowEnd = dataRowEnd;
        final var resolvedMinColumn = minColumn;
        var leftLabels = cells.stream()
                .filter(cell -> cell.path("column").asInt() < resolvedDataColumnStart
                        && cell.path("row").asInt() >= resolvedDataRowStart && cell.path("row").asInt() <= resolvedDataRowEnd
                        && !cell.path("value").asText("").strip().isBlank())
                .toList();
        // Multiple row-axis levels and merged labels are strong evidence, but
        // they are not required: a one-level cross-tab is still a MATRIX.
        var leftWidth = resolvedMinColumn - leftLabels.stream()
                .mapToInt(cell -> cell.path("column").asInt()).min().orElse(resolvedMinColumn);
        var hasMergedAxis = leftLabels.stream().anyMatch(cell -> !cell.path("mergedRange").asText("").isBlank());
        if (leftWidth < 1) return false;
        var headerRow = corner == null
                ? Math.max(1, blankHeaderRow ? dataStartRow - 1 : dataStartRow - 1)
                : corner[1];
        var rowStartColumn = corner == null
                ? Math.max(1, minColumn - Math.max(2, leftWidth)) : corner[0];
        var rowEndColumn = minColumn - 1;
        var tableRange = range(rowStartColumn, headerRow, maxColumn, maxRow);
        var rowHeaderRange = range(rowStartColumn, dataStartRow, rowEndColumn, maxRow);
        var cornerRange = range(rowStartColumn, headerRow, rowEndColumn, headerRow);
        var columnHeaderRange = range(minColumn, headerRow, maxColumn, headerRow);
        var crossDataRange = range(minColumn, dataStartRow, maxColumn, maxRow);
        var recordAxis = inferRecordAxis(grid, cells, headerRow, dataStartRow, maxRow);
        var structure = objectMapper.createObjectNode()
                .put("headerRange", columnHeaderRange)
                .put("dataRange", crossDataRange)
                .put("cornerRange", cornerRange)
                .put("rowHeaderRange", rowHeaderRange)
                .put("columnHeaderRange", columnHeaderRange)
                .put("crossDataRange", crossDataRange)
                .put("semanticMode", "CROSS_TAB")
                .put("recordAxis", recordAxis)
                .put("recordAxisHint", recordAxis)
                .put("recordHeight", maxRow - headerRow + 1)
                .put("recordWidth", 1)
                .put("recordStride", 1)
                .put("measureHeight", maxRow - dataStartRow + 1)
                .put("recordHeightIncludesIdentity", true)
                .put("canonicalStatus", "PROVISIONAL")
                .put("columnMemberRole", "COLUMN_MEMBER_INPUT")
                .put("memberMode", "RUNTIME_INPUT");
        var projection = structure.putObject("recordProjection")
                .put("mode", "COLUMN".equals(recordAxis) ? "COLUMN_RECORDS" : "UNRESOLVED")
                .put("recordAxis", recordAxis)
                .put("identityRow", headerRow)
                .put("valueStartRow", dataStartRow)
                .put("valueEndRow", maxRow);
        var recordColumns = projection.putArray("recordColumns");
        if ("COLUMN".equals(recordAxis)) {
            for (int column = minColumn; column <= maxColumn; column++) recordColumns.add(columnName(column));
        }
        var tree = structure.putArray("headerTree");
        tree.add(objectMapper.createObjectNode().put("temporaryId", "axis-column")
                .put("parentTemporaryId", "").put("name", "列成员输入")
                .put("range", columnHeaderRange).put("axis", "COLUMN")
                .put("role", "COLUMN_MEMBER_INPUT").put("memberMode", "RUNTIME_INPUT"));
        tree.add(objectMapper.createObjectNode().put("temporaryId", "axis-row-level-1")
                .put("parentTemporaryId", "").put("name", "行标题层级1")
                .put("range", range(rowStartColumn, dataStartRow, rowStartColumn, maxRow)).put("axis", "ROW")
                .put("role", "ROW_DIMENSION").put("memberMode", "CELL"));
        if (rowEndColumn - rowStartColumn >= 1) {
            tree.add(objectMapper.createObjectNode().put("temporaryId", "axis-row-level-2")
                    .put("parentTemporaryId", "").put("name", "行标题层级2")
                    .put("range", range(rowStartColumn + 1, dataStartRow, rowStartColumn + 1, maxRow))
                    .put("axis", "ROW").put("role", "ROW_DIMENSION").put("memberMode", "CELL"));
        }
        if (rowEndColumn - rowStartColumn >= 2) {
            tree.add(objectMapper.createObjectNode().put("temporaryId", "axis-row-level-3")
                    .put("parentTemporaryId", "").put("name", "行标题层级3")
                    .put("range", range(rowStartColumn + 2, dataStartRow, rowStartColumn + 2, maxRow))
                    .put("axis", "ROW").put("role", "ROW_DIMENSION").put("memberMode", "CELL"));
        }
        if (rowEndColumn - rowStartColumn >= 3) {
            tree.add(objectMapper.createObjectNode().put("temporaryId", "axis-row-attribute-1")
                    .put("parentTemporaryId", "").put("name", "行属性")
                    .put("range", range(rowStartColumn + 3, dataStartRow, rowStartColumn + 3, maxRow))
                    .put("axis", "ROW").put("role", "ROW_ATTRIBUTE").put("memberMode", "CELL"));
        }
        if (grid != null) {
            add(result, "BLANK_GRID_INPUT_SURFACE", sheetId,
                    range(dataColumnStart, dataStartRow, grid.endColumn(), dataRowEnd),
                    objectMapper.createObjectNode().put("inputMode", "BLANK_GRID")
                            .put("columnCount", grid.endColumn() - dataColumnStart + 1)
                            .put("rowCount", dataRowEnd - dataStartRow + 1),
                    0.94, List.of("BORDERED_BLANK_GRID", "REPEATED_COLUMN_SURFACE", "STYLE_REPEAT"));
            add(result, "RUNTIME_COLUMN_MEMBER_SURFACE", sheetId,
                    range(dataColumnStart, headerRow, grid.endColumn(), headerRow),
                    objectMapper.createObjectNode().put("memberMode", "RUNTIME_INPUT")
                            .put("columnMemberRole", "COLUMN_MEMBER_INPUT")
                            .put("slotCount", grid.endColumn() - dataColumnStart + 1),
                    0.94, List.of("BLANK_IDENTITY_ROW", "REPEATED_COLUMN_SURFACE"));
            add(result, "CROSS_TAB_CANDIDATE", sheetId, tableRange,
                    objectMapper.createObjectNode().put("tableKind", "MATRIX")
                            .put("semanticMode", "CROSS_TAB").put("recordAxis", recordAxis),
                    0.95, List.of("BLANK_GRID_INPUT_SURFACE", "MULTI_LEVEL_ROW_LABELS", "RUNTIME_COLUMN_MEMBER_SURFACE"));
        }
        add(result, "MATRIX", sheetId, tableRange, structure, 0.86,
                grid == null
                        ? List.of("CROSS_FILLED_DATA", "MULTI_LEVEL_ROW_LABELS", "FORMULA_OR_INPUT_GRID")
                        : List.of("BLANK_GRID_INPUT_SURFACE", "MULTI_LEVEL_ROW_LABELS", "RUNTIME_COLUMN_MEMBER_SURFACE"));
        return true;
    }

    private List<GridSurface> detectBlankGrids(JsonNode sheet) {
        var byRow = new java.util.TreeMap<Integer, List<Integer>>();
        for (var cell : sheet.path("candidateCells")) {
            if (!hasBorderEvidence(cell)) continue;
            byRow.computeIfAbsent(cell.path("row").asInt(), ignored -> new ArrayList<>())
                    .add(cell.path("column").asInt());
        }
        var candidates = new ArrayList<GridSurface>();
        for (var entry : byRow.entrySet()) {
            var columns = entry.getValue().stream().distinct().sorted().toList();
            var start = -1;
            var previous = -1;
            for (var column : columns) {
                if (start < 0 || column != previous + 1) {
                    if (start >= 0) {
                        for (int candidateStart = start; candidateStart < previous; candidateStart++) {
                            candidates.add(contiguousGrid(candidateStart, previous, entry.getKey(), byRow));
                        }
                    }
                    start = column;
                }
                previous = column;
            }
            if (start >= 0) {
                for (int candidateStart = start; candidateStart < previous; candidateStart++) {
                    candidates.add(contiguousGrid(candidateStart, previous, entry.getKey(), byRow));
                }
            }
        }
        candidates.removeIf(candidate -> candidate.endRow() - candidate.startRow() + 1 < 3
                || candidate.endColumn() - candidate.startColumn() < 1);
        candidates.sort(java.util.Comparator.comparingInt(GridSurface::area).reversed());
        var selected = new ArrayList<GridSurface>();
        for (var candidate : candidates) {
            if (selected.stream().noneMatch(existing -> overlaps(existing, candidate))) selected.add(candidate);
        }
        return List.copyOf(selected);
    }

    private GridSurface contiguousGrid(
            int startColumn, int endColumn, int startRow, java.util.TreeMap<Integer, List<Integer>> byRow
    ) {
        var required = java.util.stream.IntStream.rangeClosed(startColumn, endColumn).boxed().toList();
        var lastRow = startRow;
        for (int row = startRow + 1; row <= byRow.lastKey(); row++) {
            var columns = byRow.get(row);
            if (columns == null || !columns.stream().distinct().toList().containsAll(required)) break;
            lastRow = row;
        }
        return new GridSurface(startColumn, endColumn, startRow, lastRow);
    }

    private boolean hasBorderEvidence(JsonNode cell) {
        return cell.path("hasBorder").asBoolean(false)
                || (cell.path("style").path("bd").isObject()
                && !cell.path("style").path("bd").isEmpty());
    }

    private boolean hasMergedMatrixCorner(List<JsonNode> cells, GridSurface grid) {
        return cells.stream()
                .map(cell -> bounds(cell.path("mergedRange").asText("")))
                .anyMatch(value -> value != null
                        && value[2] > value[0]
                        && value[1] <= grid.startRow()
                        && value[3] < grid.endRow());
    }

    /**
     * Finds a structural header row without depending on business vocabulary.
     * A full-sheet border is common in forms, so the first bordered row is not
     * necessarily the table header. A horizontal merged corner followed by
     * multiple left-axis labels is a stable geometric signal; the latest such
     * row is selected so title and metadata rows stay outside the matrix.
     */
    private Integer findStructuralHeaderRow(List<JsonNode> cells, GridSurface grid) {
        if (grid == null) return null;
        var candidates = new ArrayList<Integer>();
        for (var cell : cells) {
            var value = cell.path("value").asText("").strip();
            if (value.isBlank()) continue;
            var row = cell.path("row").asInt();
            var merged = bounds(cell.path("mergedRange").asText(""));
            if (row < grid.startRow() || row >= grid.endRow() || merged == null
                    || merged[2] - merged[0] < 1 || merged[1] != merged[3]
                    || merged[2] >= grid.endColumn()) continue;
            var nextRows = cells.stream()
                    .filter(next -> next.path("row").asInt() > row
                            && next.path("row").asInt() <= Math.min(grid.endRow(), row + 3)
                            && !next.path("value").asText("").strip().isBlank()
                            && next.path("column").asInt() >= merged[0]
                            && next.path("column").asInt() <= merged[2] + 1)
                    .map(next -> next.path("column").asInt())
                    .distinct()
                    .toList();
            var hasVerticalAxis = cells.stream()
                    .map(next -> bounds(next.path("mergedRange").asText("")))
                    .anyMatch(next -> next != null && next[1] > row && next[3] > next[1]
                            && next[0] >= merged[0] && next[2] <= merged[2] + 1);
            if (nextRows.size() >= 2 || hasVerticalAxis) candidates.add(row);
        }
        return candidates.stream().max(Integer::compareTo).orElse(null);
    }

    /** Include an adjacent method/header cell in the corner axis. */
    private int[] extendCornerAcrossHeader(List<JsonNode> cells, int[] corner, int dataColumn) {
        var endColumn = corner[2];
        for (var cell : cells) {
            if (cell.path("row").asInt() != corner[1]
                    || cell.path("column").asInt() <= endColumn
                    || cell.path("column").asInt() >= dataColumn
                    || cell.path("value").asText("").strip().isBlank()) continue;
            endColumn = Math.max(endColumn, endColumn(cell, cell.path("column").asInt()));
        }
        return endColumn == corner[2]
                ? corner
                : new int[]{corner[0], corner[1], endColumn, corner[3]};
    }

    private GridSurface betterGrid(GridSurface current, GridSurface candidate) {
        return current == null || candidate.area() > current.area()
                || candidate.area() == current.area() && candidate.startRow() < current.startRow()
                ? candidate : current;
    }

    private boolean overlaps(GridSurface left, GridSurface right) {
        return left.startColumn() <= right.endColumn() && right.startColumn() <= left.endColumn()
                && left.startRow() <= right.endRow() && right.startRow() <= left.endRow();
    }

    private boolean overlaps(RowSurface left, RowSurface right) {
        return left.startColumn() <= right.endColumn() && right.startColumn() <= left.endColumn()
                && left.headerRow() <= right.endRow() && right.headerRow() <= left.endRow();
    }

    private int dataColumnStart(List<JsonNode> cells, GridSurface grid) {
        var leftValueColumn = cells.stream()
                .filter(cell -> cell.path("column").asInt() >= grid.startColumn()
                        && cell.path("column").asInt() <= grid.endColumn()
                        && cell.path("row").asInt() > grid.startRow()
                        && cell.path("row").asInt() <= grid.endRow()
                        && !cell.path("value").asText("").strip().isBlank())
                .mapToInt(cell -> cell.path("column").asInt())
                .max().orElse(grid.startColumn() - 1);
        return Math.max(grid.startColumn(), leftValueColumn + 1);
    }

    private boolean blankGridRow(GridSurface grid, int dataColumnStart, List<JsonNode> cells) {
        return cells.stream().noneMatch(cell -> cell.path("row").asInt() == grid.startRow()
                && cell.path("column").asInt() >= dataColumnStart
                && cell.path("column").asInt() <= grid.endColumn()
                && !cell.path("value").asText("").strip().isBlank());
    }

    /**
     * Returns only an axis hint. It is deliberately not a canonical decision;
     * the structure assessment stage may still choose ROW or UNKNOWN.
     */
    private String inferRecordAxis(
            GridSurface grid, List<JsonNode> cells, int headerRow, int dataStartRow, int dataEndRow
    ) {
        if (grid == null) return "UNKNOWN";
        var headerInputs = cells.stream()
                .filter(cell -> cell.path("row").asInt() == headerRow
                        && cell.path("column").asInt() >= grid.startColumn()
                        && cell.path("column").asInt() <= grid.endColumn()
                        && cell.path("inputCandidate").asBoolean(false))
                .count();
        var bodyInputs = cells.stream()
                .filter(cell -> cell.path("row").asInt() >= dataStartRow
                        && cell.path("row").asInt() <= dataEndRow
                        && cell.path("column").asInt() >= grid.startColumn()
                        && cell.path("column").asInt() <= grid.endColumn()
                        && cell.path("inputCandidate").asBoolean(false))
                .count();
        if (headerInputs > 0 && bodyInputs >= headerInputs) return "COLUMN";
        // Blank runtime headers are often omitted from semanticCells. A
        // bordered rectangular input surface with at least two columns is
        // still strong physical evidence for column members; this remains a
        // provisional hint and can be overturned by structure assessment.
        if (grid.endColumn() - grid.startColumn() >= 1
                && dataEndRow - dataStartRow + 1 >= 2) return "COLUMN";
        return "UNKNOWN";
    }

    private void addFieldGroups(ArrayNode result, String sheetId, List<JsonNode> cells) {
        for (var label : cells) {
            var text = label.path("value").asText("").strip();
            if (text.isBlank()) continue;
            var row = label.path("row").asInt();
            var column = label.path("column").asInt();
            var labelEndColumn = endColumn(label, column);
            var next = cells.stream()
                    .filter(candidate -> sheetId.equals(candidate.path("sheetId").asText("")))
                    .filter(candidate -> candidate.path("row").asInt() == row
                            && candidate.path("column").asInt() == labelEndColumn + 1)
                    .findFirst().orElse(null);
            if (next == null || (!next.path("inputCandidate").asBoolean(false)
                    && !"FORMULA".equals(next.path("factType").asText("")))) continue;
            var explicitLabel = text.matches("^.*[：:]$");
            var horizontalLabel = labelEndColumn > column && endRow(label, row) == row;
            var adjacentInputSurface = endColumn(next, labelEndColumn + 1) > labelEndColumn + 1
                    || endRow(next, row) > row;
            if (!explicitLabel && !(horizontalLabel || adjacentInputSurface)) continue;
            var fieldRange = range(column, row, endColumn(next, labelEndColumn + 1), endRow(next, row));
            if (overlapsMatrix(result, sheetId, fieldRange)) continue;
            add(result, "FORM_REGION", sheetId,
                    fieldRange,
                    objectMapper.createObjectNode().put("labelRange", address(label))
                            .put("valueRange", rangeFromCell(next)),
                    0.82, List.of("EXPLICIT_LABEL", "ADJACENT_INPUT"));
        }
    }

    private boolean overlapsMatrix(ArrayNode primitives, String sheetId, String range) {
        var candidate = bounds(range);
        if (candidate == null) return false;
        for (var primitive : primitives) {
            if (!"MATRIX".equals(primitive.path("blockType").asText())
                    || !sheetId.equals(primitive.path("sheetId").asText(""))) continue;
            var matrix = bounds(primitive.path("range").asText(""));
            if (matrix != null && candidate[0] <= matrix[2] && matrix[0] <= candidate[2]
                    && candidate[1] <= matrix[3] && matrix[1] <= candidate[3]) return true;
        }
        return false;
    }

    private void addRepeatedRows(ArrayNode result, String sheetId, JsonNode sheet, List<JsonNode> cells) {
        for (var physical : detectRepeatedRowSurfaces(sheet, cells)) {
            var physicalRange = range(physical.startColumn(), physical.headerRow(), physical.endColumn(), physical.endRow());
            if (overlapsMatrix(result, sheetId, physicalRange)) continue;
            var details = objectMapper.createObjectNode()
                    .put("headerRange", range(physical.startColumn(), physical.headerRow(),
                            physical.endColumn(), physical.headerRow()))
                    .put("dataRange", range(physical.startColumn(), physical.dataStartRow(),
                            physical.endColumn(), physical.endRow()))
                    .put("repeatAxis", "ROW")
                    .put("recordHeight", 1)
                    .put("recordStride", 1);
            if (physical.totalRow() > 0) {
                details.set("terminationRule", objectMapper.createObjectNode()
                        .put("type", "UNTIL_TOTAL_ROW")
                        .put("label", physical.totalLabel())
                        .put("address", address(physical.startColumn(), physical.totalRow())));
            } else {
                details.set("terminationRule", objectMapper.createObjectNode()
                        .put("type", "UNTIL_REGION_END"));
            }
            add(result, "ROW_TABLE", sheetId,
                    physicalRange,
                    details, 0.86,
                    List.of("REPEATED_BORDERED_ROWS", "STABLE_RECORD_WIDTH", "MULTI_ROW_INPUT_SURFACE"));
        }
        var byRow = new HashMap<Integer, List<JsonNode>>();
        for (var cell : cells) {
            if (cell.path("inputCandidate").asBoolean(false)
                    || "FORMULA".equals(cell.path("factType").asText(""))) {
                byRow.computeIfAbsent(cell.path("row").asInt(), ignored -> new ArrayList<>()).add(cell);
            }
        }
        var rows = byRow.keySet().stream().sorted().toList();
        int start = -1;
        int previous = -1;
        for (var row : rows) {
            if (byRow.get(row).size() < 3) {
                if (start >= 0) addRows(result, sheetId, cells, start, previous);
                start = -1;
                previous = -1;
                continue;
            }
            if (start < 0) start = row;
            if (previous >= 0 && row != previous + 1) {
                addRows(result, sheetId, cells, start, previous);
                start = row;
            }
            previous = row;
        }
        if (start >= 0) addRows(result, sheetId, cells, start, previous);
    }

    /**
     * Finds an ordinary repeated-row surface from physical border facts. Blank
     * input cells are frequently low-confidence or omitted from semanticCells,
     * so row-table discovery must not depend on model-facing input candidates.
     */
    private List<RowSurface> detectRepeatedRowSurfaces(JsonNode sheet, List<JsonNode> semanticCells) {
        var byRow = new java.util.TreeMap<Integer, java.util.Set<Integer>>();
        for (var cell : sheet.path("candidateCells")) {
            if (!hasBorderEvidence(cell)) continue;
            var bounds = bounds(cell.path("mergedRange").asText(""));
            var firstColumn = cell.path("column").asInt(0);
            var lastColumn = bounds == null ? firstColumn : bounds[2];
            if (firstColumn <= 0) continue;
            var columns = byRow.computeIfAbsent(cell.path("row").asInt(), ignored -> new java.util.TreeSet<>());
            for (var column = firstColumn; column <= Math.max(firstColumn, lastColumn); column++) columns.add(column);
        }
        if (byRow.isEmpty()) return List.of();

        var eligibleRows = byRow.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= 3)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (eligibleRows.size() < 4) return List.of();

        var surfaces = new ArrayList<RowSurface>();
        var runStart = 0;
        for (int index = 1; index <= eligibleRows.size(); index++) {
            var contiguous = index < eligibleRows.size()
                    && eligibleRows.get(index) == eligibleRows.get(index - 1) + 1;
            if (contiguous) continue;
            var run = eligibleRows.subList(runStart, index);
            if (run.size() >= 4) {
                var candidate = rowSurface(run, byRow, semanticCells);
                if (candidate != null) surfaces.add(candidate);
            }
            runStart = index;
        }
        surfaces.sort(java.util.Comparator.comparingInt(RowSurface::area).reversed());
        var selected = new ArrayList<RowSurface>();
        for (var surface : surfaces) {
            if (selected.stream().noneMatch(existing -> overlaps(existing, surface))) selected.add(surface);
        }
        return List.copyOf(selected);
    }

    private RowSurface rowSurface(List<Integer> rows, Map<Integer, java.util.Set<Integer>> byRow,
                                  List<JsonNode> semanticCells) {
        var headerRow = rows.stream()
                .max(java.util.Comparator.comparingInt(row -> nonBlankCells(semanticCells, row)))
                .orElse(rows.getFirst());
        var headerColumns = byRow.get(headerRow);
        if (headerColumns == null || headerColumns.size() < 3) return null;
        var startColumn = headerColumns.stream().mapToInt(Integer::intValue).min().orElse(0);
        var endColumn = headerColumns.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (endColumn - startColumn + 1 < 3) return null;

        var dataStartRow = headerRow + 1;
        var endRow = rows.stream().mapToInt(Integer::intValue).max().orElse(headerRow);
        if (endRow - dataStartRow + 1 < 3) return null;
        var totalRow = -1;
        var totalLabel = "";
        for (var cell : semanticCells) {
            if (cell.path("row").asInt() < dataStartRow || cell.path("row").asInt() > endRow) continue;
            var value = cell.path("value").asText("").strip();
            if (value.contains("小计") || value.contains("合计") || value.contains("总计")) {
                totalRow = cell.path("row").asInt();
                totalLabel = value;
                break;
            }
        }
        var effectiveEndRow = totalRow > 0 ? totalRow : endRow;
        var coveredRows = rows.stream()
                .filter(row -> row >= dataStartRow && row <= effectiveEndRow)
                .filter(row -> coverage(byRow.get(row), startColumn, endColumn) >= 0.60)
                .toList();
        if (coveredRows.size() < 3) return null;
        return new RowSurface(startColumn, endColumn, headerRow, dataStartRow, effectiveEndRow,
                totalRow, totalLabel);
    }

    private int nonBlankCells(List<JsonNode> cells, int row) {
        return (int) cells.stream()
                .filter(cell -> cell.path("row").asInt() == row)
                .filter(cell -> !cell.path("value").asText("").strip().isBlank())
                .count();
    }

    private double coverage(java.util.Set<Integer> columns, int startColumn, int endColumn) {
        if (columns == null || columns.isEmpty()) return 0;
        var covered = columns.stream()
                .filter(column -> column >= startColumn && column <= endColumn)
                .count();
        return covered / (double) Math.max(1, endColumn - startColumn + 1);
    }

    private void addRows(ArrayNode result, String sheetId, List<JsonNode> cells, int start, int end) {
        if (end - start + 1 < 3) return;
        var rowCells = cells.stream().filter(cell -> cell.path("row").asInt() >= start
                && cell.path("row").asInt() <= end
                && (cell.path("inputCandidate").asBoolean(false)
                || "FORMULA".equals(cell.path("factType").asText("")))).toList();
        if (rowCells.isEmpty()) return;
        var minColumn = rowCells.stream().mapToInt(cell -> cell.path("column").asInt()).min().orElse(1);
        var maxColumn = rowCells.stream().mapToInt(cell -> endColumn(cell, cell.path("column").asInt())).max().orElse(minColumn);
        var headerRow = Math.max(1, start - 1);
        var tableRange = range(minColumn, headerRow, maxColumn, end);
        if (overlapsMatrix(result, sheetId, tableRange)) return;
        add(result, "ROW_TABLE", sheetId, tableRange,
                objectMapper.createObjectNode()
                        .put("headerRange", range(minColumn, headerRow, maxColumn, headerRow))
                        .put("dataRange", range(minColumn, start, maxColumn, end))
                        .put("repeatAxis", "ROW")
                        .put("recordHeight", 1)
                        .put("recordStride", 1),
                0.68, List.of("REPEATED_ROWS", "STABLE_INPUT_COLUMNS"));
    }

    private void addStaticAndTextRegions(ArrayNode result, String sheetId, List<JsonNode> cells) {
        var seen = new HashSet<String>();
        for (var cell : cells) {
            var text = cell.path("value").asText("").strip();
            if (text.isBlank()) continue;
            var merged = cell.path("mergedRange").asText("");
            var address = merged.isBlank() ? address(cell) : merged;
            var prefix = STATIC_PREFIXES.stream().anyMatch(text::startsWith);
            var blockType = prefix ? "STATIC_REFERENCE"
                    : (!merged.isBlank() && (text.length() >= 12 || merged.contains(":")) ? "FREE_TEXT" : null);
            if (blockType == null || !seen.add(blockType + "|" + address)) continue;
            add(result, blockType, sheetId, address,
                    objectMapper.createObjectNode().put("textRange", address),
                    prefix ? 0.94 : 0.62,
                    prefix ? List.of("STATIC_PREFIX", "TEXT_VALUE") : List.of("MERGED_TEXT_REGION"));
        }
    }

    private void add(ArrayNode result, String blockType, String sheetId, String range,
                     ObjectNode details, double confidence, List<String> evidence) {
        for (var existing : result) {
            if (blockType.equals(existing.path("blockType").asText())
                    && sheetId.equals(existing.path("sheetId").asText())
                    && range.equalsIgnoreCase(existing.path("range").asText())) {
                if (confidence > existing.path("confidence").asDouble(0)
                        && existing instanceof ObjectNode object) {
                    object.set("structure", details.deepCopy());
                    object.put("confidence", confidence);
                     object.put("validationStatus", "VALID");
                     object.put("geometryStatus", "VALID_GEOMETRY");
                     object.put("classificationStatus", confidence >= 0.8 ? "HIGH" : "AMBIGUOUS");
                     object.put("classificationConfidence", confidence);
                     object.put("canonicalStatus", "PROVISIONAL");
                     object.put("candidateId", object.path("id").asText());
                     object.put("physicalCandidate", true);
                    var evidenceNode = objectMapper.createArrayNode();
                    evidence.forEach(evidenceNode::add);
                    object.set("evidence", evidenceNode);
                }
                return;
            }
        }
        var evidenceNode = objectMapper.createArrayNode();
        evidence.forEach(evidenceNode::add);
        var primitive = objectMapper.createObjectNode()
                .put("id", "primitive-" + RecognitionIdentity.shortHash(sheetId + "|" + blockType + "|" + range, 16))
                .put("sheetId", sheetId)
                .put("blockType", blockType)
                .put("range", range);
        primitive.set("structure", details);
        primitive.put("confidence", confidence);
        primitive.set("evidence", evidenceNode);
          primitive.put("validationStatus", "VALID");
          primitive.put("geometryStatus", "VALID_GEOMETRY");
          primitive.put("classificationStatus", confidence >= 0.8 ? "HIGH" : "AMBIGUOUS");
         primitive.put("classificationConfidence", confidence);
         primitive.put("canonicalStatus", "PROVISIONAL");
         primitive.put("candidateId", primitive.path("id").asText());
         primitive.put("physicalCandidate", true);
         result.add(primitive);
    }

    private String address(JsonNode cell) {
        return cell.path("address").asText(rangeFromCell(cell));
    }

    private String rangeFromCell(JsonNode cell) {
        var merged = cell.path("mergedRange").asText("");
        return merged.isBlank() ? address(cell.path("column").asInt(), cell.path("row").asInt()) : merged;
    }

    private int endColumn(JsonNode cell, int fallback) {
        return bounds(cell.path("mergedRange").asText(""), fallback, cell.path("column").asInt());
    }

    private int endRow(JsonNode cell, int fallback) {
        var range = cell.path("mergedRange").asText("");
        if (!range.contains(":")) return fallback;
        try { return Integer.parseInt(range.substring(range.indexOf(':') + 1).replaceAll("^[A-Z]+", "")); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private int usedRangeEndRow(String usedRange, List<JsonNode> cells, int fallback) {
        var bounds = bounds(usedRange);
        if (bounds != null) return bounds[3];
        return cells.stream().mapToInt(cell -> cell.path("row").asInt(fallback)).max().orElse(fallback);
    }

    private int[] findCorner(List<JsonNode> cells, int dataColumn, int formulaRow) {
        return cells.stream()
                .filter(cell -> !cell.path("value").asText("").strip().isBlank())
                .map(cell -> bounds(cell.path("mergedRange").asText("")))
                .filter(value -> value != null && value[2] > value[0]
                        && value[2] < dataColumn && value[1] <= formulaRow)
                .max(java.util.Comparator.comparingInt(value -> value[1]))
                .orElse(null);
    }

    private int[] bounds(String address) {
        if (address == null || address.isBlank()) return null;
        var parts = address.toUpperCase(Locale.ROOT).split(":", 2);
        var start = cellBounds(parts[0]);
        var end = cellBounds(parts.length == 1 ? parts[0] : parts[1]);
        if (start == null || end == null) return null;
        return new int[]{Math.min(start[0], end[0]), Math.min(start[1], end[1]),
                Math.max(start[0], end[0]), Math.max(start[1], end[1])};
    }

    private int[] cellBounds(String address) {
        var matcher = java.util.regex.Pattern.compile("^([A-Z]+)([1-9][0-9]*)$")
                .matcher(address == null ? "" : address.toUpperCase(Locale.ROOT));
        if (!matcher.matches()) return null;
        var column = 0;
        for (var character : matcher.group(1).toCharArray()) column = column * 26 + character - 'A' + 1;
        var row = Integer.parseInt(matcher.group(2));
        return new int[]{column, row, column, row};
    }

    private int bounds(String range, int fallback, int column) {
        if (!range.contains(":")) return fallback;
        var end = range.substring(range.indexOf(':') + 1).replaceAll("[0-9]+$", "");
        var value = 0;
        for (var ch : end.toCharArray()) value = value * 26 + ch - 'A' + 1;
        return Math.max(column, value);
    }

    private String address(int column, int row) {
        return columnName(column) + row;
    }

    private String range(int startColumn, int startRow, int endColumn, int endRow) {
        var start = address(startColumn, startRow);
        var end = address(endColumn, endRow);
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

    private record GridSurface(int startColumn, int endColumn, int startRow, int endRow) {
        int area() {
            return Math.max(0, endColumn - startColumn + 1) * Math.max(0, endRow - startRow + 1);
        }
    }

    private record RowSurface(int startColumn, int endColumn, int headerRow, int dataStartRow,
                              int endRow, int totalRow, String totalLabel) {
        int area() {
            return Math.max(0, endColumn - startColumn + 1)
                    * Math.max(0, endRow - headerRow + 1);
        }
    }
}
