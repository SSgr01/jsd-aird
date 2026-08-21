package com.jsd.aird.tpl.application;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Canonicalizes template field locations.
 *
 * The public contract is locator.label / locator.value.  The flat keys are
 * retained in the serialized object only while the existing workbook/export
 * consumers are migrated; all new writes are produced by this class.
 */
public final class TemplateLocatorNormalizer {

    public static final int LOCATOR_VERSION = 1;
    private static final Set<String> REGION_KINDS = Set.of(
            "FORM_REGION", "ROW_TABLE", "COLUMN_TABLE", "MATRIX", "TABLE_REGION"
    );

    private TemplateLocatorNormalizer() {
    }

    public static ObjectNode normalize(ObjectMapper mapper, JsonNode raw) {
        ObjectNode locator = raw != null && raw.isObject()
                ? (ObjectNode) raw.deepCopy()
                : mapper.createObjectNode();

        ObjectNode label = mapper.createObjectNode();
        ObjectNode nestedLabel = object(locator.path("label"));
        String labelRange = firstText(nestedLabel, "range", "address");
        if (labelRange.isBlank()) labelRange = firstText(locator, "labelRange", "labelAddress");
        String labelAddress = firstCell(labelRange);
        if (labelAddress.isBlank()) labelAddress = firstText(nestedLabel, "address");
        if (labelRange.isBlank()) labelRange = labelAddress;
        if (!labelAddress.isBlank()) label.put("address", labelAddress);
        if (!labelRange.isBlank()) label.put("range", labelRange);

        ObjectNode value = mapper.createObjectNode();
        ObjectNode nestedValue = object(locator.path("value"));
        String valueRange = firstText(nestedValue, "range", "address");
        if (valueRange.isBlank()) valueRange = firstText(locator,
                "valueRange", "logicalInputRange", "address", "range");
        String valueAddress = firstText(nestedValue, "address");
        if (valueAddress.isBlank()) valueAddress = firstCell(valueRange);
        if (valueAddress.isBlank()) valueAddress = valueRange;
        if (!valueAddress.isBlank()) value.put("address", valueAddress);
        if (!valueRange.isBlank()) value.put("range", valueRange);

        if (label.size() > 0) locator.set("label", label);
        else locator.remove("label");
        if (value.size() > 0) locator.set("value", value);
        else locator.remove("value");

        locator.put("locatorVersion", LOCATOR_VERSION);
        locator.put("source", normalizeSource(locator.path("source").asText(""), label.size() > 0));
        if (!locator.hasNonNull("relation")) locator.put("relation", label.size() > 0 ? "ADJACENT" : "UNRESOLVED");

        // Keep the flat aliases at the persistence boundary while old stored
        // schemas are normalized. The nested label/value objects are the
        // canonical contract consumed by new code.
        if (label.size() > 0) {
            locator.put("labelAddress", label.path("address").asText());
            locator.put("labelRange", label.path("range").asText());
        } else {
            locator.remove("labelAddress");
            locator.remove("labelRange");
        }
        if (value.size() > 0) {
            String legacyAddress = firstText(locator, "address");
            locator.put("address", legacyAddress.isBlank() ? value.path("address").asText() : legacyAddress);
            locator.put("range", value.path("range").asText());
            locator.put("valueRange", value.path("range").asText());
        }
        return locator;
    }

    public static void normalizeFieldModel(ObjectMapper mapper, ObjectNode schema) {
        if (schema == null) return;
        JsonNode modelNode = schema.path(TemplateRecognitionCompiler.FIELD_MODEL_KEY);
        if (!(modelNode instanceof ObjectNode model) || !model.path("fields").isArray()) return;

        Map<String, ObjectNode> fieldsById = new HashMap<>();
        for (JsonNode node : model.path("fields")) {
            if (!(node instanceof ObjectNode field)) continue;
            normalizeField(mapper, field);
            String id = firstText(field, "id", "fieldId");
            if (!id.isBlank()) fieldsById.put(id, field);
        }
        for (JsonNode node : model.path("fields")) {
            if (!(node instanceof ObjectNode field)) continue;
            field.set("pathSegments", pathFor(mapper, field, fieldsById, new HashSet<>()));
        }
        model.put("modelVersion", Math.max(5, model.path("modelVersion").asInt(0)));
    }

    public static ArrayNode normalizeMappings(ObjectMapper mapper, JsonNode mappings) {
        ArrayNode result = mapper.createArrayNode();
        if (mappings == null || !mappings.isArray()) return result;
        mappings.forEach(node -> {
            if (!(node instanceof ObjectNode binding)) return;
            binding.set("locator", normalize(mapper, binding.path("locator")));
            result.add(binding);
        });
        return result;
    }

