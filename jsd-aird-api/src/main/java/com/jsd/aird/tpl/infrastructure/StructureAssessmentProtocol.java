package com.jsd.aird.tpl.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.domain.QualityIssueSeverity;

/**
 * Independent workbook structure proposal protocol.
 *
 * The model does not receive backend structure candidates and therefore cannot
 * merely stamp MODEL_AGREES on a backend guess.  The resolver compares these
 * proposals with backend hypotheses after this protocol has completed.
 */
final class StructureAssessmentProtocol {

    static final int VERSION = 2;
    private static final Pattern RANGE = Pattern.compile(
            "^[A-Z]{1,4}[1-9][0-9]*(?::[A-Z]{1,4}[1-9][0-9]*)?$"
    );
    private static final Set<String> TYPES = Set.of(
            "ROW_TABLE", "COLUMN_TABLE", "MATRIX", "FORM_REGION", "UNKNOWN"
    );
    private static final Set<String> AXES = Set.of("ROW", "COLUMN", "UNKNOWN");
    private final ObjectMapper objectMapper;

    StructureAssessmentProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode responseSchema() {
        try {
            return objectMapper.readTree("""
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "type":"object","additionalProperties":false,
                      "required":["recognitionProtocolVersion","proposals","qualityIssues"],
                      "properties":{
                        "recognitionProtocolVersion":{"const":2},
                        "proposals":{"type":"array","items":{
                          "type":"object","additionalProperties":false,
                           "required":["proposalId","sheetId","type","range","recordAxis","confidence"],
                          "properties":{
                            "proposalId":{"type":"string","minLength":1},
                            "sheetId":{"type":"string","minLength":1},
                            "type":{"enum":["ROW_TABLE","COLUMN_TABLE","MATRIX","FORM_REGION","UNKNOWN"]},
                            "range":{"type":"string"},
                            "cornerRange":{"type":"string"},
                            "rowHeaderRange":{"type":"string"},
                            "columnHeaderRange":{"type":"string"},
                            "crossDataRange":{"type":"string"},
                            "headerRange":{"type":"string"},
                            "dataRange":{"type":"string"},
                            "totalRange":{"type":"string"},
                            "recordHeight":{"type":"integer","minimum":1},
                            "recordWidth":{"type":"integer","minimum":1},
                            "recordStride":{"type":"integer","minimum":1},
                            "recordAxis":{"enum":["ROW","COLUMN","UNKNOWN"]},
                            "confidence":{"type":"number","minimum":0,"maximum":1}
                          },
                          "allOf":[
                            {"if":{"properties":{"type":{"enum":["ROW_TABLE","COLUMN_TABLE"]}}},
                             "then":{"required":["headerRange","dataRange"]}},
                            {"if":{"properties":{"type":{"const":"MATRIX"}}},
                             "then":{"required":["cornerRange","rowHeaderRange","columnHeaderRange","crossDataRange"]}}
                          ]
                        }},
                        "qualityIssues":{"type":"array","items":{"type":"object","additionalProperties":true,
                          "properties":{"severity":{"enum":["INFO","WARNING","BLOCKER"]}}}}
                      }
                    }
                    """);
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载结构提议协议", exception);
        }
    }

