package com.jsd.aird.mfg.upload.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

/**
 * Reads template field values out of a Word document's parsed structure, so a
 * Word production order can fill the Excel generated from its template the same
 * way an image does.
 *
 * <p>The XLSX template states where each field's label sits in its own grid
 * ({@code locator.label}), which is the only field identity available: the label
 * text is looked up in the Word document and the value is taken from what
 * follows it.  That is the convention Word forms use — a label cell with its
 * value beside or below, or a {@code 标签：值} paragraph — and it mirrors the
 * label/adjacent-value reader the client already uses for spreadsheets.</p>
 *
 * <p>Only single values are resolved.  Detail rows (repeating tables) are left to
 * the sheet workspace, which the caller reports instead of inventing rows.</p>
 */
@Service
public class WordTemplateValueExtractor {

    private final ObjectMapper objectMapper;

    public WordTemplateValueExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @return data keyed by the mapping's {@code dataPath}, holding an entry for
     *         every field whose label was found in the document.
     */
    public JsonNode extract(JsonNode mapping, JsonNode templateSnapshot, JsonNode documentIr) {
        var data = objectMapper.createObjectNode();
        if (mapping == null || !mapping.isArray() || documentIr == null || documentIr.isMissingNode()) {
            return data;
        }
        var document = WordDocumentIndex.of(documentIr);
        for (var binding : mapping) {
            // Repeating regions and their columns are addressed by row, not by a
            // label, so a document-wide lookup cannot resolve them.
            if (!"SCALAR".equalsIgnoreCase(binding.path("mappingKind").asText("SCALAR"))) continue;
            if ("DATA_TO_EDITOR".equalsIgnoreCase(binding.path("syncDirection").asText())) continue;
            var dataPath = binding.path("dataPath").asText("");
            if (dataPath.isBlank() || dataPath.contains("*")) continue;
            var label = label(binding, templateSnapshot);
            if (label.isBlank()) continue;
            var value = document.valueFor(label);
            if (value == null || value.isBlank()) continue;
            write(data, dataPath, value);
        }
        return data;
    }

    /**
     * The field's label as the template itself writes it.  The template cell is
     * authoritative because that text is what the same form shows the operator;
     * the binding's own field name is the fallback for templates whose label cell
     * was left empty.
     */
    private String label(JsonNode binding, JsonNode templateSnapshot) {
        var locator = binding.path("locator");
        var address = firstText(locator, "labelAddress", "labelRange");
        if (address.isBlank()) {
            var nested = locator.path("label");
            address = firstText(nested, "range", "address");
        }
        if (!address.isBlank()) {
            var text = TemplateCellReader.read(templateSnapshot, locator, address);
            if (!text.isBlank()) return text;
        }
        return binding.path("fieldName").asText("").trim();
    }

