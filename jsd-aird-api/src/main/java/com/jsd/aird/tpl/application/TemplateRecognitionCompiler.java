package com.jsd.aird.tpl.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.port.TemplateImportRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Turns reviewed recognition results into the canonical JSON Schema + mapping pair.
 * The customer-facing UI edits the business field model stored in the schema extension;
 * it never needs to understand JSON Pointer or locator details.
 */
@Component
public class TemplateRecognitionCompiler {

    public static final String FIELD_MODEL_KEY = "x-jsd-field-model";
    private static final Set<String> STATIC_BLOCK_TYPES = Set.of(
            "DOCUMENT_HEADER", "INSTRUCTION_LIST", "NOTE_BLOCK", "LOOKUP_TABLE"
    );

    private final ObjectMapper objectMapper;

    public TemplateRecognitionCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CompiledRecognition compile(
            ObjectNode baseSchema,
            List<TemplateImportRepository.RecognitionSuggestionView> suggestions,
            TemplateFormat format
    ) {
        var mapping = objectMapper.createArrayNode();
        var fieldModel = objectMapper.createObjectNode();
        var groups = objectMapper.createArrayNode();
        var fields = objectMapper.createArrayNode();
        var blocks = objectMapper.createArrayNode();
        var semanticAnnotations = objectMapper.createArrayNode();
        var groupIds = new LinkedHashMap<String, String>();
        var seenPaths = new java.util.HashSet<String>();
        var seenLocations = new java.util.HashMap<String, String>();
        var seenStructuredRegions = new java.util.HashMap<String, String>();
        var independentChildParents = suggestions.stream()
                .map(TemplateImportRepository.RecognitionSuggestionView::payload)
                .filter(payload -> "CHILD".equals(payload.path("suggestionLevel").asText()))
                .map(payload -> payload.path("parentRelationId").asText(""))
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toSet());

        suggestions.stream().filter(suggestion -> "SEMANTIC_MODEL".equals(suggestion.suggestionType()))
                .findFirst().ifPresent(suggestion -> {
                    suggestion.payload().path("businessBlocks").forEach(blocks::add);
                    suggestion.payload().path("semanticAnnotations").forEach(semanticAnnotations::add);
                });
        var staticRegions = staticRegions(semanticAnnotations, blocks);

