package com.jsd.aird.data.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;

/** Resolves field labels before fixed template locators are executed. */
final class BindingLabelResolver {
    private static final Pattern CELL = Pattern.compile("([A-Za-z]+)(\\d+)");
    private static final List<String> TRANSLATED_RANGES = List.of(
            "labelRange", "labelAddress", "logicalInputRange", "valueRange", "address", "range",
            "recordRange", "dataRange", "sourceRange", "headerRange", "crossDataRange");

    private final ObjectMapper mapper;

    BindingLabelResolver(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Resolution resolve(List<TemplateDataImportFacade.ImportBinding> bindings,
                       List<TemplateDataImportFacade.FieldDefinition> fields,
                       List<TemplateDataImportFacade.ParsedSheet> sheets) {
        var definitions = new HashMap<String, TemplateDataImportFacade.FieldDefinition>();
        fields.forEach(field -> definitions.put(field.fieldCode(), field));
        var executable = new ArrayList<TemplateDataImportFacade.ImportBinding>();
        var results = mapper.createArrayNode();
        for (var binding : bindings) {
            var locator = binding.locator();
            var labelRange = first(locator, "labelRange", "labelAddress");
            if (isRegion(binding) || binding.fieldCode() == null || binding.fieldCode().isBlank()
                    || labelRange.isBlank()) {
                executable.add(binding);
                continue;
            }
            var expected = expectedLabels(binding, definitions.get(binding.fieldCode()));
            if (expected.isEmpty()) {
                executable.add(binding);
                continue;
            }
            var sheet = findSheet(locator, sheets);
            var original = parseRange(labelRange);
            if (sheet == null || original == null) {
                executable.add(binding);
                continue;
            }
            var cells = cells(sheet);
            var originalMatches = cells.stream().filter(cell -> atOriginalLabel(cell, original, sheet))
                    .filter(cell -> expected.contains(normalizeLabel(cell.value()))).toList();
            if (!originalMatches.isEmpty()) {
                results.add(result(binding, locator, "ORIGINAL", labelRange, labelRange, List.of(), "LABEL_AT_ORIGINAL_POSITION"));
                executable.add(binding);
                continue;
            }
            var matches = cells.stream().filter(cell -> expected.contains(normalizeLabel(cell.value()))).toList();
            if (matches.size() == 1) {
                var match = matches.get(0);
                int rowDelta = match.row() - original.startRow();
                int columnDelta = match.column() - original.startColumn();
                var translated = translate(locator, rowDelta, columnDelta, labelRange, match.address());
                executable.add(copy(binding, translated));
                results.add(result(binding, translated, "RELOCATED", labelRange,
                        translated.path("labelRange").asText(match.address()), List.of(match.address()), "UNIQUE_LABEL_RELOCATED"));
            } else {
                var status = matches.isEmpty() ? (binding.required() ? "MISSING_REQUIRED" : "MISSING_OPTIONAL") : "AMBIGUOUS";
                var addresses = matches.stream().map(CellValue::address).toList();
                var reason = matches.isEmpty() ? "LABEL_NOT_FOUND" : "MULTIPLE_LABEL_MATCHES";
                results.add(result(binding, locator, status, labelRange, "", addresses, reason));
            }
        }
        return new Resolution(List.copyOf(executable), results);
    }

    private boolean atOriginalLabel(CellValue cell, Range original, TemplateDataImportFacade.ParsedSheet sheet) {
        if (original.contains(cell.row(), cell.column())) return true;
        if (sheet.layoutIr() == null) return false;
        // Excel stores a merged label's text at its top-left anchor. A locator
        // inside that same merged label has not moved; translating all result
        // columns to the anchor would silently drop the final comparison group.
        for (var value : sheet.layoutIr().path("merges")) {
            var merge = parseRange(value.asText());
            if (merge != null && cell.row() == merge.startRow() && cell.column() == merge.startColumn()
                    && merge.contains(original.startRow(), original.startColumn())
                    && merge.contains(original.endRow(), original.endColumn())) return true;
        }
        return false;
    }

    private ObjectNode result(TemplateDataImportFacade.ImportBinding binding, JsonNode locator, String status,
                              String originalRange, String resolvedRange, List<String> candidates, String reason) {
        var result = mapper.createObjectNode().put("bindingId", binding.bindingId())
                .put("fieldCode", binding.fieldCode()).put("displayName", displayName(binding))
                .put("componentId", componentId(binding, locator)).put("status", status)
                .put("required", binding.required()).put("sheetId", locator.path("sheetId").asText(""))
                .put("originalLabelRange", originalRange).put("resolvedLabelRange", resolvedRange)
                .put("reasonCode", reason);
        var array = result.putArray("candidateAddresses");
        candidates.forEach(array::add);
        return result;
    }

    private String displayName(TemplateDataImportFacade.ImportBinding binding) {
        var path = binding.labelPath();
        if (path == null || path.isBlank()) return binding.fieldCode();
        var parts = path.split("\\s*(?:>|/|›)\\s*");
        return parts[parts.length - 1];
    }

    private Set<String> expectedLabels(TemplateDataImportFacade.ImportBinding binding,
                                       TemplateDataImportFacade.FieldDefinition field) {
        var result = new HashSet<String>();
        if (field != null) {
            add(result, field.displayName());
            if (field.aliases() != null) field.aliases().forEach(value -> add(result, value));
        }
        if (binding.labelPathSegments() != null && !binding.labelPathSegments().isEmpty()) {
            add(result, binding.labelPathSegments().get(binding.labelPathSegments().size() - 1));
        } else add(result, displayName(binding));
        return result;
    }

    private void add(Set<String> values, String value) {
        var normalized = normalizeLabel(value);
        if (!normalized.isBlank()) values.add(normalized);
    }

    private TemplateDataImportFacade.ParsedSheet findSheet(JsonNode locator,
                                                            List<TemplateDataImportFacade.ParsedSheet> sheets) {
        var id = locator == null ? "" : locator.path("sheetId").asText("");
        var name = locator == null ? "" : locator.path("sheetName").asText("");
        return sheets.stream().filter(sheet -> id.isBlank() && name.isBlank()
                        || id.equalsIgnoreCase(sheet.sheetId()) || id.equalsIgnoreCase(sheet.sheetName())
                        || name.equalsIgnoreCase(sheet.sheetName()))
                .findFirst().orElse(sheets.size() == 1 ? sheets.get(0) : null);
    }

    private List<CellValue> cells(TemplateDataImportFacade.ParsedSheet sheet) {
        var byAddress = new HashMap<String, CellValue>();
        if (sheet.layoutIr() != null && sheet.layoutIr().path("cells").isArray()) {
            sheet.layoutIr().path("cells").forEach(cell -> {
                var parsed = parseRange(cell.path("address").asText(""));
                if (parsed != null) {
                    var address = address(parsed.startRow(), parsed.startColumn());
                    byAddress.put(address, new CellValue(address, parsed.startRow(), parsed.startColumn(),
                            cell.path("displayValue").asText(cell.path("value").asText(""))));
                }
            });
        }
        for (int r = 0; r < sheet.rows().size(); r++) {
            var row = sheet.rows().get(r);
            for (int c = 0; c < row.size(); c++) {
                int rowNo = sheet.firstRow() + r;
                int columnNo = sheet.firstColumn() + c;
                var address = address(rowNo, columnNo);
                byAddress.putIfAbsent(address, new CellValue(address, rowNo, columnNo, row.get(c)));
            }
        }
        return new ArrayList<>(byAddress.values());
    }

    private ObjectNode translate(JsonNode locator, int rowDelta, int columnDelta,
                                 String originalLabelRange, String resolvedAddress) {
        var result = locator != null && locator.isObject() ? (ObjectNode) locator.deepCopy() : mapper.createObjectNode();
        for (var key : TRANSLATED_RANGES) {
            var range = parseRange(result.path(key).asText(""));
            if (range != null) result.put(key, range.translate(rowDelta, columnDelta).format());
        }
        result.put("autoRelocated", true).put("originalLabelRange", originalLabelRange)
                .put("resolvedLabelAddress", resolvedAddress).put("relocationReason", "UNIQUE_LABEL_MATCH");
        return result;
    }

    private TemplateDataImportFacade.ImportBinding copy(TemplateDataImportFacade.ImportBinding b, JsonNode locator) {
        return new TemplateDataImportFacade.ImportBinding(b.bindingId(), b.fieldCode(), b.dataPath(), b.mappingKind(),
                b.parentBindingId(), b.repeatAxis(), b.recordHeight(), b.recordWidth(), b.recordStride(),
                b.terminationRule(), locator, b.required(), b.identity(), b.trainingEligible(), b.valueSource(),
                b.valueType(), b.unit(), b.labelPath(), b.labelPathSegments(), b.trainingRole(), b.ragEligible(),
                b.experimentField(), b.targetPath());
    }

    private boolean isRegion(TemplateDataImportFacade.ImportBinding binding) {
        return binding.mappingKind() != null && binding.mappingKind().toUpperCase(Locale.ROOT).endsWith("_REGION");
    }

    private String componentId(TemplateDataImportFacade.ImportBinding binding, JsonNode locator) {
        var id = locator == null ? "" : locator.path("componentId").asText("");
        if (!id.isBlank()) return id;
        if (binding.parentBindingId() != null && !binding.parentBindingId().isBlank()) return binding.parentBindingId();
        return binding.bindingId();
    }

    private String first(JsonNode node, String... keys) {
        if (node == null) return "";
        for (var key : keys) if (!node.path(key).asText("").isBlank()) return node.path(key).asText();
        return "";
    }

    private String normalizeLabel(String value) {
        if (value == null) return "";
        var raw = value.trim();
        int colon = Math.max(raw.indexOf('：'), raw.indexOf(':'));
        if (colon > 0) raw = raw.substring(0, colon);
        return raw.toLowerCase(Locale.ROOT).replaceAll("[\\s_：:（）()\\-]+", "");
    }

    private Range parseRange(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var value = raw.replace("$", "").trim();
        if (value.contains("!")) value = value.substring(value.lastIndexOf('!') + 1);
        var parts = value.split(":", 2);
        var start = parseCell(parts[0]);
        var end = parseCell(parts.length == 2 ? parts[1] : parts[0]);
        return start == null || end == null ? null : new Range(start.row(), end.row(), start.column(), end.column());
    }

    private Position parseCell(String value) {
        var match = CELL.matcher(value.trim());
        if (!match.matches()) return null;
        int column = 0;
        for (char ch : match.group(1).toUpperCase(Locale.ROOT).toCharArray()) column = column * 26 + ch - 'A' + 1;
        return new Position(Integer.parseInt(match.group(2)), column);
    }

    private String address(int row, int column) {
        var letters = new StringBuilder();
        for (int value = column; value > 0; value = (value - 1) / 26) letters.append((char) ('A' + (value - 1) % 26));
        return letters.reverse().append(row).toString();
    }

    record Resolution(List<TemplateDataImportFacade.ImportBinding> executableBindings, ArrayNode fieldResolutions) {}
    private record CellValue(String address, int row, int column, String value) {}
    private record Position(int row, int column) {}
    private record Range(int startRow, int endRow, int startColumn, int endColumn) {
        boolean contains(int row, int column) {
            return row >= startRow && row <= endRow && column >= startColumn && column <= endColumn;
        }
        Range translate(int rows, int columns) {
            return new Range(startRow + rows, endRow + rows, startColumn + columns, endColumn + columns);
        }
        String format() {
            return cell(startRow, startColumn) + (startRow == endRow && startColumn == endColumn ? "" : ":" + cell(endRow, endColumn));
        }
        private static String cell(int row, int column) {
            var letters = new StringBuilder();
            for (int value = column; value > 0; value = (value - 1) / 26) letters.append((char) ('A' + (value - 1) % 26));
            return letters.reverse().append(row).toString();
        }
    }
}
