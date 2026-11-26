package com.jsd.aird.mdm.infrastructure.model;

import java.time.Instant;
import java.util.UUID;

public record ProjectCommunicationRow(UUID id, UUID projectId, Instant occurredAt,
                                      String communicationMethod, String communicator,
                                      String content, String customFields, long version,
                                      Instant createdAt, Instant updatedAt) {
}
