package com.jsd.aird.mdm.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.List;
import com.jsd.aird.shared.api.AllowedActions;

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
    public List<String> getAllowedActions() {
        return AllowedActions.meeting(archivedToKb);
    }
}
