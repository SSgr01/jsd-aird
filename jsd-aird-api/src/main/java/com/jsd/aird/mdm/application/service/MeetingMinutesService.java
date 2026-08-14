package com.jsd.aird.mdm.application.service;

import com.jsd.aird.mdm.application.port.MeetingMinutesRepository;
import com.jsd.aird.mdm.domain.model.MeetingMinutes;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class MeetingMinutesService {

    private final MeetingMinutesRepository repository;

    public MeetingMinutesService(MeetingMinutesRepository repository) {
        this.repository = repository;
    }

    public PageResponse<MeetingMinutes> listByProject(UUID projectId, int page, int size) {
        return repository.findByProject(projectId, Math.max(page, 1), clamp(size));
    }

    public MeetingMinutes get(UUID id) {
        return repository.findById(id).orElseThrow(notFound("会议纪要不存在"));
    }

    @Transactional
    public UUID create(MeetingMinutes input) {
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
        repository.insert(value, CrmServiceSupport.OPERATOR);
        return value.id();
    }

    @Transactional
    public void update(UUID id, MeetingMinutes input) {
        var current = get(id);
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
        if (!repository.update(value, current.version(), CrmServiceSupport.OPERATOR)) CrmServiceSupport.conflict();
    }

    @Transactional
    public void delete(UUID id, long version) {
        get(id);
        if (!repository.delete(id, version)) CrmServiceSupport.conflict();
    }

    @Transactional
    public void archiveToKb(UUID id) {
        var current = get(id);
        if (current.archivedToKb()) return;
        var value = new MeetingMinutes(
                current.id(), current.projectId(), current.title(), current.attendees(),
                current.summary(), current.occurredAt(), true, current.version(),
                current.createdAt(), Instant.now()
        );
        if (!repository.update(value, current.version(), CrmServiceSupport.OPERATOR)) CrmServiceSupport.conflict();
    }

    private static int clamp(int value) {
        return Math.min(Math.max(value, 1), 100);
    }

    private static Supplier<ApiException> notFound(String message) {
        return () -> new ApiException(ApiErrorCode.NOT_FOUND, message);
    }
}
