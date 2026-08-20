package com.jsd.aird.spc.infrastructure;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.spc.application.port.SpectrumRepository;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSpectrumRepository implements SpectrumRepository {

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
    public void deleteCategory(UUID organizationId, UUID categoryId) {
        jdbc.update("DELETE FROM spc.chart_category WHERE organization_id = ? AND id = ?",
                organizationId, categoryId);
    }

    @Override
    public Optional<ChartRow> findChart(UUID organizationId, UUID chartId) {
        return jdbc.query(chartQuery("WHERE a.organization_id = ? AND a.id = ?"), this::mapChart,
                organizationId, chartId).stream().findFirst();
    }

    @Override
    public Optional<ChartRow> findChartByHash(UUID organizationId, String sha256) {
        return jdbc.query(chartQuery("WHERE a.organization_id = ? AND a.sha256 = ? AND a.status = 'READY'"),
                this::mapChart, organizationId, sha256).stream().findFirst();
    }

    @Override
    public List<ChartRow> listCharts(UUID organizationId, String keyword, UUID categoryId, String status,
                                    int page, int size) {
        var normalized = blank(keyword);
        var normalizedStatus = blank(status);
        return jdbc.query(chartQuery("""
                WHERE a.organization_id = ?
                  AND (CAST(? AS text) IS NULL OR a.title ILIKE '%' || ? || '%'
                       OR a.original_name ILIKE '%' || ? || '%' OR coalesce(a.sample_name, '') ILIKE '%' || ? || '%'
                       OR coalesce(a.batch_no, '') ILIKE '%' || ? || '%')
                  AND (CAST(? AS uuid) IS NULL OR a.category_id = ?)
                  AND (CAST(? AS text) IS NULL OR a.status = ?)
                ORDER BY a.updated_at DESC
                LIMIT ? OFFSET ?
                """), this::mapChart, organizationId, normalized, normalized, normalized, normalized, normalized,
                categoryId, categoryId, normalizedStatus, normalizedStatus, size,
                Math.max(0, page - 1) * size);
    }

    @Override
    public long countCharts(UUID organizationId, String keyword, UUID categoryId, String status) {
        var normalized = blank(keyword);
        var normalizedStatus = blank(status);
        return jdbc.queryForObject("""
                SELECT count(*) FROM spc.chart_asset a
                WHERE a.organization_id = ?
                  AND (CAST(? AS text) IS NULL OR a.title ILIKE '%' || ? || '%'
                       OR a.original_name ILIKE '%' || ? || '%' OR coalesce(a.sample_name, '') ILIKE '%' || ? || '%'
                       OR coalesce(a.batch_no, '') ILIKE '%' || ? || '%')
                  AND (CAST(? AS uuid) IS NULL OR a.category_id = ?)
                  AND (CAST(? AS text) IS NULL OR a.status = ?)
                """, Long.class, organizationId, normalized, normalized, normalized, normalized, normalized,
                categoryId, categoryId, normalizedStatus, normalizedStatus);
    }

    @Override
    public ChartRow insertChart(NewChart command) {
        jdbc.update("""
                INSERT INTO spc.chart_asset (
                    id, organization_id, category_id, file_object_id, title, original_name, content_type,
                    size_bytes, sha256, sample_name, batch_no, test_conditions, metadata_jsonb, page_count,
                    created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, command.id(), command.organizationId(), command.categoryId(), command.fileObjectId(),
                command.title(), command.originalName(), command.contentType(), command.size(), command.sha256(),
                command.sampleName(), command.batchNo(), command.testConditions(), json(command.metadataJson()),
                command.pageCount(), command.createdBy());
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
    }

    @Override
    public void deleteChart(UUID organizationId, UUID chartId) {
        jdbc.update("""
                UPDATE spc.chart_asset SET status = 'DELETED', updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, organizationId, chartId);
    }

    @Override
    @Transactional
    public UUID createSession(UUID organizationId, UUID userId, String title) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO spc.chat_session (id, organization_id, title, created_by) VALUES (?, ?, ?, ?)",
                id, organizationId, title, userId);
        return id;
    }

    @Override
    public List<SessionRow> listSessions(UUID organizationId, UUID userId, int limit) {
        return jdbc.query("""
                SELECT id, title, created_at, updated_at FROM spc.chat_session
                WHERE organization_id = ? AND created_by = ?
                ORDER BY updated_at DESC LIMIT ?
                """, (rs, n) -> new SessionRow(rs.getObject("id", UUID.class), rs.getString("title"),
                instant(rs, "created_at"), instant(rs, "updated_at")), organizationId, userId, limit);
    }

    @Override
    public boolean sessionExists(UUID organizationId, UUID userId, UUID sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM spc.chat_session WHERE organization_id = ? AND created_by = ? AND id = ?)",
                Boolean.class, organizationId, userId, sessionId));
    }

    @Override
    public void touchSession(UUID organizationId, UUID sessionId, String title) {
        // Titles are owned by the worker or the explicit rename endpoint. A
        // message should only move the conversation to the top of the list.
        jdbc.update("UPDATE spc.chat_session SET updated_at = now() WHERE organization_id = ? AND id = ?",
                organizationId, sessionId);
    }

    @Override
    public void renameSession(UUID organizationId, UUID userId, UUID sessionId, String title) {
        jdbc.update("""
                UPDATE spc.chat_session SET title = ?, title_source = 'USER', updated_at = now()
                WHERE organization_id = ? AND created_by = ? AND id = ?
                """, title, organizationId, userId, sessionId);
    }

    @Override
    public void deleteSession(UUID organizationId, UUID userId, UUID sessionId) {
        jdbc.update("DELETE FROM spc.chat_session WHERE organization_id = ? AND created_by = ? AND id = ?",
                organizationId, userId, sessionId);
    }

    @Override
    public List<MessageRow> listMessages(UUID organizationId, UUID sessionId, int limit) {
        return jdbc.query("""
                SELECT id, analysis_run_id, role, content, citations_jsonb::text, result_jsonb::text,
                       warning_jsonb::text, created_at
                FROM spc.chat_message
                WHERE organization_id = ? AND session_id = ?
                ORDER BY created_at ASC LIMIT ?
                """, (rs, n) -> new MessageRow(rs.getObject("id", UUID.class), rs.getObject("analysis_run_id", UUID.class),
                rs.getString("role"), rs.getString("content"), rs.getString("citations_jsonb"),
                rs.getString("result_jsonb"), rs.getString("warning_jsonb"), instant(rs, "created_at")),
                organizationId, sessionId, limit);
    }

    @Override
    public MessageRow insertMessage(NewMessage command) {
        jdbc.update("""
                INSERT INTO spc.chat_message (
                    id, organization_id, session_id, analysis_run_id, role, content,
                    citations_jsonb, result_jsonb, warning_jsonb
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, command.id(), command.organizationId(), command.sessionId(), command.analysisRunId(),
                command.role(), command.content(), json(command.citationsJson()), json(command.resultJson()),
                json(command.warningJson()));
        return jdbc.query("""
                SELECT id, analysis_run_id, role, content, citations_jsonb::text, result_jsonb::text,
                       warning_jsonb::text, created_at
                FROM spc.chat_message WHERE organization_id = ? AND id = ?
                """, (rs, n) -> new MessageRow(rs.getObject("id", UUID.class), rs.getObject("analysis_run_id", UUID.class),
                rs.getString("role"), rs.getString("content"), rs.getString("citations_jsonb"),
                rs.getString("result_jsonb"), rs.getString("warning_jsonb"), instant(rs, "created_at")),
                command.organizationId(), command.id()).stream().findFirst().orElseThrow();
    }

    @Override
    public AnalysisRow insertAnalysis(NewAnalysis command) {
        jdbc.update("""
                INSERT INTO spc.analysis_run (
                    id, organization_id, session_id, mode, question, chart_ids_jsonb, page_selections_jsonb,
                    selected_categories_jsonb, scenario_template, prompt_version, model, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, command.id(), command.organizationId(), command.sessionId(), command.mode(), command.question(),
                json(command.chartIdsJson()), json(command.pageSelectionsJson()), json(command.categoriesJson()),
                command.scenarioTemplate(), command.promptVersion(), command.model(), command.createdBy());
        return findAnalysis(command.organizationId(), command.id()).orElseThrow();
    }

    @Override
    public Optional<AnalysisRow> findAnalysis(UUID organizationId, UUID analysisId) {
        return jdbc.query("""
                SELECT id, session_id, mode, question, chart_ids_jsonb::text AS chart_ids_json,
                       page_selections_jsonb::text AS page_selections_json,
                       selected_categories_jsonb::text AS categories_json, scenario_template, status, progress, current_stage,
                       prompt_version, model, result_jsonb::text AS result_json,
                       raw_response_jsonb::text AS raw_response_json, warning_jsonb::text AS warning_json,
                       error_message, created_at, started_at, completed_at
                FROM spc.analysis_run WHERE organization_id = ? AND id = ?
                """, this::mapAnalysis, organizationId, analysisId).stream().findFirst();
    }

    @Override
    public Optional<AnalysisRow> findAnalysisForUser(UUID organizationId, UUID userId, UUID analysisId) {
        return jdbc.query("""
                SELECT a.id, a.session_id, a.mode, a.question, a.chart_ids_jsonb::text AS chart_ids_json,
                       a.page_selections_jsonb::text AS page_selections_json,
                       a.selected_categories_jsonb::text AS categories_json, a.scenario_template, a.status, a.progress, a.current_stage,
                       a.prompt_version, a.model, a.result_jsonb::text AS result_json,
                       a.raw_response_jsonb::text AS raw_response_json, a.warning_jsonb::text AS warning_json,
                       a.error_message, a.created_at, a.started_at, a.completed_at
                FROM spc.analysis_run a
                JOIN spc.chat_session s ON s.id = a.session_id AND s.organization_id = a.organization_id
                WHERE a.organization_id = ? AND s.created_by = ? AND a.id = ?
                """, this::mapAnalysis, organizationId, userId, analysisId).stream().findFirst();
    }

    @Override
    public void updateAnalysisStarted(UUID organizationId, UUID analysisId, String stage) {
        jdbc.update("""
                UPDATE spc.analysis_run SET status = 'RUNNING', progress = 10, current_stage = ?, started_at = now()
                WHERE organization_id = ? AND id = ?
                """, stage, organizationId, analysisId);
    }

    @Override
    public void updateAnalysisProgress(UUID organizationId, UUID analysisId, int progress, String stage) {
        jdbc.update("""
                UPDATE spc.analysis_run SET progress = ?, current_stage = ?
                WHERE organization_id = ? AND id = ?
                """, Math.max(0, Math.min(100, progress)), stage, organizationId, analysisId);
    }

    @Override
    public void updateAnalysisFinished(UUID organizationId, UUID analysisId, String status, String resultJson,
                                       String rawResponseJson, String warningJson, String errorMessage) {
        jdbc.update("""
                UPDATE spc.analysis_run SET status = ?, progress = ?, current_stage = ?, result_jsonb = ?,
                    raw_response_jsonb = ?, warning_jsonb = ?, error_message = ?, completed_at = now()
                WHERE organization_id = ? AND id = ?
                """, status, "SUCCEEDED".equals(status) || "PARTIAL".equals(status) ? 100 : 0,
                "SUCCEEDED".equals(status) || "PARTIAL".equals(status) ? "COMPLETED" : "FAILED",
                json(resultJson), json(rawResponseJson), json(warningJson), errorMessage, organizationId, analysisId);
    }

    @Override
    public long appendAnalysisEvent(UUID organizationId, UUID analysisId, String eventType, String payloadJson) {
        return jdbc.queryForObject("""
                INSERT INTO spc.analysis_event (organization_id, analysis_run_id, event_type, payload_jsonb)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, organizationId, analysisId, eventType, json(payloadJson));
    }

    @Override
    public List<AnalysisEventRow> listAnalysisEvents(UUID organizationId, UUID analysisId, long afterId, int limit) {
        return jdbc.query("""
                SELECT id, event_type, payload_jsonb::text, created_at
                FROM spc.analysis_event
                WHERE organization_id = ? AND analysis_run_id = ? AND id > ?
                ORDER BY id
                LIMIT ?
                """, (rs, row) -> new AnalysisEventRow(rs.getLong("id"), rs.getString("event_type"),
                rs.getString("payload_jsonb"), instant(rs, "created_at")), organizationId, analysisId, afterId,
                Math.max(1, Math.min(limit, 200)));
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

    private AnalysisRow mapAnalysis(ResultSet rs, int row) throws java.sql.SQLException {
        return new AnalysisRow(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getString("mode"), rs.getString("question"), rs.getString("chart_ids_json"),
                rs.getString("page_selections_json"), rs.getString("categories_json"),
                rs.getString("scenario_template"), rs.getString("status"), rs.getInt("progress"),
                rs.getString("current_stage"), rs.getString("prompt_version"), rs.getString("model"),
                rs.getString("result_json"), rs.getString("raw_response_json"), rs.getString("warning_json"),
                rs.getString("error_message"), instant(rs, "created_at"), instant(rs, "started_at"),
                instant(rs, "completed_at"));
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
