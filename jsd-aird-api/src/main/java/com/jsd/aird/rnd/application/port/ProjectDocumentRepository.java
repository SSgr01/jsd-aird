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
            long version,
            UUID templateId,
            UUID templateVersionId,
            String templateName,
            UUID fileObjectId,
            UUID currentVersionId,
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

    // ===== 版本与审计（对齐实验记事本 experiment_version / experiment_audit） =====

    record VersionRecord(
            UUID id,
            UUID documentId,
            long versionNo,
            String status,
            JsonNode contentJsonb,
            String snapshotReason,
            String createdBy,
            Instant createdAt
    ) {
    }

    record AuditRecord(
            UUID id,
            UUID documentId,
            String action,
            JsonNode beforeJsonb,
            JsonNode afterJsonb,
            String operatorId,
            String operatorName,
            Instant createdAt
    ) {
    }

    record ReviewRecord(
            UUID id,
            UUID documentId,
            UUID documentVersionId,
            String action,
            String comment,
            String operatorId,
            String operatorName,
            Instant createdAt
    ) {
    }

    record VersionDiff(
            JsonNode before,
            JsonNode after
    ) {
    }

    List<VersionRecord> versions(UUID documentId);

    VersionDiff diff(UUID documentId, long from, long to);

    List<AuditRecord> audits(UUID documentId);

    List<ReviewRecord> reviews(UUID documentId);

    UUID appendVersion(UUID documentId, long versionNo, String status, JsonNode contentJsonb,
                       String snapshotReason, UUID templateVersionId, String createdBy);

    void appendAudit(UUID documentId, UUID documentVersionId, String action, JsonNode before, JsonNode after,
                     UUID operatorId, String operatorName);

    void appendReview(UUID documentId, UUID documentVersionId, String action, String comment,
                      UUID operatorId, String operatorName);

    /**
     * 发布：新建一条已发布版本行（versionNo 自动递增，published_at=now），
     * 并把文档状态置为 PUBLISHED、回写 current_version_id。
     * 对齐实验记事本 ExperimentRepository.transition 到 COMPLETED 的逻辑。
     */
    UUID publish(UUID documentId, JsonNode contentJsonb, String snapshotReason, UUID templateVersionId,
                 String createdBy);
}
