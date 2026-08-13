package com.jsd.aird.core.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.core.application.port.BusinessObjectRepository;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBusinessObjectRepository implements BusinessObjectRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcBusinessObjectRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public ObjectRow insert(UUID organizationId, UUID actorId, CreateRow input) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO core.business_object_ref (
                    id, organization_id, object_type, external_id, name, source_system, metadata_jsonb, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, object_type, external_id)
                DO UPDATE SET name = EXCLUDED.name, source_system = EXCLUDED.source_system,
                              metadata_jsonb = EXCLUDED.metadata_jsonb, status = 'ACTIVE', updated_at = now()
                """, id, organizationId, input.objectType(), input.externalId(), input.name(), input.sourceSystem(),
                json(input.metadata()), actorId);
        return jdbc.query("""
                SELECT id, object_type, external_id, name, source_system, status, metadata_jsonb,
                       created_at, updated_at
                FROM core.business_object_ref
                WHERE organization_id = ? AND object_type = ? AND external_id = ?
                """, this::map, organizationId, input.objectType(), input.externalId()).getFirst();
    }

    @Override
    public List<ObjectRow> list(UUID organizationId, String objectType, String keyword, int limit) {
        var normalizedType = objectType == null || objectType.isBlank() ? null : objectType.trim().toUpperCase();
        var normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return jdbc.query("""
                SELECT id, object_type, external_id, name, source_system, status, metadata_jsonb,
                       created_at, updated_at
                FROM core.business_object_ref
                WHERE organization_id = ? AND status = 'ACTIVE'
                  AND (CAST(? AS text) IS NULL OR object_type = ?)
                  AND (CAST(? AS text) IS NULL OR name ILIKE '%' || ? || '%'
                       OR external_id ILIKE '%' || ? || '%')
                ORDER BY object_type, name
                LIMIT ?
                """, this::map, organizationId, normalizedType, normalizedType, normalizedKeyword,
                normalizedKeyword, normalizedKeyword, Math.min(200, Math.max(1, limit)));
    }

    @Override
    public Optional<ObjectRow> find(UUID organizationId, UUID id) {
        return jdbc.query("""
                SELECT id, object_type, external_id, name, source_system, status, metadata_jsonb,
                       created_at, updated_at
                FROM core.business_object_ref WHERE organization_id = ? AND id = ?
                """, this::map, organizationId, id).stream().findFirst();
    }

    private ObjectRow map(ResultSet rs, int ignored) throws SQLException {
        return new ObjectRow(rs.getObject("id", UUID.class), rs.getString("object_type"),
                rs.getString("external_id"), rs.getString("name"), rs.getString("source_system"),
                rs.getString("status"), read(rs.getString("metadata_jsonb")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private PGobject json(JsonNode node) {
        try {
            var value = new PGobject();
            value.setType("jsonb");
            value.setValue(node == null ? "{}" : node.toString());
            return value;
        } catch (SQLException exception) {
            throw new IllegalArgumentException("业务对象元数据格式无效", exception);
        }
    }

    private JsonNode read(String value) {
        try { return value == null ? objectMapper.createObjectNode() : objectMapper.readTree(value); }
        catch (Exception exception) { return objectMapper.createObjectNode(); }
    }
}
