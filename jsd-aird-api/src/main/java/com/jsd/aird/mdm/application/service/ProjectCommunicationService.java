package com.jsd.aird.mdm.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mdm.domain.model.ProjectCommunicationRecord;
import com.jsd.aird.mdm.infrastructure.persistence.ProjectCommunicationMapper;
import com.jsd.aird.mdm.infrastructure.model.ProjectCommunicationRow;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Map;

@Service
public class ProjectCommunicationService {
    private final ProjectCommunicationMapper mapper;
    private final ProjectService projects;
    private final ObjectMapper objectMapper;

    public ProjectCommunicationService(ProjectCommunicationMapper mapper, ProjectService projects, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.projects = projects;
        this.objectMapper = objectMapper;
    }

    public PageResponse<ProjectCommunicationRecord> list(UUID projectId, int page, int size) {
        projects.requireAccess(projectId, "project.communication.view");
        int currentPage = Math.max(1, page);
        int pageSize = Math.min(100, Math.max(1, size));
        UUID org = ActorContext.required().organizationId();
        var items = mapper.list(org, projectId, (currentPage - 1) * pageSize, pageSize).stream().map(this::toDomain).toList();
        long total = mapper.count(org, projectId);
        return new PageResponse<>(items, currentPage, pageSize, total, (total + pageSize - 1) / pageSize);
    }

    @Transactional
    public UUID create(UUID projectId, ProjectCommunicationRecord record) {
        projects.requireAccess(projectId, "project.communication.create");
        var actor = ActorContext.required();
        UUID id = UUID.randomUUID();
        mapper.insert(actor.organizationId(), new ProjectCommunicationRecord(id, projectId, record.occurredAt(),
                record.communicationMethod(), record.communicator(), record.content(), record.customFields(), 0, null, null),
                serialize(record.customFields()), actor.userId());
        return id;
    }

    @Transactional
    public void update(UUID projectId, UUID id, ProjectCommunicationRecord record) {
        projects.requireAccess(projectId, "project.communication.update");
        var actor = ActorContext.required();
        requireExisting(actor.organizationId(), projectId, id);
        var value = new ProjectCommunicationRecord(id, projectId, record.occurredAt(), record.communicationMethod(),
                record.communicator(), record.content(), record.customFields(), record.version(), null, null);
        if (mapper.update(actor.organizationId(), value, serialize(record.customFields()), actor.userId()) == 0) CrmServiceSupport.conflict();
    }

    @Transactional
    public void delete(UUID projectId, UUID id, long version) {
        projects.requireAccess(projectId, "project.communication.delete");
        var actor = ActorContext.required();
        requireExisting(actor.organizationId(), projectId, id);
        if (mapper.delete(actor.organizationId(), projectId, id, version, actor.userId()) == 0) CrmServiceSupport.conflict();
    }

    private void requireExisting(UUID organizationId, UUID projectId, UUID id) {
        if (mapper.find(organizationId, projectId, id) == null)
            throw new ApiException(ApiErrorCode.NOT_FOUND, "沟通记录不存在");
    }

    private ProjectCommunicationRecord toDomain(ProjectCommunicationRow row) {
        try {
            Map<String, String> customFields = objectMapper.readValue(row.customFields(), new TypeReference<>() {});
            return new ProjectCommunicationRecord(row.id(), row.projectId(), row.occurredAt(), row.communicationMethod(),
                    row.communicator(), row.content(), customFields, row.version(), row.createdAt(), row.updatedAt());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("项目沟通记录自定义字段格式无效", ex);
        }
    }

    private String serialize(Map<String, String> customFields) {
        try {
            return objectMapper.writeValueAsString(customFields == null ? Map.of() : customFields);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "自定义字段格式无效");
        }
    }
}
