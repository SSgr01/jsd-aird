package com.jsd.aird.tpl.infrastructure;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Sanitizes model-bound content before it is sent and audited. */
final class ModelPayloadSanitizer {

    private static final Set<String> SECRET_KEYS = Set.of(
            "authorization", "cookie", "api_key", "apikey", "access_token", "refresh_token",
            "password", "secret", "client_secret"
    );
    private static final Pattern API_KEY = Pattern.compile("(?i)sk-[a-z0-9_-]{16,}");
    private static final Pattern EMAIL = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern IMAGE_DATA_URI = Pattern.compile("(?i)data:[^;,\\s]+;base64,[A-Za-z0-9+/=]+(?:[^\\s\"']*)?");

    private final ObjectMapper objectMapper;

    ModelPayloadSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode sanitize(JsonNode source) {
        return sanitize(source, false);
    }

    JsonNode sanitizeForModel(JsonNode source) {
        return sanitize(source, true);
    }

    private JsonNode sanitize(JsonNode source, boolean preserveVisualData) {
        if (source == null || source.isNull()) return objectMapper.nullNode();
        if (source.isObject()) {
            var result = objectMapper.createObjectNode();
            source.fields().forEachRemaining(entry -> {
                var key = entry.getKey();
                result.set(key, "dataUri".equals(key) && preserveVisualData
                        ? entry.getValue().deepCopy()
                        : "dataUri".equals(key)
                        ? objectMapper.getNodeFactory().textNode("[REDACTED_IMAGE_DATA]")
                        : SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT))
                        ? objectMapper.getNodeFactory().textNode("[REDACTED_SECRET]")
                        : sanitize(entry.getValue(), preserveVisualData));
            });
            return result;
        }
        if (source.isArray()) {
            var result = objectMapper.createArrayNode();
            source.forEach(item -> result.add(sanitize(item, preserveVisualData)));
            return result;
        }
        if (!source.isTextual()) return source.deepCopy();
        var value = source.asText();
        value = API_KEY.matcher(value).replaceAll("[REDACTED_API_KEY]");
        value = EMAIL.matcher(value).replaceAll("[REDACTED_EMAIL]");
        value = PHONE.matcher(value).replaceAll("[REDACTED_PHONE]");
        value = ID_CARD.matcher(value).replaceAll("[REDACTED_ID]");
        if (!preserveVisualData) {
            value = IMAGE_DATA_URI.matcher(value).replaceAll("[REDACTED_IMAGE_DATA]");
        }
        return objectMapper.getNodeFactory().textNode(value);
    }
}
