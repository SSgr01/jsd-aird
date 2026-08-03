package com.jsd.aird.mfg.infrastructure;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mfg.application.port.ProductionOrderRepository;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductionOrderRepository implements ProductionOrderRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcProductionOrderRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ProductionOrderListItem> list(UUID organizationId) {
        return jdbcTemplate.query("""
                        SELECT po.id, po.order_no, po.status, po.template_version_id,
                               t.name AS template_name, t.template_code, t.format,
                               po.quantity, po.unit_code, po.planned_date, po.updated_at
                        FROM mfg.production_order po
                        JOIN tpl.template_version tv ON tv.id = po.template_version_id
                        JOIN tpl.template t ON t.id = tv.template_id
                        WHERE po.organization_id = ?
                        ORDER BY po.updated_at DESC
                        """,
                (rs, rowNum) -> new ProductionOrderListItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("order_no"),
                        rs.getString("status"),
                        rs.getObject("template_version_id", UUID.class),
                        rs.getString("template_name"),
                        rs.getString("template_code"),
                        rs.getString("format"),
                        rs.getBigDecimal("quantity"),
                        rs.getString("unit_code"),
                        rs.getObject("planned_date", java.time.LocalDate.class),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                organizationId
        );
    }

    @Override
    public Optional<PublishedTemplate> findPublishedTemplate(UUID organizationId, UUID versionId) {
        return jdbcTemplate.query("""
                        SELECT tv.id, t.format, tv.schema_jsonb, tv.editor_snapshot_file_id,
                               tv.editor_snapshot_hash, tv.snapshot_kind, tv.editor_app_version,
                               tv.plugin_manifest_hash, tv.snapshot_format_version
                        FROM tpl.template_version tv
                        JOIN tpl.template t ON t.id = tv.template_id
                        WHERE tv.id = ? AND t.organization_id = ? AND tv.status = 'PUBLISHED'
                        """,
                (rs, rowNum) -> new PublishedTemplate(
                        rs.getObject("id", UUID.class),
                        rs.getString("format"),
                        parse(rs.getString("schema_jsonb")),
                        loadMappings(versionId),
                        rs.getObject("editor_snapshot_file_id", UUID.class),
                        rs.getString("editor_snapshot_hash"),
                        rs.getString("snapshot_kind"),
                        rs.getString("editor_app_version"),
                        rs.getString("plugin_manifest_hash"),
                        rs.getInt("snapshot_format_version")
                ),
                versionId,
                organizationId
        ).stream().findFirst();
    }

    @Override
    public void insert(NewProductionOrder order) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO mfg.production_order (
                        id, organization_id, order_no, status, template_version_id,
                        product_id, quantity, unit_code, planned_date, owner_id,
                        instance_schema_jsonb, instance_mapping_jsonb, draft_data_jsonb,
                        draft_editor_snapshot_file_id, draft_editor_snapshot_hash,
                        snapshot_kind, editor_app_version, plugin_manifest_hash,
                        snapshot_format_version, schema_hash, mapping_hash, data_hash,
                        workspace_hash, created_by
                    ) VALUES (
                        ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?
                    )
                    """);
            statement.setObject(1, order.id());
            statement.setObject(2, order.organizationId());
            statement.setString(3, order.orderNo());
            statement.setObject(4, order.templateVersionId());
            nullableObject(statement, 5, order.productId(), Types.OTHER);
            statement.setBigDecimal(6, order.quantity());
            statement.setString(7, order.unitCode());
            if (order.plannedDate() == null) {
                statement.setNull(8, Types.DATE);
            } else {
                statement.setObject(8, order.plannedDate());
            }
            nullableObject(statement, 9, order.ownerId(), Types.OTHER);
            statement.setObject(10, pgJson(order.schema()));
            statement.setObject(11, pgJson(order.mapping()));
            statement.setObject(12, pgJson(order.data()));
            nullableObject(statement, 13, order.snapshotFileId(), Types.OTHER);
            statement.setString(14, order.snapshotHash());
            statement.setString(15, order.snapshotKind());
            statement.setString(16, order.editorAppVersion());
            statement.setString(17, order.pluginManifestHash());
            statement.setInt(18, order.snapshotFormatVersion());
            statement.setString(19, order.schemaHash());
            statement.setString(20, order.mappingHash());
            statement.setString(21, order.dataHash());
            statement.setString(22, order.workspaceHash());
            statement.setObject(23, order.actorId());
            return statement;
        });
    }

    @Override
    public Optional<ProductionWorkspace> findWorkspace(UUID organizationId, UUID orderId) {
        return jdbcTemplate.query("""
                        SELECT po.id, po.order_no, po.status, po.template_version_id,
                               t.name AS template_name, t.template_code, t.format,
                               po.product_id, po.quantity,
                               unit_code, planned_date, owner_id, instance_schema_jsonb,
                               instance_mapping_jsonb, draft_data_jsonb,
                               draft_editor_snapshot_file_id, draft_editor_snapshot_hash,
                               snapshot_kind, editor_app_version, plugin_manifest_hash,
                               snapshot_format_version, schema_hash, mapping_hash, data_hash,
                               workspace_hash, lock_version
                        FROM mfg.production_order po
                        JOIN tpl.template_version tv ON tv.id = po.template_version_id
                        JOIN tpl.template t ON t.id = tv.template_id
                        WHERE po.id = ? AND po.organization_id = ?
                        """,
                (rs, rowNum) -> {
                    var mapping = parse(rs.getString("instance_mapping_jsonb"));
                    return new ProductionWorkspace(
                            rs.getObject("id", UUID.class),
                            rs.getString("order_no"),
                            rs.getString("status"),
                            rs.getObject("template_version_id", UUID.class),
                            rs.getString("template_name"),
                            rs.getString("template_code"),
                            rs.getString("format"),
                            rs.getObject("product_id", UUID.class),
                            rs.getBigDecimal("quantity"),
                            rs.getString("unit_code"),
                            rs.getObject("planned_date", java.time.LocalDate.class),
                            rs.getObject("owner_id", UUID.class),
                            parse(rs.getString("instance_schema_jsonb")),
                            mapping,
                            parse(rs.getString("draft_data_jsonb")),
                            rs.getObject("draft_editor_snapshot_file_id", UUID.class),
                            rs.getString("draft_editor_snapshot_hash"),
                            rs.getString("snapshot_kind"),
                            rs.getString("editor_app_version"),
                            rs.getString("plugin_manifest_hash"),
                            rs.getInt("snapshot_format_version"),
                            rs.getString("schema_hash"),
                            rs.getString("mapping_hash"),
                            rs.getString("data_hash"),
                            rs.getString("workspace_hash"),
                            rs.getLong("lock_version"),
                            hasInvalidBinding(mapping)
                    );
                },
                orderId,
                organizationId
        ).stream().findFirst();
    }

    @Override
    public Optional<FileReference> findFile(UUID organizationId, UUID fileId) {
        return jdbcTemplate.query("""
                        SELECT id, status, sha256
                        FROM ops.file_object
                        WHERE id = ? AND organization_id = ?
                        """,
                (rs, rowNum) -> new FileReference(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getString("sha256")
                ),
                fileId,
                organizationId
        ).stream().findFirst();
    }

    @Override
    public int updateDraft(DraftUpdate update) {
        return jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    UPDATE mfg.production_order
                    SET instance_schema_jsonb = ?,
                        instance_mapping_jsonb = ?,
                        draft_data_jsonb = ?,
                        draft_editor_snapshot_file_id = ?,
                        draft_editor_snapshot_hash = ?,
                        editor_app_version = ?,
                        plugin_manifest_hash = ?,
                        snapshot_format_version = ?,
                        schema_hash = ?,
                        mapping_hash = ?,
                        data_hash = ?,
                        workspace_hash = ?,
                        lock_version = lock_version + 1,
                        updated_at = now()
                    WHERE id = ? AND organization_id = ? AND status = 'DRAFT'
                      AND lock_version = ?
                    """);
            statement.setObject(1, pgJson(update.schema()));
            statement.setObject(2, pgJson(update.mapping()));
            statement.setObject(3, pgJson(update.data()));
            statement.setObject(4, update.snapshotFileId());
            statement.setString(5, update.snapshotHash());
            statement.setString(6, update.editorAppVersion());
            statement.setString(7, update.pluginManifestHash());
            statement.setInt(8, update.snapshotFormatVersion());
            statement.setString(9, update.schemaHash());
            statement.setString(10, update.mappingHash());
            statement.setString(11, update.dataHash());
            statement.setString(12, update.workspaceHash());
            statement.setObject(13, update.orderId());
            statement.setObject(14, update.organizationId());
            statement.setLong(15, update.expectedLockVersion());
            return statement;
        });
    }

    @Override
    public UUID submit(SubmitRevision revision) {
        var revisionNo = jdbcTemplate.queryForObject("""
                SELECT COALESCE(max(revision_no), 0) + 1
                FROM tpl.record_revision
                WHERE production_order_id = ?
                """, Integer.class, revision.orderId());
        jdbcTemplate.update("""
                        INSERT INTO tpl.record_revision (
                            id, production_order_id, revision_no, status, core_snapshot_jsonb,
                            schema_snapshot_jsonb, mapping_snapshot_jsonb, data_jsonb,
                            editor_snapshot_file_id, editor_snapshot_hash, schema_hash,
                            mapping_hash, data_hash, workspace_hash, created_by
                        ) VALUES (?, ?, ?, 'SUBMITTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                revision.id(),
                revision.orderId(),
                revisionNo,
                pgJson(revision.coreSnapshot()),
                pgJson(revision.schema()),
                pgJson(revision.mapping()),
                pgJson(revision.data()),
                revision.snapshotFileId(),
                revision.snapshotHash(),
                revision.schemaHash(),
                revision.mappingHash(),
                revision.dataHash(),
                revision.workspaceHash(),
                revision.actorId()
        );
        jdbcTemplate.update("""
                UPDATE mfg.production_order
                SET status = 'SUBMITTED', updated_at = now()
                WHERE id = ? AND organization_id = ? AND status = 'DRAFT'
                """, revision.orderId(), revision.organizationId());
        return revision.id();
    }

    @Override
    public int cancel(UUID organizationId, UUID orderId) {
        return jdbcTemplate.update("""
                        UPDATE mfg.production_order
                        SET status = 'CANCELLED', updated_at = now()
                        WHERE id = ? AND organization_id = ? AND status = 'DRAFT'
                        """, orderId, organizationId);
    }

    @Override
    public void appendOutbox(String aggregateType, UUID aggregateId, String eventType, JsonNode payload) {
        jdbcTemplate.update("""
                        INSERT INTO ops.outbox_event (
                            id, aggregate_type, aggregate_id, event_type, payload_jsonb
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                eventType,
                pgJson(payload)
        );
    }

    private JsonNode loadMappings(UUID versionId) {
        var result = objectMapper.createArrayNode();
        jdbcTemplate.query("""
                        SELECT binding_id, marker_id, field_code, data_path, binding_role,
                               locator_type, locator_jsonb, sync_direction, primary_binding,
                               binding_status, diagnostic_jsonb
                        FROM tpl.template_mapping
                        WHERE template_version_id = ?
                        ORDER BY data_path, binding_id
                        """,
                rs -> {
                    var binding = objectMapper.createObjectNode();
                    binding.put("bindingId", rs.getString("binding_id"));
                    binding.put("markerId", rs.getString("marker_id"));
                    binding.put("fieldCode", rs.getString("field_code"));
                    binding.put("dataPath", rs.getString("data_path"));
                    binding.put("role", rs.getString("binding_role"));
                    binding.put("locatorType", rs.getString("locator_type"));
                    binding.set("locator", parse(rs.getString("locator_jsonb")));
                    binding.put("syncDirection", rs.getString("sync_direction"));
                    binding.put("primaryBinding", rs.getBoolean("primary_binding"));
                    binding.put("bindingStatus", rs.getString("binding_status"));
                    binding.set("diagnostic", parse(rs.getString("diagnostic_jsonb")));
                    result.add(binding);
                },
                versionId
        );
        return result;
    }

    private boolean hasInvalidBinding(JsonNode mapping) {
        for (JsonNode binding : mapping) {
            var hasPosition = org.springframework.util.StringUtils.hasText(binding.path("markerId").asText())
                    || org.springframework.util.StringUtils.hasText(
                            binding.path("locator").path("address").asText()
                    )
                    || org.springframework.util.StringUtils.hasText(
                            binding.path("locator").path("range").asText()
                    );
            if (!"VALID".equals(binding.path("bindingStatus").asText("VALID")) && hasPosition) {
                return true;
            }
        }
        return false;
    }

    private PGobject pgJson(JsonNode value) {
        try {
            var result = new PGobject();
            result.setType("jsonb");
            result.setValue(objectMapper.writeValueAsString(value));
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to serialize JSONB", exception);
        }
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to parse JSONB", exception);
        }
    }

    private void nullableObject(java.sql.PreparedStatement statement, int index, Object value, int type)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, type);
        } else {
            statement.setObject(index, value);
        }
    }
}
