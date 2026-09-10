package com.jsd.aird.ai.formula.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.formula.application.port.ResearchRepository;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcResearchRepository implements ResearchRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcResearchRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public CreateResult createOrFind(NewRun run) {
        var inserted = jdbc.update("""
                INSERT INTO ai.research_run (
                    id, organization_id, run_type, mode, status, task_profile_code,
                    idempotency_key, request_hash, request_jsonb, created_by, created_by_name
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, idempotency_key) DO NOTHING
                """, run.id(), run.organizationId(), run.runType(), run.mode(), run.status(),
                run.taskProfileCode(), run.idempotencyKey(), run.requestHash(), pg(run.request()),
                run.createdBy(), run.createdByName()) == 1;
        var row = findByIdempotencyKey(run.organizationId(), run.idempotencyKey()).orElseThrow();
        return new CreateResult(inserted, row);
    }

    @Override
    public Optional<RunRow> findRun(UUID organizationId, UUID runId) {
        return jdbc.query("""
                SELECT id, organization_id, run_type, mode, status, task_profile_code,
                       analysis_profile_version, idempotency_key, request_hash, request_jsonb,
                       result_jsonb, error_code, error_message, created_by, created_by_name,
                       created_at, started_at, finished_at
                FROM ai.research_run
                WHERE organization_id = ? AND id = ?
                """, (rs, ignored) -> run(rs), organizationId, runId).stream().findFirst();
    }

    private Optional<RunRow> findByIdempotencyKey(UUID organizationId, String key) {
        return jdbc.query("""
                SELECT id, organization_id, run_type, mode, status, task_profile_code,
                       analysis_profile_version, idempotency_key, request_hash, request_jsonb,
                       result_jsonb, error_code, error_message, created_by, created_by_name,
                       created_at, started_at, finished_at
                FROM ai.research_run
                WHERE organization_id = ? AND idempotency_key = ?
                """, (rs, ignored) -> run(rs), organizationId, key).stream().findFirst();
    }

    @Override
    @Transactional
    public boolean markRunning(UUID organizationId, UUID runId) {
        return jdbc.update("""
                UPDATE ai.research_run
                SET status = 'RUNNING', started_at = COALESCE(started_at, now()), updated_at = now()
                WHERE organization_id = ? AND id = ? AND status = 'QUEUED'
                """, organizationId, runId) == 1;
    }

    @Override
    @Transactional
    public void complete(UUID organizationId, UUID runId, String mode, String status, String analysisProfileVersion,
                         JsonNode result, List<NewCandidate> candidates) {
        jdbc.update("DELETE FROM ai.research_candidate WHERE organization_id = ? AND research_run_id = ?",
                organizationId, runId);
        for (var item : candidates) {
            jdbc.update("""
                    INSERT INTO ai.research_candidate (
                        id, organization_id, research_run_id, candidate_no, strategy, title,
                        formula_jsonb, process_jsonb, model_context_jsonb, estimates_jsonb, rule_check_jsonb,
                        evidence_jsonb, confidence, score, content_hash
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, item.id(), organizationId, runId, item.candidateNo(), item.strategy(), item.title(),
                    pg(item.formula()), pg(item.process()), pg(item.modelContext()), pg(item.estimates()), pg(item.ruleCheck()),
                    pg(item.evidence()), item.confidence(), item.score(), item.contentHash());
        }
        jdbc.update("""
                UPDATE ai.research_run
                SET mode = ?, status = ?, analysis_profile_version = ?, result_jsonb = ?, error_code = NULL,
                    error_message = NULL, finished_at = now(), updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, mode, status, analysisProfileVersion, pg(result), organizationId, runId);
    }

    @Override
    @Transactional
    public void fail(UUID organizationId, UUID runId, String errorCode, String errorMessage) {
        jdbc.update("""
                UPDATE ai.research_run
                SET status = 'FAILED', error_code = ?, error_message = ?, finished_at = now(), updated_at = now()
                WHERE organization_id = ? AND id = ? AND status IN ('QUEUED', 'RUNNING')
                """, errorCode, truncate(errorMessage, 4000), organizationId, runId);
    }

    @Override
    public List<CandidateRow> candidates(UUID organizationId, UUID runId) {
        return jdbc.query("""
                SELECT id, organization_id, research_run_id, candidate_no, strategy, title,
                       formula_jsonb, process_jsonb, model_context_jsonb, estimates_jsonb, rule_check_jsonb,
                       evidence_jsonb, confidence, score, content_hash
                FROM ai.research_candidate
                WHERE organization_id = ? AND research_run_id = ?
                ORDER BY candidate_no
                """, (rs, ignored) -> candidate(rs), organizationId, runId);
    }

    @Override
    public Optional<CandidateRow> candidate(UUID organizationId, UUID runId, UUID candidateId) {
        return jdbc.query("""
                SELECT id, organization_id, research_run_id, candidate_no, strategy, title,
                       formula_jsonb, process_jsonb, model_context_jsonb, estimates_jsonb, rule_check_jsonb,
                       evidence_jsonb, confidence, score, content_hash
                FROM ai.research_candidate
                WHERE organization_id = ? AND research_run_id = ? AND id = ?
                """, (rs, ignored) -> candidate(rs), organizationId, runId, candidateId).stream().findFirst();
    }

    @Override
    public void lockCandidate(UUID organizationId, UUID runId, UUID candidateId) {
        jdbc.queryForObject("""
                SELECT id FROM ai.research_candidate
                WHERE organization_id = ? AND research_run_id = ? AND id = ?
                FOR UPDATE
                """, UUID.class, organizationId, runId, candidateId);
    }

    @Override
    public Optional<ExperimentLinkRow> experimentLink(UUID organizationId, UUID candidateId) {
        return jdbc.query("""
                SELECT id, research_run_id, research_candidate_id, experiment_id,
                       experiment_version_id, experiment_no, idempotency_key
                FROM ai.research_experiment_link
                WHERE organization_id = ? AND research_candidate_id = ?
                """, (rs, ignored) -> link(rs), organizationId, candidateId).stream().findFirst();
    }

    @Override
    @Transactional
    public ExperimentLinkRow saveExperimentLink(NewExperimentLink link) {
        jdbc.update("""
                INSERT INTO ai.research_experiment_link (
                    id, organization_id, research_run_id, research_candidate_id, experiment_id,
                    experiment_version_id, experiment_no, idempotency_key, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, research_candidate_id) DO NOTHING
                """, link.id(), link.organizationId(), link.runId(), link.candidateId(), link.experimentId(),
                link.experimentVersionId(), link.experimentNo(), link.idempotencyKey(), link.createdBy());
        return experimentLink(link.organizationId(), link.candidateId()).orElseThrow();
    }

    private RunRow run(ResultSet rs) throws SQLException {
        return new RunRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("run_type"), rs.getString("mode"), rs.getString("status"),
                rs.getString("task_profile_code"), rs.getString("analysis_profile_version"),
                rs.getString("idempotency_key"), rs.getString("request_hash"), read(rs.getString("request_jsonb")),
                read(rs.getString("result_jsonb")), rs.getString("error_code"), rs.getString("error_message"),
                rs.getObject("created_by", UUID.class), rs.getString("created_by_name"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")));
    }

    private CandidateRow candidate(ResultSet rs) throws SQLException {
        return new CandidateRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("research_run_id", UUID.class), rs.getInt("candidate_no"), rs.getString("strategy"),
                rs.getString("title"), read(rs.getString("formula_jsonb")), read(rs.getString("process_jsonb")),
                read(rs.getString("model_context_jsonb")), read(rs.getString("estimates_jsonb")), read(rs.getString("rule_check_jsonb")),
                read(rs.getString("evidence_jsonb")), rs.getString("confidence"), rs.getBigDecimal("score"),
                rs.getString("content_hash"));
    }

    private ExperimentLinkRow link(ResultSet rs) throws SQLException {
        return new ExperimentLinkRow(rs.getObject("id", UUID.class), rs.getObject("research_run_id", UUID.class),
                rs.getObject("research_candidate_id", UUID.class), rs.getObject("experiment_id", UUID.class),
                rs.getObject("experiment_version_id", UUID.class), rs.getString("experiment_no"),
                rs.getString("idempotency_key"));
    }

    private PGobject pg(JsonNode value) {
        try {
            var object = new PGobject();
            object.setType("jsonb");
            object.setValue(value == null ? "{}" : value.toString());
            return object;
        } catch (SQLException exception) {
            throw new IllegalArgumentException("研究运行JSON无法保存", exception);
        }
    }

    private JsonNode read(String value) {
        try { return value == null ? json.createObjectNode() : json.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException("研究运行JSON无法读取", exception); }
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
