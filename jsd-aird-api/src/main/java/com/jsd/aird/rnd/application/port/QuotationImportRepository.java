package com.jsd.aird.rnd.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 报价文件导入任务端口，与实验本导入任务保持同一套状态机。 */
public interface QuotationImportRepository {

    void create(UUID id, UUID organizationId, UUID fileId, String fileName, String sha256,
                String format, UUID actorId, UUID templateVersionId, UUID projectId,
                String projectName, UUID stageId, String stageName, UUID taskId,
                String taskName, String visibility);

    Optional<Job> find(UUID organizationId, UUID userId, String role, UUID id);

    List<Job> list(UUID organizationId, UUID userId, String role);

    Optional<Job> findCompletedBySourceHash(UUID organizationId, String sourceSha256);

    int markRetrying(UUID organizationId, UUID id);

    int cancel(UUID organizationId, UUID userId, String role, UUID id);

    boolean isParsing(UUID organizationId, UUID id);

    void complete(UUID id, UUID quotationId);

    void fail(UUID id, String errorMessage);

    int delete(UUID organizationId, UUID userId, String role, UUID id);

    record Job(UUID id, UUID sourceFileId, String sourceFileName, String sourceSha256,
               String sourceFormat, String status, UUID quotationId, String quotationNo,
               String customerName, int itemCount, String errorMessage, Instant createdAt,
               int progress, String currentStage, UUID templateVersionId, UUID projectId,
               String projectName, UUID stageId, String stageName, UUID taskId,
               String taskName, String visibility) { }
}
