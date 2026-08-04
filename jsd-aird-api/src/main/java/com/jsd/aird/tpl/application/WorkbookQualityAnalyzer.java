package com.jsd.aird.tpl.application;

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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import org.springframework.stereotype.Component;

/** Finds layout/content quality problems and applies only reversible, locally safe fixes. */
@Component
public class WorkbookQualityAnalyzer {

    private static final Pattern ADDRESS = Pattern.compile("^([A-Z]{1,4})([1-9][0-9]*)$");
    private static final Pattern MIXED_ROLE = Pattern.compile(
            "^\\s*([^：:\\r\\n]{1,16})[：:]\\s*(.{4,})\\s*$", Pattern.DOTALL
    );
    private static final Pattern HAS_WORD = Pattern.compile(".*[\\p{L}\\p{IsHan}].*");

    private final ObjectMapper objectMapper;

    public WorkbookQualityAnalyzer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Analysis analyze(JsonNode structure, JsonNode snapshot, Set<String> mappedLocations) {
        return analyze(structure, snapshot, mappedLocations, true);
    }

    public Analysis analyze(
            JsonNode structure, JsonNode snapshot, Set<String> mappedLocations, boolean applySafeFixes
    ) {
        var patched = snapshot.deepCopy();
        var issues = new ArrayList<RecognitionModelClient.QualityIssueSuggestion>();
        var bySheetAddress = candidates(structure.path("candidateCells"));
        var occupiedTargets = new HashSet<String>();

        for (var candidate : structure.path("candidateCells")) {
            if (candidate.path("empty").asBoolean(true) || candidate.path("formula").asBoolean(false)
                    || !candidate.path("value").isTextual()) continue;
            var value = candidate.path("value").asText("");
            var matcher = MIXED_ROLE.matcher(value);
            if (!matcher.matches()) {
                if (looksLikeMultipleRoles(value)) {
                    issues.add(issue(candidate, "MIXED_CELL_ROLES", "WARNING", 0.78,
                            "一个单元格可能包含多项业务内容",
                            "系统识别到多个分隔段或列表项，建议核对是否需要拆分。",
                            "混合内容会降低字段定位和后续检索的准确性。",
                            false, objectMapper.createObjectNode(), objectMapper.createObjectNode(), "DETECTED"));
                }
                continue;
            }
            var title = matcher.group(1).strip();
            var content = matcher.group(2).strip();
            if (!validTitle(title, content)) continue;
            var sheetId = candidate.path("sheetId").asText();
            var address = candidate.path("address").asText().toUpperCase(Locale.ROOT);
            var target = safeTarget(candidate, bySheetAddress, occupiedTargets, mappedLocations, structure);
            var confidence = title.length() <= 8 && content.length() >= 8 ? 0.96 : 0.93;
            var sourceSafe = !candidate.path("hasComment").asBoolean(false)
                    && !candidate.path("hasHyperlink").asBoolean(false)
                    && !candidate.path("sheetProtected").asBoolean(false)
                    && !hasValidation(structure, sheetId, address)
                    && !isReferenced(structure, sheetId, address)
                    && !mappedLocations.contains(location(sheetId, address));
            var autoFix = target != null && sourceSafe && confidence >= 0.92;
            var patch = target == null ? objectMapper.createObjectNode()
                    : patch(sheetId, address, value, title, target.path("address").asText(), "", content);
            var inverse = target == null ? objectMapper.createObjectNode()
                    : patch(sheetId, address, title, value, target.path("address").asText(), content, "");
            var status = applySafeFixes && autoFix && apply(patched, patch)
                    ? "AUTO_APPLIED" : "DETECTED";
            if ("AUTO_APPLIED".equals(status)) occupiedTargets.add(location(sheetId, target.path("address").asText()));
            issues.add(issue(candidate, "MIXED_CELL_ROLES", "WARNING", confidence,
                    "标题和正文写在同一个单元格",
                    "系统认为可无损拆分为标题“" + title + "”和独立正文。",
                    "混写会影响字段分组、内容定位和后续检索。",
                    autoFix, patch, inverse, status));
        }

        return new Analysis(patched, List.copyOf(issues), issues.stream()
                .anyMatch(issue -> "AUTO_APPLIED".equals(issue.status())));
    }

