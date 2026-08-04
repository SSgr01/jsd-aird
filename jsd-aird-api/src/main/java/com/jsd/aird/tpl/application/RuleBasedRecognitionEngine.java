package com.jsd.aird.tpl.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.json.JsonCanonicalizer;
import com.jsd.aird.tpl.application.port.RecognitionModelClient;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Component;

/**
 * Conservative fallback for explicit physical label/value pairs. It deliberately
 * ignores business keywords and never guesses tables or blocks; its low-confidence
 * candidates are used only when the global model is unavailable or fails.
 */
@Component
public class RuleBasedRecognitionEngine {

    private static final Pattern EXPLICIT_LABEL = Pattern.compile("^\\s*([^：:\\r\\n]{1,30})[：:]\\s*$");

    private final ObjectMapper objectMapper;
    private final JsonCanonicalizer canonicalizer;

    public RuleBasedRecognitionEngine(ObjectMapper objectMapper, JsonCanonicalizer canonicalizer) {
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
    }

    public RecognitionModelClient.RecognitionBatch recognize(
            TemplateFormat format, String sourceFileName, JsonNode structure
    ) {
        if (format == TemplateFormat.XLSX && structure.path("structureVersion").asInt() != 6) {
            throw new IllegalArgumentException("Excel structureVersion 必须为 6");
        }
        var fingerprint = canonicalizer.hash(structure);
        var suggestions = format == TemplateFormat.XLSX ? explicitLabelValueCandidates(structure) : List
                .<RecognitionModelClient.ModelSuggestion>of();
        return new RecognitionModelClient.RecognitionBatch(
                suggestions, List.of(), "physical-facts", "conservative-label-value-v6",
                "physical-fallback-v6", fingerprint,
                canonicalizer.hashText(sourceFileName + "|" + fingerprint), null
        );
    }

    private List<RecognitionModelClient.ModelSuggestion> explicitLabelValueCandidates(JsonNode structure) {
        var byPosition = new HashMap<String, JsonNode>();
        for (var cell : structure.path("semanticCells")) {
            byPosition.put(position(cell.path("sheetId").asText(), cell.path("row").asInt(),
                    cell.path("column").asInt()), cell);
        }
        var result = new ArrayList<RecognitionModelClient.ModelSuggestion>();
        for (var label : structure.path("semanticCells")) {
            if (!label.path("value").isTextual() || label.path("formula").isTextual()) continue;
            var matcher = EXPLICIT_LABEL.matcher(label.path("value").asText(""));
            if (!matcher.matches()) continue;
            var sheetId = label.path("sheetId").asText();
            var value = byPosition.get(position(sheetId, label.path("row").asInt(),
                    label.path("column").asInt() + 1));
            if (value == null || (!label.path("mergedRange").asText("").isBlank()
                    && label.path("mergedRange").asText("")
                    .equalsIgnoreCase(value.path("mergedRange").asText("")))) continue;
            var labelAddress = label.path("address").asText().toUpperCase(Locale.ROOT);
            var valueAddress = value.path("mergedRange").asText(value.path("address").asText())
                    .toUpperCase(Locale.ROOT);
            if (valueAddress.isBlank()) continue;
            var relationId = RecognitionIdentity.relationId(
                    sheetId, labelAddress, valueAddress, "LABEL_VALUE"
            );
            var fieldId = RecognitionIdentity.fieldId(relationId);
            var bindingId = RecognitionIdentity.bindingId(
                    fieldId, "CELL_RANGE", sheetId + "|" + valueAddress
            );
            var formula = value.path("factType").asText().equals("FORMULA");
            var payload = objectMapper.createObjectNode()
                    .put("kind", "SCALAR").put("relationId", relationId)
                    .put("fieldId", fieldId.toString()).put("bindingId", bindingId.toString())
                    .put("fieldCode", "AUTO.BASIC_INFORMATION.FIELD_"
                            + RecognitionIdentity.shortHash(relationId, 8).toUpperCase(Locale.ROOT))
                    .put("dataPath", "/recognized/basicInformation/field_"
                            + RecognitionIdentity.shortHash(relationId, 12))
                    .put("fieldName", matcher.group(1).strip())
                    .put("groupName", GroupNameNormalizer.BASIC_INFORMATION)
                    .put("valueType", physicalValueType(value)).put("required", false)
                    .put("role", "FIELD").put("locatorType", "CELL_RANGE")
                    .put("editability", formula ? "READ_ONLY" : "EDITABLE")
                    .put("valueSource", formula ? "FORMULA" : "USER_INPUT")
                    .put("reason", "智能识别未完成，根据明确的“标签：”和相邻值位置生成待核对候选")
                    .put("interpretation", "系统发现明确标签，请核对名称和填写位置。");
            payload.set("locator", objectMapper.createObjectNode()
                    .put("sheetId", sheetId).put("sheetName", label.path("sheetName").asText(sheetId))
                    .put("labelAddress", labelAddress).put("labelRange", labelAddress)
                    .put("address", valueAddress).put("anchorAddress", firstCell(valueAddress))
                    .put("logicalInputRange", valueAddress).put("valueMode", "ANCHOR"));
            var evidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                    .put("labelAddress", labelAddress).put("valueAddress", valueAddress)
                    .put("rule", "EXPLICIT_COLON_LABEL_WITH_ADJACENT_VALUE"));
            result.add(new RecognitionModelClient.ModelSuggestion(
                    "SCALAR_FIELD", payload, 0.58, evidence
            ));
        }
        return List.copyOf(result);
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
}
