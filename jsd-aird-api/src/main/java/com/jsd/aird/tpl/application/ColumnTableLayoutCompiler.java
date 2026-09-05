package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Compiles the physical layout of a column-oriented record table.
 *
 * <p>The compiler deliberately contains no workbook names, business labels or
 * fixed coordinates.  It derives four independent concepts from workbook
 * facts: the hierarchical label band, optional row-attribute columns, the
 * repeated record surface and vertically merged field groups.  A large
 * vertical merge therefore becomes a group inside one table instead of a new
 * table boundary.</p>
 */
public final class ColumnTableLayoutCompiler {

    private final ObjectMapper objectMapper;

    public ColumnTableLayoutCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Detects a large column-oriented record surface without using workbook
     * names, business terms or fixture coordinates.  The decisive geometry is
     * a low-text, structurally continuous right-hand column band paired with a
     * denser label band and one or more vertical field-group merges.
     *
     * <p>The returned proposal is still provisional.  This method only keeps
     * vertical group boundaries inside one physical component; it does not
     * make the physical proposal canonical or override model/user review.</p>
     */
    public ObjectNode detect(JsonNode sheet, String sheetId) {
        var used = bounds(sheet.path("usedRange").asText(""));
        if (used == null || used[2] - used[0] < 3 || used[3] - used[1] < 4) return null;
        var cells = cells(sheet);
        if (cells.isEmpty()) return null;

        // A bordered worksheet can contain an ordinary row table below a
        // metadata band.  The left metadata merge (for example A3:B3) and
        // the repeated borders on its right otherwise look like a column
        // identity band.  Prefer the stronger, dense-header/vertical-record
        // evidence of a row table before considering a column layout.  This
        // check is intentionally based on geometry and text density only so
        // it applies to all row-detail templates, not a named fixture.
        if (looksLikeRowOrientedSurface(cells, used)) return null;

        // A form often uses a vertically merged label beside a large,
        // horizontally merged writing area. Its physical borders make every
        // column look repeated, but there is no independent sample/record
        // column. Reject this topology before generic column-band detection.
        if (looksLikeFormWritingSurface(cells, used)) return null;

        var band = detectRepeatedColumnBand(cells, used);
        if (band == null) return null;
        var groups = physicalFieldGroups(cells, used, band.recordStart(), band.recordEnd());
        // A simple column table can have one label per row and therefore no
        // vertical group merge at all. The repeated right-hand column band is
        // already sufficient geometry; use the rows below its identity band
        // as the field surface in that case.
        int groupStart = groups.stream().mapToInt(FieldGroup::startRow).min().orElse(used[1] + 1);
        var groupEnd = groups.stream().mapToInt(FieldGroup::endRow).max().orElse(used[3]);
        var identityRow = canExtendIdentityRow(cells, used[0], band.recordStart(), band.recordEnd(), groupStart - 1)
                ? groupStart - 1 : groupStart;
        if (groups.isEmpty()) {
            var mergedIdentityRow = findMergedIdentityRow(cells, used, band);
            if (mergedIdentityRow > 0) {
                identityRow = mergedIdentityRow;
                groupStart = mergedIdentityRow + 1;
            }
        }
        // A record identity can occupy more than one row above the measure
        // band (for example an experiment number followed by a date).  Keep
        // the earliest explicit identity row inside the repeated component,
        // but do not absorb ordinary form rows merely because they share the
        // same bordered surface.
        while (identityRow > used[1]
                && canExtendIdentityRow(cells, used[0], band.recordStart(), band.recordEnd(), identityRow - 1)
                && hasRecordIdentityLabel(cells, used[0], band.recordStart(), identityRow - 1)) {
            identityRow--;
        }
        // Without vertical field groups, the identity band itself must prove
        // the label/record split.  A document title merged across the inferred
        // record columns is not a column-table header (production forms often
        // have exactly that shape above an ordinary ROW_TABLE).
        if (groups.isEmpty()
                && !hasSeparatedSimpleIdentity(cells, used[0], band.recordStart(), identityRow)) return null;
        var endRow = extendAndTrimEnd(cells, used, band.recordStart(), band.recordEnd(), groupEnd);
        if (endRow - identityRow < 3) return null;

        var region = objectMapper.createObjectNode()
                .put("sheetId", sheetId)
                .put("type", "COLUMN_TABLE")
                .put("range", range(used[0], identityRow, band.recordEnd(), endRow));
        var identityEndRow = Math.max(identityRow, groupStart - 1);
        region.putObject("structure")
                .put("recordAxis", "COLUMN")
                .put("repeatAxis", "COLUMN")
                .put("headerRange", range(used[0], identityRow, band.recordEnd(), identityEndRow))
                .put("dataRange", range(used[0], groupStart, band.recordEnd(), endRow))
                .put("recordHeight", Math.max(1, endRow - groupStart + 1))
                .put("recordWidth", 1)
                .put("recordStride", 1)
                .put("semanticMode", "COLUMN_RECORDS")
                .put("layoutCompiler", "COLUMN_LAYOUT_SIMPLE");
        region.with("structure")
                .put("canonicalStatus", "PROVISIONAL")
                .put("geometryStatus", "VALID_GEOMETRY")
                .put("classificationStatus", "HIGH")
                .put("layoutCompiler", "COLUMN_LAYOUT_V3");
        return region;
    }

