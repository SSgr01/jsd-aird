package com.jsd.aird.kb.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.port.KnowledgeScopeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKnowledgeScopeRepository implements KnowledgeScopeRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcKnowledgeScopeRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(ScopeRow scope) {
        jdbc.update("""
                INSERT INTO ai.ai_scope (id, organization_id, scope_type, external_id, name, status, metadata_jsonb, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, scope.id(), scope.organizationId(), scope.scopeType(), scope.externalId(), scope.name(),
                scope.status(), scope.metadata().toString(), null);
    }

    @Override
    public List<ScopeRow> list(UUID organizationId, String scopeType, String keyword) {
        var filter = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return jdbc.query("""
                SELECT id, organization_id, scope_type, external_id, name, status, metadata_jsonb
                FROM ai.ai_scope
                WHERE organization_id = ?
                  AND (CAST(? AS text) IS NULL OR scope_type = ?)
                  AND (CAST(? AS text) IS NULL OR name ILIKE '%' || ? || '%' OR external_id ILIKE '%' || ? || '%')
                ORDER BY scope_type, name
                """, (rs, rowNum) -> new ScopeRow(rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getString("scope_type"), rs.getString("external_id"),
                rs.getString("name"), rs.getString("status"), json(rs.getString("metadata_jsonb"))),
                organizationId, scopeType, scopeType, filter, filter, filter);
    }

    @Override
    public Optional<ScopeRow> find(UUID organizationId, UUID id) {
        return list(organizationId, null, null).stream().filter(row -> row.id().equals(id)).findFirst();
    }

    @Override
    public List<ResourceRow> resources(UUID organizationId, UUID scopeId) {
        return jdbc.query("""
                SELECT r.scope_id, r.resource_type, r.resource_id, r.relation_type
                FROM ai.ai_scope_resource r JOIN ai.ai_scope s ON s.id = r.scope_id
                WHERE s.organization_id = ? AND r.scope_id = ? ORDER BY r.resource_type, r.resource_id
                """, (rs, rowNum) -> new ResourceRow(rs.getObject("scope_id", UUID.class),
                rs.getString("resource_type"), rs.getObject("resource_id", UUID.class), rs.getString("relation_type")),
                organizationId, scopeId);
    }

    @Override
    public void attach(UUID scopeId, String resourceType, UUID resourceId, String relationType) {
        jdbc.update("""
                INSERT INTO ai.ai_scope_resource (scope_id, resource_type, resource_id, relation_type)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (scope_id, resource_type, resource_id)
                DO UPDATE SET relation_type = EXCLUDED.relation_type
                """, scopeId, resourceType, resourceId, relationType);
    }

    private JsonNode json(String value) {
        try { return value == null ? objectMapper.createObjectNode() : objectMapper.readTree(value); }
        catch (Exception ignored) { return objectMapper.createObjectNode(); }
    }
}
