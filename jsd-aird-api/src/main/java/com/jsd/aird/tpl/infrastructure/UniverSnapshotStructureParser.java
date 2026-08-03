package com.jsd.aird.tpl.infrastructure;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import com.jsd.aird.tpl.application.port.WorkbookSnapshotStructureParser;
import org.springframework.stereotype.Component;

/** Converts a persisted Univer workbook snapshot into the same compact structure used by XLSX recognition. */
@Component
public class UniverSnapshotStructureParser implements WorkbookSnapshotStructureParser {

    private final ObjectMapper objectMapper;

    public UniverSnapshotStructureParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public OfficeStructureParser.ParseResult parse(InputStream input) {
        try {
            var snapshot = objectMapper.readTree(input);
            if (!snapshot.isObject() || !snapshot.path("sheets").isObject()) {
                throw new IllegalArgumentException("Univer 工作簿快照缺少 sheets 数据");
            }
            if (snapshot.path("snapshotFormatVersion").asInt() != 3) {
                throw new IllegalArgumentException("Excel 快照版本必须为 3");
            }
            var summary = objectMapper.createObjectNode();
            var sheets = objectMapper.createArrayNode();
            var candidateCells = objectMapper.createArrayNode();
            var allMergedRanges = objectMapper.createArrayNode();
            var sheetFields = snapshot.path("sheets").fields();
            var sheetCount = 0;
            while (sheetFields.hasNext()) {
                var entry = sheetFields.next();
                var sheet = entry.getValue();
                var sheetId = sheet.path("id").asText(entry.getKey());
                var sheetName = sheet.path("name").asText(entry.getKey());
                var sheetCandidates = objectMapper.createArrayNode();
                var usedCells = appendCells(
                        sheetCandidates, sheetId, sheetName, sheet.path("cellData"), sheet.path("mergeData")
                );
                for (var candidate : sheetCandidates) candidateCells.add(candidate.deepCopy());
                var mergedRanges = appendMergedRanges(
                        allMergedRanges, sheetId, sheetName, sheet.path("mergeData")
                );
                var sheetSummary = objectMapper.createObjectNode()
                        .put("sheetId", sheetId)
                        .put("id", sheetId)
                        .put("sheetName", sheetName)
                        .put("name", sheetName)
                        .put("rowCount", sheet.path("rowCount").asInt(0))
                        .put("columnCount", sheet.path("columnCount").asInt(0))
                        .put("usedCellCount", usedCells)
                        .put("usedRange", usedRange(sheet.path("cellData")));
                sheetSummary.set("mergedRanges", mergedRanges);
                sheetSummary.set("candidateCells", sheetCandidates);
                sheetSummary.set("rowData", sheet.path("rowData").deepCopy());
                sheetSummary.set("columnData", sheet.path("columnData").deepCopy());
                sheets.add(sheetSummary);
                sheetCount++;
            }
            summary.put("format", "XLSX");
            summary.put("sourceKind", "UNIVER_SNAPSHOT");
            summary.put("structureVersion", WorkbookRegionSegmenter.STRUCTURE_VERSION);
            summary.put("parserVersion", "univer-snapshot-regions-v5");
            summary.put("sheetCount", sheetCount);
            summary.put("candidateCellCount", candidateCells.size());
            summary.set("sheets", sheets);
            summary.set("candidateCells", candidateCells);
            summary.set("mergedRanges", allMergedRanges);
            new WorkbookRegionSegmenter(objectMapper).enrich(summary);
            return new OfficeStructureParser.ParseResult(summary, snapshot, List.of());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取 Univer 工作簿快照", exception);
        }
    }

