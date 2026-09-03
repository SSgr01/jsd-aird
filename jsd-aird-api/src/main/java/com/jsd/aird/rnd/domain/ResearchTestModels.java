package com.jsd.aird.rnd.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ResearchTestModels {
    private ResearchTestModels() {}

    public enum Type { REPORT, STANDARD }
    public enum Status {
        DRAFT, PENDING_REVIEW, RETURNED, PUBLISHED, ARCHIVED;
        public boolean editable() { return this == DRAFT || this == RETURNED; }
    }

    public record Summary(UUID id, Type recordType, String businessNo, String name, String category,
                          String applicableScope, String ownerName, LocalDate businessDate,
                          String documentFormat, String sourceType, Status status, String visibility,
                          UUID projectId, String projectName, UUID stageId, String stageName,
                          UUID taskId, String taskName, UUID sourceFileId, int versionNo,
                          long lockVersion, Instant createdAt, Instant updatedAt) {}
    public record Detail(Summary summary, UUID versionId, UUID templateVersionId, String templateSnapshotHash,
                         JsonNode templateSnapshot, JsonNode editModel, JsonNode memberSnapshot,
                         LocalDate effectiveFrom, LocalDate effectiveTo, List<Review> reviews) {}
    public record Version(UUID id, int versionNo, String status, UUID templateVersionId,
                          String templateSnapshotHash, JsonNode templateSnapshot, JsonNode editModel,
                          JsonNode memberSnapshot, LocalDate effectiveFrom, LocalDate effectiveTo,
                          String changeSummary, Instant submittedAt, Instant publishedAt,
                          UUID createdBy, Instant createdAt) {}
    public record Review(UUID id, String action, String comment, String operatorName, Instant createdAt) {}
    public record Audit(UUID id, String action, JsonNode before, JsonNode after, String operatorName, Instant createdAt) {}
    public record Upload(UUID id, UUID recordId, UUID fileId, String originalName, String contentType,
                         long fileSize, String status, String category, String visibility,
                         String projectName, String stageName, String taskName, Instant createdAt) {}
}