        suggestions.stream()
                // Recognition output is a candidate overlay. Only an explicitly accepted
                // candidate is allowed to become the canonical FieldModel/Schema/Mapping.
                // PENDING items remain available through recognition-review for customer review.
                .filter(suggestion -> "ACCEPTED".equals(suggestion.decision()))
                .filter(suggestion -> !"SEMANTIC_MODEL".equals(suggestion.suggestionType()))
                 .filter(suggestion -> !isProtocolRejected(suggestion.payload()))
                 .filter(suggestion -> !isStaticSuggestion(suggestion, staticRegions))
                 .filter(this::isFormalSuggestion)
                 .filter(suggestion -> parentStructureResolved(suggestion, suggestions))
                .sorted(java.util.Comparator
                        .comparing((TemplateImportRepository.RecognitionSuggestionView item) ->
                                item.payload().path("locator").path("sheetId").asText(""))
                        .thenComparing(item -> item.payload().path("locator").path("address").asText(""))
                        .thenComparing(TemplateImportRepository.RecognitionSuggestionView::createdAt))
                .forEach(suggestion -> {
                    var payload = suggestion.payload();
                    var relationId = payload.path("relationId").asText("");
                    var locator = payload.path("locator");
                    var sheet = locator.path("sheetId").asText(locator.path("sheetName").asText(""));
                    var address = locator.path("address").asText(locator.path("range").asText(""));
                    if (relationId.isBlank()) {
                        relationId = RecognitionIdentity.relationId(
                                sheet, locator.path("labelRange").asText(locator.path("labelAddress").asText("")),
                                address, payload.path("kind").asText(payload.path("role").asText("FIELD"))
                        );
                    }
                    var dataPath = payload.path("dataPath").asText("");
                    if (dataPath.isBlank()) dataPath = "/recognized/field_"
                            + RecognitionIdentity.shortHash(relationId, 12);
                    var location = sheet + "|" + address;
                    var kind = recognitionKind(suggestion.suggestionType(), payload);
                    var regionId = payload.path("regionId").asText("");
                    var duplicateStructuredRegion = !"SCALAR".equals(kind) && StringUtils.hasText(regionId)
                            && seenStructuredRegions.containsKey(regionId);
                    if (!StringUtils.hasText(dataPath)
                             || duplicateStructuredRegion
                             || (StringUtils.hasText(sheet) && StringUtils.hasText(address)
                             && seenLocations.containsKey(location)
                             && !suggestion.source().equals(seenLocations.get(location)))) {
                        return;
                    }
                    dataPath = uniqueDataPath(dataPath, relationId, seenPaths);
                    if (StringUtils.hasText(sheet) && StringUtils.hasText(address)) {
                        seenLocations.putIfAbsent(location, suggestion.source());
                    }
                    if (!"SCALAR".equals(kind) && StringUtils.hasText(regionId)) {
                        seenStructuredRegions.putIfAbsent(regionId, suggestion.source());
                    }
                    var fieldName = payload.path("fieldName").asText("业务字段");
                    var groupName = payload.path("groupName").asText("").strip();
                    var fallbackGroup = GroupNameNormalizer.infer(
                            payload.path("blockType").asText(""),
                            payload.path("blockName").asText(fieldName));
                    var normalizedGroupName = "CUSTOMER".equalsIgnoreCase(suggestion.source())
                            ? GroupNameNormalizer.normalizeCustomerDefined(groupName)
                            : GroupNameNormalizer.normalizeModelSuggestion(groupName).orElse(fallbackGroup);
                    var groupId = groupIds.computeIfAbsent(normalizedGroupName, ignored ->
                            "group-" + GroupNameNormalizer.code(normalizedGroupName).toLowerCase(Locale.ROOT)
                                    + "-" + RecognitionIdentity.shortHash(normalizedGroupName, 8));
                    var fieldId = payload.hasNonNull("fieldId")
                            ? parseUuid(payload.path("fieldId").asText(), relationId)
                            : RecognitionIdentity.fieldId(relationId);
                    var locatorType = payload.path("locatorType").asText("CELL_RANGE");
                    var bindingId = payload.path("bindingId").asText("");
                    if (!StringUtils.hasText(bindingId)) {
                        bindingId = RecognitionIdentity.bindingId(
                                fieldId, locatorType, sheet + "|" + address
                        ).toString();
                    }
                    var validBinding = validBinding(payload);
                    var field = objectMapper.createObjectNode()
                            .put("id", fieldId.toString())
                            .put("fieldId", fieldId.toString())
                            .put("relationId", relationId)
                            .put("dataPath", dataPath)
                             .put("fieldCode", effectiveFieldCode(payload, "AUTO.FIELD"))
                             .put("groupId", groupId)
                            .put("name", fieldName)
                            .put("kind", kind)
                            .put("valueType", payload.path("valueType").asText("string"))
                            .put("required", payload.path("required").asBoolean(false))
                            .put("unit", payload.path("unit").asText(""))
                            .put("description", payload.path("reason").asText(""))
                            .put("interpretation", businessInterpretation(kind, fieldName, payload))
                            .put("confidence", suggestion.confidence())
                            .put("recognitionItemId", suggestion.id().toString())
                            .put("editability", payload.path("editability").asText("UNKNOWN"))
                            .put("valueSource", payload.path("valueSource").asText("UNKNOWN"))
                             .put("condition", payload.path("condition").asText(""))
                             .put("blockId", payload.path("blockId").asText(""))
                             .put("parentBlockId", payload.path("parentBlockId").asText(""))
                             .put("mappingKind", effectiveMappingKind(payload, kind))
                             .put("repeatAxis", payload.path("repeatAxis").asText(""))
                             .put("recordHeight", payload.path("recordHeight").asInt(1))
                             .put("recordWidth", payload.path("recordWidth").asInt(1))
                             .put("recordStride", payload.path("recordStride").asInt(1))
                             .put("semanticConflict", payload.path("semanticConflict").asBoolean(false))
                             .put("conflictCode", payload.path("conflictCode").asText(""))
                             .put("conflictMessage", payload.path("conflictMessage").asText(""))
                              .put("standardMatchStatus", payload.path("standardMatchStatus").asText("UNMATCHED"))
                              .put("dictionaryVersion", payload.path("dictionaryVersion").asInt(StandardFieldDictionary.VERSION))
                              .put("standardRequired", payload.path("standardRequired").asBoolean(false))
                              .put("requiresStandardConfirmation", payload.path("requiresStandardConfirmation").asBoolean(false))
                              .put("runtimeInputOnly", payload.path("runtimeInputOnly").asBoolean(false))
                              .put("templateStatus", payload.path("templateStatus").asText(""))
                                .put("publishable", payload.path("publishable").asBoolean(false))
                              .put("reviewStatus", validBinding && "ACCEPTED".equals(suggestion.decision())
                                     && !"DOCX_CONTENT_CONTROL".equals(payload.path("source").asText())
                                     ? "CONFIRMED"
                                     : "NEEDS_CONFIRMATION");
                    if (StringUtils.hasText(payload.path("markerId").asText())) {
                        field.put("markerId", payload.path("markerId").asText());
                    }
                    if (payload.path("locator").isObject()) field.set("locator", payload.path("locator").deepCopy());
                     copyStandardMetadata(field, payload);
                    if (StringUtils.hasText(payload.path("parentFieldId").asText())) {
                        field.put("parentFieldId", payload.path("parentFieldId").asText());
                    }
                    if (StringUtils.hasText(payload.path("parentSuggestionId").asText())) {
                        field.put("parentSuggestionId", payload.path("parentSuggestionId").asText());
                    } else if (StringUtils.hasText(payload.path("parentRelationId").asText())) {
                        field.put("parentSuggestionId", payload.path("parentRelationId").asText());
                    }
                    if (validBinding) field.put("bindingId", bindingId);
                    if (payload.path("columns").isArray()) {
                        field.set("columns", payload.path("columns").deepCopy());
                    }
                    if (payload.path("tableModel").isObject()) {
                        field.set("tableModel", payload.path("tableModel").deepCopy());
                    }
                    if (payload.path("matrixModel").isObject()) {
                        field.set("matrixModel", payload.path("matrixModel").deepCopy());
                    }
                    if (payload.path("longTableModel").isObject()) {
                        field.set("longTableModel", payload.path("longTableModel").deepCopy());
                    }
                    if (payload.path("recordProjection").isObject()) {
                        field.set("recordProjection", payload.path("recordProjection").deepCopy());
                    }
                    if (payload.path("columnSlots").isArray()) {
                        field.set("columnSlots", payload.path("columnSlots").deepCopy());
                    }
                    fields.add(field);
                    if (("ROW_TABLE".equals(kind) || "COLUMN_TABLE".equals(kind))
                            && payload.path("columns").isArray()
                            && !independentChildParents.contains(relationId)) {
                        appendTableChildFields(fields, payload, fieldId.toString(), groupId,
                                suggestion, validBinding, mapping);
                    }

                    applySchema(baseSchema, payload, kind);
                    if ((format == TemplateFormat.XLSX || format == TemplateFormat.DOCX) && validBinding) {
                        mapping.add(toBinding(bindingId, fieldId.toString(), relationId,
                                suggestion.id(), payload, kind));
                    }
                });