    public boolean apply(JsonNode snapshot, JsonNode patch) {
        if (!(snapshot instanceof ObjectNode root) || !patch.path("operations").isArray()) return false;
        for (var operation : patch.path("operations")) {
            if (!"SET_CELL".equals(operation.path("op").asText())) return false;
            var sheetId = operation.path("sheetId").asText();
            var point = point(operation.path("address").asText());
            if (point == null || !root.path("sheets").path(sheetId).isObject()) return false;
            var sheet = (ObjectNode) root.withObject("sheets").withObject(sheetId);
            var row = sheet.withObject("cellData").withObject(String.valueOf(point[1] - 1));
            var cell = row.withObject(String.valueOf(point[0] - 1));
            var actual = cell.path("v").asText("");
            if (!actual.equals(operation.path("expectedValue").asText(""))) return false;
        }
        for (var operation : patch.path("operations")) {
            var point = point(operation.path("address").asText());
            var sheet = (ObjectNode) root.withObject("sheets").withObject(operation.path("sheetId").asText());
            var cell = sheet.withObject("cellData").withObject(String.valueOf(point[1] - 1))
                    .withObject(String.valueOf(point[0] - 1));
            var value = operation.path("value").asText("");
            if (value.isBlank()) {
                cell.remove("v");
                cell.remove("t");
            } else {
                cell.put("v", value);
                cell.put("t", 1);
            }
        }
        return true;
    }

    private Map<String, JsonNode> candidates(JsonNode source) {
        var result = new HashMap<String, JsonNode>();
        for (var candidate : source) result.put(location(
                candidate.path("sheetId").asText(), candidate.path("address").asText()), candidate);
        return result;
    }

    private JsonNode safeTarget(
            JsonNode source, Map<String, JsonNode> candidates, Set<String> occupiedTargets,
            Set<String> mappedLocations, JsonNode structure
    ) {
        if (!source.path("mergedRange").asText("").isBlank()) return null;
        var point = point(source.path("address").asText());
        if (point == null) return null;
        var sheetId = source.path("sheetId").asText();
        var right = columnName(point[0] + 1) + point[1];
        var below = columnName(point[0]) + (point[1] + 1);
        for (var address : List.of(right, below)) {
            var key = location(sheetId, address);
            var target = candidates.get(key);
            if (target == null || !target.path("empty").asBoolean(false)
                    || !target.path("style").isObject() || target.path("formula").asBoolean(false)
                    || target.path("hasComment").asBoolean(false)
                    || target.path("hasHyperlink").asBoolean(false)
                    || target.path("sheetProtected").asBoolean(false)
                    || occupiedTargets.contains(key) || mappedLocations.contains(key)
                    || hasValidation(structure, sheetId, address)
                    || isReferenced(structure, sheetId, address)) continue;
            return target;
        }
        return null;
    }

    private boolean hasValidation(JsonNode structure, String sheetId, String address) {
        for (var validation : structure.path("dataValidations")) {
            if (sheetId.equals(validation.path("sheetId").asText())
                    && overlaps(address, validation.path("address").asText())) return true;
        }
        return false;
    }

    private boolean isReferenced(JsonNode structure, String sheetId, String address) {
        var reference = Pattern.compile("(?i)(?:^|[^A-Z0-9_])(?:'[^']+'!)?" + Pattern.quote(address)
                + "(?:[^0-9]|$)");
        for (var candidate : structure.path("candidateCells")) {
            if (!candidate.path("formula").asBoolean(false)) continue;
            if (reference.matcher(candidate.path("value").asText("")).find()) return true;
        }
        for (var name : structure.path("namedRanges")) {
            if (reference.matcher(name.path("formula").asText("")).find()) return true;
        }
        return false;
    }

