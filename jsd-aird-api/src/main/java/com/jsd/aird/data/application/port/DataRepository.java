package com.jsd.aird.data.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public interface DataRepository {

    void insertJob(NewJob job);

    void enqueueParse(UUID asyncJobId, UUID organizationId, UUID importJobId);

    Optional<Job> findJob(UUID organizationId, UUID importJobId);

    Optional<Job> findJobForUpdate(UUID organizationId, UUID importJobId);

    Optional<Job> findCompletedDuplicate(UUID organizationId, String sha256, UUID templateVersionId);

    void saveParsed(UUID importJobId, String parserVersion, List<Sheet> sheets, List<Mapping> mappings,
                    List<Row> rows, UUID asyncJobId);

    void updateJobStatus(UUID organizationId, UUID importJobId, String status, int progress, String stage, String error);

    void updateSheet(SheetUpdate update);

    void replaceMappings(UUID organizationId, UUID importJobId, List<Mapping> mappings);

    List<Sheet> listSheets(UUID organizationId, UUID importJobId);

    List<Mapping> listMappings(UUID organizationId, UUID importJobId);

    List<Row> listRows(UUID organizationId, UUID importJobId);

    void replaceValidation(UUID organizationId, UUID importJobId, List<Row> rows, List<Issue> issues, String status);

    List<Issue> listIssues(UUID organizationId, UUID importJobId);

    void resolveIssue(UUID organizationId, UUID issueId, UUID actorId, String status);

    CommitResult commit(UUID organizationId, UUID importJobId, UUID actorId, List<CommittedRow> rows);

    List<Asset> listAssets(UUID organizationId, String targetDataType, String keyword);

    Optional<AssetDetail> findAsset(UUID organizationId, UUID assetId);

    List<Revision> listRevisions(UUID organizationId, UUID assetId);

    List<SourceAnchor> listSourceAnchors(UUID organizationId, UUID assetId);

    record NewJob(UUID id, UUID organizationId, UUID sourceFileId, String sourceSha256, String sourceFileName,
                  String sourceFormat, UUID templateVersionId, String targetDataType, boolean duplicateOverride,
                  UUID actorId) {}

    record Job(UUID id, UUID sourceFileId, String sourceSha256, String sourceFileName, String sourceFormat,
               UUID templateVersionId, String targetDataType, String status, int progress, String currentStage,
               String parserVersion, String errorMessage, Instant createdAt, Instant updatedAt) {}

    record Sheet(UUID id, String sheetId, String sheetName, int sheetOrder, boolean selected,
                 List<Integer> headerRows, Integer dataStartRow, Integer dataEndRow, JsonNode structure,
                 String confirmationStatus) {}

    record SheetUpdate(UUID importJobId, String sheetId, boolean selected, List<Integer> headerRows,
                       Integer dataStartRow, Integer dataEndRow, String confirmationStatus) {}

    record Mapping(UUID id, String sheetId, String sourceColumn, String sourceHeader, String fieldCode,
                   String fieldName, String action, String valueType, String sourceUnit, String standardUnit,
                   JsonNode detail, String status) {}

    record Row(UUID id, String sheetId, int rowNumber, JsonNode rawValues, JsonNode normalizedValues,
               JsonNode correctedValues, String status) {}

    record Issue(UUID id, String sheetId, String fieldCode, String severity, String issueType,
                 Integer rowNumber, String column, String address, String message, JsonNode detail, String status) {}

    record CommittedRow(String sheetId, int rowNumber, JsonNode raw, JsonNode normalized, JsonNode corrected,
                        String assetKey, String displayName, List<Anchor> anchors) {}

    record CommitResult(List<CommittedAsset> assets, int rowCount) {}

    record CommittedAsset(UUID assetId, UUID revisionId, int revisionNo, String dataHash) {}

    record Anchor(String fieldCode, String sheetId, String sheetName, int rowNumber, int columnNumber,
                  String columnName, String address, JsonNode rawValue) {}

    record Asset(UUID id, String targetDataType, String assetKey, String displayName,
                 UUID currentRevisionId, String status, Instant updatedAt) {}

    record AssetDetail(UUID id, String targetDataType, String assetKey, String displayName,
                       UUID currentRevisionId, String status, JsonNode rawData, JsonNode normalizedData,
                       JsonNode correctedData, UUID importJobId, UUID templateVersionId,
                       Integer currentRevisionNo, String sourceSha256, Instant updatedAt) {}

    record Revision(UUID id, int revisionNo, UUID importJobId, UUID templateVersionId,
                    String dataHash, Instant createdAt) {}

    record SourceAnchor(UUID id, UUID revisionId, String fieldCode, UUID fileId, String sheetId,
                        String sheetName, Integer rowNumber, Integer columnNumber, String columnName,
                        String address, JsonNode rawValue) {}
}
