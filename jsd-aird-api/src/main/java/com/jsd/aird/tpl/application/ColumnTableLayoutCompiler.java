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

        var band = detectRepeatedColumnBand(cells, used);
        if (band == null) return null;
        var groups = physicalFieldGroups(cells, used, band.recordStart(), band.recordEnd());
        if (groups.isEmpty()) return null;

        var groupStart = groups.stream().mapToInt(FieldGroup::startRow).min().orElse(used[1]);
        var groupEnd = groups.stream().mapToInt(FieldGroup::endRow).max().orElse(used[3]);
        var identityRow = canExtendIdentityRow(cells, used[0], band.recordStart(), band.recordEnd(), groupStart - 1)
                ? groupStart - 1 : groupStart;
        var endRow = extendAndTrimEnd(cells, used, band.recordStart(), band.recordEnd(), groupEnd);
        if (endRow - identityRow < 3) return null;

        var region = objectMapper.createObjectNode()
                .put("sheetId", sheetId)
                .put("type", "COLUMN_TABLE")
                .put("range", range(used[0], identityRow, band.recordEnd(), endRow));
        region.putObject("structure").putObject("recordProjection")
                .put("identityRow", identityRow)
                .put("valueStartRow", groupStart)
                .put("valueEndRow", endRow)
                .put("recordStartColumn", band.recordStart())
                .put("recordEndColumn", band.recordEnd());
        var wrapped = objectMapper.createObjectNode();
        wrapped.putArray("sheets").add(sheet.deepCopy());
        if (!enrich(region, wrapped)) return null;
        region.with("structure")
                .put("canonicalStatus", "PROVISIONAL")
                .put("geometryStatus", "VALID_GEOMETRY")
                .put("classificationStatus", "HIGH")
                .put("layoutCompiler", "COLUMN_LAYOUT_V3");
        return region;
    }

    /** Enriches an already proposed COLUMN_TABLE. Returns false when the split cannot be proven. */
    public boolean enrich(ObjectNode region, JsonNode facts) {
        var total = bounds(region.path("range").asText(""));
        if (total == null || total[2] - total[0] < 2 || total[3] - total[1] < 2) return false;
        var sheetId = region.path("sheetId").asText(region.path("locator").path("sheetId").asText(""));
        var cells = cells(facts, sheetId);
        if (cells.isEmpty()) return false;

        var structure = region.with("structure");
        var identityRow = structure.path("recordProjection").path("identityRow").asInt(total[1]);
        if (identityRow < total[1] || identityRow >= total[3]) identityRow = total[1];

        var split = inferBands(cells, total, identityRow,
                structure.path("recordProjection").path("recordStartColumn").asInt(-1));
        if (split == null || split.recordStart() <= total[0] || split.recordStart() > total[2]) return false;

        var requestedValueStart = structure.path("recordProjection").path("valueStartRow")
                .asInt(identityRow + 1);
        var valueStartRow = Math.max(identityRow, requestedValueStart);
        var valueEndRow = Math.min(total[3],
                structure.path("recordProjection").path("valueEndRow").asInt(total[3]));
        if (valueStartRow > valueEndRow) return false;

        structure.put("recordAxis", "COLUMN")
                .put("repeatAxis", "COLUMN")
                .put("headerRange", range(total[0], identityRow, total[2], identityRow))
                .put("dataRange", range(total[0], valueStartRow, total[2], valueEndRow))
                .put("rowHeaderRange", range(total[0], valueStartRow, split.recordStart() - 1, valueEndRow))
                .put("columnHeaderRange", range(split.recordStart(), identityRow, total[2], identityRow))
                .put("crossDataRange", range(split.recordStart(), valueStartRow, total[2], valueEndRow))
                .put("semanticMode", "COLUMN_RECORDS")
                .put("layoutCompiler", "COLUMN_LAYOUT_V2");

        var projection = structure.putObject("recordProjection")
                .put("mode", "COLUMN_RECORDS")
                .put("recordAxis", "COLUMN")
                .put("identityRow", identityRow)
                .put("valueStartRow", valueStartRow)
                .put("valueEndRow", valueEndRow)
                .put("labelBandRange", range(total[0], identityRow, split.labelEnd(), valueEndRow))
                .put("runtimeColumnMemberRange", range(split.recordStart(), identityRow, total[2], identityRow));
        projection.putArray("recordColumns").removeAll();
        for (int column = split.recordStart(); column <= total[2]; column++) {
            projection.withArray("recordColumns").add(columnName(column));
        }

        var identity = projection.putObject("recordIdentity")
                .put("strategy", "RUNTIME_COLUMN_MEMBER")
                .put("labelRange", range(total[0], identityRow, split.labelEnd(), identityRow))
                .put("valueRange", range(split.recordStart(), identityRow, total[2], identityRow));

        var rowAttributes = projection.putArray("rowAttributeColumns");
        rowAttributes.removeAll();
        for (int column = split.labelEnd() + 1; column < split.recordStart(); column++) {
            var header = textAt(cells, column, identityRow);
            rowAttributes.add(objectMapper.createObjectNode()
                    .put("column", columnName(column))
                    .put("role", "ROW_ATTRIBUTE")
                    .put("label", header)
                    .put("labelRange", address(column, identityRow))
                    .put("valueRange", range(column, valueStartRow, column, valueEndRow)));
        }

        var groups = structure.putArray("fieldGroups");
        groups.removeAll();
        for (var group : fieldGroups(cells, total[0], split.labelEnd(), valueStartRow, valueEndRow)) {
            groups.add(objectMapper.createObjectNode()
                    .put("groupId", stableKey("group", group.label(), group.startRow(), group.endRow()))
                    .put("label", group.label())
                    .put("labelRange", group.labelRange())
                    .put("range", range(total[0], group.startRow(), total[2], group.endRow()))
                    .put("startRow", group.startRow())
                    .put("endRow", group.endRow()));
        }

        var rows = structure.putArray("fieldRows");
        rows.removeAll();
        for (int row = valueStartRow; row <= valueEndRow; row++) {
            var path = labelPath(cells, total[0], split.labelEnd(), row);
            if (path.isEmpty() || !hasSpecificRowLabel(cells, total[0], split.labelEnd(), row)) continue;
            var item = objectMapper.createObjectNode()
                    .put("row", row)
                    .put("labelPath", String.join(" > ", path))
                    .put("labelRange", coveringLabelRange(cells, total[0], split.labelEnd(), row))
                    .put("valueRange", range(split.recordStart(), row, total[2], row));
            var valueRole = rowValueRole(cells, split.recordStart(), total[2], row);
            item.put("valueSource", valueRole.formulaDerived() ? "FORMULA" : "USER_INPUT")
                    .put("editability", valueRole.formulaDerived() ? "READ_ONLY" : "EDITABLE")
                    .put("trainingEligible", !valueRole.formulaDerived())
                    .put("trainingRole", valueRole.formulaDerived() ? "EXCLUDE" : "FEATURE")
                    .put("formulaDerived", valueRole.formulaDerived())
                    .put("calculationTrustStatus", valueRole.trustStatus());
            var pathArray = item.putArray("labelPathSegments");
            path.forEach(pathArray::add);
            var attributes = item.putArray("rowAttributes");
            for (int column = split.labelEnd() + 1; column < split.recordStart(); column++) {
                var value = textAt(cells, column, row);
                if (value.isBlank()) continue;
                attributes.add(objectMapper.createObjectNode()
                        .put("column", columnName(column))
                        .put("role", "ROW_ATTRIBUTE")
                        .put("label", textAt(cells, column, identityRow))
                        .put("value", value)
                        .put("address", address(column, row)));
            }
            rows.add(item);
        }
        region.put("recordAxis", "COLUMN");
        return true;
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
        for (var key : List.of("semanticCells", "candidateCells", "physicalCells")) {
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
            for (var key : List.of("semanticCells", "candidateCells", "physicalCells")) {
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
