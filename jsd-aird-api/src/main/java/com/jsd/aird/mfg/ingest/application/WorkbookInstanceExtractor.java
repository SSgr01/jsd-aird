package com.jsd.aird.mfg.ingest.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** Deterministically reads one filled Univer/XLSX snapshot through a published Mapping. */
@Component
public class WorkbookInstanceExtractor {

    private static final String FIELD_MODEL_KEY = "x-jsd-field-model";
    private final ObjectMapper objectMapper;

    public WorkbookInstanceExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Extraction extract(JsonNode schema, JsonNode mapping, JsonNode snapshot) {
        var data = objectMapper.createObjectNode();
        var items = new ArrayList<ExtractedItem>();
        var childrenByParent = new HashMap<String, List<JsonNode>>();
        for (var binding : mapping) {
            var parentId = binding.path("parentBindingId").asText("");
            if (!parentId.isBlank()) {
                childrenByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(binding);
            }
        }
        var fieldsByIdentity = fieldsByIdentity(schema);

        for (var binding : mapping) {
            if ("DATA_TO_EDITOR".equals(binding.path("syncDirection").asText())) continue;
            if (childrenByParent.containsKey(binding.path("bindingId").asText())) continue;
            var path = binding.path("dataPath").asText("");
            if (path.isBlank()) continue;
            var value = readBinding(snapshot, binding);
            if (value == null) value = objectMapper.nullNode();
            write(data, path, value);
            var field = fieldFor(binding, fieldsByIdentity);
            appendItems(items, binding, field, value);
        }
        return new Extraction(data, List.copyOf(items));
    }

    private JsonNode readBinding(JsonNode snapshot, JsonNode binding) {
        var locator = binding.path("locator");
        var address = firstText(locator, "logicalInputRange", "valueRange", "address", "range", "dataRange");
        if (address.isBlank()) return null;
        var sheet = sheet(snapshot, locator.path("sheetId").asText(""), locator.path("sheetName").asText(""));
        var range = parseRange(address);
        if (sheet == null || range == null) return null;
        var rows = objectMapper.createArrayNode();
        for (var row = range.startRow(); row <= range.endRow(); row++) {
            var values = objectMapper.createArrayNode();
            for (var column = range.startColumn(); column <= range.endColumn(); column++) {
                values.add(cellValue(sheet, row, column));
            }
            rows.add(values);
        }
        var valueMode = locator.path("valueMode").asText("");
        var structuredLeaf = List.of("REPEAT_FIELD", "MATRIX_FIELD")
                .contains(binding.path("mappingKind").asText(""));
        if ("ARRAY_ROW".equals(valueMode)) return trim(rows.path(0));
        if ("ARRAY_COLUMN".equals(valueMode) || structuredLeaf) {
            var result = objectMapper.createArrayNode();
            for (var row : rows) result.add(row.path(0));
            return trim(result);
        }
        if (range.startRow() == range.endRow() && range.startColumn() == range.endColumn()) {
            return rows.path(0).path(0);
        }
        return trimRows(rows);
    }

    private void appendItems(List<ExtractedItem> items, JsonNode binding, JsonNode field, JsonNode value) {
        var path = binding.path("dataPath").asText();
        var kind = binding.path("mappingKind").asText("SCALAR");
        var itemKind = "MATRIX_FIELD".equals(kind) ? "MATRIX"
                : "REPEAT_FIELD".equals(kind) ? "DETAIL" : "SCALAR";
        var locator = binding.path("locator").deepCopy();
        var fieldCode = binding.path("fieldCode").asText(field == null ? "" : field.path("fieldCode").asText(""));
        var bindingId = binding.path("bindingId").asText("");
        if (("DETAIL".equals(itemKind) || "MATRIX".equals(itemKind)) && value.isArray()) {
            for (var index = 0; index < value.size(); index++) {
                var concretePath = path.replace("/*/", "/" + index + "/");
                items.add(new ExtractedItem(
                        bindingId + ":" + index, itemKind, bindingId, fieldCode, concretePath,
                        index, fieldCode + ":" + index, value.get(index), locator, 1d,
                        value.get(index).isNull() ? "NEEDS_REVIEW" : "EXTRACTED"));
            }
        } else {
            items.add(new ExtractedItem(
                    bindingId, itemKind, bindingId, fieldCode, path, null, null,
                    value, locator, 1d, value.isNull() ? "NEEDS_REVIEW" : "EXTRACTED"));
        }
    }

