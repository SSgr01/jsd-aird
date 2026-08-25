package com.jsd.aird.rnd.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.rnd.application.port.ProjectDocumentRepository;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.AuditRecord;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.ReviewRecord;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Create;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Detail;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Search;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Summary;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.VersionDiff;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.VersionRecord;
import com.jsd.aird.rnd.domain.ProjectDocumentFormat;
import com.jsd.aird.rnd.domain.ProjectDocumentSource;
import com.jsd.aird.rnd.domain.ProjectDocumentStatus;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class JdbcProjectDocumentRepository implements ProjectDocumentRepository {

    private static final RowMapper<Summary> SUMMARY_ROW_MAPPER = (rs, rn) -> new Summary(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            ProjectDocumentFormat.valueOf(rs.getString("format")),
            ProjectDocumentSource.valueOf(rs.getString("source")),
            ProjectDocumentStatus.valueOf(rs.getString("status")),
            uuid(rs, "template_id"),
            uuid(rs, "template_version_id"),
            rs.getString("template_name"),
            uuid(rs, "file_object_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("created_by")
    );

    private static final RowMapper<Detail> DETAIL_ROW_MAPPER = (rs, rn) -> new Detail(
            rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getString("title"),
            ProjectDocumentFormat.valueOf(rs.getString("format")),
            ProjectDocumentSource.valueOf(rs.getString("source")),
            ProjectDocumentStatus.valueOf(rs.getString("status")),
            rs.getLong("version"),
            uuid(rs, "template_id"),
            uuid(rs, "template_version_id"),
            rs.getString("template_name"),
            uuid(rs, "file_object_id"),
            uuid(rs, "current_version_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("created_by"),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getString("updated_by"),
            json(rs, "content_snapshot"), json(rs, "content_schema"),
            json(rs, "content_mapping"), json(rs, "content_data"),
            json(rs, "content_recognition")
    );

    private final JdbcTemplate jdbc;

    public JdbcProjectDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Summary> search(Search q) {
        var sql = """
                SELECT d.id, d.title, d.format, d.source, d.status,
                       d.template_id, d.template_version_id, t.name AS template_name,
                       d.file_object_id, d.created_at, d.created_by
                FROM mdm.project_document d
                LEFT JOIN tpl.template t ON t.id = d.template_id
                WHERE d.deleted = false
                  AND d.project_id = ?
                """;
        if (q.status() != null) {
            return jdbc.query(sql + " AND d.status = ? ORDER BY d.updated_at DESC",
                    SUMMARY_ROW_MAPPER, q.projectId(), q.status().name());
        }
        return jdbc.query(sql + " ORDER BY d.updated_at DESC",
                SUMMARY_ROW_MAPPER, q.projectId());
    }

    @Override
    public Optional<Detail> findById(UUID id) {
        var sql = """
                SELECT d.id, d.project_id, d.title, d.format, d.source, d.status, d.version,
                       d.template_id, d.template_version_id, t.name AS template_name,
                       d.file_object_id, d.current_version_id, d.created_at, d.created_by,
                       d.updated_at, d.updated_by,
                       d.content_snapshot, d.content_schema, d.content_mapping, d.content_data,
                       d.content_recognition
                FROM mdm.project_document d
                LEFT JOIN tpl.template t ON t.id = d.template_id
                WHERE d.deleted = false AND d.id = ?
                """;
        return jdbc.query(sql, DETAIL_ROW_MAPPER, id).stream().findFirst();
    }

    @Override
    public UUID create(Create cmd) {
        var id = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mdm.project_document (
                    id, project_id, title, format, source,
                    template_id, template_version_id, file_object_id, status,
                    current_version_id, version, created_at, created_by, updated_at, updated_by, deleted
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, now(), ?, now(), ?, false)
                """,
                id,
                cmd.projectId(),
                cmd.title(),
                cmd.format().name(),
                cmd.source().name(),
                cmd.templateId(),
                cmd.templateVersionId(),
                cmd.fileObjectId(),
                cmd.status().name(),
                versionId,
                cmd.createdBy(),
                cmd.createdBy());
        // 初始版本行（对齐实验记事本 create 时建立 experiment_version）
        jdbc.update("""
                INSERT INTO mdm.project_document_version (
                    id, document_id, version_no, status, content_jsonb, created_by, created_at
                ) VALUES (?, ?, 1, ?, ?::jsonb, ?, now())
                """, versionId, id, cmd.status().name(), "{}".toString(), cmd.createdBy());
        return id;
    }

    @Override
    public void delete(UUID id, String updatedBy) {
        var sql = """
                UPDATE mdm.project_document
                SET deleted = true, version = version + 1, updated_at = now(), updated_by = ?
                WHERE id = ? AND deleted = false
                """;
        jdbc.update(sql, updatedBy, id);
    }

    @Override
    public void saveContent(UUID id, JsonNode snapshot, JsonNode schema, JsonNode mapping, JsonNode data,
                            JsonNode recognition, String updatedBy) {
        // 仅更新文档内容，不新建版本（对齐实验记事本 saveDraft：保存草稿不记版本）
        jdbc.update("""
                UPDATE mdm.project_document
                SET content_snapshot = ?::jsonb, content_schema = ?::jsonb,
                    content_mapping = ?::jsonb, content_data = ?::jsonb,
                    content_recognition = ?::jsonb,
                    version = version + 1, updated_at = now(), updated_by = ?
                WHERE id = ? AND deleted = false
                """, snapshot.toString(), schema.toString(), mapping.toString(), data.toString(),
                recognition.toString(), updatedBy, id);
        // 草稿阶段同步初始版本内容；已发布文档再次编辑时不能改写历史发布快照。
        var merged = new ObjectMapper().createObjectNode()
                .put("contentSnapshot", snapshot == null ? null : snapshot.toString())
                .put("contentSchema", schema == null ? null : schema.toString())
                .put("contentMapping", mapping == null ? null : mapping.toString())
                .put("contentData", data == null ? null : data.toString())
                .put("contentRecognition", recognition == null ? null : recognition.toString());
        jdbc.update("""
                UPDATE mdm.project_document_version
                SET content_jsonb = ?::jsonb
                WHERE id = (SELECT current_version_id FROM mdm.project_document WHERE id = ?)
                  AND status = 'DRAFT'
                """, merged.toString(), id);
    }

    private static final RowMapper<VersionRecord> VERSION_ROW_MAPPER = (rs, rn) -> new VersionRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("document_id", UUID.class),
            rs.getLong("version_no"),
            rs.getString("status"),
            json(rs, "content_jsonb"),
            rs.getString("snapshot_reason"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant()
    );

    private static final RowMapper<AuditRecord> AUDIT_ROW_MAPPER = (rs, rn) -> new AuditRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("document_id", UUID.class),
            rs.getString("action"),
            json(rs, "before_jsonb"),
            json(rs, "after_jsonb"),
            rs.getString("operator_id"),
            rs.getString("operator_name"),
            rs.getTimestamp("created_at").toInstant()
    );

    @Override
    public List<VersionRecord> versions(UUID documentId) {
        return jdbc.query("""
                SELECT id, document_id, version_no, status, content_jsonb,
                       snapshot_reason, created_by, created_at
                FROM mdm.project_document_version
                WHERE document_id = ?
                ORDER BY version_no DESC
                """, VERSION_ROW_MAPPER, documentId);
    }

    @Override
    public VersionDiff diff(UUID documentId, long from, long to) {
        var sql = """
                SELECT content_jsonb
                FROM mdm.project_document_version
                WHERE document_id = ? AND version_no = ?
                """;
        var before = jdbc.query(sql, (rs, rn) -> json(rs, "content_jsonb"), documentId, from)
                .stream().findFirst().orElse(null);
        var after = jdbc.query(sql, (rs, rn) -> json(rs, "content_jsonb"), documentId, to)
                .stream().findFirst().orElse(null);
        return new VersionDiff(before, after);
    }

    @Override
    public List<AuditRecord> audits(UUID documentId) {
        return jdbc.query("""
                SELECT id, document_id, action, before_jsonb, after_jsonb,
                       operator_id, operator_name, created_at
                FROM mdm.project_document_audit
                WHERE document_id = ?
                ORDER BY created_at DESC
                """, AUDIT_ROW_MAPPER, documentId);
    }

    @Override
    public UUID appendVersion(UUID documentId, long versionNo, String status, JsonNode contentJsonb,
                              String snapshotReason, UUID templateVersionId, String createdBy) {
        var versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mdm.project_document_version (
                    id, document_id, version_no, status, content_jsonb, snapshot_reason,
                    template_version_id, created_by, created_at
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, now())
                ON CONFLICT (document_id, version_no) DO UPDATE
                SET status = EXCLUDED.status, content_jsonb = EXCLUDED.content_jsonb,
                    snapshot_reason = EXCLUDED.snapshot_reason,
                    template_version_id = EXCLUDED.template_version_id,
                    created_by = EXCLUDED.created_by
                """, versionId, documentId, versionNo, status,
                contentJsonb == null ? null : contentJsonb.toString(),
                snapshotReason, templateVersionId, createdBy);
        return versionId;
    }

    @Override
    public void appendAudit(UUID documentId, UUID documentVersionId, String action, JsonNode before, JsonNode after,
                            UUID operatorId, String operatorName) {
        jdbc.update("""
                INSERT INTO mdm.project_document_audit (
                    id, document_id, document_version_id, action, before_jsonb, after_jsonb,
                    operator_id, operator_name, created_at
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, now())
                """, UUID.randomUUID(), documentId, documentVersionId, action,
                before == null ? null : before.toString(),
                after == null ? null : after.toString(),
                operatorId, operatorName);
    }

    private static final RowMapper<ReviewRecord> REVIEW_ROW_MAPPER = (rs, rn) -> new ReviewRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("document_id", UUID.class),
            uuid(rs, "document_version_id"),
            rs.getString("action"),
            rs.getString("comment"),
            rs.getString("operator_id"),
            rs.getString("operator_name"),
            rs.getTimestamp("created_at").toInstant()
    );

    @Override
    public List<ReviewRecord> reviews(UUID documentId) {
        return jdbc.query("""
                SELECT id, document_id, document_version_id, action, comment,
                       operator_id, operator_name, created_at
                FROM mdm.project_document_review
                WHERE document_id = ?
                ORDER BY created_at DESC
                """, REVIEW_ROW_MAPPER, documentId);
    }

    @Override
    public void appendReview(UUID documentId, UUID documentVersionId, String action, String comment,
                             UUID operatorId, String operatorName) {
        jdbc.update("""
                INSERT INTO mdm.project_document_review (
                    id, document_id, document_version_id, action, comment, operator_id, operator_name, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), documentId, documentVersionId, action, comment, operatorId, operatorName);
    }

    @Override
    public UUID publish(UUID documentId, JsonNode contentJsonb, String snapshotReason, UUID templateVersionId,
                        String createdBy) {
        // 发布：新建一条已发布版本行（versionNo 自动递增），回写 current_version_id 与文档状态。
        // 对齐实验记事本 ExperimentRepository.transition 到 COMPLETED 的逻辑。
        var versionId = UUID.randomUUID();
        var nextNo = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) + 1 FROM mdm.project_document_version WHERE document_id = ?",
                Long.class, documentId);
        jdbc.update("""
                INSERT INTO mdm.project_document_version (
                    id, document_id, version_no, status, content_jsonb, snapshot_reason,
                    template_version_id, created_by, created_at, published_at
                ) VALUES (?, ?, ?, 'PUBLISHED', ?::jsonb, ?, ?, ?, now(), now())
                """, versionId, documentId, nextNo,
                contentJsonb == null ? null : contentJsonb.toString(),
                snapshotReason, templateVersionId, createdBy);
        jdbc.update("""
                UPDATE mdm.project_document
                SET status = 'PUBLISHED', current_version_id = ?, version = version + 1,
                    updated_at = now(), updated_by = ?
                WHERE id = ?
                """, versionId, createdBy, documentId);
        return versionId;
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        var o = rs.getObject(column);
        return o == null ? null : (UUID) o;
    }


    private static JsonNode json(ResultSet rs, String column) throws SQLException {
        var value = rs.getString(column);
        if (value == null) return null;
        try { return new ObjectMapper().readTree(value); }
        catch (Exception e) { throw new SQLException("Invalid JSON in " + column, e); }
    }
}
