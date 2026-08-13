package com.jsd.aird.tpl.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.jsd.aird.tpl.domain.TemplateStatus;

public interface TemplateRepository {

    List<TemplateListItem> findTemplates(
            UUID organizationId,
            String keyword,
            TemplateFormat format,
            TemplateStatus status
    );

    TemplatePage findTemplates(TemplateQuery query);

    TemplateFacetSummary findTemplateFacets(TemplateFacetQuery query);

    List<TemplateCreatorOption> findTemplateCreators(UUID organizationId);

    Optional<TemplateSummary> findTemplateSummary(UUID organizationId, UUID templateId);

    List<TemplateListItem> findPublishedDataTemplates(UUID organizationId);

    Optional<TemplateWorkspace> findPublishedDataTemplate(UUID organizationId, UUID versionId);

    void insertTemplate(NewTemplate template);

    void insertVersion(NewVersion version);

    void insertRevision(NewRevision version);

    void insertCopiedVersion(NewRevision version);

    void copyMappings(UUID sourceVersionId, UUID targetVersionId, boolean preserveRecognitionReference);

    boolean hasOpenDraft(UUID organizationId, UUID templateId);

    boolean hasProductionOrderReferences(UUID organizationId, UUID versionId);

    int deleteDraft(UUID organizationId, UUID versionId);

    int deleteTemplateIfEmpty(UUID organizationId, UUID templateId);

    int retireTemplate(UUID organizationId, UUID templateId);

    List<TemplateCategoryItem> findCategories(UUID organizationId);

    void insertCategory(UUID id, UUID organizationId, String name, String description, int sortOrder, UUID actorId);

    Optional<TemplateCategoryItem> findCategory(UUID organizationId, UUID categoryId);

    boolean categoryNameExists(UUID organizationId, String name, UUID excludingId);

    int renameCategory(UUID organizationId, UUID categoryId, String name, String description);

    int deleteCategory(UUID organizationId, UUID categoryId, UUID replacementCategoryId);

    int assignTemplateCategory(UUID organizationId, UUID templateId, UUID categoryId);

    int renameTemplate(UUID organizationId, UUID templateId, String name);

    void ensureCategory(UUID organizationId, String name, UUID actorId);

    Optional<TemplateWorkspace> findWorkspace(UUID organizationId, UUID versionId);

    List<TemplateVersionHistoryItem> findVersionHistory(UUID organizationId, UUID templateId);

    Optional<FileReference> findFile(UUID organizationId, UUID fileId);

    int updateDraft(DraftUpdate update);

    void replaceMappings(UUID versionId, TemplateFormat format, JsonNode mappings);

    void appendStructureChanges(
            UUID versionId,
            String beforeMappingHash,
            String afterMappingHash,
            List<StructureChange> operations,
            UUID actorId
    );

    boolean hasUnresolvedBlockers(UUID versionId);

    void publish(UUID organizationId, UUID versionId, UUID actorId);

    void saveImportContract(UUID organizationId, UUID versionId, int importContractVersion,
                            int layoutStructureVersion, String contractHash, JsonNode contract, UUID actorId);

    Optional<ImportContract> findImportContract(UUID organizationId, UUID versionId);

    int updatePublishedWordDocument(UUID organizationId, UUID versionId, JsonNode wordDocument);

    void appendAudit(
            UUID organizationId,
            UUID actorId,
            String action,
            String aggregateType,
            UUID aggregateId,
            JsonNode detail
    );

    void appendOutbox(String aggregateType, UUID aggregateId, String eventType, JsonNode payload);

    record TemplateListItem(
            UUID templateId,
            UUID versionId,
            String templateCode,
            String name,
            String category,
            TemplateFormat format,
            TemplateStatus status,
            int versionNo,
            long lockVersion,
            Instant updatedAt,
            int issueCount,
            UUID categoryId,
            UUID currentPublishedVersionId,
            Integer currentPublishedVersionNo,
            Integer retiredVersionNo,
            UUID draftVersionId,
            Integer draftVersionNo,
            boolean hasDraft,
            UUID createdBy,
            String createdByName,
            Instant createdAt
    ) {
    }

