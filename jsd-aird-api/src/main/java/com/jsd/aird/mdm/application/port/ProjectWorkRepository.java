package com.jsd.aird.mdm.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.mdm.application.query.ProjectTaskQuery;
import com.jsd.aird.mdm.application.query.ProjectTaskSummary;
import com.jsd.aird.mdm.domain.model.ProjectExperiment;
import com.jsd.aird.mdm.domain.model.ProjectTask;

/** Outbound port for project task and experiment persistence. */
public interface ProjectWorkRepository {

    List<ProjectTask> tasks(UUID stageId);

    boolean stageBelongs(UUID stageId, UUID projectId);

    void insertTask(ProjectTask task);

    Optional<ProjectTask> task(UUID id);

    List<ProjectExperiment> experiments(UUID taskId);

    int updateTask(ProjectTask task);

    void insertExperiment(ProjectExperiment experiment);

    Optional<ProjectExperiment> experiment(UUID id);

    int updateExperiment(UUID id, String title, String category, String owner,
                         LocalDate experimentDate, long version);

    int deleteExperiment(UUID id, long version);

    List<ProjectTaskSummary> findTaskPage(ProjectTaskQuery query, long offset, int limit);

    long countTasks(ProjectTaskQuery query);

    List<String> findTaskOwners();
}
