package com.jsd.aird.rnd.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import com.jsd.aird.shared.api.AllowedActions;
import java.util.Optional;

public interface ExperimentImportRepository {
    void create(UUID id, UUID organizationId, UUID fileId, String fileName, String sha256,
                String format, String categoryName, UUID projectId, UUID stageId, UUID taskId,
                String visibility, UUID actorId);
    void complete(UUID id, UUID experimentId, JsonNode parseResult);
    void fail(UUID id, String errorMessage);
    Optional<Job> find(UUID organizationId, UUID id);
    int markRetrying(UUID organizationId, UUID id);
    /** Marks an in-flight parse as cancelled so a worker cannot publish its result. */
    int cancel(UUID organizationId, UUID id);
    boolean isParsing(UUID organizationId, UUID id);
    int delete(UUID organizationId, UUID id);
    List<Job> list(UUID organizationId);

    record Job(UUID id, UUID sourceFileId, String sourceFileName, String sourceSha256, String sourceFormat,
               String status, UUID experimentId, String errorMessage, String categoryName,
               UUID projectId, String projectName, UUID stageId, String stageName, UUID taskId,
               String taskName, String visibility, Instant createdAt, int progress, String currentStage) {
        public List<String> getAllowedActions() {
            return AllowedActions.experimentImport(status, experimentId != null);
        }
    }
}
