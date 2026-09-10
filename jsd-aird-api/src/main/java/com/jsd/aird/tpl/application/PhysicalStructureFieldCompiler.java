package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;

/**
 * Compiles fields from already-known workbook geometry.
 *
 * Physical geometry is deterministic input. It must be visible to the reviewer
 * even when the semantic model returns only a region, otherwise a customer is
 * forced to run a second, redundant model call just to get the fields that are
 * already present in the workbook headers.
 */
public final class PhysicalStructureFieldCompiler {

    private final ObjectMapper objectMapper;

    public PhysicalStructureFieldCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void enrichParent(ObjectNode parent, JsonNode region, JsonNode facts) {
        var kind = parent.path("kind").asText(region.path("type").asText(""));
        if (Set.of("ROW_TABLE", "COLUMN_TABLE").contains(kind)) {
            ensureComponentDataPath(parent);
            var columns = parent.withArray("columns");
            var physicalColumns = buildColumns(parent, region, facts);
            if (columns.isEmpty()) {
                columns.addAll(physicalColumns);
            } else {
                enrichExistingColumnsFromPhysicalGeometry(columns, physicalColumns);
            }
            ensureTableModel(parent, region);
        }
    }

    /**
     * Model-enriched table columns may already exist before the deterministic
     * physical pass runs.  Their names and types are useful, but the workbook
     * geometry remains authoritative for the complete hierarchical label path.
     * Never leave a leaf such as "PC膜" detached from its enclosing
     * "附着力" or treatment-condition label.
     */
    private void enrichExistingColumnsFromPhysicalGeometry(ArrayNode columns, ArrayNode physicalColumns) {
        for (var value : columns) {
            if (!(value instanceof ObjectNode column)) continue;
            var physical = matchingPhysicalColumn(column, physicalColumns);
            if (physical == null) continue;
            if (physical.path("labelPathSegments").isArray()
                    && !physical.path("labelPathSegments").isEmpty()) {
                column.set("labelPathSegments", physical.path("labelPathSegments").deepCopy());
                column.put("labelPath", physical.path("labelPath").asText(
                        joinSegments(physical.path("labelPathSegments"))));
            }
            for (var key : List.of("labelRange", "parentRange")) {
                if (column.path(key).asText("").isBlank()
                        && !physical.path(key).asText("").isBlank()) {
                    column.set(key, physical.path(key).deepCopy());
                }
            }
        }
    }

    private JsonNode matchingPhysicalColumn(JsonNode column, ArrayNode physicalColumns) {
        var valueRange = column.path("valueRange").asText("");
        var labelRange = column.path("labelRange").asText("");
        for (var physical : physicalColumns) {
            if (samePhysicalRange(valueRange, physical.path("valueRange").asText(""))) return physical;
        }
        for (var physical : physicalColumns) {
            if (samePhysicalRange(labelRange, physical.path("labelRange").asText(""))) return physical;
        }
        return null;
    }

    private boolean samePhysicalRange(String first, String second) {
        return !first.isBlank() && !second.isBlank()
                && RecognitionIdentity.normalizeRange(first)
                .equals(RecognitionIdentity.normalizeRange(second));
    }

    private String joinSegments(JsonNode values) {
        var result = new ArrayList<String>();
        for (var value : values) {
            var text = value.asText("").strip();
            if (!text.isBlank() && !result.contains(text)) result.add(text);
        }
        return String.join(" > ", result);
    }

    private void ensureComponentDataPath(ObjectNode parent) {
        if (!parent.path("dataPath").asText("").isBlank()) return;
        var identity = parent.path("bindingId").asText(
                parent.path("relationId").asText(parent.path("blockId").asText("component")));
        parent.put("dataPath", "/records/component_" + RecognitionIdentity.shortHash(identity, 12));
    }

    public List<RecognitionModelClient.ModelSuggestion> children(
            ObjectNode parent, JsonNode region, JsonNode facts
    ) {
        enrichParent(parent, region, facts);
        var kind = parent.path("kind").asText(region.path("type").asText(""));
        return switch (kind) {
            case "FORM_REGION" -> formChildren(parent, region, facts);
            case "ROW_TABLE", "COLUMN_TABLE" -> tableChildren(parent, kind);
            default -> List.of();
        };
    }