    /**
     * Returns true when a dense textual header is followed by a contiguous
     * run of bordered rows.  Such a surface repeats records downward and must
     * be left to the ROW_TABLE detector.  Column tables have a sparse left
     * label band, so they do not satisfy the dense-header threshold.
     */
    private boolean looksLikeRowOrientedSurface(List<JsonNode> cells, int[] used) {
        int width = used[2] - used[0] + 1;
        if (width < 3 || used[3] - used[1] + 1 < 5) return false;
        var rows = new LinkedHashMap<Integer, List<JsonNode>>();
        for (var cell : cells) {
            var b = bounds(cellRange(cell));
            if (b == null || b[0] < used[0] || b[2] > used[2]
                    || b[1] < used[1] || b[3] > used[3]) continue;
            rows.computeIfAbsent(b[1], ignored -> new ArrayList<>()).add(cell);
        }
        var identityRows = new LinkedHashSet<Integer>();
        for (var entry : rows.entrySet()) {
            int candidateRow = entry.getKey();
            for (var left : entry.getValue()) {
                var leftBounds = bounds(cellRange(left));
                if (leftBounds == null || leftBounds[1] != candidateRow || leftBounds[3] != candidateRow
                        || leftBounds[0] != used[0] || leftBounds[2] - leftBounds[0] < 1
                        || text(left).isBlank() || formula(left)) continue;
                var rightHeaders = (int) entry.getValue().stream()
                        .filter(right -> {
                            var rightBounds = bounds(cellRange(right));
                            return rightBounds != null && rightBounds[1] == candidateRow
                                    && rightBounds[3] == candidateRow
                                    && rightBounds[0] > leftBounds[2]
                                    && rightBounds[2] <= used[2]
                                    && !text(right).isBlank() && !formula(right);
                        }).count();
                if (rightHeaders >= 3) {
                    identityRows.add(candidateRow);
                    break;
                }
            }
        }
        var identitySeen = false;
        for (int headerRow = used[1]; headerRow <= used[3] - 3; headerRow++) {
            // A merged left identity followed by populated sample titles is
            // positive COLUMN_TABLE evidence.  Rows beneath it can be dense
            // because the workbook is a completed report; do not reinterpret
            // those body rows as a row-table header.
            if (identityRows.contains(headerRow)) identitySeen = true;
            if (identitySeen) continue;
            int textHeaders = 0;
            boolean fullWidthTitle = false;
            for (var cell : rows.getOrDefault(headerRow, List.of())) {
                var b = bounds(cellRange(cell));
                if (b == null || b[1] != headerRow || b[3] != headerRow
                        || b[0] < used[0] || b[2] > used[2] || text(cell).isBlank()
                        || formula(cell)) continue;
                if (b[2] - b[0] + 1 >= Math.max(3, (int) Math.ceil(width * 0.80))) {
                    fullWidthTitle = true;
                    break;
                }
                // Count logical header cells rather than their merged span;
                // a title merged across the worksheet must not look like a
                // dense row-table header.
                textHeaders++;
            }
            if (fullWidthTitle) continue;
            if (textHeaders < Math.max(3, (int) Math.ceil(width * 0.55))) continue;

            int borderedRows = 0;
            int lastChecked = Math.min(used[3], headerRow + 8);
            for (int row = headerRow + 1; row <= lastChecked; row++) {
                if (rowHasStructuralSurface(cells, used[0], used[2], row)
                        >= Math.max(3, (int) Math.ceil(width * 0.70))) borderedRows++;
                else if (row > headerRow + 2) break;
            }
            if (borderedRows >= 3) return true;
        }
        return false;
    }

