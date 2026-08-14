package com.jsd.aird.data.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jsd.aird.shared.api.PageResponse;

public interface DataRepository {

    void insertJob(NewJob job);

    void enqueueParse(UUID asyncJobId, UUID organizationId, UUID importJobId);

    Optional<Job> findJob(UUID organizationId, UUID importJobId);

    PageResponse<Job> listJobs(UUID organizationId, UUID templateVersionId,
                               String status, String keyword, int page, int size);

    PageResponse<SourceFile> listSourceFiles(UUID organizationId, UUID categoryId, String status,
                                             String keyword, int page, int size);

    int assignSourceCategory(UUID organizationId, UUID importJobId, UUID categoryId);

    Optional<Job> findJobForUpdate(UUID organizationId, UUID importJobId);

    Optional<Job> findCompletedDuplicate(UUID organizationId, String sha256, UUID templateVersionId);

    void saveParsed(UUID importJobId, String parserVersion, List<Sheet> sheets, List<Mapping> mappings,
                    List<Row> rows, UUID asyncJobId);

    void clearParsed(UUID organizationId, UUID importJobId);

    Optional<JsonNode> findMappingProfile(UUID organizationId, UUID templateVersionId, String sourceFingerprint);

    void saveMappingProfile(UUID organizationId, UUID templateVersionId, String sourceFingerprint,
                            JsonNode mappings, UUID actorId);

    void updateJobStatus(UUID organizationId, UUID importJobId, String status, int progress, String stage, String error);

    void updateCompatibility(UUID organizationId, UUID importJobId, String compatibilityStatus);

    void saveCompatibilityReport(UUID organizationId, UUID importJobId, String compatibilityStatus, JsonNode report);

    JsonNode findCompatibilityReport(UUID organizationId, UUID importJobId);

    void saveComponentOverride(UUID organizationId, UUID importJobId, ComponentOverride override);

    List<ComponentOverride> listComponentOverrides(UUID organizationId, UUID importJobId);

    void updateSheet(SheetUpdate update);

    void replaceMappings(UUID organizationId, UUID importJobId, List<Mapping> mappings);

    List<Sheet> listSheets(UUID organizationId, UUID importJobId);

    List<Mapping> listMappings(UUID organizationId, UUID importJobId);

    List<Row> listRows(UUID organizationId, UUID importJobId);

    void replaceValidation(UUID organizationId, UUID importJobId, List<Row> rows, List<Issue> issues, String status);

    List<Issue> listIssues(UUID organizationId, UUID importJobId);

    void resolveIssue(UUID organizationId, UUID issueId, UUID actorId, String status, String reason);

    void correctValue(UUID organizationId, UUID importJobId, UUID recordId, String bindingId,
                      String valuePath, JsonNode correctedValue, UUID actorId, String reason);

    void excludeRow(UUID organizationId, UUID importJobId, UUID recordId, boolean excluded,
                    UUID actorId, String reason);

    CommitResult commit(UUID organizationId, UUID importJobId, UUID actorId, List<CommittedRow> rows);

    record NewJob(UUID id, UUID organizationId, UUID sourceFileId, String sourceSha256, String sourceFileName,
                  String sourceFormat, UUID templateVersionId, UUID categoryId,
                  boolean duplicateOverride, UUID actorId, Integer importContractVersion, String contractHash) {
        public NewJob(UUID id, UUID organizationId, UUID sourceFileId, String sourceSha256, String sourceFileName,
                      String sourceFormat, UUID templateVersionId, UUID categoryId,
                      boolean duplicateOverride, UUID actorId) {
            this(id, organizationId, sourceFileId, sourceSha256, sourceFileName, sourceFormat, templateVersionId,
                    categoryId, duplicateOverride, actorId, null, null);
        }
        public NewJob(UUID id, UUID organizationId, UUID sourceFileId, String sourceSha256, String sourceFileName,
                      String sourceFormat, UUID templateVersionId, boolean duplicateOverride,
                      UUID actorId) {
            this(id, organizationId, sourceFileId, sourceSha256, sourceFileName, sourceFormat, templateVersionId,
                    null, duplicateOverride, actorId, null, null);
        }
    }

