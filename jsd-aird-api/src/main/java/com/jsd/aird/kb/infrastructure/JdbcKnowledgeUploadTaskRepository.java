package com.jsd.aird.kb.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.port.KnowledgeUploadTaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKnowledgeUploadTaskRepository implements KnowledgeUploadTaskRepository {

    private static final String SELECT_TASKS = """
            SELECT t.id, t.file_name, t.size_bytes,
                   CASE
                       WHEN v.status = 'READY' THEN 'COMPLETED'
                       WHEN v.status IN ('FAILED', 'REJECTED', 'PENDING_PROVIDER') THEN 'FAILED'
                       WHEN v.status IN ('QUEUED', 'PROCESSING') THEN 'QUEUED'
                       ELSE t.status
                   END AS display_status,
                   CASE WHEN v.status = 'READY' THEN 100 ELSE t.progress END AS display_progress,
                   t.document_id, t.document_version_id,
                   CASE
                       WHEN v.status = 'READY' THEN '解析完成，等待人工校对'
                       WHEN v.status IN ('FAILED', 'REJECTED', 'PENDING_PROVIDER')
                           THEN coalesce(v.error_message, d.parse_error, t.message)
                       ELSE coalesce(d.parse_error, t.message)
                   END AS display_message,
                   coalesce(t.source_file_id, v.file_object_id) AS source_file_id,
                   t.upload_context_jsonb::text AS upload_context_json,
                   t.created_at, t.updated_at
              FROM kb.upload_task t
              LEFT JOIN kb.document_version v ON v.id = t.document_version_id
              LEFT JOIN kb.document d ON d.id = t.document_id AND d.organization_id = t.organization_id
             WHERE t.organization_id = ? AND t.actor_id = ? AND t.hidden_at IS NULL
            """;

    private static final String LIST_TASKS = SELECT_TASKS + """
             ORDER BY t.created_at DESC, t.id DESC
             LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcKnowledgeUploadTaskRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public UploadTaskRow create(UUID id, UUID organizationId, UUID actorId, String fileName, long size, JsonNode uploadContext) {
        jdbc.update("""
                INSERT INTO kb.upload_task (
                    id, organization_id, actor_id, file_name, size_bytes, status, progress, upload_context_jsonb
                ) VALUES (?, ?, ?, ?, ?, 'STAGING', 8, ?::jsonb)
                """, id, organizationId, actorId, fileName, size,
                uploadContext == null ? "{}" : uploadContext.toString());
        return find(organizationId, actorId, id).orElseThrow();
    }

    @Override
    public List<UploadTaskRow> list(UUID organizationId, UUID actorId, int limit) {
        jdbc.update("""
                UPDATE kb.upload_task
                   SET status = 'FAILED', progress = 100,
                       message = '上传流程中断，请重新上传', updated_at = now()
                 WHERE organization_id = ? AND actor_id = ?
                   AND hidden_at IS NULL
                   AND status IN ('STAGING', 'CHECKING', 'SUBMITTING')
                   AND updated_at < now() - interval '30 minutes'
                """, organizationId, actorId);
        return jdbc.query(LIST_TASKS, this::map, organizationId, actorId, limit);
    }

    @Override
    public Optional<UploadTaskRow> update(UUID organizationId, UUID actorId, UUID id, String status, int progress,
                                          UUID sourceFileId, JsonNode uploadContext, UUID documentId,
                                          UUID documentVersionId, String message) {
        var updated = jdbc.update("""
                UPDATE kb.upload_task
                   SET status = ?, progress = ?,
                       source_file_id = coalesce(?, source_file_id),
                       upload_context_jsonb = coalesce(?::jsonb, upload_context_jsonb),
                       document_id = coalesce(?, document_id),
                       document_version_id = coalesce(?, document_version_id),
                       message = coalesce(?, message), updated_at = now()
                 WHERE id = ? AND organization_id = ? AND actor_id = ? AND hidden_at IS NULL
                """, status, progress, sourceFileId, uploadContext == null ? null : uploadContext.toString(),
                documentId, documentVersionId, message, id, organizationId, actorId);
        return updated == 0 ? Optional.empty() : find(organizationId, actorId, id);
    }

    @Override
    public boolean delete(UUID organizationId, UUID actorId, UUID id) {
        return jdbc.update("""
                UPDATE kb.upload_task t SET hidden_at = now(), updated_at = now()
                 WHERE t.id = ? AND t.organization_id = ? AND t.actor_id = ? AND t.hidden_at IS NULL
                   AND (t.status NOT IN ('STAGING', 'CHECKING', 'SUBMITTING', 'QUEUED')
                        OR (t.status = 'QUEUED' AND t.document_version_id IS NOT NULL))
                   AND NOT EXISTS (
                       SELECT 1 FROM kb.document_version v
                        WHERE v.id = t.document_version_id AND v.status IN ('QUEUED', 'PROCESSING')
                   )
                """, id, organizationId, actorId) > 0;
    }

    private Optional<UploadTaskRow> find(UUID organizationId, UUID actorId, UUID id) {
        var query = SELECT_TASKS + " AND t.id = ? LIMIT 1";
        return jdbc.query(query, this::map, organizationId, actorId, id).stream().findFirst();
    }

    private UploadTaskRow map(ResultSet rs, int rowNum) throws SQLException {
        JsonNode uploadContext;
        try {
            var raw = rs.getString("upload_context_json");
            uploadContext = raw == null ? objectMapper.createObjectNode() : objectMapper.readTree(raw);
        } catch (java.io.IOException exception) {
            throw new SQLException("Invalid knowledge upload context JSON", exception);
        }
        return new UploadTaskRow(rs.getObject("id", UUID.class), rs.getString("file_name"),
                rs.getLong("size_bytes"), rs.getString("display_status"), rs.getInt("display_progress"),
                rs.getObject("source_file_id", UUID.class), uploadContext,
                rs.getObject("document_id", UUID.class), rs.getObject("document_version_id", UUID.class),
                rs.getString("display_message"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