    private boolean looksLikeFormWritingSurface(List<JsonNode> cells, int[] used) {
        int wideWritingBlocks = 0;
        int labelledBlocks = 0;
        int usableWidth = used[2] - used[0] + 1;
        for (var valueCell : cells) {
            var valueBounds = bounds(cellRange(valueCell));
            if (valueBounds == null || valueBounds[0] <= used[0]
                    || valueBounds[2] - valueBounds[0] + 1
                    < Math.max(3, (int) Math.ceil(usableWidth * 0.45))
                    || valueBounds[3] - valueBounds[1] < 1
                    || !text(valueCell).isBlank()) continue;
            // A merged blank range is a writing/input surface. Plain blank
            // styled cells are intentionally not enough; they are common in
            // column tables with empty sample titles.
            if (!valueCell.path("mergedRange").isTextual()
                    || valueCell.path("mergedRange").asText().isBlank()) continue;
            wideWritingBlocks++;
            boolean adjacentLabel = false;
            for (var labelCell : cells) {
                var labelBounds = bounds(cellRange(labelCell));
                if (labelBounds == null || text(labelCell).isBlank()
                        || labelBounds[2] >= valueBounds[0]
                        || labelBounds[1] > valueBounds[3] || labelBounds[3] < valueBounds[1]) continue;
                // Labels spanning multiple rows denote form sections rather
                // than one-row sample/record identity headers.
                if (labelBounds[3] > labelBounds[1]) {
                    adjacentLabel = true;
                    break;
                }
            }
            if (adjacentLabel) labelledBlocks++;
        }
        // Two independent section writing blocks are strong form evidence. A
        // single merged block may still be a legitimate grouped table header.
        return wideWritingBlocks >= 2 && labelledBlocks >= 2;
    }

    private boolean hasSeparatedSimpleIdentity(
            List<JsonNode> cells, int labelStart, int recordStart, int identityRow
    ) {
        var hasLabel = false;
        for (var cell : cells) {
            var cellBounds = bounds(cellRange(cell));
            if (cellBounds == null || cellBounds[1] > identityRow || cellBounds[3] < identityRow
                    || text(cell).isBlank()) continue;
            if (cellBounds[0] < recordStart && cellBounds[2] >= recordStart) return false;
            if (cellBounds[0] >= labelStart && cellBounds[2] < recordStart) hasLabel = true;
        }
        return hasLabel;
    }

    /** Enriches an already proposed COLUMN_TABLE. Returns false when the split cannot be proven. */
    public boolean enrich(ObjectNode region, JsonNode facts) {
        var total = bounds(region.path("range").asText(""));
        if (total == null || total[2] - total[0] < 2 || total[3] - total[1] < 2) return false;
        var sheetId = region.path("sheetId").asText(region.path("locator").path("sheetId").asText(""));
        var cells = cells(facts, sheetId);
        if (cells.isEmpty()) return false;

        var structure = region.with("structure");
        var existingHeader = bounds(structure.path("headerRange").asText(""));
        var existingData = bounds(structure.path("dataRange").asText(""));
        // A determinate physical proposal has already compiled the record
        // identity band and measure surface from the complete workbook.  This
        // enrichment pass is also used for model-only COLUMN_TABLE proposals,
        // but it must not collapse a proven multi-row identity band back to
        // the first row.  Doing so moved rows such as 实验编号/日期 out of the
        // repeated region's header and reduced the physical field projection.
        if ((region.path("physicalConfirmed").asBoolean(false)
                || structure.path("physicalConfirmed").asBoolean(false))
                && contained(existingHeader, total)
                && contained(existingData, total)
                && existingHeader[3] < existingData[1]) {
            structure.put("recordAxis", "COLUMN")
                    .put("repeatAxis", "COLUMN")
                    .put("recordWidth", Math.max(1, structure.path("recordWidth").asInt(1)))
                    .put("recordStride", Math.max(1, structure.path("recordStride").asInt(1)))
                    .put("semanticMode", "COLUMN_RECORDS")
                    .put("layoutCompiler", "COLUMN_LAYOUT_V3");
            region.put("recordAxis", "COLUMN");
            return true;
        }
        var identityRow = total[1];
        if (identityRow < total[1] || identityRow >= total[3]) identityRow = total[1];

        var split = inferBands(cells, total, identityRow, -1);
        if (split == null || split.recordStart() <= total[0] || split.recordStart() > total[2]) return false;

        var valueStartRow = identityRow + 1;
        var valueEndRow = total[3];
        if (valueStartRow > valueEndRow) return false;

        structure.put("recordAxis", "COLUMN")
                .put("repeatAxis", "COLUMN")
                .put("headerRange", range(total[0], identityRow, total[2], identityRow))
                .put("dataRange", range(total[0], valueStartRow, total[2], valueEndRow))
                .put("recordHeight", Math.max(1, valueEndRow - valueStartRow + 1))
                .put("recordWidth", 1)
                .put("recordStride", 1)
                .put("semanticMode", "COLUMN_RECORDS")
                .put("layoutCompiler", "COLUMN_LAYOUT_SIMPLE");
        region.put("recordAxis", "COLUMN");
        return true;
    }

    private boolean contained(int[] inner, int[] outer) {
        return inner != null && outer != null
                && inner[0] >= outer[0] && inner[1] >= outer[1]
                && inner[2] <= outer[2] && inner[3] <= outer[3];
    }

