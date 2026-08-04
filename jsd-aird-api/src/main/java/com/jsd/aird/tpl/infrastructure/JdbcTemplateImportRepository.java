package com.jsd.aird.tpl.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import com.jsd.aird.tpl.application.port.TemplateImportRepository;
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
                            progress, async_job_id, created_by
                        ) VALUES (?, ?, ?, ?, 'QUEUED', 0, ?, ?)
                        """,
                job.importJobId(),
                job.organizationId(),
                job.fileId(),
                job.format().name(),
                job.asyncJobId(),
                job.actorId()
        );
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
    public Optional<UUID> findGeneratedVersionId(UUID importJobId) {
        return jdbcTemplate.query("""
                        SELECT generated_template_version_id
                        FROM tpl.template_import_job
                        WHERE id = ? AND generated_template_version_id IS NOT NULL
                        """, (rs, rowNum) -> rs.getObject(1, UUID.class), importJobId)
                .stream().findFirst();
    }

    @Override
    public List<ImportJobView> list(UUID organizationId) {
        return queryJobs("""
                        WHERE tij.organization_id = ?
                        ORDER BY tij.created_at DESC
                        LIMIT 100
                        """, organizationId);
    }

    private List<ImportJobView> queryJobs(String suffix, Object... arguments) {
        return jdbcTemplate.query("""
                        SELECT tij.id, tij.source_file_id, fo.original_name AS source_file_name,
                               tij.format,
                               CASE
                                   WHEN aj.status = 'SUCCEEDED' THEN 'PARSED'
                                   WHEN aj.status = 'FAILED' THEN 'FAILED'
                                   ELSE aj.status
                               END AS status,
                               aj.progress, aj.current_stage, tij.structure_summary_jsonb,
                               aj.result_jsonb, aj.last_error, tij.created_at,
                               (SELECT count(*)::int FROM tpl.recognition_suggestion rs
                                WHERE rs.import_job_id = tij.id) AS suggestion_count,
                               (SELECT count(*)::int FROM tpl.recognition_suggestion rs
                                WHERE rs.import_job_id = tij.id AND rs.decision = 'PENDING') AS pending_suggestion_count
                        FROM tpl.template_import_job tij
                        JOIN ops.async_job aj ON aj.id = tij.async_job_id
                        JOIN ops.file_object fo ON fo.id = tij.source_file_id
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
                        rs.getString("last_error"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("suggestion_count"),
                        rs.getInt("pending_suggestion_count"),
                        loadIssues(rs.getObject("id", UUID.class))
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
                    issue.severity(),
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
            UUID importJobId, String scope, int structureVersion, int snapshotFormatVersion, int regionCount
    ) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO tpl.recognition_run (
                            id, import_job_id, scope, status, structure_version, snapshot_format_version, region_count
                        ) VALUES (?, ?, ?, 'RUNNING', ?, ?, ?)
                        """, id, importJobId, scope, structureVersion, snapshotFormatVersion, regionCount);
        return id;
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
                            error_type, error_message, phase, parent_call_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                trace.callId(), recognitionRunId, trace.regionId(), trace.attempt(), trace.provider(),
                trace.model(), trace.promptVersion(), trace.status(), trace.httpStatus(),
                java.sql.Timestamp.from(trace.startedAt()), java.sql.Timestamp.from(trace.finishedAt()),
                trace.durationMs(), trace.promptTokens(), trace.completionTokens(), trace.totalTokens(),
                auditPayloadCodec.compress(trace.requestPayload()), auditPayloadCodec.compress(trace.responsePayload()), trace.requestHash(),
                trace.responseHash(), blankToNull(trace.errorType()), blankToNull(trace.errorMessage()),
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
    public void replaceRuleSuggestions(
            UUID importJobId,
            UUID recognitionRunId,
            com.jsd.aird.tpl.application.port.RecognitionModelClient.RecognitionBatch batch
    ) {
        replaceSuggestions(importJobId, recognitionRunId, "RULE", batch);
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
                    blankToNull(issue.regionId()), issue.issueType(), issue.severity(), issue.confidence(),
                    blankToNull(issue.sheetId()), blankToNull(issue.sheetName()), issue.address(),
                    issue.title(), issue.description(), issue.businessImpact(), pgJson(issue.evidence()),
                    pgJson(issue.suggestedPatch()), pgJson(issue.inversePatch()), issue.autoFixable(),
                    issue.status(), beforeSnapshotHash, afterSnapshotHash,
                    blankToNull(issue.regionId()), issue.issueType()
            );
        }
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
                "DELETE FROM tpl.recognition_suggestion WHERE import_job_id = ? AND source = ?",
                importJobId,
                source
        );
        for (var suggestion : batch.suggestions()) {
            jdbcTemplate.update("""
                            INSERT INTO tpl.recognition_suggestion (
                                id, import_job_id, source, suggestion_type, payload_jsonb,
                                confidence, evidence_jsonb, decision, provider, model,
                                prompt_version, request_hash, response_hash,
                                recognition_run_id, recognition_call_id, region_id, relation_id, block_id
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    UUID.randomUUID(),
                    importJobId,
                    source,
                    suggestion.suggestionType(),
                    pgJson(suggestion.payload()),
                    suggestion.confidence(),
                    pgJson(suggestion.evidence()),
                    batch.provider(),
                    batch.model(),
                    batch.promptVersion(),
                    batch.requestHash(),
                    batch.responseHash(),
                    recognitionRunId,
                    suggestion.payload().path("recognitionCallId").asText("").isBlank()
                            ? batch.callTrace() == null ? null : batch.callTrace().callId()
                            : UUID.fromString(suggestion.payload().path("recognitionCallId").asText()),
                    suggestion.payload().path("regionId").asText(
                            batch.callTrace() == null ? "" : batch.callTrace().regionId()
                    ),
                    blankToNull(suggestion.payload().path("relationId").asText("")),
                    blankToNull(suggestion.payload().path("blockId").asText(""))
            );
        }
    }

    @Override
    public List<RecognitionSuggestionView> listSuggestions(UUID organizationId, UUID importJobId) {
        return jdbcTemplate.query("""
                        SELECT rs.id, rs.import_job_id, rs.source, rs.suggestion_type,
                               rs.payload_jsonb, rs.confidence, rs.evidence_jsonb,
                               rs.decision, rs.provider, rs.model, rs.prompt_version, rs.created_at
                        FROM tpl.recognition_suggestion rs
                        JOIN tpl.template_import_job tij ON tij.id = rs.import_job_id
                        WHERE rs.import_job_id = ? AND tij.organization_id = ?
                        ORDER BY rs.confidence DESC NULLS LAST, rs.created_at, rs.id
                        """,
                (rs, rowNum) -> mapSuggestion(rs),
                importJobId,
                organizationId
        );
    }

    @Override
    public Optional<RecognitionSuggestionView> decideSuggestion(
            UUID organizationId,
            UUID importJobId,
            UUID suggestionId,
            String decision,
            UUID actorId
    ) {
        var updated = jdbcTemplate.update("""
                        UPDATE tpl.recognition_suggestion rs
                        SET decision = ?, decided_by = ?, decided_at = now()
                        FROM tpl.template_import_job tij
                        WHERE rs.id = ? AND rs.import_job_id = ?
                          AND tij.id = rs.import_job_id AND tij.organization_id = ?
                        """,
                decision,
                actorId,
                suggestionId,
                importJobId,
                organizationId
        );
        if (updated == 0) {
            return Optional.empty();
        }
        return listSuggestions(organizationId, importJobId).stream()
                .filter(suggestion -> suggestion.id().equals(suggestionId))
                .findFirst();
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
                          AND tij.id = rs.import_job_id
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
                rs.getString("source"),
                rs.getString("suggestion_type"),
                parse(rs.getString("payload_jsonb")),
                rs.getDouble("confidence"),
                parse(rs.getString("evidence_jsonb")),
                rs.getString("decision"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private QualityIssueView mapQualityIssue(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new QualityIssueView(
                rs.getObject("id", UUID.class), rs.getObject("import_job_id", UUID.class),
                rs.getObject("recognition_run_id", UUID.class),
                rs.getObject("recognition_call_id", UUID.class), rs.getString("region_id"),
                rs.getString("issue_type"), rs.getString("severity"), rs.getDouble("confidence"),
                rs.getString("sheet_id"), rs.getString("sheet_name"), rs.getString("address"),
                rs.getString("title"), rs.getString("description"), rs.getString("business_impact"),
                parse(rs.getString("evidence_jsonb")), parse(rs.getString("suggested_patch_jsonb")),
                parse(rs.getString("inverse_patch_jsonb")), rs.getBoolean("auto_fixable"),
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
