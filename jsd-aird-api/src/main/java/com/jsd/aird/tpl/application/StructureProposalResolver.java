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
        var diagnostics = result.putArray("diagnostics");
        var backend = backendProposals == null ? List.<JsonNode>of() : backendProposals;
        var model = modelPayload == null ? objectMapper.createArrayNode() : modelPayload.path("structureProposals");
        var usedModel = new LinkedHashSet<String>();
        var backendSignatures = new LinkedHashSet<String>();

        for (var primitive : backend) {
            var candidate = backendCandidate(primitive);
            if (candidate == null) continue;
            var key = signature(candidate);
            if (!backendSignatures.add(key)) continue;
            var exact = findExact(candidate, model);
            if (exact != null) {
                usedModel.add(exact.path("proposalId").asText());
                confirmed(candidate, exact);
                regions.add(candidate);
                continue;
            }

            var alternatives = overlappingModel(candidate, model);
            if (!alternatives.isEmpty()) {
                var groupId = "structure-conflict-" + RecognitionIdentity.shortHash(
                        candidate.path("sheetId").asText() + "|" + candidate.path("range").asText(), 16);
                candidate.put("structureStatus", "CONFLICT")
                        .put("canonicalStatus", "PROVISIONAL")
                        .put("structureConflict", true)
                        .put("reviewRequired", true)
                        .put("candidateOnly", true)
                        .put("physicalStructureOnly", true)
                        .put("pendingReason", "STRUCTURE_CONFLICT")
                        .put("resolutionGroupId", groupId)
                        .put("modelAssessmentVerdict", "MODEL_CONFLICTS");
                var group = objectMapper.createObjectNode()
                        .put("resolutionGroupId", groupId)
                        .put("type", "STRUCTURE_CONFLICT");
                var groupAlternatives = group.putArray("alternatives");
                groupAlternatives.add(alternative(candidate, "PHYSICAL_HEURISTIC"));
                var modelAlternatives = candidate.putArray("modelAlternatives");
                for (var alternative : alternatives) {
                    usedModel.add(alternative.path("proposalId").asText());
                    groupAlternatives.add(alternative(alternative, "MODEL"));
                    modelAlternatives.add(alternative.deepCopy());
                }
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
                "recordAxis")) {
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

    private JsonNode findExact(JsonNode backend, JsonNode proposals) {
        for (var proposal : proposals) {
            if (proposal.isObject() && signature(backend).equals(signature(proposal))) return proposal;
        }
        return null;
    }

    private List<JsonNode> overlappingModel(JsonNode backend, JsonNode proposals) {
        var result = new ArrayList<JsonNode>();
        for (var proposal : proposals) {
            if (!proposal.isObject()) continue;
            if (!backend.path("sheetId").asText().equals(proposal.path("sheetId").asText())) continue;
            if (!overlap(backend.path("range").asText(), proposal.path("range").asText())) continue;
            if (signature(backend).equals(signature(proposal))) continue;
            result.add(proposal);
        }
        return result;
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

    private void validateSetOverlaps(ArrayNode regions, ArrayNode conflicts, ArrayNode diagnostics) {
        for (int left = 0; left < regions.size(); left++) {
                var first = (ObjectNode) regions.get(left);
            if (!isFormalRegion(first)) continue;
            for (int right = left + 1; right < regions.size(); right++) {
                var second = (ObjectNode) regions.get(right);
                if (!isFormalRegion(second)
                        || !first.path("sheetId").asText().equals(second.path("sheetId").asText())
                        || !overlap(first.path("range").asText(), second.path("range").asText())) continue;
                var firstGroup = first.path("resolutionGroupId").asText("");
                var secondGroup = second.path("resolutionGroupId").asText("");
                if (!firstGroup.isBlank() && firstGroup.equals(secondGroup)) continue;
                var groupId = firstGroup.isBlank() ? "structure-conflict-"
                        + RecognitionIdentity.shortHash(first.path("sheetId").asText() + "|"
                        + first.path("range").asText() + "|" + second.path("range").asText(), 16) : firstGroup;
                first.put("structureConflict", true).put("reviewRequired", true)
                        .put("canonicalStatus", "PROVISIONAL").put("structureStatus", "CONFLICT")
                        .put("candidateOnly", true).put("physicalStructureOnly", true)
                        .put("pendingReason", "STRUCTURE_CONFLICT").put("resolutionGroupId", groupId);
                second.put("structureConflict", true).put("reviewRequired", true)
                        .put("canonicalStatus", "PROVISIONAL").put("structureStatus", "CONFLICT")
                        .put("candidateOnly", true).put("physicalStructureOnly", false)
                        .put("pendingReason", "STRUCTURE_CONFLICT").put("resolutionGroupId", groupId);
                var group = objectMapper.createObjectNode().put("resolutionGroupId", groupId)
                        .put("type", "STRUCTURE_CONFLICT");
                group.putArray("alternatives").add(alternative(first, first.path("source").asText()))
                        .add(alternative(second, second.path("source").asText()));
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
            if (isFormalRegion(region) && !"CONFIRMED".equals(region.path("canonicalStatus").asText())) return false;
        }
        return true;
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
