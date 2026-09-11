package com.jsd.aird.mfg.upload.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.postgresql.util.PGobject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.mfg.upload.application.port.ProductionUploadRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductionUploadRepository implements ProductionUploadRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcProductionUploadRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(NewUpload upload) {
        jdbcTemplate.update("""
                INSERT INTO mfg.production_upload (
                    id, organization_id, file_id, production_name, order_no,
                    product_name, category, manufacture_date,
                    project_id, project_name, stage_id, stage_name, task_id, task_name,
                    visibility, source_type, selected_template_version_id, status, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?)
                """,
                upload.id(), upload.organizationId(), upload.fileId(), upload.productionName(),
                upload.orderNo(), upload.productName(), upload.category(), upload.manufactureDate(),
                upload.projectId(), upload.projectName(), upload.stageId(), upload.stageName(),
                upload.taskId(), upload.taskName(), upload.visibility(), upload.sourceType(),
                upload.selectedTemplateVersionId(), upload.actorId());
    }

    @Override
    public void attachAsyncJob(UUID uploadId, UUID asyncJobId) {
        jdbcTemplate.update("UPDATE mfg.production_upload SET async_job_id = ?, updated_at = now() WHERE id = ?",
                asyncJobId, uploadId);
    }

    @Override
    public void queueRecognition(UUID uploadId, UUID selectedTemplateVersionId) {
        jdbcTemplate.update("""
                UPDATE mfg.production_upload
                SET status = 'QUEUED', recognition_progress = 0, current_stage = 'QUEUED',
                    selected_template_version_id = ?, match_mode = NULL, template_match_score = NULL,
                    recognition_result_jsonb = NULL, structure_summary_jsonb = NULL,
                    failure_message = NULL, recognized_at = NULL, updated_at = now()
                WHERE id = ? AND status <> 'DELETED'
                """, selectedTemplateVersionId, uploadId);
    }

    @Override
    public void updateRecognitionProgress(UUID uploadId, String status, int progress, String stage) {
        jdbcTemplate.update("""
                UPDATE mfg.production_upload
                SET status = ?, recognition_progress = GREATEST(recognition_progress, ?),
                    current_stage = ?, updated_at = now()
                WHERE id = ? AND status NOT IN ('DELETED', 'PUBLISHED')
                """, status, progress, stage, uploadId);
    }

    @Override
    public void completeRecognition(UUID uploadId, UUID selectedTemplateVersionId, String matchMode,
                                    double matchScore, JsonNode structureSummary, JsonNode workbookSnapshot,
                                    JsonNode recognitionResult) {
        jdbcTemplate.update("""
                UPDATE mfg.production_upload
                -- Recognition produces an editable production-order draft.  A
                -- human review is not a separate upload status; review/editing
                -- happens in the workspace and publication creates the first
                -- version.  Keep REVIEW_REQUIRED readable below for records
                -- written by older deployments.
                SET status = 'SAVED', recognition_progress = 100,
                    current_stage = 'COMPLETED', selected_template_version_id = ?,
                    match_mode = ?, template_match_score = ?, structure_summary_jsonb = ?,
                    workbook_snapshot_jsonb = ?, recognition_result_jsonb = ?,
                    failure_message = NULL, recognized_at = now(), updated_at = now()
                WHERE id = ? AND status <> 'DELETED'
                """, selectedTemplateVersionId, matchMode, matchScore, pg(structureSummary),
                pg(workbookSnapshot), pg(recognitionResult), uploadId);
    }

    @Override
    public void failRecognition(UUID uploadId, String message) {
        jdbcTemplate.update("""
                UPDATE mfg.production_upload
                SET status = 'FAILED', current_stage = 'FAILED', failure_message = ?, updated_at = now()
                WHERE id = ? AND status NOT IN ('DELETED', 'PUBLISHED')
                """, message == null ? "XLSX 识别失败" : message.substring(0, Math.min(2000, message.length())), uploadId);
    }

    @Override
    public void replaceRecognitionFields(UUID uploadId, List<RecognitionField> fields) {
        jdbcTemplate.update("DELETE FROM mfg.production_upload_field WHERE production_upload_id = ?", uploadId);
        if (fields == null || fields.isEmpty()) return;
        jdbcTemplate.batchUpdate("""
                INSERT INTO mfg.production_upload_field (
                    id, production_upload_id, item_key, item_kind, binding_id, field_code,
                    data_path, record_index, raw_value_jsonb, normalized_value_jsonb,
                    source_locator_jsonb, confidence, review_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, fields, fields.size(), (statement, item) -> {
            statement.setObject(1, item.id());
            statement.setObject(2, uploadId);
            statement.setString(3, item.itemKey());
            statement.setString(4, item.itemKind());
            statement.setString(5, item.bindingId());
            statement.setString(6, item.fieldCode());
            statement.setString(7, item.dataPath());
            if (item.recordIndex() == null) statement.setNull(8, java.sql.Types.INTEGER);
            else statement.setInt(8, item.recordIndex());
            setJson(statement, 9, item.rawValue());
            setJson(statement, 10, item.normalizedValue());
            setJson(statement, 11, item.sourceLocator());
            statement.setDouble(12, item.confidence());
            statement.setString(13, item.reviewStatus());
        });
    }

    @Override
    public void updateRecognizedMetadata(UUID uploadId, String orderNo, String productName,
                                         String category, java.time.LocalDate manufactureDate) {
        jdbcTemplate.update("""
                UPDATE mfg.production_upload
                SET order_no = COALESCE(NULLIF(?, ''), order_no),
                    product_name = COALESCE(NULLIF(?, ''), product_name),
                    category = COALESCE(NULLIF(?, ''), category),
                    manufacture_date = COALESCE(?, manufacture_date), updated_at = now()
                WHERE id = ? AND status <> 'DELETED'
                """, orderNo, productName, category, manufactureDate, uploadId);
    }

    @Override
    public List<RecognitionFieldView> listRecognitionFields(UUID organizationId, UUID uploadId) {
        return jdbcTemplate.query("""
                SELECT f.* FROM mfg.production_upload_field f
                JOIN mfg.production_upload p ON p.id = f.production_upload_id
                WHERE p.organization_id = ? AND p.id = ?
                ORDER BY f.record_index NULLS FIRST, f.created_at, f.item_key
                """, (rs, rowNum) -> new RecognitionFieldView(
                rs.getObject("id", UUID.class), rs.getString("item_key"), rs.getString("item_kind"),
                rs.getString("binding_id"), rs.getString("field_code"), rs.getString("data_path"),
                (Integer) rs.getObject("record_index"), jsonNullable(rs.getString("raw_value_jsonb")),
                jsonNullable(rs.getString("normalized_value_jsonb")), jsonNullable(rs.getString("source_locator_jsonb")),
                rs.getDouble("confidence"), rs.getString("review_status")));
    }

    private void setJson(java.sql.PreparedStatement statement, int index, JsonNode value)
            throws java.sql.SQLException {
        if (value == null || value.isNull()) statement.setNull(index, java.sql.Types.OTHER);
        else statement.setObject(index, pg(value));
    }

    @Override
    public Optional<UploadView> find(UUID organizationId, UUID uploadId) {
        return jdbcTemplate.query(selectSql() + " WHERE pu.organization_id = ? AND pu.id = ?",
                (rs, rowNum) -> map(rs), organizationId, uploadId).stream().findFirst();
    }

    @Override
    public Optional<UUID> findAsyncJobId(UUID organizationId, UUID uploadId) {
        return jdbcTemplate.query(
                "SELECT async_job_id FROM mfg.production_upload WHERE organization_id = ? AND id = ?",
                (rs, rowNum) -> rs.getObject("async_job_id", UUID.class), organizationId, uploadId)
                .stream().findFirst();
    }

    @Override
    public Optional<UploadView> findActiveBySha256(UUID organizationId, String sha256) {
        if (sha256 == null || sha256.isBlank()) return Optional.empty();
        return jdbcTemplate.query(selectSql()
                        + " WHERE pu.organization_id = ? AND fo.sha256 = ? AND pu.status <> 'DELETED'"
                        + " ORDER BY pu.created_at DESC LIMIT 1",
                (rs, rowNum) -> map(rs), organizationId, sha256).stream().findFirst();
    }

    @Override
    public PageResult<UploadView> list(UUID organizationId, String keyword, String status, UUID projectId,
                                      boolean viewableOnly, int page, int size) {
        var where = new StringBuilder(" WHERE pu.organization_id = ? ");
        var args = new ArrayList<Object>();
        args.add(organizationId);
        if (projectId != null) {
            where.append(" AND pu.project_id = ?");
            args.add(projectId);
        }
        if (status != null && !status.isBlank()) {
            if ("PARSED".equalsIgnoreCase(status)) {
                // REVIEW_REQUIRED is the legacy name used before recognition
                // completion was folded into the saved-draft state. PARSED is
                // the upload-list filter that groups every completed record.
                where.append(" AND pu.status IN ('SAVED', 'PUBLISHED', 'REVIEW_REQUIRED')");
            } else if ("SAVED".equalsIgnoreCase(status)) {
                // Preserve the existing saved-draft filter semantics for
                // callers that still need to distinguish published records.
                where.append(" AND pu.status IN ('SAVED', 'REVIEW_REQUIRED')");
            } else {
                where.append(" AND pu.status = ?");
                args.add(status);
            }
        } else {
            where.append(" AND pu.status <> 'DELETED'");
        }
        if (viewableOnly) {
            // REVIEW_REQUIRED is a legacy completion state.  Treat it as a
            // saved draft so old uploads remain visible after the workflow
            // moves to parse -> save -> publish.
            where.append(" AND pu.status IN ('SAVED', 'PUBLISHED', 'REVIEW_REQUIRED')");
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (lower(pu.production_name) LIKE ? OR lower(pu.order_no) LIKE ? " +
                    "OR lower(pu.product_name) LIKE ? OR lower(pu.category) LIKE ? " +
                    "OR lower(fo.original_name) LIKE ?)");
            var like = "%" + keyword.trim().toLowerCase() + "%";
            args.add(like); args.add(like); args.add(like); args.add(like); args.add(like);
        }
        var from = " FROM mfg.production_upload pu JOIN ops.file_object fo ON fo.id = pu.file_id ";
        var total = jdbcTemplate.queryForObject("SELECT count(*)" + from + where, Long.class, args.toArray());
        var pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        var items = jdbcTemplate.query(selectSql() + where +
                        " ORDER BY pu.created_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> map(rs), pageArgs.toArray());
        var safeTotal = total == null ? 0 : total;
        return new PageResult<>(items, page, size, safeTotal,
                safeTotal == 0 ? 0 : (safeTotal + size - 1) / size);
    }

    @Override
    public int delete(UUID organizationId, UUID uploadId) {
        return jdbcTemplate.update("""
                UPDATE mfg.production_upload SET status = 'DELETED'
                WHERE organization_id = ? AND id = ? AND status <> 'DELETED'
                """, organizationId, uploadId);
    }

    @Override
    public Optional<UploadView> saveDraft(UUID organizationId, UUID actorId, UUID uploadId,
                                          JsonNode workbookSnapshot, long lockVersion) {
        var updated = jdbcTemplate.update("""
                UPDATE mfg.production_upload
                SET workbook_snapshot_jsonb = ?, updated_by = ?,
                    lock_version = lock_version + 1, updated_at = now()
                -- The workspace keeps the edit action available for published
                -- records so business snapshots can be corrected after release.
                -- Keep optimistic locking, but do not turn that supported UI
                -- path into a 409 merely because the record is published.
                WHERE organization_id = ? AND id = ? AND status IN ('REVIEW_REQUIRED', 'SAVED', 'PUBLISHED') AND lock_version = ?
                """, pg(workbookSnapshot), actorId, organizationId, uploadId, lockVersion);
        if (updated == 0) {
            if (find(organizationId, uploadId).isEmpty()) return Optional.empty();
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "生产单已被其他用户修改");
        }
        return find(organizationId, uploadId);
    }

    @Override
    public Optional<UploadView> updateMetadata(UUID organizationId, UUID actorId, UUID uploadId,
                                               String productionName, String orderNo, String productName,
                                               String category, java.time.LocalDate manufactureDate,
                                               long lockVersion) {
        var updated = jdbcTemplate.update("""
                UPDATE mfg.production_upload
                SET production_name = ?, order_no = ?, product_name = ?, category = ?,
                    manufacture_date = ?, updated_by = ?, lock_version = lock_version + 1,
                    updated_at = now()
                WHERE organization_id = ? AND id = ? AND status IN ('REVIEW_REQUIRED', 'SAVED', 'PUBLISHED') AND lock_version = ?
                """, productionName, orderNo, productName, category, manufactureDate, actorId,
                organizationId, uploadId, lockVersion);
        if (updated == 0) {
            if (find(organizationId, uploadId).isEmpty()) return Optional.empty();
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "生产单已被其他用户修改");
        }
        return find(organizationId, uploadId);
    }

    @Override
    public Optional<UploadView> rename(UUID organizationId, UUID actorId, UUID uploadId,
                                       String productionName, UUID projectId, String projectName,
                                       UUID stageId, String stageName, UUID taskId, String taskName,
                                       long lockVersion) {
        var updated = jdbcTemplate.update("""
                UPDATE mfg.production_upload
                SET production_name = ?, project_id = ?, project_name = ?, stage_id = ?, stage_name = ?,
                    task_id = ?, task_name = ?, updated_by = ?, lock_version = lock_version + 1,
                    updated_at = now()
                WHERE organization_id = ? AND id = ? AND status IN ('REVIEW_REQUIRED', 'SAVED', 'PUBLISHED')
                  AND lock_version = ?
                """, productionName, projectId, projectName, stageId, stageName, taskId, taskName,
                actorId, organizationId, uploadId, lockVersion);
        if (updated == 0) {
            if (find(organizationId, uploadId).isEmpty()) return Optional.empty();
            throw new ApiException(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, "生产单已被其他用户修改");
        }
        return find(organizationId, uploadId);
    }

    @Override
    public List<VersionView> versions(UUID organizationId, UUID uploadId) {
        return jdbcTemplate.query("""
                SELECT v.*, coalesce(u.display_name, u.username, '未知用户') created_by_name
                FROM mfg.production_upload_version v
                LEFT JOIN iam.app_user u ON u.id = v.created_by
                WHERE v.organization_id = ? AND v.production_upload_id = ?
                ORDER BY v.version_no DESC
                """, (rs, rowNum) -> mapVersion(rs), organizationId, uploadId);
    }

    @Override
    public VersionView publish(UUID organizationId, UUID actorId, UUID uploadId) {
        var current = find(organizationId, uploadId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "生产单上传记录不存在"));
        if (current.workbookSnapshot() == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "请先保存草稿后再发布");
        }
        if ("PUBLISHED".equals(current.status())) {
            return versions(organizationId, uploadId).stream().findFirst()
                    .orElseThrow(() -> new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "生产单已发布但版本记录不存在"));
        }
        var next = jdbcTemplate.queryForObject("""
                SELECT coalesce(max(version_no), 0) + 1
                FROM mfg.production_upload_version
                WHERE organization_id = ? AND production_upload_id = ?
                """, Integer.class, organizationId, uploadId);
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mfg.production_upload_version (
                    id, organization_id, production_upload_id, version_no,
                    workbook_snapshot_jsonb, lock_version, change_type, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'PUBLISH', ?)
                """, id, organizationId, uploadId, next, pg(current.workbookSnapshot()),
                current.lockVersion(), actorId);
        jdbcTemplate.update("""
                UPDATE mfg.production_upload
                SET status = 'PUBLISHED', updated_by = ?, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, actorId, organizationId, uploadId);
        return versions(organizationId, uploadId).getFirst();
    }

    private String selectSql() {
        return """
                SELECT pu.id, pu.file_id, fo.original_name, fo.content_type, fo.size_bytes, fo.sha256,
                       pu.production_name, pu.order_no, pu.product_name, pu.category, pu.manufacture_date,
                       pu.project_id, pu.project_name, pu.stage_id, pu.stage_name, pu.task_id, pu.task_name,
                       pu.visibility, pu.status, pu.created_by, pu.created_at,
                       pu.source_type,
                       pu.workbook_snapshot_jsonb, pu.recognition_progress, pu.current_stage,
                       pu.selected_template_version_id, pu.match_mode, pu.template_match_score,
                       pu.recognition_result_jsonb, pu.structure_summary_jsonb, pu.failure_message,
                       pu.lock_version, pu.updated_by, pu.updated_at
                FROM mfg.production_upload pu
                JOIN ops.file_object fo ON fo.id = pu.file_id
                """;
    }

    private UploadView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UploadView(
                rs.getObject("id", UUID.class),
                rs.getObject("file_id", UUID.class),
                rs.getString("original_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("production_name"),
                rs.getString("order_no"),
                rs.getString("product_name"),
                rs.getString("category"),
                rs.getObject("manufacture_date", java.time.LocalDate.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("project_name"),
                rs.getObject("stage_id", UUID.class),
                rs.getString("stage_name"),
                rs.getObject("task_id", UUID.class),
                rs.getString("task_name"),
                rs.getString("visibility"),
                rs.getString("source_type"),
                rs.getString("status"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                jsonNullable(rs.getString("workbook_snapshot_jsonb")),
                rs.getInt("recognition_progress"),
                rs.getString("current_stage"),
                rs.getObject("selected_template_version_id", UUID.class),
                rs.getString("match_mode"),
                rs.getObject("template_match_score") == null ? null : rs.getDouble("template_match_score"),
                jsonNullable(rs.getString("recognition_result_jsonb")),
                jsonNullable(rs.getString("structure_summary_jsonb")),
                rs.getString("failure_message"),
                rs.getLong("lock_version"),
                rs.getObject("updated_by", UUID.class),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private VersionView mapVersion(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new VersionView(
                rs.getObject("id", UUID.class),
                rs.getObject("production_upload_id", UUID.class),
                rs.getInt("version_no"),
                jsonNullable(rs.getString("workbook_snapshot_jsonb")),
                rs.getLong("lock_version"),
                rs.getString("change_type"),
                rs.getString("created_by_name"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private JsonNode jsonNullable(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new IllegalStateException("生产单工作簿快照解析失败", ex);
        }
    }

    private PGobject pg(JsonNode value) {
        try {
            var object = new PGobject();
            object.setType("jsonb");
            object.setValue(value.toString());
            return object;
        } catch (Exception ex) {
            throw new IllegalStateException("生产单工作簿快照序列化失败", ex);
        }
    }
}
