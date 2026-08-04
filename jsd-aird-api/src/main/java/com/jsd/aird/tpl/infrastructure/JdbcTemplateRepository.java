package com.jsd.aird.tpl.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.jsd.aird.tpl.domain.TemplateStatus;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTemplateRepository implements TemplateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcTemplateRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.objectMapper = objectMapper;
    }

    @Override
    public List<TemplateListItem> findTemplates(
            UUID organizationId,
            String keyword,
            TemplateFormat format,
            TemplateStatus status
    ) {
        var sql = new StringBuilder("""
                SELECT t.id AS template_id, tv.id AS version_id, t.template_code, t.name,
                       t.purpose, t.category, t.format, tv.status, tv.version_no,
                       tv.lock_version, tv.updated_at,
                       (
                           SELECT count(*)::int
                           FROM tpl.template_mapping tm
                           WHERE tm.template_version_id = tv.id
                             AND tm.binding_status <> 'VALID'
                       ) AS issue_count
                FROM tpl.template t
                JOIN tpl.template_version tv ON tv.template_id = t.id
                WHERE t.organization_id = :organizationId
                """);
        var normalizedKeyword = blankToNull(keyword);
        var parameters = new MapSqlParameterSource()
                .addValue("organizationId", organizationId);
        if (normalizedKeyword != null) {
            sql.append("""
                      AND (lower(t.name) LIKE lower(:keywordPattern)
                           OR lower(t.template_code) LIKE lower(:keywordPattern))
                    """);
            parameters.addValue("keywordPattern", "%" + normalizedKeyword + "%");
        }
        if (format != null) {
            sql.append("  AND t.format = :format\n");
            parameters.addValue("format", format.name());
        }
        if (status != null) {
            sql.append("  AND tv.status = :status\n");
            parameters.addValue("status", status.name());
        }
        sql.append("ORDER BY tv.updated_at DESC, t.name\n");
        return namedJdbcTemplate.query(sql.toString(), parameters, (rs, rowNum) -> new TemplateListItem(
                rs.getObject("template_id", UUID.class),
                rs.getObject("version_id", UUID.class),
                rs.getString("template_code"),
                rs.getString("name"),
                rs.getString("purpose"),
                rs.getString("category"),
                TemplateFormat.valueOf(rs.getString("format")),
                TemplateStatus.valueOf(rs.getString("status")),
                rs.getInt("version_no"),
                rs.getLong("lock_version"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getInt("issue_count")
        ));
    }

    @Override
    public void insertTemplate(NewTemplate template) {
        jdbcTemplate.update("""
                        INSERT INTO tpl.template (
                            id, organization_id, template_code, name, purpose, category,
                            format, created_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                template.id(),
                template.organizationId(),
                template.code(),
                template.name(),
                template.purpose(),
                template.category(),
                template.format().name(),
                template.actorId()
        );
    }

    @Override
    public void insertVersion(NewVersion version) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO tpl.template_version (
                        id, template_id, version_no, status, schema_jsonb, layout_summary_jsonb,
                        snapshot_kind, editor_app_version, plugin_manifest_hash,
                        snapshot_format_version, schema_hash, mapping_hash, data_hash, workspace_hash, created_by
                    ) VALUES (?, ?, 1, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setObject(1, version.id());
            statement.setObject(2, version.templateId());
            statement.setObject(3, pgJson(version.schema()));
            statement.setObject(4, pgJson(version.layoutSummary()));
            statement.setString(5, version.snapshotKind());
            statement.setString(6, version.editorAppVersion());
            statement.setString(7, version.pluginManifestHash());
            statement.setInt(8, version.layoutSummary().path("initialSnapshot")
                    .path("snapshotFormatVersion").asInt(
                            "UNIVER_WORKBOOK".equals(version.snapshotKind()) ? 3 : 1
                    ));
            statement.setString(9, version.schemaHash());
            statement.setString(10, version.mappingHash());
            statement.setString(11, version.dataHash());
            statement.setString(12, version.workspaceHash());
            statement.setObject(13, version.actorId());
            return statement;
        });
    }

    @Override
    public Optional<TemplateWorkspace> findWorkspace(UUID organizationId, UUID versionId) {
        var rows = jdbcTemplate.query("""
                        SELECT t.id AS template_id, tv.id AS version_id,
                               (SELECT tij.id FROM tpl.template_import_job tij
                                WHERE tij.generated_template_version_id = tv.id
                                ORDER BY tij.created_at DESC LIMIT 1) AS recognition_run_id,
                               t.template_code, t.name,
                               t.format, tv.status, tv.version_no, tv.schema_jsonb,
                               tv.layout_summary_jsonb, tv.editor_snapshot_file_id,
                               tv.editor_snapshot_hash, tv.snapshot_kind, tv.editor_app_version,
                               tv.plugin_manifest_hash, tv.snapshot_format_version,
                               tv.schema_hash, tv.mapping_hash, tv.data_hash, tv.workspace_hash,
                               tv.lock_version
                        FROM tpl.template_version tv
                        JOIN tpl.template t ON t.id = tv.template_id
                        WHERE tv.id = ? AND t.organization_id = ?
                        """,
                (rs, rowNum) -> mapWorkspace(rs, loadMappings(versionId)),
                versionId,
                organizationId
        );
        return rows.stream().findFirst();
    }

    @Override
    public List<TemplateVersionHistoryItem> findVersionHistory(UUID organizationId, UUID templateId) {
        return jdbcTemplate.query("""
                        SELECT tv.id, tv.version_no, tv.status, tv.created_at, tv.updated_at,
                               tv.published_at,
                               (SELECT count(*)::int FROM ops.audit_log al
                                WHERE al.aggregate_type = 'TEMPLATE_VERSION'
                                  AND al.aggregate_id = tv.id
                                  AND al.action = 'TEMPLATE_DRAFT_SAVED') AS save_count
                        FROM tpl.template_version tv
                        JOIN tpl.template t ON t.id = tv.template_id
                        WHERE tv.template_id = ? AND t.organization_id = ?
                        ORDER BY tv.version_no DESC
                        """,
                (rs, rowNum) -> new TemplateVersionHistoryItem(
                        rs.getObject("id", UUID.class),
                        rs.getInt("version_no"),
                        TemplateStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("published_at") == null
                                ? null : rs.getTimestamp("published_at").toInstant(),
                        rs.getInt("save_count")
                ),
                templateId,
                organizationId
        );
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
                    UPDATE tpl.template_version tv
                    SET schema_jsonb = ?,
                        layout_summary_jsonb = ?,
                        editor_snapshot_file_id = ?,
                        editor_snapshot_hash = ?,
                        editor_app_version = ?,
                        plugin_manifest_hash = ?,
                        snapshot_format_version = ?,
                        schema_hash = ?,
                        mapping_hash = ?,
                        data_hash = ?,
                        workspace_hash = ?,
                        lock_version = lock_version + 1,
                        updated_at = now()
                    FROM tpl.template t
                    WHERE tv.template_id = t.id
                      AND tv.id = ?
                      AND t.organization_id = ?
                      AND tv.status = 'DRAFT'
                      AND tv.lock_version = ?
                    """);
            statement.setObject(1, pgJson(update.schema()));
            statement.setObject(2, pgJson(update.layoutSummary()));
            if (update.snapshotFileId() == null) {
                statement.setNull(3, Types.OTHER);
            } else {
                statement.setObject(3, update.snapshotFileId());
            }
            statement.setString(4, update.snapshotHash());
            statement.setString(5, update.editorAppVersion());
            statement.setString(6, update.pluginManifestHash());
            statement.setInt(7, update.snapshotFormatVersion());
            statement.setString(8, update.schemaHash());
            statement.setString(9, update.mappingHash());
            statement.setString(10, update.dataHash());
            statement.setString(11, update.workspaceHash());
            statement.setObject(12, update.versionId());
            statement.setObject(13, update.organizationId());
            statement.setLong(14, update.expectedLockVersion());
            return statement;
        });
    }

    @Override
    public void replaceMappings(UUID versionId, TemplateFormat format, JsonNode mappings) {
        jdbcTemplate.update("DELETE FROM tpl.template_mapping WHERE template_version_id = ?", versionId);
        var arguments = new ArrayList<Object[]>();
        mappings.forEach(binding -> arguments.add(new Object[]{
                UUID.randomUUID(),
                versionId,
                binding.path("bindingId").asText(),
                blankToNull(binding.path("markerId").asText()),
                format.name(),
                blankToNull(binding.path("fieldCode").asText()),
                binding.path("dataPath").asText(),
                binding.path("role").asText("FIELD"),
                binding.path("locatorType").asText(),
                pgJson(binding.path("locator")),
                binding.path("syncDirection").asText("TWO_WAY"),
                binding.path("primaryBinding").asBoolean(true),
                binding.path("bindingStatus").asText("VALID"),
                pgJson(binding.path("diagnostic").isMissingNode()
                        ? objectMapper.createObjectNode()
                        : binding.path("diagnostic"))
        }));
        if (!arguments.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO tpl.template_mapping (
                        id, template_version_id, binding_id, marker_id, format, field_code,
                        data_path, binding_role, locator_type, locator_jsonb, sync_direction,
                        primary_binding, binding_status, diagnostic_jsonb, last_validated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    """, arguments);
        }
    }

    @Override
    public void insertRevision(NewRevision version) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO tpl.template_version (
                        id, template_id, version_no, status, schema_jsonb, layout_summary_jsonb,
                        editor_snapshot_file_id, editor_snapshot_hash, snapshot_kind,
                        editor_app_version, plugin_manifest_hash, snapshot_format_version,
                        schema_hash, mapping_hash, data_hash, workspace_hash,
                        derived_from_version_id, created_by
                    )
                    SELECT ?, ?, coalesce(max(version_no), 0) + 1, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    FROM tpl.template_version WHERE template_id = ?
                    """);
            statement.setObject(1, version.id());
            statement.setObject(2, version.templateId());
            statement.setObject(3, pgJson(version.schema()));
            statement.setObject(4, pgJson(version.layoutSummary()));
            statement.setObject(5, version.snapshotFileId());
            statement.setString(6, version.snapshotHash());
            statement.setString(7, version.snapshotKind());
            statement.setString(8, version.editorAppVersion());
            statement.setString(9, version.pluginManifestHash());
            statement.setInt(10, version.snapshotFormatVersion());
            statement.setString(11, version.schemaHash());
            statement.setString(12, version.mappingHash());
            statement.setString(13, version.dataHash());
            statement.setString(14, version.workspaceHash());
            statement.setObject(15, version.derivedFromVersionId());
            statement.setObject(16, version.actorId());
            statement.setObject(17, version.templateId());
            return statement;
        });
    }

    @Override
    public void copyMappings(UUID sourceVersionId, UUID targetVersionId) {
        jdbcTemplate.update("""
                INSERT INTO tpl.template_mapping (
                    id, template_version_id, binding_id, marker_id, format, field_code,
                    data_path, binding_role, locator_type, locator_jsonb, sync_direction,
                    primary_binding, binding_status, diagnostic_jsonb, last_validated_at
                )
                SELECT gen_random_uuid(), ?, binding_id, marker_id, format, field_code,
                       data_path, binding_role, locator_type, locator_jsonb, sync_direction,
                       primary_binding, binding_status, diagnostic_jsonb, now()
                FROM tpl.template_mapping WHERE template_version_id = ?
                """, targetVersionId, sourceVersionId);
    }

    @Override
    public boolean hasOpenDraft(UUID organizationId, UUID templateId) {
        var count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tpl.template_version tv
                JOIN tpl.template t ON t.id = tv.template_id
                WHERE tv.template_id = ? AND t.organization_id = ? AND tv.status = 'DRAFT'
                """, Long.class, templateId, organizationId);
        return count != null && count > 0;
    }

    @Override
    public boolean hasProductionOrderReferences(UUID organizationId, UUID versionId) {
        var count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM mfg.production_order po
                WHERE po.template_version_id = ? AND po.organization_id = ?
                """, Long.class, versionId, organizationId);
        return count != null && count > 0;
    }

    @Override
    public int deleteDraft(UUID organizationId, UUID versionId) {
        jdbcTemplate.update(
                "UPDATE tpl.template_import_job SET generated_template_version_id = NULL WHERE generated_template_version_id = ?",
                versionId
        );
        jdbcTemplate.update(
                "UPDATE tpl.template_version SET derived_from_version_id = NULL WHERE derived_from_version_id = ?",
                versionId
        );
        return jdbcTemplate.update("""
                DELETE FROM tpl.template_version tv USING tpl.template t
                WHERE tv.template_id = t.id AND tv.id = ? AND t.organization_id = ? AND tv.status = 'DRAFT'
                """, versionId, organizationId);
    }

    @Override
    public int deleteTemplateIfEmpty(UUID organizationId, UUID templateId) {
        return jdbcTemplate.update("""
                DELETE FROM tpl.template t WHERE t.id = ? AND t.organization_id = ?
                AND NOT EXISTS (SELECT 1 FROM tpl.template_version tv WHERE tv.template_id = t.id)
                """, templateId, organizationId);
    }

    @Override
    public int retireTemplate(UUID organizationId, UUID templateId) {
        var retired = jdbcTemplate.update("""
                UPDATE tpl.template_version tv SET status = 'RETIRED', updated_at = now()
                FROM tpl.template t
                WHERE tv.id = t.current_published_version_id AND t.id = ? AND t.organization_id = ?
                  AND tv.status = 'PUBLISHED'
                """, templateId, organizationId);
        if (retired > 0) {
            jdbcTemplate.update("""
                    UPDATE tpl.template SET current_published_version_id = NULL, updated_at = now()
                    WHERE id = ? AND organization_id = ?
                    """, templateId, organizationId);
        }
        return retired;
    }

    @Override
    public void appendStructureChanges(
            UUID versionId,
            String beforeMappingHash,
            String afterMappingHash,
            List<StructureChange> operations,
            UUID actorId
    ) {
        if (operations.isEmpty()) return;
        var arguments = new ArrayList<Object[]>();
        for (int index = 0; index < operations.size(); index++) {
            var operation = operations.get(index);
            arguments.add(new Object[]{
                    operation.operationId(), versionId, index, operation.type(), operation.sheetId(),
                    pgJson(operation.operation()), operation.source(), beforeMappingHash,
                    afterMappingHash, actorId
            });
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO tpl.template_structure_change (
                    id, template_version_id, operation_order, operation_type, sheet_id,
                    operation_jsonb, source, before_mapping_hash, after_mapping_hash, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, arguments);
    }

    @Override
    public boolean hasUnresolvedBlockers(UUID versionId) {
        var count = jdbcTemplate.queryForObject("""
                SELECT (
                    (SELECT count(*) FROM tpl.template_mapping
                     WHERE template_version_id = ? AND binding_status <> 'VALID')
                    +
                    (SELECT count(*)
                     FROM tpl.template_import_issue tii
                     JOIN tpl.template_import_job tij ON tij.id = tii.import_job_id
                     WHERE tij.generated_template_version_id = ?
                       AND tii.severity = 'BLOCKER'
                       AND tii.resolution = 'OPEN')
                    +
                    (SELECT count(*)
                     FROM tpl.recognition_suggestion rs
                     JOIN tpl.template_import_job tij ON tij.id = rs.import_job_id
                     WHERE tij.generated_template_version_id = ?
                       AND rs.decision = 'PENDING')
                )
                """, Long.class, versionId, versionId, versionId);
        return count != null && count > 0;
    }

    @Override
    public void publish(UUID organizationId, UUID versionId, UUID actorId) {
        var templateId = jdbcTemplate.queryForObject("""
                SELECT tv.template_id
                FROM tpl.template_version tv
                JOIN tpl.template t ON t.id = tv.template_id
                WHERE tv.id = ? AND t.organization_id = ? AND tv.status = 'DRAFT'
                FOR UPDATE
                """, UUID.class, versionId, organizationId);
        if (templateId == null) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE tpl.template_version
                SET status = 'PUBLISHED', published_at = now(), updated_at = now()
                WHERE id = ?
                """, versionId);
        jdbcTemplate.update("""
                UPDATE tpl.template
                SET current_published_version_id = ?, updated_at = now()
                WHERE id = ?
                """, versionId, templateId);
    }

    @Override
    public void appendAudit(
            UUID organizationId,
            UUID actorId,
            String action,
            String aggregateType,
            UUID aggregateId,
            JsonNode detail
    ) {
        jdbcTemplate.update("""
                        INSERT INTO ops.audit_log (
                            id, organization_id, actor_id, action, aggregate_type,
                            aggregate_id, detail_jsonb
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                organizationId,
                actorId,
                action,
                aggregateType,
                aggregateId,
                pgJson(detail)
        );
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

    private ArrayNode loadMappings(UUID versionId) {
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
                    binding.set("locator", parseJson(rs.getString("locator_jsonb")));
                    binding.put("syncDirection", rs.getString("sync_direction"));
                    binding.put("primaryBinding", rs.getBoolean("primary_binding"));
                    binding.put("bindingStatus", rs.getString("binding_status"));
                    binding.set("diagnostic", parseJson(rs.getString("diagnostic_jsonb")));
                    result.add(binding);
                },
                versionId
        );
        return result;
    }

    private TemplateWorkspace mapWorkspace(ResultSet rs, ArrayNode mapping) throws SQLException {
        var layoutSummary = parseJson(rs.getString("layout_summary_jsonb"));
        return new TemplateWorkspace(
                rs.getObject("template_id", UUID.class),
                rs.getObject("version_id", UUID.class),
                rs.getObject("recognition_run_id", UUID.class),
                rs.getString("template_code"),
                rs.getString("name"),
                TemplateFormat.valueOf(rs.getString("format")),
                TemplateStatus.valueOf(rs.getString("status")),
                rs.getInt("version_no"),
                parseJson(rs.getString("schema_jsonb")),
                mapping,
                objectMapper.createObjectNode(),
                layoutSummary.path("initialSnapshot"),
                rs.getObject("editor_snapshot_file_id", UUID.class),
                rs.getString("editor_snapshot_hash"),
                rs.getString("snapshot_kind"),
                rs.getString("editor_app_version"),
                rs.getString("plugin_manifest_hash"),
                rs.getInt("snapshot_format_version"),
                rs.getString("schema_hash"),
                rs.getString("mapping_hash"),
                rs.getString("data_hash"),
                rs.getString("workspace_hash"),
                rs.getLong("lock_version"),
                layoutSummary.path("reconciliationRequired").asBoolean(false)
        );
    }

    private PGobject pgJson(JsonNode value) {
        try {
            var result = new PGobject();
            result.setType("jsonb");
            result.setValue(objectMapper.writeValueAsString(value));
            return result;
        } catch (SQLException | JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize JSONB", exception);
        }
    }

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to parse JSONB", exception);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
