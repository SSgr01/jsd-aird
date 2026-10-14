package com.jsd.aird.mfg.upload.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.api.TemplateOfficeFacade;
import org.springframework.stereotype.Service;

/**
 * Builds the recognition review of a production order upload, in the shape the
 * template center's field manager already consumes, so the workspace can present
 * the same 识别确认 / 字段结构 / 字段属性 panels instead of a bespoke list.
 *
 * <p>The template's field model describes which fields the form has and where each
 * one lives; that is the review's field list.  The template center's recognition
 * engine is then run over the uploaded file's structure so fields the template
 * mapping does not bind still surface as candidates, which is exactly the step the
 * template import flow performs for a template.</p>
 */
@Service
public class ProductionUploadRecognitionReviewAssembler {

    private final ObjectMapper objectMapper;
    private final TemplateOfficeFacade officeStructure;

    public ProductionUploadRecognitionReviewAssembler(ObjectMapper objectMapper,
                                                      TemplateOfficeFacade officeStructure) {
        this.objectMapper = objectMapper;
        this.officeStructure = officeStructure;
    }

    /**
     * Writes {@code recognitionReview} (the template center's review contract) and
     * {@code fieldModel} (the template's own field model) into the recognition
     * result, so no template structure has to be re-derived in the workspace.
     */
    public void attach(ObjectNode result, String format, String fileName, JsonNode structureSummary,
                       JsonNode schema, JsonNode mapping, JsonNode data) {
        attach(result, format, fileName, structureSummary, schema, mapping, data, null);
    }

