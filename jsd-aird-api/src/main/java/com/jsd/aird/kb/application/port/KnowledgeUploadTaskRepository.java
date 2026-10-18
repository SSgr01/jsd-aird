package com.jsd.aird.kb.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;

public interface KnowledgeUploadTaskRepository {

    UploadTaskRow create(UUID id, UUID organizationId, UUID actorId, String fileName, long size, JsonNode uploadContext);

    List<UploadTaskRow> list(UUID organizationId, UUID actorId, int limit);

    Optional<UploadTaskRow> update(UUID organizationId, UUID actorId, UUID id, String status, int progress,
                                   UUID sourceFileId, JsonNode uploadContext, UUID documentId,
                                   UUID documentVersionId, String message);

    boolean delete(UUID organizationId, UUID actorId, UUID id);

    record UploadTaskRow(UUID id, String fileName, long size, String status, int progress,
                         UUID sourceFileId, JsonNode uploadContext, UUID documentId,
                         UUID documentVersionId, String message,
                         Instant createdAt, Instant updatedAt) { }
}
