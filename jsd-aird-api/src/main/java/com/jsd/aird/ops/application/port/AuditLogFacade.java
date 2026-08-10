package com.jsd.aird.ops.application.port;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/** Public audit boundary used by business modules. */
public interface AuditLogFacade {

    void append(UUID organizationId, UUID actorId, String action, String aggregateType, UUID aggregateId,
                JsonNode detail);
}
