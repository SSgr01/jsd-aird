package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import com.jsd.aird.tpl.application.port.StandardFieldRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Conservative fallback for explicit physical label/value pairs. It deliberately
 * ignores business keywords and never guesses tables or blocks; its low-confidence
 * candidates are used only when the global model is unavailable or fails.
 */
@Component
public class RuleBasedRecognitionEngine {

    private static final Pattern EXPLICIT_LABEL = Pattern.compile("^\\s*([^：:\\r\\n]{1,30})[：:]\\s*$");
    private static final Pattern INLINE_LABEL = Pattern.compile("^\\s*([^：:\\r\\n]{1,30})[：:]\\s*(.+?)\\s*$");
    private static final Pattern NUMBERED_LABEL = Pattern.compile("^\\s*\\d{1,3}[.．、)）]\\s*(.{1,60}?)\\s*$");
    private static final Set<String> STATIC_PREFIXES = Set.of("注", "备注", "注意", "说明", "提示", "操作要求");

    private final ObjectMapper objectMapper;
    private final JsonCanonicalizer canonicalizer;
    private final StandardFieldRepository standardFieldRepository;

    @Autowired
    public RuleBasedRecognitionEngine(
            ObjectMapper objectMapper,
            JsonCanonicalizer canonicalizer,
            StandardFieldRepository standardFieldRepository
    ) {
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.standardFieldRepository = standardFieldRepository;
    }

    public RuleBasedRecognitionEngine(ObjectMapper objectMapper, JsonCanonicalizer canonicalizer) {
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.standardFieldRepository = null;
    }

    public RecognitionModelClient.RecognitionBatch recognize(
            TemplateFormat format, String sourceFileName, JsonNode structure
    ) {
        if (format == TemplateFormat.XLSX && structure.path("structureVersion").asInt() != 6) {
            throw new IllegalArgumentException("Excel structureVersion 必须为 6");
        }
        var fingerprint = canonicalizer.hash(structure);
        // Word is a document template, not an Excel-style business-field template.
        // Its deterministic output is the documentIR/structure tree produced by
        // DocxStructureParser.  Even explicit content controls are document facts;
        // they must not silently become TemplateBinding suggestions.
        var suggestions = format == TemplateFormat.XLSX
                ? explicitLabelValueCandidates(structure)
                : List.<RecognitionModelClient.ModelSuggestion>of();
        return new RecognitionModelClient.RecognitionBatch(
                suggestions, List.of(), "physical-facts", "conservative-label-value-v6",
                "physical-fallback-v6", fingerprint,
                canonicalizer.hashText(sourceFileName + "|" + fingerprint), null
        );
    }