    private BandSplit inferBands(List<JsonNode> cells, int[] total, int identityRow, int explicitRecordStart) {
        var mergedIdentityEnd = total[0] - 1;
        for (var cell : cells) {
            var cellBounds = bounds(cellRange(cell));
            if (cellBounds == null || cellBounds[1] != identityRow || cellBounds[3] != identityRow
                    || cellBounds[0] != total[0] || text(cell).isBlank()) continue;
            mergedIdentityEnd = Math.max(mergedIdentityEnd, cellBounds[2]);
        }

        var recordStart = explicitRecordStart > total[0] && explicitRecordStart <= total[2]
                ? explicitRecordStart : -1;
        // Prefer a contiguous right-hand runtime input surface.  A single
        // populated attribute column before it remains outside the record set.
        for (int candidate = total[0] + 1; recordStart < 0 && candidate <= total[2] - 1; candidate++) {
            if (!runtimeColumnEvidence(cells, candidate, identityRow, total[3])) continue;
            var supported = 0;
            var width = total[2] - candidate + 1;
            for (int column = candidate; column <= total[2]; column++) {
                if (runtimeColumnEvidence(cells, column, identityRow, total[3])) supported++;
            }
            if (supported >= Math.max(2, (int) Math.ceil(width * 0.7))) {
                recordStart = candidate;
                break;
            }
        }
        // Blank runtime identity cells may have no semantic-cell rows.  In that
        // case the last standalone non-empty header before a blank right suffix
        // is an attribute, and the suffix is the repeated record surface.
        if (recordStart < 0) {
            for (int candidate = Math.max(total[0] + 1, mergedIdentityEnd + 1);
                 candidate <= total[2] - 1; candidate++) {
                var blank = 0;
                for (int column = candidate; column <= total[2]; column++) {
                    if (textAt(cells, column, identityRow).isBlank()) blank++;
                }
                if (blank >= Math.max(2, (int) Math.ceil((total[2] - candidate + 1) * 0.8))) {
                    recordStart = candidate;
                    break;
                }
            }
        }
        if (recordStart < 0) return null;

        // A horizontal merged identity followed by a standalone header is the
        // reliable shape of a row-attribute column. A vertical field-group
        // merge is different: its following leaf-label column remains part of
        // labelPath and must not be reclassified as an attribute.
        var labelEnd = recordStart - 1;
        if (mergedIdentityEnd >= total[0] && mergedIdentityEnd < recordStart - 1) {
            labelEnd = mergedIdentityEnd;
        }
        if (labelEnd >= recordStart) return null;
        return new BandSplit(labelEnd, recordStart);
    }

    private RepeatedColumnBand detectRepeatedColumnBand(List<JsonNode> cells, int[] used) {
        var height = used[3] - used[1] + 1;
        var stats = new LinkedHashMap<Integer, ColumnStats>();
        for (int column = used[0]; column <= used[2]; column++) {
            var structuralRows = new LinkedHashSet<Integer>();
            var textRows = new LinkedHashSet<Integer>();
            var valueRows = new LinkedHashSet<Integer>();
            for (var cell : cells) {
                var b = bounds(cellRange(cell));
                if (b == null || column < b[0] || column > b[2]) continue;
                for (int row = Math.max(used[1], b[1]); row <= Math.min(used[3], b[3]); row++) {
                    if (structural(cell)) structuralRows.add(row);
                    if (!text(cell).isBlank()) textRows.add(row);
                    if (formula(cell) || cell.path("inputCandidate").asBoolean(false)) valueRows.add(row);
                }
            }
            stats.put(column, new ColumnStats(structuralRows.size(), textRows.size(), valueRows.size(), height));
        }

        // Completed workbooks often have no inputCandidate/formula markers:
        // their bordered cells are present only in the physical candidate
        // snapshot.  A merged label immediately followed by three or more
        // continuous bordered/value columns is deterministic column-table
        // evidence and does not depend on whether the sample titles are blank.
        var identityBand = detectMergedIdentityBand(cells, used);
        if (identityBand != null) return identityBand;

        var recordStart = -1;
        var bestSeparation = 0.0;
        for (int candidate = used[0] + 1; candidate <= used[2] - 2; candidate++) {
            var current = stats.get(candidate);
            var previous = stats.get(candidate - 1);
            if (current == null || previous == null || !current.repeatedSurface()) continue;
            if (!stats.get(candidate + 1).repeatedSurface() || !stats.get(candidate + 2).repeatedSurface()) continue;
            var separation = previous.textDensity() - current.textDensity();
            if (current.textDensity() > 0.25 || previous.textDensity() < 0.18 || separation < 0.12) continue;
            if (recordStart < 0 || separation > bestSeparation) {
                recordStart = candidate;
                bestSeparation = separation;
            }
        }
        if (recordStart < 0) return null;
        var recordEnd = recordStart - 1;
        for (int column = recordStart; column <= used[2]; column++) {
            if (!stats.get(column).repeatedSurface()) break;
            recordEnd = column;
        }
        return recordEnd - recordStart + 1 >= 3 ? new RepeatedColumnBand(recordStart, recordEnd) : null;
    }

