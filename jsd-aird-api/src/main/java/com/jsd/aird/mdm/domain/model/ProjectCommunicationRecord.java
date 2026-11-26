package com.jsd.aird.mdm.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ProjectCommunicationRecord(UUID id, UUID projectId, Instant occurredAt,
                                         String communicationMethod, String communicator,
                                         String content, Map<String, String> customFields,
                                         long version, Instant createdAt, Instant updatedAt) {
}
