package com.jsd.aird.mfg.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import org.springframework.stereotype.Component;

/** Builds immutable, query-friendly projections from one canonical record revision. */
@Component
public class RecordProjectionService {

    private static final String FIELD_MODEL_KEY = "x-jsd-field-model";
    private final ObjectMapper objectMapper;

    public RecordProjectionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Projection compile(UUID revisionId, UUID orderId, JsonNode schema, JsonNode data) {
        var fields = schema.path(FIELD_MODEL_KEY).path("fields");
        var byParent = new HashMap<String, List<JsonNode>>();
        if (fields.isArray()) {
            for (var field : fields) {
                var parentId = field.path("parentFieldId").asText("");
                if (!parentId.isBlank()) {
                    byParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(field);
                }
            }
        }

        var collections = new ArrayList<ProductionOrderRepository.CollectionProjection>();
        var values = new ArrayList<ProductionOrderRepository.ValueProjection>();
        if (!fields.isArray()) return new Projection(collections, values);

        for (var field : fields) {
            if (field.path("candidate").asBoolean(false)
                    || !field.path("parentFieldId").asText("").isBlank()) continue;
            var path = field.path("dataPath").asText("");
            if (path.isBlank()) continue;
            var kind = field.path("kind").asText("SCALAR");
            var value = read(data, path);
            if ("ROW_TABLE".equals(kind) || "COLUMN_TABLE".equals(kind) || "MATRIX".equals(kind)) {
                appendCollection(revisionId, orderId, field, value, byParent.getOrDefault(
                        field.path("id").asText(""), List.of()), collections, values);
            } else if (indexable(field) && scalar(value)) {
                values.add(valueProjection(revisionId, orderId, null, field, path, value));
            }
        }
        return new Projection(List.copyOf(collections), List.copyOf(values));
    }

    private void appendCollection(
            UUID revisionId,
            UUID orderId,
            JsonNode parent,
            JsonNode value,
            List<JsonNode> children,
            List<ProductionOrderRepository.CollectionProjection> collections,
            List<ProductionOrderRepository.ValueProjection> values
    ) {
        if (value == null || !value.isArray()) return;
        var kind = "MATRIX".equals(parent.path("kind").asText()) ? "MATRIX" : "DETAIL";
        var parentPath = parent.path("dataPath").asText();
        var parentCode = parent.path("fieldCode").asText("LOCAL.COLLECTION");
        for (var index = 0; index < value.size(); index++) {
            var raw = value.get(index);
            var record = raw != null && raw.isObject()
                    ? raw.deepCopy() : objectMapper.createObjectNode().set("value", raw);
            var memberKey = memberKey(record);
            var recordKey = !memberKey.isBlank() ? memberKey : parentCode + ":" + index;
            var collectionId = UUID.randomUUID();
            collections.add(new ProductionOrderRepository.CollectionProjection(
                    collectionId, revisionId, orderId, kind, parentCode, parentPath,
                    recordKey, index, memberKey.isBlank() ? null : memberKey, record));
            for (var child : children) {
                if (!indexable(child)) continue;
                var childPath = child.path("dataPath").asText("");
                var relative = relativeChildPath(parentPath, childPath);
                if (relative.isBlank()) continue;
                var childValue = read(record, "/" + relative);
                if (!scalar(childValue)) continue;
                values.add(valueProjection(revisionId, orderId, collectionId, child,
                        childPath.replace("/*/", "/" + index + "/"), childValue));
            }
        }
    }

    private ProductionOrderRepository.ValueProjection valueProjection(
            UUID revisionId,
            UUID orderId,
            UUID collectionId,
            JsonNode field,
            String path,
            JsonNode value
    ) {
        return new ProductionOrderRepository.ValueProjection(
                UUID.randomUUID(), revisionId, orderId, collectionId,
                field.path("fieldCode").asText("AUTO.FIELD"), path,
                normalizeType(field.path("valueType").asText("string")), value);
    }

    private boolean indexable(JsonNode field) {
        return !"ORDER_LOCAL".equals(field.path("fieldOrigin").asText(""))
                && !field.path("fieldCode").asText("").isBlank();
    }

    private boolean scalar(JsonNode value) {
        return value != null && !value.isMissingNode() && !value.isContainerNode() && !value.isNull();
    }

    private String memberKey(JsonNode record) {
        var member = record.path("_member");
        if (member.isObject()) {
            var slotId = member.path("slotId").asText("");
            if (!slotId.isBlank()) return slotId;
            var coordinate = member.path("coordinate").asText("");
            if (!coordinate.isBlank()) return coordinate;
            var label = member.path("label").asText("");
            if (!label.isBlank()) return label;
        }
        return record.path("recordKey").asText("");
    }

    private String relativeChildPath(String parentPath, String childPath) {
        var prefix = parentPath + "/*/";
        return childPath.startsWith(prefix) ? childPath.substring(prefix.length()) : "";
    }

    private JsonNode read(JsonNode root, String path) {
        var current = root;
        for (var raw : path.split("/")) {
            if (raw.isBlank()) continue;
            var segment = raw.replace("~1", "/").replace("~0", "~");
            if (current == null || !current.isObject()) return null;
            current = current.get(segment);
        }
        return current;
    }

    private String normalizeType(String value) {
        return switch (value) {
            case "int", "long", "integer" -> "integer";
            case "float", "double", "decimal", "number" -> "number";
            case "bool", "boolean" -> "boolean";
            case "date" -> "date";
            case "reference" -> "reference";
            default -> "string";
        };
    }

    public record Projection(
            List<ProductionOrderRepository.CollectionProjection> collections,
            List<ProductionOrderRepository.ValueProjection> values
    ) {
    }
}
