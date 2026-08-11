package com.jsd.aird.ops.application.port;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

public interface ObjectStorage {

    void put(String objectKey, InputStream source, long size, String contentType);

    StoredObject get(String objectKey);

    default Optional<String> presignedGetUrl(String objectKey, Duration expiry) {
        return Optional.empty();
    }

    void delete(String objectKey);

    record StoredObject(InputStream stream, long size, String contentType) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            stream.close();
        }
    }
}
