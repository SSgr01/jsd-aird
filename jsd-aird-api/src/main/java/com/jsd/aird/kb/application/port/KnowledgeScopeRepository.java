package com.jsd.aird.kb.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public interface KnowledgeScopeRepository {

    void insert(ScopeRow scope);

    List<ScopeRow> list(UUID organizationId, String scopeType, String keyword);

    Optional<ScopeRow> find(UUID organizationId, UUID id);

    List<ResourceRow> resources(UUID organizationId, UUID scopeId);

    void attach(UUID scopeId, String resourceType, UUID resourceId, String relationType);

    record ScopeRow(UUID id, UUID organizationId, String scopeType, String externalId, String name,
                    String status, JsonNode metadata) { }

    record ResourceRow(UUID scopeId, String resourceType, UUID resourceId, String relationType) { }
}
