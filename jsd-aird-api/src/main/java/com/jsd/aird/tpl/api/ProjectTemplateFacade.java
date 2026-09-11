package com.jsd.aird.tpl.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * Published template contract consumed by project-detail pages.
 *
 * <p>The project module must not depend on template-center application or
 * persistence classes. This contract keeps the project endpoint project-scoped
 * while the template module remains the owner of publication and snapshot
 * loading rules.</p>
 */
public interface ProjectTemplateFacade {

    List<ProjectTemplateOption> listPublishedForProject(UUID organizationId);

    ProjectTemplateEditModel getPublishedEditModel(UUID organizationId, UUID versionId);

    record ProjectTemplateOption(
            UUID templateId,
            UUID versionId,
            String templateCode,
            String name,
            String category,
            int versionNo,
            String format
    ) {
    }

    record ProjectTemplateEditModel(
            UUID templateId,
            UUID versionId,
            String templateCode,
            String name,
            String format,
            JsonNode snapshot,
            String snapshotHash
    ) {
    }
}
