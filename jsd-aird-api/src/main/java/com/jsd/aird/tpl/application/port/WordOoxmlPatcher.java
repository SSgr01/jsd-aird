package com.jsd.aird.tpl.application.port;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.JsonNode;

public interface WordOoxmlPatcher {
    default byte[] apply(java.io.InputStream source,ArrayNode operations,String expectedSha){return apply(verified(source,expectedSha),operations);}
    default byte[] applySnapshot(java.io.InputStream source,JsonNode snapshot,String expectedSha){return applySnapshot(verified(source,expectedSha),snapshot);}
    private static byte[] verified(java.io.InputStream source,String expectedSha){
        try(source){var bytes=com.jsd.aird.shared.resource.BoundedStreams.readBytes(source,512L*1024*1024);
            if(!java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expectedSha))throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,"源文件摘要已变化");return bytes;
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException error){throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.FILE_NOT_READY);}
    }
    byte[] apply(byte[] source, ArrayNode operations);

    default byte[] applySnapshot(byte[] source, JsonNode snapshot) {
        return source;
    }
}
