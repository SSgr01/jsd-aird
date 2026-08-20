package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Parses model JSON defensively. Models may wrap a JSON object in Markdown
 * fences or add a short preamble even when JSON output was requested.
 */
@Component
public class AiJsonParser {

    private final ObjectMapper objectMapper;

    public AiJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T read(String raw, Class<T> type) {
        var node = object(raw);
        if (node == null) return null;
        try {
            return objectMapper.treeToValue(node, type);
        } catch (Exception ignored) {
            return null;
        }
    }

    public JsonNode object(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var value = raw.strip();
        var start = value.indexOf('{');
        var end = value.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            var node = objectMapper.readTree(value.substring(start, end + 1));
            return node != null && node.isObject() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
