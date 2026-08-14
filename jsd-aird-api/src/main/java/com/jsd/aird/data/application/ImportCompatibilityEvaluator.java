package com.jsd.aird.data.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;

/** Matches an uploaded workbook to component contracts without relying on Sheet order. */
final class ImportCompatibilityEvaluator {

    private final ObjectMapper objectMapper;

    ImportCompatibilityEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Result evaluate(JsonNode contract, List<TemplateDataImportFacade.ParsedSheet> sheets) {
        if (contract == null || !contract.path("components").isArray()) {
            return new Result("LEGACY", objectMapper.createArrayNode());
        }
        var matches = objectMapper.createArrayNode();
        var statuses = new ArrayList<String>();
        for (var component : contract.path("components")) {
            // A region-only binding describes layout scaffolding, not a value
            // anchor. It must not block an otherwise compatible upload.
            if (!hasExecutableBinding(component)) continue;
            var required = component.path("requiredComponent").asBoolean(false);
            var best = sheets.stream().map(sheet -> score(component, sheet))
                    .max(java.util.Comparator.comparingDouble(ComponentScore::score)).orElse(null);
            var status = componentStatus(component, best, required);
            statuses.add(status);
            var item = matches.addObject()
                    .put("componentId", component.path("componentId").asText(""))
                    .put("status", status)
                    .put("required", required)
                    .put("score", best == null ? 0 : best.score());
            var reasonCodes = item.putArray("resolutionReasonCodes");
            resolutionReasons(component, best, required, status).forEach(reasonCodes::add);
            var affected = item.putArray("affectedFieldIds");
            component.path("bindings").forEach(binding -> {
                if (isRegionOnlyBinding(binding)) return;
                var id = binding.path("fieldCode").asText(binding.path("bindingId").asText(""));
                if (!id.isBlank()) affected.add(id);
            });
            if (best != null) {
                item.put("sheetId", best.sheet().sheetId())
                        .put("sheetName", best.sheet().sheetName())
                        .put("anchorCoverage", best.anchorCoverage())
                        .put("geometryCompatible", best.geometryCompatible())
                        .put("formulaRoleCompatible", best.formulaRoleCompatible())
                        .put("fingerprintExact", best.fingerprintExact());
            }
        }
        var overall = statuses.stream().anyMatch("INCOMPATIBLE"::equals) ? "INCOMPATIBLE"
                : statuses.stream().anyMatch("REVIEW_REQUIRED"::equals) ? "REVIEW_REQUIRED"
                : statuses.stream().anyMatch("COMPATIBLE"::equals) ? "COMPATIBLE" : "EXACT";
        return new Result(overall, matches);
    }

    private ComponentScore score(JsonNode component, TemplateDataImportFacade.ParsedSheet sheet) {
        var labels = labels(component, sheet);
        var values = cellValues(sheet);
        long found = labels.stream().filter(candidates -> candidates.stream().anyMatch(values::contains)).count();
        double coverage = labels.isEmpty() ? 1 : (double) found / labels.size();
        var expectedSheet = component.path("sheetId").asText("");
        boolean sameSheet = expectedSheet.isBlank() || expectedSheet.equalsIgnoreCase(sheet.sheetId())
                || expectedSheet.equalsIgnoreCase(sheet.sheetName());
        boolean geometry = geometryCompatible(component, sheet);
        boolean formula = formulaRoleCompatible(component, sheet);
        var expectedFingerprint = component.path("sheetStructureFingerprint")
                .asText(component.path("structureFingerprint").asText(""));
        boolean fingerprintExact = !expectedFingerprint.isBlank()
                && expectedFingerprint.equalsIgnoreCase(sheet.structureFingerprint());
        var score = coverage * .60 + (sameSheet ? .15 : 0) + (geometry ? .1 : 0)
                + (formula ? .05 : 0) + (fingerprintExact ? .1 : 0);
        return new ComponentScore(sheet, score, coverage, sameSheet, geometry, formula, fingerprintExact);
    }

    private String componentStatus(JsonNode component, ComponentScore best, boolean required) {
        if (best == null || best.anchorCoverage() < (required ? .5 : .35)) return required ? "INCOMPATIBLE" : "REVIEW_REQUIRED";
        if (!best.formulaRoleCompatible()) return "INCOMPATIBLE";
        if (best.fingerprintExact() && best.sameSheet() && best.geometryCompatible()
                && best.anchorCoverage() == 1) return "EXACT";
        // 90% coverage contributes only to matching confidence. It never confirms a
        // component by itself; geometry/role differences still require review.
        if (best.anchorCoverage() == 1 && best.geometryCompatible()) return "COMPATIBLE";
        if (best.anchorCoverage() >= .9) return "REVIEW_REQUIRED";
        return "REVIEW_REQUIRED";
    }

