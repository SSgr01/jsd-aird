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

    public RecognitionCoverageValidator(ObjectMapper objectMapper) {
        this(objectMapper, false);
    }

    public RecognitionCoverageValidator(ObjectMapper objectMapper, boolean topologyV2Enabled) {
        this.objectMapper = objectMapper;
        this.primitiveRecognizer = new StructurePrimitiveRecognizer(objectMapper, topologyV2Enabled);
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
        report.putArray("issues").add(reason == null || reason.isBlank()
                ? "物理结构区域尚未完成语义识别"
                : reason);
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
            var structureConflict = region.path("structureConflict").asBoolean(false)
                    || "CONFLICT".equals(region.path("structureStatus").asText(""));
            var structureUnresolved = "UNRESOLVED".equals(region.path("structureStatus").asText(""))
                    || "MODEL_UNRESOLVED".equals(region.path("modelAssessmentVerdict").asText(""));
            var canonicalConfirmed = "CONFIRMED".equals(region.path("canonicalStatus").asText())
                    && (!region.has("structureStatus")
                        || "CONFIRMED".equals(region.path("structureStatus").asText()));
            var complete = "SUCCEEDED".equals(callState) && semantic
                    && canonicalConfirmed && !structureConflict && !structureUnresolved;
            if (complete) covered++;
            else unresolved++;
            details.add(regionDetail(region,
                    complete ? "COVERED" : callState,
                    semantic));
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
                .put("globalStructureCallFailed", globalFailed);
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
            if (!Set.of("MATRIX", "ROW_TABLE", "COLUMN_TABLE", "FORM_REGION").contains(type)) continue;
            var geometryStatus = primitive.path("geometryStatus").asText("");
            if (!"VALID_GEOMETRY".equals(geometryStatus)) continue;
            result.add(primitive);
        }
        return List.copyOf(result);
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
            if (Set.of("MATRIX", "ROW_TABLE", "COLUMN_TABLE").contains(type)) {
                var locatorRange = payload.path("locator").path("range")
                        .asText(payload.path("range").asText(range));
                if (isTableSuggestion(suggestion) && type.equals(kind)
                        && range.equalsIgnoreCase(locatorRange)) return true;
            } else if ("FORM_REGION".equals(type) || "FORM_FIELDS".equals(type)) {
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
        var type = suggestion.suggestionType();
        var kind = suggestion.payload().path("kind")
                .asText(suggestion.payload().path("tableKind").asText(""));
        return Set.of("ROW_TABLE", "COLUMN_TABLE", "MATRIX", "TABLE_REGION", "TABLE_FIELD")
                .contains(type) || Set.of("ROW_TABLE", "COLUMN_TABLE", "MATRIX").contains(kind);
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
                .put("semanticSuggestionPresent", semantic);
    }

    private String issueFor(JsonNode region, String callState, boolean semantic) {
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
