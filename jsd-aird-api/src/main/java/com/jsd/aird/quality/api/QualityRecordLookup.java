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
                      Instant createdAt, Instant updatedAt, String workflowStatus) {
        public String getDocumentFormat() {
            var configured = data == null ? "" : data.path("fileType").asText("").toUpperCase(java.util.Locale.ROOT);
            if ("EXCEL".equals(configured) || "XLSX".equals(configured)) return "EXCEL";
            if ("WORD".equals(configured) || "DOCX".equals(configured)) return "WORD";
            if ("CSV".equals(configured) || "PDF".equals(configured)) return configured;
            if ("IMAGE".equals(configured) || "PHOTO".equals(configured) || "图片".equals(configured)) return "IMAGE";
            var name = sourceFileName == null ? "" : sourceFileName.toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) return "EXCEL";
            if (name.endsWith(".docx") || name.endsWith(".doc")) return "WORD";
            if (name.endsWith(".csv")) return "CSV";
            if (name.endsWith(".pdf")) return "PDF";
            if (name.matches(".*\\.(jpg|jpeg|png|tif|tiff)$")) return "IMAGE";
            return null;
        }
        public List<String> getAllowedActions() {
            // Quality records currently expose no completed/reference marker;
            // the existing service permits soft deletion and re-checks it at command time.
            return List.of("DELETE");
        }
    }

 Optional<RecordView> recordByBusinessNo(UUID organizationId, String role, String type, String businessNo);
}
