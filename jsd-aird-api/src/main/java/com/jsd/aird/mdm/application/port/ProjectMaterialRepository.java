package com.jsd.aird.mdm.application.port;

import com.jsd.aird.mdm.domain.model.ProjectMaterial;
import com.jsd.aird.shared.api.PageResponse;

import java.util.UUID;

public interface ProjectMaterialRepository {
    PageResponse<ProjectMaterial> listByProject(UUID projectId, String category, String stage, String status, String keyword, int page, int size);

    void link(UUID projectId, UUID materialId, String operator, String remark);

    boolean updateRemark(UUID linkId, String remark);

    boolean unlink(UUID projectId, UUID materialId);

    void saveAssociations(UUID projectId, java.util.Set<UUID> materialIds, String operator);
}