    record TemplateQuery(
            UUID organizationId,
            String keyword,
            UUID categoryId,
            boolean uncategorized,
            TemplateFormat format,
            TemplateStatus status,
            UUID createdBy,
            Instant updatedFrom,
            Instant updatedTo,
            String sortBy,
            String sortDirection,
            int page,
            int size
    ) {}

    record TemplateFacetQuery(
            UUID organizationId,
            String keyword,
            TemplateFormat format,
            TemplateStatus status,
            UUID createdBy,
            Instant updatedFrom,
            Instant updatedTo
    ) {}

    record TemplatePage(List<TemplateListItem> items, long total, int page, int size, int totalPages) {}

    record TemplateFacetSummary(
            long totalCount,
            long uncategorizedCount,
            List<TemplateCategoryCount> categoryCounts
    ) {}

    record TemplateCategoryCount(UUID categoryId, long count) {}

    record TemplateCreatorOption(UUID id, String displayName) {}

    record TemplateSummary(UUID id, String name, UUID categoryId, String category) {}

    record TemplateCategoryItem(UUID id, String name, String description, int sortOrder, int templateCount) {
    }

    record NewTemplate(
            UUID id,
            UUID organizationId,
            String code,
            String name,
            String category,
            TemplateFormat format,
            UUID actorId
    ) {
    }

    record NewVersion(
            UUID id,
            UUID templateId,
            JsonNode schema,
            JsonNode layoutSummary,
            String snapshotKind,
            String editorAppVersion,
            String pluginManifestHash,
            String schemaHash,
            String mappingHash,
            String dataHash,
            String workspaceHash,
            UUID actorId
    ) {
    }

    record NewRevision(
            UUID id,
            UUID templateId,
            UUID derivedFromVersionId,
            JsonNode schema,
            JsonNode layoutSummary,
            UUID snapshotFileId,
            String snapshotHash,
            String snapshotKind,
            String editorAppVersion,
            String pluginManifestHash,
            int snapshotFormatVersion,
            String schemaHash,
            String mappingHash,
            String dataHash,
            String workspaceHash,
            UUID actorId
    ) {
    }

    record TemplateWorkspace(
            UUID templateId,
            UUID versionId,
            UUID recognitionRunId,
            String templateCode,
            String name,
            TemplateFormat format,
            TemplateStatus status,
            int versionNo,
            JsonNode schema,
            JsonNode mapping,
            JsonNode data,
            JsonNode documentStructure,
            JsonNode wordDocument,
            JsonNode inlineSnapshot,
            UUID snapshotFileId,
            String snapshotHash,
            String snapshotKind,
            String editorAppVersion,
            String pluginManifestHash,
            int snapshotFormatVersion,
            String schemaHash,
            String mappingHash,
            String dataHash,
            String workspaceHash,
            long lockVersion,
            boolean reconciliationRequired
    ) {
    }

    record TemplateVersionHistoryItem(
            UUID versionId,
            int versionNo,
            TemplateStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            int saveCount,
            UUID derivedFromVersionId,
            UUID createdBy,
            String createdByName,
            boolean currentPublished,
            boolean canRollback
    ) {
    }

    record FileReference(UUID id, String status, String sha256) {
    }

    record StructureChange(UUID operationId, String type, String sheetId, JsonNode operation, String source) {
    }

    record ImportContract(int importContractVersion, int layoutStructureVersion,
                          String contractHash, JsonNode contract) {}

    record DraftUpdate(
            UUID organizationId,
            UUID versionId,
            long expectedLockVersion,
            JsonNode schema,
            JsonNode layoutSummary,
            UUID snapshotFileId,
            String snapshotHash,
            String editorAppVersion,
            String pluginManifestHash,
            int snapshotFormatVersion,
            String schemaHash,
            String mappingHash,
            String dataHash,
            String workspaceHash,
            boolean reconciliationRequired,
            UUID actorId
    ) {
    }
}
