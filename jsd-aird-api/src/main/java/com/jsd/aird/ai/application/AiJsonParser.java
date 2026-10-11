package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
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
        var candidate = value.substring(start, end + 1);
        var parsed = readObject(candidate);
        if (parsed != null) return parsed;
        return readObject(repairModelJson(candidate));
    }

    String decodeStringContent(String value) {
        if (value == null || value.isEmpty()) return "";
        var parsed = readObject(repairModelJson("{\"value\":\"" + value + "\"}"));
        return parsed != null && parsed.path("value").isTextual() ? parsed.path("value").asText() : value;
    }

    private JsonNode readObject(String value) {
        try {
            var node = objectMapper.readTree(value);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Parses model output while preserving enough failure detail for diagnostics.
     * The response preview is bounded because model output may contain user-derived
     * search terms. Callers should log it only on parse/binding failures.
     */
    public <T> ReadResult<T> readWithDiagnostics(String raw, Class<T> type, int previewLimit) {
        var node = object(raw);
        if (node == null) {
            return new ReadResult<>(null, "INVALID_JSON_OBJECT", "$", preview(raw, previewLimit));
        }
        try {
            var value = objectMapper.treeToValue(node, type);
            if (value == null) {
                return new ReadResult<>(null, "EMPTY_BOUND_VALUE", "$", preview(node.toString(), previewLimit));
            }
            return new ReadResult<>(value, "", "", "");
        } catch (Exception exception) {
            var mapping = exception instanceof JsonMappingException jsonMapping ? jsonMapping : null;
            var path = mapping == null ? "$" : mappingPath(mapping);
            var failedNode = mapping == null ? null : nodeAtPath(node, mapping);
            var valuePreview = failedNode == null ? preview(node.toString(), previewLimit)
                    : preview(failedNode.toString(), previewLimit);
            return new ReadResult<>(null, "OBJECT_BINDING_FAILED", path, valuePreview);
        }
    }

    private static String mappingPath(JsonMappingException exception) {
        var path = new StringBuilder("$");
        for (var reference : exception.getPath()) {
            if (reference.getFieldName() != null) path.append('.').append(reference.getFieldName());
            else if (reference.getIndex() >= 0) path.append('[').append(reference.getIndex()).append(']');
        }
        return path.toString();
    }

    private static JsonNode nodeAtPath(JsonNode root, JsonMappingException exception) {
        var node = root;
        for (var reference : exception.getPath()) {
            if (node == null) return null;
            if (reference.getFieldName() != null) node = node.get(reference.getFieldName());
            else if (reference.getIndex() >= 0 && node.isArray() && reference.getIndex() < node.size()) {
                node = node.get(reference.getIndex());
            }
        }
        return node;
    }

    private static String preview(String value, int limit) {
        if (value == null) return "<null>";
        var safeLimit = Math.max(0, limit);
        var normalized = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").replace('\t', ' ');
        return normalized.length() <= safeLimit ? normalized : normalized.substring(0, safeLimit) + "…";
    }

    public record ReadResult<T>(T value, String errorCode, String errorPath, String valuePreview) {
        public boolean successful() {
            return errorCode == null || errorCode.isBlank();
        }
    }

    /**
     * Repairs only JSON syntax mistakes commonly produced around formula text:
     * literal LaTeX backslashes, control characters inside strings, and trailing
     * commas. Valid JSON is parsed before this method is used and is never changed.
     */
    private String repairModelJson(String value) {
        var result = new StringBuilder(value.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            var ch = value.charAt(index);
            if (!inString) {
                if (ch == '"') inString = true;
                if (ch == ',' && followedByClosingToken(value, index + 1)) continue;
                result.append(ch);
                continue;
            }
            if (escaped) {
                result.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '"') {
                inString = false;
                result.append(ch);
                continue;
            }
            if (ch == '\\') {
                if (isJsonEscape(value, index)) {
                    result.append(ch);
                    escaped = true;
                } else {
                    result.append("\\\\");
                }
                continue;
            }
            if (ch == '\n') {
                result.append("\\n");
            } else if (ch == '\r') {
                result.append("\\r");
            } else if (ch == '\t') {
                result.append("\\t");
            } else if (ch < 0x20) {
                result.append(String.format("\\u%04x", (int) ch));
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    private boolean followedByClosingToken(String value, int start) {
        for (int index = start; index < value.length(); index++) {
            var ch = value.charAt(index);
            if (Character.isWhitespace(ch)) continue;
            return ch == '}' || ch == ']';
        }
        return false;
    }

    private boolean isJsonEscape(String value, int slashIndex) {
        if (slashIndex + 1 >= value.length()) return false;
        var next = value.charAt(slashIndex + 1);
        if (next == '"' || next == '\\' || next == '/') return true;
        if (next == 'u') {
            if (slashIndex + 5 >= value.length()) return false;
            for (int index = slashIndex + 2; index <= slashIndex + 5; index++) {
                if (Character.digit(value.charAt(index), 16) < 0) return false;
            }
            return true;
        }
        if (next != 'b' && next != 'f' && next != 'n' && next != 'r' && next != 't') return false;
        return slashIndex + 2 >= value.length()
                || value.charAt(slashIndex + 2) < 'a' || value.charAt(slashIndex + 2) > 'z';
    }
}