    private RepeatedColumnBand detectMergedIdentityBand(List<JsonNode> cells, int[] used) {
        var row = findMergedIdentityRow(cells, used, null);
        return row > 0 ? new RepeatedColumnBand(identityRecordStart(cells, used, row), used[2]) : null;
    }

    private int findMergedIdentityRow(List<JsonNode> cells, int[] used, RepeatedColumnBand expectedBand) {
        var expectedStart = expectedBand == null ? -1 : expectedBand.recordStart();
        for (int row = used[1]; row <= used[3] - 3; row++) {
            for (var cell : cells) {
                var bounds = bounds(cellRange(cell));
                if (bounds == null || bounds[1] != row || bounds[3] != row
                        || bounds[0] != used[0] || bounds[2] >= used[2] - 1
                        || bounds[2] - bounds[0] < 1 || text(cell).isBlank()
                        || (!recordIdentityText(text(cell)) && !identityBandLabel(text(cell), bounds))) continue;
                var recordStart = bounds[2] + 1;
                if (expectedStart > 0 && recordStart != expectedStart) continue;
                var recordEnd = used[2];
                if (recordEnd - recordStart + 1 < 3) continue;
                var headerSurface = rowHasStructuralSurface(cells, recordStart, recordEnd, row);
                if (headerSurface < 3) continue;
                var bodyRows = 0;
                for (int bodyRow = row + 1; bodyRow <= used[3]; bodyRow++) {
                    var rightSurface = rowHasStructuralSurface(cells, recordStart, recordEnd, bodyRow);
                    var leftLabels = countTextCells(cells, used[0], recordStart - 1, bodyRow);
                    if (rightSurface >= Math.ceil((recordEnd - recordStart + 1) * 0.7)
                            && leftLabels > 0) bodyRows++;
                }
                if (bodyRows >= 3) return row;
            }
        }
        return -1;
    }

    private int identityRecordStart(List<JsonNode> cells, int[] used, int row) {
        for (var cell : cells) {
            var bounds = bounds(cellRange(cell));
            if (bounds != null && bounds[0] == used[0] && bounds[1] == row && bounds[3] == row
                    && bounds[2] - bounds[0] >= 1) return bounds[2] + 1;
        }
        return used[0] + 1;
    }

    private boolean identityBandLabel(String value, int[] bounds) {
        // A horizontally merged left label is the physical separator between
        // metadata and repeated records.  It is intentionally accepted even
        // when its business wording is unknown; unmerged labels still need
        // explicit identity semantics (编号/样品/配方/名称, etc.).
        return bounds != null && bounds[2] - bounds[0] >= 1;
    }

    private int rowHasStructuralSurface(List<JsonNode> cells, int startColumn, int endColumn, int row) {
        var covered = 0;
        for (int column = startColumn; column <= endColumn; column++) {
            for (var cell : cells) {
                var bounds = bounds(cellRange(cell));
                if (bounds != null && bounds[0] <= column && bounds[2] >= column
                        && bounds[1] <= row && bounds[3] >= row &&
                        (structural(cell) || !text(cell).isBlank())) {
                    covered++;
                    break;
                }
            }
        }
        return covered;
    }

    private int countTextCells(List<JsonNode> cells, int startColumn, int endColumn, int row) {
        var count = 0;
        for (var cell : cells) {
            var bounds = bounds(cellRange(cell));
            if (bounds != null && bounds[0] >= startColumn && bounds[2] <= endColumn
                    && bounds[1] <= row && bounds[3] >= row && !text(cell).isBlank()) count++;
        }
        return count;
    }

    private List<FieldGroup> physicalFieldGroups(
            List<JsonNode> cells, int[] used, int recordStart, int recordEnd
    ) {
        var result = new ArrayList<FieldGroup>();
        for (var cell : cells) {
            var b = bounds(cellRange(cell));
            var value = text(cell);
            if (b == null || value.isBlank() || b[3] - b[1] < 1
                    || b[0] < used[0] || b[2] >= recordStart) continue;
            var leafLabels = 0;
            for (int row = b[1]; row <= b[3]; row++) {
                if (hasLeafLabel(cells, b[2] + 1, recordStart - 1, row)
                        && rowHasRecordSurface(cells, recordStart, recordEnd, row)) leafLabels++;
            }
            if (leafLabels == 0) continue;
            result.add(new FieldGroup(value, cellRange(cell), b[1], b[3]));
        }
        result.sort(Comparator.comparingInt(FieldGroup::startRow).thenComparingInt(FieldGroup::endRow));
        return result;
    }