    private List<String> resolutionReasons(JsonNode component, ComponentScore best, boolean required, String status) {
        var result = new ArrayList<String>();
        if (best == null) return List.of(required ? "REQUIRED_COMPONENT_MISSING" : "COMPONENT_NOT_FOUND");
        if (!best.formulaRoleCompatible()) result.add("FORMULA_ROLE_CONFLICT");
        if (!best.geometryCompatible()) result.add("COMPONENT_RANGE_CHANGED");
        if (!best.sameSheet()) result.add("SHEET_RENAMED_OR_MOVED");
        if (best.anchorCoverage() < 1) result.add(best.anchorCoverage() < (required ? .5 : .35)
                ? "LABEL_ANCHORS_MISSING" : "LABEL_ANCHORS_CHANGED");
        if (!best.fingerprintExact() && "EXACT".equals(status)) result.add("STRUCTURE_FINGERPRINT_MISMATCH");
        if (!best.fingerprintExact() && result.isEmpty()) result.add("STRUCTURE_FINGERPRINT_UNAVAILABLE");
        return result;
    }

    private List<Set<String>> labels(JsonNode component, TemplateDataImportFacade.ParsedSheet sheet) {
        var result = new ArrayList<Set<String>>();
        component.path("bindings").forEach(binding -> {
            if (isRegionOnlyBinding(binding)) return;
            var candidates = new HashSet<String>();
            var locator = binding.path("locator");
            var labelRange = firstRange(locator, "labelRange", "labelAddress");
            if (!labelRange.isBlank()) {
                candidates.addAll(cellValuesAt(sheet, labelRange));
            }
            // Older contracts may not have a physical label range. Keep their
            // logical label path as the fallback, while preferring the actual
            // cell at locator.labelRange for current contracts.
            if (candidates.isEmpty()) {
                var path = binding.path("labelPath").asText("");
                for (var part : path.split("\\s*>\\s*")) {
                    if (!normalize(part).isBlank()) candidates.add(normalize(part));
                }
            }
            result.add(candidates);
        });
        return result;
    }

    private Set<String> cellValuesAt(TemplateDataImportFacade.ParsedSheet sheet, String address) {
        var result = new HashSet<String>();
        var range = parseRange(address);
        if (range == null) return result;
        if (sheet.layoutIr() != null && sheet.layoutIr().path("cells").isArray()) {
            for (var cell : sheet.layoutIr().path("cells")) {
                var addressValue = parseRange(cell.path("address").asText(""));
                if (addressValue != null
                        && addressValue.startRow() >= range.startRow() && addressValue.startRow() <= range.endRow()) {
                    int column = addressValue.startColumn();
                    if (column >= range.startColumn() && column <= range.endColumn()) {
                        addCellValue(result, cell.path("displayValue").asText(""));
                    }
                }
            }
        }
        if (!result.isEmpty()) return result;
        for (int rowNumber = range.startRow(); rowNumber <= range.endRow(); rowNumber++) {
            int rowIndex = rowNumber - sheet.firstRow();
            if (rowIndex < 0 || rowIndex >= sheet.rows().size()) continue;
            var row = sheet.rows().get(rowIndex);
            for (int column = range.startColumn(); column <= range.endColumn(); column++) {
                int columnIndex = column - sheet.firstColumn();
                if (columnIndex >= 0 && columnIndex < row.size()) addCellValue(result, row.get(columnIndex));
            }
        }
        return result;
    }

    private Set<String> cellValues(TemplateDataImportFacade.ParsedSheet sheet) {
        var result = new HashSet<String>();
        if (sheet.layoutIr() != null) sheet.layoutIr().path("cells").forEach(cell -> {
            addCellValue(result, cell.path("displayValue").asText(""));
        });
        if (result.isEmpty()) sheet.rows().forEach(row -> row.forEach(value -> {
            addCellValue(result, value);
        }));
        return result;
    }

