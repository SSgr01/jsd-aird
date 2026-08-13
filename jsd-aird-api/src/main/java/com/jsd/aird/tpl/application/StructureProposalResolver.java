package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Compares independent backend/model structure proposals. Physical and model
 * geometry are both evidence, never an implicit formal decision. Equivalent
 * proposals are merged; conflicting proposals remain one explicit choice
 * group; model-only proposals remain candidates only when no physical region
 * exists.
 */
public final class StructureProposalResolver {

    private final ObjectMapper objectMapper;

    public StructureProposalResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode resolve(JsonNode structure, List<JsonNode> backendProposals, JsonNode modelPayload) {
        var result = objectMapper.createObjectNode();
        var regions = result.putArray("regions");
        var conflicts = result.putArray("conflictGroups");
        var resolutions = result.putArray("resolutionGroups");
        var suppressed = result.putArray("suppressedRegions");
        var diagnostics = result.putArray("diagnostics");
        var modelDiagnostics = result.putArray("modelDiagnostics");
        var semanticTargets = result.putArray("semanticTargets");
        var unresolvedTargets = result.putArray("unresolvedStructureTargets");
        var backend = backendProposals == null ? List.<JsonNode>of() : backendProposals;
        var model = modelPayload == null ? objectMapper.createArrayNode() : modelPayload.path("structureProposals");
        var usedModel = new LinkedHashSet<String>();
        var backendSignatures = new LinkedHashSet<String>();

        for (var primitive : backend) {
            var candidate = backendCandidate(primitive);
            if (candidate == null) continue;
            var key = signature(candidate);
            if (!backendSignatures.add(key)) continue;
            var exact = findExact(candidate, model, usedModel);
            var alternatives = overlappingModel(candidate, model, usedModel, structure, suppressed);
            if (exact != null && alternatives.isEmpty()) {
                usedModel.add(exact.path("proposalId").asText());
                confirmed(candidate, exact);
                regions.add(candidate);
                continue;
            }
            if (exact == null && alternatives.isEmpty() && hasSupportingFormPartition(candidate, model)) {
                confirmedByFormPartition(candidate);
                regions.add(candidate);
                continue;
            }
            if (isComponentPhysicallyDeterminate(candidate)) {
                // A determinate component is not selected merely because its
                // rectangle overlaps a model proposal.  Its direction,
                // repeated value surface and field bindings are independently
                // proven by workbook geometry.  Conflicting model rectangles
                // remain diagnostics/semantic hints and cannot create a
                // second formal structure for this component.
                alternatives.forEach(alternative -> {
                    usedModel.add(alternative.path("proposalId").asText());
                    suppressed.add(modelSuppression(alternative,
                            "MODEL_STRUCTURE_REJECTED_BY_DETERMINATE_COMPONENT",
                            "当前组件的记录方向、填写面和字段路径已有完整物理证据；模型建议仅用于补充字段语义"));
                });
                if (exact != null) usedModel.add(exact.path("proposalId").asText());
                confirmedByPhysicalEvidence(candidate, exact);
                regions.add(candidate);
                continue;
            }
            if (exact != null) {
                // The exact proposal is one viable answer, but other model
                // proposals in the same physical envelope are still competing
                // evidence. Keep all of them in one pending choice group.
                alternatives.add(0, exact);
            }
            if (!alternatives.isEmpty()) {
                var groupId = "structure-conflict-" + RecognitionIdentity.shortHash(
                        candidate.path("sheetId").asText() + "|" + candidate.path("range").asText(), 16);
                // Even a non-overlapping model partition is still only a model
                // hypothesis.  Exact area coverage proves that the rectangles
                // fit together; it does not prove that the workbook actually
                // has those business boundaries.  Keep the physical candidate
                // and the model partition in one pending choice group so that
                // neither source silently becomes the formal structure.
                var physicalAlternativeId = groupId + "-physical";
                candidate.put("structureStatus", "CONFLICT")
                        .put("canonicalStatus", "PROVISIONAL")
                        .put("structureConflict", true)
                        .put("reviewRequired", true)
                        .put("candidateOnly", true)
                        .put("physicalStructureOnly", true)
                        .put("pendingReason", "STRUCTURE_CONFLICT")
                        .put("resolutionGroupId", groupId)
                        .put("resolutionAlternativeId", physicalAlternativeId)
                        .put("modelAssessmentVerdict", "MODEL_CONFLICTS");
                var group = objectMapper.createObjectNode()
                        .put("resolutionGroupId", groupId)
                        .put("type", "STRUCTURE_CONFLICT")
                        .put("resolutionStatus", "PENDING");
                var groupAlternatives = group.putArray("alternatives");
                var modelAlternatives = candidate.putArray("modelAlternatives");
                for (var alternative : alternatives) {
                    usedModel.add(alternative.path("proposalId").asText());
                    modelAlternatives.add(alternative.deepCopy());
                }
                groupAlternatives.add(alternativeSet(
                        physicalAlternativeId, "PHYSICAL_HEURISTIC", List.of(candidate)));
                var hypothesisIndex = 0;
                var hypotheses = modelHypotheses(alternatives, candidate.path("range").asText(""));
                for (var hypothesis : hypotheses) {
                    var modelAlternativeId = groupId + "-model-" + (++hypothesisIndex);
                    hypothesis.forEach(region -> {
                        if (region instanceof ObjectNode object) {
                            object.put("resolutionAlternativeId", modelAlternativeId);
                        }
                    });
                    groupAlternatives.add(alternativeSet(modelAlternativeId, "MODEL", hypothesis));
                }
                candidate.set("structureAlternativeSets", groupAlternatives.deepCopy());
                conflicts.add(group);
            } else {
                candidate.put("structureStatus", "UNRESOLVED")
                        .put("canonicalStatus", "PROVISIONAL")
                        .put("reviewRequired", true)
                        .put("candidateOnly", true)
                        .put("physicalStructureOnly", true)
                        .put("pendingReason", "STRUCTURE_UNRESOLVED")
                        .put("modelAssessmentVerdict", "MODEL_UNRESOLVED");
                var suppressedTable = java.util.stream.StreamSupport.stream(suppressed.spliterator(), false)
                        .anyMatch(item -> candidate.path("sheetId").asText("").equals(item.path("sheetId").asText(""))
                                && overlap(candidate.path("range").asText(""), item.path("range").asText(""))
                                && "MODEL_TABLE_WITHOUT_REPEAT_EVIDENCE".equals(item.path("code").asText("")));
                if (suppressedTable) {
                    candidate.put("pendingReason", "PHYSICAL_FORM_FIELDS_READY")
                            .put("modelAssessmentVerdict", "MODEL_STRUCTURE_REJECTED")
                            .put("resolutionReason", "MODEL_TABLE_WITHOUT_REPEAT_EVIDENCE");
                }
            }
            regions.add(candidate);
        }

        // A model-only rectangle that does not overlap a physical candidate is
        // still useful evidence when the physical pass could not explain that
        // area. It remains provisional. Grouping is component-local: unrelated
        // regions on the same sheet must never become one all-or-nothing choice.
        var modelOnly = new ArrayList<ObjectNode>();
        for (var proposal : model) {
            if (!proposal.isObject() || usedModel.contains(proposal.path("proposalId").asText())) continue;
            var modelRegion = modelCandidate(proposal, structure);
            if (modelRegion == null) {
                modelDiagnostics.add(objectMapper.createObjectNode()
                        .put("code", "INVALID_MODEL_STRUCTURE_PROPOSAL")
                        .put("proposalId", proposal.path("proposalId").asText()));
                continue;
            }
            if (overlapsConfirmedPhysical(modelRegion, regions)) {
                modelDiagnostics.add(modelSuppression(proposal, "MODEL_ONLY_AFTER_CONFIRMED_PHYSICAL",
                        "同一组件已有物理与模型一致的正式候选，该模型区域仅保留为诊断信息"));
                continue;
            }
            var overlapsPhysical = backend.stream().anyMatch(physical ->
                    modelRegion.path("sheetId").asText().equals(physical.path("sheetId").asText())
                            && overlap(modelRegion.path("range").asText(), physical.path("range").asText()));
            if (overlapsPhysical) {
                modelDiagnostics.add(modelSuppression(proposal, "MODEL_ONLY_OVERLAPS_PHYSICAL",
                        "模型区域与物理候选重叠，不能单独成为正式区域"));
                continue;
            }
            modelRegion.put("candidateOnly", true)
                    .put("reviewRequired", true)
                    .put("physicalStructureOnly", false)
                    .put("structureStatus", "UNRESOLVED")
                    .put("canonicalStatus", "PROVISIONAL")
                    .put("pendingReason", "MODEL_ONLY_STRUCTURE")
                    .put("resolutionStatus", "PENDING")
                    .put("resolutionReason", "NO_PHYSICAL_STRUCTURE");
            modelOnly.add(modelRegion);
        }
        appendModelOnlyComponents(modelOnly, regions, conflicts, resolutions);

        validateSetOverlaps(regions, conflicts, diagnostics);
        // Formal semantic recognition is intentionally limited to regions that
        // both proposals confirmed or that a user explicitly resolves. Model
        // diagnostics never become REGION_FIELDS or coverage by themselves.
        appendSemanticTargets(semanticTargets, regions);
        result.set("canonicalSemanticTargets", semanticTargets.deepCopy());
        appendUnresolvedTargets(unresolvedTargets, regions);
        result.put("recognitionStatus", conflicts.isEmpty() && diagnostics.isEmpty()
                && allConfirmed(regions) ? "COMPLETE" : "REVIEW_REQUIRED");
        result.put("canonicalStatus", allConfirmed(regions) && conflicts.isEmpty()
                ? "CONFIRMED" : "PROVISIONAL");
        return result;
    }