    private boolean hasLeafLabel(List<JsonNode> cells, int startColumn, int endColumn, int row) {
        if (startColumn > endColumn) return false;
        for (var cell : cells) {
            var b = bounds(cellRange(cell));
            if (b == null || text(cell).isBlank() || b[1] > row || b[3] < row) continue;
            if (b[0] >= startColumn && b[0] <= endColumn && b[2] <= endColumn) return true;
        }
        return false;
    }

    private boolean hasSpecificRowLabel(List<JsonNode> cells, int startColumn, int endColumn, int row) {
        for (var cell : cells) {
            var b = bounds(cellRange(cell));
            if (b == null || text(cell).isBlank() || b[0] < startColumn || b[0] > endColumn
                    || b[1] > row || b[3] < row || b[2] > endColumn) continue;
            // A vertical merge is a field-group label. It contributes to the
            // path but is not by itself a field row; a leaf label on the same
            // row is required to materialize a business field. A nested
            // vertical merge that reaches the right edge of the label band is
            // itself a leaf (for example one test item spanning several test
            // methods); materialize one field per physical row so the row
            // attribute can distinguish those values. A merge spanning the
            // whole label band from its left edge remains a group only.
            // A vertical parent must not become one field per row merely
            // because another vertical merge exists to its right. That shape
            // is an unnamed runtime row group (for example several recipe
            // slots under one merged "实验配方" label), not repeated copies of
            // the same semantic field. A real row is proven by a single-row
            // leaf, or by a nested vertical leaf that reaches the right edge
            // of the label band; in the latter case row attributes distinguish
            // the physical rows.
            if (b[3] == b[1]
                    || (b[2] == endColumn && b[0] > startColumn)) return true;
        }
        return false;
    }

    private boolean rowHasRecordSurface(List<JsonNode> cells, int startColumn, int endColumn, int row) {
        var covered = 0;
        for (int column = startColumn; column <= endColumn; column++) {
            if (structuralAt(cells, column, row)) covered++;
        }
        return covered >= Math.ceil((endColumn - startColumn + 1) * 0.7);
    }

    private boolean canExtendIdentityRow(
            List<JsonNode> cells, int labelStart, int recordStart, int recordEnd, int row
    ) {
        if (row <= 0 || !rowHasRecordSurface(cells, recordStart, recordEnd, row)) return false;
        var hasLabel = false;
        for (var cell : cells) {
            var b = bounds(cellRange(cell));
            if (b == null || b[1] > row || b[3] < row || text(cell).isBlank()) continue;
            if (b[0] < recordStart) hasLabel = true;
            if (b[0] < recordStart && b[2] >= recordStart) return false;
        }
        return hasLabel;
    }

    private boolean hasRecordIdentityLabel(
            List<JsonNode> cells, int labelStart, int recordStart, int row
    ) {
        for (var cell : cells) {
            var b = bounds(cellRange(cell));
            if (b == null || b[1] > row || b[3] < row || b[0] < labelStart || b[0] >= recordStart) continue;
            if (recordIdentityText(text(cell))) return true;
        }
        return false;
    }

    private boolean recordIdentityText(String value) {
        var normalized = value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return false;
        if (normalized.matches(".*(编号|序号|批号|实验号|试验号|样品号|试样号|树脂号).*")) return true;
        if ((normalized.contains("样品") || normalized.contains("试样"))
                && (normalized.contains("配方") || normalized.contains("树脂")
                || normalized.contains("实验") || normalized.contains("试验")
                || normalized.contains("名称"))) return true;
        return normalized.matches(".*\\b(sample|specimen|trial|experiment|batch)"
                + "([_-]?(id|no|number|code))?\\b.*");
    }

    private int extendAndTrimEnd(
            List<JsonNode> cells, int[] used, int recordStart, int recordEnd, int groupEnd
    ) {
        var end = Math.max(groupEnd, used[1]);
        for (int row = groupEnd + 1; row <= used[3]; row++) {
            var hasLeftLabel = hasLeafLabel(cells, used[0], recordStart - 1, row);
            final var currentRow = row;
            var crossesRecordSurface = cells.stream().anyMatch(cell -> {
                var b = bounds(cellRange(cell));
                return b != null && b[1] <= currentRow && b[3] >= currentRow && b[0] < recordStart
                        && b[2] >= recordStart && !text(cell).isBlank();
            });
            if (crossesRecordSurface) break;
            if (hasLeftLabel && rowHasRecordSurface(cells, recordStart, recordEnd, row)) end = row;
        }
        return end;
    }

