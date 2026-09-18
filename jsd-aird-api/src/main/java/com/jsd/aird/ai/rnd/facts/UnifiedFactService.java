package com.jsd.aird.ai.rnd.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.rnd.modeling.ConfigurationHashing;
import com.jsd.aird.ai.rnd.eligibility.EligibilityService;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.api.PageResponse;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Immutable Data/RND facts projected into one logical sample identity. */
@Service
public class UnifiedFactService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConfigurationHashing hashing;
    private final AuthorizationService authorization;
    private EligibilityService eligibilityService;

    public UnifiedFactService(JdbcTemplate jdbc, ObjectMapper json, ConfigurationHashing hashing,
                              AuthorizationService authorization) {
        this.jdbc = jdbc; this.json = json; this.hashing = hashing; this.authorization = authorization;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEligibilityService(EligibilityService eligibilityService) { this.eligibilityService = eligibilityService; }

    @Transactional
    public int projectSubmission(UUID organizationId, UUID submissionId) {
        var submission = jdbc.query("""
                SELECT s.id,s.import_job_id,s.revision_no,s.status,s.source_sequence,j.source_sha256
                FROM data.confirmed_submission s JOIN data.import_job j ON j.id=s.import_job_id
                WHERE s.organization_id=? AND s.id=?
                """, (rs, ignored) -> new Submission(rs.getObject("id", UUID.class),
                rs.getObject("import_job_id", UUID.class), rs.getInt("revision_no"), rs.getString("status"),
                rs.getLong("source_sequence"), rs.getString("source_sha256")), organizationId, submissionId)
                .stream().findFirst().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "正式提交版本不存在"));
        if (!"CONFIRMED".equals(submission.status())) return 0;
        if (!beginReceipt(organizationId, "DATA_CENTER", submission.id(), "submission:" + submission.id(),
                submission.sourceSequence(), "data.submission.confirmed.v1", hashing.hash(submission))) return 0;
        var items = jdbc.query("""
                SELECT id,source_record_id,source_record_version,source_identity_jsonb,fact_jsonb,
                    source_coordinates_jsonb,content_hash
                FROM data.confirmed_submission_item WHERE organization_id=? AND confirmed_submission_id=?
                ORDER BY created_at,id
                """, this::submissionItem, organizationId, submissionId);
        var count = 0;
        for (var item : items) {
            projectDataItem(organizationId, submission, item); count++;
        }
        completeReceipt(organizationId, "DATA_CENTER", submission.id(), "COMPLETED", null);
        if (eligibilityService != null) eligibilityService.scheduleForChangedFacts(organizationId);
        return count;
    }

    /** Freezes the current template-guided Data read model into a new immutable submission revision. */
    @Transactional
    public UUID confirmCurrentDataJob(UUID organizationId, UUID importJobId, UUID actorId) {
        var job = jdbc.query("""
                SELECT source_file_id,source_sha256,source_sequence,template_version_id,
                    import_contract_version,contract_hash,recognition_mode,parser_version
                FROM data.import_job WHERE organization_id=? AND id=? AND source_owner='DATA_CENTER'
                FOR UPDATE
                """, (rs, ignored) -> json.createObjectNode()
                .put("sourceFileId", rs.getObject("source_file_id", UUID.class).toString())
                .put("sourceSha256", rs.getString("source_sha256"))
                .put("sourceSequence", rs.getLong("source_sequence"))
                .put("templateVersionId", String.valueOf(rs.getObject("template_version_id", UUID.class)))
                .put("importContractVersion", rs.getObject("import_contract_version") == null ? 0 : rs.getInt("import_contract_version"))
                .put("contractHash", rs.getString("contract_hash") == null ? "" : rs.getString("contract_hash"))
                .put("recognitionMode", rs.getString("recognition_mode"))
                .put("parserVersion", rs.getString("parser_version") == null ? "" : rs.getString("parser_version")),
                organizationId, importJobId).stream().findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据导入任务不存在"));
        var rows = jdbc.query("""
                SELECT id,record_key,record_index,raw_data_jsonb,corrected_data_jsonb,effective_data_jsonb,
                    sheet_id,sheet_name,source_row_number
                FROM data.data_record WHERE organization_id=? AND import_job_id=? ORDER BY record_index
                """, (rs, ignored) -> new ConfirmedRow(rs.getObject("id", UUID.class), rs.getString("record_key"),
                rs.getInt("record_index"), node(rs, "raw_data_jsonb"), node(rs, "corrected_data_jsonb"),
                node(rs, "effective_data_jsonb"), rs.getString("sheet_id"), rs.getString("sheet_name"),
                (Integer) rs.getObject("source_row_number")), organizationId, importJobId);
        var payload = json.createObjectNode().set("rows", json.valueToTree(rows));
        ((ObjectNode) payload).set("contract", job.deepCopy());
        var submissionHash = hashing.hash(payload);
        var existing = jdbc.query("""
                SELECT s.id FROM data.confirmed_submission_head h JOIN data.confirmed_submission s
                    ON s.organization_id=h.organization_id AND s.id=h.confirmed_submission_id
                WHERE h.organization_id=? AND h.import_job_id=? AND s.content_hash=?
                """, (rs, ignored) -> rs.getObject(1, UUID.class), organizationId, importJobId, submissionHash)
                .stream().findFirst();
        if (existing.isPresent()) return existing.get();
        var previous = jdbc.query("SELECT confirmed_submission_id FROM data.confirmed_submission_head WHERE organization_id=? AND import_job_id=?",
                (rs, ignored) -> rs.getObject(1, UUID.class), organizationId, importJobId).stream().findFirst().orElse(null);
        var revision = jdbc.queryForObject("SELECT coalesce(max(revision_no),0)+1 FROM data.confirmed_submission WHERE organization_id=? AND import_job_id=?",
                Integer.class, organizationId, importJobId);
        if (previous != null) jdbc.update("UPDATE data.confirmed_submission SET status='SUPERSEDED' WHERE organization_id=? AND id=? AND status='CONFIRMED'",
                organizationId, previous);
        var submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO data.confirmed_submission(id,organization_id,import_job_id,revision_no,status,
                    content_hash,confirmed_by,supersedes_submission_id,source_sequence,mapping_contract_jsonb)
                VALUES(?,?,?,?,'CONFIRMED',?,?,?,?,?)
                """, submissionId, organizationId, importJobId, revision, submissionHash, actorId, previous,
                job.path("sourceSequence").asLong(), pg(job));
        for (var row : rows) {
            var coordinates = json.createObjectNode();
            if (row.sheetId() != null) coordinates.put("sheetId", row.sheetId());
            if (row.sheetName() != null) coordinates.put("sheetName", row.sheetName());
            if (row.sourceRowNumber() != null) coordinates.put("rowNumber", row.sourceRowNumber());
            var anchors = jdbc.query("""
                    SELECT field_code,binding_id,value_path,label_path,sheet_id,sheet_name,row_number,
                        column_number,column_name,cell_address,raw_value_jsonb
                    FROM data.source_anchor WHERE import_job_id=? AND record_id=? ORDER BY field_code,cell_address
                    """, (rs, ignored) -> {
                        var anchor = json.createObjectNode();
                        for (var name : List.of("field_code","binding_id","value_path","label_path","sheet_id",
                                "sheet_name","column_name","cell_address"))
                            if (rs.getString(name) != null) anchor.put(camel(name), rs.getString(name));
                        if (rs.getObject("row_number") != null) anchor.put("rowNumber", rs.getInt("row_number"));
                        if (rs.getObject("column_number") != null) anchor.put("columnNumber", rs.getInt("column_number"));
                        anchor.set("rawValue", nodeNullable(rs, "raw_value_jsonb")); return anchor;
                    }, importJobId, row.id());
            coordinates.set("anchors", json.valueToTree(anchors));
            var logicalSampleKey = "SOURCE:" + importJobId + ":" + row.recordKey();
            var identity = json.createObjectNode()
                    .put("experimentBoundaryId", "record:" + row.recordKey())
                    .put("sampleBoundaryId", row.recordKey())
                    .put("logicalSampleKey", logicalSampleKey)
                    .put("recordKey", row.recordKey()).put("recordIndex", row.recordIndex());
            identity.set("sourceGroupKeys", json.createArrayNode().add(row.recordKey()));
            jdbc.update("""
                    INSERT INTO data.confirmed_submission_item(id,organization_id,confirmed_submission_id,
                        source_record_id,source_record_version,source_file_id,sheet_name,row_coordinate,
                        source_identity_jsonb,raw_fact_jsonb,corrected_fact_jsonb,fact_jsonb,
                        source_coordinates_jsonb,source_file_sha256,content_hash)
                    VALUES(gen_random_uuid(),?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, organizationId, submissionId, row.id(), revision,
                    UUID.fromString(job.path("sourceFileId").asText()), row.sheetName(),
                    row.sourceRowNumber() == null ? null : String.valueOf(row.sourceRowNumber()),
                    pg(identity), pg(row.raw()), pg(row.corrected()), pg(row.effective()), pg(coordinates),
                    job.path("sourceSha256").asText(), hashing.hash(row.effective()));
        }
        jdbc.update("""
                INSERT INTO data.confirmed_submission_head(organization_id,import_job_id,confirmed_submission_id)
                VALUES(?,?,?) ON CONFLICT(organization_id,import_job_id) DO UPDATE SET
                    confirmed_submission_id=excluded.confirmed_submission_id,
                    revision=data.confirmed_submission_head.revision+1,updated_at=now()
                """, organizationId, importJobId, submissionId);
        jdbc.update("""
                UPDATE data.import_experiment_link SET source_updated=true,updated_at=now()
                WHERE organization_id=? AND import_job_id=? AND experiment_id IS NOT NULL
                    AND confirmed_submission_id IS DISTINCT FROM ?
                """, organizationId, importJobId, submissionId);
        projectSubmission(organizationId, submissionId);
        return submissionId;
    }

    @Transactional
    public int projectExperimentEvent(UUID organizationId, UUID eventId, UUID experimentId,
                                      UUID versionId, long sourceSequence, String eventType) {
        if (!beginReceipt(organizationId, "EXPERIMENT", eventId, "experiment:" + experimentId,
                sourceSequence, eventType, hashing.hash(List.of(experimentId, versionId, sourceSequence, eventType)))) return 0;
        if (eventType.endsWith("revision-started.v1") || eventType.endsWith("voided.v1")) {
            jdbc.update("""
                    UPDATE ai.training_sample SET status=?,authority_source_type=NULL,current_sample_revision_id=NULL,
                        identity_version=identity_version+1,revision=revision+1,updated_at=now()
                    WHERE organization_id=? AND takeover_experiment_id=?
                    """, eventType.endsWith("voided.v1") ? "INVALIDATED" : "SUSPENDED",
                    organizationId, experimentId);
            jdbc.update("""
                    UPDATE ai.sample_source SET status='INVALIDATED'
                    WHERE organization_id=? AND experiment_version_id=? AND status='CURRENT'
                    """, organizationId, versionId);
            completeReceipt(organizationId, "EXPERIMENT", eventId, "COMPLETED", null);
            if (eligibilityService != null) eligibilityService.scheduleForChangedFacts(organizationId);
            return 0;
        }
        var experiment = jdbc.query("""
                SELECT e.experiment_no,e.title,coalesce(v.edit_model_jsonb->>'visibility','ALL') AS visibility,
                    e.status,v.version_no,v.edit_model_jsonb
                FROM rnd.experiment e JOIN rnd.experiment_version v ON v.id=? AND v.experiment_id=e.id
                WHERE e.organization_id=? AND e.id=? AND e.status='COMPLETED' AND v.status='COMPLETED'
                """, (rs, ignored) -> new ExperimentFact(rs.getString("experiment_no"), rs.getString("title"),
                rs.getString("visibility"), rs.getInt("version_no"), node(rs, "edit_model_jsonb")),
                versionId, organizationId, experimentId).stream().findFirst().orElse(null);
        if (experiment == null) {
            completeReceipt(organizationId, "EXPERIMENT", eventId, "STALE", null); return 0;
        }
        var refs = jdbc.query("""
                SELECT recognition_job_id,confirmed_submission_id,experiment_boundary_id,sample_boundary_id,
                    logical_sample_key,source_group_keys_jsonb,source_file_sha256,
                    source_coordinates_jsonb,content_hash
                FROM rnd.experiment_source_reference
                WHERE organization_id=? AND experiment_id=? AND experiment_version_id=? ORDER BY sample_boundary_id
                """, this::sourceRef, organizationId, experimentId, versionId);
        if (refs.isEmpty()) {
            var sampleBoundaryId = "manual:" + versionId;
            refs = List.of(new SourceRef(null, null, "manual:" + experimentId, sampleBoundaryId,
                    "EXPERIMENT_VERSION:" + versionId, List.of("experiment-version:" + versionId),
                    hashing.hash(List.of(experimentId, versionId)),
                    json.createObjectNode().put("kind", "EXPERIMENT_VERSION"), hashing.hash(experiment.editModel())));
        }
        for (var ref : refs) projectExperiment(organizationId, experimentId, versionId,
                sourceSequence, experiment, ref);
        completeReceipt(organizationId, "EXPERIMENT", eventId, "COMPLETED", null);
        if (eligibilityService != null) eligibilityService.scheduleForChangedFacts(organizationId);
        return refs.size();
    }

    public PageResponse<SampleSummary> samples(String status, String sourceType, String keyword,
                                               int page, int size) {
        var actor = ActorContext.required();
        var safePage = Math.max(1, page); var safeSize = Math.min(100, Math.max(1, size));
        var where = new StringBuilder(" WHERE s.organization_id=?");
        var args = new ArrayList<Object>(); args.add(actor.organizationId());
        if (status != null && !status.isBlank()) { where.append(" AND s.status=?"); args.add(status); }
        if (sourceType != null && !sourceType.isBlank()) { where.append(" AND s.authority_source_type=?"); args.add(sourceType); }
        if (keyword != null && !keyword.isBlank()) { where.append(" AND lower(s.logical_sample_key) LIKE lower(?)"); args.add("%" + keyword.strip() + "%"); }
        var total = jdbc.queryForObject("SELECT count(*) FROM ai.training_sample s" + where, Long.class, args.toArray());
        var queryArgs = new ArrayList<>(args); queryArgs.add(safeSize); queryArgs.add((safePage - 1) * safeSize);
        var items = jdbc.query("""
                SELECT s.id,s.logical_sample_key,s.identity_version,s.authority_source_type,s.status,
                    s.takeover_experiment_id,s.takeover_experiment_version_id,s.revision,s.updated_at,
                    (SELECT count(*) FROM ai.sample_source ss WHERE ss.organization_id=s.organization_id
                        AND ss.training_sample_id=s.id) source_count
                FROM ai.training_sample s
                """ + where + " ORDER BY s.updated_at DESC,s.id LIMIT ? OFFSET ?", this::summary, queryArgs.toArray());
        var count = total == null ? 0 : total;
        return new PageResponse<>(items, safePage, safeSize, count, (count + safeSize - 1) / safeSize);
    }

    public SampleDetail sample(UUID id) {
        var actor = ActorContext.required();
        var summary = jdbc.query("""
                SELECT s.id,s.logical_sample_key,s.identity_version,s.authority_source_type,s.status,
                    s.takeover_experiment_id,s.takeover_experiment_version_id,s.revision,s.updated_at,
                    (SELECT count(*) FROM ai.sample_source ss WHERE ss.organization_id=s.organization_id
                        AND ss.training_sample_id=s.id) source_count
                FROM ai.training_sample s WHERE s.organization_id=? AND s.id=?
                """, this::summary, actor.organizationId(), id).stream().findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "训练样本不存在"));
        var sources = jdbc.query("""
                SELECT ss.id,ss.source_type,ss.source_business_key,ss.sample_boundary_id,
                    ss.logical_sample_key,ss.source_group_keys_jsonb,ss.source_version,
                    ss.source_sequence,ss.content_hash,ss.status,ss.confirmed_submission_item_id,
                    ss.experiment_version_id,sub.import_job_id recognition_job_id,ev.experiment_id,
                    coalesce(experiment_ref.recognition_job_id, experiment_ref.experiment_import_job_id)
                        experiment_recognition_job_id,
                    ss.created_at,sr.id revision_id,sr.composition_jsonb,
                    sr.process_jsonb,sr.conditions_jsonb,sr.observations_jsonb,sr.facts_jsonb,
                    sr.permission_scope_jsonb,sr.source_coordinates_jsonb,sr.fact_hash
                FROM ai.sample_source ss LEFT JOIN ai.sample_revision sr
                    ON sr.organization_id=ss.organization_id AND sr.sample_source_id=ss.id
                LEFT JOIN data.confirmed_submission_item submission_item
                    ON submission_item.organization_id=ss.organization_id
                    AND submission_item.id=ss.confirmed_submission_item_id
                LEFT JOIN data.confirmed_submission sub ON sub.organization_id=ss.organization_id
                    AND sub.id=submission_item.confirmed_submission_id
                LEFT JOIN rnd.experiment_version ev ON ev.organization_id=ss.organization_id
                    AND ev.id=ss.experiment_version_id
                LEFT JOIN LATERAL (
                    SELECT source_ref.recognition_job_id,
                           source_ref.experiment_import_job_id
                        FROM rnd.experiment_source_reference source_ref
                    WHERE source_ref.organization_id=ss.organization_id
                        AND source_ref.experiment_version_id=ss.experiment_version_id
                        AND source_ref.sample_boundary_id=ss.sample_boundary_id
                    ORDER BY source_ref.created_at DESC LIMIT 1
                ) experiment_ref ON true
                WHERE ss.organization_id=? AND ss.training_sample_id=?
                ORDER BY ss.source_sequence DESC,ss.created_at DESC
                """, this::source, actor.organizationId(), id);
        var filtered = sources.stream().map(source -> canRead(actor.organizationId(), actor.userId(), source.sourceType())
                ? source : source.redacted(json)).toList();
        var issues = jdbc.query("""
                SELECT id,issue_type,status,evidence_jsonb,resolution_jsonb,revision,created_at,resolved_at
                FROM ai.sample_identity_issue WHERE organization_id=? AND training_sample_id=? ORDER BY created_at DESC
                """, (rs, ignored) -> new IdentityIssue(rs.getObject("id", UUID.class), rs.getString("issue_type"),
                rs.getString("status"), node(rs, "evidence_jsonb"), nodeNullable(rs, "resolution_jsonb"),
                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant()),
                actor.organizationId(), id);
        return new SampleDetail(summary, filtered, issues);
    }

    /** Manual repair endpoint and scheduled safety net share the same operation. */
    @Transactional
    public ReconcileResult reconcile(UUID organizationId, int limit) {
        var submissions = jdbc.query("""
                SELECT h.confirmed_submission_id FROM data.confirmed_submission_head h
                LEFT JOIN ai.fact_projection_receipt r ON r.organization_id=h.organization_id
                    AND r.source_type='DATA_CENTER' AND r.source_event_id=h.confirmed_submission_id
                    AND r.status='COMPLETED'
                WHERE h.organization_id=? AND r.id IS NULL ORDER BY h.updated_at LIMIT ?
                """, (rs, ignored) -> rs.getObject(1, UUID.class), organizationId, limit);
        var projectedSubmissions = 0;
        for (var id : submissions) projectedSubmissions += projectSubmission(organizationId, id);
        var remaining = Math.max(0, limit - submissions.size());
        var events = jdbc.query("""
                SELECT o.id,o.aggregate_id,o.event_type,o.payload_jsonb
                FROM rnd.experiment_outbox o LEFT JOIN ai.fact_projection_receipt r
                    ON r.organization_id=o.organization_id AND r.source_type='EXPERIMENT' AND r.source_event_id=o.id
                    AND r.status='COMPLETED'
                WHERE o.organization_id=? AND r.id IS NULL AND o.event_type IN
                    ('experiment.published.v1','experiment.voided.v1','experiment.revision-started.v1')
                ORDER BY o.created_at,o.id LIMIT ?
                """, (rs, ignored) -> new ExperimentEvent(rs.getObject("id", UUID.class),
                rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"), node(rs, "payload_jsonb")),
                organizationId, remaining);
        var projectedExperiments = 0;
        for (var event : events) {
            var version = uuid(event.payload(), "versionId");
            if (version == null) version = jdbc.queryForObject("SELECT current_version_id FROM rnd.experiment WHERE id=?", UUID.class, event.experimentId());
            projectedExperiments += projectExperimentEvent(organizationId, event.id(), event.experimentId(),
                    version, event.payload().path("versionNo").asLong(1), event.eventType());
        }
        return new ReconcileResult(projectedSubmissions, projectedExperiments,
                submissions.size() + events.size());
    }

    public ReconcileResult reconcileCurrentOrganization() {
        return reconcile(ActorContext.required().organizationId(), 200);
    }

    @Scheduled(fixedDelayString = "${app.ai.fact-reconcile-delay:PT5M}")
    public void scheduledReconcile() {
        var organizations = jdbc.query("SELECT id FROM iam.organization ORDER BY id",
                (rs, ignored) -> rs.getObject(1, UUID.class));
        for (var organization : organizations) {
            try { reconcile(organization, 200); }
            catch (RuntimeException ignored) { /* receipts preserve the failure for the next pass */ }
        }
    }

    private void projectDataItem(UUID organizationId, Submission submission, SubmissionItem item) {
        var sampleBoundaryId = item.identity().path("sampleBoundaryId").asText(item.sourceRecordId() == null
                ? item.id().toString() : item.sourceRecordId().toString());
        var logicalKey = item.identity().path("logicalSampleKey").asText(
                "SOURCE:" + submission.importJobId() + ":" + sampleBoundaryId);
        var sourceGroupKeys = strings(item.identity().path("sourceGroupKeys"));
        if (sourceGroupKeys.isEmpty()) sourceGroupKeys = List.of(sampleBoundaryId);
        var sampleId = sampleId(organizationId, logicalKey);
        var takeover = jdbc.queryForObject("SELECT takeover_experiment_id IS NOT NULL FROM ai.training_sample WHERE id=?",
                Boolean.class, sampleId);
        var sourceKey = "DATA:" + submission.importJobId() + ":" + sampleBoundaryId;
        if (isStaleSource(organizationId, "DATA_CENTER", sourceKey, submission.sourceSequence())) return;
        jdbc.update("UPDATE ai.sample_source SET status='SUPERSEDED' WHERE organization_id=? AND source_type='DATA_CENTER' AND source_business_key=? AND status='CURRENT'",
                organizationId, sourceKey);
        var sourceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai.sample_source(id,organization_id,training_sample_id,source_type,
                    confirmed_submission_item_id,source_business_key,sample_boundary_id,logical_sample_key,
                    source_group_keys_jsonb,physical_identity_hash,
                    source_version,source_sequence,content_hash,priority,status)
                VALUES(?,?,?,'DATA_CENTER',?,?,?,?,?,?,?,?,?,?,?)
                """, sourceId, organizationId, sampleId, item.id(), sourceKey, sampleBoundaryId, logicalKey,
                pg(json.valueToTree(sourceGroupKeys)),
                hashing.hash(List.of(submission.sourceSha256(), sourceGroupKeys, item.coordinates())),
                "submission-r" + submission.revisionNo(), submission.sourceSequence(), item.contentHash(), 10,
                Boolean.TRUE.equals(takeover) ? "TAKEN_OVER" : "CURRENT");
        if (Boolean.TRUE.equals(takeover)) return;
        var revisionId = insertRevision(organizationId, sampleId, sourceId, item.fact(),
                item.coordinates(), json.createObjectNode().put("sourceType", "DATA_CENTER"));
        jdbc.update("""
                UPDATE ai.training_sample SET authority_source_type='DATA_CENTER',current_sample_revision_id=?,
                    status='ACTIVE',identity_version=identity_version+1,revision=revision+1,updated_at=now()
                WHERE organization_id=? AND id=? AND takeover_experiment_id IS NULL
                """, revisionId, organizationId, sampleId);
    }

    private void projectExperiment(UUID organizationId, UUID experimentId, UUID versionId, long sourceSequence,
                                   ExperimentFact experiment, SourceRef ref) {
        var logicalKey = ref.logicalSampleKey();
        var sampleId = findLinkedSample(organizationId, ref).orElseGet(() -> sampleId(organizationId, logicalKey));
        var sourceKey = "EXPERIMENT:" + experimentId + ":" + ref.sampleBoundaryId();
        if (isStaleSource(organizationId, "EXPERIMENT", sourceKey, sourceSequence)) return;
        jdbc.update("UPDATE ai.sample_source SET status='SUPERSEDED' WHERE organization_id=? AND source_type='EXPERIMENT' AND source_business_key=? AND status='CURRENT'",
                organizationId, sourceKey);
        jdbc.update("UPDATE ai.sample_source SET status='TAKEN_OVER' WHERE organization_id=? AND training_sample_id=? AND source_type='DATA_CENTER' AND status='CURRENT'",
                organizationId, sampleId);
        var sourceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai.sample_source(id,organization_id,training_sample_id,source_type,experiment_version_id,
                    source_business_key,sample_boundary_id,logical_sample_key,source_group_keys_jsonb,
                    physical_identity_hash,source_version,source_sequence,
                    content_hash,priority,status)
                VALUES(?,?,?,'EXPERIMENT',?,?,?,?,?,?,?,?,?,100,'CURRENT')
                """, sourceId, organizationId, sampleId, versionId, sourceKey, ref.sampleBoundaryId(), logicalKey,
                pg(json.valueToTree(ref.sourceGroupKeys())),
                hashing.hash(List.of(ref.sourceFileSha256(), ref.sourceGroupKeys(), ref.coordinates())), "experiment-v" + experiment.versionNo(),
                sourceSequence, ref.contentHash());
        var permission = json.createObjectNode().put("sourceType", "EXPERIMENT")
                .put("visibility", experiment.visibility()).put("experimentId", experimentId.toString());
        var revisionId = insertRevision(organizationId, sampleId, sourceId, experiment.editModel(),
                ref.coordinates(), permission);
        jdbc.update("""
                UPDATE ai.training_sample SET authority_source_type='EXPERIMENT',current_sample_revision_id=?,
                    takeover_experiment_id=?,takeover_experiment_version_id=?,status='ACTIVE',
                    identity_version=identity_version+1,revision=revision+1,updated_at=now()
                WHERE organization_id=? AND id=?
                """, revisionId, experimentId, versionId, organizationId, sampleId);
        createSimilarityIssue(organizationId, sampleId, sourceId, experiment.title());
    }

    private UUID insertRevision(UUID organizationId, UUID sampleId, UUID sourceId, JsonNode fact,
                                JsonNode coordinates, JsonNode permission) {
        var id = UUID.randomUUID();
        var revision = jdbc.queryForObject("SELECT coalesce(max(revision_no),0)+1 FROM ai.sample_revision WHERE organization_id=? AND training_sample_id=?",
                Integer.class, organizationId, sampleId);
        var composition = json.createObjectNode();
        if (fact.has("formulaItems")) composition.set("items", fact.path("formulaItems").deepCopy());
        else if (fact.has("composition")) composition.set("value", fact.path("composition").deepCopy());
        var process = json.createObjectNode();
        if (fact.has("processSteps")) process.set("items", fact.path("processSteps").deepCopy());
        ObjectNode observations = json.createObjectNode();
        if (fact.path("observations").isObject()) observations = ((ObjectNode) fact.path("observations")).deepCopy();
        else if (fact.path("observations").isArray()) observations.set("items", fact.path("observations").deepCopy());
        else if (fact.has("testResults")) observations.set("items", fact.path("testResults").deepCopy());
        var conditions = json.createObjectNode();
        if (fact.has("conditions")) conditions.set("value", fact.path("conditions").deepCopy());
        jdbc.update("""
                INSERT INTO ai.sample_revision(id,organization_id,training_sample_id,sample_source_id,revision_no,
                    composition_jsonb,process_jsonb,conditions_jsonb,observations_jsonb,facts_jsonb,
                    permission_scope_jsonb,source_coordinates_jsonb,fact_hash,status)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'CURRENT')
                """, id, organizationId, sampleId, sourceId, revision, pg(composition), pg(process), pg(conditions),
                pg(observations), pg(fact), pg(permission), pg(coordinates), hashing.hash(fact));
        return id;
    }

    private UUID sampleId(UUID organizationId, String logicalKey) {
        var existing = jdbc.query("SELECT id FROM ai.training_sample WHERE organization_id=? AND logical_sample_key=?",
                (rs, ignored) -> rs.getObject(1, UUID.class), organizationId, logicalKey).stream().findFirst();
        if (existing.isPresent()) return existing.get();
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO ai.training_sample(id,organization_id,logical_sample_key) VALUES(?,?,?)",
                id, organizationId, logicalKey);
        return id;
    }

    private java.util.Optional<UUID> findLinkedSample(UUID organizationId, SourceRef ref) {
        if (ref.confirmedSubmissionId() == null) return java.util.Optional.empty();
        return jdbc.query("""
                SELECT ss.training_sample_id FROM ai.sample_source ss
                JOIN data.confirmed_submission_item i ON i.organization_id=ss.organization_id
                    AND i.id=ss.confirmed_submission_item_id
                WHERE ss.organization_id=? AND i.confirmed_submission_id=?
                    AND i.source_identity_jsonb->>'logicalSampleKey'=?
                ORDER BY ss.created_at DESC LIMIT 1
                """, (rs, ignored) -> rs.getObject(1, UUID.class), organizationId,
                ref.confirmedSubmissionId(), ref.logicalSampleKey()).stream().findFirst();
    }

    private boolean isStaleSource(UUID organizationId, String type, String key, long sequence) {
        var latest = jdbc.queryForObject("SELECT coalesce(max(source_sequence),0) FROM ai.sample_source WHERE organization_id=? AND source_type=? AND source_business_key=?",
                Long.class, organizationId, type, key);
        return latest != null && latest >= sequence;
    }

    private boolean beginReceipt(UUID organizationId, String type, UUID eventId, String businessKey,
                                 long sequence, String eventType, String hash) {
        var inserted = jdbc.update("""
                INSERT INTO ai.fact_projection_receipt(id,organization_id,source_type,source_event_id,
                    source_business_key,source_sequence,event_type,payload_hash)
                VALUES(gen_random_uuid(),?,?,?,?,?,?,?) ON CONFLICT(organization_id,source_type,source_event_id) DO NOTHING
                """, organizationId, type, eventId, businessKey, sequence, eventType, hash);
        return inserted == 1;
    }

    private void completeReceipt(UUID organizationId, String type, UUID eventId, String status, String error) {
        jdbc.update("""
                UPDATE ai.fact_projection_receipt SET status=?,error_message=?,processed_at=now(),updated_at=now()
                WHERE organization_id=? AND source_type=? AND source_event_id=?
                """, status, error, organizationId, type, eventId);
    }

    private void createSimilarityIssue(UUID organizationId, UUID sampleId, UUID sourceId, String title) {
        if (title == null || title.isBlank()) return;
        var other = jdbc.query("""
                SELECT ss.id FROM ai.sample_revision sr JOIN ai.sample_source ss ON ss.id=sr.sample_source_id
                WHERE sr.organization_id=? AND sr.training_sample_id<>?
                    AND lower(sr.facts_jsonb->>'title')=lower(?) ORDER BY sr.created_at DESC LIMIT 1
                """, (rs, ignored) -> rs.getObject(1, UUID.class), organizationId, sampleId, title).stream().findFirst().orElse(null);
        if (other == null) return;
        var evidence = json.createObjectNode().put("matchedField", "title").put("matchedValue", title)
                .put("action", "REVIEW_ONLY_NO_AUTOMATIC_MERGE");
        jdbc.update("""
                INSERT INTO ai.sample_identity_issue(id,organization_id,training_sample_id,left_sample_source_id,
                    right_sample_source_id,issue_type,evidence_jsonb)
                VALUES(gen_random_uuid(),?,?,?,?, 'SIMILAR_SOURCE',?) ON CONFLICT DO NOTHING
                """, organizationId, sampleId, sourceId, other, pg(evidence));
    }

    private boolean canRead(UUID organizationId, UUID actorId, String sourceType) {
        var permission = "EXPERIMENT".equals(sourceType) ? "experiment.view" : "data.view";
        var resource = "EXPERIMENT".equals(sourceType) ? "EXPERIMENT" : "DATA";
        try { return authorization.check(new PermissionCheck(organizationId, actorId, permission, resource, null, "READ")).allowed(); }
        catch (RuntimeException ignored) { return false; }
    }

    private SampleSummary summary(ResultSet rs, int ignored) throws SQLException {
        return new SampleSummary(rs.getObject("id", UUID.class), rs.getString("logical_sample_key"),
                rs.getLong("identity_version"), rs.getString("authority_source_type"), rs.getString("status"),
                rs.getObject("takeover_experiment_id", UUID.class),
                rs.getObject("takeover_experiment_version_id", UUID.class), rs.getLong("revision"),
                rs.getInt("source_count"), rs.getTimestamp("updated_at").toInstant());
    }
    private SampleSource source(ResultSet rs, int ignored) throws SQLException {
        return new SampleSource(rs.getObject("id", UUID.class), rs.getString("source_type"),
                rs.getString("source_business_key"), rs.getString("sample_boundary_id"),
                rs.getString("logical_sample_key"), strings(node(rs, "source_group_keys_jsonb")),
                rs.getString("source_version"),
                rs.getLong("source_sequence"), rs.getString("content_hash"), rs.getString("status"),
                rs.getObject("confirmed_submission_item_id", UUID.class), rs.getObject("experiment_version_id", UUID.class),
                rs.getObject("recognition_job_id", UUID.class) == null
                        ? rs.getObject("experiment_recognition_job_id", UUID.class)
                        : rs.getObject("recognition_job_id", UUID.class),
                rs.getObject("experiment_id", UUID.class),
                rs.getObject("revision_id", UUID.class), nodeNullable(rs, "composition_jsonb"),
                nodeNullable(rs, "process_jsonb"), nodeNullable(rs, "conditions_jsonb"),
                nodeNullable(rs, "observations_jsonb"), nodeNullable(rs, "facts_jsonb"),
                nodeNullable(rs, "permission_scope_jsonb"), nodeNullable(rs, "source_coordinates_jsonb"),
                rs.getString("fact_hash"), rs.getTimestamp("created_at").toInstant(), false);
    }
    private SubmissionItem submissionItem(ResultSet rs, int ignored) throws SQLException {
        return new SubmissionItem(rs.getObject("id", UUID.class), rs.getObject("source_record_id", UUID.class),
                rs.getLong("source_record_version"), node(rs, "source_identity_jsonb"), node(rs, "fact_jsonb"),
                node(rs, "source_coordinates_jsonb"), rs.getString("content_hash"));
    }
    private SourceRef sourceRef(ResultSet rs, int ignored) throws SQLException {
        return new SourceRef(rs.getObject("recognition_job_id", UUID.class),
                rs.getObject("confirmed_submission_id", UUID.class), rs.getString("experiment_boundary_id"),
                rs.getString("sample_boundary_id"), rs.getString("logical_sample_key"),
                strings(node(rs, "source_group_keys_jsonb")), rs.getString("source_file_sha256"),
                node(rs, "source_coordinates_jsonb"), rs.getString("content_hash"));
    }
    private JsonNode node(ResultSet rs, String column) throws SQLException {
        try { return json.readTree(rs.getString(column)); } catch (Exception exception) { throw new SQLException(exception); }
    }
    private JsonNode nodeNullable(ResultSet rs, String column) throws SQLException {
        var value = rs.getString(column); if (value == null) return null;
        try { return json.readTree(value); } catch (Exception exception) { throw new SQLException(exception); }
    }
    private PGobject pg(JsonNode value) {
        try { var result = new PGobject(); result.setType("jsonb"); result.setValue(json.writeValueAsString(value)); return result; }
        catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }
    private UUID uuid(JsonNode value, String field) {
        try { var text = value.path(field).asText(""); return text.isBlank() ? null : UUID.fromString(text); }
        catch (RuntimeException ignored) { return null; }
    }
    private List<String> strings(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        var result = new ArrayList<String>();
        for (var item : value) {
            var text = item.asText("").strip();
            if (!text.isBlank() && !result.contains(text)) result.add(text);
        }
        return List.copyOf(result);
    }
    private String camel(String value) {
        var result = new StringBuilder(); var upper = false;
        for (var c : value.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            result.append(upper ? Character.toUpperCase(c) : c); upper = false;
        }
        return result.toString();
    }

    private record Submission(UUID id, UUID importJobId, int revisionNo, String status,
                              long sourceSequence, String sourceSha256) {}
    private record SubmissionItem(UUID id, UUID sourceRecordId, long sourceRecordVersion,
                                  JsonNode identity, JsonNode fact, JsonNode coordinates, String contentHash) {}
    private record ExperimentFact(String experimentNo, String title, String visibility,
                                  int versionNo, JsonNode editModel) {}
    private record SourceRef(UUID recognitionJobId, UUID confirmedSubmissionId, String experimentBoundaryId,
                             String sampleBoundaryId, String logicalSampleKey, List<String> sourceGroupKeys,
                             String sourceFileSha256, JsonNode coordinates, String contentHash) {}
    private record ExperimentEvent(UUID id, UUID experimentId, String eventType, JsonNode payload) {}
    private record ConfirmedRow(UUID id, String recordKey, int recordIndex, JsonNode raw,
                                JsonNode corrected, JsonNode effective, String sheetId,
                                String sheetName, Integer sourceRowNumber) {}

    public record SampleSummary(UUID id, String logicalSampleKey, long identityVersion,
                                String authoritySourceType, String status, UUID takeoverExperimentId,
                                UUID takeoverExperimentVersionId, long revision, int sourceCount,
                                Instant updatedAt) {}
    public record SampleSource(UUID id, String sourceType, String sourceBusinessKey, String sampleBoundaryId,
                               String logicalSampleKey, List<String> sourceGroupKeys,
                               String sourceVersion, long sourceSequence, String contentHash, String status,
                               UUID confirmedSubmissionItemId, UUID experimentVersionId, UUID recognitionJobId,
                               UUID experimentId, UUID revisionId,
                               JsonNode composition, JsonNode process, JsonNode conditions, JsonNode observations,
                               JsonNode facts, JsonNode permissionScope, JsonNode sourceCoordinates,
                               String factHash, Instant createdAt, boolean redacted) {
        SampleSource redacted(ObjectMapper json) {
            return new SampleSource(id, sourceType, sourceBusinessKey, sampleBoundaryId, logicalSampleKey,
                    sourceGroupKeys, sourceVersion,
                    sourceSequence, contentHash, status, confirmedSubmissionItemId, experimentVersionId,
                    recognitionJobId, experimentId, revisionId, null, null, null, null, null, json.createObjectNode(), null,
                    factHash, createdAt, true);
        }
    }
    public record IdentityIssue(UUID id, String issueType, String status, JsonNode evidence,
                                JsonNode resolution, long revision, Instant createdAt, Instant resolvedAt) {}
    public record SampleDetail(SampleSummary summary, List<SampleSource> sources, List<IdentityIssue> identityIssues) {}
    public record ReconcileResult(int projectedSubmissionItems, int projectedExperimentSamples, int scannedSources) {}
}