    ValidationResult validate(JsonNode response, JsonNode physicalFacts) {
        requireObject(response, "结构提议响应");
        var root = (ObjectNode) response.deepCopy();
        exactKeys(root, Set.of("recognitionProtocolVersion", "proposals", "qualityIssues"), "结构提议响应");
        require(root.path("recognitionProtocolVersion").asInt(-1) == VERSION,
                "结构提议响应 recognitionProtocolVersion 必须为 2");
        require(root.path("proposals").isArray(), "proposals 必须是数组");
        require(root.path("qualityIssues").isArray(), "qualityIssues 必须是数组");

        var normalized = objectMapper.createArrayNode();
        var issues = normalizeQualityIssues((ArrayNode) root.path("qualityIssues"));
        var seen = new LinkedHashSet<String>();
        for (var item : root.path("proposals")) {
            try {
                if (!item.isObject()) throw violation("结构提议项必须是对象");
                exactKeys(item, Set.of("proposalId", "sheetId", "type", "range", "cornerRange",
                        "rowHeaderRange", "columnHeaderRange", "crossDataRange", "headerRange", "dataRange",
                        "totalRange", "recordHeight", "recordWidth", "recordStride", "recordAxis", "confidence"),
                        "结构提议项");
                var proposal = (ObjectNode) item.deepCopy();
                var proposalId = requiredText(proposal, "proposalId", "结构提议项");
                var sheetId = requiredText(proposal, "sheetId", "结构提议项");
                require(seen.add(proposalId), "proposalId 重复: " + proposalId);
                var type = requiredText(proposal, "type", "结构提议项");
                require(TYPES.contains(type),
                        "结构提议项 type 不合法");
                // FORM_REGION has no repeat topology. Older/less strict model
                // providers therefore sometimes omit recordAxis altogether.
                // Normalising that semantically empty value is geometry
                // preserving; doing the same for a table or matrix would hide a
                // material protocol error and is deliberately forbidden.
                if ("FORM_REGION".equals(type)
                        && (!proposal.path("recordAxis").isTextual()
                        || proposal.path("recordAxis").asText("").isBlank())) {
                    proposal.put("recordAxis", "UNKNOWN");
                    issues.add(objectMapper.createObjectNode()
                            .put("issueType", "PROTOCOL_DEFAULT_APPLIED")
                            .put("severity", "INFO")
                            .put("proposalId", proposalId)
                            .put("sheetId", sheetId)
                            .put("field", "recordAxis")
                            .put("defaultValue", "UNKNOWN")
                            .put("title", "表单区域协议默认值已补齐")
                            .put("description", "FORM_REGION 未返回 recordAxis，已机械补为 UNKNOWN；区域几何未改变。"));
                }
                require(AXES.contains(requiredText(proposal, "recordAxis", "结构提议项")),
                        "结构提议项 recordAxis 不合法");
                var confidence = proposal.path("confidence").asDouble(-1);
                require(confidence >= 0 && confidence <= 1, "结构提议项 confidence 不合法");
                for (var key : List.of("range", "cornerRange", "rowHeaderRange",
                        "columnHeaderRange", "crossDataRange", "headerRange", "dataRange", "totalRange")) {
                    var value = proposal.path(key).asText("");
                    require(value.isBlank() || RANGE.matcher(value.toUpperCase(Locale.ROOT)).matches(),
                            key + " 不是合法 Excel 区域");
                }
                validateGeometry(proposal, physicalFacts, sheetId);
                proposal.put("source", "MODEL").put("proposalStatus", "PROVISIONAL");
                normalized.add(proposal);
            } catch (RuntimeException invalidItem) {
                // A malformed proposal must not discard valid proposals from the same
                // workbook.  The item is kept in diagnostics for review/audit.
                issues.add(objectMapper.createObjectNode()
                        .put("issueType", diagnosticType(item, invalidItem))
                        .put("severity", "WARNING")
                        .put("title", "模型结构提议已忽略")
                        .put("description", safeMessage(invalidItem))
                        .set("proposal", item.deepCopy()));
            }
        }
        return new ValidationResult(normalized, issues);
    }

    private ArrayNode normalizeQualityIssues(ArrayNode source) {
        var result = objectMapper.createArrayNode();
        for (var issue : source) {
            if (issue != null && issue.isObject() && issue.size() > 0) {
                var normalized = (ObjectNode) issue.deepCopy();
                normalized.put("severity", QualityIssueSeverity.normalize(issue.path("severity").asText("")));
                result.add(normalized);
                continue;
            }
            result.add(objectMapper.createObjectNode()
                    .put("issueType", "MALFORMED_QUALITY_ISSUE")
                    .put("severity", "WARNING")
                    .put("title", "模型质量问题项格式不完整")
                    .put("description", "模型返回了空的质量问题对象，系统已保留为待复核诊断。")
                    .put("businessImpact", "该质量问题无法自动解释，相关识别结果需要人工复核。")
                    .set("evidence", objectMapper.createObjectNode().put("rawIssue", issue == null ? "null" : issue.toString())));
        }
        return result;
    }

