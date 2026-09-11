package com.jsd.aird.mdm.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.mdm.application.query.ProjectTaskQuery;
import com.jsd.aird.mdm.application.query.ProjectTaskSummary;
import com.jsd.aird.mdm.domain.model.ProjectTask;

/** Outbound port for project task persistence. */
public interface ProjectWorkRepository {

    List<ProjectTask> tasks(UUID stageId);

    boolean stageBelongs(UUID stageId, UUID projectId);

    void insertTask(ProjectTask task);

    Optional<ProjectTask> task(UUID id);

    int updateTask(ProjectTask task);

    List<ProjectTaskSummary> findTaskPage(ProjectTaskQuery query, long offset, int limit);

    long countTasks(ProjectTaskQuery query);

    List<String> findTaskOwners();
}