    private List<RecognitionModelClient.ModelSuggestion> explicitLabelValueCandidates(JsonNode structure) {
        var byPosition = new HashMap<String, JsonNode>();
        var cells = semanticCells(structure);
        for (var cell : cells) {
            byPosition.put(position(cell.path("sheetId").asText(), cell.path("row").asInt(),
                    cell.path("column").asInt()), cell);
        }
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        for (var label : cells) {
            if (!label.path("value").isTextual() || label.path("formula").isTextual()) continue;
            var text = label.path("value").asText("");
            var sheetId = label.path("sheetId").asText();
            var inline = INLINE_LABEL.matcher(text);
            if (inline.matches()) {
                if (STATIC_PREFIXES.contains(inline.group(1).strip())) continue;
                result.add(candidate(label, label, inline.group(1).strip(),
                        label.path("address").asText(), "INLINE_TEXT", true));
                continue;
            }
            var matcher = EXPLICIT_LABEL.matcher(text);
            if (!matcher.matches()) continue;
            var row = label.path("row").asInt();
            var column = label.path("column").asInt();
            var merged = label.path("mergedRange").asText("");
            var rightColumn = Math.max(column, lastColumn(merged)) + 1;
            var bottomRow = Math.max(row, lastRow(merged)) + 1;
            // 兜底规则只保留一个最明确的邻接值：横向优先，只有横向没有值时
            // 才尝试纵向，避免同一标签生成互相竞争的两个候选。
            var horizontal = byPosition.get(position(sheetId, row, rightColumn));
            var vertical = byPosition.get(position(sheetId, bottomRow, column));
            var adjacent = validAdjacent(merged, horizontal)
                    ? new Adjacent("HORIZONTAL_LABEL_VALUE", horizontal)
                    : new Adjacent("VERTICAL_LABEL_VALUE", vertical);
            if (validAdjacent(merged, adjacent.value())) {
                var value = adjacent.value();
                result.add(candidate(label, value, matcher.group(1).strip(),
                        value.path("mergedRange").asText(value.path("address").asText()),
                        adjacent.relationType(), false));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Content controls are deterministic Word fields. Plain label paragraphs
     * are also surfaced as review-only candidates so a document still has a
     * useful field model when no external semantic model is configured. They
     * intentionally remain unbound until the user inserts a real content
     * control at the chosen position.
     */
    private List<RecognitionModelClient.ModelSuggestion> docxContentControlCandidates(JsonNode structure) {
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        var occupied = new java.util.HashSet<String>();
        var controls = structure.path("documentIR").path("contentControls");
        if (!controls.isArray()) controls = structure.path("contentControls");
        for (var control : controls) {
            var nodeId = control.path("nodeId").asText("");
            var contentControlId = control.path("contentControlId").asText("");
            var documentMarkerId = control.path("markerId").asText("");
            var tag = control.path("tag").asText("").strip();
            var alias = control.path("alias").asText("").strip();
            var text = control.path("text").asText("").strip();
            var name = firstNonPlaceholder(alias, tag, text);
            // w:id/contentControlId identifies the OOXML element only.  It is
            // not a data marker and cannot be used as a publishable binding;
            // only the explicit w:dataBinding storeItemID is stable across
            // controlled patches and document revisions.
            var markerId = documentMarkerId;
            if (nodeId.isBlank()) continue;
            var stableMarker = !documentMarkerId.isBlank();
            var fieldSeed = markerId + "|" + name;
            var fieldId = RecognitionIdentity.fieldId(RecognitionIdentity.relationId(
                    "docx", nodeId, markerId, "DOCX_CONTENT_CONTROL"));
            var fieldCode = tag.isBlank()
                    ? "AUTO.WORD.FIELD_" + RecognitionIdentity.shortHash(fieldSeed, 12).toUpperCase(Locale.ROOT)
                    : tag;
            var dataPath = "/recognized/word/" + safePathSegment(name, fieldId.toString());
            var payload = objectMapper.createObjectNode()
                    .put("kind", "SCALAR")
                    .put("role", "FIELD")
                    .put("blockType", "FORM_REGION")
                    .put("blockName", "Word 文档字段")
                    .put("fieldId", fieldId.toString())
                    .put("fieldCode", fieldCode)
                    .put("fieldName", name.isBlank() ? "待命名字段" : name)
                    .put("dataPath", dataPath)
                    .put("editability", "EDITABLE")
                    .put("valueSource", "USER_INPUT")
                    .put("valueType", "string")
                    .put("required", false)
                    .put("locatorType", "DOCX_CONTENT_CONTROL")
                    .put("markerId", markerId)
                    .put("source", "DOCX_CONTENT_CONTROL")
                    .put("candidateOnly", !stableMarker)
                    .put("reviewRequired", true)
                    .put("publishable", stableMarker)
                    .put("autoAccept", stableMarker)
                    .put("pendingReason", stableMarker ? "DOCX_FIELD_REVIEW" : "DOCX_MARKER_MISSING")
                    .put("standardMatchStatus", "UNMATCHED")
                    .put("requiresStandardConfirmation", false)
                    .put("groupName", GroupNameNormalizer.BASIC_INFORMATION)
                    .put("regionId", "docx-document")
                    .put("blockId", "docx-document")
                    .put("regionRange", "DOCX")
                    .put("candidateRef", nodeId);
            payload.set("locator", objectMapper.createObjectNode()
                    .put("nodeId", nodeId)
                    .put("markerId", markerId)
                    .put("contentControlId", contentControlId)
                    .put("tag", tag)
                    .put("alias", alias)
                    .put("text", text)
                    .put("locatorType", "DOCX_CONTENT_CONTROL"));
            var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                    .put("nodeId", nodeId).put("markerId", markerId)
                    .put("source", "DOCX_CONTENT_CONTROL"));
            result.add(new RecognitionModelClient.ModelSuggestion(
                    "SCALAR_FIELD", payload, stableMarker ? 0.98 : 0.65, evidence));
            occupied.add(nodeId);
        }

        var documentIr = structure.path("documentIR");
        var blocks = documentIr.path("blocks");
        if (!blocks.isArray()) blocks = structure.path("blocks");
        for (var block : blocks) {
            if (!"PARAGRAPH".equals(block.path("type").asText(""))) continue;
            var nodeId = block.path("id").asText("").strip();
            var text = block.path("text").asText("").strip();
            if (nodeId.isBlank() || text.isBlank() || occupied.contains(nodeId)) continue;
            var fieldName = docxLabelName(text);
            if (fieldName.isBlank() || STATIC_PREFIXES.contains(fieldName)) continue;
            result.add(docxTextLabelCandidate(nodeId, text, fieldName));
        }
        return List.copyOf(result);
    }

    private String docxLabelName(String text) {
        var inline = INLINE_LABEL.matcher(text);
        if (inline.matches()) return inline.group(1).strip();
        var explicit = EXPLICIT_LABEL.matcher(text);
        if (explicit.matches()) return explicit.group(1).strip();
        var numbered = NUMBERED_LABEL.matcher(text);
        return numbered.matches() ? numbered.group(1).strip() : "";
    }

    private RecognitionModelClient.ModelSuggestion docxTextLabelCandidate(
            String nodeId, String text, String fieldName
    ) {
        var relationId = RecognitionIdentity.relationId(
                "DOCX", nodeId, fieldName, "DOCX_TEXT_LABEL");
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var markerSeed = relationId + "|" + fieldName;
        var fieldCode = "AUTO.WORD.FIELD_"
                + RecognitionIdentity.shortHash(markerSeed, 12).toUpperCase(Locale.ROOT);
        var dataPath = "/recognized/word/" + safePathSegment(fieldName, fieldId.toString());
        var payload = objectMapper.createObjectNode()
                .put("kind", "SCALAR")
                .put("role", "FIELD")
                .put("relationId", relationId)
                .put("fieldId", fieldId.toString())
                .put("fieldCode", fieldCode)
                .put("fieldName", fieldName)
                .put("dataPath", dataPath)
                .put("editability", "EDITABLE")
                .put("valueSource", "USER_INPUT")
                .put("valueType", "string")
                .put("required", false)
                .put("locatorType", "DOCX_TEXT_LABEL")
                .put("source", "DOCX_TEXT_LABEL")
                .put("candidateOnly", true)
                .put("reviewRequired", true)
                .put("publishable", false)
                .put("autoAccept", false)
                .put("pendingReason", "DOCX_FIELD_POSITION_REQUIRED")
                .put("standardMatchStatus", "UNMATCHED")
                .put("requiresStandardConfirmation", false)
                .put("groupName", GroupNameNormalizer.BASIC_INFORMATION)
                .put("regionId", "docx-document")
                .put("blockId", "docx-document")
                .put("regionRange", "DOCX")
                .put("candidateRef", nodeId)
                .put("labelAnchor", nodeId)
                .put("valueAnchor", nodeId);
        payload.set("locator", objectMapper.createObjectNode()
                .put("nodeId", nodeId)
                .put("labelAnchor", nodeId)
                .put("valueAnchor", nodeId)
                .put("text", text)
                .put("locatorType", "DOCX_TEXT_LABEL"));
        var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("nodeId", nodeId)
                .put("text", text)
                .put("source", "DOCX_TEXT_LABEL_RULE"));
        return new RecognitionModelClient.ModelSuggestion(
                "SCALAR_FIELD", payload, 0.72, evidence);
    }

    private String firstNonPlaceholder(String... values) {
        for (var value : values) {
            if (value == null) continue;
            var normalized = value.strip();
            if (normalized.isBlank()) continue;
            if (normalized.matches("(?i)^(请输入|点击此处|单击此处|输入|placeholder).*$")) continue;
            return normalized;
        }
        return "";
    }

    private String safePathSegment(String value, String fallback) {
        var normalized = value == null ? "" : value.strip().replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        return normalized.isBlank() ? "field_" + RecognitionIdentity.shortHash(fallback, 10) : normalized;
    }

    private List<JsonNode> semanticCells(JsonNode structure) {
        var result = new ArrayList<JsonNode>();
        for (var sheet : structure.path("sheets")) {
            if (sheet.path("semanticCells").isArray()) sheet.path("semanticCells").forEach(result::add);
        }
        return result;
    }

    private RecognitionModelClient.ModelSuggestion candidate(
            JsonNode label, JsonNode value, String fieldName, String valueRange,
            String relationType, boolean inline
    ) {
        var sheetId = label.path("sheetId").asText();
        var labelAddress = label.path("address").asText().toUpperCase(Locale.ROOT);
        var normalizedValueRange = valueRange.toUpperCase(Locale.ROOT);
        var relationId = RecognitionIdentity.relationId(sheetId, labelAddress, normalizedValueRange, relationType);
        var fieldId = RecognitionIdentity.fieldId(relationId);
        var locatorType = inline ? "INLINE_TEXT" : "CELL_RANGE";
        var bindingId = RecognitionIdentity.bindingId(fieldId, locatorType, sheetId + "|" + normalizedValueRange);
        var formula = !inline && value.path("factType").asText().equals("FORMULA");
        var standard = standard(fieldName);
        var payload = objectMapper.createObjectNode()
                .put("kind", "SCALAR").put("relationId", relationId)
                .put("fieldId", fieldId.toString()).put("bindingId", bindingId.toString())
                .put("fieldCode", standard == null ? "AUTO.BASIC_INFORMATION.FIELD_"
                        + RecognitionIdentity.shortHash(relationId, 8).toUpperCase(Locale.ROOT)
                        : standard.fieldCode())
                .put("dataPath", standard == null ? "/recognized/basicInformation/field_"
                        + RecognitionIdentity.shortHash(relationId, 12)
                        : "/recognized/basicInformation/" + standard.pathSegment())
                // fieldName 保留原始模板标签，标准字段使用 fieldCode/dataPath 追踪；
                // 这样审核页仍能看到用户在 Excel 中实际写的名称。
                .put("fieldName", fieldName)
                .put("groupName", GroupNameNormalizer.BASIC_INFORMATION)
                .put("valueType", inline ? "string" : physicalValueType(value)).put("required", false)
                .put("role", "FIELD").put("locatorType", locatorType)
                .put("editability", formula ? "READ_ONLY" : "EDITABLE")
                .put("valueSource", formula ? "FORMULA" : "USER_INPUT")
                .put("dictionaryVersion", standard == null ? StandardFieldDictionary.VERSION : standard.version())
                .put("standardMatchStatus", standard == null ? "UNMATCHED" : "MATCHED")
                 .put("requiresStandardConfirmation", false)
                 .put("fieldOrigin", standard == null ? "TEMPLATE_LOCAL" : "STANDARD")
                 .put("standardSelectionStatus", standard == null ? "CUSTOM" : "MATCHED")
                 .put("requiresManualConfirmation", true)
                 .put("source", "RULE")
                 .put("reasonCode", "RULE_FALLBACK")
                .put("reason", "根据明确的标签和值位置生成待核对候选")
                .put("interpretation", "系统发现明确标签，请核对名称和填写位置。");
        var locator = objectMapper.createObjectNode()
                .put("sheetId", sheetId).put("sheetName", label.path("sheetName").asText(sheetId))
                .put("labelAddress", labelAddress).put("labelRange", labelAddress)
                .put("address", normalizedValueRange).put("anchorAddress", firstCell(normalizedValueRange))
                .put("logicalInputRange", normalizedValueRange).put("valueMode", inline ? "INLINE_TEXT" : "ANCHOR");
        if (inline) {
            locator.put("valuePart", "AFTER_DELIMITER").put("labelPrefix", fieldName);
        }
        payload.set("locator", locator);
        var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("labelAddress", labelAddress).put("valueAddress", normalizedValueRange)
                .put("rule", relationType));
        return new RecognitionModelClient.ModelSuggestion("SCALAR_FIELD", payload, 0.58, evidence);
    }

