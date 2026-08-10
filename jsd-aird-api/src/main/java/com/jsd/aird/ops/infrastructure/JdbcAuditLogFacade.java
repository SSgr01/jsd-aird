package com.jsd.aird.ops.infrastructure;

import java.util.UUID;

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
}