    private boolean overlapsConfirmedPhysical(JsonNode modelRegion, ArrayNode regions) {
        for (var region : regions) {
            if (!"PHYSICAL_HEURISTIC".equals(region.path("source").asText())
                    || !"CONFIRMED".equals(region.path("canonicalStatus").asText())
                    || !"CONFIRMED".equals(region.path("structureStatus").asText())) continue;
            if (modelRegion.path("sheetId").asText().equals(region.path("sheetId").asText())
                    && overlap(modelRegion.path("range").asText(), region.path("range").asText())) return true;
        }
        return false;
    }

    /**
     * Builds model-only decision groups by independent spatial component. A
     * model supplied component id is honoured; otherwise only overlapping
     * rectangles are connected. Merely sharing a sheet is never evidence that
     * proposals belong to one decision.
     */
    private void appendModelOnlyComponents(
            List<ObjectNode> candidates, ArrayNode regions, ArrayNode conflicts, ArrayNode resolutions
    ) {
        var remaining = new LinkedHashSet<Integer>();
        for (int index = 0; index < candidates.size(); index++) remaining.add(index);
        while (!remaining.isEmpty()) {
            var seed = remaining.iterator().next();
            remaining.remove(seed);
            var component = new ArrayList<ObjectNode>();
            component.add(candidates.get(seed));
            var changed = true;
            while (changed) {
                changed = false;
                for (var index : List.copyOf(remaining)) {
                    var candidate = candidates.get(index);
                    if (component.stream().anyMatch(existing -> sameModelComponent(existing, candidate))) {
                        component.add(candidate);
                        remaining.remove(index);
                        changed = true;
                    }
                }
            }
            appendModelOnlyComponent(component, regions, conflicts, resolutions);
        }
    }

    private boolean sameModelComponent(JsonNode first, JsonNode second) {
        if (!first.path("sheetId").asText().equals(second.path("sheetId").asText())) return false;
        var firstExplicit = proposalText(first, "componentId");
        var secondExplicit = proposalText(second, "componentId");
        if (!firstExplicit.isBlank() || !secondExplicit.isBlank()) {
            return !firstExplicit.isBlank() && firstExplicit.equals(secondExplicit);
        }
        var firstHypothesis = proposalText(first, "hypothesisId");
        var secondHypothesis = proposalText(second, "hypothesisId");
        if (!firstHypothesis.isBlank() || !secondHypothesis.isBlank()) {
            return !firstHypothesis.isBlank() && firstHypothesis.equals(secondHypothesis);
        }
        return overlap(first.path("range").asText(), second.path("range").asText());
    }

