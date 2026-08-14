package com.jsd.aird.rnd.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.rnd.application.port.ProjectDocumentRepository;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Create;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Detail;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Search;
import com.jsd.aird.rnd.application.port.ProjectDocumentRepository.Summary;
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
            uuid(rs, "template_id"),
            uuid(rs, "template_version_id"),
            rs.getString("template_name"),
            uuid(rs, "file_object_id"),
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
                SELECT d.id, d.project_id, d.title, d.format, d.source, d.status,
                       d.template_id, d.template_version_id, t.name AS template_name,
                       d.file_object_id, d.created_at, d.created_by, d.updated_at, d.updated_by,
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
        var sql = """
                INSERT INTO mdm.project_document (
                    id, project_id, title, format, source,
                    template_id, template_version_id, file_object_id, status,
                    version, created_at, created_by, updated_at, updated_by, deleted
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, now(), ?, now(), ?, false)
                """;
        var id = UUID.randomUUID();
        jdbc.update(sql,
                id,
                cmd.projectId(),
                cmd.title(),
                cmd.format().name(),
                cmd.source().name(),
                cmd.templateId(),
                cmd.templateVersionId(),
                cmd.fileObjectId(),
                cmd.status().name(),
                cmd.createdBy(),
                cmd.createdBy());
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
        jdbc.update("""
                UPDATE mdm.project_document
                SET content_snapshot = ?::jsonb, content_schema = ?::jsonb,
                    content_mapping = ?::jsonb, content_data = ?::jsonb,
                    content_recognition = ?::jsonb,
                    version = version + 1, updated_at = now(), updated_by = ?
                WHERE id = ? AND deleted = false
                """, snapshot.toString(), schema.toString(), mapping.toString(), data.toString(),
                recognition.toString(), updatedBy, id);
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
