package com.jsd.aird.tpl.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.jsd.aird.tpl.domain.TargetDataType;
import com.jsd.aird.tpl.domain.TemplateScope;
import com.jsd.aird.tpl.domain.TemplateStatus;

public interface TemplateRepository {

    List<TemplateListItem> findTemplates(
            UUID organizationId,
            String keyword,
            TemplateFormat format,
            TemplateStatus status
    );

    List<TemplateListItem> findPublishedDataTemplates(UUID organizationId, TargetDataType targetDataType);

    Optional<TemplateWorkspace> findPublishedDataTemplate(UUID organizationId, UUID versionId);

    void insertTemplate(NewTemplate template);

    void insertVersion(NewVersion version);

    void insertRevision(NewRevision version);

    void copyMappings(UUID sourceVersionId, UUID targetVersionId);

    boolean hasOpenDraft(UUID organizationId, UUID templateId);

    boolean hasProductionOrderReferences(UUID organizationId, UUID versionId);

    int deleteDraft(UUID organizationId, UUID versionId);

    int deleteTemplateIfEmpty(UUID organizationId, UUID templateId);

    int retireTemplate(UUID organizationId, UUID templateId);

    List<TemplateCategoryItem> findCategories(UUID organizationId);

    void insertCategory(UUID id, UUID organizationId, String name, int sortOrder, UUID actorId);

    Optional<TemplateCategoryItem> findCategory(UUID organizationId, UUID categoryId);

    boolean categoryNameExists(UUID organizationId, String name, UUID excludingId);

    int renameCategory(UUID organizationId, UUID categoryId, String name);

    int deleteCategory(UUID organizationId, UUID categoryId, UUID replacementCategoryId);

    int assignTemplateCategory(UUID organizationId, UUID templateId, UUID categoryId);

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
            String purpose,
            String category,
            TemplateFormat format,
            TemplateStatus status,
            TemplateScope scope,
            TargetDataType targetDataType,
            int versionNo,
            long lockVersion,
            Instant updatedAt,
            int issueCount
    ) {
    }

    record TemplateCategoryItem(UUID id, String name, int sortOrder, int templateCount) {
    }

    record NewTemplate(
            UUID id,
            UUID organizationId,
            String code,
            String name,
            String purpose,
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
            TemplateScope scope,
            TargetDataType targetDataType,
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
            TemplateScope scope,
            TargetDataType targetDataType,
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
            TemplateScope scope,
            TargetDataType targetDataType,
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
            int saveCount
    ) {
    }

    record FileReference(UUID id, String status, String sha256) {
    }

    record StructureChange(UUID operationId, String type, String sheetId, JsonNode operation, String source) {
    }

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
