package com.jsd.aird.mdm.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mdm.application.port.ProjectRepository;
import com.jsd.aird.mdm.application.query.ProjectQuery;
import com.jsd.aird.mdm.domain.model.Project;
import com.jsd.aird.mdm.domain.model.ProjectPriority;
import com.jsd.aird.mdm.domain.model.ProjectStatus;
import com.jsd.aird.mdm.infrastructure.model.ProjectRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisProjectRepository implements ProjectRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProjectMapper mapper;

    public MyBatisProjectRepository(ProjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<Project> findPage(ProjectQuery query) {
        return mapper.findPage(query.keyword(), query.owner(), name(query.priority()), name(query.status()),
                query.partnerId(), query.startDateFrom(), query.startDateTo(), query.offset(), query.size())
            .stream().map(MyBatisProjectRepository::toProject).toList();
    }

    @Override
    public long count(ProjectQuery query) {
        return mapper.count(query.keyword(), query.owner(), name(query.priority()), name(query.status()),
            query.partnerId(), query.startDateFrom(), query.startDateTo());
    }

    @Override
    public Optional<Project> findById(UUID id) {
        return mapper.findById(id).map(MyBatisProjectRepository::toProject);
    }

    @Override
    public List<Project> findAllByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return mapper.findAllByIds(ids).stream().map(MyBatisProjectRepository::toProject).toList();
    }

    @Override
    public boolean existsByProjectCode(String projectCode, UUID excludedId) {
        return mapper.existsByProjectCode(projectCode, excludedId);
    }

    @Override
    public void insert(Project project, String operator) {
        mapper.insert(project.id(), project.projectCode(), project.name(), project.partnerId(),
            project.partnerName(), project.owner(), project.startDate(), project.endDate(),
            project.priority().name(), project.status().name(), project.teamSize(), project.background(),
            json(project.customFields()), json(project.teamMembers()), Instant.now(), operator);
    }

    @Override
    public boolean update(Project project, long version, String operator) {
        return mapper.update(project.id(), project.projectCode(), project.name(), project.partnerId(),
            project.partnerName(), project.owner(), project.startDate(), project.endDate(),
            project.priority().name(), project.status().name(), project.teamSize(), project.background(),
            json(project.customFields()), json(project.teamMembers()), version, operator) == 1;
    }

    @Override
    public boolean softDelete(UUID id, String operator) {
        return mapper.softDelete(id, operator) == 1;
    }

    @Override
    public String nextProjectCode(int year) {
        var prefix = "JSD-PM-" + year + "-";
        return prefix + String.format("%03d", mapper.maxSequence(prefix) + 1);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Project toProject(ProjectRow r) {
        return new Project(r.id(), r.projectCode(), r.name(), r.partnerId(), r.partnerName(), r.owner(),
            r.startDate(), r.endDate(), ProjectPriority.valueOf(r.priority()), ProjectStatus.valueOf(r.status()),
            r.teamSize(), r.background(), parse(r.customFields()), parse(r.teamMembers()),
            r.version(), r.createdAt(), r.updatedAt());
    }

    private static String json(JsonNode value) {
        return value == null || value.isNull() ? "{}" : value.toString();
    }

    private static JsonNode parse(String value) {
        try {
            return JSON.readTree(value == null ? "{}" : value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Invalid JSON stored in jsonb column", exception);
        }
    }
}
