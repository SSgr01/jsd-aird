package com.jsd.aird.rnd.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

public final class ExperimentModels {
    private ExperimentModels() {}

    public record Summary(UUID id, String experimentNo, String title, String categoryName, String sourceType,
                          ExperimentStatus status, UUID projectId, UUID stageId, UUID taskId, String ownerName,
                          LocalDate experimentDate, int versionNo, long revision, Instant updatedAt) {}
    public record Detail(Summary summary, UUID currentVersionId, UUID templateVersionId, String templateSnapshotHash,
                         JsonNode templateSnapshot, JsonNode editModel, List<Review> reviews,
                         List<Attachment> attachments) {}
    public record Version(UUID id, int versionNo, String status, UUID templateVersionId, String snapshotHash,
                          JsonNode editModel, String revisionReason, Instant submittedAt, Instant publishedAt,
                          Instant createdAt) {}
    public record Review(UUID id, String action, String comment, String operatorName, Instant createdAt) {}
    public record Attachment(UUID id, UUID fileId, UUID fileVersionId, String type, String sectionKey,
                             String fileName, String description, Instant createdAt) {}
    public record Audit(UUID id, String action, JsonNode before, JsonNode after, String operatorName, Instant createdAt) {}
    public record Category(UUID id, String code, String name, String description, boolean active, long revision) {}
    public record ImportJob(UUID id, UUID sourceFileId, String sourceFileName, String sha256, String status,
                            boolean duplicateOverride, JsonNode parseResult, UUID experimentId, String errorMessage,
                            Instant createdAt, Instant updatedAt) {}
}
