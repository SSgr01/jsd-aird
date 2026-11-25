package com.jsd.aird.rnd.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The only cross-module write boundary for creating experiment drafts.
 * Callers provide source facts; the RND module owns numbering, V2 normalization,
 * category validation, version creation and audit events.
 */
public interface ExperimentDraftFacade {

    CategoryRef requireActiveCategory(UUID organizationId, UUID categoryId);

    ImportedDraft createImportedDraft(ImportedDraftCommand command);

    PreparedImportedDraft prepareImportedDraft(ImportedDraftCommand command);

    ImportedDraft createPreparedImportedDraft(PreparedImportedDraft prepared);

    /** Internal RND preparation result; never accepted from an HTTP request. */
    record PreparedImportedDraft(UUID experimentId, UUID versionId, ImportedDraftCommand command, JsonNode editModel) {}

    ImportedDraft createResearchDraft(ResearchDraftCommand command);

    record CategoryRef(UUID id, String code, String name) {}

    record ImportedDraftCommand(
            UUID organizationId,
            UUID actorId,
            String actorName,
            String title,
            UUID categoryId,
            String sourceType,
            UUID projectId,
            UUID stageId,
            UUID taskId,
            String ownerName,
            LocalDate experimentDate,
            UUID templateVersionId,
            String templateSnapshotHash,
            JsonNode templateSnapshot,
            JsonNode editModel,
            JsonNode importConfirmation
    ) {
        public ImportedDraftCommand(UUID organizationId,UUID actorId,String actorName,String title,UUID categoryId,String sourceType,
                UUID projectId,UUID stageId,UUID taskId,String ownerName,LocalDate experimentDate,UUID templateVersionId,
                String templateSnapshotHash,JsonNode templateSnapshot,JsonNode editModel) {
            this(organizationId,actorId,actorName,title,categoryId,sourceType,projectId,stageId,taskId,ownerName,experimentDate,
                    templateVersionId,templateSnapshotHash,templateSnapshot,editModel,null);
        }
    }

    record ResearchDraftCommand(
            String title,
            UUID categoryId,
            UUID projectId,
            UUID stageId,
            UUID taskId,
            String ownerName,
            LocalDate plannedExperimentDate,
            UUID templateVersionId,
            String templateSnapshotHash,
            JsonNode templateSnapshot,
            JsonNode editModel
    ) {}

    record ImportedDraft(UUID experimentId, UUID experimentVersionId, String experimentNo, String title) {}
}
