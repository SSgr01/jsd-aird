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
    private final boolean topologyV2Enabled;
    private final TableTopologyClassifier topologyClassifier;

    public StructurePrimitiveRecognizer(ObjectMapper objectMapper) {
        this(objectMapper, false);
    }

    public StructurePrimitiveRecognizer(ObjectMapper objectMapper, boolean topologyV2Enabled) {
        this.objectMapper = objectMapper;
        this.topologyV2Enabled = topologyV2Enabled;
        this.topologyClassifier = new TableTopologyClassifier();
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
            // Scalar label/value pairs are evidence inside a form, not region
            // roots. Consolidate the non-table bands into form envelopes only
            // after table topology is known, so two fields in the same header
            // cannot become two independent "basic information" regions.
            consolidateFormEnvelopes(result, sheetId, sheet, cells);
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
        if (topologyV2Enabled) {
            var attributeStartRow = findColumnAttributeStartRow(cells, grid);
            if (attributeStartRow != null) {
                matrixHeaderRow = matrixHeaderRow == null
                        ? attributeStartRow : Math.min(matrixHeaderRow, attributeStartRow);
            }
        }
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
            minColumn = topologyV2Enabled
                    ? topologyDataColumnStart(cells, grid, grid.startRow())
                    : Math.max(minColumn, dataColumnStart(cells, grid));
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
        maxRow = trimTrailingFullWidthTextRows(
                cells, corner == null ? (grid == null ? 1 : grid.startColumn()) : corner[0],
                maxColumn, dataStartRow, maxRow
        );
        if (corner != null) minColumn = corner[2] + 1;
        var dataColumnStart = minColumn;
        var dataRowStart = dataStartRow;
        var dataRowEnd = maxRow;
        if (grid != null) {
            // Once a corner is found, its right edge is the authoritative split
            // between row labels and the runtime input surface.  Do not let a
            // populated result cell inside E:N move the split to F or later.
            dataColumnStart = corner == null
                    ? topologyV2Enabled
                            ? topologyDataColumnStart(cells, grid, headerRowFor(grid, matrixHeaderRow))
                            : Math.max(dataColumnStart, dataColumnStart(cells, grid))
                    : corner[2] + 1;
            // A merged/static corner already accounts for the header row.  In that
            // layout the first bordered grid row is a real data row, even if it is
            // blank.  Only skip a bordered row when there is no explicit corner.
            dataRowStart = Math.max(dataRowStart,
                    grid.startRow() + (blankHeaderRow && corner == null ? 1 : 0));
            dataRowEnd = Math.min(dataRowEnd, grid.endRow());
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
        var topologyEvidence = new TableTopologyClassifier.Evidence(
                recordAxis,
                blankHeaderRow,
                countValues(cells, headerRow, dataColumnStart, maxColumn),
                (int) leftLabels.stream().map(cell -> cell.path("row").asInt()).distinct().count(),
                maxColumn - dataColumnStart + 1,
                maxRow - dataStartRow + 1,
                grid != null || active.size() >= 6,
                blankHeaderRow && (grid != null || active.size() >= 6),
                false,
                blankHeaderRow ? columnHeaderRange : "",
                "",
                crossDataRange,
                (int) leftLabels.stream().map(cell -> cell.path("row").asInt()).distinct().count(),
                !formulaCells.isEmpty()
        );
        var classification = topologyV2Enabled
                ? topologyClassifier.analyze(topologyEvidence)
                : new TableTopologyClassifier.Classification(
                        TableTopologyClassifier.Topology.MATRIX,
                        List.of(TableTopologyClassifier.Topology.MATRIX), topologyEvidence);
        // The recognizer emits a proposal only when the evidence has exactly
        // one defensible topology.  It never promotes a physical proposal to
        // canonical; that decision remains in StructureProposalResolver.
        var topology = classification.candidates().size() == 1
                ? classification.candidates().getFirst()
                : TableTopologyClassifier.Topology.UNKNOWN;
        if (topology == TableTopologyClassifier.Topology.UNKNOWN) {
            var unknownEvidence = objectMapper.createObjectNode()
                    .put("tableKind", "UNKNOWN")
                    .put("recordAxis", recordAxis)
                    .put("topologyClassifierVersion", 2);
            unknownEvidence.set("topologyEvidence", topologyEvidenceNode(topologyEvidence));
            unknownEvidence.put("reviewRequired", true);
            add(result, "TABLE_TOPOLOGY_UNKNOWN", sheetId, tableRange,
                    unknownEvidence,
                    0.55, List.of("AMBIGUOUS_ONE_AXIS_OR_TWO_AXIS_GRID"));
            return true;
        }
        if (topology == TableTopologyClassifier.Topology.COLUMN_TABLE) {
            var headerRange = range(rowStartColumn, headerRow, maxColumn, headerRow);
            var dataRange = range(rowStartColumn, dataStartRow, maxColumn, maxRow);
            var columnStructure = objectMapper.createObjectNode()
                    .put("headerRange", headerRange)
                    .put("dataRange", dataRange)
                    .put("semanticMode", "COLUMN_RECORDS")
                    .put("recordAxis", "COLUMN")
                    .put("repeatAxis", "COLUMN")
                    .put("recordHeight", maxRow - dataStartRow + 1)
                    .put("recordWidth", 1)
                    .put("recordStride", 1)
                    .put("canonicalStatus", "PROVISIONAL")
                    .put("topologyClassifierVersion", 2);
            columnStructure.set("topologyEvidence", topologyEvidenceNode(topologyEvidence));
            var columnProjection = columnStructure.putObject("recordProjection")
                    .put("mode", "COLUMN_RECORDS")
                    .put("recordAxis", "COLUMN")
                    .put("identityRow", headerRow)
                    .put("valueStartRow", dataStartRow)
                    .put("valueEndRow", maxRow);
            var projectionColumns = columnProjection.putArray("recordColumns");
            for (int column = dataColumnStart; column <= maxColumn; column++) {
                projectionColumns.add(columnName(column));
            }
            add(result, "COLUMN_TABLE", sheetId, tableRange, columnStructure, 0.9,
                    blankHeaderRow
                            ? List.of("BLANK_RECORD_IDENTITY_BAND", "REPEATED_COLUMN_SURFACE", "LEFT_ATTRIBUTE_BAND")
                            : List.of("REPEATED_COLUMN_SURFACE", "LEFT_ATTRIBUTE_BAND"));
            return true;
        }
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
        structure.put("topologyClassifierVersion", topologyV2Enabled ? 2 : 1);
        structure.set("topologyEvidence", topologyEvidenceNode(topologyEvidence));
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

    private int countValues(List<JsonNode> cells, int row, int startColumn, int endColumn) {
        return (int) cells.stream()
                .filter(cell -> cell.path("row").asInt() == row
                        && cell.path("column").asInt() >= startColumn
                        && cell.path("column").asInt() <= endColumn
                        && !cell.path("value").asText("").strip().isBlank())
                .count();
    }

    private int trimTrailingFullWidthTextRows(
            List<JsonNode> cells, int startColumn, int endColumn, int dataStartRow, int endRow
    ) {
        var trimmed = endRow;
        while (trimmed >= dataStartRow) {
            final var candidateRow = trimmed;
            var fullWidthStaticText = cells.stream().anyMatch(cell -> {
                if (cell.path("row").asInt() != candidateRow
                        || cell.path("value").asText("").strip().isBlank()
                        || cell.path("inputCandidate").asBoolean(false)
                        || "FORMULA".equals(cell.path("factType").asText(""))) return false;
                var merged = bounds(cell.path("mergedRange").asText(""));
                return merged != null && merged[0] <= startColumn && merged[2] >= endColumn;
            });
            if (!fullWidthStaticText) break;
            trimmed--;
        }
        return trimmed;
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
            if (columns == null || !columns.stream().distinct().toList().containsAll(required)) {
                if (!topologyV2Enabled || !bridgeableAttributeRow(row, required, byRow)) break;
            }
            lastRow = row;
        }
        return new GridSurface(startColumn, endColumn, startRow, lastRow);
    }

    /**
     * Some column-record forms omit borders from the runtime cells of one
     * attribute row while keeping the left attribute band. Treat that single
     * sparse prefix as part of the surrounding surface only when the complete
     * record columns resume immediately below it. A fully blank separator is
     * never bridged, so independent tables remain independent.
     */
    private boolean bridgeableAttributeRow(
            int row, List<Integer> required, java.util.TreeMap<Integer, List<Integer>> byRow
    ) {
        var columns = byRow.get(row);
        var nextColumns = byRow.get(row + 1);
        if (columns == null || nextColumns == null || required.size() < 4
                || !nextColumns.stream().distinct().toList().containsAll(required)) return false;
        var available = columns.stream().distinct().sorted().toList();
        if (available.size() < 2 || !available.contains(required.getFirst())) return false;
        var prefixEnd = required.getFirst() - 1;
        for (var column : required) {
            if (!available.contains(column)) break;
            prefixEnd = column;
        }
        var prefixWidth = prefixEnd - required.getFirst() + 1;
        return prefixWidth >= 2 && prefixWidth < required.size();
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
        return topologyV2Enabled
                ? candidates.stream().min(Integer::compareTo).orElse(null)
                : candidates.stream().max(Integer::compareTo).orElse(null);
    }

    /**
     * Finds the first vertical attribute group that shares a repeated input
     * surface to its right. This is the common start shape of a COLUMN_TABLE
     * whose visual sections continue down the same record columns.
     */
    private Integer findColumnAttributeStartRow(List<JsonNode> cells, GridSurface grid) {
        if (grid == null) return null;
        return cells.stream()
                .map(cell -> bounds(cell.path("mergedRange").asText("")))
                .filter(range -> range != null
                        && range[3] > range[1]
                        && range[0] >= grid.startColumn()
                        && range[2] < grid.endColumn())
                .filter(range -> cells.stream().anyMatch(label ->
                        label.path("row").asInt() == range[1]
                                && label.path("column").asInt() > range[2]
                                && label.path("column").asInt() < grid.endColumn()
                                && endColumn(label, label.path("column").asInt()) < grid.endColumn()
                                && !label.path("value").asText("").strip().isBlank()))
                .map(range -> range[1])
                .min(Integer::compareTo)
                .orElse(null);
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

    private int topologyDataColumnStart(List<JsonNode> cells, GridSurface grid, int headerRow) {
        return cells.stream()
                .filter(cell -> cell.path("row").asInt() == headerRow
                        && cell.path("column").asInt() >= grid.startColumn()
                        && cell.path("column").asInt() < grid.endColumn()
                        && !cell.path("value").asText("").strip().isBlank()
                        && !"FORMULA".equals(cell.path("factType").asText("")))
                .mapToInt(cell -> endColumn(cell, cell.path("column").asInt()))
                .filter(column -> column < grid.endColumn())
                .max()
                .stream()
                .map(column -> Math.min(grid.endColumn(), column + 1))
                .findFirst()
                .orElse(grid.startColumn());
    }

    private int headerRowFor(GridSurface grid, Integer structuralHeaderRow) {
        return structuralHeaderRow == null ? grid.startRow() : structuralHeaderRow;
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
            if (!Set.of("MATRIX", "COLUMN_TABLE", "ROW_TABLE")
                    .contains(primitive.path("blockType").asText())
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
                            physical.endColumn(), physical.dataEndRow()))
                    .put("recordAxis", "ROW")
                    .put("repeatAxis", "ROW");
            if (physical.recordSlots().isEmpty()) {
                details.put("recordHeight", 1).put("recordStride", 1);
            } else {
                var slots = details.putArray("recordSlots");
                for (int index = 0; index < physical.recordSlots().size(); index++) {
                    var slotRange = physical.recordSlots().get(index);
                    slots.add(objectMapper.createObjectNode()
                            .put("slotId", "record-" + (index + 1))
                            .put("recordKey", "record-" + (index + 1))
                            .put("order", index + 1)
                            .put("range", slotRange)
                            .put("identityAddress", slotRange.split(":", 2)[0]));
                }
                details.set("recordProjection", objectMapper.createObjectNode()
                        .put("mode", "ROW_RECORDS")
                        .put("recordAxis", "ROW")
                        .set("recordSlots", slots.deepCopy()));
            }
            if (physical.totalRow() > 0) {
                details.put("totalRange", range(physical.startColumn(), physical.totalRow(),
                        physical.endColumn(), physical.totalRow()));
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
                    physical.recordSlots().isEmpty()
                            ? List.of("REPEATED_BORDERED_ROWS", "STABLE_RECORD_WIDTH", "MULTI_ROW_INPUT_SURFACE")
                            : List.of("VERTICAL_MERGE_RECORD_SLOTS", "DISTINCT_RECORD_CADENCE", "ROW_RECORD_SURFACE"));
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
                if (candidate != null) surfaces.addAll(splitByVerticalMergeCadence(sheet, candidate));
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
        var effectiveEndRow = totalRow > 0 ? totalRow - 1 : endRow;
        for (int row = dataStartRow + 2; row <= effectiveEndRow; row++) {
            if (isSectionFooterRow(semanticCells, row, startColumn, endColumn)) {
                effectiveEndRow = row - 1;
                break;
            }
        }
        final var resolvedEndRow = effectiveEndRow;
        var coveredRows = rows.stream()
                .filter(row -> row >= dataStartRow && row <= resolvedEndRow)
                .filter(row -> coverage(byRow.get(row), startColumn, endColumn) >= 0.60)
                .toList();
        if (coveredRows.size() < 3) return null;
        var physicalEndRow = totalRow > 0 ? totalRow : resolvedEndRow;
        return new RowSurface(startColumn, endColumn, headerRow, dataStartRow, resolvedEndRow,
                physicalEndRow, totalRow, totalLabel, List.of());
    }

    /**
     * Splits a bordered row surface when its right-hand band has a different,
     * explicit record cadence: variable-height vertical merges aligned to the
     * same columns. This is a purely geometric invariant; the cell text is not
     * inspected and no workbook-specific coordinates are involved.
     */
    private List<RowSurface> splitByVerticalMergeCadence(JsonNode sheet, RowSurface source) {
        var groups = new java.util.LinkedHashMap<String, List<int[]>>();
        for (var merged : sheet.path("mergedRanges")) {
            var bounds = bounds(merged.path("range").asText(merged.path("address").asText("")));
            if (bounds == null || bounds[3] <= bounds[1]) continue;
            if (bounds[0] <= source.startColumn() || bounds[2] > source.endColumn()) continue;
            if (bounds[1] < source.dataStartRow() || bounds[3] > source.dataEndRow()) continue;
            groups.computeIfAbsent(bounds[0] + ":" + bounds[2], ignored -> new ArrayList<>()).add(bounds);
        }
        for (var group : groups.values()) {
            group.sort(java.util.Comparator.comparingInt(value -> value[1]));
            if (group.size() < 2) continue;
            var firstColumn = group.getFirst()[0];
            var lastColumn = group.getFirst()[2];
            if (lastColumn != source.endColumn() || firstColumn - source.startColumn() < 2) continue;
            if (group.stream().anyMatch(value -> value[0] != firstColumn || value[2] != lastColumn)) continue;
            var contiguous = group.getFirst()[1] == source.dataStartRow();
            for (int index = 1; index < group.size() && contiguous; index++) {
                contiguous = group.get(index)[1] == group.get(index - 1)[3] + 1;
            }
            if (!contiguous) continue;
            var slotEndRow = group.getLast()[3];
            var covered = group.stream().mapToInt(value -> value[3] - value[1] + 1).sum();
            if (covered < Math.ceil((slotEndRow - source.dataStartRow() + 1) * 0.8)) continue;

            var slots = group.stream().map(value -> range(value[0], value[1], value[2], value[3])).toList();
            var left = new RowSurface(source.startColumn(), firstColumn - 1,
                    source.headerRow(), source.dataStartRow(), source.dataEndRow(), source.endRow(),
                    source.totalRow(), source.totalLabel(), List.of());
            var right = new RowSurface(firstColumn, lastColumn,
                    source.headerRow(), source.dataStartRow(), slotEndRow, slotEndRow,
                    -1, "", slots);
            return List.of(left, right);
        }
        return List.of(source);
    }

    private void consolidateFormEnvelopes(
            ArrayNode result, String sheetId, JsonNode sheet, List<JsonNode> semanticCells
    ) {
        var used = structuralEnvelopeBounds(sheet, semanticCells);
        if (used == null) return;
        var occupied = new java.util.TreeSet<Integer>();
        for (var primitive : result) {
            if (!sheetId.equals(primitive.path("sheetId").asText(""))) continue;
            if (!Set.of("MATRIX", "ROW_TABLE", "COLUMN_TABLE")
                    .contains(primitive.path("blockType").asText(""))) continue;
            var area = bounds(primitive.path("range").asText(""));
            if (area == null) continue;
            for (int row = area[1]; row <= area[3]; row++) occupied.add(row);
        }
        var bandStart = used[1];
        for (int row = used[1]; row <= used[3] + 1; row++) {
            var isOccupied = row <= used[3] && occupied.contains(row);
            if (!isOccupied && row <= used[3]) continue;
            if (bandStart <= row - 1) addFormEnvelope(result, sheetId, sheet, semanticCells,
                    used[0], bandStart, used[2], row - 1);
            while (row <= used[3] && occupied.contains(row)) row++;
            bandStart = row;
        }
    }

    /**
     * Univer snapshots may retain formatted but otherwise unused cells beyond
     * the worksheet's real business surface. Those cells must not widen form
     * envelopes. Derive the extent from values, formulas, borders, merges and
     * explicit input evidence, then retain at most one immediately adjacent
     * styled row for an unbordered signature/input line.
     */
    private int[] structuralEnvelopeBounds(JsonNode sheet, List<JsonNode> semanticCells) {
        var structural = new ArrayList<JsonNode>();
        sheet.path("candidateCells").forEach(cell -> {
            if (!cell.path("value").asText("").strip().isBlank()
                    || cell.path("formula").isTextual()
                    || "FORMULA".equals(cell.path("factType").asText(""))
                    || cell.path("inputCandidate").asBoolean(false)
                    || hasBorderEvidence(cell)
                    || !cell.path("mergedRange").asText("").isBlank()) structural.add(cell);
        });
        for (var cell : semanticCells) if (structural.stream().noneMatch(existing ->
                existing.path("address").asText("").equalsIgnoreCase(cell.path("address").asText("")))) {
            structural.add(cell);
        }
        if (structural.isEmpty()) return bounds(sheet.path("usedRange").asText(""));
        var minColumn = structural.stream().mapToInt(cell -> cell.path("column").asInt(0))
                .filter(value -> value > 0).min().orElse(1);
        var maxColumn = structural.stream().mapToInt(cell -> endColumn(cell, cell.path("column").asInt(0)))
                .max().orElse(minColumn);
        var minRow = structural.stream().mapToInt(cell -> cell.path("row").asInt(0))
                .filter(value -> value > 0).min().orElse(1);
        var maxRow = structural.stream().mapToInt(cell -> endRow(cell, cell.path("row").asInt(0)))
                .max().orElse(minRow);
        final var trailingRow = maxRow + 1;
        var hasAdjacentStyledRow = java.util.stream.StreamSupport
                .stream(sheet.path("candidateCells").spliterator(), false)
                .anyMatch(cell -> cell.path("row").asInt(0) == trailingRow
                        && cell.path("column").asInt(0) >= minColumn
                        && cell.path("column").asInt(0) <= maxColumn);
        if (hasAdjacentStyledRow) maxRow = trailingRow;
        return new int[]{minColumn, minRow, maxColumn, maxRow};
    }

    private void addFormEnvelope(
            ArrayNode result, String sheetId, JsonNode sheet, List<JsonNode> semanticCells,
            int startColumn, int startRow, int endColumn, int endRow
    ) {
        if (endRow < startRow || formEvidenceCount(sheet, semanticCells, startRow, endRow, endColumn) < 2) return;
        var envelopeRange = range(startColumn, startRow, endColumn, endRow);
        var details = objectMapper.createObjectNode().put("recordAxis", "UNKNOWN");
        var surfaces = details.putArray("fieldSurfaces");
        for (var primitive : result) {
            if (!"FORM_REGION".equals(primitive.path("blockType").asText())
                    || !sheetId.equals(primitive.path("sheetId").asText(""))) continue;
            var primitiveRange = primitive.path("range").asText("");
            if (contains(envelopeRange, primitiveRange)) {
                surfaces.add(objectMapper.createObjectNode()
                        .put("range", primitiveRange)
                        .set("structure", primitive.path("structure").deepCopy()));
            }
        }
        var staticContents = details.putArray("staticContents");
        for (var cell : semanticCells) {
            var row = cell.path("row").asInt();
            var text = cell.path("value").asText("").strip();
            if (row < startRow || row > endRow || text.isBlank() || !isStaticNote(text)) continue;
            staticContents.add(objectMapper.createObjectNode()
                    .put("address", rangeFromCell(cell)).put("text", text).put("role", "STATIC_NOTE"));
        }
        for (int index = result.size() - 1; index >= 0; index--) {
            var primitive = result.get(index);
            if ("FORM_REGION".equals(primitive.path("blockType").asText())
                    && sheetId.equals(primitive.path("sheetId").asText(""))
                    && contains(envelopeRange, primitive.path("range").asText(""))) result.remove(index);
        }
        add(result, "FORM_REGION", sheetId, envelopeRange, details, 0.84,
                List.of("CONTIGUOUS_FORM_BAND", "MULTIPLE_LABEL_VALUE_SURFACES", "TABLE_BOUNDARY_ENVELOPE"));
    }

    private int formEvidenceCount(JsonNode sheet, List<JsonNode> semanticCells, int startRow, int endRow, int endColumn) {
        var candidates = new ArrayList<JsonNode>();
        sheet.path("candidateCells").forEach(candidates::add);
        var count = 0;
        for (var cell : semanticCells) {
            var row = cell.path("row").asInt();
            var column = cell.path("column").asInt();
            var text = cell.path("value").asText("").strip();
            if (row < startRow || row > endRow || text.isBlank() || isStaticNote(text) || text.length() > 24) continue;
            var delimiter = text.indexOf('：');
            if (delimiter < 0) delimiter = text.indexOf(':');
            if (delimiter > 0 && delimiter <= 12) {
                count++;
                continue;
            }
            var labelEnd = endColumn(cell, column);
            var blankRight = candidates.stream().anyMatch(candidate -> candidate.path("row").asInt() == row
                    && candidate.path("column").asInt() > labelEnd
                    && candidate.path("column").asInt() <= endColumn
                    && candidate.path("column").asInt() <= labelEnd + 3
                    && candidate.path("empty").asBoolean(true)
                    && hasBorderEvidence(candidate));
            if (blankRight) count++;
        }
        return count;
    }

    private boolean isStaticNote(String text) {
        return STATIC_PREFIXES.stream().anyMatch(text::startsWith) && text.length() >= 12;
    }

    private boolean isSectionFooterRow(
            List<JsonNode> cells, int row, int startColumn, int endColumn
    ) {
        var merged = cells.stream()
                .filter(cell -> cell.path("row").asInt() == row)
                .filter(cell -> !cell.path("value").asText("").strip().isBlank())
                .map(cell -> bounds(cell.path("mergedRange").asText("")))
                .filter(java.util.Objects::nonNull)
                .filter(range -> range[1] == range[3] && range[2] > range[0])
                .toList();
        if (merged.isEmpty() || merged.size() > 2) return false;
        var covered = new java.util.HashSet<Integer>();
        var widest = 0;
        for (var range : merged) {
            widest = Math.max(widest, range[2] - range[0] + 1);
            for (int column = Math.max(startColumn, range[0]); column <= Math.min(endColumn, range[2]); column++) {
                covered.add(column);
            }
        }
        var width = endColumn - startColumn + 1;
        return widest >= 3 && covered.size() >= Math.ceil(width * 0.8);
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
                        .put("recordAxis", "ROW")
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
                        && (!topologyV2Enabled || value[1] == value[3])
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

    private boolean contains(String outer, String inner) {
        var a = bounds(outer);
        var b = bounds(inner);
        return a != null && b != null && a[0] <= b[0] && a[1] <= b[1]
                && a[2] >= b[2] && a[3] >= b[3];
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

    private ObjectNode topologyEvidenceNode(TableTopologyClassifier.Evidence evidence) {
        return objectMapper.createObjectNode()
                .put("recordAxisEvidence", evidence.recordAxis())
                .put("blankIdentityBand", evidence.blankIdentityBand())
                .put("explicitColumnMemberCount", evidence.explicitColumnMemberCount())
                .put("rowLabelDepth", evidence.rowLabelDepth())
                .put("dataColumnCount", evidence.dataColumnCount())
                .put("bodyRowCount", evidence.bodyRowCount())
                .put("crossSurfacePresent", evidence.crossSurfacePresent())
                .put("runtimeColumnMemberSurface", evidence.runtimeColumnMemberSurface())
                .put("runtimeRowMemberSurface", evidence.runtimeRowMemberSurface())
                .put("runtimeColumnMemberRange", evidence.runtimeColumnMemberRange())
                .put("runtimeRowMemberRange", evidence.runtimeRowMemberRange())
                .put("crossDataRange", evidence.crossDataRange())
                .put("formulaTopologyPresent", evidence.formulaTopologyPresent())
                .put("confidence", 0.0);
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
                              int dataEndRow, int endRow, int totalRow, String totalLabel,
                              List<String> recordSlots) {
        int area() {
            return Math.max(0, endColumn - startColumn + 1)
                    * Math.max(0, endRow - headerRow + 1);
        }
    }
}
