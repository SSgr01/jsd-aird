package com.jsd.aird.mdm.application.port;

import com.jsd.aird.mdm.application.query.ProjectQuery;
import com.jsd.aird.mdm.domain.model.Project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {

    List<Project> findPage(ProjectQuery query);

    long count(ProjectQuery query);

    Optional<Project> findById(UUID id);

    List<Project> findAllByIds(List<UUID> ids);

    boolean existsByProjectCode(String projectCode, UUID excludedId);

    void insert(Project project, String operator);

    boolean update(Project project, long version, String operator);

    boolean softDelete(UUID id, String operator);

    String nextProjectCode(int year);
}