    private RowValueRole rowValueRole(List<JsonNode> cells, int startColumn, int endColumn, int row) {
        var formulas = 0;
        var cached = 0;
        for (int column = startColumn; column <= endColumn; column++) {
            var cell = cellAt(cells, column, row);
            if (!formula(cell)) continue;
            formulas++;
            if (cell.has("cachedValue") || cell.has("calculatedValue")
                    || (!cell.path("displayValue").asText("").isBlank()
                    && !cell.path("displayValue").asText("").startsWith("="))) cached++;
        }
        var derived = formulas >= Math.max(1, (int) Math.ceil((endColumn - startColumn + 1) * 0.5));
        return new RowValueRole(derived, !derived ? "NOT_APPLICABLE"
                : cached == formulas ? "CACHED_VALUE_PRESENT" : "RECALCULATION_REQUIRED");
    }

    private JsonNode cellAt(List<JsonNode> cells, int column, int row) {
        for (var cell : cells) {
            var b = bounds(cellRange(cell));
            if (b != null && b[0] <= column && b[2] >= column && b[1] <= row && b[3] >= row) return cell;
        }
        return objectMapper.missingNode();
    }

    private boolean structuralAt(List<JsonNode> cells, int column, int row) {
        for (var cell : cells) {
            var b = bounds(cellRange(cell));
            if (b != null && b[0] <= column && b[2] >= column && b[1] <= row && b[3] >= row
                    && structural(cell)) return true;
        }
        return false;
    }

    private boolean structural(JsonNode cell) {
        return cell.path("hasBorder").asBoolean(false)
                || cell.path("style").path("bd").isObject()
                || cell.path("inputCandidate").asBoolean(false)
                || formula(cell)
                || !cell.path("mergedRange").asText("").isBlank();
    }

    private boolean formula(JsonNode cell) {
        return cell != null && !cell.isMissingNode() && (cell.path("formula").asBoolean(false)
                || cell.path("formula").isTextual()
                || "FORMULA".equalsIgnoreCase(cell.path("factType").asText(""))
                || "FORMULA".equalsIgnoreCase(cell.path("valueType").asText(""))
                || cell.path("value").asText("").stripLeading().startsWith("="));
    }

    private List<JsonNode> cells(JsonNode sheet) {
        var result = new LinkedHashMap<String, JsonNode>();
        // Candidate/physical snapshots carry border and input facts that are
        // absent from semantic cells in completed workbooks. Prefer them when
        // the same address occurs in more than one collection.
        for (var key : List.of("candidateCells", "physicalCells", "semanticCells")) {
            for (var cell : sheet.path(key)) {
                var identity = cell.path("address").asText(cellRange(cell));
                result.putIfAbsent(identity.toUpperCase(Locale.ROOT), cell);
            }
        }
        return new ArrayList<>(result.values());
    }

    private boolean runtimeColumnEvidence(List<JsonNode> cells, int column, int identityRow, int endRow) {
        var structural = 0;
        var input = 0;
        var nonBlank = 0;
        for (var cell : cells) {
            var b = bounds(cellRange(cell));
            if (b == null || column < b[0] || column > b[2] || b[3] < identityRow || b[1] > endRow) continue;
            structural++;
            if (cell.path("inputCandidate").asBoolean(false)
                    || "FORMULA".equals(cell.path("factType").asText(""))
                    || cell.path("formula").isTextual()) input++;
            if (!text(cell).isBlank()) nonBlank++;
        }
        if (input >= 2) return true;
        var headerBlank = textAt(cells, column, identityRow).isBlank();
        return headerBlank && structural >= 2 && nonBlank <= Math.max(1, structural / 3);
    }

    private List<FieldGroup> fieldGroups(
            List<JsonNode> cells, int labelStart, int labelEnd, int startRow, int endRow
    ) {
        var result = new ArrayList<FieldGroup>();
        for (var cell : cells) {
            var b = bounds(cellRange(cell));
            var value = text(cell);
            if (b == null || value.isBlank() || b[3] <= b[1]
                    || b[0] < labelStart || b[0] > labelEnd
                    || b[1] < startRow || b[3] > endRow) continue;
            // A group starts at the left edge of the current label hierarchy.
            // Nested vertical merges remain children and are represented by
            // labelPath, not competing table components.
            if (b[0] != labelStart) continue;
            result.add(new FieldGroup(value, cellRange(cell), b[1], b[3]));
        }
        result.sort(Comparator.comparingInt(FieldGroup::startRow).thenComparingInt(FieldGroup::endRow));
        return result;
    }

