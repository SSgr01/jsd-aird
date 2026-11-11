package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.port.MaterialRepository;
import com.jsd.aird.mdm.application.port.ProjectMaterialRepository;
import com.jsd.aird.mdm.domain.model.ProjectMaterial;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProjectMaterialService {

    private final ProjectMaterialRepository repository;
    private final MaterialRepository materialRepository;
    private final ProjectService projectAccess;

    @Autowired
    public ProjectMaterialService(ProjectMaterialRepository repository, MaterialRepository materialRepository,
                                  ProjectService projectAccess) {
        this.repository = repository;
        this.materialRepository = materialRepository;
        this.projectAccess = projectAccess;
    }

    public ProjectMaterialService(ProjectMaterialRepository repository, MaterialRepository materialRepository) {
        this(repository, materialRepository, null);
    }

    public PageResponse<ProjectMaterial> listByProject(UUID projectId, String category, String stage, String status, String keyword, int page, int size) {
        if (projectAccess != null) projectAccess.get(projectId);
        return repository.listByProject(projectId, category, stage, status, keyword, Math.max(page, 1), Math.min(Math.max(size, 1), 200));
    }

    @Transactional
    public void link(UUID projectId, UUID materialId, String remark) {
        requireWrite(projectId);
        if (materialRepository.findById(materialId).isEmpty()) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "资料不存在，无法关联");
        }
        repository.link(projectId, materialId, CrmServiceSupport.operator(), remark);
    }

    @Transactional
    public void unlink(UUID projectId, UUID materialId) {
        requireWrite(projectId);
        if (!repository.unlink(projectId, materialId)) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "关联不存在");
        }
    }

    @Transactional
    public void saveAssociations(UUID projectId, java.util.Set<UUID> materialIds) {
        requireWrite(projectId);
        repository.saveAssociations(projectId, materialIds, CrmServiceSupport.operator());
    }

    private void requireWrite(UUID projectId) {
        if (projectAccess != null) projectAccess.requireAccess(projectId, "project.assign");
    }
}
