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
                        SELECT tv.id, t.format, tv.schema_jsonb, tv.layout_summary_jsonb,
                               tv.editor_snapshot_file_id,
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
                        parse(rs.getString("layout_summary_jsonb")).path("wordDocument"),
                        parse(rs.getString("layout_summary_jsonb")).path("initialSnapshot"),
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
    public List<RevisionSummary> listRevisions(UUID organizationId, UUID orderId) {
        return jdbcTemplate.query("""
                        SELECT rr.id, rr.revision_no, rr.status, rr.created_at, rr.data_hash
                        FROM tpl.record_revision rr
                        JOIN mfg.production_order po ON po.id = rr.production_order_id
                        WHERE rr.production_order_id = ? AND po.organization_id = ?
                        ORDER BY rr.revision_no DESC
                        """,
                (rs, rowNum) -> new RevisionSummary(
                        rs.getObject("id", UUID.class), rs.getInt("revision_no"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("data_hash")), orderId, organizationId);
    }

    @Override
    public Optional<RecordRevision> findRevision(UUID organizationId, UUID orderId, UUID revisionId) {
        return jdbcTemplate.query("""
                        SELECT rr.id, rr.production_order_id, rr.revision_no, rr.status,
                               rr.schema_snapshot_jsonb, rr.mapping_snapshot_jsonb, rr.data_jsonb,
                               rr.editor_snapshot_file_id, rr.editor_snapshot_hash,
                               rr.schema_hash, rr.mapping_hash, rr.data_hash
                        FROM tpl.record_revision rr
                        JOIN mfg.production_order po ON po.id = rr.production_order_id
                        WHERE rr.id = ? AND rr.production_order_id = ? AND po.organization_id = ?
                        """,
                (rs, rowNum) -> new RecordRevision(
                        rs.getObject("id", UUID.class), rs.getObject("production_order_id", UUID.class),
                        rs.getInt("revision_no"), rs.getString("status"), parse(rs.getString("schema_snapshot_jsonb")),
                        parse(rs.getString("mapping_snapshot_jsonb")), parse(rs.getString("data_jsonb")),
                        rs.getObject("editor_snapshot_file_id", UUID.class), rs.getString("editor_snapshot_hash"),
                        rs.getString("schema_hash"), rs.getString("mapping_hash"), rs.getString("data_hash")),
                revisionId, orderId, organizationId).stream().findFirst();
    }

    @Override
    public List<TemplateCandidate> listPublishedTemplates(UUID organizationId) {
        return jdbcTemplate.query("""
                        SELECT tv.id, t.template_code, t.name
                        FROM tpl.template_version tv
                        JOIN tpl.template t ON t.id = tv.template_id
                        WHERE t.organization_id = ? AND tv.status = 'PUBLISHED' AND t.format = 'XLSX'
                        ORDER BY t.updated_at DESC
                        LIMIT 100
                        """,
                (rs, rowNum) -> new TemplateCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("template_code"),
                        rs.getString("name")),
                organizationId);
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
                               po.unit_code, po.planned_date, po.owner_id, po.instance_schema_jsonb,
                               po.instance_mapping_jsonb, po.draft_data_jsonb,
                               po.draft_editor_snapshot_file_id, po.draft_editor_snapshot_hash,
                               po.snapshot_kind, po.editor_app_version, po.plugin_manifest_hash,
                               po.snapshot_format_version, po.schema_hash, po.mapping_hash, po.data_hash,
                               po.workspace_hash, po.lock_version
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
                    SET template_version_id = ?,
                        instance_schema_jsonb = ?,
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
            statement.setObject(1, update.templateVersionId());
            statement.setObject(2, pgJson(update.schema()));
            statement.setObject(3, pgJson(update.mapping()));
            statement.setObject(4, pgJson(update.data()));
            statement.setObject(5, update.snapshotFileId());
            statement.setString(6, update.snapshotHash());
            statement.setString(7, update.editorAppVersion());
            statement.setString(8, update.pluginManifestHash());
            statement.setInt(9, update.snapshotFormatVersion());
            statement.setString(10, update.schemaHash());
            statement.setString(11, update.mappingHash());
            statement.setString(12, update.dataHash());
            statement.setString(13, update.workspaceHash());
            statement.setObject(14, update.orderId());
            statement.setObject(15, update.organizationId());
            statement.setLong(16, update.expectedLockVersion());
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
    public void insertRevisionProjection(
            List<CollectionProjection> collections,
            List<ValueProjection> values
    ) {
        if (!collections.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO tpl.record_collection_item (
                        id, revision_id, production_order_id, record_kind,
                        parent_field_code, parent_data_path, record_key, record_index,
                        member_key, data_jsonb
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, collections, collections.size(), (statement, item) -> {
                statement.setObject(1, item.id());
                statement.setObject(2, item.revisionId());
                statement.setObject(3, item.productionOrderId());
                statement.setString(4, item.recordKind());
                statement.setString(5, item.parentFieldCode());
                statement.setString(6, item.parentDataPath());
                statement.setString(7, item.recordKey());
                statement.setInt(8, item.recordIndex());
                statement.setString(9, item.memberKey());
                statement.setObject(10, pgJson(item.data()));
            });
        }
        if (!values.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO tpl.record_value_index (
                        id, revision_id, production_order_id, collection_item_id,
                        field_code, data_path, value_type, text_value, numeric_value,
                        boolean_value, date_value, reference_value
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, values, values.size(), (statement, item) -> {
                statement.setObject(1, item.id());
                statement.setObject(2, item.revisionId());
                statement.setObject(3, item.productionOrderId());
                nullableObject(statement, 4, item.collectionItemId(), Types.OTHER);
                statement.setString(5, item.fieldCode());
                statement.setString(6, item.dataPath());
                statement.setString(7, item.valueType());
                var value = item.value();
                var type = item.valueType();
                statement.setString(8, isTextType(type) && value != null && !value.isNull()
                        ? value.asText() : null);
                if (isNumericType(type) && value != null && value.isNumber()) {
                    statement.setBigDecimal(9, value.decimalValue());
                } else statement.setNull(9, Types.NUMERIC);
                if ("boolean".equals(type) && value != null && value.isBoolean()) {
                    statement.setBoolean(10, value.asBoolean());
                } else statement.setNull(10, Types.BOOLEAN);
                if ("date".equals(type) && value != null && !value.asText().isBlank()) {
                    try {
                        statement.setObject(11, java.time.LocalDate.parse(value.asText()));
                    } catch (java.time.format.DateTimeParseException ignored) {
                        statement.setNull(11, Types.DATE);
                    }
                } else statement.setNull(11, Types.DATE);
                if ("reference".equals(type) && value != null) {
                    try {
                        statement.setObject(12, UUID.fromString(value.asText()));
                    } catch (IllegalArgumentException ignored) {
                        statement.setNull(12, Types.OTHER);
                    }
                } else statement.setNull(12, Types.OTHER);
            });
        }
    }

    @Override
    public void attachConfirmedIngestSources(
            UUID organizationId, UUID orderId, UUID revisionId, UUID actorId
    ) {
        jdbcTemplate.update("""
                INSERT INTO tpl.record_attachment (
                    id, production_order_id, revision_id, file_object_id,
                    attachment_type, created_by
                )
                SELECT gen_random_uuid(), j.production_order_id, ?, s.file_object_id,
                       'INSTANCE_SOURCE', ?
                FROM mfg.production_ingest_job j
                JOIN mfg.production_ingest_source s ON s.ingest_job_id = j.id
                WHERE j.organization_id = ? AND j.production_order_id = ?
                  AND j.status = 'CONFIRMED'
                  AND NOT EXISTS (
                      SELECT 1 FROM tpl.record_attachment a
                      WHERE a.revision_id = ? AND a.file_object_id = s.file_object_id
                  )
                """, revisionId, actorId, organizationId, orderId, revisionId);
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
    public int delete(UUID organizationId, UUID orderId) {
        // Ingest jobs and their source/item rows are ON DELETE CASCADE.  A
        // submitted order is intentionally excluded so production history and
        // its revision projections cannot be removed from this list screen.
        return jdbcTemplate.update("""
                DELETE FROM mfg.production_order
                WHERE id = ? AND organization_id = ?
                  AND status IN ('DRAFT', 'CANCELLED')
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
                        SELECT binding_id, field_id, parent_binding_id, marker_id, field_code,
                               data_path, binding_role, mapping_kind, repeat_axis,
                               record_height, record_width, record_stride, termination_jsonb,
                               locator_type, locator_jsonb, sync_direction, primary_binding,
                               binding_status, diagnostic_jsonb
                        FROM tpl.template_mapping
                        WHERE template_version_id = ?
                        ORDER BY data_path, binding_id
                        """,
                rs -> {
                    var binding = objectMapper.createObjectNode();
                    binding.put("bindingId", rs.getString("binding_id"));
                    var fieldId = rs.getString("field_id");
                    if (fieldId != null) binding.put("fieldId", fieldId);
                    var parentBindingId = rs.getString("parent_binding_id");
                    if (parentBindingId != null) binding.put("parentBindingId", parentBindingId);
                    binding.put("markerId", rs.getString("marker_id"));
                    binding.put("fieldCode", rs.getString("field_code"));
                    binding.put("dataPath", rs.getString("data_path"));
                    binding.put("role", rs.getString("binding_role"));
                    binding.put("mappingKind", rs.getString("mapping_kind"));
                    var repeatAxis = rs.getString("repeat_axis");
                    if (repeatAxis != null) binding.put("repeatAxis", repeatAxis);
                    binding.put("recordHeight", rs.getInt("record_height"));
                    binding.put("recordWidth", rs.getInt("record_width"));
                    binding.put("recordStride", rs.getInt("record_stride"));
                    binding.set("termination", parse(rs.getString("termination_jsonb")));
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

    private boolean isTextType(String type) {
        return !isNumericType(type) && !"boolean".equals(type)
                && !"date".equals(type) && !"reference".equals(type);
    }

    private boolean isNumericType(String type) {
        return java.util.Set.of("number", "integer", "decimal").contains(type);
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
