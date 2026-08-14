package com.jsd.aird.mdm.infrastructure.model;

import java.time.Instant;
import java.util.UUID;

public record CommunicationRow(UUID id, String recordCode, String name, UUID partnerId, Instant communicatedAt,
                               String internalParticipants, String communicationMethod, String content,
                               String status, String customFields,
                               long version, Instant createdAt, Instant updatedAt) {
}
