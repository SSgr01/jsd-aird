package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Compares independent backend/model structure proposals.  It validates
 * invariants and records conflicts; it never synthesizes a third geometry.
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
            if (exact != null) {
                usedModel.add(exact.path("proposalId").asText());
                confirmed(candidate, exact);
                regions.add(candidate);
                continue;
            }

            var alternatives = overlappingModel(candidate, model, usedModel);
            if (!alternatives.isEmpty()) {
                var groupId = "structure-conflict-" + RecognitionIdentity.shortHash(
                        candidate.path("sheetId").asText() + "|" + candidate.path("range").asText(), 16);
                if (isExactModelPartition(candidate, alternatives, structure)) {
                    var physicalAlternativeId = groupId + "-physical";
                    var modelAlternativeId = groupId + "-model-partition";
                    var modelRegions = new ArrayList<JsonNode>();
                    for (var alternative : alternatives) {
                        usedModel.add(alternative.path("proposalId").asText());
                        var modelRegion = modelCandidate(alternative, structure);
                        if (modelRegion == null) continue;
                        confirmPartition(modelRegion, candidate, groupId, modelAlternativeId);
                        regions.add(modelRegion);
                        modelRegions.add(modelRegion);
                    }
                    var resolution = objectMapper.createObjectNode()
                            .put("resolutionGroupId", groupId)
                            .put("type", "STRUCTURE_REPLACEMENT")
                            .put("resolutionStatus", "AUTO_RESOLVED")
                            .put("resolutionReason", "MODEL_PARTITION_EXACT_COVER")
                            .put("selectedAlternativeId", modelAlternativeId);
                    var resolutionAlternatives = resolution.putArray("alternatives");
                    resolutionAlternatives.add(alternativeSet(
                            physicalAlternativeId, "PHYSICAL_HEURISTIC", List.of(candidate)));
                    resolutionAlternatives.add(alternativeSet(
                            modelAlternativeId, "MODEL", modelRegions));
                    resolutions.add(resolution);

                    var suppressedCandidate = candidate.deepCopy();
                    suppressedCandidate.put("structureStatus", "SUPERSEDED")
                            .put("canonicalStatus", "REJECTED")
                            .put("candidateOnly", true)
                            .put("physicalStructureOnly", true)
                            .put("reviewRequired", false)
                            .put("resolutionStatus", "AUTO_RESOLVED")
                            .put("resolutionReason", "MODEL_PARTITION_EXACT_COVER")
                            .put("resolutionGroupId", groupId)
                            .put("resolutionAlternativeId", physicalAlternativeId);
                    suppressed.add(suppressedCandidate);
                    continue;
                }
                var physicalAlternativeId = groupId + "-physical";
                var modelAlternativeId = groupId + "-model-partition";
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
                var modelSetRegions = new ArrayList<JsonNode>();
                for (var alternative : alternatives) {
                    usedModel.add(alternative.path("proposalId").asText());
                    var alternativeCopy = alternative.deepCopy();
                    if (alternativeCopy instanceof ObjectNode object) {
                        object.put("resolutionAlternativeId", modelAlternativeId);
                    }
                    modelAlternatives.add(alternativeCopy);
                    modelSetRegions.add(alternativeCopy);
                }
                groupAlternatives.add(alternativeSet(
                        physicalAlternativeId, "PHYSICAL_HEURISTIC", List.of(candidate)));
                groupAlternatives.add(alternativeSet(modelAlternativeId, "MODEL", modelSetRegions));
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
            }
            regions.add(candidate);
        }

        // Model-only proposals are retained for review rather than silently
        // discarded. They can be selected in a conflict group after matching.
        for (var proposal : model) {
            if (!proposal.isObject() || usedModel.contains(proposal.path("proposalId").asText())) continue;
            var modelRegion = modelCandidate(proposal, structure);
            if (modelRegion == null) {
                diagnostics.add(objectMapper.createObjectNode()
                        .put("code", "INVALID_MODEL_STRUCTURE_PROPOSAL")
                        .put("proposalId", proposal.path("proposalId").asText()));
                continue;
            }
            modelRegion.put("candidateOnly", true)
                    .put("reviewRequired", true)
                    .put("physicalStructureOnly", false)
                    .put("structureStatus", "UNRESOLVED")
                    .put("canonicalStatus", "PROVISIONAL")
                    .put("pendingReason", "MODEL_ONLY_STRUCTURE");
            regions.add(modelRegion);
        }

        validateSetOverlaps(regions, conflicts, diagnostics);
        // Formal semantic recognition is intentionally limited to the regions
        // that both proposals have confirmed (or that were proven by the
        // strict exact-partition invariant).  Unresolved model/physical
        // proposals are retained separately for review and audit, but are not
        // sent to REGION_FIELDS and cannot contribute to coverage.
        appendSemanticTargets(semanticTargets, regions);
        result.set("canonicalSemanticTargets", semanticTargets.deepCopy());
        appendUnresolvedTargets(unresolvedTargets, regions);
        result.put("recognitionStatus", conflicts.isEmpty() && diagnostics.isEmpty()
                && allConfirmed(regions) ? "COMPLETE" : "REVIEW_REQUIRED");
        result.put("canonicalStatus", allConfirmed(regions) && conflicts.isEmpty()
                ? "CONFIRMED" : "PROVISIONAL");
        return result;
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
        for (var key : List.of("cornerRange", "rowHeaderRange", "columnHeaderRange", "crossDataRange",
                "headerRange", "dataRange", "totalRange", "recordHeight", "recordWidth", "recordStride",
                "recordAxis", "repeatAxis")) {
            if (proposal.has(key)) details.set(key, proposal.path(key).deepCopy());
        }
        result.set("structure", details);
        result.set("proposal", proposal.deepCopy());
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
        return ("ROW_TABLE".equals(type) && "ROW".equals(axis))
                || ("COLUMN_TABLE".equals(type) && "COLUMN".equals(axis));
    }

    private JsonNode findExact(JsonNode backend, JsonNode proposals, Set<String> usedModel) {
        for (var proposal : proposals) {
            if (proposal.isObject()
                    && !usedModel.contains(proposal.path("proposalId").asText())
                    && signature(backend).equals(signature(proposal))) return proposal;
        }
        return null;
    }

    private List<JsonNode> overlappingModel(JsonNode backend, JsonNode proposals, Set<String> usedModel) {
        var result = new ArrayList<JsonNode>();
        for (var proposal : proposals) {
            if (!proposal.isObject()) continue;
            if (usedModel.contains(proposal.path("proposalId").asText())) continue;
            if (!backend.path("sheetId").asText().equals(proposal.path("sheetId").asText())) continue;
            if (!overlap(backend.path("range").asText(), proposal.path("range").asText())) continue;
            if (signature(backend).equals(signature(proposal))) continue;
            result.add(proposal);
        }
        return result;
    }

    private boolean isExactModelPartition(JsonNode backend, List<JsonNode> proposals, JsonNode structure) {
        if (proposals.size() < 2) return false;
        var backendBounds = bounds(backend.path("range").asText());
        if (backendBounds == null) return false;
        long coveredArea = 0;
        for (int index = 0; index < proposals.size(); index++) {
            var proposal = proposals.get(index);
            var proposalType = normalizeType(proposal.path("type").asText(""));
            if (!Set.of("MATRIX", "ROW_TABLE", "COLUMN_TABLE", "FORM_REGION")
                    .contains(proposalType)) return false;
            if (!backend.path("sheetId").asText().equals(proposal.path("sheetId").asText())) return false;
            var proposalBounds = bounds(proposal.path("range").asText());
            if (proposalBounds == null) return false;
            if (!validModelProposalGeometry(proposal, proposalType, structure)) return false;
            for (int previous = 0; previous < index; previous++) {
                if (overlap(proposal.path("range").asText(), proposals.get(previous).path("range").asText())) {
                    return false;
                }
            }
            var intersection = intersection(backendBounds, proposalBounds);
            if (intersection == null) return false;
            coveredArea += area(intersection);
        }
        return coveredArea == area(backendBounds);
    }

    private boolean validModelProposalGeometry(JsonNode proposal, String type, JsonNode structure) {
        if ("FORM_REGION".equals(type)) return true;
        var candidate = modelCandidate(proposal, structure);
        return candidate != null && validTableGeometry(candidate, type);
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

    private void confirmPartition(
            ObjectNode modelRegion, JsonNode suppressedPhysical, String groupId, String alternativeId
    ) {
        modelRegion.put("structureStatus", "CONFIRMED")
                .put("canonicalStatus", "CONFIRMED")
                .put("candidateOnly", false)
                .put("physicalStructureOnly", false)
                .put("reviewRequired", false)
                .put("modelAssessmentVerdict", "MODEL_PARTITION_EXACT_COVER")
                .put("resolutionStatus", "AUTO_RESOLVED")
                .put("resolutionReason", "MODEL_PARTITION_EXACT_COVER")
                .put("resolutionGroupId", groupId)
                .put("resolutionAlternativeId", alternativeId)
                .put("canonicalStructureMayReopen", false);
        var resolution = modelRegion.putObject("resolution")
                .put("resolutionGroupId", groupId)
                .put("selectedAlternativeId", alternativeId)
                .put("resolutionStatus", "AUTO_RESOLVED")
                .put("resolutionReason", "MODEL_PARTITION_EXACT_COVER");
        resolution.set("suppressedPhysical", alternative(suppressedPhysical, "PHYSICAL_HEURISTIC"));
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
