package com.jsd.aird.mdm.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CommunicationRecord(
    UUID id, String recordCode, String name, UUID partnerId, Instant communicatedAt,
    String internalParticipants, String communicationMethod, String content,
    CommunicationStatus status,
    JsonNode customFields, long version, Instant createdAt, Instant updatedAt
) {
    public enum CommunicationStatus {OPEN, FOLLOWING, CLOSED}
}