    public void attach(ObjectNode result, String format, String fileName, JsonNode structureSummary,
                       JsonNode schema, JsonNode mapping, JsonNode data,
                       TemplateOfficeFacade.RecognitionBatch recognitionBatch) {
        var runId = UUID.randomUUID();
        var review = objectMapper.createObjectNode();
        review.put("recognitionRunId", runId.toString());
        review.put("runStatus", "SUCCEEDED");
        review.put("recognitionStatus", "RECOGNIZED");

        var fieldModel = schema == null ? null : schema.path("x-jsd-field-model");
        if (fieldModel != null && fieldModel.isObject()) review.set("semanticModel", fieldModel.deepCopy());

        var items = objectMapper.createArrayNode();
        var groups = new LinkedHashSet<String>();
        var bound = new LinkedHashSet<String>();
        var counts = new int[6]; // total, confirmed, pending, scalar, rowTable, columnTable
        // Template bindings never carry `fieldName`, so the schema has to be indexed
        // once to label the review rows a reviewer can recognise.
        var schemaNames = schemaDisplayNames(schema);
        for (var binding : bindings(mapping)) {
            var dataPath = binding.path("dataPath").asText("");
            bound.add(dataPath);
            bound.add(binding.path("fieldCode").asText(""));
            var kind = kindOf(binding);
            var groupName = binding.path("groupName").asText("");
            if (!groupName.isBlank()) groups.add(groupName);
            var value = valueAt(data, dataPath);
            var hasValue = value != null && !value.isBlank();
            counts[0]++;
            // A value produced by recognition is evidence, not a human decision.
            // Keep every newly recognised field in the pending queue so the
            // production-order workspace opens with the same "AI 识别待确认"
            // state as the template workspace.
            counts[2]++;
            if ("SCALAR".equals(kind)) counts[3]++;
            else if ("ROW_TABLE".equals(kind)) counts[4]++;
            else if ("COLUMN_TABLE".equals(kind)) counts[5]++;
            items.add(item(binding, binding, kind, groupName, "PENDING",
                    hasValue ? 1d : 0.6d, "TEMPLATE", "", schemaNames));
        }

        // Fields the engine found in the uploaded file that the template's mapping
        // does not bind: the reviewer sees them as candidates instead of losing them.
        for (var suggestion : recognitionBatch == null
                ? engineSuggestions(format, fileName, structureSummary)
                : batchSuggestions(recognitionBatch)) {
            var payload = suggestion.path("payload");
            var dataPath = payload.path("dataPath").asText("");
            var fieldCode = payload.path("fieldCode").asText("");
            if (dataPath.isBlank() || bound.contains(dataPath) || bound.contains(fieldCode)) continue;
            var kind = payload.path("kind").asText("SCALAR");
            var groupName = payload.path("groupName").asText("");
            if (!groupName.isBlank()) groups.add(groupName);
            counts[0]++;
            counts[2]++;
            items.add(item(payload, suggestion, kind, groupName, "PENDING",
                    suggestion.path("confidence").asDouble(0.5d), "RULE",
                    "未绑定到模板，确认后才会写入工作簿", schemaNames));
        }

        var regions = objectMapper.createArrayNode();
        var regionSuggestions = recognitionBatch == null
                ? engineSuggestions(format, fileName, structureSummary)
                : batchRegionSuggestions(recognitionBatch);
        if (regionSuggestions.isEmpty()) {
            regionSuggestions = engineSuggestions(format, fileName, structureSummary);
        }
        var seenRegions = new LinkedHashSet<String>();
        for (var suggestion : regionSuggestions) {
            var payload = suggestion.path("payload");
            var kind = normalizeKind(payload.path("kind").asText(""));
            if (!"ROW_TABLE".equals(kind) && !"COLUMN_TABLE".equals(kind)) continue;
            var locator = payload.path("locator");
            var range = firstNonBlank(locator.path("range").asText(""),
                    locator.path("dataRange").asText(""), locator.path("address").asText(""));
            var regionKey = locator.path("sheetId").asText("") + "|" + range + "|" + kind;
            if (range.isBlank() || !seenRegions.add(regionKey)) continue;
            var root = item(payload, suggestion, kind, payload.path("groupName").asText(""),
                    "PENDING", suggestion.path("confidence").asDouble(0.5d), "MODEL", "", schemaNames);
            var regionId = firstNonBlank(payload.path("regionId").asText(""),
                    payload.path("relationId").asText(""), root.path("id").asText(""));
            var rootPayload = (ObjectNode) root.path("payload");
            rootPayload.put("regionId", regionId);
            if (rootPayload.path("dataPath").asText("").isBlank()) {
                rootPayload.put("dataPath", "/recognized/regions/" + regionId.replaceAll("[^A-Za-z0-9_-]", "_"));
            }
            items.add(root);
            var region = regions.addObject();
            region.put("regionId", regionId);
            region.put("kind", kind);
            region.put("sheetId", locator.path("sheetId").asText(""));
            region.put("sheetName", locator.path("sheetName").asText(""));
            region.put("range", range);
            region.put("fieldName", root.path("fieldName").asText("重复记录区域"));
            region.put("status", "PENDING");
            region.put("canonicalStatus", "PROVISIONAL");
            region.put("structureStatus", "PROVISIONAL");
            var alternative = region.putArray("alternatives").addObject();
            alternative.put("alternativeId", root.path("id").asText(""));
            alternative.putArray("regions").addObject()
                    .put("suggestionId", root.path("suggestionIds").path(0).asText(""))
                    .put("kind", kind).put("range", range);
            region.set("fields", objectMapper.createArrayNode());
            region.set("auditSuggestions", objectMapper.createArrayNode());
        }
        for (var itemNode : items) {
            if (!"SCALAR".equals(itemNode.path("kind").asText("SCALAR"))) continue;
            var regionId = itemNode.path("payload").path("regionId").asText("");
            ObjectNode target = null;
            for (var regionNode : regions) {
                if (regionId.equals(regionNode.path("regionId").asText("")) && !regionId.isBlank()) {
                    target = (ObjectNode) regionNode;
                    break;
                }
            }
            if (target == null && !regions.isEmpty()) target = (ObjectNode) regions.get(0);
            if (target != null) target.withArray("fields").add(itemNode.deepCopy());
        }

        var summary = objectMapper.createObjectNode();
        summary.put("total", counts[0]);
        summary.put("confirmed", counts[1]);
        summary.put("pending", counts[2]);
        summary.put("lowConfidence", 0);
        summary.put("conflict", 0);
        summary.put("ignored", 0);
        summary.put("scalar", counts[3]);
        summary.put("rowTable", counts[4]);
        summary.put("columnTable", counts[5]);
        summary.put("regionCount", regions.size());
        summary.put("qualityIssueCount", 0);
        summary.put("autoFixedCount", 0);
        summary.put("blockingIssueCount", 0);
        review.set("summary", summary);

        var groupArray = objectMapper.createArrayNode();
        groups.forEach(groupArray::add);
        review.set("groups", groupArray);
        review.set("items", items);
        review.set("regions", regions);
        var statistics = review.putObject("statistics");
        statistics.put("regionCount", regions.size());
        statistics.put("structureAlternativeCount", regions.size());
        statistics.put("structureConflictGroups", 0);
        statistics.put("fieldCount", counts[0]);
        statistics.put("pendingFieldCount", counts[2]);
        statistics.put("auditSuggestionCount", 0);
        var coverage = review.putObject("recognitionCoverage");
        coverage.put("status", regions.isEmpty() ? "COMPLETE" : "REVIEW_REQUIRED");
        coverage.put("physicalRegionCount", regions.size());
        coverage.put("expectedRegionCount", regions.size());
        coverage.put("coveredRegionCount", regions.isEmpty() ? 0 : regions.size());
        coverage.put("unresolvedRegionCount", regions.size());
        coverage.put("coverageRatio", regions.isEmpty() ? 1d : 0d);
        review.set("qualityIssues", objectMapper.createArrayNode());

        result.set("recognitionReview", review);
        if (fieldModel != null && fieldModel.isObject()) result.set("fieldModel", fieldModel.deepCopy());
    }