    private void appendModelOnlyComponent(
            List<ObjectNode> component, ArrayNode regions, ArrayNode conflicts, ArrayNode resolutions
    ) {
        if (component.isEmpty()) return;
        var seed = component.getFirst();
        var explicitComponent = proposalText(seed, "componentId");
        var groupId = "structure-model-only-" + RecognitionIdentity.shortHash(
                seed.path("sheetId").asText() + "|"
                        + (explicitComponent.isBlank() ? componentEnvelope(component) : explicitComponent), 16);
        var hypotheses = modelHypotheses(new ArrayList<>(component), componentEnvelope(component));
        var alternatives = objectMapper.createArrayNode();
        var alternativeIndex = 0;
        for (var hypothesis : hypotheses) {
            var alternativeId = groupId + "-model-" + (++alternativeIndex);
            for (var item : hypothesis) {
                if (item instanceof ObjectNode region) {
                    region.put("componentId", explicitComponent.isBlank() ? groupId + "-component" : explicitComponent)
                            .put("resolutionGroupId", groupId)
                            .put("resolutionAlternativeId", alternativeId);
                }
            }
            alternatives.add(alternativeSet(alternativeId, "MODEL", hypothesis));
        }
        component.forEach(regions::add);
        var group = objectMapper.createObjectNode()
                .put("resolutionGroupId", groupId)
                .put("type", hypotheses.size() > 1 ? "STRUCTURE_CONFLICT" : "MODEL_ONLY_STRUCTURE")
                .put("resolutionStatus", "PENDING");
        group.set("alternatives", alternatives);
        component.forEach(region -> region.set("structureAlternativeSets", alternatives.deepCopy()));
        if (hypotheses.size() > 1) conflicts.add(group); else resolutions.add(group);
    }

    /**
     * A 90% envelope overlap may associate proposals with a physical
     * component, but never confirms one. Here it is used only to decide
     * whether non-overlapping regions with an explicit shared hypothesis form
     * one partition alternative.
     */
    private List<List<JsonNode>> modelHypotheses(List<? extends JsonNode> proposals, String componentRange) {
        var grouped = new LinkedHashMap<String, List<JsonNode>>();
        var anonymous = new ArrayList<JsonNode>();
        for (var proposal : proposals) {
            var explicit = proposalText(proposal, "hypothesisId");
            if (explicit.isBlank()) explicit = proposalText(proposal, "resolutionAlternativeId");
            if (explicit.isBlank()) anonymous.add(proposal);
            else grouped.computeIfAbsent("explicit-" + explicit, ignored -> new ArrayList<>()).add(proposal);
        }
        if (!anonymous.isEmpty()) {
            if (anonymous.size() > 1 && pairwiseNonOverlapping(anonymous)
                    && coverageRatio(componentRange, anonymous) >= 0.90) {
                grouped.put("anonymous-partition", anonymous);
            } else {
                var index = 0;
                for (var proposal : anonymous) {
                    grouped.put("anonymous-" + (++index), new ArrayList<>(List.of(proposal)));
                }
            }
        }
        var result = new ArrayList<List<JsonNode>>();
        for (var entry : grouped.entrySet()) {
            var values = entry.getValue();
            if (values.size() == 1) {
                result.add(values);
                continue;
            }
            var validPartition = pairwiseNonOverlapping(values)
                    && coverageRatio(componentRange, values) >= 0.90;
            if (validPartition) result.add(values);
            else values.forEach(value -> result.add(new ArrayList<>(List.of(value))));
        }
        return result;
    }

    private boolean pairwiseNonOverlapping(List<JsonNode> values) {
        for (int left = 0; left < values.size(); left++) {
            for (int right = left + 1; right < values.size(); right++) {
                if (overlap(values.get(left).path("range").asText(), values.get(right).path("range").asText())) {
                    return false;
                }
            }
        }
        return true;
    }

    private double coverageRatio(String envelope, List<JsonNode> values) {
        var outer = bounds(envelope);
        if (outer == null || area(outer) == 0) return 0;
        long covered = 0;
        for (var value : values) {
            var inner = bounds(value.path("range").asText());
            if (inner == null) continue;
            var intersection = intersection(outer, inner);
            if (intersection != null) covered += area(intersection);
        }
        return Math.min(1.0, covered / (double) area(outer));
    }

