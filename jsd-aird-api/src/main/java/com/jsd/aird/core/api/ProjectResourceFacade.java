package com.jsd.aird.core.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.security.Actor;

public interface ProjectResourceFacade {

    List<RelatedProjectView> links(Actor actor, ResourceType resourceType, UUID resourceId);

    Map<UUID, List<RelatedProjectView>> links(Actor actor, ResourceType resourceType,
                                              Collection<UUID> resourceIds);

    Set<UUID> resourceIdsForProject(Actor actor, ResourceType resourceType, UUID projectId);

    List<RelatedProjectView> replaceLinks(Actor actor, ResourceType resourceType, UUID resourceId,
                                          List<ProjectRelationTarget> targets);

    List<ReferenceView> addReferences(Actor actor, ResourceType resourceType, UUID resourceId,
                                      String summary, List<ProjectRelationTarget> targets);

    PageResponse<ReferenceView> references(Actor actor, UUID projectId, ReferenceQuery query);

    void removeReference(Actor actor, UUID referenceId);

    ReferenceView restoreReference(Actor actor, UUID referenceId);

    enum ResourceType {
        KNOWLEDGE_DOCUMENT,
        DATA_IMPORT_JOB
    }

    record ProjectRelationTarget(UUID projectId, UUID stageId, UUID taskId) { }

    record RelatedProjectView(UUID projectId, String projectCode, String projectName,
                              UUID stageId, String stageName, UUID taskId, String taskName) { }

    record ReferenceQuery(String keyword, String sourceModule, UUID stageId, UUID taskId,
                          UUID addedBy, String status, int page, int size) { }

    record ReferenceView(UUID id, UUID projectId, UUID stageId, String stageName,
                         UUID taskId, String taskName,
                         ResourceType resourceType, UUID resourceId, UUID fileVersionId,
                         UUID fileObjectId, String sourceModule, String title, String originalName,
                         String contentType, long size, String summary, String status,
                         UUID addedBy, String addedByName, Instant addedAt,
                         UUID removedBy, Instant removedAt, boolean sourceAvailable) { }
}
