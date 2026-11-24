package com.jsd.aird.kb.infrastructure;

import com.jsd.aird.kb.application.port.MineruTaskStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMineruTaskStore implements MineruTaskStore {
    private final JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    public JdbcMineruTaskStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<Task> find(UUID organization, UUID version, String key) {
        return jdbc.query("SELECT * FROM kb.mineru_task_checkpoint WHERE organization_id=? AND document_version_id=? AND task_key=?",
                this::read,
                organization, version, key).stream().findFirst();
    }
    public void save(UUID organization, UUID version, String key, Task task) {
        jdbc.update("""
                INSERT INTO kb.mineru_task_checkpoint
                    (organization_id, document_version_id, task_key, batch_id, upload_url, uploaded, remote_state,
                     provider, endpoint_fingerprint, remote_file_id, upload_headers_jsonb)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (organization_id, document_version_id, task_key) DO UPDATE SET
                    batch_id=excluded.batch_id, upload_url=excluded.upload_url, uploaded=excluded.uploaded,
                    remote_state=excluded.remote_state, provider=excluded.provider,
                    endpoint_fingerprint=excluded.endpoint_fingerprint, remote_file_id=excluded.remote_file_id,
                    upload_headers_jsonb=excluded.upload_headers_jsonb, updated_at=now()
                """, organization, version, key, task.batchId(), task.uploadUrl(), task.uploaded(), task.state(),
                task.provider(), task.endpointFingerprint(), task.remoteFileId(), headers(task));
    }
    public Optional<Task> latest(UUID organization, UUID version) {
        return jdbc.query("""
                SELECT * FROM kb.mineru_task_checkpoint WHERE organization_id=? AND document_version_id=?
                ORDER BY updated_at DESC LIMIT 1
                """, this::read,
                organization, version).stream().findFirst();
    }
    public Optional<Task> previousTimeout(UUID organization, UUID version, String ocrMode) {
        return jdbc.query("""
                SELECT provider_task_id FROM kb.document_parse_run
                WHERE organization_id=? AND document_version_id=? AND provider='MineruDocumentParser'
                    AND error_message='MinerU 精准任务等待超时' AND provider_task_id IS NOT NULL
                    AND result_jsonb->>'requestedOcrMode'=?
                ORDER BY created_at DESC LIMIT 1
                """, (rs,n) -> new Task(rs.getString(1), null, true, "pending"), organization, version, ocrMode)
                .stream().findFirst();
    }
    private String headers(Task task) {
        try { return mapper.writeValueAsString(task.uploadHeaders()); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    private Task read(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        try {
            String json = rs.getString("upload_headers_jsonb");
            var node = mapper.readTree(json == null ? "{}" : json);
            if (node.isTextual()) node = mapper.readTree(node.asText()); // H2 PostgreSQL JSON compatibility
            var headers = new java.util.LinkedHashMap<String, String>();
            node.fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
            return new Task(rs.getString("batch_id"), rs.getString("upload_url"), rs.getBoolean("uploaded"),
                    rs.getString("remote_state"), rs.getString("provider"), rs.getString("endpoint_fingerprint"),
                    rs.getString("remote_file_id"), headers);
        } catch (java.io.IOException error) { throw new java.sql.SQLException("Invalid MinerU upload headers", error); }
    }
}
