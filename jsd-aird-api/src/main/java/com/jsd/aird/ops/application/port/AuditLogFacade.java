package com.jsd.aird.ops.application.port;

import java.util.UUID;
import java.util.List;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/** Public audit boundary used by business modules. */
public interface AuditLogFacade {

    void append(UUID organizationId, UUID actorId, String action, String aggregateType, UUID aggregateId,
                JsonNode detail);

    List<AuditEntry> list(UUID organizationId, String aggregateType, UUID aggregateId, int limit);

    record AuditEntry(UUID id, UUID actorId, String action, String aggregateType, UUID aggregateId,
                      JsonNode detail, Instant createdAt) { }
}
