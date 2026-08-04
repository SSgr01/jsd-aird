package com.jsd.aird.tpl.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Deterministic identities used by semantic recognition and mapping compilation. */
public final class RecognitionIdentity {

    public static final UUID FIELD_NAMESPACE = UUID.fromString("56a16d8a-0daa-5cd0-94d6-7cf6fbaf8a14");
    public static final UUID BINDING_NAMESPACE = UUID.fromString("80f73f71-c302-5e30-9db3-a38cf16f9982");

    private RecognitionIdentity() {
    }

    public static String relationId(
            String sheetId, String labelRange, String valueRange, String relationType
    ) {
        return "rel-" + shortHash(normalize(sheetId) + "|" + normalizeRange(labelRange)
                + "|" + normalizeRange(valueRange) + "|" + normalize(relationType), 24);
    }

    public static String blockId(
            String sheetId, String range, String blockType, String parentIdentity
    ) {
        return "blk-" + shortHash(normalize(sheetId) + "|" + normalizeRange(range)
                + "|" + normalize(blockType) + "|" + normalize(parentIdentity), 24);
    }

    public static UUID fieldId(String relationId) {
        return uuidV5(FIELD_NAMESPACE, relationId);
    }

    public static UUID bindingId(UUID fieldId, String locatorType, String locatorIdentity) {
        return uuidV5(BINDING_NAMESPACE,
                fieldId + "|" + normalize(locatorType) + "|" + normalize(locatorIdentity));
    }

    public static UUID uuidV5(UUID namespace, String name) {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            var namespaceBytes = ByteBuffer.allocate(16)
                    .putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits()).array();
            digest.update(namespaceBytes);
            var hash = digest.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] &= 0x0f;
            hash[6] |= 0x50;
            hash[8] &= 0x3f;
            hash[8] |= (byte) 0x80;
            var buffer = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成稳定 UUID", exception);
        }
    }

    public static String shortHash(String material, int length) {
        try {
            var hash = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, length);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成稳定标识", exception);
        }
    }

    public static String normalizeRange(String value) {
        return value == null ? "" : value.replace("$", "").strip().toUpperCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