    private ResolvedStandard standard(String label) {
        if (standardFieldRepository != null) {
            try {
                var match = standardFieldRepository.search(label, null).stream().findFirst();
                if (match.isPresent()) {
                    var value = match.get();
                    return new ResolvedStandard(value.fieldCode(), value.version(),
                            pathSegment(value.fieldCode()));
                }
            } catch (RuntimeException ignored) {
                // Keep the conservative fallback available for offline imports.
            }
        }
        return StandardFieldDictionary.match(label)
                .map(value -> new ResolvedStandard(value.fieldCode(), StandardFieldDictionary.VERSION,
                        value.pathSegment()))
                .orElse(null);
    }

    private String pathSegment(String fieldCode) {
        var value = fieldCode == null ? "field" : fieldCode.substring(fieldCode.lastIndexOf('.') + 1);
        var result = new StringBuilder();
        for (var token : value.toLowerCase(Locale.ROOT).split("_")) {
            if (token.isBlank()) continue;
            result.append(result.isEmpty() ? token : Character.toUpperCase(token.charAt(0)) + token.substring(1));
        }
        return result.isEmpty() ? "field" : result.toString();
    }

    private record ResolvedStandard(String fieldCode, int version, String pathSegment) {
    }

    private String physicalValueType(JsonNode cell) {
        return switch (cell.path("physicalValueType").asText("").toLowerCase(Locale.ROOT)) {
            case "number", "numeric" -> "number";
            case "boolean" -> "boolean";
            default -> "string";
        };
    }

