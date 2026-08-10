package com.jsd.aird.mfg.ingest.application;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** Writes confirmed extracted values into a cloned template snapshot. */
@Component
public class WorkbookSnapshotValueWriter {

    private final ObjectMapper objectMapper;

    public WorkbookSnapshotValueWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode write(JsonNode source, JsonNode mapping, JsonNode data) {
        var snapshot = source.deepCopy();
        for (var binding : mapping) {
            if ("DATA_TO_EDITOR".equals(binding.path("syncDirection").asText())) continue;
            var locator = binding.path("locator");
            var address = first(locator, "logicalInputRange", "valueRange", "address", "range");
            var range = parseRange(address);
            var sheet = sheet(snapshot, locator.path("sheetId").asText(), locator.path("sheetName").asText());
            if (range == null || sheet == null) continue;
            var value = readPath(data, binding.path("dataPath").asText());
            if (value == null) continue;
            writeRange(sheet, range, value, locator.path("valueMode").asText());
        }
        return snapshot;
    }

    private JsonNode readPath(JsonNode current, String pointer) {
        var segments = new ArrayList<String>();
        for (var raw : pointer.split("/")) if (!raw.isBlank()) segments.add(raw.replace("~1", "/").replace("~0", "~"));
        return read(current, segments);
    }

    private JsonNode read(JsonNode current, List<String> segments) {
        if (segments.isEmpty()) return current;
        var head = segments.getFirst();
        var tail = segments.subList(1, segments.size());
        if ("*".equals(head)) {
            var result = objectMapper.createArrayNode();
            if (current.isArray()) current.forEach(item -> result.add(read(item, tail)));
            return result;
        }
        return read(current.path(head), tail);
    }

    private void writeRange(ObjectNode sheet, Range range, JsonNode value, String valueMode) {
        if (value.isArray()) {
            for (var index = 0; index < value.size(); index++) {
                var row = "ARRAY_ROW".equals(valueMode) ? range.startRow() : range.startRow() + index;
                var column = "ARRAY_ROW".equals(valueMode) ? range.startColumn() + index : range.startColumn();
                if (row <= range.endRow() && column <= range.endColumn()) put(sheet, row, column, value.get(index));
            }
        } else {
            put(sheet, range.startRow(), range.startColumn(), value);
        }
    }

    private void put(ObjectNode sheet, int row, int column, JsonNode value) {
        var cellData = (ObjectNode) sheet.withObject("cellData");
        var rowNode = (ObjectNode) cellData.withObject(String.valueOf(row - 1));
        var cell = (ObjectNode) rowNode.withObject(String.valueOf(column - 1));
        if (value == null || value.isNull() || value.isMissingNode()) cell.remove("v");
        else cell.set("v", value.deepCopy());
    }

    private ObjectNode sheet(JsonNode snapshot, String id, String name) {
        if (!id.isBlank() && snapshot.path("sheets").path(id).isObject()) return (ObjectNode) snapshot.path("sheets").path(id);
        var sheets = snapshot.path("sheets").fields();
        while (sheets.hasNext()) {
            var entry = sheets.next();
            if (name.equals(entry.getValue().path("name").asText())) return (ObjectNode) entry.getValue();
        }
        return null;
    }

    private String first(JsonNode node, String... keys) {
        for (var key : keys) if (!node.path(key).asText("").isBlank()) return node.path(key).asText();
        return "";
    }

    private Range parseRange(String value) {
        var match = java.util.regex.Pattern.compile("^([A-Z]+)([0-9]+)(?::([A-Z]+)([0-9]+))?$",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value.replace("$", ""));
        if (!match.matches()) return null;
        return new Range(Integer.parseInt(match.group(2)), Integer.parseInt(match.group(4) == null ? match.group(2) : match.group(4)),
                column(match.group(1)), column(match.group(3) == null ? match.group(1) : match.group(3)));
    }

    private int column(String letters) {
        var result = 0;
        for (var item : letters.toUpperCase().toCharArray()) result = result * 26 + item - 'A' + 1;
        return result;
    }

    private record Range(int startRow, int endRow, int startColumn, int endColumn) {
    }
}
