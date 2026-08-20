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
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private static final String LOGICAL_TEMPLATES_CTE = """
            WITH logical_templates AS (
                SELECT t.id AS template_id,
                       coalesce(draft.id, published.id, retired.id, latest.id) AS version_id,
                       t.template_code, t.name, t.category_id,
                       coalesce(tc.name, t.category) AS category, t.format,
                       CASE
                           WHEN published.id IS NOT NULL THEN published.status
                           WHEN retired.id IS NOT NULL THEN 'RETIRED'
                           ELSE coalesce(draft.status, latest.status)
                       END AS lifecycle_status,
                       coalesce(draft.version_no, published.version_no, retired.version_no, latest.version_no) AS version_no,
                       coalesce(draft.lock_version, published.lock_version, retired.lock_version, latest.lock_version) AS lock_version,
                       greatest(t.updated_at, coalesce(draft.updated_at, published.updated_at, retired.updated_at, latest.updated_at)) AS effective_updated_at,
                       t.current_published_version_id,
                       published.version_no AS published_version_no,
                       retired.version_no AS retired_version_no,
                       draft.id AS draft_version_id,
                       draft.version_no AS draft_version_no,
                       t.created_by, au.display_name AS created_by_name, t.created_at
                FROM tpl.template t
                LEFT JOIN tpl.template_category tc ON tc.id = t.category_id
                LEFT JOIN iam.app_user au ON au.id = t.created_by
                LEFT JOIN tpl.template_version published ON published.id = t.current_published_version_id
                LEFT JOIN LATERAL (
                    SELECT tv.* FROM tpl.template_version tv
                    WHERE tv.template_id = t.id AND tv.status = 'RETIRED'
                    ORDER BY tv.version_no DESC LIMIT 1
                ) retired ON true
                LEFT JOIN LATERAL (
                    SELECT tv.* FROM tpl.template_version tv
                    WHERE tv.template_id = t.id AND tv.status = 'DRAFT'
                    ORDER BY tv.version_no DESC LIMIT 1
                ) draft ON true
                LEFT JOIN LATERAL (
                    SELECT tv.* FROM tpl.template_version tv
                    WHERE tv.template_id = t.id
                    ORDER BY tv.version_no DESC LIMIT 1
                ) latest ON true
                WHERE t.organization_id = :organizationId
            )
            """;

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
        return findTemplates(new TemplateQuery(organizationId, keyword, null, false, format, status,
                null, null, null, "UPDATED_AT", "DESC", 1, 1000)).items();
    }

    @Override
    public TemplatePage findTemplates(TemplateQuery query) {
        var sql = new StringBuilder(LOGICAL_TEMPLATES_CTE).append("""
                SELECT lt.*,
                       (SELECT count(*)::int FROM tpl.template_mapping tm
                        WHERE tm.template_version_id = lt.version_id
                          AND tm.binding_status <> 'VALID') AS issue_count,
                       count(*) OVER() AS total_count
                FROM logical_templates lt
                WHERE lt.version_id IS NOT NULL
                """);
        var parameters = new MapSqlParameterSource()
                .addValue("organizationId", query.organizationId());
        if (query.categoryId() != null) {
            sql.append(" AND lt.category_id = :categoryId\n");
            parameters.addValue("categoryId", query.categoryId());
        } else if (query.uncategorized()) {
            sql.append(" AND lt.category_id IS NULL\n");
        }
        appendCommonFilters(sql, parameters, query.keyword(), query.format(), query.status(),
                query.createdBy(), query.updatedFrom(), query.updatedTo());
        appendScopeFilter(sql, parameters, query.scope());
        var countSql = "SELECT count(*) FROM (" + sql + ") page_count";
        var total = java.util.Optional.ofNullable(namedJdbcTemplate.queryForObject(
                countSql, parameters, Long.class)).orElse(0L);
        var sortColumn = switch (query.sortBy() == null ? "" : query.sortBy().toUpperCase(java.util.Locale.ROOT)) {
            case "CREATED_AT" -> "lt.created_at";
            case "NAME" -> "lower(lt.name)";
            default -> "lt.effective_updated_at";
        };
        var direction = "ASC".equalsIgnoreCase(query.sortDirection()) ? "ASC" : "DESC";
        var page = Math.max(1, query.page());
        var size = Math.max(1, Math.min(200, query.size()));
        sql.append(" ORDER BY ").append(sortColumn).append(' ').append(direction)
                .append(", lt.template_id LIMIT :limit OFFSET :offset");
        parameters.addValue("limit", size).addValue("offset", (page - 1) * size);
        var rows = namedJdbcTemplate.query(sql.toString(), parameters, (rs, rowNum) -> new TemplateListItem(
                rs.getObject("template_id", UUID.class),
                rs.getObject("version_id", UUID.class),
                rs.getString("template_code"),
                rs.getString("name"),
                rs.getString("category"),
                TemplateFormat.valueOf(rs.getString("format")),
                TemplateStatus.valueOf(rs.getString("lifecycle_status")),
                rs.getInt("version_no"),
                rs.getLong("lock_version"),
                rs.getTimestamp("effective_updated_at").toInstant(),
                rs.getInt("issue_count"),
                rs.getObject("category_id", UUID.class),
                rs.getObject("current_published_version_id", UUID.class),
                (Integer) rs.getObject("published_version_no"),
                (Integer) rs.getObject("retired_version_no"),
                rs.getObject("draft_version_id", UUID.class),
                (Integer) rs.getObject("draft_version_no"),
                rs.getObject("draft_version_id") != null,
                rs.getObject("created_by", UUID.class),
                rs.getString("created_by_name"),
                rs.getTimestamp("created_at").toInstant()
        ));
        var safeTotal = total;
        return new TemplatePage(rows, safeTotal, page, size,
                safeTotal == 0 ? 0 : (int) Math.ceil(safeTotal / (double) size));
    }

    @Override
    public TemplateFacetSummary findTemplateFacets(TemplateFacetQuery query) {
        var sql = new StringBuilder(LOGICAL_TEMPLATES_CTE).append("""
                , filtered_templates AS (
                    SELECT lt.*
                    FROM logical_templates lt
                    WHERE lt.version_id IS NOT NULL
                """);
        var parameters = new MapSqlParameterSource()
                .addValue("organizationId", query.organizationId());
        appendCommonFilters(sql, parameters, query.keyword(), query.format(), query.status(),
                query.createdBy(), query.updatedFrom(), query.updatedTo());
        appendScopeFilter(sql, parameters, query.scope());
        sql.append("""
                ), category_counts AS (
                    SELECT category_id, count(*)::bigint AS template_count
                    FROM filtered_templates
                    GROUP BY category_id
                )
                SELECT c.id AS category_id, coalesce(cc.template_count, 0)::bigint AS template_count
                FROM tpl.template_category c
                LEFT JOIN category_counts cc ON cc.category_id = c.id
                WHERE c.organization_id = :organizationId
                UNION ALL
                SELECT NULL::uuid AS category_id,
                       coalesce((SELECT template_count FROM category_counts WHERE category_id IS NULL), 0)::bigint
                """);
        var counts = namedJdbcTemplate.query(sql.toString(), parameters,
                (rs, rowNum) -> new TemplateCategoryCount(
                        rs.getObject("category_id", UUID.class), rs.getLong("template_count")));
        var uncategorized = counts.stream()
                .filter(item -> item.categoryId() == null)
                .mapToLong(TemplateCategoryCount::count)
                .sum();
        var categories = counts.stream().filter(item -> item.categoryId() != null).toList();
        var total = counts.stream().mapToLong(TemplateCategoryCount::count).sum();
        return new TemplateFacetSummary(total, uncategorized, categories);
    }

    private void appendCommonFilters(
            StringBuilder sql,
            MapSqlParameterSource parameters,
            String requestedKeyword,
            TemplateFormat format,
            TemplateStatus status,
            UUID createdBy,
            java.time.Instant updatedFrom,
            java.time.Instant updatedTo
    ) {
        var keyword = blankToNull(requestedKeyword);
        if (keyword != null) {
            sql.append(" AND (lower(lt.name) LIKE lower(:keywordPattern) OR lower(lt.template_code) LIKE lower(:keywordPattern))\n");
            parameters.addValue("keywordPattern", "%" + keyword + "%");
        }
        if (format != null) {
            sql.append(" AND lt.format = :format\n");
            parameters.addValue("format", format.name());
        }
        if (status != null) {
            sql.append(" AND lt.lifecycle_status = :status\n");
            parameters.addValue("status", status.name());
        }
        if (createdBy != null) {
            sql.append(" AND lt.created_by = :createdBy\n");
            parameters.addValue("createdBy", createdBy);
        }
        if (updatedFrom != null) {
            sql.append(" AND lt.effective_updated_at >= :updatedFrom\n");
            parameters.addValue("updatedFrom", java.sql.Timestamp.from(updatedFrom));
        }
        if (updatedTo != null) {
            sql.append(" AND lt.effective_updated_at <= :updatedTo\n");
            parameters.addValue("updatedTo", java.sql.Timestamp.from(updatedTo));
        }
    }

    private void appendScopeFilter(StringBuilder sql, MapSqlParameterSource parameters,
                                   TemplateRepository.DataScopeFilter scope) {
        if (scope == null || "ALL".equals(scope.type())) return;
        switch (scope.type()) {
            case "SELF" -> {
                sql.append(" AND lt.created_by = :scopeActorId\n");
                parameters.addValue("scopeActorId", scope.actorId());
            }
            case "SELECTED" -> {
                if (scope.targetIds().isEmpty()) sql.append(" AND 1 = 0\n");
                else {
                    sql.append(" AND lt.template_id IN (:scopeTargetIds)\n");
                    parameters.addValue("scopeTargetIds", scope.targetIds());
                }
            }
            case "CATEGORY" -> {
                if (scope.targetIds().isEmpty()) sql.append(" AND 1 = 0\n");
                else {
                    sql.append(" AND lt.category_id IN (:scopeTargetIds)\n");
                    parameters.addValue("scopeTargetIds", scope.targetIds());
                }
            }
            // A template has no assignee/project relation in the current model.
            // Returning an empty set is safer than broadening access.
            case "ASSIGNED", "PROJECT" -> sql.append(" AND 1 = 0\n");
            default -> sql.append(" AND 1 = 0\n");
        }
    }

    @Override
    public List<TemplateCreatorOption> findTemplateCreators(UUID organizationId) {
        return jdbcTemplate.query("""
                SELECT DISTINCT u.id, u.display_name
                FROM tpl.template t JOIN iam.app_user u ON u.id = t.created_by
                WHERE t.organization_id = ?
                ORDER BY u.display_name, u.id
                """, (rs, rowNum) -> new TemplateCreatorOption(
                rs.getObject("id", UUID.class), rs.getString("display_name")), organizationId);
    }

    @Override
    public Optional<TemplateSummary> findTemplateSummary(UUID organizationId, UUID templateId) {
        return jdbcTemplate.query("""
                SELECT t.id, t.name, t.category_id, coalesce(c.name, t.category) AS category
                FROM tpl.template t LEFT JOIN tpl.template_category c ON c.id = t.category_id
                WHERE t.organization_id = ? AND t.id = ?
                """, (rs, rowNum) -> new TemplateSummary(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("category_id", UUID.class), rs.getString("category")), organizationId, templateId)
                .stream().findFirst();
    }

    @Override
    public List<TemplateListItem> findPublishedDataTemplates(UUID organizationId) {
        return findTemplates(new TemplateQuery(organizationId, null, null, false, null, TemplateStatus.PUBLISHED,
                null, null, null, "UPDATED_AT", "DESC", 1, 1000)).items().stream()
                .map(item -> item.currentPublishedVersionId() == null ? item : new TemplateListItem(
                        item.templateId(), item.currentPublishedVersionId(), item.templateCode(), item.name(),
                        item.category(), item.format(), TemplateStatus.PUBLISHED,
                        item.currentPublishedVersionNo() == null ? item.versionNo() : item.currentPublishedVersionNo(),
                        item.lockVersion(), item.updatedAt(), item.issueCount(), item.categoryId(),
                        item.currentPublishedVersionId(), item.currentPublishedVersionNo(), item.retiredVersionNo(), item.draftVersionId(),
                        item.draftVersionNo(), item.hasDraft(), item.createdBy(), item.createdByName(), item.createdAt()))
                .toList();
    }

    @Override
    public Optional<TemplateWorkspace> findPublishedDataTemplate(UUID organizationId, UUID versionId) {
        return findWorkspace(organizationId, versionId)
                .filter(item -> item.status() == TemplateStatus.PUBLISHED);
    }

    @Override
    public void insertTemplate(NewTemplate template) {
        jdbcTemplate.update("""
                        INSERT INTO tpl.template (
                            id, organization_id, template_code, name, category, category_id,
                            format, created_by
                        ) VALUES (?, ?, ?, ?, ?, (
                            SELECT id FROM tpl.template_category WHERE organization_id = ? AND name = ?
                        ), ?, ?)
                        """,
                template.id(),
                template.organizationId(),
                template.code(),
                template.name(),
                template.category(),
                template.organizationId(),
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
                        snapshot_format_version,
                        schema_hash, mapping_hash, data_hash, workspace_hash, created_by
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
                                t.format, tv.status, tv.version_no,
                                tv.schema_jsonb,
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
                               tv.published_at, tv.derived_from_version_id, tv.created_by,
                               au.display_name AS created_by_name,
                               (t.current_published_version_id = tv.id) AS current_published,
                               NOT EXISTS (
                                   SELECT 1 FROM tpl.template_version draft
                                   WHERE draft.template_id = tv.template_id AND draft.status = 'DRAFT'
                               ) AS can_rollback,
                               (SELECT count(*)::int FROM ops.audit_log al
                                WHERE al.aggregate_type = 'TEMPLATE_VERSION'
                                  AND al.aggregate_id = tv.id
                                  AND al.action = 'TEMPLATE_DRAFT_SAVED') AS save_count
                        FROM tpl.template_version tv
                        JOIN tpl.template t ON t.id = tv.template_id
                        LEFT JOIN iam.app_user au ON au.id = tv.created_by
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
                        rs.getInt("save_count"),
                        rs.getObject("derived_from_version_id", UUID.class),
                        rs.getObject("created_by", UUID.class),
                        rs.getString("created_by_name"),
                        rs.getBoolean("current_published"),
                        rs.getBoolean("can_rollback")
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
        mappings.forEach(binding -> {
            var role = textOrDefault(binding.path("role"), "FIELD");
            var mappingKind = textOrDefault(binding.path("mappingKind"),
                    "REPEAT_REGION".equals(role) ? "REPEAT_REGION" : "SCALAR");
            var diagnostic = binding.path("diagnostic").isObject()
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) binding.path("diagnostic").deepCopy()
                    : objectMapper.createObjectNode();
            copyMappingMetadata(binding, diagnostic, "fieldName");
            copyMappingMetadata(binding, diagnostic, "valueType");
            copyMappingMetadata(binding, diagnostic, "unit");
            copyMappingMetadata(binding, diagnostic, "required");
            copyMappingMetadata(binding, diagnostic, "identity");
            copyMappingMetadata(binding, diagnostic, "trainingEligible");
            copyMappingMetadata(binding, diagnostic, "valueSource");
            if (binding.path("aliases").isArray()) diagnostic.set("aliases", binding.path("aliases"));
            arguments.add(new Object[]{
                    UUID.randomUUID(),
                    versionId,
                    binding.path("bindingId").asText(),
                    uuidOrNull(nullableText(binding.path("fieldId"))),
                    nullableText(binding.path("parentBindingId")),
                    nullableText(binding.path("markerId")),
                    format.name(),
                    nullableText(binding.path("fieldCode")),
                    binding.path("dataPath").asText(),
                    role,
                    mappingKind,
                    normalizeRepeatAxis(mappingKind, nullableText(binding.path("repeatAxis"))),
                    binding.path("recordHeight").asInt(1),
                    binding.path("recordWidth").asInt(1),
                    binding.path("recordStride").asInt(1),
                    binding.path("locatorType").asText(),
                    pgJson(binding.path("locator")),
                    binding.path("syncDirection").asText("TWO_WAY"),
                    binding.path("primaryBinding").asBoolean(true),
                    binding.path("bindingStatus").asText("VALID"),
                    pgJson(diagnostic),
                    pgJson(binding.path("termination").isMissingNode()
                            ? objectMapper.createObjectNode()
                            : binding.path("termination"))
            });
        });
        if (!arguments.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO tpl.template_mapping (
                        id, template_version_id, binding_id, field_id, parent_binding_id, marker_id,
                        format, field_code, data_path, binding_role, mapping_kind, repeat_axis,
                        record_height, record_width, record_stride, locator_type, locator_jsonb,
                        sync_direction, primary_binding, binding_status, diagnostic_jsonb,
                        termination_jsonb, last_validated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    """, arguments);
        }
    }

    private void copyMappingMetadata(JsonNode binding, ObjectNode diagnostic, String name) {
        if (binding.has(name) && !binding.path(name).isNull()) diagnostic.set(name, binding.path(name));
    }

    @Override
    public void insertRevision(NewRevision version) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO tpl.template_version (
                        id, template_id, version_no, status, schema_jsonb, layout_summary_jsonb,
                        editor_snapshot_file_id, editor_snapshot_hash, snapshot_kind,
                        editor_app_version, plugin_manifest_hash,
                        snapshot_format_version,
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
    public void insertCopiedVersion(NewRevision version) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO tpl.template_version (
                        id, template_id, version_no, status, schema_jsonb, layout_summary_jsonb,
                        editor_snapshot_file_id, editor_snapshot_hash, snapshot_kind,
                        editor_app_version, plugin_manifest_hash, snapshot_format_version,
                        schema_hash, mapping_hash, data_hash, workspace_hash,
                        derived_from_version_id, created_by
                    ) VALUES (?, ?, 1, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            return statement;
        });
    }

    @Override
    public void copyMappings(UUID sourceVersionId, UUID targetVersionId, boolean preserveRecognitionReference) {
        jdbcTemplate.update("""
                INSERT INTO tpl.template_mapping (
                    id, template_version_id, binding_id, field_id, parent_binding_id, marker_id,
                    format, field_code, data_path, binding_role, mapping_kind, repeat_axis,
                    record_height, record_width, record_stride, locator_type, locator_jsonb,
                    sync_direction, primary_binding, binding_status, diagnostic_jsonb,
                    termination_jsonb, last_validated_at
                )
                SELECT gen_random_uuid(), ?, binding_id, field_id, parent_binding_id, marker_id,
                       format, field_code, data_path, binding_role, mapping_kind, repeat_axis,
                       record_height, record_width, record_stride, locator_type, locator_jsonb,
                       sync_direction, primary_binding, binding_status,
                       CASE WHEN ? THEN diagnostic_jsonb
                            ELSE coalesce(diagnostic_jsonb, '{}'::jsonb) - 'recognitionItemId' END,
                       termination_jsonb, now()
                FROM tpl.template_mapping WHERE template_version_id = ?
                """, targetVersionId, preserveRecognitionReference, sourceVersionId);
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
    public List<TemplateCategoryItem> findCategories(UUID organizationId) {
        return jdbcTemplate.query("""
                SELECT c.id, c.name, c.description, c.sort_order,
                       count(t.id) FILTER (WHERE EXISTS (
                           SELECT 1 FROM tpl.template_version tv WHERE tv.template_id = t.id
                       ))::int AS template_count
                FROM tpl.template_category c
                LEFT JOIN tpl.template t ON t.category_id = c.id AND t.organization_id = c.organization_id
                WHERE c.organization_id = ?
                GROUP BY c.id, c.name, c.description, c.sort_order, c.created_at
                ORDER BY c.sort_order, c.created_at, c.name
                """, (rs, rowNum) -> new TemplateCategoryItem(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getInt("sort_order"), rs.getInt("template_count")), organizationId);
    }

    @Override
    public void insertCategory(UUID id, UUID organizationId, String name, String description, int sortOrder, UUID actorId) {
        jdbcTemplate.update("""
                INSERT INTO tpl.template_category (id, organization_id, name, description, sort_order, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, organizationId, name, description, sortOrder, actorId);
    }

    @Override
    public Optional<TemplateCategoryItem> findCategory(UUID organizationId, UUID categoryId) {
        return jdbcTemplate.query("""
                SELECT c.id, c.name, c.description, c.sort_order,
                       count(t.id) FILTER (WHERE EXISTS (
                           SELECT 1 FROM tpl.template_version tv WHERE tv.template_id = t.id
                       ))::int AS template_count
                FROM tpl.template_category c
                LEFT JOIN tpl.template t ON t.category_id = c.id AND t.organization_id = c.organization_id
                WHERE c.organization_id = ? AND c.id = ?
                GROUP BY c.id, c.name, c.description, c.sort_order
                """, (rs, rowNum) -> new TemplateCategoryItem(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getInt("sort_order"), rs.getInt("template_count")), organizationId, categoryId)
                .stream().findFirst();
    }

    @Override
    public boolean categoryNameExists(UUID organizationId, String name, UUID excludingId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM tpl.template_category
                WHERE organization_id = ? AND lower(name) = lower(?) AND (?::uuid IS NULL OR id <> ?::uuid))
                """, Boolean.class, organizationId, name, excludingId, excludingId));
    }

    @Override
    public int renameCategory(UUID organizationId, UUID categoryId, String name, String description) {
        var updated = jdbcTemplate.update("""
                UPDATE tpl.template_category SET name = ?, description = ?, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, name, description, organizationId, categoryId);
        if (updated > 0) {
            jdbcTemplate.update("""
                    UPDATE tpl.template SET category = ?, updated_at = now()
                    WHERE organization_id = ? AND category_id = ?
                    """, name, organizationId, categoryId);
        }
        return updated;
    }

    @Override
    public int deleteCategory(UUID organizationId, UUID categoryId, UUID replacementCategoryId) {
        var replacementName = replacementCategoryId == null ? null : jdbcTemplate.queryForObject("""
                SELECT name FROM tpl.template_category WHERE organization_id = ? AND id = ?
                """, String.class, organizationId, replacementCategoryId);
        jdbcTemplate.update("""
                UPDATE tpl.template SET category_id = ?, category = ?, updated_at = now()
                WHERE organization_id = ? AND category_id = ?
                """, replacementCategoryId, replacementName, organizationId, categoryId);
        return jdbcTemplate.update("DELETE FROM tpl.template_category WHERE organization_id = ? AND id = ?",
                organizationId, categoryId);
    }

    @Override
    public int assignTemplateCategory(UUID organizationId, UUID templateId, UUID categoryId) {
        if (categoryId == null) {
            return jdbcTemplate.update("""
                    UPDATE tpl.template SET category_id = NULL, category = NULL, updated_at = now()
                    WHERE organization_id = ? AND id = ?
                    """, organizationId, templateId);
        }
        return jdbcTemplate.update("""
                UPDATE tpl.template t SET category_id = ?, category = c.name, updated_at = now()
                FROM tpl.template_category c
                WHERE t.organization_id = ? AND t.id = ? AND c.organization_id = ? AND c.id = ?
                """, categoryId, organizationId, templateId, organizationId, categoryId);
    }

    @Override
    public int renameTemplate(UUID organizationId, UUID templateId, String name) {
        return jdbcTemplate.update("""
                UPDATE tpl.template SET name = ?, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, name, organizationId, templateId);
    }

    @Override
    public void ensureCategory(UUID organizationId, String name, UUID actorId) {
        jdbcTemplate.update("""
                INSERT INTO tpl.template_category (id, organization_id, name, sort_order, created_by)
                VALUES (?, ?, ?, coalesce((SELECT max(sort_order) + 1 FROM tpl.template_category WHERE organization_id = ?), 0), ?)
                ON CONFLICT (organization_id, name) DO NOTHING
                """, UUID.randomUUID(), organizationId, name, organizationId, actorId);
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
        var current = jdbcTemplate.query("""
                SELECT tv.template_id, t.current_published_version_id
                FROM tpl.template_version tv
                JOIN tpl.template t ON t.id = tv.template_id
                WHERE tv.id = ? AND t.organization_id = ? AND tv.status = 'DRAFT'
                FOR UPDATE
                """, (rs, rowNum) -> new UUID[]{
                rs.getObject("template_id", UUID.class),
                rs.getObject("current_published_version_id", UUID.class)}, versionId, organizationId);
        if (current.isEmpty()) {
            return;
        }
        var templateId = current.getFirst()[0];
        var oldPublished = current.getFirst()[1];
        if (oldPublished != null && !oldPublished.equals(versionId)) {
            jdbcTemplate.update("""
                    UPDATE tpl.template_version
                    SET status = 'RETIRED', updated_at = now()
                    WHERE id = ? AND status = 'PUBLISHED'
                    """, oldPublished);
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
    public void saveImportContract(UUID organizationId, UUID versionId, int importContractVersion,
                                   int layoutStructureVersion, String contractHash, JsonNode contract, UUID actorId) {
        jdbcTemplate.update("""
                INSERT INTO tpl.template_import_contract (
                    template_version_id, import_contract_version, layout_structure_version,
                    contract_hash, contract_jsonb, created_by
                )
                SELECT tv.id, ?, ?, ?, ?, ?
                FROM tpl.template_version tv JOIN tpl.template t ON t.id = tv.template_id
                WHERE tv.id = ? AND t.organization_id = ? AND tv.status = 'DRAFT'
                ON CONFLICT (template_version_id) DO UPDATE SET
                    import_contract_version = EXCLUDED.import_contract_version,
                    layout_structure_version = EXCLUDED.layout_structure_version,
                    contract_hash = EXCLUDED.contract_hash,
                    contract_jsonb = EXCLUDED.contract_jsonb,
                    created_by = EXCLUDED.created_by,
                    created_at = now()
                """, importContractVersion, layoutStructureVersion, contractHash, pgJson(contract), actorId,
                versionId, organizationId);
    }

    @Override
    public Optional<ImportContract> findImportContract(UUID organizationId, UUID versionId) {
        return jdbcTemplate.query("""
                SELECT c.import_contract_version, c.layout_structure_version,
                       c.contract_hash, c.contract_jsonb
                FROM tpl.template_import_contract c
                JOIN tpl.template_version tv ON tv.id = c.template_version_id
                JOIN tpl.template t ON t.id = tv.template_id
                WHERE c.template_version_id = ? AND t.organization_id = ?
                """, (rs, rowNum) -> new ImportContract(
                rs.getInt("import_contract_version"), rs.getInt("layout_structure_version"),
                rs.getString("contract_hash"), parseJson(rs.getString("contract_jsonb"))),
                versionId, organizationId).stream().findFirst();
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
                        SELECT binding_id, field_id, parent_binding_id, marker_id, field_code, data_path,
                               binding_role, mapping_kind, repeat_axis, record_height, record_width,
                               record_stride, locator_type, locator_jsonb, sync_direction, primary_binding,
                               binding_status, diagnostic_jsonb, termination_jsonb
                        FROM tpl.template_mapping
                        WHERE template_version_id = ?
                        ORDER BY data_path, binding_id
                        """,
                rs -> {
                    var binding = objectMapper.createObjectNode();
                    binding.put("bindingId", rs.getString("binding_id"));
                    var fieldId = rs.getObject("field_id", UUID.class);
                    if (fieldId != null) binding.put("fieldId", fieldId.toString());
                    binding.put("parentBindingId", rs.getString("parent_binding_id"));
                    binding.put("markerId", rs.getString("marker_id"));
                    binding.put("fieldCode", rs.getString("field_code"));
                    binding.put("dataPath", rs.getString("data_path"));
                    binding.put("role", rs.getString("binding_role"));
                    binding.put("mappingKind", rs.getString("mapping_kind"));
                    binding.put("repeatAxis", rs.getString("repeat_axis"));
                    binding.put("recordHeight", rs.getInt("record_height"));
                    binding.put("recordWidth", rs.getInt("record_width"));
                    binding.put("recordStride", rs.getInt("record_stride"));
                    binding.put("locatorType", rs.getString("locator_type"));
                    binding.set("locator", parseJson(rs.getString("locator_jsonb")));
                    binding.put("syncDirection", rs.getString("sync_direction"));
                    binding.put("primaryBinding", rs.getBoolean("primary_binding"));
                    binding.put("bindingStatus", rs.getString("binding_status"));
                    var diagnostic = parseJson(rs.getString("diagnostic_jsonb"));
                    binding.set("diagnostic", diagnostic);
                    for (String metadata : List.of(
                            "fieldName", "valueType", "unit", "required", "identity",
                            "trainingEligible", "valueSource", "aliases")) {
                        if (!binding.has(metadata) && diagnostic.has(metadata)) {
                            binding.set(metadata, diagnostic.path(metadata));
                        }
                    }
                    binding.set("termination", parseJson(rs.getString("termination_jsonb")));
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
                layoutSummary.path("documentStructure"),
                layoutSummary.path("wordDocument"),
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

    @Override
    public int updatePublishedWordDocument(UUID organizationId, UUID versionId, JsonNode wordDocument) {
        return jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    UPDATE tpl.template_version tv
                    SET layout_summary_jsonb = jsonb_set(
                            COALESCE(tv.layout_summary_jsonb, '{}'::jsonb),
                            '{wordDocument}', ?::jsonb, true),
                        updated_at = now()
                    FROM tpl.template t
                    WHERE tv.id = ?
                      AND tv.template_id = t.id
                      AND t.organization_id = ?
                      AND tv.status = 'DRAFT'
                    """);
            statement.setString(1, wordDocument.toString());
            statement.setObject(2, versionId);
            statement.setObject(3, organizationId);
            return statement;
        });
    }

    private String nullableText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        var value = blankToNull(node.asText());
        return "null".equalsIgnoreCase(value) ? null : value;
    }

    private String textOrDefault(JsonNode node, String defaultValue) {
        var value = nullableText(node);
        return value == null ? defaultValue : value;
    }

    private String normalizeRepeatAxis(String mappingKind, String repeatAxis) {
        var repeating = "REPEAT_REGION".equals(mappingKind) || "REPEAT_FIELD".equals(mappingKind)
                || "MATRIX_REGION".equals(mappingKind) || "MATRIX_FIELD".equals(mappingKind);
        if (repeatAxis == null) return repeating ? "ROW" : null;
        if (!"ROW".equals(repeatAxis) && !"COLUMN".equals(repeatAxis)) {
            throw new IllegalArgumentException(
                    "Mapping repeatAxis 只能是 ROW 或 COLUMN，当前值: " + repeatAxis);
        }
        return repeatAxis;
    }

    private UUID uuidOrNull(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
