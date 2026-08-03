package com.jsd.aird.shared.json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class JsonCanonicalizer {

    private final ObjectMapper objectMapper;

    public JsonCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(JsonNode node) {
        try {
            var canonicalBytes = objectMapper.writeValueAsBytes(canonicalize(node));
            return hex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        } catch (Exception exception) {
            throw new IllegalArgumentException("JSON cannot be canonicalized", exception);
        }
    }

    public String hashText(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            var result = objectMapper.createArrayNode();
            node.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        var result = objectMapper.createObjectNode();
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(Comparator.naturalOrder());
        names.forEach(name -> result.set(name, canonicalize(node.get(name))));
        return result;
    }

    public String workspaceHash(
            String aggregateId,
            JsonNode schema,
            JsonNode mapping,
            JsonNode data,
            String snapshotHash,
            String appVersion,
            String pluginManifestHash
    ) {
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("aggregateId", aggregateId);
        manifest.put("schemaHash", hash(schema));
        manifest.put("mappingHash", hash(mapping));
        manifest.put("dataHash", hash(data));
        manifest.put("snapshotHash", snapshotHash == null ? "" : snapshotHash);
        manifest.put("appVersion", appVersion);
        manifest.put("pluginManifestHash", pluginManifestHash);
        return hash(manifest);
    }

    private String hex(byte[] value) {
        var result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }
}
