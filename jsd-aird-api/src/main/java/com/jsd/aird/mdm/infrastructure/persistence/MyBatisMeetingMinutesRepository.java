package com.jsd.aird.mdm.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mdm.application.port.MeetingMinutesRepository;
import com.jsd.aird.mdm.domain.model.MeetingMinutes;
import com.jsd.aird.mdm.infrastructure.model.MeetingMinutesRow;
import com.jsd.aird.shared.api.PageResponse;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisMeetingMinutesRepository implements MeetingMinutesRepository {

    private final MeetingMinutesMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisMeetingMinutesRepository(MeetingMinutesMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResponse<MeetingMinutes> findByProject(UUID projectId, int page, int size) {
        int offset = (Math.max(page, 1) - 1) * size;
        var rows = mapper.listByProject(projectId, offset, size);
        var total = mapper.countByProject(projectId);
        long totalPages = size > 0 ? (long) Math.ceil((double) total / size) : 0;
        return new PageResponse<>(rows.stream().map(this::toDomain).toList(), page, size, total, totalPages);
    }

    @Override
    public Optional<MeetingMinutes> findById(UUID id) {
        var row = mapper.findById(id);
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    @Override
    public void insert(MeetingMinutes record, String operator) {
        mapper.insert(toRow(record), operator);
    }

    @Override
    public boolean update(MeetingMinutes record, long currentVersion, String operator) {
        return mapper.update(toRow(record), currentVersion, operator) > 0;
    }

    @Override
    public boolean delete(UUID id, long version) {
        return mapper.delete(id, version) > 0;
    }

    private MeetingMinutes toDomain(MeetingMinutesRow row) {
        return new MeetingMinutes(
                row.id(),
                row.projectId(),
                row.title(),
                parseAttendees(row.attendees()),
                row.summary(),
                row.occurredAt(),
                row.archivedToKb(),
                row.version(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    private MeetingMinutesRow toRow(MeetingMinutes value) {
        return new MeetingMinutesRow(
                value.id(),
                value.projectId(),
                value.title(),
                serializeAttendees(value.attendees()),
                value.summary(),
                value.occurredAt(),
                value.archivedToKb(),
                value.version(),
                value.createdAt(),
                value.updatedAt()
        );
    }

    private List<String> parseAttendees(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String serializeAttendees(List<String> attendees) {
        try {
            return objectMapper.writeValueAsString(attendees == null ? List.of() : attendees);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }
}