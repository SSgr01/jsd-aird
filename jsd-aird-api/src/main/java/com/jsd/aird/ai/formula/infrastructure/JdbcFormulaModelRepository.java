package com.jsd.aird.ai.formula.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.formula.application.port.FormulaModelRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.postgresql.util.PGobject;
import org.springframework.dao.DuplicateKeyException;
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
public class JdbcFormulaModelRepository implements FormulaModelRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcFormulaModelRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public TaskProfileRow ensureProfile(NewTaskProfile profile) {
        var existing = findProfile(profile.organizationId(), profile.code(), profile.version());
        if (existing.isPresent()) {
            if (!existing.get().contentHash().equals(profile.contentHash())) {
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "相同版本的模型任务档案内容不一致");
            }
            return existing.get();
        }
        try {
            jdbc.update("""
                    INSERT INTO ai.formulation_task_profile (
                        id, organization_id, profile_code, profile_version, status,
                        contract_version, schema_hash, content_hash, profile_jsonb, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, profile.id(), profile.organizationId(), profile.code(), profile.version(), profile.status(),
                    profile.contractVersion(), profile.schemaHash(), profile.contentHash(), pg(profile.profile()),
                    profile.createdBy());
        } catch (DuplicateKeyException ignored) {
            // A concurrent build registered the same immutable profile.
        }
        return findProfile(profile.organizationId(), profile.code(), profile.version()).orElseThrow();
    }

    @Override
    @Transactional
    public void createBuild(NewSnapshot snapshot, NewModelVersion version) {
        jdbc.update("""
                INSERT INTO ai.formula_model_snapshot (
                    id, organization_id, task_profile_id, status, schema_version,
                    data_nature, snapshot_purpose, object_prefix, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshot.id(), snapshot.organizationId(), snapshot.taskProfileId(), snapshot.status(),
                snapshot.schemaVersion(), snapshot.dataNature(), snapshot.purpose(), snapshot.objectPrefix(),
                snapshot.createdBy());
        jdbc.update("""
                INSERT INTO ai.formula_model_version (
                    id, organization_id, task_profile_id, snapshot_id, status, created_by
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, version.id(), version.organizationId(), version.taskProfileId(), version.snapshotId(),
                version.status(), version.createdBy());
    }

    @Override
    public Optional<SnapshotRow> snapshot(UUID organizationId, UUID snapshotId) {
        return jdbc.query("""
                SELECT id, organization_id, task_profile_id, status, schema_version, data_nature,
                       snapshot_purpose, snapshot_hash, validation_folds_hash, t06_baseline_hash,
                       object_prefix, artifacts_jsonb, target_summary_jsonb, row_count,
                       error_code, error_message, created_at, completed_at
                FROM ai.formula_model_snapshot WHERE organization_id = ? AND id = ?
                """, (rs, ignored) -> snapshot(rs), organizationId, snapshotId).stream().findFirst();
    }

    @Override
    public Optional<ModelVersionRow> modelVersion(UUID organizationId, UUID versionId) {
        return jdbc.query("""
                SELECT id, organization_id, task_profile_id, snapshot_id, status, model_bundle_hash,
                       model_bundle_key, model_card_jsonb, training_result_jsonb, error_code,
                       error_message, created_at, completed_at
                FROM ai.formula_model_version WHERE organization_id = ? AND id = ?
                """, (rs, ignored) -> modelVersion(rs), organizationId, versionId).stream().findFirst();
    }

    @Override
    @Transactional
    public void completeSnapshot(UUID organizationId, UUID snapshotId, String snapshotHash,
                                 String foldsHash, String baselineHash, int rowCount,
                                 JsonNode artifacts, JsonNode targetSummary) {
        var count = jdbc.update("""
                UPDATE ai.formula_model_snapshot
                SET status = 'READY', snapshot_hash = ?, validation_folds_hash = ?,
                    t06_baseline_hash = ?, row_count = ?, artifacts_jsonb = ?,
                    target_summary_jsonb = ?, completed_at = now(), error_code = NULL, error_message = NULL
                WHERE organization_id = ? AND id = ? AND status = 'BUILDING'
                """, snapshotHash, foldsHash, baselineHash, rowCount, pg(artifacts), pg(targetSummary),
                organizationId, snapshotId);
        if (count != 1) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "模型快照状态已变化");
    }

    @Override
    @Transactional
    public void completeModel(UUID organizationId, UUID versionId, String bundleHash,
                              String bundleKey, JsonNode modelCard, JsonNode trainingResult,
                              List<NewModelTarget> targets) {
        for (var target : targets) {
            jdbc.update("""
                    INSERT INTO ai.formula_model_target (
                        model_version_id, organization_id, target_key, target_code, value_type,
                        status, production_eligible, scorer_type, primary_metric_name,
                        primary_metric_value, baseline_metric_value, baseline_improvement,
                        reasons_jsonb, result_jsonb
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, versionId, organizationId, target.targetKey(), target.targetCode(), target.valueType(),
                    target.status(), target.productionEligible(), target.scorerType(), target.primaryMetricName(),
                    target.primaryMetricValue(), target.baselineMetricValue(), target.baselineImprovement(),
                    pg(target.reasons()), pg(target.result()));
        }
        var count = jdbc.update("""
                UPDATE ai.formula_model_version
                SET status = 'CANDIDATE', model_bundle_hash = ?, model_bundle_key = ?,
                    model_card_jsonb = ?, training_result_jsonb = ?, completed_at = now(),
                    error_code = NULL, error_message = NULL
                WHERE organization_id = ? AND id = ? AND status = 'BUILDING'
                """, bundleHash, bundleKey, pg(modelCard), pg(trainingResult), organizationId, versionId);
        if (count != 1) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "模型版本状态已变化");
    }

    @Override
    @Transactional
    public void failBuild(UUID organizationId, UUID snapshotId, UUID versionId,
                          String errorCode, String errorMessage) {
        jdbc.update("""
                UPDATE ai.formula_model_snapshot SET status = 'FAILED', error_code = ?, error_message = ?,
                    completed_at = now() WHERE organization_id = ? AND id = ? AND status = 'BUILDING'
                """, errorCode, truncate(errorMessage), organizationId, snapshotId);
        jdbc.update("""
                UPDATE ai.formula_model_version SET status = 'FAILED', error_code = ?, error_message = ?,
                    completed_at = now() WHERE organization_id = ? AND id = ? AND status = 'BUILDING'
                """, errorCode, truncate(errorMessage), organizationId, versionId);
    }

    @Override
    public List<ModelTargetRow> modelTargets(UUID organizationId, UUID versionId) {
        return jdbc.query("""
                SELECT model_version_id, target_key, target_code, value_type, status,
                       production_eligible, scorer_type, primary_metric_name, primary_metric_value,
                       baseline_metric_value, baseline_improvement, reasons_jsonb, result_jsonb
                FROM ai.formula_model_target
                WHERE organization_id = ? AND model_version_id = ? ORDER BY target_key
                """, (rs, ignored) -> target(rs), organizationId, versionId);
    }

    @Override
    public Optional<ActivationRow> activeTarget(UUID organizationId, UUID taskProfileId, String targetKey) {
        return jdbc.query("""
                SELECT id, organization_id, task_profile_id, target_key, model_version_id,
                       previous_model_version_id, status, activation_reason, activated_by,
                       activated_at, ended_at, consecutive_failure_count, last_failure_at
                FROM ai.formula_model_activation
                WHERE organization_id = ? AND task_profile_id = ? AND target_key = ? AND status = 'ACTIVE'
                """, (rs, ignored) -> activation(rs), organizationId, taskProfileId, targetKey)
                .stream().findFirst();
    }

    @Override
    @Transactional
    public ActivationRow activate(UUID organizationId, UUID taskProfileId, String targetKey,
                                  UUID modelVersionId, UUID actorId, String reason) {
        requireQualifiedTarget(organizationId, taskProfileId, modelVersionId, targetKey);
        var current = lockActive(organizationId, taskProfileId, targetKey);
        if (current.isPresent() && current.get().modelVersionId().equals(modelVersionId)) return current.get();
        current.ifPresent(value -> jdbc.update("""
                UPDATE ai.formula_model_activation SET status = 'REPLACED', ended_at = now()
                WHERE id = ? AND status = 'ACTIVE'
                """, value.id()));
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai.formula_model_activation (
                    id, organization_id, task_profile_id, target_key, model_version_id,
                    previous_model_version_id, status, activation_reason, activated_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, id, organizationId, taskProfileId, targetKey, modelVersionId,
                current.map(ActivationRow::modelVersionId).orElse(null), reason, actorId);
        return activeTarget(organizationId, taskProfileId, targetKey).orElseThrow();
    }

    @Override
    @Transactional
    public ActivationRow rollback(UUID organizationId, UUID taskProfileId, String targetKey,
                                  UUID actorId, String reason) {
        var current = lockActive(organizationId, taskProfileId, targetKey)
                .or(() -> lockLatestPaused(organizationId, taskProfileId, targetKey))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "该目标没有活动或待复评模型"));
        var previous = current.previousModelVersionId();
        if (previous == null) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "该目标没有可回退模型");
        requireQualifiedTarget(organizationId, taskProfileId, previous, targetKey);
        jdbc.update("""
                UPDATE ai.formula_model_activation SET status = 'ROLLED_BACK', ended_at = now()
                WHERE id = ? AND status IN ('ACTIVE', 'PAUSED')
                """, current.id());
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai.formula_model_activation (
                    id, organization_id, task_profile_id, target_key, model_version_id,
                    previous_model_version_id, status, activation_reason, activated_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, id, organizationId, taskProfileId, targetKey, previous,
                current.modelVersionId(), reason, actorId);
        return activeTarget(organizationId, taskProfileId, targetKey).orElseThrow();
    }

    @Override
    public List<ActivationRow> activeTargets(UUID organizationId, UUID taskProfileId) {
        return jdbc.query("""
                SELECT id, organization_id, task_profile_id, target_key, model_version_id,
                       previous_model_version_id, status, activation_reason, activated_by,
                       activated_at, ended_at, consecutive_failure_count, last_failure_at
                FROM ai.formula_model_activation
                WHERE organization_id = ? AND task_profile_id = ? AND status = 'ACTIVE'
                ORDER BY target_key
                """, (rs, ignored) -> activation(rs), organizationId, taskProfileId);
    }

    @Override
    public List<MonitoringScope> monitoringScopes() {
        return jdbc.query("""
                SELECT DISTINCT ON (organization_id, task_profile_id)
                       organization_id, task_profile_id, activated_by
                FROM ai.formula_model_activation
                WHERE status = 'ACTIVE' AND activated_by IS NOT NULL
                ORDER BY organization_id, task_profile_id, activated_at DESC
                """, (rs, ignored) -> new MonitoringScope(
                rs.getObject("organization_id", UUID.class),
                rs.getObject("task_profile_id", UUID.class),
                rs.getObject("activated_by", UUID.class)));
    }

    @Override
    public List<FeedbackRow> recentModelFeedback(UUID organizationId, UUID modelVersionId,
                                                 String targetKey, int limit) {
        return jdbc.query("""
                SELECT l.experiment_id, c.estimates_jsonb -> ? AS target_estimate, l.created_at
                FROM ai.research_experiment_link l
                JOIN ai.research_candidate c
                  ON c.id = l.research_candidate_id AND c.organization_id = l.organization_id
                WHERE l.organization_id = ?
                  AND c.estimates_jsonb -> ? ->> 'modelVersionId' = ?
                ORDER BY l.created_at DESC, l.id DESC
                LIMIT ?
                """, (rs, ignored) -> new FeedbackRow(
                rs.getObject("experiment_id", UUID.class), read(rs.getString("target_estimate")),
                instant(rs.getTimestamp("created_at"))),
                targetKey, organizationId, targetKey, modelVersionId.toString(), Math.max(10, limit));
    }

    @Override
    public Optional<SnapshotRow> latestReadySnapshot(UUID organizationId, UUID taskProfileId) {
        return jdbc.query("""
                SELECT id, organization_id, task_profile_id, status, schema_version, data_nature,
                       snapshot_purpose, snapshot_hash, validation_folds_hash, t06_baseline_hash,
                       object_prefix, artifacts_jsonb, target_summary_jsonb, row_count,
                       error_code, error_message, created_at, completed_at
                FROM ai.formula_model_snapshot
                WHERE organization_id = ? AND task_profile_id = ? AND status = 'READY'
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """, (rs, ignored) -> snapshot(rs), organizationId, taskProfileId).stream().findFirst();
    }

    @Override
    public void recordModelCallSuccess(UUID organizationId, UUID activationId) {
        jdbc.update("""
                UPDATE ai.formula_model_activation
                SET consecutive_failure_count = 0, last_failure_at = NULL
                WHERE organization_id = ? AND id = ? AND status = 'ACTIVE'
                """, organizationId, activationId);
    }

    @Override
    public void pauseModelForArtifactFailure(UUID organizationId, UUID activationId, String reason) {
        jdbc.update("""
                UPDATE ai.formula_model_activation
                SET consecutive_failure_count = consecutive_failure_count + 1,
                    last_failure_at = now(), status = 'PAUSED', ended_at = now(), activation_reason = ?
                WHERE organization_id = ? AND id = ? AND status = 'ACTIVE'
                """, truncate("暂停待复评：模型制品或契约校验失败；" + reason), organizationId, activationId);
    }

    @Override
    @Transactional
    public void pauseModelForQualityReview(UUID organizationId, UUID activationId, String reason) {
        jdbc.update("""
                UPDATE ai.formula_model_activation
                SET status = 'PAUSED', ended_at = now(), activation_reason = ?
                WHERE organization_id = ? AND id = ? AND status = 'ACTIVE'
                """, truncate("暂停待复评：模型质量漂移；" + reason), organizationId, activationId);
    }

    private Optional<TaskProfileRow> findProfile(UUID organizationId, String code, String version) {
        return jdbc.query("""
                SELECT id, organization_id, profile_code, profile_version, status, contract_version,
                       schema_hash, content_hash, profile_jsonb, created_at
                FROM ai.formulation_task_profile
                WHERE organization_id IS NOT DISTINCT FROM ? AND profile_code = ? AND profile_version = ?
                """, (rs, ignored) -> profile(rs), organizationId, code, version).stream().findFirst();
    }

    private Optional<ActivationRow> lockActive(UUID organizationId, UUID taskProfileId, String targetKey) {
        return jdbc.query("""
                SELECT id, organization_id, task_profile_id, target_key, model_version_id,
                       previous_model_version_id, status, activation_reason, activated_by,
                       activated_at, ended_at, consecutive_failure_count, last_failure_at
                FROM ai.formula_model_activation
                WHERE organization_id = ? AND task_profile_id = ? AND target_key = ? AND status = 'ACTIVE'
                FOR UPDATE
                """, (rs, ignored) -> activation(rs), organizationId, taskProfileId, targetKey)
                .stream().findFirst();
    }

    private Optional<ActivationRow> lockLatestPaused(UUID organizationId, UUID taskProfileId, String targetKey) {
        return jdbc.query("""
                SELECT id, organization_id, task_profile_id, target_key, model_version_id,
                       previous_model_version_id, status, activation_reason, activated_by,
                       activated_at, ended_at, consecutive_failure_count, last_failure_at
                FROM ai.formula_model_activation
                WHERE organization_id = ? AND task_profile_id = ? AND target_key = ? AND status = 'PAUSED'
                ORDER BY ended_at DESC NULLS LAST, activated_at DESC
                LIMIT 1
                FOR UPDATE
                """, (rs, ignored) -> activation(rs), organizationId, taskProfileId, targetKey)
                .stream().findFirst();
    }

    private void requireQualifiedTarget(UUID organizationId, UUID taskProfileId,
                                        UUID versionId, String targetKey) {
        if (!isQualifiedTarget(organizationId, taskProfileId, versionId, targetKey)) {
            throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "该目标未通过生产门禁，不能激活");
        }
    }

    private boolean isQualifiedTarget(UUID organizationId, UUID taskProfileId,
                                      UUID versionId, String targetKey) {
        var count = jdbc.queryForObject("""
                SELECT count(*) FROM ai.formula_model_target t
                JOIN ai.formula_model_version v ON v.id = t.model_version_id
                WHERE t.organization_id = ? AND t.model_version_id = ? AND t.target_key = ?
                  AND t.production_eligible = true AND t.status = 'QUALIFIED'
                  AND v.task_profile_id = ? AND v.status = 'CANDIDATE'
                """, Integer.class, organizationId, versionId, targetKey, taskProfileId);
        return count != null && count == 1;
    }

    private TaskProfileRow profile(ResultSet rs) throws SQLException {
        return new TaskProfileRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("profile_code"), rs.getString("profile_version"), rs.getString("status"),
                rs.getString("contract_version"), rs.getString("schema_hash"), rs.getString("content_hash"),
                read(rs.getString("profile_jsonb")), instant(rs.getTimestamp("created_at")));
    }

    private SnapshotRow snapshot(ResultSet rs) throws SQLException {
        return new SnapshotRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("task_profile_id", UUID.class), rs.getString("status"), rs.getString("schema_version"),
                rs.getString("data_nature"), rs.getString("snapshot_purpose"), rs.getString("snapshot_hash"),
                rs.getString("validation_folds_hash"), rs.getString("t06_baseline_hash"),
                rs.getString("object_prefix"), read(rs.getString("artifacts_jsonb")),
                read(rs.getString("target_summary_jsonb")), rs.getInt("row_count"), rs.getString("error_code"),
                rs.getString("error_message"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("completed_at")));
    }

    private ModelVersionRow modelVersion(ResultSet rs) throws SQLException {
        return new ModelVersionRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("task_profile_id", UUID.class), rs.getObject("snapshot_id", UUID.class),
                rs.getString("status"), rs.getString("model_bundle_hash"), rs.getString("model_bundle_key"),
                read(rs.getString("model_card_jsonb")), read(rs.getString("training_result_jsonb")),
                rs.getString("error_code"), rs.getString("error_message"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("completed_at")));
    }

    private ModelTargetRow target(ResultSet rs) throws SQLException {
        return new ModelTargetRow(rs.getObject("model_version_id", UUID.class), rs.getString("target_key"),
                rs.getString("target_code"), rs.getString("value_type"), rs.getString("status"),
                rs.getBoolean("production_eligible"), rs.getString("scorer_type"),
                rs.getString("primary_metric_name"), rs.getBigDecimal("primary_metric_value"),
                rs.getBigDecimal("baseline_metric_value"), rs.getBigDecimal("baseline_improvement"),
                read(rs.getString("reasons_jsonb")), read(rs.getString("result_jsonb")));
    }

    private ActivationRow activation(ResultSet rs) throws SQLException {
        return new ActivationRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("task_profile_id", UUID.class), rs.getString("target_key"),
                rs.getObject("model_version_id", UUID.class), rs.getObject("previous_model_version_id", UUID.class),
                rs.getString("status"), rs.getString("activation_reason"), rs.getObject("activated_by", UUID.class),
                instant(rs.getTimestamp("activated_at")), instant(rs.getTimestamp("ended_at")),
                rs.getInt("consecutive_failure_count"), instant(rs.getTimestamp("last_failure_at")));
    }

    private PGobject pg(JsonNode node) {
        try {
            var value = new PGobject();
            value.setType("jsonb");
            value.setValue(node == null ? "{}" : node.toString());
            return value;
        } catch (SQLException exception) {
            throw new IllegalArgumentException("模型JSON无法持久化", exception);
        }
    }

    private JsonNode read(String value) {
        try {
            return value == null ? json.createObjectNode() : json.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("模型JSON无法读取", exception);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 4000 ? value : value.substring(0, 4000);
    }
}
