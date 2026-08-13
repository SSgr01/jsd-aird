package com.jsd.aird.ops.infrastructure;

import java.util.UUID;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditLogFacade implements AuditLogFacade {

    private final JdbcTemplate jdbc;

    public JdbcAuditLogFacade(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(UUID organizationId, UUID actorId, String action, String aggregateType, UUID aggregateId,
                       JsonNode detail) {
        try {
            var json = new PGobject();
            json.setType("jsonb");
            json.setValue(detail == null ? "{}" : detail.toString());
            jdbc.update("""
                    INSERT INTO ops.audit_log (id, organization_id, actor_id, action, aggregate_type, aggregate_id, detail_jsonb)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), organizationId, actorId, action, aggregateType, aggregateId, json);
        } catch (Exception exception) {
            throw new IllegalStateException("审计记录写入失败", exception);
        }
    }

    @Override
    public List<AuditEntry> list(UUID organizationId, String aggregateType, UUID aggregateId, int limit) {
        return jdbc.query("""
                SELECT id, actor_id, action, aggregate_type, aggregate_id, detail_jsonb, created_at
                FROM ops.audit_log WHERE organization_id = ? AND aggregate_type = ? AND aggregate_id = ?
                ORDER BY created_at DESC LIMIT ?
                """, (rs, ignored) -> {
            JsonNode detail;
            try { detail = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rs.getString("detail_jsonb")); }
            catch (Exception exception) { detail = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(); }
            return new AuditEntry(rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class),
                    rs.getString("action"), rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                    detail, rs.getTimestamp("created_at").toInstant());
        }, organizationId, aggregateType, aggregateId, Math.min(500, Math.max(1, limit)));
    }
}