    /** One review item, using the template center's field contract. */
    private ObjectNode item(JsonNode field, JsonNode source, String kind, String groupName,
                            String status, double confidence, String origin, String note,
                            Map<String, String> schemaNames) {
        var locator = field.path("locator");
        var item = objectMapper.createObjectNode();
        item.put("id", identifier(field, source));
        var suggestionIds = objectMapper.createArrayNode();
        suggestionIds.add(identifier(source, field));
        item.set("suggestionIds", suggestionIds);
        var fieldName = displayName(field, source, schemaNames);
        item.put("fieldName", fieldName);
        item.put("description", note);
        item.put("groupName", groupName);
        item.put("kind", normalizeKind(kind));
        item.put("valueType", firstNonBlank(field.path("valueType").asText(""),
                source.path("valueType").asText(""), "string"));
        item.put("sheetId", firstNonBlank(field.path("sheetId").asText(""),
                locator.path("sheetId").asText("")));
        item.put("sheetName", firstNonBlank(field.path("sheetName").asText(""),
                locator.path("sheetName").asText("")));
        item.put("labelAddress", firstNonBlank(locator.path("labelAddress").asText(""),
                locator.path("labelRange").asText(""), locator.path("label").path("address").asText("")));
        item.put("address", firstNonBlank(locator.path("logicalInputRange").asText(""),
                locator.path("valueRange").asText(""), locator.path("address").asText(""),
                locator.path("range").asText(""), locator.path("dataRange").asText("")));
        item.put("confidence", confidence);
        item.put("confidenceLevel", confidence >= 0.8d ? "HIGH" : confidence >= 0.65d ? "MEDIUM" : "LOW");
        item.put("source", origin.startsWith("TEMPLATE") ? "RULE" : origin);
        item.put("status", status);
        item.set("payload", payload(field, fieldName));
        return item;
    }

    /** The field contract the review payload must carry; template bindings satisfy it. */
    private ObjectNode payload(JsonNode field, String fieldName) {
        var payload = objectMapper.createObjectNode();
        payload.put("fieldCode", field.path("fieldCode").asText(""));
        for (var key : List.of("fieldId", "bindingId", "relationId", "regionId")) {
            if (field.path(key).isTextual()) payload.put(key, field.path(key).asText());
        }
        payload.put("fieldName", fieldName);
        payload.put("dataPath", field.path("dataPath").asText(""));
        payload.put("valueType", firstNonBlank(field.path("valueType").asText(""), "string"));
        payload.put("required", field.path("required").asBoolean(false));
        payload.put("role", firstNonBlank(field.path("role").asText(""),
                "SCALAR".equals(normalizeKind(field.path("kind").asText(""))) ? "FIELD" : "REPEAT_REGION"));
        payload.put("kind", normalizeKind(field.path("kind").asText(kindOf(field))));
        payload.put("locatorType", firstNonBlank(field.path("locatorType").asText(""), "CELL_RANGE"));
        if (field.path("locator").isObject()) payload.set("locator", field.path("locator").deepCopy());
        else payload.set("locator", objectMapper.createObjectNode());
        if (field.path("mappingKind").isTextual()) payload.put("mappingKind", field.path("mappingKind").asText());
        if (field.path("parentBindingId").isTextual()) {
            payload.put("parentBindingId", field.path("parentBindingId").asText());
        }
        return payload;
    }

