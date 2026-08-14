package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.application.port.ProjectStageRepository;
import com.jsd.aird.mdm.application.query.ProjectStageQuery;
import com.jsd.aird.mdm.domain.model.ProjectStage;
import com.jsd.aird.mdm.domain.model.StageStatus;
import com.jsd.aird.mdm.infrastructure.model.ProjectStageRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisProjectStageRepository implements ProjectStageRepository {
    private final ProjectStageMapper mapper;

    public MyBatisProjectStageRepository(ProjectStageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ProjectStage> findByProject(UUID projectId) {
        return mapper.findByProject(projectId).stream().map(MyBatisProjectStageRepository::map).toList();
    }

    @Override
    public Optional<ProjectStage> findById(UUID id) {
        return mapper.findById(id).map(MyBatisProjectStageRepository::map);
    }

    @Override
    public List<ProjectStage> findPage(ProjectStageQuery q) {
        return mapper.findPage(q.keyword(), q.projectId(), name(q.status()), q.owner(), q.plannedFrom(), q.plannedTo(), q.offset(), q.size()).stream().map(MyBatisProjectStageRepository::map).toList();
    }

    @Override
    public long count(ProjectStageQuery q) {
        return mapper.count(q.keyword(), q.projectId(), name(q.status()), q.owner(), q.plannedFrom(), q.plannedTo());
    }

    @Override
    public boolean existsActiveName(UUID projectId, String name, UUID excludedId) {
        return mapper.existsActiveName(projectId, name, excludedId);
    }

    @Override
    public int nextOrderNo(UUID projectId) {
        return mapper.nextOrderNo(projectId);
    }

    @Override
    public void insert(ProjectStage s, String operator) {
        mapper.insert(s.id(), s.projectId(), s.stageCode(), s.name(), s.orderNo(), s.status().name(), s.owner(),
            s.description(), s.plannedStart(), s.plannedEnd(), s.actualStart(), s.actualEnd(), Instant.now(), operator);
    }

    @Override
    public boolean update(ProjectStage s, long expectedVersion, String operator) {
        return mapper.update(s.id(), s.stageCode(), s.name(), s.status().name(), s.owner(), s.description(),
            s.plannedStart(), s.plannedEnd(), s.actualStart(), s.actualEnd(), expectedVersion, operator) == 1;
    }

    @Override
    public boolean softDelete(UUID id, long expectedVersion, String operator) {
        return mapper.softDelete(id, expectedVersion, operator) == 1;
    }

    @Override
    public void reorder(UUID projectId, List<ProjectStage> stages, String operator) {
        mapper.parkOrderNumbers(projectId);
        for (int index = 0; index < stages.size(); index++) {
            ProjectStage stage = stages.get(index);
            if (mapper.updateOrder(stage.id(), projectId, index + 1, stage.version(), operator) != 1) {
                throw new IllegalStateException("Concurrent stage reorder detected");
            }
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static ProjectStage map(ProjectStageRow r) {
        return new ProjectStage(r.id(), r.projectId(), r.projectCode(), r.projectName(), r.stageCode(), r.name(),
            r.orderNo(), StageStatus.valueOf(r.status()), r.owner(), r.description(), r.plannedStart(), r.plannedEnd(),
            r.actualStart(), r.actualEnd(), r.taskCount(), r.openTaskCount(), r.version(), r.createdAt(), r.updatedAt());
    }
}
