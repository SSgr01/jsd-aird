package com.jsd.aird.ai.rnd.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;

@Component
public class ConfigurationHashing {
    private final ObjectMapper mapper;

    public ConfigurationHashing(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String hash(Object value) {
        try {
            var node = value instanceof JsonNode json ? json : mapper.valueToTree(value);
            var bytes = mapper.writeValueAsBytes(canonical(node));
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalArgumentException("配置内容无法计算哈希", exception);
        }
    }

    public JsonNode canonical(JsonNode value) {
        if (value == null || value.isNull()) return mapper.nullNode();
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            java.util.stream.StreamSupport.stream(
                            java.util.Spliterators.spliteratorUnknownSize(value.fieldNames(), 0), false)
                    .sorted(Comparator.naturalOrder())
                    .forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }

    public static String normalizeAlias(String value) {
        if (value == null) return "";
        var normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).strip();
        return normalized.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