    /** Compatibility adapter for historical readers only; new code does not call it. */
    ObjectNode toLegacyEnvelope(ValidationResult result, JsonNode physicalFacts) {
        var envelope = objectMapper.createObjectNode().put("recognitionProtocolVersion", 1);
        envelope.putArray("semanticAnnotations");
        var blocks = envelope.putArray("businessBlocks");
        envelope.putArray("fieldRelations");
        envelope.putArray("tables");
        envelope.set("qualityIssues", result.qualityIssues().deepCopy());
        for (var proposal : result.assessments()) {
            var type = "FORM_FIELDS".equals(proposal.path("type").asText())
                    ? "FORM_REGION" : proposal.path("type").asText("UNKNOWN");
            blocks.add(objectMapper.createObjectNode()
                    .put("temporaryId", "proposal-" + proposal.path("proposalId").asText())
                    .put("sheetId", proposal.path("sheetId").asText())
                    .put("range", proposal.path("range").asText())
                    .put("type", type).put("parentTemporaryId", "")
                    .put("businessName", "待确认结构")
                    .put("groupNameSuggestion", "").put("semanticKeySuggestion", ""));
        }
        return envelope;
    }

    private void validateGeometry(ObjectNode proposal, JsonNode physicalFacts, String sheetId) {
        var range = proposal.path("range").asText("");
        var used = usedRange(physicalFacts, sheetId);
        require(sheetExists(physicalFacts, sheetId), "结构提议引用了不存在的 Sheet: " + sheetId);
        require(!range.isBlank(), "结构提议 range 不能为空");
        if (!used.isBlank()) require(contains(used, range), "结构提议范围必须位于 usedRange 内: " + range);
        for (var key : List.of("cornerRange", "rowHeaderRange", "columnHeaderRange", "crossDataRange")) {
            var value = proposal.path(key).asText("");
            if (!value.isBlank()) require(contains(range, value), key + " 必须位于 range 内: " + value);
        }
        for (var key : List.of("headerRange", "dataRange", "totalRange")) {
            var value = proposal.path(key).asText("");
            if (!value.isBlank()) require(contains(range, value), key + " 必须位于 range 内: " + value);
        }
        if ("MATRIX".equals(proposal.path("type").asText())) {
            require(!proposal.path("cornerRange").asText("").isBlank(), "MATRIX 缺少 cornerRange");
            require(!proposal.path("rowHeaderRange").asText("").isBlank(), "MATRIX 缺少 rowHeaderRange");
            require(!proposal.path("columnHeaderRange").asText("").isBlank(), "MATRIX 缺少 columnHeaderRange");
            require(!proposal.path("crossDataRange").asText("").isBlank(), "MATRIX 缺少 crossDataRange");
            require(sameWidth(proposal.path("columnHeaderRange").asText(), proposal.path("crossDataRange").asText()),
                    "MATRIX 列标题和交叉数据宽度不一致");
            require(sameHeight(proposal.path("rowHeaderRange").asText(), proposal.path("crossDataRange").asText()),
                    "MATRIX 行标题和交叉数据高度不一致");
        } else if (Set.of("ROW_TABLE", "COLUMN_TABLE").contains(proposal.path("type").asText())) {
            require(!proposal.path("headerRange").asText("").isBlank(), "普通表格缺少 headerRange");
            require(!proposal.path("dataRange").asText("").isBlank(), "普通表格缺少 dataRange");
            var expectedAxis = "ROW_TABLE".equals(proposal.path("type").asText()) ? "ROW" : "COLUMN";
            require(expectedAxis.equals(proposal.path("recordAxis").asText("UNKNOWN")),
                    proposal.path("type").asText() + " 的 recordAxis 必须为 " + expectedAxis);
        }
    }

