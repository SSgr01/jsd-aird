package com.jsd.aird.mdm.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MeetingMinutes(
        UUID id,
        UUID projectId,
        String title,
        List<String> attendees,
        String summary,
        Instant occurredAt,
        boolean archivedToKb,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}