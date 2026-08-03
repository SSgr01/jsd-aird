package com.jsd.aird.ops.application.port;

import java.io.InputStream;

public interface ObjectStorage {

    void put(String objectKey, InputStream source, long size, String contentType);

    StoredObject get(String objectKey);

    void delete(String objectKey);

    record StoredObject(InputStream stream, long size, String contentType) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            stream.close();
        }
    }
}
