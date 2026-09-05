package com.jsd.aird.rnd.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import com.jsd.aird.shared.api.AllowedActions;

public interface ExperimentImportRepository {
    void create(UUID id, UUID organizationId, UUID fileId, String fileName, String sha256,
                String format, String categoryName, UUID projectId, UUID stageId, UUID taskId,
                String visibility, UUID actorId);
    void complete(UUID id, UUID experimentId, JsonNode parseResult);
    void fail(UUID id, String errorMessage);
    int delete(UUID organizationId, UUID id);
    List<Job> list(UUID organizationId);

    record Job(UUID id, UUID sourceFileId, String sourceFileName, String sourceFormat,
               String status, UUID experimentId, String errorMessage, String categoryName,
               UUID projectId, String projectName, UUID stageId, String stageName, UUID taskId,
               String taskName, String visibility, Instant createdAt) {
        public List<String> getAllowedActions() {
            return AllowedActions.experimentImport(status, experimentId != null);
        }
    }
}