    private String position(String sheetId, int row, int column) {
        return sheetId + "|" + row + "|" + column;
    }

    private String firstCell(String range) {
        return range.split(":", 2)[0];
    }

    private int lastColumn(String range) {
        if (range == null || range.isBlank()) return 0;
        var cell = range.toUpperCase(Locale.ROOT).split(":", 2);
        var letters = cell[cell.length - 1].replaceAll("[0-9]+$", "");
        var result = 0;
        for (var letter : letters.toCharArray()) result = result * 26 + letter - 'A' + 1;
        return result;
    }

    private int lastRow(String range) {
        if (range == null || range.isBlank()) return 0;
        var cell = range.toUpperCase(Locale.ROOT).split(":", 2);
        var digits = cell[cell.length - 1].replaceAll("^[A-Z]+", "");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean validAdjacent(String labelMergedRange, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return false;
        if (labelMergedRange != null && !labelMergedRange.isBlank()
                && labelMergedRange.equalsIgnoreCase(value.path("mergedRange").asText(""))) return false;
        var text = value.path("value").asText("").strip();
        if (text.isBlank()) return value.path("inputCandidate").asBoolean(false)
                || "INPUT_CANDIDATE".equals(value.path("factType").asText(""));
        if (EXPLICIT_LABEL.matcher(text).matches() || INLINE_LABEL.matcher(text).matches()) return false;
        return !STATIC_PREFIXES.contains(text.replaceAll("[：:]$", ""));
    }

    private record Adjacent(String relationType, JsonNode value) {
    }
}
