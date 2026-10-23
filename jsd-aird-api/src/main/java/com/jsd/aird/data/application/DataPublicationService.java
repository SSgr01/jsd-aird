package com.jsd.aird.data.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.ops.application.port.AuditLogFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class DataPublicationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DataRepository repository;
    private final DataImportService imports;
    private final AuthorizationService authorization;
    private final AuditLogFacade audit;
    private ExperimentAssemblyService assembly;
    @org.springframework.beans.factory.annotation.Autowired
    public void setAssembly(ExperimentAssemblyService assembly) { this.assembly = assembly; }

    public DataPublicationService(JdbcTemplate jdbc, ObjectMapper json, DataRepository repository,
            DataImportService imports, AuthorizationService authorization, AuditLogFacade audit) {
        this.jdbc = jdbc; this.json = json; this.repository = repository;
        this.imports = imports; this.authorization = authorization; this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Workspace get(UUID jobId) {
        require(jobId, "data.view", false);
        return workspace(jobId);
    }

    @Transactional
    public Workspace confirm(UUID jobId) {
        var job = require(jobId, "data.update", true);
        ensure(jobId);
        var state = status(jobId);
        check(Set.of("DRAFT", "RETURNED", "CONFIRMED").contains(state), "当前状态不能确认数据");
        check(job.templateVersionId() != null && !isFreeform(jobId), "请在自由识别工作区确认数据");
        check(!"COMPLETED".equals(job.status()), "请先创建修订，再修改已发布数据");
        imports.validateInternal(org(), jobId);
        var current = repository.findJob(org(), jobId).orElseThrow();
        check("WAITING_CONFIRM".equals(current.status()), "请先处理字段映射和校验问题，再确认数据");
        check(!Set.of("INCOMPATIBLE", "REVIEW_REQUIRED").contains(Objects.toString(current.compatibilityStatus(), "")),
                "模板结构仍需处理，请在映射面板核对数据区域");
        var rows = repository.listRows(org(), jobId);
        check(rows.stream().anyMatch(row -> !row.excluded()), "没有可发布记录，请检查工作表选择和字段映射");
        check(rows.stream().noneMatch(row -> !row.excluded() && "BLOCKED".equals(row.status())), "仍有阻断记录未修正");
        insertConfirmation(jobId, payload(jobId), null);
        return workspace(jobId);
    }

    /** Called in the same transaction as free-recognition finalization. Parsing is unchanged. */
    @Transactional
    public void confirmRecognition(UUID jobId, UUID submissionId) {
        require(jobId, "data.update", true);
        ensure(jobId);
        check(Set.of("DRAFT", "RETURNED", "CONFIRMED").contains(status(jobId)), "当前状态不能再次确认");
        insertConfirmation(jobId, payload(jobId), submissionId);
    }

    @Transactional
    public Workspace submit(UUID jobId, UUID reviewerId) {
        require(jobId, "data.submit", true);
        ensure(jobId);
        var state = status(jobId);
        var previous = currentSnapshot(jobId);
        var needsConfirmation = Set.of("DRAFT", "RETURNED").contains(state)
                || ("CONFIRMED".equals(state) && (previous == null || !previous.contentHash().equals(hash(payload(jobId)))));
        if (needsConfirmation && !isFreeform(jobId)) {
            // Like the experiment workbench, submitting a saved draft confirms its immutable snapshot
            // in the same transaction. A failed submission must not leave a partial confirmation.
            confirm(jobId);
        }
        check("CONFIRMED".equals(status(jobId)), "请先确认当前数据，修改后需要重新确认");
        verifySnapshot(jobId);
        check(reviewerId != null, "请选择审核人");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM iam.app_user WHERE organization_id=? AND id=? AND status='ACTIVE')", Boolean.class, org(), reviewerId)), "审核人不存在或已停用");
        jdbc.update("UPDATE data.publication SET reviewer_id=? WHERE organization_id=? AND import_job_id=?", reviewerId, org(), jobId);
        authorization.require(new PermissionCheck(org(), reviewerId, "data.approve", "DATA", jobId, "WRITE"));
        setStatus(jobId, "PENDING_REVIEW");
        event(jobId, "SUBMITTED", null);
        return workspace(jobId);
    }

    @Transactional
    public Workspace withdraw(UUID jobId) {
        require(jobId, "data.submit", true);
        check(Set.of("PENDING_REVIEW", "APPROVED").contains(status(jobId)), "当前数据不能撤回");
        setStatus(jobId, "CONFIRMED");
        event(jobId, "WITHDRAWN", null);
        return workspace(jobId);
    }

    @Transactional
    public Workspace review(UUID jobId, UUID snapshotId, boolean approved, String comment) {
        require(jobId, "data.approve", true);
        check("PENDING_REVIEW".equals(status(jobId)), "当前数据不在审核中，请刷新后查看");
        var snapshot = verifySnapshot(jobId);
        check(snapshot.id().equals(snapshotId), "审核快照已变化，请刷新后重新核对");
        if (!approved) {
            check(comment != null && !comment.isBlank(), "退回时必须填写原因");
            setStatus(jobId, "RETURNED");
            // A recognition result can be corrected after return without re-parsing the source.
            if (isFreeform(jobId)) repository.updateJobStatus(org(), jobId, "WAITING_MAPPING", 100, "WAITING_MAPPING", null);
            event(jobId, "RETURNED", comment.strip());
            return workspace(jobId);
        }
        setStatus(jobId, "APPROVED");
        event(jobId, "APPROVED", comment);
        return workspace(jobId);
    }

    @Transactional
    public Workspace publish(UUID jobId) {
        require(jobId, "data.approve", true);
        check("APPROVED".equals(status(jobId)), "请先完成审核，再发布导入数据");
        var snapshot = verifySnapshot(jobId);
        UUID submissionId = snapshot.confirmedSubmissionId();
        if (submissionId == null) {
            imports.commit(jobId);
            submissionId = createGuidedSubmission(jobId, snapshot);
        } else {
            publishRecognition(jobId, submissionId);
        }
        jdbc.update("DELETE FROM data.published_mapping WHERE import_job_id=?", jobId);
        jdbc.update("INSERT INTO data.published_mapping SELECT * FROM data.import_mapping WHERE import_job_id=?", jobId);
        jdbc.update("INSERT INTO data.publication_release(snapshot_id,confirmed_submission_id,published_by) VALUES(?,?,?)",
                snapshot.id(),submissionId,ActorContext.required().userId());
        // Switch the evidence head only after the new read projection is ready.
        jdbc.update("""
                UPDATE data.confirmed_submission SET status='SUPERSEDED'
                WHERE organization_id=? AND import_job_id=? AND status='CONFIRMED' AND id<>?
                """, org(), jobId, submissionId);
        jdbc.update("""
                INSERT INTO data.confirmed_submission_head(organization_id,import_job_id,confirmed_submission_id)
                VALUES(?,?,?) ON CONFLICT(organization_id,import_job_id) DO UPDATE SET
                  confirmed_submission_id=excluded.confirmed_submission_id,
                  revision=data.confirmed_submission_head.revision+1,updated_at=now()
                """, org(), jobId, submissionId);
        jdbc.update("""
                UPDATE data.publication SET status='PUBLISHED',published_snapshot_id=confirmed_snapshot_id,published_enabled=true,updated_at=now()
                WHERE organization_id=? AND import_job_id=?
                """, org(), jobId);
        repository.updateJobStatus(org(), jobId, "COMPLETED", 100, "COMPLETED", null);
        event(jobId, "PUBLISHED", null);
        var publishedJob = repository.findJob(org(),jobId).orElseThrow();
        if (assembly != null && !isFreeform(jobId) && "EXPERIMENT_DRAFT".equals(publishedJob.importPurpose())
                && authorization.check(new PermissionCheck(org(),ActorContext.required().userId(),"experiment.create","EXPERIMENT",null,"WRITE")).allowed()) {
            var plan = assembly.preview(jobId);
            if (plan.readyCount() > 0 && plan.reviewCount() == 0 && plan.blockedCount() == 0)
                assembly.sync(jobId,publishedJob.targetExperimentCategoryId(),null);
        }
        return workspace(jobId);
    }

    @Transactional
    public Workspace setPublishedEnabled(UUID jobId, boolean enabled) {
        var job = require(jobId, "data.update", true);
        ensure(jobId);
        var legacyPublished = !enabled && "DRAFT".equals(status(jobId)) && "COMPLETED".equals(job.status());
        check(legacyPublished || (enabled ? "DISABLED" : "PUBLISHED").equals(status(jobId)), "当前状态不能" + (enabled ? "恢复使用" : "停用"));
        jdbc.update("UPDATE data.publication SET status=?,published_enabled=?,updated_at=now() WHERE organization_id=? AND import_job_id=?",
                enabled ? "PUBLISHED" : "DISABLED", enabled, org(), jobId);
        event(jobId, enabled ? "RESTORED" : "DISABLED", null);
        return workspace(jobId);
    }

    @Transactional
    public Workspace revise(UUID jobId) {
        var job = require(jobId, "data.update", true);
        ensure(jobId);
        check(Set.of("PUBLISHED", "DISABLED").contains(status(jobId)) || ("DRAFT".equals(status(jobId)) && "COMPLETED".equals(job.status())),
                "仅已发布或历史已入库数据可创建修订");
        jdbc.update("""
                UPDATE data.publication SET status='DRAFT',confirmed_snapshot_id=NULL,updated_at=now()
                WHERE organization_id=? AND import_job_id=?
                """, org(), jobId);
        repository.updateJobStatus(org(), jobId, isFreeform(jobId) ? "WAITING_MAPPING" : "WAITING_CONFIRM", 100,
                isFreeform(jobId) ? "WAITING_MAPPING" : "WAITING_CONFIRM", null);
        event(jobId, "REVISION_CREATED", "修订需重新审核和发布，原发布版本及启停状态保持");
        return workspace(jobId);
    }

    @Transactional(readOnly = true)
    public JsonNode snapshot(UUID jobId, UUID snapshotId) {
        require(jobId, "data.view", false);
        return jdbc.query("SELECT payload_jsonb::text FROM data.publication_snapshot WHERE organization_id=? AND import_job_id=? AND id=?",
                (rs, n) -> parse(rs.getString(1)), org(), jobId, snapshotId).stream().findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "确认快照不存在"));
    }

    @Transactional(readOnly = true)
    public List<FormalRecord> records(UUID jobId) {
        require(jobId, "data.view", false);
        return formalRecords(jobId);
    }

    @Transactional(readOnly = true)
    public List<Reviewer> reviewers(UUID jobId) {
        require(jobId, "data.view", false);
        return jdbc.query("SELECT id,coalesce(nullif(display_name,''),username) FROM iam.app_user WHERE organization_id=? AND status='ACTIVE' ORDER BY display_name,username",
                (rs, n) -> new Reviewer(rs.getObject(1, UUID.class), rs.getString(2)), org()).stream()
                .filter(user -> authorization.check(new PermissionCheck(org(), user.id(), "data.approve", "DATA", null, "WRITE")).allowed()).toList();
    }

    @Transactional
    public void govern(UUID jobId, UUID recordId, String name, String nextStatus, String reason) {
        require(jobId, "data.update", true);
        var record = formalRecords(jobId).stream().filter(item -> item.id().equals(recordId)).findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "正式记录不存在"));
        check(name != null && !name.isBlank() && name.strip().length() <= 200, "记录名称须为 1 至 200 个字符");
        check(Set.of("ACTIVE", "DISABLED").contains(nextStatus), "记录仅支持有效、停用两种状态");
        if (!nextStatus.equals(record.status())) check(reason != null && !reason.isBlank(), "变更记录状态必须填写原因");
        jdbc.update("""
                INSERT INTO data.record_governance(organization_id,import_job_id,record_id,display_name,status,reason,updated_by)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(organization_id,record_id) DO UPDATE SET
                  display_name=excluded.display_name,status=excluded.status,reason=excluded.reason,
                  updated_by=excluded.updated_by,updated_at=now()
                """, org(), jobId, recordId, name.strip(), nextStatus, reason, ActorContext.required().userId());
        event(jobId, "RECORD_UPDATED", record.name() + " → " + name.strip() + "；" + ("ACTIVE".equals(nextStatus) ? "有效" : "停用")
                + (reason == null ? "" : "；" + reason));
    }

    @Transactional(readOnly = true)
    public byte[] export(UUID jobId) {
        require(jobId, "data.export", false);
        var job = repository.findJob(org(), jobId).orElseThrow();
        var version = jdbc.query("""
                SELECT s.version_no FROM data.publication p JOIN data.publication_snapshot s ON s.id=p.published_snapshot_id
                WHERE p.organization_id=? AND p.import_job_id=?
                """, (rs, n) -> "V" + rs.getInt(1), org(), jobId).stream().findFirst().orElse("历史入库");
        var out = new StringBuilder("\ufeff记录ID,记录名称,状态,发布版本,字段,值,单位,来源文件,工作表,位置\r\n");
        for (var record : formalRecords(jobId)) {
            var values = record.values();
            if (values.has("recognizedItems") && values.path("recognizedItems").isArray()) {
                for (var item : values.path("recognizedItems")) appendCsv(out, record.id(), record.name(), record.status(), version,
                        item.path("labelPath").asText(item.path("fieldCode").asText()),
                        nodeValue(item.path("effectiveValue")),
                        item.path("unit").asText(item.path("standardUnit").asText()), job.sourceFileName(), "", item.path("sourceCoordinate"));
            } else {
                var fields = values.fields();
                while (fields.hasNext()) {
                    var field = fields.next(); var value = field.getValue();
                    appendCsv(out, record.id(), record.name(), record.status(), version,
                            value.path("labelPath").asText(value.path("fieldName").asText(value.path("fieldCode").asText(field.getKey()))),
                            nodeValue(value.has("normalizedValue") ? value.get("normalizedValue") : value),
                            value.path("normalizedUnit").asText(), job.sourceFileName(), record.sheetName(),
                            sourceAddress(record.id(), value));
                }
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void insertConfirmation(UUID jobId, JsonNode payload, UUID submissionId) {
        var hash = hash(payload);
        var current = currentSnapshot(jobId);
        if ("CONFIRMED".equals(status(jobId)) && current != null && current.contentHash().equals(hash)) return;
        var id = UUID.randomUUID();
        var version = jdbc.queryForObject("SELECT coalesce(max(version_no),0)+1 FROM data.publication_snapshot WHERE organization_id=? AND import_job_id=?",
                Integer.class, org(), jobId);
        jdbc.update("""
                INSERT INTO data.publication_snapshot(id,organization_id,import_job_id,version_no,content_hash,payload_jsonb,confirmed_submission_id,confirmed_by)
                VALUES(?,?,?,?,?,?::jsonb,?,?)
                """, id, org(), jobId, version, hash, payload.toString(), submissionId, ActorContext.required().userId());
        jdbc.update("UPDATE data.publication SET status='CONFIRMED',confirmed_snapshot_id=?,updated_at=now() WHERE organization_id=? AND import_job_id=?",
                id, org(), jobId);
        event(jobId, "CONFIRMED", null);
    }

    private JsonNode payload(UUID jobId) {
        var job = repository.findJob(org(), jobId).orElseThrow();
        var payload = json.createObjectNode();
        payload.put("sourceFileId", job.sourceFileId().toString()).put("sourceFileName", job.sourceFileName()).put("sourceSha256", job.sourceSha256())
                .put("templateVersionId", Objects.toString(job.templateVersionId(), "")).put("contractHash", job.contractHash());
        payload.set("projectRelations", json.valueToTree(jdbc.queryForList("""
                SELECT l.project_id,l.stage_id,l.task_id,p.name project_name,s.name stage_name,t.name task_name
                FROM core.project_resource_link l LEFT JOIN mdm.project p ON p.id=l.project_id
                LEFT JOIN mdm.project_stage s ON s.id=l.stage_id LEFT JOIN mdm.project_task t ON t.id=l.task_id
                WHERE l.organization_id=? AND l.resource_type='DATA_IMPORT_JOB' AND l.resource_id=?
                ORDER BY l.project_id,l.stage_id,l.task_id
                """, org(), jobId)));
        if (isFreeform(jobId)) {
            payload.set("recognition", jdbc.query("""
                    SELECT jsonb_build_object('workspace',recognition_workspace_jsonb,'boundaries',experiment_boundaries_jsonb)::text
                    FROM data.import_job WHERE organization_id=? AND id=?
                    """, (rs, n) -> parse(rs.getString(1)), org(), jobId).getFirst());
        } else {
            payload.set("sheets", json.valueToTree(repository.listSheets(org(), jobId)));
            payload.set("mappings", json.valueToTree(repository.listMappings(org(), jobId)));
            payload.set("rows", json.valueToTree(repository.listRows(org(), jobId)));
            payload.set("issues", json.valueToTree(repository.listIssues(org(), jobId)));
        }
        return payload;
    }

    private Snapshot verifySnapshot(UUID jobId) {
        var current = currentSnapshot(jobId);
        check(current != null, "请先确认当前数据");
        check(current.contentHash().equals(hash(payload(jobId))), "数据、映射、工作表或项目关系已变化，请重新确认后送审");
        return current;
    }

    private UUID createGuidedSubmission(UUID jobId, Snapshot snapshot) {
        var submissionId = UUID.randomUUID();
        var revision = jdbc.queryForObject("SELECT coalesce(max(revision_no),0)+1 FROM data.confirmed_submission WHERE organization_id=? AND import_job_id=?",
                Integer.class, org(), jobId);
        jdbc.update("""
                INSERT INTO data.confirmed_submission(id,organization_id,import_job_id,revision_no,status,content_hash,confirmed_by,mapping_contract_jsonb,source_sequence)
                SELECT ?,?,?,?,'CONFIRMED',?,?,?::jsonb,source_sequence FROM data.import_job WHERE organization_id=? AND id=?
                """, submissionId, org(), jobId, revision, snapshot.contentHash(), snapshot.confirmedBy(),
                json.createObjectNode().set("mappings", snapshot(jobId, snapshot.id()).path("mappings")).toString(), org(), jobId);
        jdbc.update("""
                INSERT INTO data.confirmed_submission_item(id,organization_id,confirmed_submission_id,source_record_id,
                  source_record_version,source_file_id,sheet_name,row_coordinate,mapping_version_id,
                  source_identity_jsonb,raw_fact_jsonb,corrected_fact_jsonb,fact_jsonb,source_coordinates_jsonb,source_file_sha256,content_hash)
                SELECT gen_random_uuid(),r.organization_id,?,r.id,?,j.source_file_id,r.sheet_name,r.source_row_number::text,NULL,
                  jsonb_build_object('recordKey',r.record_key),r.raw_data_jsonb,r.corrected_data_jsonb,r.effective_data_jsonb,
                  jsonb_build_object('anchors',coalesce((SELECT jsonb_agg(to_jsonb(a)) FROM data.source_anchor a WHERE a.record_id=r.id),'[]'::jsonb)),j.source_sha256,?
                FROM data.data_record r JOIN data.import_job j ON j.id=r.import_job_id
                WHERE r.organization_id=? AND r.import_job_id=?
                """, submissionId, revision, snapshot.contentHash(), org(), jobId);
        return submissionId;
    }

    private void publishRecognition(UUID jobId, UUID submissionId) {
        jdbc.update("DELETE FROM data.data_record WHERE organization_id=? AND import_job_id=?", org(), jobId);
        jdbc.update("""
                INSERT INTO data.data_record(id,organization_id,import_job_id,record_key,record_index,raw_data_jsonb,
                    normalized_data_jsonb,corrected_data_jsonb,effective_data_jsonb,quality_status,synthetic_key)
                SELECT source_record_id,organization_id,?,coalesce(source_identity_jsonb->>'sampleBoundaryId',id::text),
                    row_number() OVER(ORDER BY source_identity_jsonb->>'sampleBoundaryId',id),raw_fact_jsonb,fact_jsonb,
                    corrected_fact_jsonb,fact_jsonb,'VALID',false
                FROM data.confirmed_submission_item WHERE organization_id=? AND confirmed_submission_id=?
                """, jobId, org(), submissionId);
    }

    private Workspace workspace(UUID jobId) {
        var states = jdbc.query("SELECT status,published_snapshot_id,legacy_published FROM data.publication WHERE organization_id=? AND import_job_id=?",
                (rs, n) -> new State(rs.getString(1), rs.getObject(2, UUID.class), rs.getBoolean(3)), org(), jobId);
        var state = states.isEmpty() ? new State("DRAFT", null, Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM data.data_record WHERE organization_id=? AND import_job_id=?)",Boolean.class,org(),jobId))) : states.getFirst();
        var snapshots = jdbc.query("""
                SELECT id,version_no,content_hash,confirmed_submission_id,confirmed_by,confirmed_at
                FROM data.publication_snapshot WHERE organization_id=? AND import_job_id=? ORDER BY version_no DESC
                """, (rs, n) -> new Snapshot(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getTimestamp(6).toInstant()), org(), jobId);
        var events = jdbc.query("""
                SELECT id,snapshot_id,action,actor_name,comment,created_at FROM data.publication_event
                WHERE organization_id=? AND import_job_id=? ORDER BY created_at DESC,id DESC
                """, (rs, n) -> new Event(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()), org(), jobId);
        var reviewer = jdbc.query("SELECT u.id,coalesce(nullif(u.display_name,''),u.username) FROM data.publication p JOIN iam.app_user u ON u.id=p.reviewer_id WHERE p.organization_id=? AND p.import_job_id=?",
                (rs,n) -> new Reviewer(rs.getObject(1,UUID.class),rs.getString(2)),org(),jobId).stream().findFirst().orElse(null);
        var publishedEnabled = jdbc.query("SELECT published_enabled FROM data.publication WHERE organization_id=? AND import_job_id=?",
                (rs,n) -> rs.getBoolean(1),org(),jobId).stream().findFirst().orElse(true);
        return new Workspace(state.status(), currentSnapshot(jobId), state.publishedSnapshotId(), state.legacyPublished(), publishedEnabled, reviewer, snapshots, events);
    }

    private List<FormalRecord> formalRecords(UUID jobId) {
        return jdbc.query("""
                SELECT r.id,r.record_key,coalesce(g.display_name,CASE WHEN r.synthetic_key
                         THEN j.source_file_name || ' · 记录' || r.record_index ELSE r.record_key END),coalesce(g.status,'ACTIVE'),g.reason,
                       r.sheet_name,r.source_row_number,r.effective_data_jsonb::text,
                       coalesce((SELECT jsonb_agg(jsonb_build_object('bindingId',a.binding_id,'valuePath',a.value_path,
                         'sheetName',a.sheet_name,'address',a.cell_address)) FROM data.source_anchor a WHERE a.record_id=r.id),'[]'::jsonb)::text
                FROM data.data_record r JOIN data.import_job j ON j.id=r.import_job_id
                LEFT JOIN data.record_governance g ON g.organization_id=r.organization_id AND g.record_id=r.id
                WHERE r.organization_id=? AND r.import_job_id=? ORDER BY r.record_index
                """, (rs, n) -> new FormalRecord(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), (Integer) rs.getObject(7), parse(rs.getString(8)),parse(rs.getString(9))), org(), jobId);
    }

    private String sourceAddress(UUID recordId, JsonNode value) {
        return jdbc.query("SELECT cell_address FROM data.source_anchor WHERE record_id=? AND binding_id=? AND value_path=? ORDER BY id LIMIT 1",
                (rs, n) -> Objects.toString(rs.getString(1), ""), recordId, value.path("bindingId").asText(),
                value.path("valuePath").asText()).stream().findFirst().orElse("");
    }

    private DataRepository.Job require(UUID jobId, String permission, boolean lock) {
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(org(), actor.userId(), permission, "DATA", jobId, permission.equals("data.view") || permission.equals("data.export") ? "READ" : "WRITE"));
        var job = (lock ? repository.findJobForUpdate(org(), jobId) : repository.findJob(org(), jobId))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "数据来源不存在"));
        check("DATA_CENTER".equals(repository.findSourceOwner(org(), jobId).orElse("")), "该来源不属于数据中心");
        if (lock && isFreeform(jobId)) {
            throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT,
                    "历史自由识别记录已设为只读，只能查看和下载原文件");
        }
        return job;
    }

    private void ensure(UUID jobId) {
        var inserted = jdbc.update("""
                INSERT INTO data.publication(organization_id,import_job_id,legacy_published)
                SELECT ?,?,EXISTS(SELECT 1 FROM data.data_record WHERE organization_id=? AND import_job_id=?)
                ON CONFLICT DO NOTHING
                """, org(), jobId, org(), jobId);
        if (inserted > 0) jdbc.update("""
                INSERT INTO data.published_mapping SELECT m.* FROM data.import_mapping m
                WHERE m.import_job_id=? AND EXISTS(SELECT 1 FROM data.publication p
                  WHERE p.organization_id=? AND p.import_job_id=? AND p.legacy_published)
                """, jobId, org(), jobId);
    }
    private boolean isFreeform(UUID jobId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT recognition_mode='FREEFORM' FROM data.import_job WHERE organization_id=? AND id=?", Boolean.class, org(), jobId));
    }
    private Snapshot currentSnapshot(UUID jobId) {
        return jdbc.query("""
                SELECT s.id,s.version_no,s.content_hash,s.confirmed_submission_id,s.confirmed_by,s.confirmed_at
                FROM data.publication p JOIN data.publication_snapshot s ON s.id=p.confirmed_snapshot_id
                WHERE p.organization_id=? AND p.import_job_id=?
                """, (rs, n) -> new Snapshot(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class), rs.getTimestamp(6).toInstant()), org(), jobId).stream().findFirst().orElse(null);
    }
    private String status(UUID jobId) {
        return jdbc.query("SELECT status FROM data.publication WHERE organization_id=? AND import_job_id=?",
                (rs, n) -> rs.getString(1), org(), jobId).stream().findFirst().orElse("DRAFT");
    }
    private void setStatus(UUID jobId, String status) {
        jdbc.update("UPDATE data.publication SET status=?,updated_at=now() WHERE organization_id=? AND import_job_id=?", status, org(), jobId);
    }
    private void event(UUID jobId, String action, String comment) {
        var actor = ActorContext.required(); var snapshot = currentSnapshot(jobId);
        jdbc.update("""
                INSERT INTO data.publication_event(id,organization_id,import_job_id,snapshot_id,action,actor_id,actor_name,comment)
                VALUES(?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), org(), jobId, snapshot == null ? null : snapshot.id(), action, actor.userId(), actor.username(), comment);
        audit.append(org(), actor.userId(), "DATA_" + action, "DATA_IMPORT_JOB", jobId,
                json.createObjectNode().put("comment", comment).put("snapshotId", snapshot == null ? null : snapshot.id().toString()));
    }
    private UUID org() { return ActorContext.required().organizationId(); }
    private JsonNode parse(String value) { try { return json.readTree(value); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    static void check(boolean condition, String message) { if (!condition) throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, message); }
    String hash(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical(value)))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode(); var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, canonical(node.get(name)))); return result;
        }
        if (node.isArray()) { var result = json.createArrayNode(); node.forEach(item -> result.add(canonical(item))); return result; }
        return node;
    }
    private static Object nodeValue(JsonNode node) { return node == null || node.isNull() ? "" : node.isValueNode() ? node.asText() : node.toString(); }
    static void appendCsv(StringBuilder out, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            var value = Objects.toString(values[i], "");
            // CSV opened in Excel must not execute imported values as formulas.
            var start=value.stripLeading();
            if (!start.isEmpty() && "=+@-".indexOf(start.charAt(0)) >= 0
                    && !start.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")) value = "'" + value;
            out.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }
    private record State(String status, UUID publishedSnapshotId, boolean legacyPublished) {}
    public record Snapshot(UUID id, int versionNo, String contentHash, UUID confirmedSubmissionId, UUID confirmedBy, Instant confirmedAt) {}
    public record Event(UUID id, UUID snapshotId, String action, String actorName, String comment, Instant createdAt) {}
    public record Reviewer(UUID id, String name) {}
    public record Workspace(String status, Snapshot confirmedSnapshot, UUID publishedSnapshotId, boolean legacyPublished, boolean publishedEnabled, Reviewer reviewer, List<Snapshot> versions, List<Event> events) {}
    public record FormalRecord(UUID id, String recordKey, String name, String status, String reason, String sheetName, Integer rowNumber, JsonNode values, JsonNode locations) {}
}
