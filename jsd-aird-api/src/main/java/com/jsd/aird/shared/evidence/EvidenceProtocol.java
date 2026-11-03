package com.jsd.aird.shared.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Canonical source hashing matches Python SourceRef defaults and sorted UTF-8 JSON. */
public final class EvidenceProtocol {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> OPTIONAL = List.of("publication_id", "url", "resource_id", "resource_type",
        "project_id", "retrieved_at");
    private EvidenceProtocol() { }

    public static String evidenceId(JsonNode source) {
        var canonical = ((ObjectNode) source).deepCopy();
        for (var key : OPTIONAL) if (!canonical.has(key)) canonical.putNull(key);
        try {
            return "e" + sha(canonicalJson(canonical)).substring(0, 24);
        } catch (Exception error) { throw new IllegalArgumentException("Invalid evidence source", error); }
    }

    public static ObjectNode candidate(String label, String text, JsonNode source) {
        if (!sha(text).equals(source.path("content_sha256").asText()))
            throw new IllegalArgumentException("SOURCE_HASH_MISMATCH");
        var result = JSON.createObjectNode().put("text", text).put("evidence_id", evidenceId(source));
        if (label != null) result.put("label", label);
        result.set("source", source);
        return result;
    }

    public static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static String canonicalJson(JsonNode node) throws Exception {
        if (node.isObject()) {
            var keys = new TreeSet<String>(); node.fieldNames().forEachRemaining(keys::add);
            var values = new ArrayList<String>();
            for (var key : keys) values.add(JSON.writeValueAsString(key) + ":" + canonicalJson(node.get(key)));
            return "{" + String.join(",", values) + "}";
        }
        if (node.isArray()) {
            var values = new ArrayList<String>();
            for (var item : node) values.add(canonicalJson(item));
            return "[" + String.join(",", values) + "]";
        }
        if (node.isFloatingPointNumber()) {
            var value = node.doubleValue();
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite source coordinate");
            if (value == 0) return Double.doubleToRawLongBits(value) < 0 ? "-0.0" : "0.0";
            var decimal = java.math.BigDecimal.valueOf(value).stripTrailingZeros();
            if (Math.abs(value) >= 0.0001 && Math.abs(value) < 1e16) {
                var plain = decimal.toPlainString();
                return plain.contains(".") ? plain : plain + ".0";
            }
            int exponent = decimal.precision() - decimal.scale() - 1;
            return decimal.movePointLeft(exponent).toPlainString() + "e" + (exponent < 0 ? "-" : "+")
                + String.format(Locale.ROOT, "%02d", Math.abs(exponent));
        }
        return JSON.writeValueAsString(node);
    }
}