    private void addCellValue(Set<String> values, String raw) {
        var normalized = normalize(raw);
        if (!normalized.isBlank()) values.add(normalized);
        if (raw == null) return;
        var separator = Math.max(raw.indexOf('：'), raw.indexOf(':'));
        if (separator <= 0) return;
        var inlineLabel = normalize(raw.substring(0, separator));
        if (!inlineLabel.isBlank()) values.add(inlineLabel);
    }

    private boolean hasExecutableBinding(JsonNode component) {
        for (var binding : component.path("bindings")) if (!isRegionOnlyBinding(binding)) return true;
        return false;
    }

    private boolean isRegionOnlyBinding(JsonNode binding) {
        return binding.path("mappingKind").asText("").toUpperCase(Locale.ROOT).endsWith("_REGION");
    }

    private boolean formulaRoleCompatible(JsonNode component, TemplateDataImportFacade.ParsedSheet sheet) {
        var formulaAddresses = new HashSet<String>();
        if (sheet.layoutIr() != null) sheet.layoutIr().path("cells").forEach(cell -> {
            if ("FORMULA".equals(cell.path("valueSource").asText())) {
                formulaAddresses.add(cell.path("address").asText(""));
            }
        });
        for (var binding : component.path("bindings")) {
            if (!"FORMULA".equalsIgnoreCase(binding.path("valueSource").asText("INPUT"))) continue;
            var locator = binding.path("locator");
            var range = firstRange(locator, "valueRange", "dataRange", "range");
            if (!range.isBlank() && formulaAddresses.stream().noneMatch(address -> inRange(address, range))) return false;
        }
        return true;
    }

    private boolean geometryCompatible(JsonNode component, TemplateDataImportFacade.ParsedSheet sheet) {
        String range = component.path("range").asText("");
        if (range == null || range.isBlank()) return true;
        var parsed = parseRange(range);
        if (parsed == null) return true;
        if (!isRepeatComponent(component)) {
            return parsed.endRow() <= sheet.lastRow() && parsed.endColumn() <= sheet.lastColumn();
        }
        // Repeat components commonly describe a capacity or a sub-region
        // (for example A1:K200 or A9:I35). Other components may exist before,
        // after, or beside that region, so the whole sheet's used tail must not
        // be compared with this component's end coordinates. The physical
        // anchors below still provide the precise field-level check.
        return sheet.lastRow() >= parsed.startRow() && sheet.lastColumn() >= parsed.startColumn();
    }

    private boolean isRepeatComponent(JsonNode component) {
        for (var binding : component.path("bindings")) {
            if (binding.path("mappingKind").asText("").toUpperCase(Locale.ROOT).startsWith("REPEAT_")) return true;
        }
        return false;
    }

    private boolean inRange(String address, String range) {
        var cell = parseRange(address);
        var region = parseRange(range);
        return cell != null && region != null && cell.startRow() >= region.startRow() && cell.startRow() <= region.endRow()
                && cell.startColumn() >= region.startColumn() && cell.startColumn() <= region.endColumn();
    }

    private Range parseRange(String raw) {
        var value = raw == null ? "" : raw.replace("$", "").trim();
        if (value.contains("!")) value = value.substring(value.lastIndexOf('!') + 1);
        var parts = value.split(":", 2);
        var start = cell(parts[0]);
        var end = cell(parts.length > 1 ? parts[1] : parts[0]);
        return start == null || end == null ? null : new Range(start.row(), end.row(), start.column(), end.column());
    }

    private Cell cell(String raw) {
        var match = java.util.regex.Pattern.compile("([A-Za-z]+)(\\d+)").matcher(raw.trim());
        if (!match.matches()) return null;
        int column = 0;
        for (char item : match.group(1).toUpperCase(Locale.ROOT).toCharArray()) column = column * 26 + item - 'A' + 1;
        return new Cell(Integer.parseInt(match.group(2)), column);
    }

    private String firstRange(JsonNode locator, String... keys) {
        for (var key : keys) if (!locator.path(key).asText("").isBlank()) return locator.path(key).asText();
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_：:（）()\\-]+", "");
    }

    record Result(String status, JsonNode componentMatches) {}
    private record ComponentScore(TemplateDataImportFacade.ParsedSheet sheet, double score,
                                  double anchorCoverage, boolean sameSheet,
                                  boolean geometryCompatible, boolean formulaRoleCompatible,
                                  boolean fingerprintExact) {}
    private record Cell(int row, int column) {}
    private record Range(int startRow, int endRow, int startColumn, int endColumn) {}
}
