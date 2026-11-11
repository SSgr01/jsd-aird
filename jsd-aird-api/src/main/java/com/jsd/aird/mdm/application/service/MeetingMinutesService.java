package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.port.MeetingMinutesRepository;
import com.jsd.aird.mdm.domain.model.MeetingMinutes;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class MeetingMinutesService {

    private final MeetingMinutesRepository repository;
    private final ProjectService projectAccess;

    @Autowired
    public MeetingMinutesService(MeetingMinutesRepository repository, ProjectService projectAccess) {
        this.repository = repository;
        this.projectAccess = projectAccess;
    }

    public MeetingMinutesService(MeetingMinutesRepository repository) {
        this(repository, null);
    }

    public PageResponse<MeetingMinutes> listByProject(UUID projectId, int page, int size) {
        requireProject(projectId);
        return repository.findByProject(projectId, Math.max(page, 1), clamp(size));
    }

    public MeetingMinutes get(UUID id) {
        var meeting = repository.findById(id).orElseThrow(notFound("会议纪要不存在"));
        requireProject(meeting.projectId());
        return meeting;
    }

    @Transactional
    public UUID create(MeetingMinutes input) {
        requireAction(input.projectId(), "project.meeting.create");
        var now = Instant.now();
        var value = new MeetingMinutes(
                UUID.randomUUID(),
                input.projectId(),
                input.title(),
                input.attendees() == null ? List.of() : input.attendees(),
                input.summary(),
                input.occurredAt() == null ? now : input.occurredAt(),
                false,
                0,
                now,
                now
        );
        repository.insert(value, CrmServiceSupport.operator());
        return value.id();
    }

    @Transactional
    public void update(UUID id, MeetingMinutes input) {
        var current = get(id);
        requireAction(current.projectId(), "project.meeting.update");
        if (current.archivedToKb()) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "已归档会议纪要不可编辑");
        }
        var value = new MeetingMinutes(
                id,
                current.projectId(),
                input.title(),
                input.attendees() == null ? List.of() : input.attendees(),
                input.summary(),
                input.occurredAt() == null ? current.occurredAt() : input.occurredAt(),
                input.archivedToKb(),
                input.version(),
                current.createdAt(),
                Instant.now()
        );
        if (!repository.update(value, input.version(), CrmServiceSupport.operator())) CrmServiceSupport.conflict();
    }

    @Transactional
    public void delete(UUID id, long version) {
        requireAction(get(id).projectId(), "project.meeting.delete");
        if (!repository.delete(id, version)) CrmServiceSupport.conflict();
    }

    @Transactional
    public void archiveToKb(UUID id,long expectedVersion) {
        var current = get(id);
        requireAction(current.projectId(), "project.meeting.archive");
        if(current.version()!=expectedVersion)CrmServiceSupport.conflict();
        if (current.archivedToKb()) return;
        var value = new MeetingMinutes(
                current.id(), current.projectId(), current.title(), current.attendees(),
                current.summary(), current.occurredAt(), true, current.version(),
                current.createdAt(), Instant.now()
        );
        if (!repository.update(value, expectedVersion, CrmServiceSupport.operator())) CrmServiceSupport.conflict();
    }

    private static int clamp(int value) {
        return Math.min(Math.max(value, 1), 100);
    }

    private void requireProject(UUID projectId) {
        if (projectAccess != null) projectAccess.get(projectId);
    }

    private void requireAction(UUID projectId, String permissionCode) {
        if (projectAccess != null) projectAccess.requireAccess(projectId, permissionCode);
    }

    private static Supplier<ApiException> notFound(String message) {
        return () -> new ApiException(ApiErrorCode.NOT_FOUND, message);
    }
}