    private String firstText(JsonNode node, String... keys) {
        for (var key : keys) {
            var value = node.path(key).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    /** JSON Pointer write that creates the intermediate objects a field needs. */
    private void write(ObjectNode data, String dataPath, String value) {
        var segments = new ArrayList<String>();
        for (var raw : dataPath.split("/")) {
            if (!raw.isBlank()) segments.add(raw.replace("~1", "/").replace("~0", "~"));
        }
        if (segments.isEmpty()) return;
        var current = data;
        for (var index = 0; index < segments.size() - 1; index++) {
            current = current.withObject(segments.get(index));
        }
        current.put(segments.get(segments.size() - 1), value);
    }

    /** Text lookup over the document's table cells and paragraphs. */
    private static final class WordDocumentIndex {

        private final List<List<List<String>>> tables = new ArrayList<>();
        private final List<String> paragraphs = new ArrayList<>();

        static WordDocumentIndex of(JsonNode documentIr) {
            var index = new WordDocumentIndex();
            for (var table : documentIr.path("tables")) {
                var rows = new ArrayList<List<String>>();
                for (var row : table.path("rows")) {
                    var cells = new ArrayList<String>();
                    for (var cell : row.path("cells")) cells.add(text(cell.path("text")));
                    rows.add(cells);
                }
                index.tables.add(rows);
            }
            for (var paragraph : documentIr.path("paragraphTexts")) {
                index.paragraphs.add(text(paragraph.path("text")));
            }
            if (index.paragraphs.isEmpty()) {
                for (var block : documentIr.path("blocks")) {
                    if ("PARAGRAPH".equalsIgnoreCase(block.path("type").asText(""))) {
                        index.paragraphs.add(text(block.path("text")));
                    }
                }
            }
            return index;
        }

        /**
         * A form keeps its value beside its label, or on the line after it when
         * the label owns a whole row.  Blank neighbours are skipped so an empty
         * cell in between does not hide the value.
         */
        String valueFor(String label) {
            var wanted = normalize(label);
            if (wanted.isEmpty()) return null;
            for (var table : tables) {
                for (var rowIndex = 0; rowIndex < table.size(); rowIndex++) {
                    var row = table.get(rowIndex);
                    for (var columnIndex = 0; columnIndex < row.size(); columnIndex++) {
                        var cell = row.get(columnIndex);
                        var normalized = normalize(cell);
                        if (!matches(normalized, wanted)) continue;
                        var inline = inlineValue(cell);
                        if (inline != null) return inline;
                        for (var next = columnIndex + 1; next < row.size(); next++) {
                            if (!row.get(next).isBlank()) return row.get(next).trim();
                        }
                        for (var nextRow = rowIndex + 1; nextRow < table.size(); nextRow++) {
                            var below = table.get(nextRow);
                            if (columnIndex < below.size() && !below.get(columnIndex).isBlank()) {
                                return below.get(columnIndex).trim();
                            }
                        }
                    }
                }
            }
            for (var paragraphIndex = 0; paragraphIndex < paragraphs.size(); paragraphIndex++) {
                var paragraph = paragraphs.get(paragraphIndex);
                if (!matches(normalize(paragraph), wanted)) continue;
                var inline = inlineValue(paragraph);
                if (inline != null) return inline;
                for (var next = paragraphIndex + 1; next < paragraphs.size(); next++) {
                    if (!paragraphs.get(next).isBlank()) return paragraphs.get(next).trim();
                }
            }
            return null;
        }

        /** A label cell may already carry its value: {@code 订单号：PO-001}. */
        private String inlineValue(String text) {
            var match = java.util.regex.Pattern.compile("[：:]\\s*(\\S.*)$").matcher(text);
            if (!match.find()) return null;
            var value = match.group(1).trim();
            return value.isEmpty() ? null : value;
        }

        private boolean matches(String normalized, String wanted) {
            if (normalized.isEmpty()) return false;
            return normalized.equals(wanted) || normalized.startsWith(wanted) || normalized.contains(wanted);
        }

        private static String normalize(String value) {
            return (value == null ? "" : value).replaceAll("[\\s：:（）()_\\-]", "").toLowerCase(Locale.ROOT);
        }

        private static String text(JsonNode node) {
            return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("");
        }
    }

    /** Reads a label cell out of the published template's workbook snapshot. */
    private static final class TemplateCellReader {

        static String read(JsonNode snapshot, JsonNode locator, String address) {
            if (snapshot == null || !snapshot.isObject()) return "";
            var sheet = sheet(snapshot, locator.path("sheetId").asText(""), locator.path("sheetName").asText(""));
            if (sheet == null) return "";
            var range = parseRange(address);
            if (range == null) return "";
            return cell(sheet, range.startRow(), range.startColumn());
        }

        private static JsonNode sheet(JsonNode snapshot, String id, String name) {
            if (!id.isBlank() && snapshot.path("sheets").path(id).isObject()) {
                return snapshot.path("sheets").path(id);
            }
            var entries = snapshot.path("sheets").fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                if (name.equals(entry.getValue().path("name").asText())) return entry.getValue();
            }
            // Single-sheet templates often carry no sheet name in the locator.
            entries = snapshot.path("sheets").fields();
            return entries.hasNext() ? entries.next().getValue() : null;
        }

        private static String cell(JsonNode sheet, int row, int column) {
            var value = sheet.path("cellData").path(String.valueOf(row - 1))
                    .path(String.valueOf(column - 1)).path("v");
            if (value.isMissingNode() || value.isNull()) return "";
            return value.isTextual() ? value.asText("").trim() : value.asText("").trim();
        }

        private static Range parseRange(String value) {
            var match = java.util.regex.Pattern.compile("^([A-Z]+)([0-9]+)(?::([A-Z]+)([0-9]+))?$",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value.replace("$", ""));
            if (!match.matches()) return null;
            // Only the label's first cell matters: its text identifies the field.
            return new Range(Integer.parseInt(match.group(2)), column(match.group(1)));
        }

        private static int column(String letters) {
            var result = 0;
            for (var item : letters.toUpperCase(Locale.ROOT).toCharArray()) result = result * 26 + item - 'A' + 1;
            return result;
        }

        private record Range(int startRow, int startColumn) {
        }
    }
}
