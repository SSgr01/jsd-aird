package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;

/**
 * Upgrades legacy experiment edit models to the V2 JSON contract without
 * discarding extension fields that are not rendered by the current editor.
 */
@Component
public class ExperimentEditModelNormalizer {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    private final ObjectMapper json;

    public ExperimentEditModelNormalizer(ObjectMapper json) {
        this.json = json;
    }

    public ObjectNode empty(String title, UUID stableSeed) {
        var model = json.createObjectNode();
        model.put("title", title);
        model.put("purpose", "");
        model.put("plan", "");
        model.set("dynamicValues", json.createObjectNode());
        model.set("tables", json.createArrayNode());
        model.set("formulaItems", json.createArrayNode());
        model.set("processSteps", json.createArrayNode());
        model.set("testResults", json.createArrayNode());
        model.set("events", json.createArrayNode());
        var conclusion = json.createObjectNode();
        conclusion.put("resultStatus", "");
        conclusion.put("mainConclusion", "");
        conclusion.put("failureCategory", "");
        model.set("conclusion", conclusion);
        return normalize(model, stableSeed);
    }

    public ObjectNode normalize(JsonNode value, UUID stableSeed) {
        if (value == null || !value.isObject()) {
            throw validation("editModel必须是JSON对象");
        }
        var model = ((ObjectNode) value).deepCopy();
        var schemaVersion = schemaVersion(model);
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw validation("editModel schemaVersion暂不支持：" + schemaVersion);
        }
        model.put("schemaVersion", CURRENT_SCHEMA_VERSION);
        var itemIdSeed = stableSeed == null ? UUID.randomUUID() : stableSeed;
        normalizeItems(model, "formulaItems", itemIdSeed, ItemKind.FORMULA);
        normalizeItems(model, "processSteps", itemIdSeed, ItemKind.PROCESS);
        normalizeItems(model, "testResults", itemIdSeed, ItemKind.TEST);
        return model;
    }

    private int schemaVersion(ObjectNode model) {
        var value = model.get("schemaVersion");
        if (value == null || value.isNull()) return 1;
        if (!value.isIntegralNumber() || value.asInt() < 1) {
            throw validation("editModel schemaVersion必须是正整数");
        }
        return value.asInt();
    }

    private void normalizeItems(ObjectNode model, String fieldName, UUID stableSeed, ItemKind kind) {
        var current = model.get(fieldName);
        if (current == null || current.isNull()) {
            model.set(fieldName, json.createArrayNode());
            return;
        }
        if (!current.isArray()) {
            throw validation("editModel." + fieldName + "必须是数组");
        }

        var normalized = json.createArrayNode();
        var itemIds = new HashSet<String>();
        var index = 0;
        for (var value : current) {
            if (!value.isObject()) {
                throw validation("editModel." + fieldName + "[" + index + "]必须是JSON对象");
            }
            var item = ((ObjectNode) value).deepCopy();
            var rawItemId = item.get("itemId");
            if (rawItemId != null && !rawItemId.isNull() && !rawItemId.isTextual()) {
                throw validation("editModel." + fieldName + "[" + index + "].itemId必须是字符串");
            }
            var itemId = rawItemId == null || rawItemId.isNull() ? "" : rawItemId.asText().strip();
            if (itemId.isBlank()) {
                itemId = generatedItemId(stableSeed, fieldName, index);
            }
            item.put("itemId", itemId);
            if (!itemIds.add(itemId)) {
                throw validation("editModel." + fieldName + "存在重复itemId：" + itemId);
            }
            normalizeSourceRefs(item, fieldName, index);
            kind.addDefaults(item);
            normalized.add(item);
            index++;
        }
        model.set(fieldName, normalized);
    }

    private void normalizeSourceRefs(ObjectNode item, String fieldName, int index) {
        var sourceRefs = item.get("sourceRefs");
        if (sourceRefs == null || sourceRefs.isNull()) {
            item.set("sourceRefs", json.createArrayNode());
            return;
        }
        if (!sourceRefs.isArray()) {
            throw validation("editModel." + fieldName + "[" + index + "].sourceRefs必须是数组");
        }
        var sourceIndex = 0;
        for (var sourceRef : (ArrayNode) sourceRefs) {
            if (!sourceRef.isObject()) {
                throw validation("editModel." + fieldName + "[" + index + "].sourceRefs["
                        + sourceIndex + "]必须是JSON对象");
            }
            sourceIndex++;
        }
    }

    private String generatedItemId(UUID stableSeed, String fieldName, int index) {
        return UUID.nameUUIDFromBytes((stableSeed + ":" + fieldName + ":" + index)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void putTextDefault(ObjectNode item, String fieldName) {
        if (!item.has(fieldName) || item.path(fieldName).isNull()) item.put(fieldName, "");
    }

    private static void putNullDefault(ObjectNode item, String fieldName) {
        if (!item.has(fieldName)) item.putNull(fieldName);
    }

    private static ApiException validation(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_ERROR, message);
    }

    private enum ItemKind {
        FORMULA {
            @Override
            void addDefaults(ObjectNode item) {
                putNullDefault(item, "materialId");
                putTextDefault(item, "materialCode");
                putNullDefault(item, "rawValue");
                putTextDefault(item, "rawUnit");
            }
        },
        PROCESS,
        TEST {
            @Override
            void addDefaults(ObjectNode item) {
                putTextDefault(item, "testMethod");
                putTextDefault(item, "testCondition");
                putTextDefault(item, "substrate");
            }
        };

        void addDefaults(ObjectNode item) {
            // Stable itemId and sourceRefs are common to every item kind.
        }
    }
}
