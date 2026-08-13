package com.jsd.aird.tpl.infrastructure;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.tpl.application.RecognitionIdentity;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import com.jsd.aird.tpl.application.port.TemplateImportRepository;
import com.jsd.aird.tpl.domain.QualityIssueSeverity;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTemplateImportRepository implements TemplateImportRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ModelAuditPayloadCodec auditPayloadCodec;

    public JdbcTemplateImportRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.auditPayloadCodec = new ModelAuditPayloadCodec(objectMapper);
    }

    @Override
    public void enqueue(NewImportJob job) {
        var payload = objectMapper.createObjectNode()
                .put("importJobId", job.importJobId().toString())
                .put("organizationId", job.organizationId().toString())
                .put("fileId", job.fileId().toString())
                .put("format", job.format().name())
                .put("sourceKind", job.sourceKind())
                .put("scope", job.scope());
        if (job.sheetId() != null) payload.put("sheetId", job.sheetId());
        if (job.address() != null) payload.put("address", job.address());
        if (job.snapshotFragment() != null && !job.snapshotFragment().isNull()) {
            payload.set("snapshotFragment", job.snapshotFragment().deepCopy());
        }
        jdbcTemplate.update("""
                        INSERT INTO ops.async_job (
                            id, organization_id, job_type, status, payload_jsonb,
                            priority, idempotency_key
                        ) VALUES (?, ?, ?, 'READY', ?, 50, ?)
                        """,
                job.asyncJobId(),
                job.organizationId(),
                "UNIVER_SNAPSHOT".equals(job.sourceKind())
                        ? "XLSX_SNAPSHOT_RECOGNIZE"
                        : job.format() == TemplateFormat.XLSX ? "XLSX_PARSE" : "DOCX_PARSE",
                pgJson(payload),
                "template-import:" + job.importJobId()
        );
        jdbcTemplate.update("""
                        INSERT INTO tpl.template_import_job (
                            id, organization_id, source_file_id, format, status,
                            progress, async_job_id, created_by, category_id, source_sha256,
                            duplicate_override, duplicate_source_job_id, operation_source
                        ) VALUES (?, ?, ?, ?, 'QUEUED', 0, ?, ?, ?, ?, ?, ?, ?)
                        """,
                job.importJobId(),
                job.organizationId(),
                job.fileId(),
                job.format().name(),
                job.asyncJobId(),
                job.actorId(),
                job.categoryId(),
                job.sourceSha256(),
                job.duplicateOverride(),
                job.duplicateSourceJobId(),
                job.operationSource()
        );
    }

    @Override
    public boolean enqueueRerun(RerunImportJob job) {
        var payload = objectMapper.createObjectNode()
                .put("importJobId", job.importJobId().toString())
                .put("organizationId", job.organizationId().toString())
                .put("fileId", job.sourceFileId().toString())
                .put("format", job.format().name())
                .put("sourceKind", job.sourceKind())
                .put("source", "UNIVER_SNAPSHOT".equals(job.sourceKind())
                        ? "CURRENT_DRAFT_SNAPSHOT" : "ORIGINAL_FILE")
                .put("scope", "WORKBOOK")
                .put("runReason", job.runReason());
        if (job.parentRunId() != null) payload.put("parentRunId", job.parentRunId().toString());
        jdbcTemplate.update("""
                        INSERT INTO ops.async_job (
                            id, organization_id, job_type, status, payload_jsonb,
                            priority, idempotency_key
                        ) VALUES (?, ?, ?, 'READY', ?, 50, ?)
                        """, job.asyncJobId(), job.organizationId(),
                        "UNIVER_SNAPSHOT".equals(job.sourceKind())
                        ? "XLSX_SNAPSHOT_RECOGNIZE"
                        : job.format() == TemplateFormat.XLSX ? "XLSX_PARSE" : "DOCX_PARSE",
                pgJson(payload),
                "template-import-rerun:" + job.importJobId() + ":" + job.asyncJobId());
        var updated = jdbcTemplate.update("""
                        UPDATE tpl.template_import_job tij
                        SET async_job_id = ?, status = 'QUEUED', progress = 0, updated_at = now()
                        WHERE tij.id = ? AND tij.organization_id = ?
                          AND EXISTS (
                              SELECT 1 FROM ops.async_job current_job
                              WHERE current_job.id = tij.async_job_id
                                AND current_job.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                          )
                        """, job.asyncJobId(), job.importJobId(), job.organizationId());
        return updated == 1;
    }

    @Override
    public Optional<ImportJobView> find(UUID organizationId, UUID importJobId) {
        return queryJobs("""
                        WHERE tij.id = ? AND tij.organization_id = ?
                        """, importJobId, organizationId).stream().findFirst();
    }

    @Override
    public Optional<ImportJobView> findLatestForVersion(UUID organizationId, UUID versionId) {
        return queryJobs("""
                        WHERE tij.generated_template_version_id = ? AND tij.organization_id = ?
                        ORDER BY tij.created_at DESC
                        LIMIT 1
                        """, versionId, organizationId).stream().findFirst();
    }

    @Override
    public Optional<UUID> findOriginalSourceFileId(UUID organizationId, UUID versionId) {
        return jdbcTemplate.query("""
                        SELECT tij.source_file_id
                        FROM tpl.template_import_job tij
                        JOIN ops.file_object source ON source.id = tij.source_file_id
                        WHERE tij.generated_template_version_id = ?
                          AND tij.organization_id = ?
                          AND source.content_type NOT LIKE '%json%'
                          AND lower(source.original_name) ~ '\\.(xlsx|docx)$'
                        ORDER BY tij.created_at ASC, tij.id ASC
                        LIMIT 1
                        """, (rs, rowNum) -> rs.getObject(1, UUID.class), versionId, organizationId)
                .stream().findFirst();
    }

    @Override
    public Optional<UUID> findGeneratedVersionId(UUID importJobId) {
        return jdbcTemplate.query("""
                        SELECT generated_template_version_id
                        FROM tpl.template_import_job
                        WHERE id = ? AND generated_template_version_id IS NOT NULL
                        """, (rs, rowNum) -> rs.getObject(1, UUID.class), importJobId)
                .stream().findFirst();
    }

    @Override
    public int countManualReruns(UUID importJobId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*)::int
                FROM tpl.recognition_run
                WHERE import_job_id = ?
                  AND run_reason LIKE 'MANUAL_RERUN%'
                """, Integer.class, importJobId);
        return count == null ? 0 : count;
    }

    @Override
    public List<ImportJobView> list(UUID organizationId) {
        return queryJobs(false, """
                        WHERE tij.organization_id = ?
                        ORDER BY tij.created_at DESC
                        LIMIT 100
                        """, organizationId);
    }

    @Override
    public Optional<ImportJobView> findDuplicate(UUID organizationId, String sourceSha256, TemplateFormat format) {
        if (sourceSha256 == null || sourceSha256.isBlank()) return Optional.empty();
        return queryJobs("""
                WHERE tij.organization_id = ? AND tij.source_sha256 = ? AND tij.format = ?
                ORDER BY tij.created_at DESC LIMIT 1
                """, organizationId, sourceSha256, format.name()).stream().findFirst();
    }

    private List<ImportJobView> queryJobs(String suffix, Object... arguments) {
        return queryJobs(true, suffix, arguments);
    }

    private List<ImportJobView> queryJobs(boolean includeDetails, String suffix, Object... arguments) {
        var detailProjection = includeDetails
                ? "tij.structure_summary_jsonb, aj.result_jsonb,"
                : "'{}'::jsonb AS structure_summary_jsonb, "
                  + "jsonb_build_object("
                  + "'modelStatus', COALESCE(aj.result_jsonb ->> 'modelStatus', 'NOT_APPLICABLE'), "
                  + "'recognitionStatus', COALESCE(aj.result_jsonb ->> 'recognitionStatus', 'REVIEW_REQUIRED')"
                  + ") AS result_jsonb,";
        return jdbcTemplate.query("""
                        SELECT tij.id, tij.source_file_id, fo.original_name AS source_file_name,
                               tij.format,
                               CASE
                                   WHEN aj.status = 'SUCCEEDED' THEN 'PARSED'
                                   WHEN aj.status = 'FAILED' THEN 'FAILED'
                                   ELSE aj.status
                               END AS status,
                                aj.progress, aj.current_stage,
                               """ + detailProjection + """
                               jsonb_build_object(
                                   'parseStatus', CASE
                                       WHEN aj.status = 'SUCCEEDED' THEN 'PARSED'
                                       WHEN aj.status = 'FAILED' THEN 'FAILED'
                                       ELSE aj.status
                                   END,
                                   'runStatus', COALESCE(latest_run.status, 'NONE'),
                                   'modelStatus', COALESCE(aj.result_jsonb ->> 'modelStatus', 'NOT_APPLICABLE'),
                                   'recognitionStatus', COALESCE(aj.result_jsonb ->> 'recognitionStatus', 'REVIEW_REQUIRED'),
                                   'reviewResolutionStatus', COALESCE(aj.result_jsonb ->> 'reviewResolutionStatus', 'OPEN'),
                                   'canonicalStatus', COALESCE(aj.result_jsonb ->> 'canonicalStatus', 'PROVISIONAL'),
                                   'publicationReadiness', COALESCE(aj.result_jsonb ->> 'publicationReadiness', 'NOT_READY'),
                                   'coverage', COALESCE(aj.result_jsonb -> 'recognitionCoverage', '{}'::jsonb),
                                   'counts', jsonb_build_object(
                                       'rawSuggestions', (SELECT count(*)::int
                                          FROM tpl.recognition_suggestion rs
                                          WHERE rs.import_job_id = tij.id
                                            AND rs.recognition_run_id = latest_run.id),
                                       'pendingSuggestions', (SELECT count(*)::int
                                          FROM tpl.recognition_suggestion rs
                                          WHERE rs.import_job_id = tij.id
                                            AND rs.recognition_run_id = latest_run.id
                                            AND rs.decision = 'PENDING'),
                                       'pendingFields', (SELECT count(*)::int
                                          FROM tpl.recognition_suggestion rs
                                          WHERE rs.import_job_id = tij.id
                                            AND rs.recognition_run_id = latest_run.id
                                            AND rs.decision = 'PENDING'
                                            AND rs.suggestion_type IN ('SCALAR_FIELD', 'TABLE_CHILD_FIELD', 'MATRIX_FIELD')
                                            AND NULLIF(BTRIM(rs.payload_jsonb ->> 'fieldName'), '') IS NOT NULL
                                            AND COALESCE(rs.payload_jsonb ->> 'runtimeInputOnly', 'false') <> 'true'
                                            AND COALESCE(rs.payload_jsonb ->> 'nameSource', '') <> 'RUNTIME_SLOT'
                                            AND COALESCE((rs.payload_jsonb ->> 'suppressed')::boolean, false) = false
                                            AND COALESCE(rs.payload_jsonb ->> 'structureStatus', '') NOT IN ('SUPERSEDED', 'REJECTED')
                                            AND COALESCE(rs.payload_jsonb ->> 'pendingReason', '') <> 'PHYSICAL_STRUCTURE_SELECTED'),
                                       'reviewableFields', (SELECT count(*)::int
                                          FROM tpl.recognition_suggestion rs
                                          WHERE rs.import_job_id = tij.id
                                            AND rs.recognition_run_id = latest_run.id
                                            AND rs.decision NOT IN ('REJECTED', 'REJECTED_BY_RESOLUTION')
                                            AND rs.suggestion_type IN ('SCALAR_FIELD', 'TABLE_CHILD_FIELD', 'MATRIX_FIELD')
                                            AND NULLIF(BTRIM(rs.payload_jsonb ->> 'fieldName'), '') IS NOT NULL
                                            AND COALESCE(rs.payload_jsonb ->> 'runtimeInputOnly', 'false') <> 'true'
                                            AND COALESCE(rs.payload_jsonb ->> 'nameSource', '') <> 'RUNTIME_SLOT'
                                            AND COALESCE((rs.payload_jsonb ->> 'suppressed')::boolean, false) = false
                                            AND COALESCE(rs.payload_jsonb ->> 'structureStatus', '') NOT IN ('SUPERSEDED', 'REJECTED')
                                            AND COALESCE(rs.payload_jsonb ->> 'pendingReason', '') <> 'PHYSICAL_STRUCTURE_SELECTED'),
                                       'structureCandidates', (SELECT count(*)::int FROM (
                                          SELECT COALESCE(NULLIF(rs.payload_jsonb ->> 'resolutionGroupId', ''), rs.id::text) AS candidate_group
                                          FROM tpl.recognition_suggestion rs
                                          WHERE rs.import_job_id = tij.id
                                            AND rs.recognition_run_id = latest_run.id
                                            AND rs.decision NOT IN ('REJECTED', 'REJECTED_BY_RESOLUTION')
                                            AND COALESCE(rs.payload_jsonb ->> 'suppressed', 'false') <> 'true'
                                            AND (
                                                rs.suggestion_type IN ('TABLE_REGION', 'TABLE_FIELD')
                                                OR rs.payload_jsonb ->> 'kind' IN ('MATRIX', 'ROW_TABLE', 'COLUMN_TABLE', 'FORM_REGION', 'TABLE_REGION')
                                            )
                                          GROUP BY COALESCE(NULLIF(rs.payload_jsonb ->> 'resolutionGroupId', ''), rs.id::text)
                                       ) candidates),
                                       'structureConflictGroups', (SELECT count(*)::int FROM (
                                          SELECT COALESCE(NULLIF(rs.payload_jsonb ->> 'resolutionGroupId', ''), rs.id::text) AS conflict_group
                                          FROM tpl.recognition_suggestion rs
                                          WHERE rs.import_job_id = tij.id
                                            AND rs.recognition_run_id = latest_run.id
                                            AND rs.decision NOT IN ('REJECTED', 'REJECTED_BY_RESOLUTION')
                                            AND rs.payload_jsonb ->> 'structureConflict' = 'true'
                                            AND COALESCE(rs.payload_jsonb ->> 'resolutionStatus', '') <> 'AUTO_RESOLVED'
                                            AND COALESCE(rs.payload_jsonb ->> 'suppressed', 'false') <> 'true'
                                          GROUP BY COALESCE(NULLIF(rs.payload_jsonb ->> 'resolutionGroupId', ''), rs.id::text)
                                       ) conflicts),
                                       'qualityIssues', (SELECT count(*)::int
                                          FROM tpl.template_quality_issue qi
                                          WHERE qi.import_job_id = tij.id
                                            AND qi.recognition_run_id = latest_run.id
                                            AND qi.status NOT IN ('AUTO_APPLIED', 'CONFIRMED', 'IGNORED'))
                                   )
                               ) AS recognition_summary,
                               aj.last_error, tij.created_at,
                               (SELECT count(*)::int FROM tpl.recognition_run rerun
                                WHERE rerun.import_job_id = tij.id
                                  AND rerun.run_reason LIKE 'MANUAL_RERUN%') AS retry_count,
                               latest_run.id AS recognition_run_id,
                               latest_run.status AS recognition_run_status,
                               tij.generated_template_version_id,
                               tv.workspace_hash, tij.category_id, tc.name AS category_name,
                               tij.source_sha256, tij.duplicate_override, tij.duplicate_source_job_id,
                               (SELECT count(*)::int FROM tpl.recognition_suggestion rs
                                 WHERE rs.import_job_id = tij.id
                                   AND rs.recognition_run_id = latest_run.id) AS suggestion_count,
                               (SELECT count(*)::int FROM tpl.recognition_suggestion rs
                                WHERE rs.import_job_id = tij.id
                                  AND rs.recognition_run_id = latest_run.id
                                  AND rs.decision = 'PENDING') AS pending_suggestion_count
                        FROM tpl.template_import_job tij
                        JOIN ops.async_job aj ON aj.id = tij.async_job_id
                        JOIN ops.file_object fo ON fo.id = tij.source_file_id
                        LEFT JOIN tpl.template_version tv ON tv.id = tij.generated_template_version_id
                        LEFT JOIN tpl.template_category tc ON tc.id = tij.category_id
                        LEFT JOIN LATERAL (
                            SELECT rr.id, rr.status
                            FROM tpl.recognition_run rr
                            WHERE rr.import_job_id = tij.id
                            ORDER BY rr.created_at DESC, rr.id DESC
                            LIMIT 1
                        ) latest_run ON true
                        """ + suffix,
                (rs, rowNum) -> new ImportJobView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("source_file_id", UUID.class),
                        rs.getString("source_file_name"),
                        TemplateFormat.valueOf(rs.getString("format")),
                        rs.getString("status"),
                        rs.getInt("progress"),
                        rs.getString("current_stage"),
                         parse(rs.getString("structure_summary_jsonb")),
                         parse(rs.getString("result_jsonb")),
                         parse(rs.getString("recognition_summary")),
                        rs.getString("last_error"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("retry_count"),
                        rs.getInt("suggestion_count"),
                        rs.getInt("pending_suggestion_count"),
                        rs.getObject("recognition_run_id", UUID.class),
                        rs.getString("recognition_run_status"),
                        rs.getObject("generated_template_version_id", UUID.class),
                        rs.getString("workspace_hash"),
                        includeDetails ? loadIssues(rs.getObject("id", UUID.class)) : List.of(),
                        rs.getObject("category_id", UUID.class),
                        rs.getString("category_name"),
                        rs.getString("source_sha256"),
                        rs.getBoolean("duplicate_override"),
                        rs.getObject("duplicate_source_job_id", UUID.class)
                ),
                arguments
        );
    }

    @Override
    public void complete(UUID importJobId, OfficeStructureParser.ParseResult result) {
        jdbcTemplate.update("""
                        UPDATE tpl.template_import_job
                        SET status = 'PARSED', progress = 100, parser_version = ?,
                            structure_summary_jsonb = ?, updated_at = now()
                        WHERE id = ?
                        """,
                result.structureSummary().path("parserVersion").asText("structure-v4"),
                pgJson(result.structureSummary()),
                importJobId
        );
        jdbcTemplate.update(
                "DELETE FROM tpl.template_import_issue WHERE import_job_id = ?",
                importJobId
        );
        for (var issue : result.issues()) {
            jdbcTemplate.update("""
                            INSERT INTO tpl.template_import_issue (
                                id, import_job_id, severity, issue_code,
                                location_jsonb, message, resolution
                            ) VALUES (?, ?, ?, ?, ?, ?, 'OPEN')
                            """,
                    UUID.randomUUID(),
                    importJobId,
                    QualityIssueSeverity.normalize(issue.severity()),
                    issue.code(),
                    pgJson(issue.location()),
                    issue.message()
            );
        }
        var resultJson = objectMapper.createObjectNode()
                .set("initialEditorSnapshot", result.initialEditorSnapshot());
        jdbcTemplate.update("""
                        UPDATE ops.async_job
                        SET result_jsonb = ?, current_stage = 'PERSISTING_RESULT',
                            progress = 95, updated_at = now()
                        WHERE id = (
                            SELECT async_job_id FROM tpl.template_import_job WHERE id = ?
                        )
                        """,
                pgJson(resultJson),
                importJobId
        );
    }

    @Override
    public void updateProgress(UUID importJobId, int progress, String stage) {
        jdbcTemplate.update("""
                        UPDATE ops.async_job
                        SET progress = GREATEST(progress, ?), current_stage = ?, updated_at = now()
                        WHERE id = (
                            SELECT async_job_id FROM tpl.template_import_job WHERE id = ?
                        )
                        """,
                progress,
                stage,
                importJobId
        );
        jdbcTemplate.update("""
                        UPDATE tpl.template_import_job
                        SET status = 'PARSING', progress = GREATEST(progress, ?),
                            updated_at = now()
                        WHERE id = ?
                        """,
                progress,
                importJobId
        );
    }

    @Override
    public UUID startRecognitionRun(
            UUID importJobId, String scope, int structureVersion, int snapshotFormatVersion, int regionCount,
            UUID parentRunId, String runReason
    ) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO tpl.recognition_run (
                            id, import_job_id, scope, status, structure_version, snapshot_format_version, region_count,
                            parent_run_id, run_reason
                        ) VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?)
                        """, id, importJobId, scope, structureVersion, snapshotFormatVersion, regionCount,
                parentRunId, runReason == null || runReason.isBlank() ? "INITIAL_RECOGNITION" : runReason);
        return id;
    }

    @Override
    public void saveRenderSnapshot(UUID importJobId, JsonNode snapshot) {
        var result = objectMapper.createObjectNode();
        result.set("initialEditorSnapshot", snapshot == null ? objectMapper.createObjectNode() : snapshot.deepCopy());
        jdbcTemplate.update("""
                        UPDATE ops.async_job
                        SET result_jsonb = ?, current_stage = 'RENDER_CONTEXT_READY', updated_at = now()
                        WHERE id = (SELECT async_job_id FROM tpl.template_import_job WHERE id = ?)
                        """, pgJson(result), importJobId);
    }

    @Override
    public void saveImportResult(UUID importJobId, JsonNode result) {
        jdbcTemplate.update("""
                        UPDATE ops.async_job
                        SET result_jsonb = ?, updated_at = now()
                        WHERE id = (
                            SELECT async_job_id FROM tpl.template_import_job WHERE id = ?
                        )
                        """,
                pgJson(result == null ? objectMapper.createObjectNode() : result),
                importJobId
        );
    }

    @Override
    public void fail(UUID importJobId, String message) {
        jdbcTemplate.update("""
                UPDATE tpl.template_import_job
                SET status = 'FAILED', updated_at = now()
                WHERE id = ? AND status NOT IN ('PARSED', 'FAILED')
                """, importJobId);
        jdbcTemplate.update("""
                INSERT INTO tpl.template_import_issue (
                    id, import_job_id, severity, issue_code, location_jsonb, message, resolution
                )
                SELECT ?, ?, 'BLOCKER', 'TEMPLATE_IMPORT_FAILED', '{}'::jsonb, ?, 'OPEN'
                WHERE NOT EXISTS (
                    SELECT 1 FROM tpl.template_import_issue
                    WHERE import_job_id = ? AND issue_code = 'TEMPLATE_IMPORT_FAILED'
                      AND resolution = 'OPEN'
                )
                """, UUID.randomUUID(), importJobId,
                message == null || message.isBlank() ? "导入任务执行失败，请检查文件后重试" : message,
                importJobId);
    }

    @Override
    public void updateRecognitionRunSnapshot(UUID recognitionRunId, String snapshotHash, String reason) {
        jdbcTemplate.update("""
                        UPDATE tpl.recognition_run
                        SET source_snapshot_hash = ?, run_reason = ?
                        WHERE id = ?
                """, snapshotHash, reason, recognitionRunId);
    }

    @Override
    public void updateRecognitionRunRegionCount(UUID recognitionRunId, int regionCount) {
        jdbcTemplate.update("UPDATE tpl.recognition_run SET region_count = ? WHERE id = ?",
                Math.max(1, regionCount), recognitionRunId);
    }

    @Override
    public void saveRecognitionCall(
            UUID recognitionRunId,
            com.jsd.aird.tpl.application.port.RecognitionModelClient.CallTrace trace
    ) {
        jdbcTemplate.update("""
                        INSERT INTO tpl.recognition_call (
                            id, recognition_run_id, region_id, attempt, provider, model, prompt_version,
                            status, http_status, started_at, finished_at, duration_ms,
                            prompt_tokens, completion_tokens, total_tokens,
                            request_payload_gzip, response_payload_gzip, request_hash, response_hash,
                             error_type, error_message, finish_reason, outcome_code, response_truncated,
                             phase, parent_call_id
                         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                trace.callId(), recognitionRunId, trace.regionId(), trace.attempt(), trace.provider(),
                trace.model(), trace.promptVersion(), trace.status(), trace.httpStatus(),
                java.sql.Timestamp.from(trace.startedAt()), java.sql.Timestamp.from(trace.finishedAt()),
                trace.durationMs(), trace.promptTokens(), trace.completionTokens(), trace.totalTokens(),
                auditPayloadCodec.compress(trace.requestPayload()), auditPayloadCodec.compress(trace.responsePayload()), trace.requestHash(),
                trace.responseHash(), blankToNull(trace.errorType()), blankToNull(trace.errorMessage()),
                blankToNull(trace.finishReason()), blankToNull(trace.outcomeCode()), trace.responseTruncated(),
                trace.phase(), trace.parentCallId()
        );
    }

    @Override
    public void completeRecognitionRun(UUID recognitionRunId, String status) {
        jdbcTemplate.update("""
                        UPDATE tpl.recognition_run rr
                        SET status = ?, finished_at = now(),
                            call_count = summary.call_count,
                            succeeded_call_count = summary.succeeded_count,
                            failed_call_count = summary.failed_count,
                            prompt_tokens = summary.prompt_tokens,
                            completion_tokens = summary.completion_tokens,
                            total_tokens = summary.total_tokens
                        FROM (
                            SELECT count(*)::int call_count,
                                   count(*) FILTER (WHERE status = 'SUCCEEDED')::int succeeded_count,
                                   count(*) FILTER (WHERE status = 'FAILED')::int failed_count,
                                   coalesce(sum(prompt_tokens), 0) prompt_tokens,
                                   coalesce(sum(completion_tokens), 0) completion_tokens,
                                   coalesce(sum(total_tokens), 0) total_tokens
                            FROM tpl.recognition_call WHERE recognition_run_id = ?
                        ) summary
                        WHERE rr.id = ?
                        """, status, recognitionRunId, recognitionRunId);
    }

    @Override
    public int purgeExpiredRecognitionPayloads() {
        return jdbcTemplate.update("""
                        UPDATE tpl.recognition_call
                        SET request_payload_gzip = NULL, response_payload_gzip = NULL,
                            payload_purged_at = now()
                        WHERE payload_purged_at IS NULL AND payload_expires_at <= now()
                        """);
    }

    @Override
    public void replaceModelSuggestions(
            UUID importJobId,
            UUID recognitionRunId,
            com.jsd.aird.tpl.application.port.RecognitionModelClient.RecognitionBatch batch
    ) {
        replaceSuggestions(importJobId, recognitionRunId, "MODEL", batch);
    }

    @Override
    public void appendModelSuggestions(
            UUID importJobId, UUID recognitionRunId,
            com.jsd.aird.tpl.application.port.RecognitionModelClient.RecognitionBatch batch
    ) {
        persistSuggestions(importJobId, recognitionRunId, "MODEL", batch);
    }

    @Override
    public void supersedeStructureGeneration(
            UUID organizationId,
            UUID recognitionRunId,
            List<UUID> selectedStructureSuggestionIds,
            List<String> selectedRegionIds,
            String generationId,
            UUID actorId
    ) {
        if ((selectedStructureSuggestionIds == null || selectedStructureSuggestionIds.isEmpty())
                && (selectedRegionIds == null || selectedRegionIds.isEmpty())) return;
        var supersedeSql = """
                UPDATE tpl.recognition_suggestion rs
                SET decision = 'REJECTED', decided_by = ?, decided_at = now(),
                    payload_jsonb = rs.payload_jsonb || jsonb_build_object(
                        'suppressed', true,
                        'structureStatus', 'SUPERSEDED',
                        'resolutionDecision', 'SUPERSEDED_BY_RECOMPILE',
                        'supersededByGenerationId', ?)
                FROM tpl.recognition_run rr
                JOIN tpl.template_import_job tij ON tij.id = rr.import_job_id
                WHERE rs.recognition_run_id = ?
                  AND rr.id = rs.recognition_run_id
                  AND tij.organization_id = ?
                  AND (rs.region_id = ?
                    OR rs.payload_jsonb ->> 'regionId' = ?
                    OR rs.payload_jsonb ->> 'blockId' = ?
                    OR rs.payload_jsonb ->> 'candidateRef' = ?
                    OR rs.payload_jsonb ->> 'semanticRecompileRegionId' = ?)
                """;
        if (selectedRegionIds != null) {
            selectedRegionIds.stream().filter(value -> value != null && !value.isBlank()).distinct().forEach(regionId ->
                    jdbcTemplate.update(supersedeSql, actorId, generationId, recognitionRunId,
                            organizationId, regionId, regionId, regionId, regionId, regionId));
        }
        if (selectedStructureSuggestionIds == null) return;
        for (var suggestionId : selectedStructureSuggestionIds) {
            jdbcTemplate.update("""
                    UPDATE tpl.recognition_suggestion rs
                    SET decision = 'PENDING', decided_by = NULL, decided_at = NULL,
                        payload_jsonb = ((rs.payload_jsonb
                            - 'suppressed' - 'supersededByGenerationId' - 'resolutionDecision')
                            || jsonb_build_object(
                                'activeGenerationId', ?,
                                'semanticRecompileStatus', 'RUNNING'))
                    FROM tpl.recognition_run rr
                    JOIN tpl.template_import_job tij ON tij.id = rr.import_job_id
                    WHERE rs.id = ? AND rs.recognition_run_id = ?
                      AND rr.id = rs.recognition_run_id AND tij.organization_id = ?
                    """, generationId, suggestionId, recognitionRunId, organizationId);
            jdbcTemplate.update("""
                    UPDATE tpl.recognition_suggestion rs
                    SET decision = 'REJECTED', decided_by = ?, decided_at = now(),
                        payload_jsonb = rs.payload_jsonb || jsonb_build_object(
                            'suppressed', true,
                            'structureStatus', 'SUPERSEDED',
                            'resolutionDecision', 'SUPERSEDED_BY_RECOMPILE',
                            'supersededByGenerationId', ?)
                    FROM tpl.recognition_run rr
                    JOIN tpl.template_import_job tij ON tij.id = rr.import_job_id
                    WHERE rs.parent_suggestion_id = ? AND rs.recognition_run_id = ?
                      AND rr.id = rs.recognition_run_id AND tij.organization_id = ?
                    """, actorId, generationId, suggestionId, recognitionRunId, organizationId);
        }
    }

    @Override
    public void replacePhysicalSuggestions(
            UUID importJobId,
            UUID recognitionRunId,
            com.jsd.aird.tpl.application.port.RecognitionModelClient.RecognitionBatch batch
    ) {
        replaceSuggestions(importJobId, recognitionRunId, "PHYSICAL", batch);
    }

    @Override
    public void replaceRuleSuggestions(
            UUID importJobId,
            UUID recognitionRunId,
            com.jsd.aird.tpl.application.port.RecognitionModelClient.RecognitionBatch batch
    ) {
        replaceSuggestions(importJobId, recognitionRunId, "RULE", batch);
    }

    @Override
    public void appendRuleSuggestions(
            UUID importJobId,
            UUID recognitionRunId,
            com.jsd.aird.tpl.application.port.RecognitionModelClient.RecognitionBatch batch
    ) {
        persistSuggestions(importJobId, recognitionRunId, "RULE", batch);
    }

    @Override
    public void replaceQualityIssues(
            UUID importJobId,
            UUID recognitionRunId,
            List<com.jsd.aird.tpl.application.port.RecognitionModelClient.QualityIssueSuggestion> issues,
            String beforeSnapshotHash,
            String afterSnapshotHash
    ) {
        jdbcTemplate.update(
                "DELETE FROM tpl.template_quality_issue WHERE recognition_run_id = ?",
                recognitionRunId
        );
        for (var issue : issues) {
            jdbcTemplate.update("""
                            INSERT INTO tpl.template_quality_issue (
                                id, import_job_id, recognition_run_id, recognition_call_id, region_id,
                                issue_type, severity, confidence, sheet_id, sheet_name, address,
                                title, description, business_impact, evidence_jsonb,
                                suggested_patch_jsonb, inverse_patch_jsonb, auto_fixable, status,
                                before_snapshot_hash, after_snapshot_hash, root_block_id,
                                customer_issue_category
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    UUID.randomUUID(), importJobId, recognitionRunId, issue.recognitionCallId(),
                    blankToNull(issue.regionId()), issue.issueType(),
                    QualityIssueSeverity.normalize(issue.severity()), issue.confidence(),
                    blankToNull(issue.sheetId()), blankToNull(issue.sheetName()), issue.address(),
                    issue.title(), issue.description(), issue.businessImpact(),
                    pgJson(issue.evidence() == null ? objectMapper.createArrayNode() : issue.evidence()),
                    pgJson(issue.suggestedPatch() == null ? objectMapper.createObjectNode() : issue.suggestedPatch()),
                    pgJson(issue.inversePatch() == null ? objectMapper.createObjectNode() : issue.inversePatch()),
                    issue.autoFixable(),
                    qualityIssueStatus(issue.status()), beforeSnapshotHash, afterSnapshotHash,
                    blankToNull(issue.regionId()), issue.issueType()
            );
        }
    }

    /**
     * Quality issues use the database lifecycle (DETECTED -> CONFIRMED/IGNORED),
     * while recognition runs use OPEN/RESOLVED.  Keep that distinction at the
     * persistence boundary so a diagnostic cannot abort the whole import by
     * violating template_quality_issue_status_check.
     */
    private String qualityIssueStatus(String status) {
        return switch (status == null ? "" : status) {
            case "DETECTED", "AUTO_APPLIED", "CONFIRMED", "IGNORED", "ROLLED_BACK", "FAILED" -> status;
            case "OPEN", "PENDING", "" -> "DETECTED";
            default -> "DETECTED";
        };
    }

    @Override
    public List<QualityIssueView> listQualityIssues(UUID organizationId, UUID importJobId) {
        return jdbcTemplate.query("""
                        SELECT qi.id, qi.import_job_id, qi.recognition_run_id, qi.recognition_call_id,
                               qi.region_id, qi.issue_type, qi.severity, qi.confidence,
                               qi.sheet_id, qi.sheet_name, qi.address, qi.title, qi.description,
                               qi.business_impact, qi.evidence_jsonb, qi.suggested_patch_jsonb,
                               qi.inverse_patch_jsonb, qi.auto_fixable, qi.status,
                               qi.before_snapshot_hash, qi.after_snapshot_hash, qi.created_at
                        FROM tpl.template_quality_issue qi
                        JOIN tpl.template_import_job tij ON tij.id = qi.import_job_id
                        WHERE qi.import_job_id = ? AND tij.organization_id = ?
                          AND qi.recognition_run_id = (
                              SELECT rr.id FROM tpl.recognition_run rr
                              WHERE rr.import_job_id = tij.id
                              ORDER BY rr.created_at DESC, rr.id DESC LIMIT 1
                          )
                        ORDER BY CASE qi.severity WHEN 'BLOCKER' THEN 1 WHEN 'WARNING' THEN 2 ELSE 3 END,
                                 qi.created_at, qi.id
                        """, (rs, rowNum) -> mapQualityIssue(rs), importJobId, organizationId);
    }

    @Override
    public Optional<QualityIssueView> decideQualityIssue(
            UUID organizationId, UUID importJobId, UUID issueId, String action, UUID actorId
    ) {
        var status = switch (action) {
            case "APPLY" -> "CONFIRMED";
            case "IGNORE" -> "IGNORED";
            case "ROLLBACK" -> "ROLLED_BACK";
            default -> throw new IllegalArgumentException("Unsupported quality issue action: " + action);
        };
        var updated = jdbcTemplate.update("""
                        UPDATE tpl.template_quality_issue qi
                        SET status = ?, decided_by = ?, decided_at = now(), updated_at = now()
                        FROM tpl.template_import_job tij
                        WHERE qi.id = ? AND qi.import_job_id = ?
                          AND tij.id = qi.import_job_id AND tij.organization_id = ?
                        """, status, actorId, issueId, importJobId, organizationId);
        if (updated == 0) return Optional.empty();
        return listQualityIssues(organizationId, importJobId).stream()
                .filter(issue -> issue.id().equals(issueId)).findFirst();
    }

    private void replaceSuggestions(
            UUID importJobId,
            UUID recognitionRunId,
            String source,
            com.jsd.aird.tpl.application.port.RecognitionModelClient.RecognitionBatch batch
    ) {
        jdbcTemplate.update(
                "DELETE FROM tpl.recognition_suggestion WHERE recognition_run_id = ? AND source = ?",
                recognitionRunId,
                source
        );
        persistSuggestions(importJobId, recognitionRunId, source, batch);
    }

    private void persistSuggestions(
            UUID importJobId,
            UUID recognitionRunId,
            String source,
            com.jsd.aird.tpl.application.port.RecognitionModelClient.RecognitionBatch batch
    ) {
        var persisted = new ArrayList<PersistedSuggestion>();
        var identities = new LinkedHashMap<String, UUID>();
        var fingerprints = new HashSet<String>();
        for (var suggestion : batch.suggestions()) {
            // Keep formula expressions in workbook facts/audit, not in the
            // customer-confirmable suggestion stream. A formula may provide a
            // cached derived value, but "=IF(...)" is never a field name.
            if (isFormulaExpressionCandidate(suggestion.payload())) continue;
            var id = UUID.randomUUID();
            var relationId = blankToNull(suggestion.payload().path("relationId").asText(""));
            if (relationId != null) identities.putIfAbsent(relationId, id);
            var fingerprint = suggestionFingerprint(source, suggestion.payload());
            if (!fingerprints.add(fingerprint)) continue;
            persisted.add(new PersistedSuggestion(id, suggestion, relationId));
        }
        for (var entry : persisted) {
            var suggestion = entry.suggestion();
            var payload = suggestion.payload();
            var parentRelationId = blankToNull(payload.path("parentRelationId").asText(""));
            var explicitParentSuggestionId = safeUuid(payload.path("parentSuggestionId").asText(""));
            var fieldId = safeUuid(payload.path("fieldId").asText(""));
            var fingerprint = suggestionFingerprint(source, payload);
            var filterReasonCode = filterReasonCode(payload);
            jdbcTemplate.update("""
                            INSERT INTO tpl.recognition_suggestion (
                                id, import_job_id, source, suggestion_type, payload_jsonb,
                                confidence, evidence_jsonb, decision, provider, model,
                                prompt_version, request_hash, response_hash,
                                recognition_run_id, recognition_call_id, region_id, relation_id, block_id,
                                parent_suggestion_id, field_id, semantic_fingerprint, suggestion_level,
                                filter_stage, filter_reason_code, filter_detail
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN 'ACCEPTED' ELSE 'PENDING' END, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    entry.id(),
                    importJobId,
                    source,
                    suggestion.suggestionType(), pgJson(payload), suggestion.confidence(), pgJson(suggestion.evidence()),
                    payload.path("autoAccept").asBoolean(false),
                    batch.provider(),
                    batch.model(),
                    batch.promptVersion(),
                    batch.requestHash(),
                    batch.responseHash(),
                    recognitionRunId,
                    payload.path("recognitionCallId").asText("").isBlank()
                            ? batch.callTrace() == null ? null : batch.callTrace().callId()
                            : UUID.fromString(payload.path("recognitionCallId").asText()),
                    payload.path("regionId").asText(
                            batch.callTrace() == null ? "" : batch.callTrace().regionId()
                    ),
                    entry.relationId(), blankToNull(payload.path("blockId").asText("")),
                    explicitParentSuggestionId != null
                            ? explicitParentSuggestionId
                            : parentRelationId == null ? null : identities.get(parentRelationId),
                    fieldId, fingerprint,
                    payload.path("suggestionLevel").asText(
                            "CHILD".equals(suggestion.suggestionType()) ? "CHILD" : "ROOT"),
                    "FORMAL_MAPPING_COMPILE", filterReasonCode, filterDetail(filterReasonCode)
            );
        }
    }

    private String suggestionFingerprint(String source, JsonNode payload) {
        return RecognitionIdentity.shortHash(
                source + "|" + payload.path("relationId").asText("") + "|"
                        + payload.path("dataPath").asText("") + "|"
                        + payload.path("semanticRecompileRegionId").asText("") + "|"
                        + payload.path("locator").path("sheetId").asText("") + "|"
                        + payload.path("locator").path("address").asText(
                        payload.path("locator").path("range").asText("")), 64);
    }

    private UUID safeUuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String filterReasonCode(JsonNode payload) {
        if (payload.path("semanticConflict").asBoolean(false)) return "SEMANTIC_CONFLICT";
        if (payload.path("requiresStandardConfirmation").asBoolean(false)) return "STANDARD_FIELD_UNMATCHED";
        if ("UNKNOWN".equals(payload.path("editability").asText("UNKNOWN"))) return "EDITABILITY_UNKNOWN";
        if ("UNKNOWN".equals(payload.path("valueSource").asText("UNKNOWN"))) return "VALUE_SOURCE_UNKNOWN";
        if ("CHILD".equals(payload.path("suggestionLevel").asText(""))
                && payload.path("parentRelationId").asText("").isBlank()) return "CHILD_PARENT_MISSING";
        return "PENDING_NOT_CONFIRMED";
    }

    private boolean isFormulaExpressionCandidate(JsonNode payload) {
        if (payload == null) return false;
        if (payload.path("fieldName").asText("").strip().startsWith("=")) return true;
        if (payload.path("label").asText("").strip().startsWith("=")) return true;
        return "FORMULA".equals(payload.path("valueSource").asText(""))
                && payload.path("labelPath").asText("").strip().isBlank();
    }

    private String filterDetail(String reasonCode) {
        return switch (reasonCode) {
            case "SEMANTIC_CONFLICT" -> "字段含义或单位存在冲突";
            case "STANDARD_FIELD_UNMATCHED" -> "未匹配标准字段，需人工确认";
            case "EDITABILITY_UNKNOWN" -> "填写权限未明确";
            case "VALUE_SOURCE_UNKNOWN" -> "数据来源未明确";
            case "CHILD_PARENT_MISSING" -> "明细字段缺少父级";
            default -> "识别建议尚未确认";
        };
    }

    private record PersistedSuggestion(
            UUID id,
            com.jsd.aird.tpl.application.port.RecognitionModelClient.ModelSuggestion suggestion,
            String relationId
    ) {
    }

    @Override
    public List<RecognitionSuggestionView> listSuggestions(UUID organizationId, UUID importJobId) {
        return jdbcTemplate.query("""
                        SELECT rs.id, rs.import_job_id, rs.recognition_run_id, rs.source, rs.suggestion_type,
                               rs.payload_jsonb, rs.confidence, rs.evidence_jsonb,
                               rs.decision, rs.provider, rs.model, rs.prompt_version,
                               rs.filter_reason_code, rs.filter_detail, rs.created_at
                        FROM tpl.recognition_suggestion rs
                        JOIN tpl.template_import_job tij ON tij.id = rs.import_job_id
                        WHERE rs.recognition_run_id = (
                            SELECT rr.id FROM tpl.recognition_run rr
                            WHERE rr.import_job_id = tij.id
                            ORDER BY rr.created_at DESC, rr.id DESC LIMIT 1
                        )
                          AND tij.id = ? AND tij.organization_id = ?
                        ORDER BY rs.confidence DESC NULLS LAST, rs.created_at, rs.id
                        """,
                (rs, rowNum) -> mapSuggestion(rs),
                importJobId,
                organizationId
        );
    }

    private List<RecognitionSuggestionView> listSuggestionsByRun(UUID organizationId, UUID recognitionRunId) {
        return jdbcTemplate.query("""
                        SELECT rs.id, rs.import_job_id, rs.recognition_run_id, rs.source, rs.suggestion_type,
                               rs.payload_jsonb, rs.confidence, rs.evidence_jsonb,
                               rs.decision, rs.provider, rs.model, rs.prompt_version,
                               rs.filter_reason_code, rs.filter_detail, rs.created_at
                        FROM tpl.recognition_suggestion rs
                        JOIN tpl.template_import_job tij ON tij.id = rs.import_job_id
                        WHERE rs.recognition_run_id = ? AND tij.organization_id = ?
                        ORDER BY rs.confidence DESC NULLS LAST, rs.created_at, rs.id
                        """,
                (rs, rowNum) -> mapSuggestion(rs),
                recognitionRunId,
                organizationId
        );
    }

    @Override
    public List<RecognitionCallView> listRecognitionCalls(UUID organizationId, UUID importJobId) {
        return jdbcTemplate.query("""
                        SELECT rc.id, rc.recognition_run_id, rc.region_id, rc.attempt, rc.provider, rc.model, rc.prompt_version,
                               rc.status, rc.http_status, rc.started_at, rc.finished_at, rc.duration_ms,
                               rc.prompt_tokens, rc.completion_tokens, rc.total_tokens,
                               rc.request_payload_gzip, rc.response_payload_gzip, rc.error_type,
                               rc.error_message, rc.finish_reason, rc.outcome_code, rc.response_truncated,
                               rc.phase, rc.parent_call_id
                        FROM tpl.recognition_call rc
                        JOIN tpl.recognition_run rr ON rr.id = rc.recognition_run_id
                        JOIN tpl.template_import_job tij ON tij.id = rr.import_job_id
                        WHERE tij.id = ? AND tij.organization_id = ?
                          AND rc.recognition_run_id = (
                              SELECT latest.id FROM tpl.recognition_run latest
                              WHERE latest.import_job_id = tij.id
                              ORDER BY latest.created_at DESC, latest.id DESC LIMIT 1
                          )
                        ORDER BY rc.started_at, rc.attempt
                        """,
                (rs, rowNum) -> {
                    var request = rs.getBytes("request_payload_gzip");
                    var response = rs.getBytes("response_payload_gzip");
                    return new RecognitionCallView(
                             rs.getObject("id", UUID.class), rs.getObject("recognition_run_id", UUID.class),
                             rs.getString("region_id"), rs.getInt("attempt"),
                            rs.getString("provider"), rs.getString("model"), rs.getString("prompt_version"),
                            rs.getString("status"), (Integer) rs.getObject("http_status"),
                            rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("finished_at").toInstant(),
                            rs.getLong("duration_ms"), rs.getInt("prompt_tokens"), rs.getInt("completion_tokens"),
                            rs.getInt("total_tokens"), auditPayload(request), auditPayload(response),
                             rs.getString("error_type"), rs.getString("error_message"),
                             rs.getString("finish_reason"), rs.getString("outcome_code"),
                             rs.getBoolean("response_truncated"), rs.getString("phase"),
                            rs.getObject("parent_call_id", UUID.class), request != null || response != null
                    );
                }, importJobId, organizationId);
    }

    @Override
    public int delete(UUID organizationId, UUID importJobId) {
        var asyncJobId = jdbcTemplate.query("""
                        SELECT async_job_id FROM tpl.template_import_job
                        WHERE id = ? AND organization_id = ?
                        """, (rs, rowNum) -> rs.getObject(1, UUID.class), importJobId, organizationId)
                .stream().findFirst().orElse(null);
        if (asyncJobId == null) return 0;
        var deleted = jdbcTemplate.update("""
                        DELETE FROM tpl.template_import_job
                        WHERE id = ? AND organization_id = ?
                        """, importJobId, organizationId);
        jdbcTemplate.update("DELETE FROM ops.async_job WHERE id = ?", asyncJobId);
        return deleted;
    }

    @Override
    public Optional<RecognitionSuggestionView> decideSuggestion(
            UUID organizationId,
            UUID recognitionRunId,
            UUID suggestionId,
            String decision,
            UUID actorId
    ) {
        var persistedDecision = "REJECTED_BY_RESOLUTION".equals(decision) ? "REJECTED" : decision;
        var updated = jdbcTemplate.update("""
                        UPDATE tpl.recognition_suggestion rs
                        SET decision = ?,
                            payload_jsonb = CASE
                                WHEN ? = 'REJECTED_BY_RESOLUTION'
                                THEN rs.payload_jsonb || jsonb_build_object(
                                    'resolutionDecision', 'REJECTED_BY_RESOLUTION')
                                ELSE rs.payload_jsonb
                            END,
                            decided_by = ?, decided_at = now()
                        FROM tpl.recognition_run rr
                        JOIN tpl.template_import_job tij ON tij.id = rr.import_job_id
                        WHERE rs.id = ? AND rs.recognition_run_id = ?
                          AND rr.id = rs.recognition_run_id AND tij.organization_id = ?
                        """,
                persistedDecision,
                decision,
                actorId,
                suggestionId,
                recognitionRunId,
                organizationId
        );
        if (updated == 0) {
            return Optional.empty();
        }
        return listSuggestionsByRun(organizationId, recognitionRunId).stream()
                .filter(suggestion -> suggestion.id().equals(suggestionId))
                .findFirst();
    }

    @Override
    public void markStructureResolved(UUID organizationId, UUID recognitionRunId, UUID suggestionId) {
        jdbcTemplate.update("""
                UPDATE tpl.recognition_suggestion rs
                SET payload_jsonb = (rs.payload_jsonb
                    || jsonb_build_object(
                        'humanResolved', true,
                        'resolutionSource', 'HUMAN_REVIEW',
                        'candidateOnly', false,
                        'physicalStructureOnly', false,
                        'reviewRequired', false,
                        'structureConflict', false,
                        'canonicalStatus', 'CONFIRMED',
                        'structureStatus', 'CONFIRMED',
                        'publishable', false,
                        'semanticRecompileStatus', 'SUCCEEDED'))
                    - 'pendingReason'
                FROM tpl.recognition_run rr
                JOIN tpl.template_import_job tij ON tij.id = rr.import_job_id
                WHERE rs.id = ? AND rs.recognition_run_id = ?
                  AND rr.id = rs.recognition_run_id AND tij.organization_id = ?
                """, suggestionId, recognitionRunId, organizationId);
    }

    @Override
    public int acceptSuggestionsAboveConfidence(
            UUID organizationId,
            UUID importJobId,
            double confidence,
            UUID actorId
    ) {
        return jdbcTemplate.update("""
                        UPDATE tpl.recognition_suggestion rs
                        SET decision = 'ACCEPTED', decided_by = ?, decided_at = now()
                        FROM tpl.template_import_job tij
                        WHERE rs.import_job_id = ?
                          AND rs.decision = 'PENDING'
                          AND rs.confidence >= ?
                          AND coalesce((rs.payload_jsonb ->> 'semanticConflict')::boolean, false) = false
                          AND coalesce((rs.payload_jsonb ->> 'reviewRequired')::boolean, false) = false
                          AND coalesce((rs.payload_jsonb ->> 'physicalStructureOnly')::boolean, false) = false
                          AND coalesce((rs.payload_jsonb ->> 'candidateOnly')::boolean, false) = false
                          AND coalesce(rs.payload_jsonb ->> 'protocolRecovery', '') NOT IN ('RETAINED_REJECTED_CANDIDATE', 'PROTOCOL_REVIEW_REQUIRED')
                          AND tij.id = rs.import_job_id
                          AND rs.recognition_run_id = (
                              SELECT latest.id FROM tpl.recognition_run latest
                              WHERE latest.import_job_id = rs.import_job_id
                              ORDER BY latest.created_at DESC, latest.id DESC LIMIT 1
                          )
                          AND tij.organization_id = ?
                        """,
                actorId,
                importJobId,
                confidence,
                organizationId
        );
    }

    @Override
    public void linkGeneratedVersion(UUID organizationId, UUID importJobId, UUID versionId) {
        jdbcTemplate.update("""
                        UPDATE tpl.template_import_job
                        SET generated_template_version_id = ?, updated_at = now()
                        WHERE id = ? AND organization_id = ?
                        """,
                versionId,
                importJobId,
                organizationId
        );
    }

    private RecognitionSuggestionView mapSuggestion(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RecognitionSuggestionView(
                rs.getObject("id", UUID.class),
                rs.getObject("import_job_id", UUID.class),
                rs.getObject("recognition_run_id", UUID.class),
                rs.getString("source"),
                rs.getString("suggestion_type"),
                parse(rs.getString("payload_jsonb")),
                rs.getDouble("confidence"),
                parse(rs.getString("evidence_jsonb")),
                rs.getString("decision"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getString("filter_reason_code"),
                rs.getString("filter_detail"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private QualityIssueView mapQualityIssue(java.sql.ResultSet rs) throws java.sql.SQLException {
        var evidence = parse(rs.getString("evidence_jsonb"));
        var suggestedPatch = parse(rs.getString("suggested_patch_jsonb"));
        var inversePatch = parse(rs.getString("inverse_patch_jsonb"));
        if (!evidence.isArray()) evidence = objectMapper.createArrayNode();
        if (!suggestedPatch.isObject()) suggestedPatch = objectMapper.createObjectNode();
        if (!inversePatch.isObject()) inversePatch = objectMapper.createObjectNode();
        return new QualityIssueView(
                rs.getObject("id", UUID.class), rs.getObject("import_job_id", UUID.class),
                rs.getObject("recognition_run_id", UUID.class),
                rs.getObject("recognition_call_id", UUID.class), rs.getString("region_id"),
                rs.getString("issue_type"), rs.getString("severity"), rs.getDouble("confidence"),
                rs.getString("sheet_id"), rs.getString("sheet_name"), rs.getString("address"),
                rs.getString("title"), rs.getString("description"), rs.getString("business_impact"),
                evidence, suggestedPatch, inversePatch, rs.getBoolean("auto_fixable"),
                rs.getString("status"), rs.getString("before_snapshot_hash"),
                rs.getString("after_snapshot_hash"), rs.getTimestamp("created_at").toInstant()
        );
    }

    private List<IssueView> loadIssues(UUID importJobId) {
        return jdbcTemplate.query("""
                        SELECT severity, issue_code, message, location_jsonb, resolution
                        FROM tpl.template_import_issue
                        WHERE import_job_id = ?
                        ORDER BY
                            CASE severity WHEN 'BLOCKER' THEN 1 WHEN 'WARNING' THEN 2 ELSE 3 END,
                            created_at
                        """,
                (rs, rowNum) -> new IssueView(
                        rs.getString("severity"),
                        rs.getString("issue_code"),
                        rs.getString("message"),
                        parse(rs.getString("location_jsonb")),
                        rs.getString("resolution")
                ),
                importJobId
        );
    }

    private PGobject pgJson(JsonNode value) {
        try {
            var result = new PGobject();
            result.setType("jsonb");
            result.setValue(objectMapper.writeValueAsString(value));
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to serialize JSONB", exception);
        }
    }

    private JsonNode parse(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to parse JSONB", exception);
        }
    }

    private JsonNode auditPayload(byte[] value) {
        return value == null ? objectMapper.createObjectNode() : auditPayloadCodec.decompress(value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