    private List<JsonNode> bindings(JsonNode mapping) {
        var result = new ArrayList<JsonNode>();
        if (mapping == null || !mapping.isArray()) return result;
        mapping.forEach(result::add);
        return result;
    }

    /**
     * Runs the template center's rule engine over the uploaded file, so its
     * candidates show up in the same review the template import flow produces.  A
     * document without a parsed structure (an image) has nothing to recognise.
     */
    private List<JsonNode> engineSuggestions(String format, String fileName, JsonNode structureSummary) {
        var result = new ArrayList<JsonNode>();
        if (structureSummary == null || !structureSummary.isObject() || structureSummary.isEmpty()) return result;
        try {
            var batch = officeStructure.recognize(format, fileName == null ? "" : fileName, structureSummary);
            if (batch == null || batch.suggestions() == null) return result;
            result.addAll(batch.suggestions().stream()
                    .map(suggestion -> {
                        ObjectNode node = objectMapper.createObjectNode();
                        node.put("suggestionType", suggestion.suggestionType());
                        node.put("confidence", suggestion.confidence());
                        node.set("payload", suggestion.payload() == null
                                ? objectMapper.createObjectNode() : suggestion.payload());
                        node.set("evidence", suggestion.evidence() == null
                                ? objectMapper.createArrayNode() : suggestion.evidence());
                        return (JsonNode) node;
                    })
                    .toList());
        } catch (Exception exception) {
            // Recognition candidates are an addition to the template's fields; a
            // structure the engine cannot read must not fail the upload.
            return result;
        }
        return result;
    }

    private List<JsonNode> batchSuggestions(TemplateOfficeFacade.RecognitionBatch batch) {
        if (batch == null || batch.suggestions() == null) return List.of();
        return batch.suggestions().stream()
                .filter(item -> List.of("SCALAR_FIELD", "REPEAT_FIELD", "TABLE_CHILD_FIELD")
                        .contains(item.suggestionType()))
                .map(item -> {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("suggestionType", item.suggestionType());
                    node.put("confidence", item.confidence());
                    node.set("payload", item.payload() == null
                            ? objectMapper.createObjectNode() : item.payload());
                    node.set("evidence", item.evidence() == null
                            ? objectMapper.createArrayNode() : item.evidence());
                    return (JsonNode) node;
                }).toList();
    }

    private List<JsonNode> batchRegionSuggestions(TemplateOfficeFacade.RecognitionBatch batch) {
        if (batch == null || batch.suggestions() == null) return List.of();
        return batch.suggestions().stream()
                .filter(item -> item.payload() != null
                        && List.of("ROW_TABLE", "COLUMN_TABLE")
                                .contains(item.payload().path("kind").asText("")))
                .map(item -> {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("confidence", item.confidence());
                    node.set("payload", item.payload());
                    return (JsonNode) node;
                }).toList();
    }

    private String kindOf(JsonNode binding) {
        var mappingKind = binding.path("mappingKind").asText("");
        if ("REPEAT_REGION".equals(mappingKind)) return "ROW_TABLE";
        if ("REPEAT_FIELD".equals(mappingKind)) return "COLUMN_TABLE";
        var kind = binding.path("kind").asText("");
        return kind.isBlank() ? "SCALAR" : kind;
    }

    private String normalizeKind(String kind) {
        return switch (kind == null ? "" : kind.toUpperCase(java.util.Locale.ROOT)) {
            case "FORM_REGION" -> "FORM_REGION";
            case "ROW_TABLE" -> "ROW_TABLE";
            case "COLUMN_TABLE" -> "COLUMN_TABLE";
            default -> "SCALAR";
        };
    }

