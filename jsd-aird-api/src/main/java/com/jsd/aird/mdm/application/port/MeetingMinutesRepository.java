package com.jsd.aird.mdm.application.port;

import com.jsd.aird.mdm.domain.model.MeetingMinutes;
import com.jsd.aird.shared.api.PageResponse;

import java.util.Optional;
import java.util.UUID;

public interface MeetingMinutesRepository {
    PageResponse<MeetingMinutes> findByProject(UUID projectId, int page, int size);

    Optional<MeetingMinutes> findById(UUID id);

    void insert(MeetingMinutes record, String operator);

    boolean update(MeetingMinutes record, long currentVersion, String operator);

    boolean delete(UUID id, long version);
}