package com.jsd.aird.rnd.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.rnd.application.port.ExperimentImportRepository;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.SQLException;
import java.util.UUID;
import java.util.List;

@Repository
public class JdbcExperimentImportRepository implements ExperimentImportRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcExperimentImportRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void create(UUID id, UUID organizationId, UUID fileId, String fileName, String sha256,
                       String format, String categoryName, UUID projectId, UUID stageId, UUID taskId,
                       String visibility, UUID actorId) {
        jdbc.update("""
                INSERT INTO rnd.experiment_import_job
                (id, organization_id, source_file_id, source_file_name, source_sha256, source_format,
                 category_name, project_id, stage_id, task_id, visibility, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PARSING', ?)
                """, id, organizationId, fileId, fileName, sha256, format, categoryName, projectId,
                stageId, taskId, visibility, actorId);
    }

    @Override
    public void complete(UUID id, UUID experimentId, JsonNode parseResult) {
        jdbc.update("UPDATE rnd.experiment_import_job SET status='COMPLETED', experiment_id=?, parse_result_jsonb=?, updated_at=now() WHERE id=?",
                experimentId, json(parseResult), id);
    }

    @Override
    public void fail(UUID id, String errorMessage) {
        jdbc.update("UPDATE rnd.experiment_import_job SET status='FAILED', error_message=?, updated_at=now() WHERE id=?",
                errorMessage == null ? "文件解析失败" : errorMessage.substring(0, Math.min(2000, errorMessage.length())), id);
    }

    @Override
    public int delete(UUID organizationId, UUID id) {
        return jdbc.update("DELETE FROM rnd.experiment_import_job WHERE organization_id=? AND id=? AND experiment_id IS NULL AND status IN ('COMPLETED', 'FAILED')",
                organizationId, id);
    }

    @Override
    public List<Job> list(UUID organizationId) {
        return jdbc.query("""
                SELECT j.id, j.source_file_id, j.source_file_name, j.source_format, j.status,
                       j.experiment_id, j.error_message, j.category_name, j.project_id,
                       p.name project_name, j.stage_id, s.name stage_name,
                       j.task_id, t.name task_name, j.visibility, j.created_at
                FROM rnd.experiment_import_job j
                LEFT JOIN mdm.project p ON p.id=j.project_id
                LEFT JOIN mdm.project_stage s ON s.id=j.stage_id
                LEFT JOIN mdm.project_task t ON t.id=j.task_id
                WHERE j.organization_id=?
                ORDER BY j.created_at DESC
                """, (result, row) -> new Job(
                result.getObject("id", UUID.class),
                result.getObject("source_file_id", UUID.class),
                result.getString("source_file_name"),
                result.getString("source_format"),
                result.getString("status"),
                result.getObject("experiment_id", UUID.class),
                result.getString("error_message"),
                result.getString("category_name"),
                result.getObject("project_id", UUID.class),
                result.getString("project_name"),
                result.getObject("stage_id", UUID.class),
                result.getString("stage_name"),
                result.getObject("task_id", UUID.class),
                result.getString("task_name"),
                result.getString("visibility"),
                result.getTimestamp("created_at").toInstant()), organizationId);
    }

    private PGobject json(JsonNode value) {
        try {
            var object = new PGobject();
            object.setType("jsonb");
            object.setValue(objectMapper.writeValueAsString(value));
            return object;
        } catch (SQLException | JsonProcessingException exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