    record Job(UUID id, UUID sourceFileId, String sourceSha256, String sourceFileName, String sourceFormat,
               UUID templateVersionId, UUID categoryId, String status, int progress,
               String currentStage, String parserVersion, String errorMessage, Instant createdAt, Instant updatedAt,
               Integer importContractVersion, String contractHash, String compatibilityStatus) {
        public Job(UUID id, UUID sourceFileId, String sourceSha256, String sourceFileName, String sourceFormat,
                   UUID templateVersionId, UUID categoryId, String status, int progress,
                   String currentStage, String parserVersion, String errorMessage, Instant createdAt, Instant updatedAt) {
            this(id, sourceFileId, sourceSha256, sourceFileName, sourceFormat, templateVersionId,
                     categoryId, status, progress, currentStage, parserVersion, errorMessage, createdAt, updatedAt,
                     null, null, "LEGACY");
        }
        public Job(UUID id, UUID sourceFileId, String sourceSha256, String sourceFileName, String sourceFormat,
                   UUID templateVersionId, String status, int progress, String currentStage,
                   String parserVersion, String errorMessage, Instant createdAt, Instant updatedAt) {
            this(id, sourceFileId, sourceSha256, sourceFileName, sourceFormat, templateVersionId,
                     null, status, progress, currentStage, parserVersion, errorMessage, createdAt, updatedAt,
                     null, null, "LEGACY");
        }
    }

    record Sheet(UUID id, String sheetId, String sheetName, int sheetOrder, boolean selected,
                 List<Integer> headerRows, Integer dataStartRow, Integer dataEndRow, JsonNode structure,
                 String confirmationStatus) {}

    record SheetUpdate(UUID importJobId, String sheetId, boolean selected, List<Integer> headerRows,
                       Integer dataStartRow, Integer dataEndRow, String confirmationStatus) {}

    record Mapping(UUID id, String sheetId, String sourceColumn, String sourceHeader, String fieldCode,
                   String fieldName, String action, String valueType, String sourceUnit, String standardUnit,
                   JsonNode detail, String status) {}

    record Row(UUID id, String sheetId, int rowNumber, JsonNode rawValues, JsonNode normalizedValues,
               JsonNode correctedValues, String status, JsonNode sourceMetadata,
               boolean excluded, String exclusionReason) {
        public Row(UUID id, String sheetId, int rowNumber, JsonNode rawValues, JsonNode normalizedValues,
                   JsonNode correctedValues, String status) {
            this(id, sheetId, rowNumber, rawValues, normalizedValues, correctedValues, status,
                    JsonNodeFactory.instance.objectNode(), false, null);
        }
        public Row(UUID id, String sheetId, int rowNumber, JsonNode rawValues, JsonNode normalizedValues,
                   JsonNode correctedValues, String status, JsonNode sourceMetadata) {
            this(id, sheetId, rowNumber, rawValues, normalizedValues, correctedValues, status,
                    sourceMetadata, false, null);
        }
    }

    record Issue(UUID id, String sheetId, String fieldCode, String severity, String issueType,
                 Integer rowNumber, String column, String address, String message, JsonNode detail, String status) {}

    record ComponentOverride(String componentId, String sheetId, String sourceRange,
                             String reason, UUID actorId, Instant updatedAt) {}

    record CommittedRow(String sheetId, String sheetName, int rowNumber, JsonNode raw, JsonNode normalized, JsonNode corrected,
                        String recordKey, List<Anchor> anchors) {}

    record CommitResult(List<CommittedRecord> records, int rowCount) {}

    record CommittedRecord(UUID recordId, String recordKey, String dataHash) {}

    record Anchor(String fieldCode, String bindingId, String valuePath, String labelPath,
                  String valueSource, String valueStatus, String sheetId, String sheetName,
                  int rowNumber, int columnNumber, String columnName, String address, JsonNode rawValue) {}

    record SourceAnchor(UUID id, UUID recordId, String fieldCode, String bindingId,
                        String valuePath, String labelPath, String valueSource, String valueStatus,
                        UUID fileId, String sheetId, String sheetName, Integer rowNumber,
                        Integer columnNumber, String columnName, String address, JsonNode rawValue) {}

    record SourceFile(UUID importJobId, UUID fileObjectId, String originalName, String sourceFormat,
                      UUID templateVersionId, UUID categoryId, String categoryName, String status,
                      int progress, Instant createdAt, Instant updatedAt, int sheetCount,
                      int recordCount, int fieldCount) {}
}
