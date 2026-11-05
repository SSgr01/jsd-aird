package com.jsd.aird.rnd.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.rnd.application.port.QuotationImportRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuotationImportRepository implements QuotationImportRepository {
    private static final String VISIBILITY = """
             AND (j.visibility = 'ALL' OR j.created_by = ? OR ? IN ('ADMIN', 'SYSTEM_ADMIN')
                  OR (j.visibility = 'RND' AND ? LIKE 'RND%')
                  OR (j.visibility = 'PROJECT' AND EXISTS (
                      SELECT 1 FROM iam.project_member pm
                      WHERE pm.organization_id = j.organization_id AND pm.project_id = j.project_id
                        AND pm.user_id = ?)))
            """;
    private final JdbcTemplate jdbc;

    public JdbcQuotationImportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(UUID id, UUID organizationId, UUID fileId, String fileName, String sha256,
                       String format, UUID actorId, UUID templateVersionId, UUID projectId,
                       String projectName, UUID stageId, String stageName, UUID taskId,
                       String taskName, String visibility) {
        jdbc.update("""
                INSERT INTO rnd.quotation_import_job
                (id, organization_id, source_file_id, source_file_name, source_sha256, source_format,
                 status, created_by, template_version_id, project_id, project_name,
                 stage_id, stage_name, task_id, task_name, visibility)
                VALUES (?, ?, ?, ?, ?, ?, 'PARSING', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, organizationId, fileId, fileName, sha256, format, actorId,
                templateVersionId, projectId, projectName, stageId, stageName, taskId,
                taskName, visibility);
    }

    @Override
    public Optional<Job> find(UUID organizationId, UUID userId, String role, UUID id) {
        return list(organizationId, userId, role).stream()
                .filter(job -> job.id().equals(id)).findFirst();
    }

    @Override
    public List<Job> list(UUID organizationId, UUID userId, String role) {
        return jdbc.query("""
                SELECT j.id, j.source_file_id, j.source_file_name, j.source_sha256, j.source_format,
                       j.status, j.quotation_id, q.quotation_no, q.customer_name,
                       j.error_message, j.created_at, j.template_version_id, j.project_id,
                       j.project_name, j.stage_id, j.stage_name, j.task_id, j.task_name, j.visibility,
                       COALESCE((SELECT count(*) FROM rnd.quotation_item i
                                 WHERE i.organization_id = j.organization_id
                                   AND i.quotation_id = j.quotation_id), 0) AS item_count
                FROM rnd.quotation_import_job j
                LEFT JOIN rnd.quotation_record q
                       ON q.organization_id = j.organization_id AND q.id = j.quotation_id
                WHERE j.organization_id = ? AND j.status <> 'CANCELLED'
                """ + VISIBILITY + """
                ORDER BY j.created_at DESC
                """, (result, row) -> job(result), organizationId, userId, role, role, userId);
    }

    @Override
    public Optional<Job> findCompletedBySourceHash(UUID organizationId, String sourceSha256) {
        return jdbc.query("""
                SELECT j.id, j.source_file_id, j.source_file_name, j.source_sha256, j.source_format,
                       j.status, j.quotation_id, q.quotation_no, q.customer_name,
                       j.error_message, j.created_at, j.template_version_id, j.project_id,
                       j.project_name, j.stage_id, j.stage_name, j.task_id, j.task_name, j.visibility,
                       COALESCE((SELECT count(*) FROM rnd.quotation_item i
                                 WHERE i.organization_id = j.organization_id
                                   AND i.quotation_id = j.quotation_id), 0) AS item_count
                FROM rnd.quotation_import_job j
                LEFT JOIN rnd.quotation_record q
                       ON q.organization_id = j.organization_id AND q.id = j.quotation_id
                WHERE j.organization_id = ? AND j.source_sha256 = ? AND j.status = 'COMPLETED'
                ORDER BY j.created_at DESC LIMIT 1
                """, (result, row) -> job(result), organizationId, sourceSha256)
                .stream().findFirst();
    }

    @Override
    public int markRetrying(UUID organizationId, UUID id) {
        return jdbc.update("""
                UPDATE rnd.quotation_import_job
                SET status = 'PARSING', error_message = NULL, parse_result_jsonb = '{}'::jsonb,
                    updated_at = now()
                WHERE organization_id = ? AND id = ? AND status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                """, organizationId, id);
    }

    @Override
    public int cancel(UUID organizationId, UUID userId, String role, UUID id) {
        return jdbc.update("""
                UPDATE rnd.quotation_import_job j
                SET status = 'CANCELLED', error_message = '用户取消解析', updated_at = now()
                WHERE j.organization_id = ? AND j.id = ? AND j.status = 'PARSING'
                """ + VISIBILITY, organizationId, id, userId, role, role, userId);
    }

    @Override
    public boolean isParsing(UUID organizationId, UUID id) {
        return !jdbc.query("""
                SELECT id FROM rnd.quotation_import_job
                WHERE organization_id = ? AND id = ? AND status = 'PARSING'
                FOR UPDATE
                """, (result, row) -> result.getObject("id", UUID.class), organizationId, id).isEmpty();
    }

    @Override
    public void complete(UUID id, UUID quotationId) {
        jdbc.update("""
                UPDATE rnd.quotation_import_job
                SET status = 'COMPLETED', quotation_id = ?, updated_at = now()
                WHERE id = ? AND status = 'PARSING'
                """, quotationId, id);
    }

    @Override
    public void fail(UUID id, String errorMessage) {
        jdbc.update("""
                UPDATE rnd.quotation_import_job
                SET status = 'FAILED', error_message = ?, updated_at = now()
                WHERE id = ? AND status = 'PARSING'
                """, errorMessage == null || errorMessage.isBlank() ? "报价文件解析失败"
                : errorMessage.substring(0, Math.min(2000, errorMessage.length())), id);
    }

    @Override
    public int delete(UUID organizationId, UUID userId, String role, UUID id) {
        return jdbc.update("""
                DELETE FROM rnd.quotation_import_job j
                WHERE j.organization_id = ? AND j.id = ?
                  AND j.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                """ + VISIBILITY, organizationId, id, userId, role, role, userId);
    }

    private Job job(ResultSet result) throws SQLException {
        return new Job(result.getObject("id", UUID.class),
                result.getObject("source_file_id", UUID.class),
                result.getString("source_file_name"),
                result.getString("source_sha256"),
                result.getString("source_format"),
                result.getString("status"),
                result.getObject("quotation_id", UUID.class),
                result.getString("quotation_no"),
                result.getString("customer_name"),
                result.getInt("item_count"),
                result.getString("error_message"),
                result.getTimestamp("created_at").toInstant(),
                0, null,
                result.getObject("template_version_id", UUID.class),
                result.getObject("project_id", UUID.class), result.getString("project_name"),
                result.getObject("stage_id", UUID.class), result.getString("stage_name"),
                result.getObject("task_id", UUID.class), result.getString("task_name"),
                result.getString("visibility"));
    }
}
