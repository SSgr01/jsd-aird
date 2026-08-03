package com.jsd.aird.tpl.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class ModelAuditPayloadCodec {

    private final ObjectMapper objectMapper;

    ModelAuditPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    byte[] compress(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        try {
            var output = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(output)) {
                gzip.write(objectMapper.writeValueAsBytes(value));
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to compress model audit payload", exception);
        }
    }

    JsonNode decompress(byte[] value) {
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(value))) {
            return objectMapper.readTree(gzip);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to decompress model audit payload", exception);
        }
    }
}
