package com.jsd.aird.ops.application.port;

import java.io.InputStream;
import java.util.UUID;

/** Public file boundary for business modules. Object keys never cross this boundary. */
public interface FileStorageFacade {

    StagedFile stageFile(String originalName, String contentType, String kind, InputStream source);

    StoredFile open(UUID organizationId, UUID fileId);

    void activate(UUID fileId);

    record StagedFile(UUID fileId, String originalName, String contentType, long size, String sha256, String status) {
    }

    record StoredFile(
            UUID fileId,
            String originalName,
            String contentType,
            long size,
            String sha256,
            InputStream stream
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            stream.close();
        }
    }
}