    /**
     * Compiles deterministic label/value surfaces inside a selected form
     * component.  A form's region boundary may be proposed by either physical
     * rules or the semantic model; field coordinates still come from workbook
     * geometry.  This prevents a model from leaving a valid label unbound or
     * binding a section label to an invented range.
     */
    private List<RecognitionModelClient.ModelSuggestion> formChildren(
            ObjectNode parent, JsonNode region, JsonNode facts
    ) {
        var sheetId = parent.path("locator").path("sheetId").asText(
                region.path("sheetId").asText(""));
        var regionBounds = bounds(region.path("range").asText(
                parent.path("locator").path("range").asText("")));
        if (sheetId.isBlank() || regionBounds == null) return List.of();

        // Always compile the workbook geometry first.  Earlier runs trusted an
        // upstream fieldSurfaces array whenever it existed, which meant one
        // broad model range could hide several deterministic label/value pairs
        // on the same row (for example name/date/temperature/humidity).
        var surfaces = new ArrayList<FormSurface>(
                discoverFormSurfaces(facts, sheetId, regionBounds));
        for (var surface : region.path("structure").path("fieldSurfaces")) {
            var structure = surface.path("structure");
            var labelRange = structure.path("labelRange").asText("");
            if (hasPhysicalReplacement(surfaces, facts, sheetId, labelRange)
                    || isFormGroupContainer(facts, sheetId, regionBounds, labelRange)) continue;
            addFormSurface(surfaces, facts, sheetId, regionBounds, labelRange,
                    structure.path("valueRange").asText(""));
        }

        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        var seen = new HashSet<String>();
        for (var surface : surfaces) {
            var identity = RecognitionIdentity.normalizeRange(surface.labelRange()) + "|"
                    + RecognitionIdentity.normalizeRange(surface.valueRange());
            if (!seen.add(identity)) continue;
            var relationId = RecognitionIdentity.relationId(
                    sheetId, surface.labelRange(), surface.valueRange(), "FORM_FIELD");
            var fieldId = RecognitionIdentity.fieldId(relationId);
            var formulaDerived = surface.formulaDerived();
            var payload = objectMapper.createObjectNode()
                    .put("kind", "SCALAR")
                    .put("role", "FIELD")
                    .put("suggestionLevel", "ROOT")
                    .put("mappingKind", "SCALAR")
                    .put("relationId", relationId)
                    .put("fieldId", fieldId.toString())
                    .put("bindingId", RecognitionIdentity.bindingId(
                            fieldId, "CELL_RANGE", sheetId + "|" + surface.valueRange()).toString())
                    .put("blockId", parent.path("blockId").asText(""))
                    .put("regionId", parent.path("regionId").asText(parent.path("blockId").asText("")))
                    .put("candidateRef", relationId)
                    .put("fieldCode", fieldCode(surface.label(), result.size()))
                    .put("dataPath", "/fields/" + fieldCode(surface.label(), result.size()))
                    .put("fieldName", surface.label())
                    .put("labelPath", surface.label())
                    .put("valueType", sampleType(facts, sheetId,
                            bounds(surface.valueRange())[0], bounds(surface.valueRange())[1]))
                    .put("required", false)
                    .put("editability", formulaDerived ? "READ_ONLY" : "EDITABLE")
                    .put("valueSource", formulaDerived ? "FORMULA" : "USER_INPUT")
                    .put("formulaDerived", formulaDerived)
                    .put("calculationTrustStatus", formulaDerived
                            ? surface.calculationTrustStatus() : "NOT_APPLICABLE")
                    .put("trainingEligible", !formulaDerived)
                    .put("trainingRole", formulaDerived ? "EXCLUDE" : "FEATURE")
                    .put("fieldOrigin", "TEMPLATE_LOCAL")
                    .put("standardSelectionStatus", "CUSTOM")
                    .put("standardRequired", false)
                    .put("requiresStandardConfirmation", false)
                    .put("reviewRequired", true)
                    .put("candidateOnly", false)
                    .put("publishable", false)
                    .put("pendingReason", "FIELD_CONFIRMATION_REQUIRED")
                    .put("recognitionOrigin", "PHYSICAL_FORM_COMPILER")
                    .put("nameSource", "PHYSICAL_LABEL");
            payload.set("locator", objectMapper.createObjectNode()
                    .put("sheetId", sheetId)
                    .put("labelRange", surface.labelRange())
                    .put("labelAddress", surface.labelRange().split(":", 2)[0])
                    .put("valueRange", surface.valueRange())
                    .put("logicalInputRange", surface.valueRange())
                    .put("address", surface.valueRange())
                    .put("range", surface.valueRange())
                    .put("anchorAddress", surface.valueRange().split(":", 2)[0])
                    .put("locatorType", "CELL_RANGE")
                    .put("valueMode", formulaDerived ? "FORMULA"
                            : surface.inlineValue() ? "INLINE" : "ANCHOR"));
            result.add(new RecognitionModelClient.ModelSuggestion(
                    "SCALAR_FIELD", payload, 0.94,
                    objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                            .put("source", "PHYSICAL_FORM_COMPILER")
                            .put("labelRange", surface.labelRange())
                            .put("valueRange", surface.valueRange()))));
        }
        return List.copyOf(result);
    }

    private List<FormSurface> discoverFormSurfaces(JsonNode facts, String sheetId, int[] regionBounds) {
        var semantic = cells(facts, sheetId);
        var candidates = candidateCells(facts, sheetId);
        var result = new ArrayList<FormSurface>();
        for (var labelCell : semantic) {
            var rawLabel = cellText(labelCell);
            var label = normalizedLabelName(rawLabel.replaceFirst("[：:]\\s*$", ""));
            var labelBounds = bounds(cellRange(labelCell));
            if (label.isBlank() || isFormulaText(label) || labelBounds == null
                    || !inside(regionBounds, labelBounds) || label.length() > 60
                    || isStaticInstruction(label)) continue;
            if (isImmediateMergedValueForLeftLabel(semantic, regionBounds, labelCell, labelBounds)) continue;
            if (isFormGroupContainer(semantic, regionBounds, labelBounds)) continue;

            // A merged cell ending in a colon is an inline form field: the
            // label and the runtime input share one physical Excel cell.  Do
            // not bind it to an unrelated cell to its right.
            if (rawLabel.matches(".*[：:]\\s*$") && area(labelBounds) > 1) {
                if (isInlinePlaceholderForParent(semantic, regionBounds, labelBounds)) continue;
                addFormSurface(result, facts, sheetId, regionBounds,
                        cellRange(labelCell), cellRange(labelCell));
                continue;
            }

            // Plain labels ending in a delimiter can still have a blank
            // unmerged input band. Some production templates do not style
            // every blank cell, so candidateCells may only retain the last
            // cell (or none at all). The next label/right component edge is
            // deterministic enough to preserve the whole input band.
            if (rawLabel.matches(".*[：:]\\s*$") && labelBounds[2] < regionBounds[2]) {
                var nextLabel = nextLabelColumn(semantic, regionBounds, labelBounds);
                var valueStart = labelBounds[2] + 1;
                var valueEnd = nextLabel.isPresent()
                        ? nextLabel.getAsInt() - 1 : regionBounds[2];
                if (valueEnd >= valueStart) {
                    addFormSurface(result, facts, sheetId, regionBounds,
                            cellRange(labelCell), valueRange(
                                    valueStart, labelBounds[1], valueEnd, labelBounds[3]));
                } else {
                    addFormSurface(result, facts, sheetId, regionBounds,
                            cellRange(labelCell), cellRange(labelCell));
                }
                continue;
            }
            if (labelBounds[2] >= regionBounds[2]) continue;

            JsonNode best = null;
            var expectedColumn = labelBounds[2] + 1;
            var nextLabel = nextLabelColumn(semantic, regionBounds, labelBounds);
            var valueEndColumn = nextLabel.isPresent()
                    ? nextLabel.getAsInt() - 1 : regionBounds[2];
            if (valueEndColumn < expectedColumn) continue;
            for (var candidate : candidates) {
                var candidateBounds = bounds(cellRange(candidate));
                if (candidateBounds == null || candidateBounds[0] > expectedColumn
                        || candidateBounds[2] < expectedColumn
                        || candidateBounds[1] > labelBounds[1]
                        || candidateBounds[3] < labelBounds[1]
                        || !inside(regionBounds, candidateBounds)) continue;
                if (best == null || area(candidateBounds) > area(bounds(cellRange(best)))) best = candidate;
            }
            if (best == null) {
                // Multiple labels on one row provide deterministic boundaries
                // even if the blank cells have no style and were therefore
                // omitted from candidateCells.
                if (nextLabel.isPresent()
                        || sameRowLabelCount(semantic, regionBounds, labelBounds) >= 2) {
                    addFormSurface(result, facts, sheetId, regionBounds,
                            cellRange(labelCell), valueRange(expectedColumn, labelBounds[1],
                                    valueEndColumn, labelBounds[3]));
                }
                continue;
            }
            var valueBounds = bounds(cellRange(best));
            var blankOrInput = cellText(best).isBlank()
                    || best.path("inputCandidate").asBoolean(false)
                    || cellText(best).matches("^.{1,20}[：:]\\s*$");
            var structuredSurface = valueBounds[2] > valueBounds[0]
                    || valueBounds[3] > valueBounds[1]
                    || hasBorderEvidence(best);
            // A structured input surface may contain a default/example value
            // in the source template (for example an experiment purpose).
            // Preserve it as an editable field. Plain unstructured neighbour
            // text is still rejected to avoid guessing labels as values.
            if (!blankOrInput && !isFormulaCell(best) && !structuredSurface) continue;
            // A blank cell bounded by the next label is itself deterministic
            // form geometry even when the workbook has no explicit border.
            var boundedPair = nextLabel.isPresent();
            if (!structuredSurface && !best.path("inputCandidate").asBoolean(false)
                    && !boundedPair && expectedColumn == regionBounds[2]) continue;
            var candidateEnd = contiguousValueEnd(
                    candidates, expectedColumn, valueEndColumn, labelBounds[1], valueBounds[2]);
            var candidateEndRow = Math.min(valueBounds[3], labelBounds[3]);
            addFormSurface(result, facts, sheetId, regionBounds,
                    cellRange(labelCell), valueRange(expectedColumn, labelBounds[1],
                            Math.max(expectedColumn, candidateEnd),
                            Math.max(labelBounds[1], candidateEndRow)));
        }
        result.sort(Comparator.comparingInt((FormSurface item) -> bounds(item.labelRange())[1])
                .thenComparingInt(item -> bounds(item.labelRange())[0]));
        return List.copyOf(result);
    }

    private void addFormSurface(
            List<FormSurface> result, JsonNode facts, String sheetId, int[] regionBounds,
            String labelRange, String valueRange
    ) {
        var labelBounds = bounds(labelRange);
        var valueBounds = bounds(valueRange);
        if (labelBounds == null || valueBounds == null
                || !inside(regionBounds, labelBounds) || !inside(regionBounds, valueBounds)) return;
        var labelCell = findCell(facts, sheetId, labelBounds[0], labelBounds[1]);
        var valueCell = findCell(facts, sheetId, valueBounds[0], valueBounds[1]);
        var label = normalizedLabelName(cellText(labelCell).replaceFirst("[：:]\\s*$", ""));
        if (label.isBlank() || isFormulaText(label) || isStaticInstruction(label)) return;
        var formula = isFormulaCell(valueCell);
        var trust = formula && !valueCell.path("cachedValue").isMissingNode()
                && !valueCell.path("cachedValue").isNull()
                ? "CACHED_VALUE_PRESENT" : formula ? "RECALCULATION_REQUIRED" : "NOT_APPLICABLE";
        result.add(new FormSurface(label, labelRange, valueRange, formula, trust,
                RecognitionIdentity.normalizeRange(labelRange)
                        .equals(RecognitionIdentity.normalizeRange(valueRange))));
    }

    private boolean hasPhysicalReplacement(
            List<FormSurface> surfaces, JsonNode facts, String sheetId, String labelRange
    ) {
        var normalized = canonicalPhysicalRange(facts, sheetId, labelRange);
        if (normalized.isBlank()) return false;
        if (surfaces.stream().anyMatch(surface -> canonicalPhysicalRange(
                facts, sheetId, surface.labelRange()).equals(normalized))) return true;
        return isFormGroupContainer(facts, sheetId, null, labelRange);
    }

    /**
     * The structure primitive may reference only the anchor cell of a merged
     * label (for example {@code F1}) while the workbook geometry exposes the
     * complete merge ({@code F1:G1}).  Both references describe one physical
     * label and must not compile into two fields.
     */
    private String canonicalPhysicalRange(JsonNode facts, String sheetId, String range) {
        var rangeBounds = bounds(range);
        if (rangeBounds == null) return RecognitionIdentity.normalizeRange(range);
        var cell = findCell(facts, sheetId, rangeBounds[0], rangeBounds[1]);
        var physical = cellRange(cell);
        return RecognitionIdentity.normalizeRange(physical.isBlank() ? range : physical);
    }

    private boolean isStaticInstruction(String label) {
        if (label == null) return false;
        var normalized = label.strip();
        if (!normalized.matches("^(注|说明|注意事项?|提示|备注)[：:].+")) return false;
        var separator = Math.max(normalized.indexOf('：'), normalized.indexOf(':'));
        return separator >= 0 && normalized.substring(separator + 1).strip().length() >= 4;
    }

    private boolean isFormGroupContainer(
            JsonNode facts, String sheetId, int[] regionBounds, String labelRange
    ) {
        var labelBounds = bounds(labelRange);
        if (labelBounds != null) {
            var physicalCell = findCell(facts, sheetId, labelBounds[0], labelBounds[1]);
            var physicalBounds = bounds(cellRange(physicalCell));
            if (physicalBounds != null) labelBounds = physicalBounds;
        }
        return labelBounds != null && isFormGroupContainer(cells(facts, sheetId), regionBounds, labelBounds);
    }

    private boolean isFormGroupContainer(
            List<JsonNode> semantic, int[] regionBounds, int[] labelBounds
    ) {
        if (labelBounds[3] <= labelBounds[1]) return false;
        return semantic.stream().filter(cell -> {
            var child = bounds(cellRange(cell));
            return child != null && !cellText(cell).isBlank() && !isFormulaText(cellText(cell))
                    && (regionBounds == null || inside(regionBounds, child))
                    && child[0] > labelBounds[2]
                    && child[1] >= labelBounds[1] && child[3] <= labelBounds[3];
        }).mapToInt(cell -> bounds(cellRange(cell))[1]).distinct().count() >= 2;
    }

    private boolean isInlinePlaceholderForParent(
            List<JsonNode> semantic, int[] regionBounds, int[] labelBounds
    ) {
        return semantic.stream().anyMatch(cell -> {
            var parent = bounds(cellRange(cell));
            return parent != null && !cellText(cell).isBlank() && !isFormulaText(cellText(cell))
                    && !cellText(cell).matches(".*[：:]\\s*$")
                    && inside(regionBounds, parent) && parent[2] + 1 == labelBounds[0]
                    && parent[1] <= labelBounds[1] && parent[3] >= labelBounds[3]
                    && !isFormGroupContainer(semantic, regionBounds, parent);
        });
    }

    private java.util.OptionalInt nextLabelColumn(
            List<JsonNode> semantic, int[] regionBounds, int[] labelBounds
    ) {
        return semantic.stream().map(this::cellRange).map(this::bounds)
                .filter(candidate -> candidate != null && inside(regionBounds, candidate)
                        && candidate[0] > labelBounds[2]
                        && candidate[1] <= labelBounds[1] && candidate[3] >= labelBounds[1])
                // Same-row labels may span columns, but a vertically merged
                // neighbour is normally the value surface of a vertical form
                // group (for example a multi-row problem description).
                .filter(candidate -> candidate[1] == candidate[3])
                .filter(candidate -> {
                    var cell = findCellIn(semantic, candidate[0], labelBounds[1]);
                    var text = cellText(cell);
                    if (candidate[0] == labelBounds[2] + 1
                            && candidate[2] > candidate[0]
                            && !text.matches(".*[：:]\\s*$")) return false;
                    return !text.isBlank() && !isFormulaText(text);
                })
                .mapToInt(candidate -> candidate[0]).min();
    }

    private long sameRowLabelCount(
            List<JsonNode> semantic, int[] regionBounds, int[] labelBounds
    ) {
        return semantic.stream().filter(candidate -> {
            var bounds = bounds(cellRange(candidate));
            return bounds != null && inside(regionBounds, bounds)
                    && bounds[1] <= labelBounds[1] && bounds[3] >= labelBounds[1]
                    && !cellText(candidate).isBlank() && !isFormulaText(cellText(candidate));
        }).count();
    }

    private boolean isImmediateMergedValueForLeftLabel(
            List<JsonNode> semantic, int[] regionBounds, JsonNode current, int[] currentBounds
    ) {
        var text = cellText(current);
        if (currentBounds[2] <= currentBounds[0] || text.matches(".*[：:]\\s*$")) return false;
        return semantic.stream().anyMatch(candidate -> {
            var bounds = bounds(cellRange(candidate));
            return bounds != null && inside(regionBounds, bounds)
                    && bounds[1] <= currentBounds[1] && bounds[3] >= currentBounds[1]
                    && bounds[2] + 1 == currentBounds[0]
                    && !cellText(candidate).isBlank()
                    && !isFormulaText(cellText(candidate));
        });
    }

    private JsonNode findCellIn(List<JsonNode> source, int column, int row) {
        var address = address(column, row);
        for (var cell : source) {
            if (address.equalsIgnoreCase(cell.path("address").asText(""))) return cell;
        }
        for (var cell : source) {
            var cellBounds = bounds(cellRange(cell));
            if (cellBounds != null && column >= cellBounds[0] && column <= cellBounds[2]
                    && row >= cellBounds[1] && row <= cellBounds[3]) return cell;
        }
        return null;
    }

    private List<JsonNode> candidateCells(JsonNode facts, String sheetId) {
        var result = new ArrayList<JsonNode>();
        for (var sheet : facts.path("sheets")) {
            var id = sheet.path("id").asText(sheet.path("sheetId").asText(""));
            if (sheetId.equals(id)) sheet.path("candidateCells").forEach(result::add);
        }
        return result;
    }

    private int contiguousValueEnd(
            List<JsonNode> candidates, int startColumn, int maximumColumn, int row, int initialEnd
    ) {
        var end = Math.min(initialEnd, maximumColumn);
        var changed = true;
        while (changed) {
            changed = false;
            for (var candidate : candidates) {
                var bounds = bounds(cellRange(candidate));
                if (bounds == null || bounds[1] > row || bounds[3] < row
                        || bounds[0] < startColumn || bounds[0] > end + 1
                        || bounds[2] > maximumColumn) continue;
                var validSurface = cellText(candidate).isBlank()
                        || candidate.path("inputCandidate").asBoolean(false)
                        || isFormulaCell(candidate);
                if (validSurface && bounds[2] > end) {
                    end = bounds[2];
                    changed = true;
                }
            }
        }
        return end;
    }

    private boolean inside(int[] outer, int[] inner) {
        return outer != null && inner != null && outer[0] <= inner[0] && outer[1] <= inner[1]
                && outer[2] >= inner[2] && outer[3] >= inner[3];
    }

    private int area(int[] value) {
        return value == null ? 0 : (value[2] - value[0] + 1) * (value[3] - value[1] + 1);
    }

    private boolean hasBorderEvidence(JsonNode cell) {
        return !cell.path("borderSignature").asText("").isBlank()
                || cell.path("bordered").asBoolean(false)
                || cell.path("hasBorder").asBoolean(false);
    }

    private record FormSurface(
            String label, String labelRange, String valueRange,
            boolean formulaDerived, String calculationTrustStatus, boolean inlineValue
    ) {}

    private record HeaderLabel(String name, String unit) {}

    private List<RecognitionModelClient.ModelSuggestion> tableChildren(ObjectNode parent, String kind) {
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        var parentRelationId = parent.path("relationId").asText("");
        var parentFieldId = parent.path("fieldId").asText("");
        var parentBindingId = parent.path("bindingId").asText("");
        var repeatAxis = "COLUMN_TABLE".equals(kind) ? "COLUMN" : "ROW";
        var parentPath = parent.path("dataPath").asText("");
        var ordinal = 0;
        for (var column : parent.path("columns")) {
            var name = column.path("name").asText("").strip();
            var valueRange = column.path("valueRange").asText("");
            // A formula cell is a derived value, never a field label. Older
            // fallback compilation read the formula expression as the header
            // text and consequently exposed =IF(...)/=SUM(...) as editable
            // business fields. Keep derived rows in the physical projection,
            // but do not materialize a semantic field without a real label.
            if (name.isBlank() || valueRange.isBlank() || isFormulaText(name)
                    || column.path("formulaLabel").asBoolean(false)) continue;
            var code = column.path("code").asText("field_" + ordinal++);
            var valueSource = column.path("valueSource").asText("USER_INPUT");
            var formulaDerived = column.path("formulaDerived").asBoolean("FORMULA".equals(valueSource));
            var relationId = parentRelationId + "|physical-child|" + code + "|"
                    + RecognitionIdentity.normalizeRange(valueRange);
            var fieldId = RecognitionIdentity.fieldId(relationId);
            var bindingId = RecognitionIdentity.bindingId(fieldId, "CELL_RANGE",
                    parent.path("locator").path("sheetId").asText("") + "|" + valueRange);
            var child = objectMapper.createObjectNode()
                    .put("kind", "SCALAR")
                    .put("suggestionLevel", "CHILD")
                    .put("mappingKind", "REPEAT_FIELD")
                    .put("relationId", relationId)
                    .put("modelRelationId", parentRelationId)
                    .put("fieldId", fieldId.toString())
                    .put("bindingId", bindingId.toString())
                    .put("parentRelationId", parentRelationId)
                    .put("parentFieldId", parentFieldId)
                    .put("parentBindingId", parentBindingId)
                    .put("regionId", parent.path("regionId").asText(parent.path("blockId").asText("")))
                    .put("blockId", parent.path("blockId").asText(parent.path("regionId").asText("")))
                    .put("parentBlockId", parent.path("blockId").asText(""))
                    // Candidate identity belongs to the physical field, not
                    // the enclosing repeat region.  This gives the semantic
                    // patch protocol a stable whitelist and keeps review,
                    // coverage and export bound to the same cell surface.
                    .put("candidateRef", relationId)
                    .put("fieldCode", column.path("fieldCode").asText("TABLE.COLUMN." + code))
                    .put("dataPath", parentPath + "/*/" + code)
                    .put("fieldName", name)
                    .put("groupName", parent.path("groupName").asText("业务数据"))
                    .put("valueType", column.path("valueType").asText("string"))
                    .put("required", column.path("required").asBoolean(false))
                    .put("role", "FIELD")
                    .put("locatorType", "CELL_RANGE")
                    .put("editability", formulaDerived ? "READ_ONLY" :
                            column.path("editability").asText("EDITABLE"))
                    .put("valueSource", formulaDerived ? "FORMULA" : valueSource)
                    .put("trainingEligible", !formulaDerived
                            && column.path("trainingEligible").asBoolean(true))
                    .put("trainingRole", formulaDerived ? "EXCLUDE" :
                            column.path("trainingRole").asText("FEATURE"))
                    .put("formulaDerived", formulaDerived)
                    .put("calculationTrustStatus", column.path("calculationTrustStatus")
                            .asText(formulaDerived ? "RECALCULATION_REQUIRED" : "NOT_APPLICABLE"))
                    .put("unit", column.path("unit").asText(""))
                    .put("repeatAxis", repeatAxis)
                    .put("recordHeight", parent.path("recordHeight").asInt(1))
                    .put("recordWidth", parent.path("recordWidth").asInt(1))
                    .put("recordStride", parent.path("recordStride").asInt(1))
                    .put("candidateOnly", parent.path("candidateOnly").asBoolean(true))
                    .put("reviewRequired", parent.path("reviewRequired").asBoolean(true))
                    .put("publishable", false)
                    .put("physicalStructureOnly", parent.path("physicalStructureOnly").asBoolean(true))
                    .put("pendingReason", "PHYSICAL_HEADER_REVIEW")
                    .put("canonicalStatus", parent.path("canonicalStatus").asText("PROVISIONAL"))
                    .put("structureStatus", parent.path("structureStatus").asText("PROVISIONAL"))
                    .put("recognitionOrigin", "PHYSICAL_HEADER_FALLBACK")
                    .put("nameSource", "PHYSICAL_HEADER_FALLBACK")
                    .put("fieldOrigin", "TEMPLATE_LOCAL")
                    .put("standardSelectionStatus", "CUSTOM")
                    .put("standardRequired", false)
                    .put("requiresStandardConfirmation", false)
                    .put("reason", "字段来自模板中的实际表头，可直接确认；标准字段不是必选项。")
                    .put("interpretation", "按" + repeatAxis + "方向读取每条记录的“" + name + "”。");
            if (column.path("labelPathSegments").isArray()) {
                child.set("labelPathSegments", column.path("labelPathSegments").deepCopy());
            }
            if (!column.path("labelPath").asText("").isBlank()) {
                child.put("labelPath", column.path("labelPath").asText());
            }
            var locator = objectMapper.createObjectNode()
                    .put("sheetId", parent.path("locator").path("sheetId").asText(""))
                    .put("sheetName", parent.path("locator").path("sheetName").asText(""))
                    .put("address", valueRange)
                    .put("range", valueRange)
                    .put("valueRange", valueRange)
                    .put("labelRange", column.path("labelRange").asText(""))
                    .put("parentRange", column.path("parentRange").asText(
                            parent.path("locator").path("dataRange")
                                    .asText(parent.path("locator").path("range").asText(""))))
                    .put("valueMode", "COLUMN".equals(repeatAxis) ? "ARRAY_ROW" : "ARRAY_COLUMN")
                    .put("locatorType", "CELL_RANGE");
            child.set("locator", locator);
            result.add(new RecognitionModelClient.ModelSuggestion(
                    "TABLE_CHILD_FIELD", child, 0.92,
                    objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                            .put("source", "PHYSICAL_HEADER_FALLBACK")
                            .put("valueRange", valueRange)
                            .put("parentRelationId", parentRelationId))));
        }
        return List.copyOf(result);
    }

    private ArrayNode buildColumns(ObjectNode parent, JsonNode region, JsonNode facts) {
        var kind = parent.path("kind").asText(region.path("type").asText("ROW_TABLE"));
        var details = region.path("structure");
        var header = bounds(details.path("headerRange").asText(parent.path("headerRange").asText("")));
        var data = bounds(details.path("dataRange").asText(parent.path("dataRange").asText("")));
        var result = objectMapper.createArrayNode();
        if (header == null || data == null) return result;
        if ("ROW_TABLE".equals(kind)) {
            var seenHeaderRanges = new HashSet<String>();
            for (int column = header[0]; column <= header[2]; column++) {
                var cell = findHeaderCell(facts, parent.path("locator").path("sheetId").asText(""),
                        column, header[1]);
                if (isFormulaCell(cell)) continue;
                var headerLabel = headerLabel(cellText(cell));
                var name = headerLabel.name();
                if (name.isBlank() || isFormulaText(name)) continue;
                var physicalLabelRange = cellRange(cell);
                var normalizedLabelRange = RecognitionIdentity.normalizeRange(physicalLabelRange);
                if (!seenHeaderRanges.add(normalizedLabelRange)) continue;
                var labelBounds = bounds(physicalLabelRange);
                var valueStartColumn = labelBounds == null ? column : Math.max(header[0], labelBounds[0]);
                var valueEndColumn = labelBounds == null ? column : Math.min(header[2], labelBounds[2]);
                var code = fieldCode(name, column);
                result.add(column(name, headerLabel.unit(), code,
                        valueRange(valueStartColumn, data[1], valueEndColumn, data[3]),
                        physicalLabelRange, sampleType(facts,
                                parent.path("locator").path("sheetId").asText(""),
                                valueStartColumn, data[1])));
            }
        } else {
            // COLUMN_TABLE keeps label/attribute columns inside dataRange.
            // Derive the first repeated record column from the physical
            // identity row instead of assuming that only the first column is
            // a label. Real templates commonly use two to four hierarchy and
            // method columns before the blank sample/experiment columns.
            var firstRecordColumn = firstColumnRecord(facts,
                    parent.path("locator").path("sheetId").asText(""), header, data);
            // The top identity band belongs to the same COLUMN_TABLE.  Keep
            // sample/initiator identifiers (including blank input slots) in
            // the repeat-field projection instead of leaving them as form
            // fields or dropping them before the metric rows.
            var sheetId = parent.path("locator").path("sheetId").asText("");
            var identityEnd = Math.min(header[3], data[1] - 1);
            for (int row = header[1]; row <= identityEnd; row++) {
                var label = findRowLabel(facts, sheetId, row, firstRecordColumn);
                if (label == null || cellText(label).isBlank()
                        || !hasRecordSurface(facts, sheetId, firstRecordColumn, data[2], row)) continue;
                var headerLabel = headerLabel(cellText(label));
                var name = headerLabel.name();
                if (name.isBlank() || isFormulaText(name) || isAxisLabel(name)) continue;
                var identity = column(name, headerLabel.unit(), fieldCode(name, row),
                        valueRange(firstRecordColumn, row, data[2], row), cellRange(label),
                        sampleType(facts, sheetId, firstRecordColumn, row));
                identity.put("parentRange", region.path("range").asText(
                        parent.path("locator").path("range")
                                .asText(parent.path("locator").path("dataRange").asText(""))));
                result.add(identity);
            }
            for (int row = data[1]; row <= data[3]; row++) {
                var label = findRowLabel(facts, sheetId,
                        row, firstRecordColumn);
                if (label == null || cellText(label).isBlank()) continue;
                var headerLabel = headerLabel(cellText(label));
                var name = headerLabel.name();
                var code = fieldCode(name, row);
                var projected = column(name, headerLabel.unit(), code,
                        valueRange(firstRecordColumn, row, data[2], row),
                        cellRange(label), sampleType(facts, sheetId, firstRecordColumn, row));
                var labelPath = rowLabelPath(facts,
                        sheetId, row, firstRecordColumn);
                if (!labelPath.isEmpty()) {
                    projected.put("labelPath", String.join(" > ", labelPath));
                    var segments = projected.putArray("labelPathSegments");
                    labelPath.forEach(segments::add);
                }
                result.add(projected);
            }
        }
        return result;
    }

    private int firstColumnRecord(JsonNode facts, String sheetId, int[] header, int[] data) {
        // A merged left identity label (for example A4:B4) gives an exact
        // record-column split even when every sample title is already filled.
        for (var cell : cells(facts, sheetId)) {
            var b = bounds(cellRange(cell));
            if (b == null || b[0] != data[0] || b[1] != header[1] || b[3] != header[3]
                    || b[2] <= b[0] || b[2] >= data[2] - 1 || cellText(cell).isBlank()) continue;
            return b[2] + 1;
        }
        var labelEnd = data[0] - 1;
        for (var cell : cells(facts, sheetId)) {
            var b = bounds(cellRange(cell));
            if (b == null || cellText(cell).isBlank() || b[1] > header[3] || b[3] < header[1]
                    || b[0] < data[0] || b[2] >= data[2] - 1) continue;
            labelEnd = Math.max(labelEnd, b[2]);
        }
        return Math.max(data[0] + 1, Math.min(data[2] - 1, labelEnd + 1));
    }

    private boolean hasRecordSurface(JsonNode facts, String sheetId, int startColumn, int endColumn, int row) {
        var covered = 0;
        for (int column = startColumn; column <= endColumn; column++) {
            var cell = findCell(facts, sheetId, column, row);
            if (cell != null && (!cellText(cell).isBlank() || hasBorder(cell)
                    || cell.path("inputCandidate").asBoolean(false))) covered++;
        }
        return covered >= Math.max(2, (int) Math.ceil((endColumn - startColumn + 1) * 0.7));
    }

    private boolean hasBorder(JsonNode cell) {
        return cell.path("hasBorder").asBoolean(false)
                || cell.path("bordered").asBoolean(false)
                || cell.path("style").path("bd").isObject()
                || !cell.path("borderSignature").asText("").isBlank();
    }

    private boolean isAxisLabel(String value) {
        return Set.of("属性", "项目", "指标", "参数", "字段", "特性", "项目/属性", "属性/项目")
                .contains(value == null ? "" : value.strip());
    }

    private List<String> rowLabelPath(JsonNode facts, String sheetId, int row, int firstRecordColumn) {
        var labels = cells(facts, sheetId).stream()
                .filter(cell -> {
                    var b = bounds(cellRange(cell));
                    return b != null && b[0] < firstRecordColumn && b[1] <= row && b[3] >= row
                            && !cellText(cell).isBlank() && !isFormulaCell(cell);
                })
                .sorted(Comparator.comparingInt((JsonNode cell) -> bounds(cellRange(cell))[0])
                        .thenComparingInt(cell -> bounds(cellRange(cell))[2]))
                .map(this::cellText)
                .distinct()
                .toList();
        return List.copyOf(labels);
    }

    private void ensureTableModel(ObjectNode parent, JsonNode region) {
        var kind = parent.path("kind").asText(region.path("type").asText("ROW_TABLE"));
        var details = region.path("structure");
        var headerRange = parent.path("headerRange").asText(details.path("headerRange").asText(""));
        var dataRange = parent.path("dataRange").asText(details.path("dataRange").asText(""));
        if (bounds(headerRange) == null || bounds(dataRange) == null) return;
        var repeatAxis = "COLUMN_TABLE".equals(kind) ? "COLUMN" : "ROW";
        parent.put("repeatAxis", parent.path("repeatAxis").asText(repeatAxis))
                .put("recordHeight", parent.path("recordHeight").asInt(details.path("recordHeight").asInt(1)))
                .put("recordWidth", parent.path("recordWidth").asInt(details.path("recordWidth").asInt(1)))
                .put("recordStride", parent.path("recordStride").asInt(details.path("recordStride").asInt(1)));
        var model = objectMapper.createObjectNode()
                .put("headerRange", headerRange)
                .put("dataRange", dataRange)
                .put("totalRange", details.path("totalRange").asText(""))
                .put("repeatAxis", parent.path("repeatAxis").asText(repeatAxis))
                .put("recordHeight", parent.path("recordHeight").asInt(1))
                .put("recordWidth", parent.path("recordWidth").asInt(1))
                .put("recordStride", parent.path("recordStride").asInt(1));
        model.set("columns", parent.path("columns").deepCopy());
        if (details.path("terminationRule").isObject()) {
            model.set("terminationRule", details.path("terminationRule").deepCopy());
        }
        parent.set("tableModel", model);
    }

    private ObjectNode column(
            String name, String unit, String code, String valueRange, String labelRange, String type
    ) {
        return objectMapper.createObjectNode().put("name", name).put("code", code)
                .put("fieldCode", "TABLE.COLUMN." + code).put("dataPath", "/records/*/" + code)
                .put("labelRange", labelRange).put("valueRange", valueRange)
                .put("valueType", type).put("editability", "EDITABLE").put("unit", unit)
                .put("valueSource", "USER_INPUT").put("fieldOrigin", "TEMPLATE_LOCAL")
                .put("standardSelectionStatus", "CUSTOM");
    }

    private HeaderLabel headerLabel(String value) {
        var normalized = normalizedLabelName(value);
        var matcher = java.util.regex.Pattern.compile(
                "^(.+?)[（(]\\s*([^（）()]{1,12})\\s*[）)]$").matcher(normalized);
        if (!matcher.matches()) return new HeaderLabel(normalized, "");
        return new HeaderLabel(matcher.group(1).strip(), matcher.group(2).strip());
    }

    private String normalizedLabelName(String value) {
        if (value == null) return "";
        return value.strip()
                .replaceAll("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})", "")
                .replaceAll("\\s{2,}", " ");
    }

    private String fieldCode(String name, int ordinal) {
        var normalized = name.replaceAll("[^\\p{L}\\p{Nd}_]+", "_").replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) normalized = "field_" + ordinal;
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String sampleType(JsonNode facts, String sheetId, int column, int row) {
        var cell = findCell(facts, sheetId, column, row);
        if (cell == null) return "string";
        if (cell.path("value").isBoolean()) return "boolean";
        if (cell.path("value").isNumber()) return "number";
        var value = cellText(cell);
        if (value.matches("[-+]?\\d+(?:[.,]\\d+)?%?")) return "number";
        return "string";
    }

    private JsonNode findHeaderCell(JsonNode facts, String sheetId, int column, int row) {
        return findCell(facts, sheetId, column, row);
    }

    private JsonNode findRowLabel(JsonNode facts, String sheetId, int row, int firstRecordColumn) {
        JsonNode selected = null;
        var selectedEndColumn = -1;
        for (var cell : cells(facts, sheetId)) {
            var b = bounds(cellRange(cell));
            if (b == null || b[1] > row || b[3] < row || b[0] >= firstRecordColumn) continue;
            // A group merge spanning several rows is context, not a field by
            // itself. Materialize the rightmost leaf that starts on this row;
            // rowLabelPath() retains every enclosing group as internal context.
            if (b[1] != row || cellText(cell).isBlank() || isFormulaCell(cell)) continue;
            if (b[3] > b[1] && (b[2] != firstRecordColumn - 1
                    || !hasEnclosingParentLabel(facts, sheetId, b, row))) continue;
            if (b[2] > selectedEndColumn) {
                selected = cell;
                selectedEndColumn = b[2];
            }
        }
        return selected;
    }

    private boolean hasEnclosingParentLabel(
            JsonNode facts, String sheetId, int[] leafBounds, int row
    ) {
        for (var cell : cells(facts, sheetId)) {
            var candidate = bounds(cellRange(cell));
            if (candidate == null || cellText(cell).isBlank() || isFormulaCell(cell)) continue;
            if (candidate[0] < leafBounds[0] && candidate[2] < leafBounds[0]
                    && candidate[1] <= row && candidate[3] >= row) return true;
        }
        return false;
    }

    private String firstText(JsonNode facts, String sheetId, String range) {
        var b = bounds(range);
        if (b == null) return "";
        return cells(facts, sheetId).stream()
                .filter(cell -> {
                    var cb = bounds(cellRange(cell));
                    return cb != null && cb[1] >= b[1] && cb[3] <= b[3]
                            && cb[0] <= b[2] && cb[2] >= b[0] && !cellText(cell).isBlank();
                })
                .sorted(Comparator.comparingInt(cell -> bounds(cellRange(cell))[1]))
                .map(this::cellText).findFirst().orElse("");
    }

    private JsonNode findCell(JsonNode facts, String sheetId, int column, int row) {
        var address = address(column, row);
        // Prefer the exact physical cell over a containing merged candidate.
        // A blank G2:J2 input surface must not hide the semantic H2 label when
        // form coordinates are compiled from the union of snapshots.
        for (var cell : cells(facts, sheetId)) {
            if (address.equalsIgnoreCase(cell.path("address").asText(""))) return cell;
        }
        for (var cell : cells(facts, sheetId)) {
            var b = bounds(cellRange(cell));
            if (b != null && column >= b[0] && column <= b[2] && row >= b[1] && row <= b[3]) return cell;
        }
        return null;
    }

    private List<JsonNode> cells(JsonNode facts, String sheetId) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (var sheet : facts.path("sheets")) {
            var id = sheet.path("id").asText(sheet.path("sheetId").asText(""));
            if (!sheetId.equals(id)) continue;
            for (var key : List.of("candidateCells", "physicalCells", "semanticCells")) {
                for (var cell : sheet.path(key)) {
                    var address = cell.path("address").asText(cellRange(cell));
                    if (address.isBlank()) continue;
                    result.putIfAbsent(address.toUpperCase(Locale.ROOT), cell);
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    private String cellText(JsonNode cell) {
        if (cell == null || cell.isMissingNode() || cell.isNull()) return "";
        var text = cell.path("value").asText("");
        if (text.isBlank()) text = cell.path("displayValue").asText("");
        return text.replaceAll("[\\r\\n]+", " ").strip();
    }

    private boolean isFormulaCell(JsonNode cell) {
        if (cell == null || cell.isMissingNode() || cell.isNull()) return false;
        return cell.path("formula").asBoolean(false)
                || cell.path("formula").isTextual()
                || "FORMULA".equalsIgnoreCase(cell.path("factType").asText(""))
                || "FORMULA".equalsIgnoreCase(cell.path("valueType").asText(""))
                || isFormulaText(cell.path("value").asText(""));
    }

    private boolean isFormulaText(String value) {
        return value != null && value.stripLeading().startsWith("=");
    }

    private String cellRange(JsonNode cell) {
        return cell.path("mergedRange").asText(cell.path("address").asText(""));
    }

    private String valueRange(int startColumn, int startRow, int endColumn, int endRow) {
        return address(startColumn, startRow) + ":" + address(endColumn, endRow);
    }

    private String address(int column, int row) {
        var result = new StringBuilder();
        var n = column;
        while (n > 0) {
            var rem = (n - 1) % 26;
            result.append((char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return result.reverse() + Integer.toString(row);
    }

    private int columnNumber(String value) {
        var text = value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        var result = 0;
        for (var c : text.toCharArray()) result = result * 26 + c - 'A' + 1;
        return result;
    }

    private int[] bounds(String value) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.replace("$", "").replace(" ", "");
        var parts = normalized.split(":", 2);
        var first = cellBounds(parts[0]);
        var last = cellBounds(parts.length == 1 ? parts[0] : parts[1]);
        if (first == null || last == null) return null;
        return new int[]{Math.min(first[0], last[0]), Math.min(first[1], last[1]),
                Math.max(first[2], last[2]), Math.max(first[3], last[3])};
    }

    private int[] cellBounds(String value) {
        if (value == null || value.isBlank()) return null;
        var match = java.util.regex.Pattern.compile("^([A-Z]+)(\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(value.strip());
        if (!match.matches()) return null;
        var column = columnNumber(match.group(1));
        var row = Integer.parseInt(match.group(2));
        return new int[]{column, row, column, row};
    }
}