    private int appendCells(
            com.fasterxml.jackson.databind.node.ArrayNode target,
            String sheetId,
            String sheetName,
            JsonNode cellData,
            JsonNode mergeData
    ) {
        if (!cellData.isObject()) return 0;
        var count = 0;
        Iterator<Map.Entry<String, JsonNode>> rows = cellData.fields();
        while (rows.hasNext()) {
            var row = rows.next();
            var rowIndex = parseIndex(row.getKey());
            if (rowIndex < 0 || !row.getValue().isObject()) continue;
            Iterator<Map.Entry<String, JsonNode>> columns = row.getValue().fields();
            while (columns.hasNext()) {
                var column = columns.next();
                var columnIndex = parseIndex(column.getKey());
                if (columnIndex < 0) continue;
                var cell = column.getValue();
                var value = cellValue(cell);
                var styled = cell.path("s").isObject() || cell.path("s").isTextual();
                if ((value == null || value.isNull() || value.asText().isBlank()) && !styled) continue;
                var candidate = objectMapper.createObjectNode()
                        .put("sheetId", sheetId)
                        .put("sheetName", sheetName)
                        .put("address", columnName(columnIndex + 1) + (rowIndex + 1))
                        .put("row", rowIndex + 1)
                        .put("column", columnIndex + 1)
                        .put("empty", value == null || value.isNull() || value.asText().isBlank())
                        .put("bold", cell.path("s").path("bl").asInt(0) > 0)
                        .put("hasBorder", cell.path("s").path("bd").isObject()
                                && !cell.path("s").path("bd").isEmpty());
                if (value != null && !value.isNull() && !value.asText().isBlank()) candidate.set("value", value);
                if (cell.path("s").isObject()) candidate.set("style", cell.path("s").deepCopy());
                var mergedRange = mergedRange(rowIndex, columnIndex, mergeData);
                if (!mergedRange.isBlank()) candidate.put("mergedRange", mergedRange);
                target.add(candidate);
                count++;
            }
        }
        return count;
    }

    private String mergedRange(int row, int column, JsonNode mergeData) {
        if (!mergeData.isArray()) return "";
        for (var merge : mergeData) {
            if (row >= merge.path("startRow").asInt() && row <= merge.path("endRow").asInt()
                    && column >= merge.path("startColumn").asInt()
                    && column <= merge.path("endColumn").asInt()) {
                return columnName(merge.path("startColumn").asInt() + 1)
                        + (merge.path("startRow").asInt() + 1) + ":"
                        + columnName(merge.path("endColumn").asInt() + 1)
                        + (merge.path("endRow").asInt() + 1);
            }
        }
        return "";
    }

    private com.fasterxml.jackson.databind.node.ArrayNode appendMergedRanges(
            com.fasterxml.jackson.databind.node.ArrayNode all,
            String sheetId,
            String sheetName,
            JsonNode mergeData
    ) {
        var result = objectMapper.createArrayNode();
        if (!mergeData.isArray()) return result;
        for (var merge : mergeData) {
            var address = columnName(merge.path("startColumn").asInt() + 1)
                    + (merge.path("startRow").asInt() + 1) + ":"
                    + columnName(merge.path("endColumn").asInt() + 1)
                    + (merge.path("endRow").asInt() + 1);
            var item = objectMapper.createObjectNode()
                    .put("sheetId", sheetId).put("sheetName", sheetName).put("address", address)
                    .put("startRow", merge.path("startRow").asInt() + 1)
                    .put("endRow", merge.path("endRow").asInt() + 1)
                    .put("startColumn", merge.path("startColumn").asInt() + 1)
                    .put("endColumn", merge.path("endColumn").asInt() + 1);
            result.add(item);
            all.add(item.deepCopy());
        }
        return result;
    }

    private String usedRange(JsonNode cellData) {
        var minRow = Integer.MAX_VALUE;
        var maxRow = -1;
        var minColumn = Integer.MAX_VALUE;
        var maxColumn = -1;
        if (cellData.isObject()) {
            var rows = cellData.fields();
            while (rows.hasNext()) {
                var row = rows.next();
                var rowIndex = parseIndex(row.getKey());
                if (rowIndex < 0 || !row.getValue().isObject()) continue;
                var columns = row.getValue().fieldNames();
                while (columns.hasNext()) {
                    var columnIndex = parseIndex(columns.next());
                    if (columnIndex < 0) continue;
                    minRow = Math.min(minRow, rowIndex);
                    maxRow = Math.max(maxRow, rowIndex);
                    minColumn = Math.min(minColumn, columnIndex);
                    maxColumn = Math.max(maxColumn, columnIndex);
                }
            }
        }
        return maxRow < 0 ? "A1" : columnName(minColumn + 1) + (minRow + 1) + ":"
                + columnName(maxColumn + 1) + (maxRow + 1);
    }

    private JsonNode cellValue(JsonNode cell) {
        if (!cell.isObject()) return null;
        var value = cell.get("v");
        if (value != null && !value.isContainerNode()) return value;
        var formula = cell.path("f").asText("");
        return formula.isBlank() ? null : objectMapper.getNodeFactory().textNode("=" + formula);
    }

    private int parseIndex(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
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
}