        groupIds.forEach((name, id) -> groups.add(objectMapper.createObjectNode()
                .put("id", id)
                .put("name", name)
                .put("groupCode", GroupNameNormalizer.code(name))
                .put("order", groups.size())));
        if (groups.isEmpty()) {
            groups.add(objectMapper.createObjectNode()
                    .put("id", "group-basic")
                    .put("name", GroupNameNormalizer.BASIC_INFORMATION)
                    .put("groupCode", "BASIC_INFORMATION")
                    .put("order", 0));
        }
        fieldModel.set("groups", groups);
        fieldModel.set("fields", fields);
        fieldModel.set("blocks", blocks);
        fieldModel.set("semanticAnnotations", semanticAnnotations);
        fieldModel.set("staticRegions", staticRegions);
        fieldModel.put("modelVersion", 4);
        baseSchema.set(FIELD_MODEL_KEY, fieldModel);
        return new CompiledRecognition(baseSchema, mapping, fieldModel);
    }

    private ObjectNode toBinding(
            String bindingId, String fieldId, String relationId,
            java.util.UUID recognitionItemId, JsonNode payload, String kind
    ) {
        var binding = objectMapper.createObjectNode();
        binding.put("bindingId", bindingId);
        binding.put("fieldId", fieldId);
        binding.put("relationId", relationId);
        if (StringUtils.hasText(payload.path("markerId").asText())) {
            binding.put("markerId", payload.path("markerId").asText());
        } else if (StringUtils.hasText(payload.path("locator").path("markerId").asText())) {
            binding.put("markerId", payload.path("locator").path("markerId").asText());
        }
        binding.put("fieldCode", payload.path("fieldCode").asText("AUTO.FIELD"));
        binding.put("fieldName", payload.path("fieldName").asText("业务字段"));
        binding.put("dataPath", payload.path("dataPath").asText());
        binding.put("role", "SCALAR".equals(kind) ? "FIELD" : "REPEAT_REGION");
        binding.put("mappingKind", effectiveMappingKind(payload, kind));
        var componentId = firstText(payload, "componentId", "regionId", "blockId", "parentBlockId");
        if (StringUtils.hasText(componentId)) binding.put("componentId", componentId);
        copyIfPresent(payload, binding, "labelPath", "required", "identity", "trainingRole",
                "trainingEligible", "ragEligible", "valueSource", "valueType", "unit");
        if (StringUtils.hasText(payload.path("parentBindingId").asText())) {
            binding.put("parentBindingId", payload.path("parentBindingId").asText());
        }
        if (StringUtils.hasText(payload.path("repeatAxis").asText())) {
            binding.put("repeatAxis", payload.path("repeatAxis").asText());
        }
        binding.put("recordHeight", payload.path("recordHeight").asInt(1));
        binding.put("recordWidth", payload.path("recordWidth").asInt(1));
        binding.put("recordStride", payload.path("recordStride").asInt(1));
        if (payload.path("terminationRule").isObject()) {
            binding.set("termination", payload.path("terminationRule").deepCopy());
        }
        binding.put("locatorType", payload.path("locatorType").asText("CELL_RANGE"));
        binding.set("locator", payload.path("locator").deepCopy());
        binding.put("syncDirection", syncDirection(payload));
        binding.put("primaryBinding", true);
        binding.put("bindingStatus", "VALID");
        binding.set("diagnostic", objectMapper.createObjectNode()
                .put("source", "AUTO_RECOGNIZED")
                .put("recognitionItemId", recognitionItemId.toString())
                .put("kind", kind)
                .put("groupName", payload.path("groupName").asText(""))
                .put("editability", payload.path("editability").asText("UNKNOWN"))
                .put("valueSource", payload.path("valueSource").asText("UNKNOWN"))
                .put("semanticConflict", payload.path("semanticConflict").asBoolean(false))
                .put("conflictCode", payload.path("conflictCode").asText(""))
                .put("conflictMessage", payload.path("conflictMessage").asText(""))
                .put("standardMatchStatus", payload.path("standardMatchStatus").asText("UNMATCHED"))
                .put("dictionaryVersion", payload.path("dictionaryVersion").asInt(StandardFieldDictionary.VERSION))
                .put("condition", payload.path("condition").asText(""))
                .put("blockId", payload.path("blockId").asText("")));
        if (payload.path("matrixModel").isObject()) {
            binding.withObject("diagnostic").set("matrixModel", payload.path("matrixModel").deepCopy());
        }
        if (payload.path("longTableModel").isObject()) {
            binding.withObject("diagnostic").set("longTableModel", payload.path("longTableModel").deepCopy());
        }
        if (payload.path("tableModel").isObject()) {
            binding.withObject("diagnostic").set("tableModel", payload.path("tableModel").deepCopy());
        }
        return binding;
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String... keys) {
        for (var key : keys) {
            if (source.has(key) && !source.path(key).isNull()) target.set(key, source.path(key).deepCopy());
        }
    }

    private String firstText(JsonNode source, String... keys) {
        for (var key : keys) {
            var value = source.path(key).asText("");
            if (StringUtils.hasText(value)) return value;
        }
        return "";
    }

    private boolean validBinding(JsonNode payload) {
        var editability = payload.path("editability").asText("UNKNOWN");
        var valueSource = payload.path("valueSource").asText("UNKNOWN");
        var locatorType = payload.path("locatorType").asText(
                payload.path("locator").path("locatorType").asText(""));
        if (locatorType.startsWith("DOCX") || payload.has("markerId")) {
            var markerId = payload.path("markerId").asText(
                    payload.path("locator").path("markerId").asText(""));
            if (!StringUtils.hasText(markerId)) return false;
        }
        return !"UNKNOWN".equals(editability) && !"UNKNOWN".equals(valueSource)
                && !payload.path("semanticConflict").asBoolean(false)
                && !("EDITABLE".equals(editability) && "FORMULA".equals(valueSource));
    }

    private boolean isFormalSuggestion(TemplateImportRepository.RecognitionSuggestionView suggestion) {
        var payload = suggestion.payload();
        // FORM_REGION is a review/containment node. Its accepted scalar
        // children are the canonical fields; compiling the container itself
        // used to expose a fake ROW_TABLE named "基本信息区域".
        if ("FORM_REGION".equals(payload.path("kind").asText(""))) return false;
        // An explicit user confirmation is the boundary that turns a
        // reviewRequired physical field into a canonical field. Requiring the
        // pre-click policy again here silently discarded every confirmed form
        // and table child during template creation.
        var explicitlyConfirmedField = "ACCEPTED".equals(suggestion.decision())
                && RecognitionCandidatePolicy.isOneClickFieldConfirmable(payload);
        return (RecognitionCandidatePolicy.isFormalCandidate(payload) || explicitlyConfirmedField)
                 && !STATIC_BLOCK_TYPES.contains(payload.path("blockType").asText());
    }

    private boolean parentStructureResolved(
            TemplateImportRepository.RecognitionSuggestionView suggestion,
            List<TemplateImportRepository.RecognitionSuggestionView> allSuggestions
    ) {
        var payload = suggestion.payload();
        if (!"CHILD".equals(payload.path("suggestionLevel").asText(""))) return true;
        var parentRelationId = payload.path("parentRelationId").asText("");
        if (parentRelationId.isBlank()) return false;
        return allSuggestions.stream().anyMatch(parent ->
                "ACCEPTED".equals(parent.decision())
                        && parentRelationId.equals(parent.payload().path("relationId").asText(""))
                        && (RecognitionCandidatePolicy.isFormallyConfirmable(parent.payload())
                        || (RecognitionCandidatePolicy.isStructural(parent.payload())
                        && !parent.payload().path("candidateOnly").asBoolean(false)
                        && !parent.payload().path("physicalStructureOnly").asBoolean(false)
                        && !parent.payload().path("structureConflict").asBoolean(false)
                        && "CONFIRMED".equals(parent.payload().path("canonicalStatus").asText())
                        && "CONFIRMED".equals(parent.payload().path("structureStatus").asText()))));
    }

    private boolean isProtocolRejected(JsonNode payload) {
        return RecognitionCandidatePolicy.isProtocolRejected(payload);
    }

    private String effectiveMappingKind(JsonNode payload, String kind) {
        var explicit = payload.path("mappingKind").asText("");
        if (Set.of("SCALAR", "REPEAT_REGION", "REPEAT_FIELD", "MATRIX_REGION", "MATRIX_FIELD")
                .contains(explicit)) return explicit;
        return payload.path("suggestionLevel").asText("").equals("CHILD")
                ? ("MATRIX".equals(kind) ? "MATRIX_FIELD" : "REPEAT_FIELD")
                : ("MATRIX".equals(kind) ? "MATRIX_REGION"
                : "SCALAR".equals(kind) ? "SCALAR" : "REPEAT_REGION");
    }

    private boolean isStaticSuggestion(
            TemplateImportRepository.RecognitionSuggestionView suggestion,
            ArrayNode staticRegions
    ) {
        var locator = suggestion.payload().path("locator");
        var sheetId = locator.path("sheetId").asText("");
        var address = locator.path("address").asText(locator.path("range").asText(""));
        var candidate = cellRange(address);
        if (sheetId.isBlank() || candidate == null) return false;
        for (var region : staticRegions) {
            if (!sheetId.equals(region.path("sheetId").asText(""))) continue;
            var fixed = cellRange(region.path("address").asText(region.path("range").asText("")));
            if (fixed != null && overlaps(candidate, fixed)) return true;
        }
        return false;
    }

    private boolean overlaps(int[] left, int[] right) {
        return left[0] <= right[2] && right[0] <= left[2]
                && left[1] <= right[3] && right[1] <= left[3];
    }

    private int[] cellRange(String address) {
        if (address == null || address.isBlank()) return null;
        var parts = address.replace("$", "").replace(" ", "")
                .toUpperCase(Locale.ROOT).split(":", 2);
        var first = cell(parts[0]);
        var last = cell(parts.length == 1 ? parts[0] : parts[1]);
        if (first == null || last == null) return null;
        return new int[]{Math.min(first[0], last[0]), Math.min(first[1], last[1]),
                Math.max(first[0], last[0]), Math.max(first[1], last[1])};
    }

    private int[] cell(String address) {
        var match = java.util.regex.Pattern.compile("^([A-Z]+)([1-9][0-9]*)$")
                .matcher(address == null ? "" : address);
        if (!match.matches()) return null;
        var column = 0;
        for (var letter : match.group(1).toCharArray()) {
            column = column * 26 + letter - 'A' + 1;
        }
        return new int[]{column, Integer.parseInt(match.group(2))};
    }

    private String syncDirection(JsonNode payload) {
        var editability = payload.path("editability").asText("UNKNOWN");
        var valueSource = payload.path("valueSource").asText("UNKNOWN");
        if ("CONDITIONAL".equals(editability)) return "TWO_WAY";
        if ("EDITABLE".equals(editability) && "USER_INPUT".equals(valueSource)) return "TWO_WAY";
        if ("READ_ONLY".equals(editability)
                || "FORMULA".equals(valueSource) || "STATIC".equals(valueSource)) {
            return "EDITOR_TO_DATA";
        }
        if ("REFERENCE".equals(valueSource)) {
            return "EDITABLE".equals(editability) ? "TWO_WAY" : "EDITOR_TO_DATA";
        }
        if ("MIXED".equals(valueSource)) {
            return "EDITABLE".equals(editability) ? "TWO_WAY" : "EDITOR_TO_DATA";
        }
        throw new IllegalArgumentException("无法为未知字段生成同步方向");
    }

    private void applySchema(ObjectNode root, JsonNode payload, String kind) {
        var segments = payload.path("dataPath").asText("").split("/");
        ObjectNode current = root;
        for (int index = 1; index < segments.length; index++) {
            var segment = unescape(segments[index]);
            if (segment.isBlank()) {
                continue;
            }
            var properties = current.withObject("properties");
            var last = index == segments.length - 1;
            if ("*".equals(segment)) {
                var items = current.path("items");
                if (!items.isObject()) {
                    items = objectMapper.createObjectNode()
                            .put("type", "object")
                            .set("properties", objectMapper.createObjectNode());
                    current.set("items", items);
                }
                current = (ObjectNode) items;
                continue;
            }
            if (last) {
                properties.set(segment, schemaFor(payload, kind));
                if (payload.path("required").asBoolean(false)) {
                    var required = current.withArray("required");
                    var exists = false;
                    for (JsonNode item : required) {
                        exists |= segment.equals(item.asText());
                    }
                    if (!exists) {
                        required.add(segment);
                    }
                }
            } else {
                var child = properties.path(segment);
                if (!child.isObject()) {
                    child = objectMapper.createObjectNode()
                            .put("type", "object")
                            .set("properties", objectMapper.createObjectNode());
                    properties.set(segment, child);
                }
                current = (ObjectNode) child;
            }
        }
    }

    private ObjectNode schemaFor(JsonNode payload, String kind) {
        var schema = objectMapper.createObjectNode()
                .put("title", payload.path("fieldName").asText("业务字段"))
                .put("x-field-code", payload.path("fieldCode").asText("AUTO.FIELD"))
                .put("x-region-kind", kind);
        if ("ROW_TABLE".equals(kind) || "COLUMN_TABLE".equals(kind)) {
            schema.put("type", "array");
            var item = objectMapper.createObjectNode().put("type", "object");
            var properties = objectMapper.createObjectNode();
            var columns = payload.path("columns");
            if (columns.isArray()) {
                var number = 1;
                for (JsonNode column : columns) {
                    var code = column.path("code").asText("column" + number++);
                    var property = objectMapper.createObjectNode()
                            .put("type", normalizeType(column.path("valueType").asText("string")))
                            .put("title", column.path("name").asText(code))
                             .put("x-field-code", effectiveFieldCode(column, "TABLE.COLUMN." + code))
                            .put("x-data-path", column.path("dataPath").asText(""));
                    properties.set(code, property);
                }
            }
            item.set("properties", properties);
            schema.set("items", item);
        } else if ("MATRIX".equals(kind)) {
            schema.put("type", "array");
            if ("RECORD_SET".equals(payload.path("matrixModel").path("semanticMode").asText())) {
                schema.set("items", objectMapper.createObjectNode()
                        .put("type", "object")
                        .put("additionalProperties", true));
            } else {
                schema.set("items", objectMapper.createObjectNode()
                        .put("type", "array")
                        .set("items", objectMapper.createObjectNode()
                                .put("type", normalizeType(payload.path("valueType").asText("number")))));
            }
            if (payload.path("matrixModel").isObject()) {
                schema.set("x-semantic-model", payload.path("matrixModel").deepCopy());
            }
            if (payload.path("longTableModel").isObject()) {
                schema.set("x-long-table-model", payload.path("longTableModel").deepCopy());
            }
        } else {
            var valueType = payload.path("valueType").asText("string");
            schema.put("type", normalizeType(valueType));
            if ("date".equals(valueType)) {
                schema.put("format", "date");
            } else if ("datetime".equals(valueType)) {
                schema.put("format", "date-time");
            } else if ("time".equals(valueType)) {
                schema.put("format", "time");
            } else if ("duration".equals(valueType)) {
                schema.put("format", "duration");
            }
        }
        if (StringUtils.hasText(payload.path("unit").asText())) {
            schema.put("x-unit", payload.path("unit").asText());
        }
        return schema;
    }

    private void appendTableChildFields(
            ArrayNode fields,
            JsonNode payload,
            String parentFieldId,
            String groupId,
            TemplateImportRepository.RecognitionSuggestionView suggestion,
            boolean parentBindingValid,
            ArrayNode mapping
    ) {
        for (var column : payload.path("columns")) {
            var code = column.path("code").asText("column");
            var childRelation = payload.path("relationId").asText() + "|child|" + code + "|"
                    + RecognitionIdentity.normalizeRange(column.path("valueRange").asText(""));
            var childId = RecognitionIdentity.fieldId(childRelation).toString();
            var child = objectMapper.createObjectNode()
                    .put("id", childId)
                    .put("fieldId", childId)
                    .put("parentFieldId", parentFieldId)
                    .put("relationId", childRelation)
                     .put("fieldCode", effectiveFieldCode(column, "TABLE.COLUMN." + code))
                    .put("dataPath", column.path("dataPath").asText(""))
                    .put("groupId", groupId)
                    .put("name", column.path("name").asText(code))
                    .put("kind", "SCALAR")
                    .put("valueType", column.path("valueType").asText("string"))
                    .put("required", column.path("required").asBoolean(false))
                    .put("unit", column.path("unit").asText(""))
                    .put("description", "明细表列字段")
                    .put("interpretation", "系统认为这是“" + column.path("name").asText(code) + "”明细列。")
                    .put("confidence", suggestion.confidence())
                    .put("recognitionItemId", suggestion.id().toString())
                    .put("editability", column.path("editability").asText("UNKNOWN"))
                    .put("valueSource", column.path("valueSource").asText("UNKNOWN"))
                    .put("reviewStatus", parentBindingValid && "ACCEPTED".equals(suggestion.decision())
                            ? "CONFIRMED" : "NEEDS_CONFIRMATION")
                    .put("mappingKind", "REPEAT_FIELD")
                    .put("repeatAxis", payload.path("repeatAxis").asText("ROW"))
                    .put("recordHeight", payload.path("recordHeight").asInt(1))
                    .put("recordWidth", payload.path("recordWidth").asInt(1))
                    .put("recordStride", payload.path("recordStride").asInt(1))
                    .put("semanticConflict", column.path("semanticConflict").asBoolean(false))
                    .put("conflictCode", column.path("conflictCode").asText(""))
                     .put("conflictMessage", column.path("conflictMessage").asText(""))
                     .put("standardMatchStatus", column.path("standardMatchStatus").asText("UNMATCHED"))
                     .put("dictionaryVersion", column.path("dictionaryVersion").asInt(StandardFieldDictionary.VERSION))
                     .put("standardRequired", column.path("standardRequired").asBoolean(false))
                     .put("requiresStandardConfirmation", column.path("requiresStandardConfirmation").asBoolean(false))
                    .put("labelRange", column.path("labelRange").asText(""))
                     .put("valueRange", column.path("valueRange").asText(""))
                     .put("dataStartRow", column.path("dataStartRow").asInt(0));
             copyStandardMetadata(child, column);
            var locator = objectMapper.createObjectNode()
                    .put("sheetId", payload.path("locator").path("sheetId").asText(""))
                    .put("sheetName", payload.path("locator").path("sheetName").asText(""))
                     .put("labelAddress", column.path("labelRange").asText(""))
                     .put("labelRange", column.path("labelRange").asText(""))
                     .put("address", column.path("valueRange").asText(""))
                     .put("anchorAddress", firstCell(column.path("valueRange").asText("")))
                     .put("anchorRange", firstColumnRange(column.path("valueRange").asText("")))
                     .put("logicalInputRange", column.path("valueRange").asText(""))
                    .put("valueMode", "ROW".equals(payload.path("repeatAxis").asText("ROW"))
                            ? "ARRAY_COLUMN" : "ARRAY_ROW");
            child.set("locator", locator);
            var childBindingValid = parentBindingValid && validBinding(column);
            var childBindingId = RecognitionIdentity.bindingId(
                    RecognitionIdentity.fieldId(childRelation), "CELL_RANGE",
                    locator.path("sheetId").asText("") + "|" + locator.path("address").asText("")
            ).toString();
            if (childBindingValid) {
                child.put("bindingId", childBindingId);
                mapping.add(toBinding(childBindingId, childId, childRelation, suggestion.id(),
                        childPayloadForMapping(child, payload), "SCALAR"));
            }
            fields.add(child);
        }
    }

    private ObjectNode childPayloadForMapping(JsonNode child, JsonNode parent) {
        var payload = child.deepCopy();
        if (!(payload instanceof ObjectNode object)) return objectMapper.createObjectNode();
        object.put("parentBindingId", parent.path("bindingId").asText(""));
        object.put("parentFieldId", parent.path("fieldId").asText(""));
        object.put("suggestionLevel", "CHILD");
        return object;
    }

    private void copyStandardMetadata(ObjectNode target, JsonNode source) {
        target.put("fieldCode", effectiveFieldCode(source, target.path("fieldCode").asText("AUTO.FIELD")));
        if (source.hasNonNull("standardFieldId") && !source.path("standardFieldId").asText().isBlank()) {
            target.put("standardFieldId", source.path("standardFieldId").asText());
        }
        if (source.has("standardFieldVersion")) target.put("standardFieldVersion", source.path("standardFieldVersion").asInt());
        if (source.has("standardFieldName")) target.put("standardFieldName", source.path("standardFieldName").asText());
        if (source.has("fieldOrigin") && !source.path("fieldOrigin").asText().isBlank()) {
            target.put("fieldOrigin", source.path("fieldOrigin").asText());
        }
        if (source.has("standardSelectionStatus") && !source.path("standardSelectionStatus").asText().isBlank()) {
            target.put("standardSelectionStatus", source.path("standardSelectionStatus").asText());
        }
        if (source.has("uiType")) target.put("uiType", source.path("uiType").asText("TEXT"));
    }

    private String effectiveFieldCode(JsonNode source, String fallback) {
        var fieldCode = source.path("fieldCode").asText(fallback);
        if (!fieldCode.startsWith("TABLE.COLUMN.")) return fieldCode;
        var standardName = source.path("standardFieldName").asText("");
        return StandardFieldDictionary.match(standardName)
                .map(StandardFieldDictionary.Entry::fieldCode)
                .orElse(fieldCode);
    }

    private String uniqueDataPath(String basePath, String relationId, Set<String> usedPaths) {
        if (!StringUtils.hasText(basePath)) return basePath;
        var candidate = basePath;
        if (usedPaths.contains(candidate)) {
            var suffix = RecognitionIdentity.shortHash(relationId + "|" + basePath, 10);
            candidate = basePath + "__" + suffix;
            var ordinal = 2;
            while (usedPaths.contains(candidate)) candidate = basePath + "__" + suffix + "_" + ordinal++;
        }
        usedPaths.add(candidate);
        return candidate;
    }

    private String firstCell(String range) {
        return range == null || range.isBlank() ? "" : range.split(":", 2)[0].toUpperCase(Locale.ROOT);
    }

    private String firstColumnRange(String range) {
        var value = range == null ? "" : range.toUpperCase(Locale.ROOT);
        var first = firstCell(value);
        if (first.isBlank()) return "";
        var last = value.contains(":") ? value.substring(value.indexOf(':') + 1) : first;
        var rowStart = first.replaceAll("^[A-Z]+", "");
        var column = first.replaceAll("[0-9]+$", "");
        var rowEnd = last.replaceAll("^[A-Z]+", "");
        return column + rowStart + ":" + column + rowEnd;
    }

    private String normalizeType(String valueType) {
        return switch (valueType.toLowerCase(Locale.ROOT)) {
            case "number", "integer", "boolean", "array", "object" -> valueType.toLowerCase(Locale.ROOT);
            case "date", "datetime", "time", "duration" -> "string";
            default -> "string";
        };
    }

    private ArrayNode staticRegions(ArrayNode annotations, ArrayNode blocks) {
        var regions = objectMapper.createArrayNode();
        var seen = new java.util.HashSet<String>();
        for (JsonNode annotation : annotations) {
            var regionType = staticRegionType(annotation.path("role").asText(""));
            var address = annotation.path("range").asText("");
            if (regionType == null || address.isBlank()) continue;
            var key = annotation.path("sheetId").asText("") + "|" + address;
            if (!seen.add(key)) continue;
            regions.add(objectMapper.createObjectNode()
                    .put("id", "baseline-" + RecognitionIdentity.shortHash(key, 16))
                    .put("sheetId", annotation.path("sheetId").asText(""))
                    .put("address", address)
                    .put("regionType", regionType)
                    .put("displayName", staticRegionName(regionType))
                    .put("source", "TEMPLATE_BASELINE")
                    .put("locked", true));
        }
        for (JsonNode block : blocks) {
            var regionType = staticRegionType(block.path("type").asText(""));
            var address = block.path("range").asText("");
            if (regionType == null || address.isBlank()) continue;
            var key = block.path("sheetId").asText("") + "|" + address;
            if (!seen.add(key)) continue;
            regions.add(objectMapper.createObjectNode()
                    .put("id", "baseline-" + RecognitionIdentity.shortHash(key, 16))
                    .put("sheetId", block.path("sheetId").asText(""))
                    .put("address", address)
                    .put("regionType", regionType)
                    .put("displayName", block.path("businessName").asText(staticRegionName(regionType)))
                    .put("source", "TEMPLATE_BASELINE")
                    .put("locked", true));
        }
        return regions;
    }

    private String staticRegionType(String role) {
        return switch (role.toUpperCase(Locale.ROOT)) {
            case "STATIC_REFERENCE", "DOCUMENT_HEADER", "LOOKUP_TABLE" -> "STATIC_REFERENCE";
            case "INSTRUCTION", "INSTRUCTION_LIST" -> "INSTRUCTION";
            case "NOTE", "NOTE_BLOCK" -> "NOTE";
            default -> null;
        };
    }

    private String staticRegionName(String type) {
        return switch (type) {
            case "INSTRUCTION" -> "填写说明";
            case "NOTE" -> "备注说明";
            default -> "固定引用内容";
        };
    }

    private java.util.UUID parseUuid(String value, String fallbackRelationId) {
        try {
            return java.util.UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return RecognitionIdentity.fieldId(fallbackRelationId);
        }
    }

    private String recognitionKind(String suggestionType, JsonNode payload) {
        var declared = payload.path("kind").asText("").toUpperCase(Locale.ROOT);
        var type = suggestionType == null ? "" : suggestionType.toUpperCase(Locale.ROOT);
        if ("MATRIX".equals(declared) || type.contains("MATRIX")) {
            return "MATRIX";
        }
        if ("COLUMN_TABLE".equals(declared)) {
            return "COLUMN_TABLE";
        }
        // TABLE_CHILD_FIELD describes where the suggestion came from, not the
        // business kind of the child. An explicit SCALAR child must stay a
        // scalar; otherwise it is mistaken for a second table region and is
        // removed by component de-duplication.
        if ("SCALAR".equals(declared)) {
            return "SCALAR";
        }
        if ("ROW_TABLE".equals(declared) || type.contains("TABLE")
                || "REPEAT_REGION".equals(payload.path("role").asText())) {
            return "ROW_TABLE";
        }
        return "SCALAR";
    }

    private String businessInterpretation(String kind, String name, JsonNode payload) {
        if (StringUtils.hasText(payload.path("interpretation").asText())) {
            return payload.path("interpretation").asText();
        }
        return switch (kind) {
            case "ROW_TABLE" -> "系统认为“" + name + "”中每一行代表一条业务记录。";
            case "COLUMN_TABLE" -> "系统认为“" + name + "”中每一列代表一条业务记录。";
            case "MATRIX" -> "系统认为“" + name + "”的行和列分别表示两类条件，交叉位置填写结果。";
            default -> "系统认为这里用于填写“" + name + "”。";
        };
    }

    private String unescape(String value) {
        return value.replace("~1", "/").replace("~0", "~");
    }

    public record CompiledRecognition(ObjectNode schema, ArrayNode mapping, JsonNode fieldModel) {
    }
}
