package com.jsd.aird.mdm.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.jsd.aird.shared.api.AllowedActions;

public record CommunicationRecord(
    UUID id, String recordCode, String name, UUID partnerId, Instant communicatedAt,
    String internalParticipants, String communicationMethod, String content,
    CommunicationStatus status,
    JsonNode customFields, long version, Instant createdAt, Instant updatedAt
) {
    public List<String> getAllowedActions() {
        return "CLOSED".equalsIgnoreCase(status == null ? null : status.name()) ? List.of() : List.of("DELETE");
    }
    public enum CommunicationStatus {OPEN, FOLLOWING, CLOSED}
}