    private String diagnosticType(JsonNode item, RuntimeException invalidItem) {
        var type = item.path("type").asText("");
        var message = invalidItem.getMessage() == null ? "" : invalidItem.getMessage();
        if (Set.of("ROW_TABLE", "COLUMN_TABLE").contains(type)
                && message.contains("缺少")
                && (message.contains("headerRange") || message.contains("dataRange"))) {
            return "MISSING_TABLE_GEOMETRY";
        }
        if ("MATRIX".equals(type)
                && message.contains("缺少")
                && (message.contains("cornerRange") || message.contains("rowHeaderRange")
                || message.contains("columnHeaderRange") || message.contains("crossDataRange"))) {
            return "MISSING_MATRIX_GEOMETRY";
        }
        return "INVALID_STRUCTURE_PROPOSAL";
    }

    private boolean sheetExists(JsonNode facts, String sheetId) {
        if (!facts.path("sheets").isArray()) return false;
        for (var sheet : facts.path("sheets")) {
            if (sheetId.equals(sheet.path("id").asText(sheet.path("sheetId").asText("")))) return true;
        }
        return false;
    }

    private boolean sameWidth(String first, String second) {
        var a = rangeBounds(first); var b = rangeBounds(second);
        return a != null && b != null && a[2] - a[0] == b[2] - b[0];
    }

    private boolean sameHeight(String first, String second) {
        var a = rangeBounds(first); var b = rangeBounds(second);
        return a != null && b != null && a[3] - a[1] == b[3] - b[1];
    }

    private String usedRange(JsonNode facts, String sheetId) {
        for (var sheet : facts.path("sheets")) {
            var id = sheet.path("id").asText(sheet.path("sheetId").asText(""));
            if (sheetId.equals(id)) return sheet.path("usedRange").asText("");
        }
        return "";
    }

    private boolean contains(String outer, String inner) {
        var a = rangeBounds(outer);
        var b = rangeBounds(inner);
        return a != null && b != null && a[0] <= b[0] && a[1] <= b[1]
                && a[2] >= b[2] && a[3] >= b[3];
    }

    private int[] rangeBounds(String value) {
        if (value == null || value.isBlank()) return null;
        var parts = value.replace("$", "").toUpperCase(Locale.ROOT).split(":", 2);
        var first = cellBounds(parts[0]);
        var last = cellBounds(parts.length == 1 ? parts[0] : parts[1]);
        if (first == null || last == null) return null;
        return new int[]{Math.min(first[0], last[0]), Math.min(first[1], last[1]),
                Math.max(first[0], last[0]), Math.max(first[1], last[1])};
    }

    private int[] cellBounds(String value) {
        var matcher = Pattern.compile("^([A-Z]{1,4})([1-9][0-9]*)$").matcher(value);
        if (!matcher.matches()) return null;
        var column = 0;
        for (var character : matcher.group(1).toCharArray()) column = column * 26 + character - 'A' + 1;
        return new int[]{column, Integer.parseInt(matcher.group(2))};
    }

    private String requiredText(JsonNode node, String key, String name) {
        require(node.path(key).isTextual(), name + "." + key + " 必须是字符串");
        return node.path(key).asText();
    }

    private void exactKeys(JsonNode node, Set<String> allowed, String name) {
        node.fieldNames().forEachRemaining(key -> require(allowed.contains(key), name + "包含未定义字段: " + key));
    }

    private void requireObject(JsonNode node, String name) {
        require(node != null && node.isObject(), name + "必须是对象");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw violation(message);
    }

    private IllegalArgumentException violation(String message) {
        return new IllegalArgumentException(message);
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    record ValidationResult(ArrayNode assessments, JsonNode qualityIssues) {
    }
}
