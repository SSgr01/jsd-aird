package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.application.port.ProjectMaterialRepository;
import com.jsd.aird.mdm.domain.model.ProjectMaterial;
import com.jsd.aird.mdm.infrastructure.model.ProjectMaterialProjection;
import com.jsd.aird.mdm.infrastructure.model.ProjectMaterialRow;
import com.jsd.aird.shared.api.PageResponse;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public class MyBatisProjectMaterialRepository implements ProjectMaterialRepository {

    private final ProjectMaterialMapper mapper;

    public MyBatisProjectMaterialRepository(ProjectMaterialMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResponse<ProjectMaterial> listByProject(UUID projectId, String category, String stage, String status, String keyword, int page, int size) {
        int offset = (Math.max(page, 1) - 1) * size;
        var rows = mapper.listByProject(projectId, category, stage, status, keyword, offset, size);
        var total = mapper.countByProject(projectId, category, stage, status, keyword);
        return new PageResponse<>(rows.stream().map(this::toDomain).toList(), page, size, total, (total + size - 1) / size);
    }

    @Override
    public void link(UUID projectId, UUID materialId, String operator, String remark) {
        mapper.insert(new ProjectMaterialRow(UUID.randomUUID(), projectId, materialId));
    }

    @Override
    public boolean updateRemark(UUID linkId, String remark) {
        return mapper.updateRemark(new ProjectMaterialRow(linkId, null, null)) > 0;
    }

    @Override
    public boolean unlink(UUID projectId, UUID materialId) {
        return mapper.unlink(projectId, materialId) > 0;
    }

    @Override
    public void saveAssociations(UUID projectId, java.util.Set<UUID> materialIds, String operator) {
        var existing = mapper.findLinkedMaterialIds(projectId);
        var toRemove = existing.stream().filter(id -> !materialIds.contains(id)).toList();
        for (var id : toRemove) {
            mapper.unlink(projectId, id);
        }
        var toAdd = materialIds.stream().filter(id -> !existing.contains(id)).toList();
        for (var id : toAdd) {
            mapper.insert(new ProjectMaterialRow(UUID.randomUUID(), projectId, id));
        }
    }

    private ProjectMaterial toDomain(ProjectMaterialProjection p) {
        return new ProjectMaterial(
                p.id(),
                p.projectId(),
                p.materialId(),
                p.mCode(),
                p.mName(),
                p.mCategory(),
                p.mSourceCategory(),
                p.mSourceModule(),
                p.mStage(),
                p.mContactPerson(),
                p.mStatus(),
                p.createdAt()
        );
    }
}