    private Map<String, JsonNode> fieldsByIdentity(JsonNode schema) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (var field : schema.path(FIELD_MODEL_KEY).path("fields")) {
            putIdentity(result, field.path("bindingId").asText(""), field);
            putIdentity(result, field.path("fieldId").asText(""), field);
            putIdentity(result, field.path("id").asText(""), field);
            putIdentity(result, field.path("dataPath").asText(""), field);
        }
        return result;
    }

    private JsonNode fieldFor(JsonNode binding, Map<String, JsonNode> fields) {
        for (var key : List.of("bindingId", "fieldId", "dataPath")) {
            var value = binding.path(key).asText("");
            if (fields.containsKey(value)) return fields.get(value);
        }
        return null;
    }

    private void putIdentity(Map<String, JsonNode> result, String key, JsonNode field) {
        if (!key.isBlank()) result.putIfAbsent(key, field);
    }

    private JsonNode sheet(JsonNode snapshot, String sheetId, String sheetName) {
        var sheets = snapshot.path("sheets");
        if (!sheetId.isBlank() && sheets.has(sheetId)) return sheets.path(sheetId);
        var iterator = sheets.fields();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (sheetName.equals(entry.getValue().path("name").asText())) return entry.getValue();
        }
        return null;
    }

    private JsonNode cellValue(JsonNode sheet, int row, int column) {
        var value = sheet.path("cellData").path(String.valueOf(row - 1))
                .path(String.valueOf(column - 1)).path("v");
        return value.isMissingNode() ? objectMapper.nullNode() : value.deepCopy();
    }

    private ArrayNode trim(JsonNode source) {
        var result = objectMapper.createArrayNode();
        if (source != null && source.isArray()) source.forEach(item -> result.add(item.deepCopy()));
        while (!result.isEmpty() && blank(result.get(result.size() - 1))) result.remove(result.size() - 1);
        return result;
    }

    private ArrayNode trimRows(ArrayNode source) {
        var result = source.deepCopy();
        while (!result.isEmpty()) {
            var row = result.get(result.size() - 1);
            var blank = true;
            for (var cell : row) blank &= blank(cell);
            if (!blank) break;
            result.remove(result.size() - 1);
        }
        return result;
    }

    private boolean blank(JsonNode value) {
        return value == null || value.isNull() || value.asText("").isBlank();
    }

    private void write(ObjectNode root, String pointer, JsonNode value) {
        var segments = new ArrayList<String>();
        for (var raw : pointer.split("/")) {
            if (!raw.isBlank()) segments.add(raw.replace("~1", "/").replace("~0", "~"));
        }
        writeObject(root, segments, value);
    }

    private void writeObject(ObjectNode current, List<String> segments, JsonNode value) {
        if (segments.isEmpty()) return;
        var segment = segments.getFirst();
        if (segments.size() == 1) {
            current.set(segment, value.deepCopy());
            return;
        }
        if ("*".equals(segments.get(1))) {
            var values = value.isArray() ? value : objectMapper.createArrayNode().add(value);
            var existing = current.path(segment);
            var records = existing.isArray() ? (ArrayNode) existing : objectMapper.createArrayNode();
            var tail = segments.subList(2, segments.size());
            while (records.size() < values.size()) records.add(objectMapper.createObjectNode());
            for (var index = 0; index < values.size(); index++) {
                var record = records.get(index).isObject()
                        ? (ObjectNode) records.get(index) : objectMapper.createObjectNode();
                if (tail.isEmpty()) records.set(index, values.get(index).deepCopy());
                else writeObject(record, tail, values.get(index));
                if (!tail.isEmpty()) records.set(index, record);
            }
            current.set(segment, records);
            return;
        }
        var child = current.path(segment).isObject()
                ? (ObjectNode) current.path(segment) : objectMapper.createObjectNode();
        writeObject(child, segments.subList(1, segments.size()), value);
        current.set(segment, child);
    }

    private String firstText(JsonNode source, String... keys) {
        for (var key : keys) {
            var value = source.path(key).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private CellRange parseRange(String value) {
        var match = java.util.regex.Pattern.compile(
                "^([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?$",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value.replace("$", "").trim());
        if (!match.matches()) return null;
        var firstColumn = column(match.group(1));
        var firstRow = Integer.parseInt(match.group(2));
        var lastColumn = column(match.group(3) == null ? match.group(1) : match.group(3));
        var lastRow = Integer.parseInt(match.group(4) == null ? match.group(2) : match.group(4));
        return new CellRange(Math.min(firstRow, lastRow), Math.max(firstRow, lastRow),
                Math.min(firstColumn, lastColumn), Math.max(firstColumn, lastColumn));
    }

    private int column(String letters) {
        var result = 0;
        for (var letter : letters.toUpperCase().toCharArray()) result = result * 26 + letter - 'A' + 1;
        return result;
    }

    private record CellRange(int startRow, int endRow, int startColumn, int endColumn) {
    }

    public record ExtractedItem(
            String itemKey,
            String itemKind,
            String bindingId,
            String fieldCode,
            String dataPath,
            Integer recordIndex,
            String recordKey,
            JsonNode value,
            JsonNode sourceLocator,
            double confidence,
            String reviewStatus
    ) {
    }

    public record Extraction(ObjectNode data, List<ExtractedItem> items) {
    }
}
