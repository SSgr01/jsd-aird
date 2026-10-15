package com.jsd.aird.tpl.api;

import java.util.List;
import java.util.Locale;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Edits only value cells in a copy of a published workbook; the published snapshot stays immutable. */
public final class TemplateWorkbookValues {
    private static final Pattern CELL = Pattern.compile("[A-Za-z]+[1-9][0-9]*");

    private TemplateWorkbookValues() {}

    public static JsonNode blankCopy(JsonNode published, List<TemplateDataImportFacade.ImportBinding> bindings) {
        var copy = published.deepCopy();
        var labelCells = labelCells(copy, bindings);
        for (var binding : bindings) clearInput(copy, binding, labelCells);
        return copy;
    }

    public static void writeInput(JsonNode snapshot, TemplateDataImportFacade.ImportBinding binding, String value) {
        if (!isInput(binding)) return;
        var sheet = sheet(snapshot, binding.locator());
        if (sheet == null) return;
        var address = firstInputAddress(binding.locator());
        if (address == null) return;
        var coordinate = coordinate(address);
        if (coordinate == null) return;
        var cell = sheet.withObject("cellData").withObject(String.valueOf(coordinate[0]))
                .withObject(String.valueOf(coordinate[1]));
        var nextValue = value == null ? "" : value;
        if (inlineValue(binding.locator())) {
            var prefix = inlinePrefix(cell.path("v").asText(""), binding.locator());
            nextValue = prefix + nextValue;
        }
        cell.put("v", nextValue);
    }

    private static void clearInput(JsonNode snapshot, TemplateDataImportFacade.ImportBinding binding,
                                   Map<ObjectNode, Set<Long>> labelCells) {
        if (!isInput(binding)) return;
        var sheet = sheet(snapshot, binding.locator());
        if (sheet == null) return;
        var range = range(binding.locator());
        if (range == null) return;
        var first = coordinate(range[0]);
        var last = coordinate(range[1]);
        if (first == null || last == null) return;
        if ((long) (last[0] - first[0] + 1) * (last[1] - first[1] + 1) > 10_000) return;
        var rows = sheet.withObject("cellData");
        for (int row = first[0]; row <= last[0]; row++) {
            var cells = rows.path(String.valueOf(row));
            if (!(cells instanceof ObjectNode cellRow)) continue;
            for (int column = first[1]; column <= last[1]; column++) {
                var cell = cellRow.path(String.valueOf(column));
                if (!(cell instanceof ObjectNode valueCell)) continue;
                if (inlineValue(binding.locator())) {
                    valueCell.put("v", inlinePrefix(valueCell.path("v").asText(""), binding.locator()));
                } else if (!labelCells.getOrDefault(sheet, Set.of()).contains(cellKey(row, column))) {
                    valueCell.remove("v");
                }
            }
        }
    }

    private static Map<ObjectNode, Set<Long>> labelCells(
            JsonNode snapshot, List<TemplateDataImportFacade.ImportBinding> bindings) {
        Map<ObjectNode, Set<Long>> result = new IdentityHashMap<>();
        for (var binding : bindings) {
            var locator = binding.locator();
            var sheet = sheet(snapshot, locator);
            if (sheet == null) continue;
            var range = parseRange(locator.path("labelRange").asText(""));
            if (range == null) continue;
            var first = coordinate(range[0]);
            var last = coordinate(range[1]);
            if (first == null || last == null) continue;
            if ((long) (last[0] - first[0] + 1) * (last[1] - first[1] + 1) > 10_000) continue;
            var cells = result.computeIfAbsent(sheet, ignored -> new HashSet<Long>());
            for (int row = first[0]; row <= last[0]; row++) {
                for (int column = first[1]; column <= last[1]; column++) cells.add(cellKey(row, column));
            }
        }
        return result;
    }

    private static long cellKey(int row, int column) {
        return ((long) row << 32) | column;
    }

