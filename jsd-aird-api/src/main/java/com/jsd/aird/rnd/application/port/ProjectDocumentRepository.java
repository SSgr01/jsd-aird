package com.jsd.aird.rnd.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.rnd.domain.ProjectDocumentFormat;
import com.jsd.aird.rnd.domain.ProjectDocumentSource;
import com.jsd.aird.rnd.domain.ProjectDocumentStatus;
import com.fasterxml.jackson.databind.JsonNode;

public interface ProjectDocumentRepository {

    record Search(
            UUID projectId,
            ProjectDocumentStatus status
    ) {
    }

    record Create(
            UUID projectId,
            String title,
            ProjectDocumentFormat format,
            ProjectDocumentSource source,
            UUID templateId,
            UUID templateVersionId,
            UUID fileObjectId,
            ProjectDocumentStatus status,
            String createdBy
    ) {
    }

    record Summary(
            UUID id,
            String title,
            ProjectDocumentFormat format,
            ProjectDocumentSource source,
            ProjectDocumentStatus status,
            UUID templateId,
            UUID templateVersionId,
            String templateName,
            UUID fileObjectId,
            Instant createdAt,
            String createdBy
    ) {
    }

    record Detail(
            UUID id,
            UUID projectId,
            String title,
            ProjectDocumentFormat format,
            ProjectDocumentSource source,
            ProjectDocumentStatus status,
            UUID templateId,
            UUID templateVersionId,
            String templateName,
            UUID fileObjectId,
            Instant createdAt,
            String createdBy,
            Instant updatedAt,
            String updatedBy,
            JsonNode contentSnapshot,
            JsonNode contentSchema,
            JsonNode contentMapping,
            JsonNode contentData,
            JsonNode contentRecognition
    ) {
    }

    List<Summary> search(Search q);

    Optional<Detail> findById(UUID id);

    UUID create(Create cmd);

    void saveContent(UUID id, JsonNode snapshot, JsonNode schema, JsonNode mapping, JsonNode data,
                     JsonNode recognition, String updatedBy);

    void delete(UUID id, String updatedBy);
}
