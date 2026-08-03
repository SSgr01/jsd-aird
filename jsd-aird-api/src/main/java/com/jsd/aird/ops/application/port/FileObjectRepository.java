package com.jsd.aird.ops.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface FileObjectRepository {

    void insert(NewFileObject file);

    Optional<FileObject> find(UUID organizationId, UUID fileId);

    void activate(UUID fileId);

    int markExpiredStagedDeleted(Instant olderThan);

    record NewFileObject(
            UUID id,
            UUID organizationId,
            String bucket,
            String objectKey,
            String originalName,
            String contentType,
            long size,
            String sha256,
            UUID actorId
    ) {
    }

    record FileObject(
            UUID id,
            UUID organizationId,
            String objectKey,
            String originalName,
            String contentType,
            long size,
            String sha256,
            String status
    ) {
    }
}