    private List<String> labelPath(List<JsonNode> cells, int startColumn, int endColumn, int row) {
        var byColumn = new LinkedHashMap<Integer, String>();
        cells.stream().filter(cell -> bounds(cellRange(cell)) != null)
                .sorted(Comparator.comparingInt(cell -> bounds(cellRange(cell))[0])).forEach(cell -> {
            var b = bounds(cellRange(cell));
            var value = text(cell);
            if (b == null || value.isBlank() || b[1] > row || b[3] < row
                    || b[0] < startColumn || b[0] > endColumn) return;
            byColumn.putIfAbsent(b[0], value);
        });
        return new ArrayList<>(new LinkedHashSet<>(byColumn.values()));
    }

    private String coveringLabelRange(List<JsonNode> cells, int startColumn, int endColumn, int row) {
        var ranges = new ArrayList<int[]>();
        for (var cell : cells) {
            var b = bounds(cellRange(cell));
            if (b != null && !text(cell).isBlank() && b[1] <= row && b[3] >= row
                    && b[0] >= startColumn && b[0] <= endColumn) ranges.add(b);
        }
        if (ranges.isEmpty()) return "";
        var left = ranges.stream().mapToInt(value -> value[0]).min().orElse(startColumn);
        var right = ranges.stream().mapToInt(value -> value[2]).max().orElse(endColumn);
        return range(left, row, Math.min(right, endColumn), row);
    }

    private List<JsonNode> cells(JsonNode facts, String sheetId) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (var sheet : facts.path("sheets")) {
            var id = sheet.path("sheetId").asText(sheet.path("id").asText(""));
            if (!sheetId.equals(id)) continue;
            for (var key : List.of("candidateCells", "physicalCells", "semanticCells")) {
                for (var cell : sheet.path(key)) {
                    var identity = cell.path("address").asText(cellRange(cell));
                    result.putIfAbsent(identity.toUpperCase(Locale.ROOT), cell);
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    private String textAt(List<JsonNode> cells, int column, int row) {
        for (var cell : cells) {
            var b = bounds(cellRange(cell));
            if (b != null && b[0] <= column && b[2] >= column && b[1] <= row && b[3] >= row) {
                var value = text(cell);
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }

    private String text(JsonNode cell) {
        if (cell.path("formula").asBoolean(false)
                || cell.path("formula").isTextual()
                || "FORMULA".equalsIgnoreCase(cell.path("factType").asText(""))
                || "FORMULA".equalsIgnoreCase(cell.path("valueType").asText(""))) return "";
        var value = cell.path("value").asText("");
        if (value.isBlank()) value = cell.path("displayValue").asText("");
        if (value.startsWith("=")) return "";
        return value.replaceAll("[\\r\\n]+", " ").strip();
    }

    private String cellRange(JsonNode cell) {
        return cell.path("mergedRange").asText(cell.path("address").asText(""));
    }

    private String stableKey(String prefix, String label, int start, int end) {
        return prefix + "-" + Integer.toUnsignedString((label + "|" + start + "|" + end).hashCode(), 36);
    }

    private String range(int left, int top, int right, int bottom) {
        return address(left, top) + ":" + address(right, bottom);
    }

    private String address(int column, int row) {
        return columnName(column) + row;
    }

    private String columnName(int column) {
        var value = new StringBuilder();
        for (int current = column; current > 0; current = (current - 1) / 26) {
            value.append((char) ('A' + (current - 1) % 26));
        }
        return value.reverse().toString();
    }

    private int[] bounds(String value) {
        if (value == null || value.isBlank()) return null;
        var parts = value.replace("$", "").split(":", 2);
        var first = cell(parts[0]);
        var last = cell(parts.length == 2 ? parts[1] : parts[0]);
        if (first == null || last == null) return null;
        return new int[]{Math.min(first[0], last[0]), Math.min(first[1], last[1]),
                Math.max(first[0], last[0]), Math.max(first[1], last[1])};
    }

    private int[] cell(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        var matcher = java.util.regex.Pattern.compile("([A-Z]+)([0-9]+)").matcher(normalized);
        if (!matcher.matches()) return null;
        var column = 0;
        for (var character : matcher.group(1).toCharArray()) column = column * 26 + character - 'A' + 1;
        return new int[]{column, Integer.parseInt(matcher.group(2))};
    }

    private record BandSplit(int labelEnd, int recordStart) {}

    private record FieldGroup(String label, String labelRange, int startRow, int endRow) {}

    private record RepeatedColumnBand(int recordStart, int recordEnd) {}

    private record RowValueRole(boolean formulaDerived, String trustStatus) {}

    private record ColumnStats(int structuralRows, int textRows, int valueRows, int height) {
        private double structuralDensity() { return structuralRows / (double) Math.max(1, height); }
        private double textDensity() { return textRows / (double) Math.max(1, height); }
        private boolean repeatedSurface() {
            return structuralDensity() >= 0.45 && (valueRows > 0 || structuralDensity() >= 0.65);
        }
    }
}
