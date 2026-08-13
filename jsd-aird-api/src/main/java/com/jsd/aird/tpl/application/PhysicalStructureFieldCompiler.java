package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
    private final CanonicalMatrixCompiler matrixCompiler;

    public PhysicalStructureFieldCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.matrixCompiler = new CanonicalMatrixCompiler(objectMapper);
    }

    public void enrichParent(ObjectNode parent, JsonNode region, JsonNode facts) {
        var kind = parent.path("kind").asText(region.path("type").asText(""));
        if (Set.of("ROW_TABLE", "COLUMN_TABLE").contains(kind)) {
            ensureComponentDataPath(parent);
            var columns = parent.withArray("columns");
            if (columns.isEmpty()) {
                columns.addAll(buildColumns(parent, region, facts));
            }
            ensureTableProjection(parent, region, facts);
        } else if ("MATRIX".equals(kind)) {
            ensureComponentDataPath(parent);
            // normalizeMatrixRegion normally supplies these artifacts. Keep a
            // physical-only fallback here for older recognition runs.
            if (!parent.path("matrixModel").isObject()) {
                ensureMatrixProjection(parent, region, facts);
            }
        }
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
            case "MATRIX" -> matrixChildren(parent, region, facts);
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
                    .put("candidateRef", region.path("regionId").asText(""))
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
            var label = rawLabel.replaceFirst("[：:]\\s*$", "").strip();
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
        var label = cellText(labelCell).replaceFirst("[：:]\\s*$", "").strip();
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
                    .put("candidateRef", parent.path("candidateRef").asText(""))
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
            if (column.path("rowAttributes").isArray()) {
                child.set("rowAttributes", column.path("rowAttributes").deepCopy());
            }
            var locator = objectMapper.createObjectNode()
                    .put("sheetId", parent.path("locator").path("sheetId").asText(""))
                    .put("sheetName", parent.path("locator").path("sheetName").asText(""))
                    .put("address", valueRange)
                    .put("range", valueRange)
                    .put("valueRange", valueRange)
                    .put("labelRange", column.path("labelRange").asText(""))
                    .put("parentRange", parent.path("locator").path("dataRange")
                            .asText(parent.path("locator").path("range").asText("")))
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

    private List<RecognitionModelClient.ModelSuggestion> matrixChildren(
            ObjectNode parent, JsonNode region, JsonNode facts
    ) {
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        var bindings = parent.path("matrixModel").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            ensureMatrixProjection(parent, region, facts);
            bindings = parent.path("matrixModel").path("bindings");
        }
        var seen = new HashSet<String>();
        for (var binding : bindings) {
            var bindingKind = binding.path("bindingKind").asText("");
            if ("COLUMN_MEMBER".equals(bindingKind)) continue;
            var name = binding.path("name").asText("").strip();
            if (name.isBlank() && "MEASURE".equals(bindingKind)) name = "交叉值";
            var sourceRange = binding.path("sourceRange").asText("");
            if (name.isBlank() || sourceRange.isBlank()) continue;
            var code = binding.path("code").asText(bindingKind.toLowerCase(Locale.ROOT));
            if (!seen.add(bindingKind + "|" + code + "|" + sourceRange)) continue;
            result.add(matrixChild(parent, bindingKind, code, name, sourceRange,
                    binding.path("valueType").asText("MEASURE".equals(bindingKind) ? "number" : "string")));
        }
        // A minimal cross-tab often has no named row axis. Expose the physical
        // row header as one local dimension instead of silently dropping it.
        if (result.stream().noneMatch(item -> "ROW_DIMENSION".equals(item.payload().path("bindingKind").asText()))) {
            var rowRange = parent.path("rowHeaderRange").asText(
                    parent.path("locator").path("rowHeaderRange").asText(""));
            if (!rowRange.isBlank()) {
                var name = firstText(facts, parent.path("locator").path("sheetId").asText(""), rowRange);
                var corner = parent.path("cornerRange").asText(
                        parent.path("locator").path("cornerRange").asText(""));
                if (!corner.isBlank()) {
                    var cornerText = firstText(facts, parent.path("locator").path("sheetId").asText(""), corner);
                    if (!cornerText.isBlank()) name = cornerText.split("[/／]", 2)[0].strip();
                }
                if (name.isBlank()) name = "行维度";
                result.add(matrixChild(parent, "ROW_DIMENSION", "row_dimension", name, rowRange, "string"));
            }
        }
        return List.copyOf(result);
    }

    private RecognitionModelClient.ModelSuggestion matrixChild(
            ObjectNode parent, String bindingKind, String code, String name, String sourceRange, String valueType
    ) {
        var parentRelationId = parent.path("relationId").asText("");
        var relationId = parentRelationId + "|physical-matrix|" + code + "|"
                + RecognitionIdentity.normalizeRange(sourceRange);
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var bindingId = RecognitionIdentity.bindingId(fieldId, "MATRIX_REGION",
                parent.path("locator").path("sheetId").asText("") + "|" + sourceRange);
        var parentPath = parent.path("dataPath").asText("");
        var child = objectMapper.createObjectNode()
                .put("kind", "SCALAR")
                .put("suggestionLevel", "CHILD")
                .put("mappingKind", "MATRIX_FIELD")
                .put("bindingKind", bindingKind)
                .put("relationId", relationId)
                .put("modelRelationId", parentRelationId)
                .put("fieldId", fieldId.toString())
                .put("bindingId", bindingId.toString())
                .put("parentRelationId", parentRelationId)
                .put("parentFieldId", parent.path("fieldId").asText(""))
                .put("parentBindingId", parent.path("bindingId").asText(""))
                .put("regionId", parent.path("regionId").asText(parent.path("blockId").asText("")))
                .put("blockId", parent.path("blockId").asText(parent.path("regionId").asText("")))
                .put("candidateRef", parent.path("candidateRef").asText(""))
                .put("fieldCode", "MATRIX." + bindingKind + "." + code)
                .put("dataPath", parentPath + "/*/" + ("MEASURE".equals(bindingKind) ? "value" : code))
                .put("fieldName", name)
                .put("groupName", parent.path("groupName").asText("业务数据"))
                .put("valueType", valueType)
                .put("role", "FIELD")
                .put("locatorType", "MATRIX_REGION")
                .put("editability", "EDITABLE")
                .put("valueSource", "USER_INPUT")
                .put("sourceRange", sourceRange)
                .put("candidateOnly", parent.path("candidateOnly").asBoolean(true))
                .put("reviewRequired", parent.path("reviewRequired").asBoolean(true))
                .put("publishable", false)
                .put("physicalStructureOnly", parent.path("physicalStructureOnly").asBoolean(true))
                .put("pendingReason", "PHYSICAL_MATRIX_AXIS_REVIEW")
                .put("canonicalStatus", parent.path("canonicalStatus").asText("PROVISIONAL"))
                .put("structureStatus", parent.path("structureStatus").asText("PROVISIONAL"))
                .put("recognitionOrigin", "PHYSICAL_MATRIX_COMPILER")
                .put("nameSource", "PHYSICAL_HEADER_FALLBACK")
                .put("fieldOrigin", "TEMPLATE_LOCAL")
                .put("standardSelectionStatus", "CUSTOM")
                .put("standardRequired", false)
                .put("requiresStandardConfirmation", false)
                .put("reason", "字段来自矩阵的" + ("MEASURE".equals(bindingKind) ? "交叉指标" : "行维度或行属性") + "结构。")
                .put("interpretation", "按行维度、列成员和指标值展开为长表记录。");
        child.set("locator", parent.path("locator").deepCopy());
        if (child.path("locator") instanceof ObjectNode locator) {
            locator.put("sourceRange", sourceRange).put("logicalInputRange", sourceRange);
        }
        return new RecognitionModelClient.ModelSuggestion(
                "MATRIX_FIELD", child, 0.9,
                objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .put("source", "PHYSICAL_MATRIX_COMPILER")
                        .put("bindingKind", bindingKind)
                        .put("sourceRange", sourceRange)));
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
                var name = cellText(cell);
                if (name.isBlank() || isFormulaText(name)) continue;
                var physicalLabelRange = cellRange(cell);
                var normalizedLabelRange = RecognitionIdentity.normalizeRange(physicalLabelRange);
                if (!seenHeaderRanges.add(normalizedLabelRange)) continue;
                var labelBounds = bounds(physicalLabelRange);
                var valueStartColumn = labelBounds == null ? column : Math.max(header[0], labelBounds[0]);
                var valueEndColumn = labelBounds == null ? column : Math.min(header[2], labelBounds[2]);
                var code = fieldCode(name, column);
                result.add(column(name, code,
                        valueRange(valueStartColumn, data[1], valueEndColumn, data[3]),
                        physicalLabelRange, sampleType(facts,
                                parent.path("locator").path("sheetId").asText(""),
                                valueStartColumn, data[1])));
            }
        } else {
            var projection = details.path("recordProjection");
            var recordColumns = new ArrayList<Integer>();
            for (var value : projection.path("recordColumns")) {
                var parsed = columnNumber(value.asText(""));
                if (parsed > 0) recordColumns.add(parsed);
            }
            if (recordColumns.isEmpty()) {
                var start = data[0] + 1;
                for (int column = start; column <= data[2]; column++) recordColumns.add(column);
            }
            var firstRecordColumn = recordColumns.stream().min(Comparator.naturalOrder()).orElse(data[0] + 1);
            if (details.path("fieldRows").isArray() && !details.path("fieldRows").isEmpty()) {
                var usedCodes = new HashSet<String>();
                for (var fieldRow : details.path("fieldRows")) {
                    var row = fieldRow.path("row").asInt();
                    if (row < data[1] || row > data[3]) continue;
                    var structuralPath = fieldRow.path("labelPath").asText("").strip();
                    if (structuralPath.isBlank()) continue;
                    var segments = fieldRow.path("labelPathSegments");
                    var name = segments.isArray() && !segments.isEmpty()
                            ? segments.get(segments.size() - 1).asText(structuralPath) : structuralPath;
                    var completeSegments = objectMapper.createArrayNode();
                    if (segments.isArray()) segments.forEach(completeSegments::add);
                    var semanticIdentity = new StringBuilder(structuralPath);
                    for (var attribute : fieldRow.path("rowAttributes")) {
                        var attributeLabel = attribute.path("label").asText("").strip();
                        var attributeValue = attribute.path("value").asText("").strip();
                        if (attributeValue.isBlank()) continue;
                        semanticIdentity.append('|').append(attributeLabel).append('=').append(attributeValue);
                        var alreadyPresent = false;
                        for (var segment : completeSegments) {
                            if (attributeValue.equals(segment.asText(""))) {
                                alreadyPresent = true;
                                break;
                            }
                        }
                        if (!alreadyPresent) completeSegments.add(attributeValue);
                    }
                    var completePath = new ArrayList<String>();
                    completeSegments.forEach(segment -> {
                        if (!segment.asText("").isBlank()) completePath.add(segment.asText());
                    });
                    var baseCode = fieldCode(semanticIdentity.toString(), row);
                    var code = baseCode;
                    var duplicate = 2;
                    while (!usedCodes.add(code)) code = baseCode + "_" + duplicate++;
                    var item = column(name, code,
                            fieldRow.path("valueRange").asText(
                                    valueRange(firstRecordColumn, row, recordColumns.getLast(), row)),
                            fieldRow.path("labelRange").asText(address(data[0], row)),
                            sampleType(facts, parent.path("locator").path("sheetId").asText(""),
                                    firstRecordColumn, row));
                    item.put("labelPath", completePath.isEmpty()
                                    ? structuralPath : String.join(" > ", completePath))
                            .set("labelPathSegments", completeSegments);
                    item.set("rowAttributes", fieldRow.path("rowAttributes").deepCopy());
                    item.put("valueSource", fieldRow.path("valueSource").asText("USER_INPUT"))
                            .put("editability", fieldRow.path("editability").asText("EDITABLE"))
                            .put("trainingEligible", fieldRow.path("trainingEligible").asBoolean(true))
                            .put("trainingRole", fieldRow.path("trainingRole").asText("FEATURE"))
                            .put("formulaDerived", fieldRow.path("formulaDerived").asBoolean(false))
                            .put("calculationTrustStatus", fieldRow.path("calculationTrustStatus")
                                    .asText("NOT_APPLICABLE"));
                    result.add(item);
                }
                return result;
            }
            for (int row = data[1]; row <= data[3]; row++) {
                var label = findRowLabel(facts, parent.path("locator").path("sheetId").asText(""),
                        row, firstRecordColumn);
                if (label == null || cellText(label).isBlank()) continue;
                var name = cellText(label);
                var code = fieldCode(name, row);
                result.add(column(name, code,
                        valueRange(firstRecordColumn, row, recordColumns.getLast(), row),
                        cellRange(label), sampleType(facts, parent.path("locator").path("sheetId").asText(""), firstRecordColumn, row)));
            }
        }
        return result;
    }

    private void ensureTableProjection(ObjectNode parent, JsonNode region, JsonNode facts) {
        var kind = parent.path("kind").asText(region.path("type").asText("ROW_TABLE"));
        var details = region.path("structure");
        var headerRange = parent.path("headerRange").asText(details.path("headerRange").asText(""));
        var dataRange = parent.path("dataRange").asText(details.path("dataRange").asText(""));
        var h = bounds(headerRange);
        var d = bounds(dataRange);
        if (h == null || d == null) return;
        var projection = parent.path("recordProjection").isObject()
                ? (ObjectNode) parent.path("recordProjection") : objectMapper.createObjectNode();
        if (projection.isEmpty() && details.path("recordProjection").isObject()) {
            projection = (ObjectNode) details.path("recordProjection").deepCopy();
            parent.set("recordProjection", projection);
        }
        for (var key : List.of("fieldGroups", "fieldRows")) {
            if (!parent.path(key).isArray() && details.path(key).isArray()) {
                parent.set(key, details.path(key).deepCopy());
            }
        }
        if (projection.isEmpty()) {
            projection = matrixCompiler.recordProjection(h[1], d[0], d[2], h[1], d[1], d[3],
                    "COLUMN_TABLE".equals(kind) ? "COLUMN" : "ROW");
            parent.set("recordProjection", projection);
        }
        if (projection.path("recordIdentity").isObject()) {
            parent.set("recordIdentity", projection.path("recordIdentity").deepCopy());
        }
        if (projection.path("rowAttributeColumns").isArray()) {
            parent.set("rowAttributeColumns", projection.path("rowAttributeColumns").deepCopy());
        }
        if ("COLUMN_TABLE".equals(kind)) {
            parent.set("columnSlots", matrixCompiler.columnSlots(
                    parent.path("locator").path("sheetId").asText(""), parent.path("blockId").asText(""),
                    parent.path("locator").path("range").asText(""), d[0] + 1, d[2], h[1], d[3]));
        }
        var slots = parent.path("columnSlots").isArray()
                ? (ArrayNode) parent.path("columnSlots") : objectMapper.createArrayNode();
        if ("COLUMN_TABLE".equals(kind)
                && (!parent.path("longTableModel").isObject()
                || !parent.path("longTableModel").path("records").isArray()
                || parent.path("longTableModel").path("records").isEmpty())) {
            parent.set("longTableModel", compileColumnTableProjection(
                    facts, parent, d, headerRange, dataRange, projection, slots));
        } else if (!parent.path("longTableModel").isObject()) {
            var rowHeaderRange = "ROW_TABLE".equals(kind)
                    ? headerRange
                    : columnLabelRange(d, projection);
            parent.set("longTableModel", matrixCompiler.compileLongTableModel(
                    facts, parent.path("locator").path("sheetId").asText(""), parent.path("blockId").asText(""),
                    kind, parent.path("locator").path("range").asText(dataRange), "",
                    rowHeaderRange, headerRange, dataRange, projection, slots, objectMapper.createArrayNode()));
            parent.with("longTableModel").put("output", "COLUMN_TABLE".equals(kind)
                    ? "ONE_RECORD_PER_COLUMN" : "ONE_RECORD_PER_ROW");
        }
    }

    private String columnLabelRange(int[] data, ObjectNode projection) {
        var firstRecordColumn = data[0] + 1;
        for (var value : projection.path("recordColumns")) {
            var parsed = columnNumber(value.asText(""));
            if (parsed > data[0]) firstRecordColumn = Math.min(firstRecordColumn, parsed);
        }
        if (firstRecordColumn <= data[0]) return "";
        return valueRange(data[0], data[1], firstRecordColumn - 1, data[3]);
    }

    private ObjectNode compileColumnTableProjection(
            JsonNode facts, ObjectNode parent, int[] data, String headerRange, String dataRange,
            ObjectNode projection, ArrayNode slots
    ) {
        var sheetId = parent.path("locator").path("sheetId").asText("");
        var regionId = parent.path("blockId").asText(parent.path("regionId").asText(""));
        var result = objectMapper.createObjectNode()
                .put("schemaVersion", 1).put("sourceKind", "COLUMN_TABLE")
                .put("semanticMode", "RECORD_SET").put("layoutMode", "LONG_FORM")
                .put("sourceRange", parent.path("locator").path("range").asText(dataRange))
                .put("rowHeaderRange", columnLabelRange(data, projection))
                .put("columnHeaderRange", headerRange).put("dataRange", dataRange)
                .put("aggregatePolicy", "INCLUDE_MARKED")
                .put("blankAxisPolicy", "SKIP_EMPTY_RUNTIME_MEMBER")
                .put("trainingPolicy", "REQUIRE_RUNTIME_MEMBER")
                .put("projectionStatus", "COLUMN_RECORDS")
                .put("output", "ONE_RECORD_PER_COLUMN");
        result.set("recordProjection", projection.deepCopy());
        result.set("columnSlots", slots.deepCopy());
        result.set("rowSlots", objectMapper.createArrayNode());
        result.set("dimensions", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("code", "column_member").put("name", "列成员")
                .put("role", "COLUMN_DIMENSION").put("sourceRange", headerRange)));
        result.set("measure", objectMapper.createObjectNode().put("code", "fields")
                .put("name", "属性字段").put("sourceRange", dataRange));
        var records = result.putArray("records");
        var recordColumns = new ArrayList<Integer>();
        for (var value : projection.path("recordColumns")) {
            var parsed = columnNumber(value.asText(""));
            if (parsed >= data[0] && parsed <= data[2]) recordColumns.add(parsed);
        }
        if (recordColumns.isEmpty()) {
            for (int column = data[0] + 1; column <= data[2]; column++) recordColumns.add(column);
        }
        for (var column : recordColumns) {
            var headerCell = findCell(facts, sheetId, column, bounds(headerRange)[1]);
            var memberName = cellText(headerCell);
            var memberStatus = memberName.isBlank() ? "EMPTY" : "POPULATED";
            var record = objectMapper.createObjectNode()
                    .put("recordKey", sheetId + "|" + regionId + "|COLUMN|" + address(column, bounds(headerRange)[1]))
                    .put("recordId", sheetId + "|" + regionId + "|COLUMN|" + address(column, bounds(headerRange)[1]))
                    .put("entityRecordId", sheetId + "|" + regionId + "|COLUMN|" + address(column, bounds(headerRange)[1]))
                    .put("rowIndex", data[1]).put("columnIndex", column).put("rowRole", "TEST_ITEM")
                    .put("trainingEligible", !memberName.isBlank()).put("sampleAddress", address(column, bounds(headerRange)[1]))
                    .put("sampleName", memberName);
            record.putArray("rowPath").add(memberName);
            record.set("rowDimensions", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                    .put("code", "column_member").put("value", memberName)
                    .put("sourceAddress", address(column, bounds(headerRange)[1]))));
            record.set("rowAttributes", objectMapper.createArrayNode());
            record.set("columnMember", objectMapper.createObjectNode()
                    .put("coordinate", columnLetters(column)).put("address", address(column, bounds(headerRange)[1]))
                    .put("label", memberName).put("status", memberStatus)
                    .put("instanceStatus", memberStatus).put("role", "COLUMN_MEMBER_INPUT"));
            var values = record.putArray("values");
            JsonNode firstValue = null;
            String firstAddress = address(column, data[1]);
            for (int row = data[1]; row <= data[3]; row++) {
                var labelCell = findRowLabel(facts, sheetId, row, column);
                var valueCell = findCell(facts, sheetId, column, row);
                var valueAddress = address(column, row);
                var formula = valueCell != null && valueCell.path("formula").isTextual();
                var value = objectMapper.createObjectNode().put("address", valueAddress)
                        .put("label", labelCell == null ? "" : cellText(labelCell))
                        .put("valueSource", formula ? "FORMULA" : "USER_INPUT")
                        .put("trainingEligible", !memberName.isBlank() && !formula);
                if (valueCell != null && valueCell.has("value")) value.set("value", valueCell.path("value").deepCopy());
                if (valueCell != null && valueCell.has("formula")) value.set("formula", valueCell.path("formula").deepCopy());
                if (firstValue == null) {
                    firstValue = value.deepCopy();
                    firstAddress = valueAddress;
                }
                values.add(value);
            }
            record.set("value", firstValue == null
                    ? objectMapper.createObjectNode().put("address", firstAddress).put("trainingEligible", false)
                    : firstValue);
            record.put("valueAddress", firstAddress);
            records.add(record);
        }
        var eligibleCount = 0;
        var emptyMemberCount = 0;
        for (var record : records) {
            if (record.path("trainingEligible").asBoolean(false)) eligibleCount++;
            if ("EMPTY".equals(record.path("columnMember").path("instanceStatus").asText())) emptyMemberCount++;
        }
        result.set("trainingSummary", objectMapper.createObjectNode()
                .put("total", records.size()).put("eligible", eligibleCount)
                .put("testItem", records.size()).put("replicate", 0).put("aggregate", 0).put("unknown", 0)
                .put("emptyRuntimeMember", emptyMemberCount)
                .put("formulaExcluded", 0));
        return result;
    }

    private String columnLetters(int column) {
        var result = new StringBuilder();
        var value = column;
        while (value > 0) {
            var rem = (value - 1) % 26;
            result.append((char) ('A' + rem));
            value = (value - 1) / 26;
        }
        return result.reverse().toString();
    }

    private void ensureMatrixProjection(ObjectNode parent, JsonNode region, JsonNode facts) {
        var details = region.path("structure");
        var range = parent.path("locator").path("range").asText(region.path("range").asText(""));
        var corner = details.path("cornerRange").asText("");
        var rowHeader = details.path("rowHeaderRange").asText("");
        var columnHeader = details.path("columnHeaderRange").asText("");
        var crossData = details.path("crossDataRange").asText("");
        var cb = bounds(corner);
        var db = bounds(crossData);
        if (cb == null || db == null || rowHeader.isBlank() || columnHeader.isBlank()) return;
        var projection = matrixCompiler.recordProjection(cb[3], db[0], db[2], cb[3], db[1], db[3],
                details.path("recordAxis").asText("COLUMN"));
        var slots = matrixCompiler.columnSlots(parent.path("locator").path("sheetId").asText(""),
                parent.path("blockId").asText(""), range, db[0], db[2], cb[3], db[3]);
        var artifacts = matrixCompiler.compileMatrixArtifacts(facts,
                parent.path("locator").path("sheetId").asText(""), parent.path("blockId").asText(""),
                range, corner, rowHeader, columnHeader, crossData, objectMapper.createArrayNode(),
                projection, slots, objectMapper.createArrayNode(), "PROVISIONAL");
        parent.set("matrixModel", artifacts.path("matrixModel").deepCopy());
        parent.set("tableModel", artifacts.path("tableModel").deepCopy());
        parent.set("longTableModel", artifacts.path("longTableModel").deepCopy());
        parent.set("recordProjection", artifacts.path("recordProjection").deepCopy());
        parent.set("columnSlots", artifacts.path("columnSlots").deepCopy());
        parent.put("rowHeaderRange", rowHeader).put("columnHeaderRange", columnHeader)
                .put("crossDataRange", crossData).put("cornerRange", corner);
    }

    private ObjectNode column(String name, String code, String valueRange, String labelRange, String type) {
        return objectMapper.createObjectNode().put("name", name).put("code", code)
                .put("fieldCode", "TABLE.COLUMN." + code).put("dataPath", "/records/*/" + code)
                .put("labelRange", labelRange).put("valueRange", valueRange)
                .put("valueType", type).put("editability", "EDITABLE")
                .put("valueSource", "USER_INPUT").put("fieldOrigin", "TEMPLATE_LOCAL")
                .put("standardSelectionStatus", "CUSTOM");
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
        JsonNode fallback = null;
        for (var cell : cells(facts, sheetId)) {
            var b = bounds(cellRange(cell));
            if (b == null || b[1] > row || b[3] < row || b[0] >= firstRecordColumn) continue;
            if (!cellText(cell).isBlank()) {
                if (b[1] == row) return cell;
                if (fallback == null) fallback = cell;
            }
        }
        return fallback;
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
        for (var cell : cells(facts, sheetId)) {
            var b = bounds(cellRange(cell));
            if (b != null && column >= b[0] && column <= b[2] && row >= b[1] && row <= b[3]) return cell;
            if (address.equalsIgnoreCase(cell.path("address").asText(""))) return cell;
        }
        return null;
    }

    private List<JsonNode> cells(JsonNode facts, String sheetId) {
        var result = new ArrayList<JsonNode>();
        for (var sheet : facts.path("sheets")) {
            var id = sheet.path("id").asText(sheet.path("sheetId").asText(""));
            if (!sheetId.equals(id)) continue;
            sheet.path("semanticCells").forEach(result::add);
        }
        return result;
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
