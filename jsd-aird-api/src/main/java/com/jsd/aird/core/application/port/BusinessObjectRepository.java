package com.jsd.aird.core.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public interface BusinessObjectRepository {

    ObjectRow insert(UUID organizationId, UUID actorId, CreateRow input);

    List<ObjectRow> list(UUID organizationId, String objectType, String keyword, int limit);

    Optional<ObjectRow> find(UUID organizationId, UUID id);

    record CreateRow(String objectType, String externalId, String name, String sourceSystem, JsonNode metadata) { }

    record ObjectRow(UUID id, String objectType, String externalId, String name, String sourceSystem,
                     String status, JsonNode metadata, Instant createdAt, Instant updatedAt) { }
}
