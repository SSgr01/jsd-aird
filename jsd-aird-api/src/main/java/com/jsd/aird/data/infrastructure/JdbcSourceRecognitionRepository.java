package com.jsd.aird.data.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.recognition.application.port.SourceRecognitionRepository;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.postgresql.util.PGobject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcSourceRecognitionRepository implements SourceRecognitionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcSourceRecognitionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public void create(NewJob job) {
        jdbc.update("""
                INSERT INTO data.import_job(
                    id,organization_id,source_file_id,source_sha256,source_file_name,source_format,
                    template_version_id,category_id,status,progress,current_stage,
                    duplicate_override,created_by,source_file_hash,compatibility_status,import_purpose,
                    target_experiment_category_id,source_owner,recognition_mode,
                    recognition_workspace_jsonb,experiment_boundaries_jsonb,visibility)
                VALUES(?,?,?,?,?,?,?,?,'QUEUED',0,'QUEUED',false,?,?,?, ?,?,?,?,?,?,?)
                """, job.id(), job.organizationId(), job.sourceFileId(), job.sourceSha256(),
                job.sourceFileName(), job.sourceFormat(), job.templateVersionId(), job.categoryId(),
                job.actorId(), job.sourceSha256(), job.templateVersionId() == null ? "LEGACY" : "REVIEW_REQUIRED",
                job.importPurpose(), job.targetExperimentCategoryId(), job.sourceOwner(), job.recognitionMode(),
                pg(job.initialWorkspace()), pg(job.initialBoundaries()), job.visibility());
        var payload = json.createObjectNode()
                .put("organizationId", job.organizationId().toString())
                .put("recognitionJobId", job.id().toString())
                .put("sourceOwner", job.sourceOwner());
        jdbc.update("""
                INSERT INTO ops.async_job(id,organization_id,job_type,status,payload_jsonb,priority,idempotency_key)
                VALUES(gen_random_uuid(),?,'SOURCE_RECOGNITION_PARSE','READY',?,50,?)
                """, job.organizationId(), pg(payload), "source-recognition:" + job.id() + ":1");
    }

    @Override
    public Optional<Job> find(UUID organizationId, UUID id, String sourceOwner) {
        return jdbc.query(SELECT_JOB + " WHERE organization_id=? AND id=? AND source_owner=?",
                this::job, organizationId, id, sourceOwner).stream().findFirst();
    }

    @Override
    public Optional<Job> findBySourceHash(UUID organizationId, String sourceOwner, String sourceSha256) {
        return jdbc.query(SELECT_JOB + " WHERE organization_id=? AND source_owner=? AND source_sha256=? "
                        + "AND status<>'CANCELLED' ORDER BY created_at DESC LIMIT 1",
                this::job, organizationId, sourceOwner, sourceSha256).stream().findFirst();
    }

    @Override
    public List<Job> list(UUID organizationId, String sourceOwner) {
        return jdbc.query(SELECT_JOB + " WHERE organization_id=? AND source_owner=? ORDER BY created_at DESC,id DESC",
                this::job, organizationId, sourceOwner);
    }

    @Override
    public void saveParsed(UUID organizationId, UUID id, String parserVersion, JsonNode workspace,
                           JsonNode boundaries) {
        var updated = jdbc.update("""
                UPDATE data.import_job
                SET parser_version=?,recognition_workspace_jsonb=?,experiment_boundaries_jsonb=?,
                    status='WAITING_MAPPING',progress=100,current_stage='WAITING_REVIEW',error_message=NULL,
                    recognition_revision=recognition_revision+1,updated_at=now()
                WHERE organization_id=? AND id=? AND status IN ('QUEUED','PARSING','FAILED')
                """, parserVersion, pg(workspace), pg(boundaries), organizationId, id);
        if (updated != 1) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                "识别任务状态已变化，请刷新后重试");
    }

    @Override
    @Transactional
    public Job updateWorkspace(UUID organizationId, UUID id, String sourceOwner, long expectedRevision,
                               JsonNode workspace, JsonNode boundaries, UUID profileId) {
        var updated = jdbc.update("""
                UPDATE data.import_job SET recognition_workspace_jsonb=?,experiment_boundaries_jsonb=?,
                    recognition_profile_id=?,recognition_revision=recognition_revision+1,
                    status='WAITING_MAPPING',finalized_at=NULL,updated_at=now()
                WHERE organization_id=? AND id=? AND source_owner=? AND recognition_revision=?
                  AND status NOT IN ('CANCELLED','PARSING','QUEUED')
                """, pg(workspace), pg(boundaries), profileId, organizationId, id, sourceOwner, expectedRevision);
        if (updated != 1) throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                "识别工作区已被其他会话修改，请刷新后重试");
        return find(organizationId, id, sourceOwner).orElseThrow();
    }

    @Override
    public int markParsing(UUID organizationId, UUID id, String sourceOwner) {
        var updated = jdbc.update("""
                UPDATE data.import_job SET status='QUEUED',progress=0,current_stage='QUEUED',
                    error_message=NULL,updated_at=now()
                WHERE organization_id=? AND id=? AND source_owner=? AND status IN ('FAILED','WAITING_MAPPING','COMPLETED')
                """, organizationId, id, sourceOwner);
        if (updated == 1) {
            var sequence = jdbc.queryForObject("SELECT source_sequence+1 FROM data.import_job WHERE organization_id=? AND id=?",
                    Long.class, organizationId, id);
            var payload = json.createObjectNode().put("organizationId", organizationId.toString())
                    .put("recognitionJobId", id.toString()).put("sourceOwner", sourceOwner);
            jdbc.update("""
                    INSERT INTO ops.async_job(id,organization_id,job_type,status,payload_jsonb,priority,idempotency_key)
                    VALUES(gen_random_uuid(),?,'SOURCE_RECOGNITION_PARSE','READY',?,50,?)
                    """, organizationId, pg(payload), "source-recognition:" + id + ":" + sequence);
        }
        return updated;
    }

    @Override
    public int claimFinalization(UUID organizationId, UUID id, String sourceOwner, long expectedRevision) {
        return jdbc.update("""
                UPDATE data.import_job SET status='COMMITTING',current_stage='FINALIZING',
                    source_sequence=source_sequence+1,updated_at=now()
                WHERE organization_id=? AND id=? AND source_owner=? AND recognition_revision=?
                    AND status='WAITING_MAPPING'
                """, organizationId, id, sourceOwner, expectedRevision);
    }

    @Override
    public int cancel(UUID organizationId, UUID id, String sourceOwner) {
        return jdbc.update("""
                UPDATE data.import_job SET status='CANCELLED',current_stage='CANCELLED',updated_at=now()
                WHERE organization_id=? AND id=? AND source_owner=? AND status<>'COMPLETED'
                """, organizationId, id, sourceOwner);
    }

    @Override
    public void fail(UUID organizationId, UUID id, String message) {
        jdbc.update("""
                UPDATE data.import_job SET status='FAILED',progress=0,current_stage='FAILED',error_message=?,updated_at=now()
                WHERE organization_id=? AND id=?
                """, message == null || message.isBlank() ? "来源文件识别失败" : message, organizationId, id);
    }

    @Override
    public void markFinalized(UUID organizationId, UUID id) {
        jdbc.update("""
                UPDATE data.import_job SET status='COMPLETED',progress=100,current_stage='COMPLETED',
                    finalized_at=now(),updated_at=now() WHERE organization_id=? AND id=?
                """, organizationId, id);
    }

    @Override
    @Transactional
    public FinalizedData finalizeData(UUID organizationId, UUID recognitionJobId, UUID actorId,
                                      List<RecognizedSample> samples, JsonNode mappingContract,
                                      String submissionHash) {
        var job = jdbc.query(SELECT_JOB + " WHERE organization_id=? AND id=? FOR UPDATE", this::job,
                organizationId, recognitionJobId).stream().findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "识别任务不存在"));
        if (!"COMMITTING".equals(job.status())) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                "识别任务未锁定或已完成，请刷新后重试");
        var previous = jdbc.query("""
                SELECT h.confirmed_submission_id FROM data.confirmed_submission_head h
                WHERE h.organization_id=? AND h.import_job_id=? FOR UPDATE
                """, (rs, ignored) -> rs.getObject(1, UUID.class), organizationId, recognitionJobId)
                .stream().findFirst().orElse(null);
        var revisionNo = jdbc.queryForObject("""
                SELECT coalesce(max(revision_no),0)+1 FROM data.confirmed_submission
                WHERE organization_id=? AND import_job_id=?
                """, Integer.class, organizationId, recognitionJobId);
        if (previous != null) jdbc.update("""
                UPDATE data.confirmed_submission SET status='SUPERSEDED'
                WHERE organization_id=? AND id=? AND status='CONFIRMED'
                """, organizationId, previous);
        var submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO data.confirmed_submission(id,organization_id,import_job_id,revision_no,status,
                    content_hash,confirmed_by,supersedes_submission_id,source_sequence,mapping_contract_jsonb)
                VALUES(?,?,?,?,'CONFIRMED',?,?,?,?,?)
                """, submissionId, organizationId, recognitionJobId, revisionNo, submissionHash,
                actorId, previous, job.sourceSequence(), pg(mappingContract));
        // data_record remains the current Data read model. Immutable submission
        // items keep the old facts and coordinates after this replacement.
        jdbc.update("DELETE FROM data.data_record WHERE organization_id=? AND import_job_id=?",
                organizationId, recognitionJobId);
        var itemIds = new java.util.ArrayList<UUID>();
        var index = 0;
        for (var sample : samples) {
            var recordId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO data.data_record(id,organization_id,import_job_id,record_key,record_index,
                        sheet_id,sheet_name,source_row_number,raw_data_jsonb,normalized_data_jsonb,
                        corrected_data_jsonb,effective_data_jsonb,quality_status,synthetic_key)
                    VALUES(?,?,?,?,?,NULL,NULL,NULL,?,?,?,?,'VALID',false)
                    """, recordId, organizationId, recognitionJobId, sample.sampleBoundaryId(), ++index,
                    pg(sample.rawFact()), pg(sample.effectiveFact()), pg(sample.correctedFact()),
                    pg(sample.effectiveFact()));
            var itemId = UUID.randomUUID();
            var identity = json.createObjectNode()
                    .put("experimentBoundaryId", sample.experimentBoundaryId())
                    .put("sampleBoundaryId", sample.sampleBoundaryId())
                    .put("logicalSampleKey", sample.logicalSampleKey())
                    .put("title", sample.title());
            identity.set("sourceGroupKeys", json.valueToTree(sample.sourceGroupKeys()));
            jdbc.update("""
                    INSERT INTO data.confirmed_submission_item(id,organization_id,confirmed_submission_id,
                        source_record_id,source_record_version,source_file_id,source_identity_jsonb,
                        raw_fact_jsonb,corrected_fact_jsonb,fact_jsonb,source_coordinates_jsonb,
                        source_file_sha256,content_hash)
                    VALUES(?,?,?,?,1,?,?,?,?,?,?,?,?)
                    """, itemId, organizationId, submissionId, recordId, job.sourceFileId(), pg(identity),
                    pg(sample.rawFact()), pg(sample.correctedFact()), pg(sample.effectiveFact()),
                    pg(sample.sourceCoordinates()), job.sourceSha256(), sample.contentHash());
            itemIds.add(itemId);
        }
        jdbc.update("""
                INSERT INTO data.confirmed_submission_head(organization_id,import_job_id,confirmed_submission_id)
                VALUES(?,?,?) ON CONFLICT(organization_id,import_job_id) DO UPDATE SET
                    confirmed_submission_id=excluded.confirmed_submission_id,
                    revision=data.confirmed_submission_head.revision+1,updated_at=now()
                """, organizationId, recognitionJobId, submissionId);
        jdbc.update("""
                UPDATE data.import_experiment_link SET source_updated=true,updated_at=now()
                WHERE organization_id=? AND import_job_id=? AND experiment_id IS NOT NULL
                    AND confirmed_submission_id IS DISTINCT FROM ?
                """, organizationId, recognitionJobId, submissionId);
        return new FinalizedData(submissionId, revisionNo, List.copyOf(itemIds));
    }

    @Override
    @Transactional
    public void linkExperiment(UUID organizationId, UUID recognitionJobId, UUID confirmedSubmissionId,
                               String experimentBoundaryId, UUID experimentId, UUID experimentVersionId,
                               String experimentNo, JsonNode sourceCoordinates, JsonNode recognitionSnapshot,
                               List<RecognizedSample> samples, String contentHash, UUID actorId) {
        var job = jdbc.query(SELECT_JOB + " WHERE organization_id=? AND id=?", this::job,
                organizationId, recognitionJobId).stream().findFirst().orElseThrow();
        var sampleBoundaryIds = samples.stream().map(RecognizedSample::sampleBoundaryId).toList();
        var logicalSampleKeys = samples.stream().map(RecognizedSample::logicalSampleKey).toList();
        var sourceGroupKeys = samples.stream().flatMap(sample -> sample.sourceGroupKeys().stream()).distinct().toList();
        var linked = jdbc.update("""
                INSERT INTO data.import_experiment_link AS existing(id,organization_id,import_job_id,recognition_job_id,
                    confirmed_submission_id,assembly_key,experiment_boundary_id,source_identity,source_identity_type,
                    source_record_keys_jsonb,sample_boundary_ids_jsonb,logical_sample_keys_jsonb,source_group_keys_jsonb,
                    plan_hash,content_hash,status,experiment_id,experiment_version_id,experiment_no,
                    source_snapshot_jsonb,created_by)
                VALUES(gen_random_uuid(),?,?,?,?,?,?,?,'EXPERIMENT_BOUNDARY',?,?,?,?,?,?,'SYNCED',?,?,?,?,?)
                ON CONFLICT(organization_id,import_job_id,assembly_key) DO UPDATE SET
                    confirmed_submission_id=excluded.confirmed_submission_id,
                    experiment_id=excluded.experiment_id,experiment_version_id=excluded.experiment_version_id,
                    experiment_no=excluded.experiment_no,status='SYNCED',source_snapshot_jsonb=excluded.source_snapshot_jsonb,
                    experiment_boundary_id=excluded.experiment_boundary_id,
                    source_record_keys_jsonb=excluded.source_record_keys_jsonb,
                    sample_boundary_ids_jsonb=excluded.sample_boundary_ids_jsonb,
                    logical_sample_keys_jsonb=excluded.logical_sample_keys_jsonb,
                    source_group_keys_jsonb=excluded.source_group_keys_jsonb,
                    source_updated=false,updated_at=now()
                WHERE existing.experiment_id=excluded.experiment_id
                    AND existing.experiment_version_id=excluded.experiment_version_id
                """, organizationId, recognitionJobId, recognitionJobId, confirmedSubmissionId,
                experimentBoundaryId, experimentBoundaryId, experimentBoundaryId,
                pg(json.valueToTree(sourceGroupKeys)), pg(json.valueToTree(sampleBoundaryIds)),
                pg(json.valueToTree(logicalSampleKeys)), pg(json.valueToTree(sourceGroupKeys)), contentHash, contentHash,
                experimentId, experimentVersionId, experimentNo, pg(recognitionSnapshot), actorId);
        if (linked != 1) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                "该实验边界已绑定其他实验，不能覆盖已有来源关系");
        for (var sample : samples) {
            jdbc.update("""
                    INSERT INTO rnd.experiment_source_reference(id,organization_id,experiment_id,experiment_version_id,
                        recognition_job_id,confirmed_submission_id,experiment_boundary_id,sample_boundary_id,
                        logical_sample_key,source_group_keys_jsonb,source_file_id,source_file_sha256,
                        source_coordinates_jsonb,recognition_snapshot_jsonb,content_hash,created_by)
                    VALUES(gen_random_uuid(),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(organization_id,experiment_version_id,recognition_job_id,sample_boundary_id) DO NOTHING
                    """, organizationId, experimentId, experimentVersionId, recognitionJobId, confirmedSubmissionId,
                    experimentBoundaryId, sample.sampleBoundaryId(), sample.logicalSampleKey(),
                    pg(json.valueToTree(sample.sourceGroupKeys())), job.sourceFileId(), job.sourceSha256(),
                    pg(sample.sourceCoordinates()), pg(recognitionSnapshot), sample.contentHash(), actorId);
        }
    }

    @Override
    public Optional<UUID> currentSubmissionId(UUID organizationId, UUID recognitionJobId) {
        return jdbc.query("""
                SELECT confirmed_submission_id FROM data.confirmed_submission_head
                WHERE organization_id=? AND import_job_id=?
                """, (rs, ignored) -> rs.getObject(1, UUID.class), organizationId, recognitionJobId)
                .stream().findFirst();
    }

    @Override
    public RecognitionProfile saveProfile(UUID organizationId, String name, String sourceFormat,
                                          JsonNode rules, String hash, UUID actorId) {
        var id = UUID.randomUUID();
        var code = "RECOGNITION_" + hash.substring(0, 16).toUpperCase(java.util.Locale.ROOT);
        try {
            jdbc.update("""
                    INSERT INTO data.recognition_profile(id,organization_id,profile_code,name,source_format,
                        rules_jsonb,profile_hash,created_by,updated_by)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """, id, organizationId, code, name, sourceFormat, pg(rules), hash, actorId, actorId);
        } catch (DuplicateKeyException duplicate) {
            return jdbc.query("""
                    SELECT id,profile_code,name,source_format,status,rules_jsonb,profile_hash,revision,updated_at
                    FROM data.recognition_profile WHERE organization_id=? AND profile_hash=?
                    """, this::profile, organizationId, hash).stream().findFirst().orElseThrow(() -> duplicate);
        }
        return jdbc.query("""
                SELECT id,profile_code,name,source_format,status,rules_jsonb,profile_hash,revision,updated_at
                FROM data.recognition_profile WHERE organization_id=? AND id=?
                """, this::profile, organizationId, id).getFirst();
    }

    private Job job(ResultSet rs, int ignored) throws SQLException {
        return new Job(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("source_file_id", UUID.class), rs.getString("source_file_name"),
                rs.getString("source_sha256"), rs.getString("source_format"), rs.getString("source_owner"),
                rs.getString("recognition_mode"), rs.getObject("template_version_id", UUID.class),
                rs.getObject("category_id", UUID.class), rs.getString("import_purpose"),
                rs.getObject("target_experiment_category_id", UUID.class), rs.getString("visibility"),
                rs.getString("status"), rs.getInt("progress"), rs.getString("current_stage"),
                rs.getString("parser_version"), node(rs, "recognition_workspace_jsonb"),
                node(rs, "experiment_boundaries_jsonb"), rs.getLong("recognition_revision"),
                rs.getObject("recognition_profile_id", UUID.class), rs.getLong("source_sequence"),
                rs.getTimestamp("finalized_at") == null ? null : rs.getTimestamp("finalized_at").toInstant(),
                rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private RecognitionProfile profile(ResultSet rs, int ignored) throws SQLException {
        return new RecognitionProfile(rs.getObject("id", UUID.class), rs.getString("profile_code"),
                rs.getString("name"), rs.getString("source_format"), rs.getString("status"),
                node(rs, "rules_jsonb"), rs.getString("profile_hash"), rs.getLong("revision"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private JsonNode node(ResultSet rs, String column) throws SQLException {
        try { return json.readTree(rs.getString(column)); }
        catch (Exception exception) { throw new SQLException("Invalid JSON in " + column, exception); }
    }

    private PGobject pg(JsonNode value) {
        try {
            var result = new PGobject(); result.setType("jsonb");
            result.setValue(json.writeValueAsString(value)); return result;
        } catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }

    private static final String SELECT_JOB = """
            SELECT id,organization_id,source_file_id,source_file_name,source_sha256,source_format,
                source_owner,recognition_mode,template_version_id,category_id,import_purpose,
                target_experiment_category_id,visibility,status,progress,current_stage,parser_version,
                recognition_workspace_jsonb,experiment_boundaries_jsonb,recognition_revision,
                recognition_profile_id,source_sequence,finalized_at,error_message,created_at,updated_at
            FROM data.import_job
            """;
}
