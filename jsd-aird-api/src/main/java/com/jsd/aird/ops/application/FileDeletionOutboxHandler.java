package com.jsd.aird.ops.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.*;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Durable, idempotent cleanup for artifacts whose owning commit was rejected. */
@Component
public class FileDeletionOutboxHandler implements OutboxEventHandler {
    private final FileObjectRepository files;private final ObjectStorage storage;
    public FileDeletionOutboxHandler(FileObjectRepository files,ObjectStorage storage){this.files=files;this.storage=storage;}
    @Override public boolean supports(String eventType){return "FILE_DELETION_REQUESTED".equals(eventType);}
    @Override public void handle(UUID fileId,JsonNode payload){
        var organizationId=UUID.fromString(payload.path("organizationId").asText());
        files.find(organizationId,fileId).filter(file->"DELETED".equals(file.status())).ifPresent(file->storage.delete(file.objectKey()));
    }
}