    private String componentEnvelope(List<? extends JsonNode> values) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = 0, bottom = 0;
        for (var value : values) {
            var cellBounds = bounds(value.path("range").asText());
            if (cellBounds == null) continue;
            left = Math.min(left, cellBounds[0]);
            top = Math.min(top, cellBounds[1]);
            right = Math.max(right, cellBounds[2]);
            bottom = Math.max(bottom, cellBounds[3]);
        }
        return left == Integer.MAX_VALUE ? "UNKNOWN"
                : excelColumn(left) + top + ":" + excelColumn(right) + bottom;
    }

    private String excelColumn(int column) {
        var result = new StringBuilder();
        for (var value = column; value > 0; value = (value - 1) / 26) {
            result.insert(0, (char) ('A' + (value - 1) % 26));
        }
        return result.toString();
    }

    private String proposalText(JsonNode candidate, String key) {
        var direct = candidate.path(key).asText("");
        if (!direct.isBlank()) return direct;
        return candidate.path("proposal").path(key).asText("");
    }

    private boolean hasExplicitHypothesis(JsonNode candidate) {
        return !proposalText(candidate, "hypothesisId").isBlank()
                || !proposalText(candidate, "resolutionAlternativeId").isBlank();
    }

    private ObjectNode modelSuppression(JsonNode proposal, String code, String reason) {
        return objectMapper.createObjectNode()
                .put("code", code)
                .put("proposalId", proposal.path("proposalId").asText())
                .put("sheetId", proposal.path("sheetId").asText())
                .put("range", proposal.path("range").asText())
                .put("type", normalizeType(proposal.path("type").asText(proposal.path("blockType").asText())))
                .put("reason", reason);
    }

    private boolean isComponentPhysicallyDeterminate(JsonNode candidate) {
        var type = normalizeType(candidate.path("type").asText(candidate.path("blockType").asText("")));
        var structure = candidate.path("structure");
        if (!candidate.path("physicalConfirmed").asBoolean(false)
                || candidate.path("confidence").asDouble(0) < 0.80
                || !"VALID_GEOMETRY".equals(candidate.path("geometryStatus").asText(""))) return false;
        if ("COLUMN_TABLE".equals(type)) {
            var projection = structure.path("recordProjection");
            return "COLUMN".equals(structure.path("recordAxis").asText(
                    structure.path("repeatAxis").asText("")))
                    && projection.path("recordColumns").isArray()
                    && projection.path("recordColumns").size() >= 2
                    && structure.path("fieldRows").isArray()
                    && structure.path("fieldRows").size() >= 2
                    && bounds(structure.path("crossDataRange").asText("")) != null;
        }
        if ("ROW_TABLE".equals(type)) {
            return "ROW".equals(structure.path("recordAxis").asText(
                    structure.path("repeatAxis").asText("")))
                    && structure.path("columns").isArray() && structure.path("columns").size() >= 2
                    && bounds(structure.path("headerRange").asText("")) != null
                    && bounds(structure.path("dataRange").asText("")) != null;
        }
        if ("MATRIX".equals(type)) return validTableGeometry(candidate, type);
        // FORM_REGION was marked physicalConfirmed only after the detector
        // counted multiple label/value surfaces inside a closed form envelope.
        // Some inline merged fields are not serialized into fieldSurfaces, so
        // repeating that count here would incorrectly reopen the component.
        return "FORM_REGION".equals(type) && bounds(candidate.path("range").asText("")) != null;
    }

    private void confirmedByPhysicalEvidence(ObjectNode backend, JsonNode model) {
        backend.put("structureStatus", "CONFIRMED")
                .put("canonicalStatus", "CONFIRMED")
                .put("candidateOnly", false)
                .put("physicalStructureOnly", false)
                .put("reviewRequired", false)
                .put("structureConflict", false)
                .put("modelAssessmentVerdict", model == null
                        ? "PHYSICAL_COMPONENT_DETERMINATE" : "MODEL_CONFLICT_REJECTED")
                .put("resolutionStatus", "AUTO_RESOLVED")
                .put("resolutionReason", "DETERMINATE_COMPONENT_GEOMETRY")
                .put("canonicalStructureMayReopen", true);
        if (model != null) backend.set("modelProposal", model.deepCopy());
    }

    private ObjectNode backendCandidate(JsonNode primitive) {
        var type = normalizeType(primitive.path("blockType").asText(primitive.path("type").asText("")));
        if (!Set.of("MATRIX", "ROW_TABLE", "COLUMN_TABLE", "FORM_REGION").contains(type)) return null;
        if (!"VALID_GEOMETRY".equals(primitive.path("geometryStatus").asText(""))
                && !"VALID".equals(primitive.path("validationStatus").asText(""))) return null;
        var result = objectMapper.createObjectNode()
                .put("candidateId", primitive.path("candidateId").asText(primitive.path("id").asText()))
                .put("proposalId", primitive.path("candidateId").asText(primitive.path("id").asText()))
                .put("source", "PHYSICAL_HEURISTIC")
                .put("sheetId", primitive.path("sheetId").asText())
                .put("type", type)
                .put("range", primitive.path("range").asText())
                .put("geometryStatus", primitive.path("geometryStatus").asText("VALID_GEOMETRY"))
                .put("structureStatus", "PROVISIONAL")
                .put("canonicalStatus", "PROVISIONAL")
                .put("candidateOnly", true)
                .put("physicalStructureOnly", true)
                .put("reviewRequired", true)
                .put("confidence", primitive.path("confidence").asDouble(0.5));
        result.set("structure", primitive.path("structure").deepCopy());
        result.put("physicalConfirmed", primitive.path("physicalConfirmed").asBoolean(
                primitive.path("structure").path("physicalConfirmed").asBoolean(false)));
        // Older physical recognizers emitted repeatAxis while the resolver
        // uses the canonical recordAxis vocabulary. Normalize the alias at
        // the proposal boundary so comparison is independent of producer
        // protocol version.
        var structure = result.with("structure");
        var recordAxis = structure.path("recordAxis").asText("");
        if (recordAxis.isBlank() || "UNKNOWN".equalsIgnoreCase(recordAxis)) {
            var repeatAxis = structure.path("repeatAxis").asText("");
            if (!repeatAxis.isBlank() && !"UNKNOWN".equalsIgnoreCase(repeatAxis)) {
                structure.put("recordAxis", repeatAxis.toUpperCase(java.util.Locale.ROOT));
            }
        }
        if (!result.has("recordAxis") && structure.has("recordAxis")) {
            result.set("recordAxis", structure.path("recordAxis").deepCopy());
        }
        return result;
    }

    private ObjectNode modelCandidate(JsonNode proposal, JsonNode structure) {
        var type = normalizeType(proposal.path("type").asText(""));
        var sheetId = proposal.path("sheetId").asText("");
        var range = proposal.path("range").asText("");
        if (!Set.of("MATRIX", "ROW_TABLE", "COLUMN_TABLE", "FORM_REGION").contains(type)
                || sheetId.isBlank() || range.isBlank()) return null;
        var result = objectMapper.createObjectNode()
                .put("proposalId", proposal.path("proposalId").asText())
                .put("candidateId", proposal.path("proposalId").asText())
                .put("source", "MODEL")
                .put("sheetId", sheetId).put("type", type).put("range", range)
                .put("geometryStatus", "VALID_GEOMETRY")
                .put("structureStatus", "PROVISIONAL")
                .put("canonicalStatus", "PROVISIONAL")
                .put("confidence", proposal.path("confidence").asDouble(0.5));
        var details = objectMapper.createObjectNode();
        var source = proposal.path("structure").isObject() ? proposal.path("structure") : proposal;
        for (var key : List.of("cornerRange", "rowHeaderRange", "columnHeaderRange", "crossDataRange",
                "headerRange", "dataRange", "totalRange", "recordHeight", "recordWidth", "recordStride",
                "recordAxis", "repeatAxis", "recordProjection", "rowAttributeColumns", "fieldGroups")) {
            if (source.has(key)) details.set(key, source.path(key).deepCopy());
        }
        result.set("structure", details);
        result.set("proposal", proposal.deepCopy());
        if (!validTableGeometry(result, type)) return null;
        return result;
    }

    private void confirmed(ObjectNode backend, JsonNode model) {
        backend.put("structureStatus", "CONFIRMED")
                .put("canonicalStatus", "CONFIRMED")
                .put("candidateOnly", false)
                .put("physicalStructureOnly", false)
                .put("reviewRequired", false)
                .put("modelAssessmentVerdict", "MODEL_AGREES")
                .put("resolutionStatus", "AUTO_RESOLVED")
                .put("resolutionReason", "EXACT_SIGNATURE_AGREEMENT")
                .put("canonicalStructureMayReopen", false);
        var structure = backend.with("structure");
        for (var key : List.of("cornerRange", "rowHeaderRange", "columnHeaderRange", "crossDataRange",
                "headerRange", "dataRange", "totalRange", "recordHeight", "recordWidth", "recordStride",
                "recordAxis")) {
            var value = model.path(key).asText("");
            if (!value.isBlank() && model.path(key).isIntegralNumber()) structure.put(key, model.path(key).asInt());
            else if (!value.isBlank() && !"UNKNOWN".equals(value)) structure.put(key, value);
        }
        backend.set("modelProposal", model.deepCopy());
    }

    private boolean validTableGeometry(JsonNode candidate, String type) {
        var structure = candidate.path("structure");
        if (structure == null || !structure.isObject() || bounds(candidate.path("range").asText()) == null) {
            return false;
        }
        if ("FORM_REGION".equals(type)) return true;
        if ("MATRIX".equals(type)) {
            var region = bounds(candidate.path("range").asText());
            var corner = bounds(structure.path("cornerRange").asText());
            var rowHeader = bounds(structure.path("rowHeaderRange").asText());
            var columnHeader = bounds(structure.path("columnHeaderRange").asText());
            var crossData = bounds(structure.path("crossDataRange").asText());
            var axis = structure.path("recordAxis").asText(candidate.path("recordAxis").asText(""));
            return region != null && corner != null && rowHeader != null && columnHeader != null && crossData != null
                    && area(crossData) > 0
                    && Set.of("ROW", "COLUMN").contains(axis)
                    && contains(region, corner) && contains(region, rowHeader)
                    && contains(region, columnHeader) && contains(region, crossData)
                    && height(rowHeader) == height(crossData)
                    && width(columnHeader) == width(crossData)
                    && !overlapBounds(rowHeader, columnHeader)
                    && !overlapBounds(rowHeader, crossData)
                    && !overlapBounds(columnHeader, crossData);
        }
        var header = bounds(structure.path("headerRange").asText());
        var data = bounds(structure.path("dataRange").asText());
        if (header == null || data == null) return false;
        var axis = structure.path("recordAxis").asText("");
        if (axis.isBlank() || "UNKNOWN".equalsIgnoreCase(axis)) {
            axis = structure.path("repeatAxis").asText(candidate.path("recordAxis")
                    .asText(candidate.path("repeatAxis").asText("")));
        }
        axis = axis.toUpperCase(java.util.Locale.ROOT);
        if ("ROW_TABLE".equals(type)) return "ROW".equals(axis);
        if (!"COLUMN_TABLE".equals(type) || !"COLUMN".equals(axis)) return false;
        var projection = structure.path("recordProjection");
        var explicitRecords = projection.path("recordColumns").isArray()
                && projection.path("recordColumns").size() >= 2;
        var crossData = bounds(structure.path("crossDataRange").asText(""));
        var explicitSurface = crossData != null && width(crossData) >= 2 && height(crossData) >= 2;
        // A rectangle containing only the left label band is not a valid
        // COLUMN_TABLE hypothesis. The model must identify the repeated record
        // surface; otherwise it remains diagnostic and cannot compete with a
        // physically proven full component.
        return explicitRecords || explicitSurface;
    }

    private JsonNode findExact(JsonNode backend, JsonNode proposals, Set<String> usedModel) {
        for (var proposal : proposals) {
            if (proposal.isObject()
                    && !usedModel.contains(proposal.path("proposalId").asText())
                    && signature(backend).equals(signature(proposal))) return proposal;
        }
        return null;
    }

    private List<JsonNode> overlappingModel(
            JsonNode backend, JsonNode proposals, Set<String> usedModel,
            JsonNode workbookStructure, ArrayNode suppressed
    ) {
        var result = new ArrayList<JsonNode>();
        for (var proposal : proposals) {
            if (!proposal.isObject()) continue;
            if (usedModel.contains(proposal.path("proposalId").asText())) continue;
            if (!backend.path("sheetId").asText().equals(proposal.path("sheetId").asText())) continue;
            if (!overlap(backend.path("range").asText(), proposal.path("range").asText())) continue;
            if (signature(backend).equals(signature(proposal))) continue;
            var normalized = modelCandidate(proposal, workbookStructure);
            var geometry = normalized == null ? proposal : normalized;
            if (modelOccupiesOnlyLabelBand(backend, geometry)) {
                usedModel.add(proposal.path("proposalId").asText());
                suppressed.add(modelSuppression(proposal, "MODEL_COLUMN_TABLE_WITHOUT_RECORD_SURFACE",
                        "模型区域只覆盖字段标签带，没有覆盖物理上连续的记录列，不能作为列表结构候选"));
                continue;
            }
            if (modelTableConsumesFormFields(backend, geometry)) {
                usedModel.add(proposal.path("proposalId").asText());
                suppressed.add(modelSuppression(proposal, "MODEL_TABLE_WITHOUT_REPEAT_EVIDENCE",
                        "模型把多个表单标签/填写面对误判成列表，但没有足够的重复记录面证据"));
                continue;
            }
            if (isFieldGroupSubdivision(backend, geometry)) {
                usedModel.add(proposal.path("proposalId").asText());
                suppressed.add(modelSuppression(proposal, "MODEL_TABLE_DEMOTED_TO_FIELD_GROUP",
                        "模型分区与同一连续记录面的字段组一致，已作为字段组语义保留，不再重复显示为独立列表"));
                continue;
            }
            if (isFormSemanticSubdivision(backend, geometry, proposals)) {
                usedModel.add(proposal.path("proposalId").asText());
                suppressed.add(modelSuppression(proposal, "MODEL_FORM_DEMOTED_TO_SEMANTIC_GROUP",
                        "模型区域位于同一表单物理组件内，已作为字段语义分组保留，不再形成竞争结构"));
                continue;
            }
            // Keep the original proposal shape for the established grouping
            // flow: componentId/hypothesisId live on the model payload. The
            // normalized copy above is used only for geometry validation.
            result.add(proposal);
        }
        return result;
    }

    private boolean modelOccupiesOnlyLabelBand(JsonNode backend, JsonNode model) {
        if (!"COLUMN_TABLE".equals(normalizeType(backend.path("type").asText(
                backend.path("blockType").asText(""))))
                || !"COLUMN_TABLE".equals(normalizeType(model.path("type").asText(
                model.path("blockType").asText(""))))) return false;
        var firstRecordColumn = firstRecordColumn(backend.path("structure").path("recordProjection"));
        var modelBounds = bounds(model.path("range").asText(""));
        return firstRecordColumn > 0 && modelBounds != null && modelBounds[2] < firstRecordColumn;
    }

    private boolean modelTableConsumesFormFields(JsonNode backend, JsonNode model) {
        if (!"FORM_REGION".equals(normalizeType(backend.path("type").asText(
                backend.path("blockType").asText(""))))) return false;
        var modelType = normalizeType(model.path("type").asText(model.path("blockType").asText("")));
        if (!Set.of("ROW_TABLE", "COLUMN_TABLE").contains(modelType)) return false;
        var surfaces = backend.path("structure").path("fieldSurfaces");
        if (!surfaces.isArray() || surfaces.size() < 3) return false;
        var modelBounds = bounds(model.path("range").asText(""));
        var dataBounds = bounds(model.path("structure").path("dataRange").asText(
                model.path("dataRange").asText("")));
        if (modelBounds == null || dataBounds == null) return false;
        var touched = 0;
        for (var surface : surfaces) {
            var surfaceBounds = bounds(surface.path("range").asText(
                    surface.path("structure").path("valueRange").asText("")));
            if (surfaceBounds != null && overlapBounds(modelBounds, surfaceBounds)) touched++;
        }
        if (touched < 2) return false;
        // A two-row rectangle whose cells are already explained by several
        // label/value surfaces is a compact form band, regardless of whether
        // the model calls the horizontal direction ROW or COLUMN records.
        if (height(modelBounds) <= 2) return true;
        var repeatedDepth = "COLUMN_TABLE".equals(modelType) ? width(dataBounds) : height(dataBounds);
        return repeatedDepth < 2;
    }

    private boolean isFieldGroupSubdivision(JsonNode backend, JsonNode model) {
        if (!"COLUMN_TABLE".equals(normalizeType(backend.path("type").asText(
                backend.path("blockType").asText(""))))
                || !"COLUMN_TABLE".equals(normalizeType(model.path("type").asText(
                model.path("blockType").asText(""))))) return false;
        var structure = backend.path("structure");
        var groups = structure.path("fieldGroups");
        if (!groups.isArray() || groups.size() < 2
                || firstRecordColumn(structure.path("recordProjection")) <= 0) return false;
        var modelRange = normalizeRange(model.path("range").asText(""));
        if (modelRange.equals(normalizeRange(backend.path("range").asText("")))) return false;
        for (var group : groups) {
            if (modelRange.equals(normalizeRange(group.path("range").asText("")))) return true;
        }
        return false;
    }

    /**
     * A model may split one physically continuous form into department or
     * chapter bands. Those rectangles add semantic grouping, but they do not
     * redefine where the editable label/value surfaces are. Treat them as
     * semantic subdivisions only when the physical form has multiple explicit
     * field surfaces and the model keeps the FORM_REGION type. A competing
     * ROW/COLUMN/MATRIX proposal still remains a genuine structure conflict.
     */
    private boolean isFormSemanticSubdivision(JsonNode backend, JsonNode model, JsonNode proposals) {
        if (!"FORM_REGION".equals(normalizeType(backend.path("type").asText(
                backend.path("blockType").asText(""))))
                || !"FORM_REGION".equals(normalizeType(model.path("type").asText(
                model.path("blockType").asText(""))))) return false;
        var backendRange = bounds(backend.path("range").asText(""));
        var modelRange = bounds(model.path("range").asText(""));
        if (backendRange == null || modelRange == null || !contains(backendRange, modelRange)
                || normalizeRange(backend.path("range").asText(""))
                .equals(normalizeRange(model.path("range").asText("")))) return false;
        var surfaces = backend.path("structure").path("fieldSurfaces");
        if (!surfaces.isArray() || surfaces.size() < 2 || !hasSupportingFormPartition(backend, proposals)) {
            return false;
        }
        var containedSurfaces = 0;
        for (var surface : surfaces) {
            var surfaceRange = bounds(surface.path("range").asText(
                    surface.path("structure").path("valueRange").asText("")));
            if (surfaceRange != null && overlapBounds(modelRange, surfaceRange)) containedSurfaces++;
        }
        // Header-only semantic bands can legitimately contain no editable
        // field surface; once the sibling FORM bands prove a partition, they
        // are still semantic context rather than a competing component.
        return containedSurfaces > 0 || bounds(model.path("range").asText("")) != null;
    }

    private boolean hasSupportingFormPartition(JsonNode backend, JsonNode proposals) {
        if (!"FORM_REGION".equals(normalizeType(backend.path("type").asText(
                backend.path("blockType").asText(""))))
                || !backend.path("structure").path("fieldSurfaces").isArray()
                || backend.path("structure").path("fieldSurfaces").size() < 2) return false;
        var backendRange = bounds(backend.path("range").asText(""));
        if (backendRange == null) return false;
        var partitionMembers = new ArrayList<int[]>();
        for (var proposal : proposals) {
            if (!"FORM_REGION".equals(normalizeType(proposal.path("type").asText(
                    proposal.path("blockType").asText(""))))) continue;
            if (!backend.path("sheetId").asText("").equals(proposal.path("sheetId").asText(""))) continue;
            var proposalRange = bounds(proposal.path("range").asText(""));
            if (proposalRange != null && contains(backendRange, proposalRange)
                    && !normalizeRange(proposal.path("range").asText(""))
                    .equals(normalizeRange(backend.path("range").asText("")))) {
                partitionMembers.add(proposalRange);
            }
        }
        // A single shorter FORM proposal may be a genuine disagreement about
        // the component boundary. Multiple non-overlapping FORM bands are the
        // common model representation of semantic chapters inside one form.
        if (partitionMembers.size() < 2) return false;
        for (int left = 0; left < partitionMembers.size(); left++) {
            for (int right = left + 1; right < partitionMembers.size(); right++) {
                if (overlapBounds(partitionMembers.get(left), partitionMembers.get(right))) return false;
            }
        }
        return true;
    }

    private void confirmedByFormPartition(ObjectNode backend) {
        backend.put("structureStatus", "CONFIRMED")
                .put("canonicalStatus", "CONFIRMED")
                .put("candidateOnly", false)
                .put("physicalStructureOnly", false)
                .put("reviewRequired", false)
                .put("modelAssessmentVerdict", "MODEL_SUPPORTS_FORM_PARTITION")
                .put("resolutionStatus", "AUTO_RESOLVED")
                .put("resolutionReason", "PHYSICAL_FORM_WITH_MODEL_SEMANTIC_PARTITION")
                .put("canonicalStructureMayReopen", true);
    }

    private int firstRecordColumn(JsonNode projection) {
        var first = Integer.MAX_VALUE;
        for (var value : projection.path("recordColumns")) {
            var parsed = columnNumber(value.asText(""));
            if (parsed > 0) first = Math.min(first, parsed);
        }
        return first == Integer.MAX_VALUE ? -1 : first;
    }

    private int columnNumber(String value) {
        var normalized = value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        var result = 0;
        for (var character : normalized.toCharArray()) result = result * 26 + character - 'A' + 1;
        return result;
    }

    private void appendSemanticTargets(ArrayNode targets, ArrayNode regions) {
        var seen = new LinkedHashSet<String>();
        for (var region : regions) {
            if (!isFormalRegion(region) || region.path("suppressed").asBoolean(false)) continue;
            if (!"CONFIRMED".equals(region.path("canonicalStatus").asText())
                    || !"CONFIRMED".equals(region.path("structureStatus").asText())) continue;
            addSemanticTarget(targets, seen, region);
        }
    }

    private void appendUnresolvedTargets(ArrayNode targets, ArrayNode regions) {
        var seen = new LinkedHashSet<String>();
        for (var region : regions) {
            if (!isFormalRegion(region) || region.path("suppressed").asBoolean(false)
                    || ("CONFIRMED".equals(region.path("canonicalStatus").asText())
                    && "CONFIRMED".equals(region.path("structureStatus").asText()))) continue;
            var copy = region.deepCopy();
            if (copy instanceof ObjectNode object) {
                object.put("semanticOnly", true).put("publishable", false)
                        .put("reviewRequired", true);
            }
            addSemanticTarget(targets, seen, copy);
        }
    }

    private void addSemanticTarget(ArrayNode targets, Set<String> seen, JsonNode region) {
        var key = region.path("sheetId").asText("") + "|"
                + normalizeRange(region.path("range").asText("")) + "|"
                + normalizeType(region.path("type").asText(region.path("blockType").asText(""))) + "|"
                + region.path("candidateId").asText(region.path("proposalId").asText(""));
        if (!seen.add(key)) return;
        var copy = region.deepCopy();
        if (copy instanceof ObjectNode object) {
            var type = normalizeType(object.path("type").asText(object.path("blockType").asText("UNKNOWN")));
            var blockId = object.path("blockId").asText("");
            if (blockId.isBlank()) {
                blockId = RecognitionIdentity.blockId(object.path("sheetId").asText(""),
                        object.path("range").asText(""), type, "");
            }
            object.put("blockId", blockId);
            var regionId = object.path("regionId").asText(blockId);
            var candidateRef = object.path("candidateRef").asText(object.path("candidateId").asText(
                    object.path("proposalId").asText(regionId)));
            object.put("semanticOnly", !"CONFIRMED".equals(object.path("canonicalStatus").asText("")))
                    .put("regionId", regionId)
                    .put("candidateRef", candidateRef);
        }
        targets.add(copy);
    }

    private ObjectNode alternative(JsonNode value, String source) {
        var result = objectMapper.createObjectNode()
                .put("proposalId", value.path("proposalId").asText())
                .put("source", source)
                .put("sheetId", value.path("sheetId").asText())
                .put("type", normalizeType(value.path("type").asText(value.path("blockType").asText())))
                .put("range", value.path("range").asText());
        if (value.path("structure").isObject()) result.set("structure", value.path("structure").deepCopy());
        if (value.path("proposal").isObject()) result.set("proposal", value.path("proposal").deepCopy());
        return result;
    }

    private ObjectNode alternativeSet(String alternativeId, String source, List<JsonNode> values) {
        var result = objectMapper.createObjectNode()
                .put("alternativeId", alternativeId)
                .put("source", source);
        var regions = result.putArray("regions");
        values.forEach(value -> regions.add(alternative(value, source)));
        return result;
    }

    private void validateSetOverlaps(ArrayNode regions, ArrayNode conflicts, ArrayNode diagnostics) {
        for (int left = 0; left < regions.size(); left++) {
            var first = (ObjectNode) regions.get(left);
            if (!isActiveRegion(first)) continue;
            for (int right = left + 1; right < regions.size(); right++) {
                var second = (ObjectNode) regions.get(right);
                if (!isActiveRegion(second)
                        || !first.path("sheetId").asText().equals(second.path("sheetId").asText())
                        || !overlap(first.path("range").asText(), second.path("range").asText())) continue;
                var firstGroup = first.path("resolutionGroupId").asText("");
                var secondGroup = second.path("resolutionGroupId").asText("");
                if (!firstGroup.isBlank() && firstGroup.equals(secondGroup)) continue;
                var groupId = firstGroup.isBlank() ? "structure-conflict-"
                        + RecognitionIdentity.shortHash(first.path("sheetId").asText() + "|"
                        + first.path("range").asText() + "|" + second.path("range").asText(), 16) : firstGroup;
                var firstAlternativeId = groupId + "-first";
                var secondAlternativeId = groupId + "-second";
                first.put("structureConflict", true).put("reviewRequired", true)
                        .put("canonicalStatus", "PROVISIONAL").put("structureStatus", "CONFLICT")
                        .put("candidateOnly", true).put("physicalStructureOnly", true)
                        .put("pendingReason", "STRUCTURE_CONFLICT").put("resolutionGroupId", groupId)
                        .put("resolutionAlternativeId", firstAlternativeId);
                second.put("structureConflict", true).put("reviewRequired", true)
                        .put("canonicalStatus", "PROVISIONAL").put("structureStatus", "CONFLICT")
                        .put("candidateOnly", true).put("physicalStructureOnly", false)
                        .put("pendingReason", "STRUCTURE_CONFLICT").put("resolutionGroupId", groupId)
                        .put("resolutionAlternativeId", secondAlternativeId);
                var group = objectMapper.createObjectNode().put("resolutionGroupId", groupId)
                        .put("type", "STRUCTURE_CONFLICT").put("resolutionStatus", "PENDING");
                var alternatives = group.putArray("alternatives");
                alternatives.add(alternativeSet(firstAlternativeId, first.path("source").asText(), List.of(first)));
                alternatives.add(alternativeSet(secondAlternativeId, second.path("source").asText(), List.of(second)));
                first.set("structureAlternativeSets", alternatives.deepCopy());
                second.set("structureAlternativeSets", alternatives.deepCopy());
                conflicts.add(group);
                diagnostics.add(objectMapper.createObjectNode()
                        .put("code", "STRUCTURE_CANDIDATE_CONFLICT")
                        .put("sheetId", first.path("sheetId").asText())
                        .put("firstRange", first.path("range").asText())
                        .put("secondRange", second.path("range").asText()));
            }
        }
    }

    private boolean allConfirmed(ArrayNode regions) {
        for (var region : regions) {
            if (!isActiveRegion(region)
                    || "REJECTED".equals(region.path("canonicalStatus").asText())) continue;
            if (isFormalRegion(region) && !"CONFIRMED".equals(region.path("canonicalStatus").asText())) return false;
        }
        return true;
    }

    private boolean isActiveRegion(JsonNode region) {
        if (!isFormalRegion(region)) return false;
        return !region.path("suppressed").asBoolean(false)
                && !Set.of("SUPERSEDED", "REJECTED").contains(region.path("structureStatus").asText())
                && !Set.of("REJECTED", "REJECTED_BY_RESOLUTION")
                        .contains(region.path("canonicalStatus").asText());
    }

    private boolean isFormalRegion(JsonNode node) {
        return Set.of("MATRIX", "ROW_TABLE", "COLUMN_TABLE", "FORM_REGION")
                .contains(normalizeType(node.path("type").asText(node.path("blockType").asText())));
    }

    private String signature(JsonNode node) {
        var type = normalizeType(node.path("type").asText(node.path("blockType").asText()));
        var structure = node.path("structure").isObject() ? node.path("structure") : node;
        return node.path("sheetId").asText().toUpperCase(Locale.ROOT) + "|"
                + type + "|" + normalizeRange(node.path("range").asText()) + "|"
                + normalizeRange(structure.path("cornerRange").asText(node.path("cornerRange").asText())) + "|"
                + normalizeRange(structure.path("rowHeaderRange").asText(node.path("rowHeaderRange").asText())) + "|"
                + normalizeRange(structure.path("columnHeaderRange").asText(node.path("columnHeaderRange").asText())) + "|"
                + normalizeRange(structure.path("crossDataRange").asText(node.path("crossDataRange").asText())) + "|"
                + normalizeRange(structure.path("headerRange").asText(node.path("headerRange").asText())) + "|"
                + normalizeRange(structure.path("dataRange").asText(node.path("dataRange").asText())) + "|"
                + normalizeRange(structure.path("totalRange").asText(node.path("totalRange").asText())) + "|"
                + structure.path("recordAxis").asText(node.path("recordAxis").asText("UNKNOWN"));
    }

    private String normalizeType(String type) {
        return "FORM_FIELDS".equals(type) ? "FORM_REGION" : type;
    }

    private String normalizeRange(String value) {
        return value == null ? "" : value.replace("$", "").toUpperCase(Locale.ROOT);
    }

    private boolean overlap(String first, String second) {
        var a = bounds(first);
        var b = bounds(second);
        return a != null && b != null && a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
    }

    private int[] intersection(int[] first, int[] second) {
        var left = Math.max(first[0], second[0]);
        var top = Math.max(first[1], second[1]);
        var right = Math.min(first[2], second[2]);
        var bottom = Math.min(first[3], second[3]);
        return left <= right && top <= bottom ? new int[]{left, top, right, bottom} : null;
    }

    private long area(int[] bounds) {
        return (long) (bounds[2] - bounds[0] + 1) * (bounds[3] - bounds[1] + 1);
    }

    private int width(int[] bounds) {
        return bounds[2] - bounds[0] + 1;
    }

    private int height(int[] bounds) {
        return bounds[3] - bounds[1] + 1;
    }

    private boolean contains(int[] outer, int[] inner) {
        return outer != null && inner != null
                && outer[0] <= inner[0] && outer[1] <= inner[1]
                && outer[2] >= inner[2] && outer[3] >= inner[3];
    }

    private boolean overlapBounds(int[] first, int[] second) {
        return first != null && second != null
                && first[0] <= second[2] && second[0] <= first[2]
                && first[1] <= second[3] && second[1] <= first[3];
    }

    private int[] bounds(String value) {
        var parts = normalizeRange(value).split(":", 2);
        if (parts.length == 0 || parts[0].isBlank()) return null;
        var first = cell(parts[0]);
        var last = cell(parts.length == 1 ? parts[0] : parts[1]);
        if (first == null || last == null) return null;
        return new int[]{Math.min(first[0], last[0]), Math.min(first[1], last[1]),
                Math.max(first[0], last[0]), Math.max(first[1], last[1])};
    }

    private int[] cell(String value) {
        var matcher = java.util.regex.Pattern.compile("^([A-Z]+)([1-9][0-9]*)$").matcher(value);
        if (!matcher.matches()) return null;
        var column = 0;
        for (var character : matcher.group(1).toCharArray()) column = column * 26 + character - 'A' + 1;
        return new int[]{column, Integer.parseInt(matcher.group(2))};
    }
}