    private static boolean inlineValue(JsonNode locator) {
        return locator != null && ("INLINE_TEXT".equalsIgnoreCase(locator.path("valueMode").asText(""))
                || "AFTER_DELIMITER".equalsIgnoreCase(locator.path("valuePart").asText("")));
    }

    private static String inlinePrefix(String current, JsonNode locator) {
        var chinese = current.indexOf('：');
        var ascii = current.indexOf(':');
        var separator = chinese < 0 ? ascii : ascii < 0 ? chinese : Math.min(chinese, ascii);
        if (separator >= 0) return current.substring(0, separator + 1);
        var label = locator.path("labelPrefix").asText("");
        return label.isBlank() ? "" : label + "：";
    }

    private static boolean isInput(TemplateDataImportFacade.ImportBinding binding) {
        if (binding == null) return false;
        var kind = binding.mappingKind() == null ? "" : binding.mappingKind().toUpperCase(Locale.ROOT);
        if (kind.contains("REGION")) return false;
        var source = binding.valueSource() == null ? "" : binding.valueSource().toUpperCase(Locale.ROOT);
        return !"FORMULA".equals(source) && !"DERIVED".equals(source);
    }

    private static ObjectNode sheet(JsonNode snapshot, JsonNode locator) {
        if (snapshot == null || locator == null) return null;
        var sheets = snapshot.path("sheets");
        var direct = sheets.path(locator.path("sheetId").asText(""));
        if (direct instanceof ObjectNode object) return object;
        for (var candidate : sheets)
            if (candidate instanceof ObjectNode object
                    && locator.path("sheetName").asText("").equals(candidate.path("name").asText("")))
                return object;
        return null;
    }

    private static String firstInputAddress(JsonNode locator) {
        var range = range(locator);
        if (range == null) return null;
        if (inlineValue(locator)) return range[0];
        var label = parseRange(locator.path("labelRange").asText(""));
        if (label == null) return range[0];
        var first = coordinate(range[0]);
        var last = coordinate(range[1]);
        var labelFirst = coordinate(label[0]);
        var labelLast = coordinate(label[1]);
        if (first == null || last == null || labelFirst == null || labelLast == null) return range[0];
        for (int row = first[0]; row <= last[0]; row++) {
            for (int column = first[1]; column <= last[1]; column++) {
                if (row < labelFirst[0] || row > labelLast[0] || column < labelFirst[1] || column > labelLast[1]) {
                    return address(row, column);
                }
            }
        }
        return null;
    }

    private static String address(int row, int column) {
        var letters = new StringBuilder();
        for (int n = column + 1; n > 0; n = (n - 1) / 26) {
            letters.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return letters.toString() + (row + 1);
    }

    private static String[] range(JsonNode locator) {
        if (locator == null) return null;
        var raw = locator.path("valueRange").asText("");
        if (raw.isBlank()) raw = locator.path("address").asText("");
        if (raw.isBlank()) raw = locator.path("logicalInputRange").asText("");
        return parseRange(raw);
    }

    private static String[] parseRange(String raw) {
        raw = raw.replace("$", "");
        var parts = raw.split(":", -1);
        if (parts.length == 0 || parts.length > 2 || !CELL.matcher(parts[0]).matches()) return null;
        if (parts.length == 2 && !CELL.matcher(parts[1]).matches()) return null;
        return new String[] {parts[0], parts.length == 2 ? parts[1] : parts[0]};
    }

    private static int[] coordinate(String address) {
        if (address == null || !CELL.matcher(address).matches()) return null;
        var letters = address.replaceAll("[0-9]", "").toUpperCase(Locale.ROOT);
        var row = Integer.parseInt(address.replaceAll("[A-Za-z]", "")) - 1;
        var column = 0;
        for (char letter : letters.toCharArray()) column = column * 26 + letter - 'A' + 1;
        return new int[] {row, column - 1};
    }
}
