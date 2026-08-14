package com.jsd.aird.mdm.application.port;

import com.jsd.aird.mdm.application.query.ProjectStageQuery;
import com.jsd.aird.mdm.domain.model.ProjectStage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectStageRepository {
    List<ProjectStage> findByProject(UUID projectId);
    Optional<ProjectStage> findById(UUID id);
    List<ProjectStage> findPage(ProjectStageQuery query);
    long count(ProjectStageQuery query);
    boolean existsActiveName(UUID projectId, String name, UUID excludedId);
    int nextOrderNo(UUID projectId);
    void insert(ProjectStage stage, String operator);
    boolean update(ProjectStage stage, long expectedVersion, String operator);
    boolean softDelete(UUID id, long expectedVersion, String operator);
    void reorder(UUID projectId, List<ProjectStage> current, String operator);
}
