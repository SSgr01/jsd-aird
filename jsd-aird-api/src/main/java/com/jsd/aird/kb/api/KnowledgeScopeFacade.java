package com.jsd.aird.kb.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public interface KnowledgeScopeFacade {

    List<ScopeView> list(UUID organizationId, String scopeType, String keyword);

    ScopeView create(UUID organizationId, UUID actorId, CreateScope command);

    List<ScopeResource> resources(UUID organizationId, UUID scopeId);

    void attach(UUID organizationId, UUID scopeId, AttachResource resource);

    Set<UUID> validate(UUID organizationId, List<UUID> scopeIds, List<String> scopeTypes);

    record CreateScope(String scopeType, String externalId, String name, JsonNode metadata) { }

    record AttachResource(String resourceType, UUID resourceId, String relationType) { }

    record ScopeView(UUID id, String scopeType, String externalId, String name, String status, JsonNode metadata) { }

    record ScopeResource(UUID scopeId, String resourceType, UUID resourceId, String relationType) { }
}
