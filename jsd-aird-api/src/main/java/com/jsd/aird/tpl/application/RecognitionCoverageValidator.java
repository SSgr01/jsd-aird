package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;

/**
 * Checks completeness against physical structure instead of treating any
 * non-empty model response as a successful recognition.
 */
public final class RecognitionCoverageValidator {

    private final ObjectMapper objectMapper;
    private final StructurePrimitiveRecognizer primitiveRecognizer;
    private final PhysicalStructureFieldCompiler fieldCompiler;

    public RecognitionCoverageValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.primitiveRecognizer = new StructurePrimitiveRecognizer(objectMapper);
        this.fieldCompiler = new PhysicalStructureFieldCompiler(objectMapper);
    }

    public ObjectNode physicalReport(JsonNode structure, String reason) {
        return physicalReport(structure, physicalRegions(structure), reason);
    }

    public ObjectNode physicalReport(JsonNode structure, List<JsonNode> regions, String reason) {
        var report = objectMapper.createObjectNode().put("schemaVersion", 1)
                .put("status", regions.isEmpty() ? "NO_PHYSICAL_TABLE" : "REVIEW_REQUIRED")
                .put("reason", reason == null ? "" : reason)
                .put("physicalRegionCount", regions.size())
                .put("expectedRegionCount", regions.size())
                .put("coveredRegionCount", 0)
                .put("unresolvedRegionCount", regions.size())
                .put("coverageRatio", 0.0);
        var details = report.putArray("regions");
        for (var region : regions) {
            details.add(regionDetail(region, "UNRESOLVED", false));
        }
        var reportIssues = report.putArray("issues");
        for (var region : regions) {
            if ("UNKNOWN".equals(region.path("blockType").asText(region.path("type").asText("")))) {
                reportIssues.add("STRUCTURE_DIRECTION_UNCLEAR");
            }
        }
        reportIssues.add(reason == null || reason.isBlank()
                ? "物理结构区域尚未完成语义识别"
                : reason);
        report.put("expectedFieldCount", regions.stream()
                        .mapToInt(region -> region.path("expectedFieldCount").asInt(0)).sum())
                .put("returnedFieldCount", 0)
                .put("validLocationFieldCount", 0)
                .put("pendingFieldCount", regions.stream()
                        .mapToInt(region -> region.path("expectedFieldCount").asInt(0)).sum())
                .put("structureExceptionCount", reportIssues.size());
        return report;
    }

    public Assessment assess(
            JsonNode structure,
            List<JsonNode> expectedRegions,
            Map<String, String> regionStates,
            List<RecognitionModelClient.ModelSuggestion> suggestions,
            boolean globalSucceeded,
            boolean globalFailed
    ) {
        return assess(structure, expectedRegions, regionStates, suggestions,
                globalSucceeded, globalFailed, physicalRegions(structure));
    }

    public Assessment assess(
            JsonNode structure,
            List<JsonNode> expectedRegions,
            Map<String, String> regionStates,
            List<RecognitionModelClient.ModelSuggestion> suggestions,
            boolean globalSucceeded,
            boolean globalFailed,
            List<JsonNode> physicalRegionFacts
    ) {
        var expected = expectedRegions == null || expectedRegions.isEmpty()
                ? physicalRegionFacts
                : expectedRegions;
        var details = objectMapper.createArrayNode();
        var covered = 0;
        var unresolved = 0;
        var issues = objectMapper.createArrayNode();
        var seen = new LinkedHashSet<String>();

        for (var region : expected) {
            var key = regionKey(region);
            var callState = regionStates.getOrDefault(key, "NOT_SCHEDULED");
            var semantic = hasSemanticSuggestion(region, suggestions);
            var expectedFields = region.path("expectedFieldCount").asInt(0);
            var fieldCoverage = fieldCoverage(region, suggestions);
            var returnedFields = fieldCoverage.returned();
            var fieldsCovered = expectedFields == 0
                    || (fieldCoverage.valid() >= expectedFields && fieldCoverage.pending() == 0);
            var structureConflict = region.path("structureConflict").asBoolean(false)
                    || "CONFLICT".equals(region.path("structureStatus").asText(""));
            var structureUnresolved = "UNRESOLVED".equals(region.path("structureStatus").asText(""))
                    || "MODEL_UNRESOLVED".equals(region.path("modelAssessmentVerdict").asText(""));
            var canonicalConfirmed = "CONFIRMED".equals(region.path("canonicalStatus").asText())
                    && (!region.has("structureStatus")
                        || "CONFIRMED".equals(region.path("structureStatus").asText()));
            var complete = "SUCCEEDED".equals(callState) && semantic && fieldsCovered
                    && canonicalConfirmed && !structureConflict && !structureUnresolved;
            if (complete) covered++;
            else unresolved++;
            details.add(regionDetail(region,
                    complete ? "COVERED" : callState,
                    semantic).put("expectedFieldCount", expectedFields)
                    .put("returnedFieldCount", returnedFields)
                    .put("validLocationFieldCount", fieldCoverage.valid())
                    .put("pendingFieldCount", fieldCoverage.pending())
                    .put("fieldCoverageComplete", fieldsCovered));
            if (seen.add(key) && !complete) {
                issues.add(issueFor(region, callState, semantic));
            }
        }

        var expectedCount = expected.size();
        var ratio = expectedCount == 0
                ? (physicalRegionFacts.isEmpty() ? 1.0 : 0.0)
                : covered / (double) expectedCount;
        var status = "COMPLETE";
        if (globalFailed || unresolved > 0) status = "REVIEW_REQUIRED";
        if (!globalSucceeded && expectedCount > 0) status = "REVIEW_REQUIRED";
        if (expectedCount == 0 && (!globalSucceeded || hasPhysicalContent(structure))) {
            status = "REVIEW_REQUIRED";
            issues.add("工作簿存在物理内容，但没有形成可验证的业务区域覆盖");
        }

        var report = objectMapper.createObjectNode().put("schemaVersion", 1)
                .put("status", status)
                .put("physicalRegionCount", physicalRegionFacts.size())
                .put("expectedRegionCount", expectedCount)
                .put("coveredRegionCount", covered)
                .put("unresolvedRegionCount", unresolved)
                .put("coverageRatio", ratio)
                .put("globalStructureCallSucceeded", globalSucceeded)
                .put("globalStructureCallFailed", globalFailed)
                .put("expectedFieldCount", expected.stream()
                        .mapToInt(region -> region.path("expectedFieldCount").asInt(0)).sum())
                .put("returnedFieldCount", expected.stream()
                        .mapToInt(region -> fieldCoverage(region, suggestions).returned()).sum())
                .put("validLocationFieldCount", expected.stream()
                        .mapToInt(region -> fieldCoverage(region, suggestions).valid()).sum())
                .put("pendingFieldCount", expected.stream()
                        .mapToInt(region -> fieldCoverage(region, suggestions).pending()).sum())
                .put("structureExceptionCount", issues.size());
        report.set("regions", details);
        report.set("issues", issues);
        return new Assessment(report, status, covered, unresolved);
    }

    private boolean hasPhysicalContent(JsonNode structure) {
        for (var sheet : structure.path("sheets")) {
            for (var cell : sheet.path("semanticCells")) {
                var factType = cell.path("factType").asText("");
                if (Set.of("VALUE", "FORMULA", "INPUT_CANDIDATE").contains(factType)) return true;
            }
        }
        return false;
    }

    public List<JsonNode> physicalRegions(JsonNode structure) {
        var result = new ArrayList<JsonNode>();
        for (var primitive : primitiveRecognizer.recognize(structure)) {
            var type = primitive.path("blockType").asText("");
            if (!Set.of("ROW_TABLE", "COLUMN_TABLE", "FORM_REGION", "UNKNOWN").contains(type)) continue;
            var geometryStatus = primitive.path("geometryStatus").asText("");
            if (!"VALID_GEOMETRY".equals(geometryStatus)) continue;
            var copy = primitive.deepCopy();
            if (copy instanceof ObjectNode object) {
                var expectedRefs = expectedCandidateRefs(object, structure);
                object.put("expectedFieldCount", expectedRefs.size());
                var refs = object.putArray("expectedCandidateRefs");
                expectedRefs.forEach(refs::add);
            }
            result.add(copy);
        }
        return List.copyOf(result);
    }

    private Set<String> expectedCandidateRefs(JsonNode region, JsonNode facts) {
        var type = region.path("blockType").asText(region.path("type").asText(""));
        if ("UNKNOWN".equals(type)) return Set.of();
        var parent = objectMapper.createObjectNode().put("kind", type)
                .put("blockId", region.path("candidateId").asText(region.path("id").asText("")))
                .put("regionId", region.path("candidateId").asText(region.path("id").asText("")));
        parent.set("locator", objectMapper.createObjectNode()
                .put("sheetId", region.path("sheetId").asText())
                .put("range", region.path("range").asText()));
        var refs = new LinkedHashSet<String>();
        for (var field : fieldCompiler.children(parent, region, facts)) {
            var payload = field.payload();
            var ref = payload.path("candidateRef").asText("").strip();
            if (ref.isBlank()) ref = payload.path("relationId").asText("").strip();
            if (!ref.isBlank()) refs.add(ref);
        }
        return refs;
    }

    private FieldCoverage fieldCoverage(JsonNode region,
            List<RecognitionModelClient.ModelSuggestion> suggestions) {
        var expectedRefs = new LinkedHashSet<String>();
        region.path("expectedCandidateRefs").forEach(ref -> {
            if (ref.isTextual() && !ref.asText().isBlank()) expectedRefs.add(ref.asText());
        });
        if (expectedRefs.isEmpty()) return new FieldCoverage(0, 0, 0);
        var returnedRefs = new LinkedHashSet<String>();
        var validRefs = new LinkedHashSet<String>();
        var pendingRefs = new LinkedHashSet<String>();
        for (var suggestion : suggestions == null ? List.<RecognitionModelClient.ModelSuggestion>of() : suggestions) {
            var payload = suggestion.payload();
            if ("SCALAR_FIELD".equals(suggestion.suggestionType())
                    || "REPEAT_FIELD".equals(payload.path("mappingKind").asText(""))) {
                var ref = payload.path("candidateRef").asText("");
                addMatchingCandidate(returnedRefs, expectedRefs, ref);
                if (expectedRefs.contains(ref)) {
                    if (hasValidFieldLocation(payload)) validRefs.add(ref);
                    if (isPendingField(payload)) pendingRefs.add(ref);
                }
            }
            for (var column : payload.path("columns")) {
                var ref = column.path("candidateRef").asText("");
                addMatchingCandidate(returnedRefs, expectedRefs, ref);
                if (expectedRefs.contains(ref)) {
                    if (hasValidFieldLocation(column)) validRefs.add(ref);
                    if (isPendingField(column)) pendingRefs.add(ref);
                }
            }
        }
        // Missing candidates are pending by definition; an explicitly returned
        // candidate remains pending when it has no usable locator or is marked
        // for human confirmation.
        var missingOrInvalid = new LinkedHashSet<>(expectedRefs);
        missingOrInvalid.removeAll(validRefs);
        pendingRefs.addAll(missingOrInvalid);
        return new FieldCoverage(returnedRefs.size(), validRefs.size(), pendingRefs.size());
    }

    private boolean hasValidFieldLocation(JsonNode field) {
        var locator = field.path("locator");
        var range = locator.path("value").path("range").asText("");
        if (range.isBlank()) range = locator.path("valueRange").asText("");
        if (range.isBlank()) range = field.path("valueRange").asText("");
        if (range.isBlank()) range = locator.path("range").asText("");
        if (range.isBlank()) range = locator.path("address").asText("");
        return bounds(range) != null;
    }

    private boolean isPendingField(JsonNode field) {
        return field.path("reviewRequired").asBoolean(false)
                || field.path("candidateOnly").asBoolean(false)
                || field.path("semanticFallback").asBoolean(false)
                || field.path("positionPending").asBoolean(false);
    }

    private record FieldCoverage(int returned, int valid, int pending) {
    }

    private void addMatchingCandidate(Set<String> returned, Set<String> expected, String candidateRef) {
        var ref = candidateRef == null ? "" : candidateRef.strip();
        if (!ref.isBlank() && expected.contains(ref)) returned.add(ref);
    }

    private boolean hasSemanticSuggestion(
            JsonNode region, List<RecognitionModelClient.ModelSuggestion> suggestions
    ) {
        var type = region.path("type").asText(region.path("blockType").asText(""));
        var expectedId = region.path("blockId").asText(region.path("temporaryId").asText(""));
        var range = region.path("range").asText("");
        for (var suggestion : suggestions == null ? List.<RecognitionModelClient.ModelSuggestion>of() : suggestions) {
            var payload = suggestion.payload();
            var candidateRef = payload.path("candidateRef").asText("");
            var blockId = payload.path("blockId").asText(payload.path("regionId").asText(""));
            if (!expectedId.equals(candidateRef) && !expectedId.equals(blockId)) continue;
            var kind = payload.path("kind").asText(payload.path("tableKind").asText(""));
            if (Set.of("ROW_TABLE", "COLUMN_TABLE").contains(type)) {
                var locatorRange = payload.path("locator").path("range")
                        .asText(payload.path("range").asText(range));
                if (isTableSuggestion(suggestion) && type.equals(kind)
                        && range.equalsIgnoreCase(locatorRange)) return true;
            } else if ("FORM_REGION".equals(type)) {
                var locator = payload.path("locator");
                var locatorRange = locator.path("range").asText(payload.path("range").asText(range));
                if ("SCALAR_FIELD".equals(suggestion.suggestionType())
                        && containsRange(range, locatorRange)) return true;
            } else if (payload.path("locator").path("range").asText(range).equalsIgnoreCase(range)
                    || "SCALAR_FIELD".equals(suggestion.suggestionType())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsRange(String outer, String inner) {
        var outerBounds = bounds(outer);
        var innerBounds = bounds(inner);
        return outerBounds != null && innerBounds != null
                && outerBounds[0] <= innerBounds[0] && outerBounds[1] <= innerBounds[1]
                && outerBounds[2] >= innerBounds[2] && outerBounds[3] >= innerBounds[3];
    }

    private int[] bounds(String value) {
        var normalized = value == null ? "" : value.replace("$", "").toUpperCase(java.util.Locale.ROOT);
        var parts = normalized.split(":", 2);
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

    private boolean isTableSuggestion(RecognitionModelClient.ModelSuggestion suggestion) {
        return Set.of("ROW_TABLE", "COLUMN_TABLE").contains(suggestion.suggestionType());
    }

    private ObjectNode regionDetail(JsonNode region, String status, boolean semantic) {
        return objectMapper.createObjectNode()
                .put("sheetId", region.path("sheetId").asText(""))
                .put("range", region.path("range").asText(""))
                .put("type", region.path("type").asText(region.path("blockType").asText("")))
                .put("geometryStatus", region.path("geometryStatus").asText(
                        region.path("validationStatus").asText("VALID_GEOMETRY")))
                .put("validationStatus", region.path("validationStatus").asText("VALID"))
                .put("structureStatus", region.path("structureStatus").asText("PROVISIONAL"))
                .put("structureConflict", region.path("structureConflict").asBoolean(false))
                .put("modelAssessmentVerdict", region.path("modelAssessmentVerdict").asText("MODEL_UNRESOLVED"))
                .put("status", status)
                .put("semanticSuggestionPresent", semantic)
                .put("expectedFieldCount", region.path("expectedFieldCount").asInt(0))
                .put("candidateId", region.path("candidateId").asText(region.path("id").asText("")));
    }

    private String issueFor(JsonNode region, String callState, boolean semantic) {
        if ("UNKNOWN".equals(region.path("type").asText(region.path("blockType").asText("")))) {
            return "区域 " + region.path("range").asText("") + "：STRUCTURE_DIRECTION_UNCLEAR（无法唯一判断按行或按列重复方向）";
        }
        if (!"SUCCEEDED".equals(callState)) {
            return "区域 " + region.path("range").asText("") + " 未完成第二阶段识别：" + callState;
        }
        if (region.path("structureConflict").asBoolean(false)
                || "CONFLICT".equals(region.path("structureStatus").asText(""))) {
            return "区域 " + region.path("range").asText("") + " 的模型结构与物理候选冲突，需要人工裁决";
        }
        if ("UNRESOLVED".equals(region.path("structureStatus").asText(""))
                || "MODEL_UNRESOLVED".equals(region.path("modelAssessmentVerdict").asText(""))) {
            return "区域 " + region.path("range").asText("") + " 尚未形成结构评估结论，需要人工确认";
        }
        if (!semantic) {
            return "区域 " + region.path("range").asText("") + " 已调用第二阶段，但没有返回匹配的结构建议";
        }
        return "区域 " + region.path("range").asText("") + " 需要人工确认";
    }

    private String regionKey(JsonNode region) {
        return region.path("sheetId").asText("") + "|"
                + region.path("range").asText("") + "|"
                + region.path("type").asText(region.path("blockType").asText(""));
    }

    public record Assessment(
            ObjectNode report,
            String status,
            int coveredRegionCount,
            int unresolvedRegionCount
    ) {
    }
}
