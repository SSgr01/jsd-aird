package com.jsd.aird.mdm.infrastructure.model;

import java.time.Instant;
import java.util.UUID;

public record MeetingMinutesRow(
        UUID id,
        UUID projectId,
        String title,
        String attendees,
        String summary,
        Instant occurredAt,
        boolean archivedToKb,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}