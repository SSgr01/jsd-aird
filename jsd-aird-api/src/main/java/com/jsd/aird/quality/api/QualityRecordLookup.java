package com.jsd.aird.quality.api;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
public interface QualityRecordLookup {
    record RecordView(UUID id, String businessType, UUID categoryId, String categoryName,
                      String businessNo, String displayName, JsonNode data, UUID sourceFileId, String sourceFileName,
                      UUID projectId, String projectName, UUID stageId, String stageName,
                      UUID taskId, String taskName,
                      String visibility, JsonNode workbookSnapshot, long lockVersion,
                      Instant createdAt, Instant updatedAt) {
        public List<String> getAllowedActions() {
            // Quality records currently expose no completed/reference marker;
            // the existing service permits soft deletion and re-checks it at command time.
            return List.of("DELETE");
        }
    }

 Optional<RecordView> recordByBusinessNo(UUID organizationId, String role, String type, String businessNo);
}
