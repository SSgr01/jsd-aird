package com.jsd.aird.spc.infrastructure;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.spc.application.port.SpectrumRepository;
import com.jsd.aird.iam.api.RecordDataScope;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSpectrumRepository implements SpectrumRepository {
    @Override public void lockCategory(UUID org,UUID id){
        if(jdbc.query("SELECT id FROM spc.chart_category WHERE organization_id=? AND id=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),org,id).isEmpty())
            throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.NOT_FOUND,"图谱分类不存在");
    }
    @Override public void lockChart(UUID org,UUID id){
        if(jdbc.query("SELECT id FROM spc.chart_asset WHERE organization_id=? AND id=? AND status<>'DELETED' FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),org,id).isEmpty())
            throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.NOT_FOUND,"图谱不存在或已删除");
    }

    private final JdbcTemplate jdbc;

    public JdbcSpectrumRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CategoryRow> listCategories(UUID organizationId) {
        return jdbc.query("""
                SELECT c.id, c.organization_id, c.code, c.name, c.description, c.analysis_hint,
                       c.fields_jsonb::text, c.sort_order, c.system_category, count(a.id) AS chart_count
                FROM spc.chart_category c
                LEFT JOIN spc.chart_asset a ON a.category_id = c.id AND a.organization_id = c.organization_id
                    AND a.status = 'READY'
                WHERE c.organization_id = ?
                GROUP BY c.id, c.organization_id, c.code, c.name, c.description, c.analysis_hint,
                         c.fields_jsonb, c.sort_order, c.system_category, c.created_at
                ORDER BY c.sort_order, c.name
                """, this::mapCategory, organizationId);
    }

    @Override
    public Optional<CategoryRow> findCategory(UUID organizationId, UUID categoryId) {
        return jdbc.query("""
                SELECT c.id, c.organization_id, c.code, c.name, c.description, c.analysis_hint,
                       c.fields_jsonb::text, c.sort_order, c.system_category,
                       (SELECT count(*) FROM spc.chart_asset a WHERE a.category_id = c.id
                           AND a.organization_id = c.organization_id AND a.status = 'READY') AS chart_count
                FROM spc.chart_category c
                WHERE c.organization_id = ? AND c.id = ?
                """, this::mapCategory, organizationId, categoryId).stream().findFirst();
    }

    @Override
    public Optional<CategoryRow> findCategoryByCode(UUID organizationId, String code) {
        return jdbc.query("""
                SELECT c.id, c.organization_id, c.code, c.name, c.description, c.analysis_hint,
                       c.fields_jsonb::text, c.sort_order, c.system_category,
                       (SELECT count(*) FROM spc.chart_asset a WHERE a.category_id = c.id
                           AND a.organization_id = c.organization_id AND a.status = 'READY') AS chart_count
                FROM spc.chart_category c
                WHERE c.organization_id = ? AND c.code = ?
                """, this::mapCategory, organizationId, code).stream().findFirst();
    }

    @Override
    public void lockCategoryName(UUID organizationId, String name) {
        var lockKey = organizationId + ":spectrum-category-name:" + name.trim().toLowerCase(java.util.Locale.ROOT);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
                rs -> null, lockKey);
    }

    @Override
    public boolean categoryNameExists(UUID organizationId, String name, UUID excludingCategoryId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM spc.chart_category
                    WHERE organization_id = ?
                      AND lower(btrim(name)) = lower(btrim(?))
                      AND (CAST(? AS uuid) IS NULL OR id <> CAST(? AS uuid))
                )
                """, Boolean.class, organizationId, name, excludingCategoryId, excludingCategoryId));
    }

    @Override
    public void lockCategoryCode(UUID organizationId, String code) {
        var lockKey = organizationId + ":spectrum-category-code:" + code;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
                rs -> null, lockKey);
    }

    @Override
    public UUID ensureUncategorizedCategory(UUID organizationId, UUID createdBy) {
        var existing = findCategoryByCode(organizationId, "UNCATEGORIZED");
        if (existing.isPresent()) return existing.get().id();
        var byName = jdbc.query("SELECT id FROM spc.chart_category WHERE organization_id = ? AND btrim(name) = '未分类' ORDER BY sort_order LIMIT 1",
                (rs, n) -> rs.getObject(1, UUID.class), organizationId);
        if (!byName.isEmpty()) {
            var id = byName.getFirst();
            jdbc.update("UPDATE spc.chart_category SET system_category = true WHERE organization_id = ? AND id = ?", organizationId, id);
            return id;
        }
        var id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO spc.chart_category (
                        id, organization_id, code, name, description, analysis_hint, fields_jsonb,
                        sort_order, system_category, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, true, ?)
                    """, id, organizationId, "UNCATEGORIZED", "未分类", "未归属分类的图谱", "", "[]", 9999, createdBy);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return findCategoryByCode(organizationId, "UNCATEGORIZED").map(CategoryRow::id).orElse(id);
        }
        return id;
    }

    @Override
    public CategoryRow createCategory(NewCategory command) {
        jdbc.update("""
                INSERT INTO spc.chart_category (
                    id, organization_id, code, name, description, analysis_hint, fields_jsonb,
                    sort_order, system_category, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, command.id(), command.organizationId(), command.code(), command.name(), command.description(),
                command.analysisHint(), json(command.fieldsJson()), command.sortOrder(), command.systemCategory(),
                command.createdBy());
        return findCategory(command.organizationId(), command.id()).orElseThrow();
    }

    @Override
    public CategoryRow renameCategory(UUID organizationId, UUID categoryId, String name, String description,
                                      String analysisHint, String fieldsJson) {
        jdbc.update("""
                UPDATE spc.chart_category
                SET name = ?, description = ?, analysis_hint = ?, fields_jsonb = ?, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, name, description, analysisHint, json(fieldsJson), organizationId, categoryId);
        return findCategory(organizationId, categoryId).orElseThrow();
    }

    @Override
    public void deleteCategory(UUID organizationId, UUID categoryId, UUID replacementCategoryId) {
        if (replacementCategoryId != null) {
            if (replacementCategoryId.equals(categoryId)) {
                throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.VALIDATION_ERROR,
                        "迁移目标不能是当前分类");
            }
            if (findCategory(organizationId, replacementCategoryId).isEmpty()) {
                throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.NOT_FOUND,
                        "迁移目标分类不存在");
            }
            jdbc.update("""
                    UPDATE spc.chart_asset
                    SET category_id = ?, updated_at = now()
                    WHERE organization_id = ? AND category_id = ?
                    """, replacementCategoryId, organizationId, categoryId);
        }
        jdbc.update("DELETE FROM spc.chart_category WHERE organization_id = ? AND id = ?",
                organizationId, categoryId);
    }

    @Override
    public Optional<ChartRow> findChart(UUID organizationId, UUID chartId) {
        return jdbc.query(chartQuery("WHERE a.organization_id = ? AND a.id = ? AND a.status = 'READY'"), this::mapChart,
                organizationId, chartId).stream().findFirst();
    }

    @Override
    public Optional<ChartRow> findChartByHash(UUID organizationId, String sha256) {
        return jdbc.query(chartQuery("WHERE a.organization_id = ? AND a.sha256 = ? AND a.status = 'READY'"),
                this::mapChart, organizationId, sha256).stream().findFirst();
    }

    @Override
    public Optional<ChartRow> findChartByRequestId(UUID organizationId, UUID requestId) {
        return jdbc.query(chartQuery("WHERE a.organization_id = ? AND a.request_id = ?"),
                this::mapChart, organizationId, requestId).stream().findFirst();
    }

    @Override
    public void lockChartHash(UUID organizationId, String sha256) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))", rs -> null,
                organizationId + ":spectrum-chart-hash:" + sha256);
    }

    @Override
    public void lockChartRequest(UUID organizationId, UUID requestId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))", rs -> null,
                organizationId + ":spectrum-chart-request:" + requestId);
    }

    @Override
    public List<ChartRow> listCharts(UUID organizationId, String keyword, UUID categoryId, String status,
                                    int page, int size) {
        return listCharts(organizationId, keyword, categoryId, status, page, size, RecordDataScope.all());
    }

    @Override
    public List<ChartRow> listCharts(UUID organizationId, String keyword, UUID categoryId, String status,
                                    int page, int size, RecordDataScope scope) {
        var normalized = blank(keyword);
        var normalizedStatus = blank(status);
        var args = new ArrayList<Object>();
        args.add(organizationId);
        args.add(normalized); args.add(normalized); args.add(normalized); args.add(normalized); args.add(normalized);
        args.add(categoryId); args.add(categoryId); args.add(normalizedStatus); args.add(normalizedStatus);
        var scopeSql = chartScope(scope, args);
        args.add(size); args.add(Math.max(0, page - 1) * size);
        return jdbc.query(chartQuery("""
                WHERE a.organization_id = ?
                  AND (CAST(? AS text) IS NULL OR a.title ILIKE '%' || ? || '%'
                       OR a.original_name ILIKE '%' || ? || '%' OR coalesce(a.sample_name, '') ILIKE '%' || ? || '%'
                       OR coalesce(a.batch_no, '') ILIKE '%' || ? || '%')
                  AND (CAST(? AS uuid) IS NULL OR a.category_id = ?)
                  AND (CAST(? AS text) IS NULL OR a.status = ?)
                """ + scopeSql + "\n" + """
                ORDER BY a.updated_at DESC
                LIMIT ? OFFSET ?
                """), this::mapChart, args.toArray());
    }

    @Override
    public long countCharts(UUID organizationId, String keyword, UUID categoryId, String status) {
        return countCharts(organizationId, keyword, categoryId, status, RecordDataScope.all());
    }

    @Override
    public long countCharts(UUID organizationId, String keyword, UUID categoryId, String status, RecordDataScope scope) {
        var normalized = blank(keyword);
        var normalizedStatus = blank(status);
        var args = new ArrayList<Object>();
        args.add(organizationId); args.add(normalized); args.add(normalized); args.add(normalized);
        args.add(normalized); args.add(normalized); args.add(categoryId); args.add(categoryId);
        args.add(normalizedStatus); args.add(normalizedStatus);
        var scopeSql = chartScope(scope, args);
        return jdbc.queryForObject("""
                SELECT count(*) FROM spc.chart_asset a
                WHERE a.organization_id = ?
                  AND (CAST(? AS text) IS NULL OR a.title ILIKE '%' || ? || '%'
                       OR a.original_name ILIKE '%' || ? || '%' OR coalesce(a.sample_name, '') ILIKE '%' || ? || '%'
                       OR coalesce(a.batch_no, '') ILIKE '%' || ? || '%')
                  AND (CAST(? AS uuid) IS NULL OR a.category_id = ?)
                  AND (CAST(? AS text) IS NULL OR a.status = ?)
                """ + scopeSql, Long.class, args.toArray());
    }

    private String chartScope(RecordDataScope scope, List<Object> args) {
        return switch (scope.type()) {
            case "ALL", "ORG" -> "";
            case "SELF", "CREATOR" -> { args.add(scope.actorId()); yield " AND a.created_by = ?"; }
            case "DEPARTMENT" -> {
                args.add(scope.actorId());
                yield " AND a.department_id = (SELECT department_id FROM iam.app_user WHERE id = ? AND organization_id = a.organization_id)";
            }
            case "SELECTED" -> {
                if (scope.targetIds().isEmpty()) yield " AND false";
                args.addAll(scope.targetIds());
                args.add(scope.actorId());
                yield " AND (a.id IN (" + String.join(",", scope.targetIds().stream().map(id -> "?").toList()) + ") OR a.created_by = ?)";
            }
            default -> " AND false";
        };
    }

    @Override
    public ChartRow insertChart(NewChart command) {
        jdbc.update("""
                INSERT INTO spc.chart_asset (
                    id, organization_id, category_id, file_object_id, title, original_name, content_type,
                    size_bytes, sha256, sample_name, batch_no, test_conditions, metadata_jsonb, page_count,
                    request_id, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, command.id(), command.organizationId(), command.categoryId(), command.fileObjectId(),
                command.title(), command.originalName(), command.contentType(), command.size(), command.sha256(),
                command.sampleName(), command.batchNo(), command.testConditions(), json(command.metadataJson()),
                command.pageCount(), command.requestId(), command.createdBy());
        return findChart(command.organizationId(), command.id()).orElseThrow();
    }

    @Override
    public void updateChart(UUID organizationId, UUID chartId, String title, String sampleName, String batchNo,
                            String testConditions, String metadataJson) {
        jdbc.update("""
                UPDATE spc.chart_asset
                SET title = ?, sample_name = ?, batch_no = ?, test_conditions = ?, metadata_jsonb = ?, updated_at = now()
                WHERE organization_id = ? AND id = ? AND status = 'READY'
                """, title, sampleName, batchNo, testConditions, json(metadataJson), organizationId, chartId);
        jdbc.update("""
                UPDATE core.project_reference
                   SET title = ?
                 WHERE organization_id = ? AND resource_type = 'SPECTRUM_CHART' AND resource_id = ?
                   AND status = 'ACTIVE'
                """, title, organizationId, chartId);
    }

    @Override
    public int assignChartCategory(UUID organizationId, UUID chartId, UUID categoryId) {
        return jdbc.update("""
                UPDATE spc.chart_asset a
                SET category_id = ?, updated_at = now()
                FROM spc.chart_category c
                WHERE a.organization_id = ? AND a.id = ? AND a.status = 'READY'
                  AND c.organization_id = a.organization_id AND c.id = ?
                """, categoryId, organizationId, chartId, categoryId);
    }

    @Override
    public boolean hasActiveProjectReference(UUID organizationId, UUID chartId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM core.project_reference
                     WHERE organization_id = ? AND resource_type = 'SPECTRUM_CHART'
                       AND resource_id = ? AND status = 'ACTIVE'
                )
                """, Boolean.class, organizationId, chartId));
    }

    @Override
    public void deleteChart(UUID organizationId, UUID chartId) {
        jdbc.update("""
                DELETE FROM core.project_resource_link
                 WHERE organization_id = ? AND resource_type = 'SPECTRUM_CHART' AND resource_id = ?
                """, organizationId, chartId);
        jdbc.update("""
                UPDATE spc.chart_asset SET status = 'DELETED', updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, organizationId, chartId);
    }

    @Override
    public void recordUploadAttempt(NewUploadAttempt attempt) {
        jdbc.update("""
                INSERT INTO spc.chart_upload_attempt (
                    id, organization_id, request_id, category_id, chart_id, file_object_id, original_name,
                    size_bytes, status, error_code, error_message, upload_context_jsonb, parent_request_id, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (organization_id, request_id) DO UPDATE
                   SET category_id = EXCLUDED.category_id,
                       chart_id = coalesce(EXCLUDED.chart_id, spc.chart_upload_attempt.chart_id),
                       file_object_id = coalesce(EXCLUDED.file_object_id, spc.chart_upload_attempt.file_object_id),
                       original_name = EXCLUDED.original_name,
                       size_bytes = EXCLUDED.size_bytes,
                       status = EXCLUDED.status,
                       error_code = EXCLUDED.error_code,
                       error_message = EXCLUDED.error_message,
                       upload_context_jsonb = EXCLUDED.upload_context_jsonb,
                       parent_request_id = coalesce(EXCLUDED.parent_request_id, spc.chart_upload_attempt.parent_request_id),
                       hidden_at = NULL,
                       created_at = now()
                 WHERE spc.chart_upload_attempt.status <> 'SUCCEEDED'
                    OR EXCLUDED.status = 'SUCCEEDED'
                """, UUID.randomUUID(), attempt.organizationId(), attempt.requestId(), attempt.categoryId(),
                attempt.chartId(), attempt.fileObjectId(), attempt.originalName(), Math.max(0, attempt.size()),
                attempt.status(), attempt.errorCode(), attempt.errorMessage(),
                attempt.uploadContextJson() == null ? "{}" : attempt.uploadContextJson(),
                attempt.parentRequestId(), attempt.createdBy());
    }

    @Override
    public List<UploadAttemptRow> listUploadAttempts(UUID organizationId, int limit) {
        return jdbc.query("""
                SELECT request_id, category_id, chart_id, original_name, file_object_id, size_bytes, status,
                       error_code, error_message, upload_context_jsonb::text, parent_request_id, created_at
                  FROM spc.chart_upload_attempt
                 WHERE organization_id = ?
                   AND hidden_at IS NULL
                 ORDER BY created_at DESC
                 LIMIT ?
                """, (rs, ignored) -> new UploadAttemptRow(rs.getObject("request_id", UUID.class),
                rs.getObject("category_id", UUID.class), rs.getObject("chart_id", UUID.class),
                rs.getString("original_name"), rs.getObject("file_object_id", UUID.class),
                rs.getLong("size_bytes"), rs.getString("status"), rs.getString("error_code"),
                rs.getString("error_message"), rs.getString("upload_context_jsonb"),
                rs.getObject("parent_request_id", UUID.class), instant(rs, "created_at")),
                organizationId, Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public int hideUploadAttempt(UUID organizationId, UUID requestId) {
        return jdbc.update("""
                UPDATE spc.chart_upload_attempt
                   SET hidden_at = now()
                 WHERE organization_id = ? AND request_id = ? AND hidden_at IS NULL
                """, organizationId, requestId);
    }

    private String chartQuery(String where) {
        return """
                SELECT a.id, a.organization_id, a.category_id, c.code AS category_code, c.name AS category_name,
                       a.file_object_id, a.title, a.original_name, a.content_type, a.size_bytes, a.sha256,
                       a.sample_name, a.batch_no, a.test_conditions, a.metadata_jsonb::text AS metadata_json, a.page_count,
                       a.status, a.created_at, a.updated_at
                FROM spc.chart_asset a
                JOIN spc.chart_category c ON c.id = a.category_id AND c.organization_id = a.organization_id
                """ + where;
    }

    private CategoryRow mapCategory(ResultSet rs, int row) throws java.sql.SQLException {
        return new CategoryRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getString("analysis_hint"), rs.getString("fields_jsonb"), rs.getInt("sort_order"),
                rs.getBoolean("system_category"), rs.getLong("chart_count"));
    }

    private ChartRow mapChart(ResultSet rs, int row) throws java.sql.SQLException {
        return new ChartRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("category_id", UUID.class), rs.getString("category_code"), rs.getString("category_name"),
                rs.getObject("file_object_id", UUID.class), rs.getString("title"), rs.getString("original_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("sample_name"), rs.getString("batch_no"), rs.getString("test_conditions"),
                rs.getString("metadata_json"), rs.getInt("page_count"), rs.getString("status"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private PGobject json(String value) {
        try {
            var object = new PGobject();
            object.setType("jsonb");
            object.setValue(value == null || value.isBlank() ? "{}" : value);
            return object;
        } catch (Exception exception) {
            throw new IllegalArgumentException("图谱 JSON 数据无效", exception);
        }
    }
}