    private RecognitionModelClient.QualityIssueSuggestion issue(
            JsonNode source, String type, String severity, double confidence, String title,
            String description, String impact, boolean autoFixable, JsonNode patch,
            JsonNode inverse, String status
    ) {
        return new RecognitionModelClient.QualityIssueSuggestion(
                type, severity, source.path("sheetId").asText(), source.path("sheetName").asText(),
                source.path("address").asText(), title, description, impact, confidence,
                autoFixable, patch, inverse,
                objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .put("address", source.path("address").asText())
                        .put("originalText", source.path("value").asText(""))),
                status, source.path("regionId").asText(""), null
        );
    }

    private ObjectNode patch(
            String sheetId, String sourceAddress, String sourceExpected, String sourceValue,
            String targetAddress, String targetExpected, String targetValue
    ) {
        var result = objectMapper.createObjectNode();
        var operations = objectMapper.createArrayNode();
        operations.add(setOperation(sheetId, sourceAddress, sourceExpected, sourceValue));
        operations.add(setOperation(sheetId, targetAddress, targetExpected, targetValue));
        result.set("operations", operations);
        return result;
    }

    private ObjectNode setOperation(String sheetId, String address, String expected, String value) {
        return objectMapper.createObjectNode().put("op", "SET_CELL").put("sheetId", sheetId)
                .put("address", address).put("expectedValue", expected).put("value", value);
    }

    private boolean validTitle(String title, String content) {
        if (!HAS_WORD.matcher(title).matches() || content.isBlank()) return false;
        var lower = title.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http") || title.contains("/") || title.matches("\\d{1,2}")) return false;
        return !content.startsWith("//") && !title.matches("[A-Za-z]+\\d+");
    }

    private boolean looksLikeMultipleRoles(String value) {
        if (value.length() < 8) return false;
        var separators = value.chars().filter(character -> character == ':' || character == '：').count();
        var lineBreaks = value.chars().filter(character -> character == '\n').count();
        var bullets = value.matches("(?s).*(^|\\n)\\s*([0-9]+[.、]|[-•])\\s+.*");
        return separators >= 2 || lineBreaks >= 2 || bullets;
    }

    private boolean overlaps(String left, String right) {
        var leftPoint = point(left.split(":", 2)[0]);
        var rightStart = point(right.split(":", 2)[0]);
        var rightParts = right.split(":", 2);
        var rightEnd = point(rightParts.length == 2 ? rightParts[1] : rightParts[0]);
        return leftPoint != null && rightStart != null && rightEnd != null
                && leftPoint[0] >= Math.min(rightStart[0], rightEnd[0])
                && leftPoint[0] <= Math.max(rightStart[0], rightEnd[0])
                && leftPoint[1] >= Math.min(rightStart[1], rightEnd[1])
                && leftPoint[1] <= Math.max(rightStart[1], rightEnd[1]);
    }

    private int[] point(String address) {
        var matcher = ADDRESS.matcher(address == null ? "" : address.toUpperCase(Locale.ROOT));
        if (!matcher.matches()) return null;
        var column = 0;
        for (var letter : matcher.group(1).toCharArray()) column = column * 26 + letter - 'A' + 1;
        return new int[]{column, Integer.parseInt(matcher.group(2))};
    }

    private String columnName(int column) {
        var value = column;
        var result = new StringBuilder();
        while (value > 0) {
            value--;
            result.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }
        return result.toString();
    }

    private String location(String sheetId, String address) {
        return sheetId + "|" + (address == null ? "" : address.toUpperCase(Locale.ROOT));
    }

    public record Analysis(
            JsonNode snapshot,
            List<RecognitionModelClient.QualityIssueSuggestion> issues,
            boolean changed
    ) {
    }
}