    public static void normalizeField(ObjectMapper mapper, ObjectNode field) {
        String kind = firstText(field, "kind");
        String mappingKind = firstText(field, "mappingKind");
        String fieldType = firstText(field, "fieldType");
        if (fieldType.isBlank()) {
            fieldType = isRegion(field) ? "REGION"
                    : ("REPEAT_FIELD".equals(mappingKind) ? "TABLE_COLUMN" : "FIELD");
            field.put("fieldType", fieldType);
        }
        if (!field.has("displayRole")) field.put("displayRole", "REGION".equals(fieldType) ? "REGION" : "FIELD");
        field.set("locator", normalize(mapper, field.path("locator")));
        if (!field.has("pathSegments")) {
            ArrayNode path = mapper.createArrayNode();
            path.add(field.path("name").asText(""));
            field.set("pathSegments", path);
        }
        ObjectNode locator = (ObjectNode) field.path("locator");
        String status = "REGION".equals(fieldType)
                ? "NOT_APPLICABLE"
                : hasLabel(locator) ? "RESOLVED" : "UNRESOLVED";
        field.put("labelStatus", status);
        if (field.path("columns").isArray()) {
            for (JsonNode columnNode : field.path("columns")) {
                if (!(columnNode instanceof ObjectNode column)) continue;
                ObjectNode columnLocator = normalize(mapper, column.path("locator").isObject()
                        ? column.path("locator") : column);
                column.set("locator", columnLocator);
                column.put("fieldType", "TABLE_COLUMN");
                column.put("labelStatus", hasLabel(columnLocator) ? "RESOLVED" : "UNRESOLVED");
            }
        }
    }

    public static boolean isRegion(JsonNode field) {
        return "REGION".equals(field.path("displayRole").asText())
                || REGION_KINDS.contains(field.path("kind").asText())
                || "REPEAT_REGION".equals(field.path("mappingKind").asText());
    }

    public static boolean hasLabel(JsonNode locator) {
        return !labelRange(locator).isBlank();
    }

    public static boolean hasValue(JsonNode locator) {
        return !valueRange(locator).isBlank();
    }

    public static String labelRange(JsonNode locator) {
        return firstText(object(locator == null ? null : locator.path("label")), "range", "address")
                .isBlank() ? firstText(locator, "labelRange", "labelAddress")
                : firstText(object(locator == null ? null : locator.path("label")), "range", "address");
    }

    public static String valueRange(JsonNode locator) {
        return firstText(object(locator == null ? null : locator.path("value")), "range", "address")
                .isBlank() ? firstText(locator, "valueRange", "logicalInputRange", "address", "range")
                : firstText(object(locator == null ? null : locator.path("value")), "range", "address");
    }

    private static ObjectNode object(JsonNode value) {
        return value instanceof ObjectNode node ? node : null;
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) return "";
        for (String key : keys) {
            String value = node.path(key).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String firstCell(String range) {
        if (range == null || range.isBlank()) return "";
        String first = range.replace("$", "").trim().split(":", 2)[0];
        return first.matches("(?i)^[A-Z]{1,4}[1-9][0-9]*$") ? first.toUpperCase(Locale.ROOT) : "";
    }

    private static ArrayNode pathFor(
            ObjectMapper mapper,
            ObjectNode field,
            Map<String, ObjectNode> fieldsById,
            Set<String> visiting
    ) {
        ArrayNode path = mapper.createArrayNode();
        String id = firstText(field, "id", "fieldId");
        if (!id.isBlank() && !visiting.add(id)) {
            path.add(field.path("name").asText(""));
            return path;
        }
        String parentId = firstText(field, "parentFieldId");
        ObjectNode parent = fieldsById.get(parentId);
        if (parent != null) {
            pathFor(mapper, parent, fieldsById, visiting).forEach(path::add);
        }
        String name = field.path("name").asText("");
        if (!name.isBlank()) path.add(name);
        return path;
    }

    private static String normalizeSource(String source, boolean hasLabel) {
        if (Set.of("RECOGNIZED", "INFERRED", "MANUAL", "UNRESOLVED").contains(source)) return source;
        if (source.toUpperCase(Locale.ROOT).contains("MANUAL")) return "MANUAL";
        if (source.toUpperCase(Locale.ROOT).contains("INFER")) return "INFERRED";
        return hasLabel ? "RECOGNIZED" : "UNRESOLVED";
    }
}