    /**
     * User-facing name of a reviewed field.
     *
     * <p>The template's own field model is the only authoritative source.  Its
     * entries are matched to a binding by {@code bindingId} / {@code fieldId} /
     * {@code relationId} / {@code id}, which is exactly how the template center
     * resolves the same names for its field tree.</p>
     *
     * <p>A binding's own {@code groupName} / {@code name} / {@code displayName} /
     * {@code diagnostic.*} attributes are deliberately not used for a leaf field:
     * the import contract reads them to name the <em>component</em> a binding
     * belongs to, so every field of one region would be labelled with the same
     * group name — which is how a photo review ended up with every row called
     * 「基础信息」.  Only a region or table root, which has no field label of its
     * own, falls back to that component name.</p>
     */
    private String displayName(JsonNode field, JsonNode fallback, Map<String, String> schemaNames) {
        for (var key : List.of("bindingId", "fieldId", "relationId", "id", "dataPath", "fieldCode")) {
            var name = schemaNames.get(field.path(key).asText(""));
            if (name != null && !name.isBlank()) return name;
        }
        var declared = firstNonBlank(field.path("fieldName").asText(""), fallback.path("fieldName").asText(""));
        if (!declared.isBlank()) return declared;
        if (isComponentRoot(field)) {
            var component = firstNonBlank(field.path("diagnostic").path("displayName").asText(""),
                    field.path("diagnostic").path("title").asText(""),
                    field.path("name").asText(""));
            if (!component.isBlank()) return component;
        }
        var fieldCode = field.path("fieldCode").asText("");
        return fieldCode.isBlank() ? "未命名字段" : fieldCode;
    }

    /** A region or table root: it names a structure, not a value, so its own name fits. */
    private boolean isComponentRoot(JsonNode binding) {
        if (!binding.path("parentBindingId").asText("").isBlank()) return false;
        var kind = firstNonBlank(binding.path("mappingKind").asText(""), binding.path("role").asText(""))
                .toUpperCase(java.util.Locale.ROOT);
        return kind.contains("REGION") || kind.contains("TABLE");
    }

    /**
     * Index of the field names the schema defines, keyed by every identifier a
     * binding may be matched on.  The template center's field model is the
     * authoritative source; {@code schema.fields} is only a fallback for schemas
     * that never published one.
     */
    private Map<String, String> schemaDisplayNames(JsonNode schema) {
        var names = new LinkedHashMap<String, String>();
        if (schema == null || !schema.isObject()) return names;
        collectNames(names, schema.path("x-jsd-field-model").path("fields"));
        collectNames(names, schema.path("fields"));
        return names;
    }

    private void collectNames(Map<String, String> names, JsonNode fields) {
        if (fields == null || !fields.isArray()) return;
        for (var field : fields) {
            var name = firstNonBlank(field.path("displayName").asText(""),
                    field.path("name").asText(""), field.path("fieldName").asText(""),
                    field.path("label").asText(""));
            if (name.isBlank()) continue;
            for (var key : List.of("bindingId", "fieldId", "relationId", "fieldCode", "dataPath", "id")) {
                var value = field.path(key).asText("");
                if (!value.isBlank()) names.putIfAbsent(value, name);
            }
        }
    }

    private String identifier(JsonNode primary, JsonNode fallback) {
        for (var node : List.of(primary, fallback)) {
            for (var key : List.of("bindingId", "fieldId", "relationId", "dataPath", "fieldCode")) {
                var value = node.path(key).asText("");
                if (!value.isBlank()) return value;
            }
        }
        return UUID.randomUUID().toString();
    }

    /** Current value of a field, keyed by the binding's JSON Pointer. */
    private String valueAt(JsonNode data, String dataPath) {
        if (data == null || !data.isObject() || dataPath.isBlank() || dataPath.contains("*")) return null;
        var node = data;
        for (var segment : dataPath.split("/")) {
            if (segment.isBlank()) continue;
            node = node.path(segment);
        }
        if (node.isMissingNode() || node.isNull()) return null;
        return node.isTextual() ? node.asText("") : node.asText("");
    }

    private String firstNonBlank(String... values) {
        for (var value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
}
