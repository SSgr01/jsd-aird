package com.jsd.aird.data.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.tpl.api.TemplateDataImportFacade;

public interface DataProjectionRepository {

    ProjectionResult project(UUID organizationId, UUID importJobId, UUID actorId,
                             UUID templateVersionId, List<UUID> recordIds,
                             List<TemplateDataImportFacade.ImportBinding> bindings);

    Optional<TrainingDataset> findLatestDataset(UUID organizationId, UUID importJobId);

    Optional<TrainingDataset> findDataset(UUID organizationId, UUID datasetId);

    List<LongTableRow> previewRows(UUID organizationId, UUID importJobId, int limit);

    void retireDatasets(UUID organizationId, UUID importJobId, UUID exceptDatasetId);

    void updateDatasetStatus(UUID organizationId, UUID datasetId, String status, UUID actorId);

    record ProjectionResult(UUID datasetId, int recordCount, int longValueCount, int eligibleRecordCount) {}

    record TrainingDataset(UUID id, UUID importJobId, UUID templateVersionId, String projectionVersion,
                           String name, String status, JsonNode schema, JsonNode qualitySummary,
                           JsonNode sourceRecordIds, int recordCount, int eligibleRecordCount) {}

    record LongTableRow(String recordKey, JsonNode dimensions, JsonNode measures, JsonNode source,
                        boolean trainingEligible, String exclusionReason) {}
}
