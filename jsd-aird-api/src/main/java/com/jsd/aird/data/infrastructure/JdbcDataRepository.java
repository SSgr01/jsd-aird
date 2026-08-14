package com.jsd.aird.data.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.application.port.DataRepository;
import com.jsd.aird.shared.api.PageResponse;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDataRepository implements DataRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcDataRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insertJob(NewJob job) {
        jdbc.update("""
                INSERT INTO data.import_job (
                    id, organization_id, source_file_id, source_sha256, source_file_name,
                    source_format, template_version_id, category_id, status,
                    duplicate_override, created_by, import_contract_version, contract_hash,
                    source_file_hash, compatibility_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?, ?, ?)
                """, job.id(), job.organizationId(), job.sourceFileId(), job.sourceSha256(), job.sourceFileName(),
                job.sourceFormat(), job.templateVersionId(), job.categoryId(),
                job.duplicateOverride(), job.actorId(), job.importContractVersion(), job.contractHash(),
                job.sourceSha256(), job.importContractVersion() == null ? "LEGACY" : "REVIEW_REQUIRED");
    }

    @Override
    public void enqueueParse(UUID asyncJobId, UUID organizationId, UUID importJobId) {
        var payload = objectMapper.createObjectNode()
                .put("organizationId", organizationId.toString())
                .put("importJobId", importJobId.toString());
        jdbc.update("""
                INSERT INTO ops.async_job (id, organization_id, job_type, status, payload_jsonb, priority, idempotency_key)
                VALUES (?, ?, 'DATA_IMPORT_PARSE', 'READY', ?, 50, ?)
                """, asyncJobId, organizationId, pgJson(payload), "data-import-parse:" + importJobId);
        updateJobStatus(organizationId, importJobId, "QUEUED", 1, "QUEUED", null);
    }

    @Override
    public Optional<Job> findJob(UUID organizationId, UUID importJobId) {
        return jdbc.query("""
                SELECT id, source_file_id, source_sha256, source_file_name, source_format,
                       template_version_id, status, progress, current_stage,
                       category_id,
                       parser_version, error_message, created_at, updated_at,
                       import_contract_version, contract_hash, compatibility_status
                FROM data.import_job WHERE organization_id = ? AND id = ?
                """, this::mapJob, organizationId, importJobId).stream().findFirst();
    }

    @Override
    public PageResponse<Job> listJobs(UUID organizationId, UUID templateVersionId,
                                      String status, String keyword, int page, int size) {
        var conditions = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        conditions.add("organization_id = ?");
        parameters.add(organizationId);
        if (templateVersionId != null) {
            conditions.add("template_version_id = ?");
            parameters.add(templateVersionId);
        }
        if (status != null && !status.isBlank()) {
            conditions.add("status = ?");
            parameters.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            conditions.add("lower(source_file_name) LIKE lower(?)");
            parameters.add("%" + keyword.trim() + "%");
        }
        var where = String.join(" AND ", conditions);
        var total = jdbc.queryForObject("SELECT count(*) FROM data.import_job WHERE " + where,
                Long.class, parameters.toArray());
        var offset = (page - 1) * size;
        var rows = new ArrayList<>(parameters);
        rows.add(size);
        rows.add(offset);
        var items = jdbc.query("""
                SELECT id, source_file_id, source_sha256, source_file_name, source_format,
                       template_version_id, status, progress, current_stage,
                       category_id,
                       parser_version, error_message, created_at, updated_at,
                       import_contract_version, contract_hash, compatibility_status
                FROM data.import_job
                WHERE
                """ + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", this::mapJob, rows.toArray());
        var totalValue = total == null ? 0L : total;
        return new PageResponse<>(items, page, size, totalValue, (totalValue + size - 1) / size);
    }

    @Override
    public PageResponse<SourceFile> listSourceFiles(UUID organizationId, UUID categoryId, String status,
                                                     String keyword, int page, int size) {
        var conditions = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        conditions.add("j.organization_id = ?");
        parameters.add(organizationId);
        if (categoryId != null) {
            conditions.add("j.category_id = ?");
            parameters.add(categoryId);
        }
        if (status != null && !status.isBlank()) {
            conditions.add("j.status = ?");
            parameters.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            conditions.add("lower(j.source_file_name) LIKE lower(?)");
            parameters.add("%" + keyword.trim() + "%");
        }
        var where = String.join(" AND ", conditions);
        var total = jdbc.queryForObject("SELECT count(*) FROM data.import_job j WHERE " + where,
                Long.class, parameters.toArray());
        var args = new ArrayList<>(parameters);
        args.add(size);
        args.add(Math.max(0, page - 1) * size);
        var items = jdbc.query("""
                SELECT j.id AS import_job_id, j.source_file_id, j.source_file_name, j.source_format,
                       j.template_version_id, j.category_id, c.name AS category_name, j.status, j.progress,
                       j.created_at, j.updated_at,
                       (SELECT count(*) FROM data.import_sheet s WHERE s.import_job_id = j.id) AS sheet_count,
                       greatest(
                           (SELECT count(*) FROM data.data_record r WHERE r.import_job_id = j.id),
                           (SELECT count(*) FROM data.staging_row sr WHERE sr.import_job_id = j.id AND coalesce(sr.excluded, false) = false)
                       ) AS record_count,
                       (SELECT count(*) FROM data.data_value v
                          JOIN data.data_record r ON r.id = v.record_id
                         WHERE r.import_job_id = j.id) AS field_count
                FROM data.import_job j
                LEFT JOIN data.data_category c ON c.id = j.category_id
                 WHERE """ + " " + where + " ORDER BY j.updated_at DESC, j.created_at DESC LIMIT ? OFFSET ?",
                (rs, n) -> new SourceFile(rs.getObject("import_job_id", UUID.class),
                        rs.getObject("source_file_id", UUID.class), rs.getString("source_file_name"),
                        rs.getString("source_format"), rs.getObject("template_version_id", UUID.class),
                        rs.getObject("category_id", UUID.class), rs.getString("category_name"),
                        rs.getString("status"), rs.getInt("progress"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                        rs.getInt("sheet_count"), rs.getInt("record_count"), rs.getInt("field_count")),
                args.toArray());
        var totalValue = total == null ? 0L : total;
        return new PageResponse<>(items, page, size, totalValue, (totalValue + size - 1) / size);
    }

    @Override
    public int assignSourceCategory(UUID organizationId, UUID importJobId, UUID categoryId) {
        return jdbc.update("UPDATE data.import_job SET category_id = ?, updated_at = now() "
                        + "WHERE organization_id = ? AND id = ?",
                categoryId, organizationId, importJobId);
    }

    @Override
    public Optional<Job> findJobForUpdate(UUID organizationId, UUID importJobId) {
        return jdbc.query("""
                SELECT id, source_file_id, source_sha256, source_file_name, source_format,
                       template_version_id, status, progress, current_stage,
                       category_id,
                       parser_version, error_message, created_at, updated_at,
                       import_contract_version, contract_hash, compatibility_status
                FROM data.import_job WHERE organization_id = ? AND id = ? FOR UPDATE
                """, this::mapJob, organizationId, importJobId).stream().findFirst();
    }

    @Override
    public Optional<Job> findCompletedDuplicate(UUID organizationId, String sha256, UUID templateVersionId) {
        return jdbc.query("""
                SELECT id, source_file_id, source_sha256, source_file_name, source_format,
                       template_version_id, status, progress, current_stage,
                       category_id,
                       parser_version, error_message, created_at, updated_at,
                       import_contract_version, contract_hash, compatibility_status
                FROM data.import_job
                WHERE organization_id = ? AND source_sha256 = ? AND template_version_id = ?
                  AND status = 'COMPLETED'
                ORDER BY completed_at DESC NULLS LAST, created_at DESC LIMIT 1
                """, this::mapJob, organizationId, sha256, templateVersionId).stream().findFirst();
    }

    @Override
    @Transactional
    public void saveParsed(UUID importJobId, String parserVersion, List<Sheet> sheets, List<Mapping> mappings,
                            List<Row> rows, UUID asyncJobId) {
        jdbc.update("DELETE FROM data.import_sheet WHERE import_job_id = ?", importJobId);
        jdbc.update("DELETE FROM data.import_mapping WHERE import_job_id = ?", importJobId);
        jdbc.update("DELETE FROM data.staging_row WHERE import_job_id = ?", importJobId);
        var sheetIds = new ArrayList<UUID>();
        for (Sheet sheet : sheets) {
            var id = sheet.id() == null ? UUID.randomUUID() : sheet.id();
            sheetIds.add(id);
            jdbc.update("""
                    INSERT INTO data.import_sheet (
                        id, import_job_id, sheet_id, sheet_name, sheet_order, selected,
                        header_rows_jsonb, data_start_row, data_end_row, structure_jsonb, confirmation_status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, importJobId, sheet.sheetId(), sheet.sheetName(), sheet.sheetOrder(), sheet.selected(),
                    pgJson(objectMapper.valueToTree(sheet.headerRows())), sheet.dataStartRow(), sheet.dataEndRow(),
                    pgJson(sheet.structure()), sheet.confirmationStatus());
        }
        for (Mapping mapping : mappings) {
            jdbc.update("""
                    INSERT INTO data.import_mapping (
                        id, import_job_id, import_sheet_id, source_column, source_header, field_code,
                        field_name, action, value_type, source_unit, standard_unit, mapping_jsonb, status
                    ) VALUES (?, ?, (SELECT id FROM data.import_sheet WHERE import_job_id = ? AND sheet_id = ?),
                              ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, mapping.id() == null ? UUID.randomUUID() : mapping.id(), importJobId, importJobId,
                    mapping.sheetId(), mapping.sourceColumn(), mapping.sourceHeader(), mapping.fieldCode(),
                    mapping.fieldName(), mapping.action(), mapping.valueType(), mapping.sourceUnit(), mapping.standardUnit(),
                    pgJson(mapping.detail()), mapping.status());
        }
        for (Row row : rows) {
            jdbc.update("""
                    INSERT INTO data.staging_row (
                        id, import_job_id, import_sheet_id, source_row_number, raw_values_jsonb,
                        normalized_values_jsonb, corrected_values_jsonb, source_metadata_jsonb, row_hash, status
                    ) VALUES (?, ?, (SELECT id FROM data.import_sheet WHERE import_job_id = ? AND sheet_id = ?),
                              ?, ?, ?, ?, ?, ?, ?)
                    """, row.id() == null ? UUID.randomUUID() : row.id(), importJobId, importJobId, row.sheetId(),
                    row.rowNumber(), pgJson(row.rawValues()), pgJson(row.normalizedValues()), pgJson(row.correctedValues()),
                    pgJson(row.sourceMetadata()), hash(row.rawValues()), row.status());
        }
        jdbc.update("""
                UPDATE data.import_job SET parser_version = ?, status = 'WAITING_MAPPING', progress = 35,
                    current_stage = 'WAITING_MAPPING', updated_at = now()
                WHERE id = ?
                """, parserVersion, importJobId);
        // The worker completes the ops job after the handler returns. Keeping this method
        // independent of the ops job row also preserves the module boundary.
    }

    @Override
    @Transactional
    public void clearParsed(UUID organizationId, UUID importJobId) {
        jdbc.update("""
                DELETE FROM data.import_issue
                WHERE import_job_id = ? AND EXISTS (
                    SELECT 1 FROM data.import_job WHERE id = ? AND organization_id = ?
                )
                """, importJobId, importJobId, organizationId);
        jdbc.update("""
                DELETE FROM data.import_mapping
                WHERE import_job_id = ? AND EXISTS (
                    SELECT 1 FROM data.import_job WHERE id = ? AND organization_id = ?
                )
                """, importJobId, importJobId, organizationId);
        jdbc.update("""
                DELETE FROM data.staging_row
                WHERE import_job_id = ? AND EXISTS (
                    SELECT 1 FROM data.import_job WHERE id = ? AND organization_id = ?
                )
                """, importJobId, importJobId, organizationId);
        jdbc.update("""
                DELETE FROM data.import_sheet
                WHERE import_job_id = ? AND EXISTS (
                    SELECT 1 FROM data.import_job WHERE id = ? AND organization_id = ?
                )
                """, importJobId, importJobId, organizationId);
    }

    @Override
    public Optional<JsonNode> findMappingProfile(UUID organizationId, UUID templateVersionId, String sourceFingerprint) {
        return jdbc.query("""
                SELECT mapping_jsonb FROM data.import_mapping_profile
                WHERE organization_id = ? AND template_version_id = ? AND source_fingerprint = ?
                """, (rs, rowNum) -> parse(rs.getString("mapping_jsonb")),
                organizationId, templateVersionId, sourceFingerprint).stream().findFirst();
    }

    @Override
    public void saveMappingProfile(UUID organizationId, UUID templateVersionId, String sourceFingerprint,
                                   JsonNode mappings, UUID actorId) {
        jdbc.update("""
                INSERT INTO data.import_mapping_profile (
                    id, organization_id, template_version_id, source_fingerprint, mapping_jsonb,
                    approved_by, approved_at
                ) VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (organization_id, template_version_id, source_fingerprint)
                DO UPDATE SET mapping_jsonb = EXCLUDED.mapping_jsonb, approved_by = EXCLUDED.approved_by,
                              approved_at = now(), updated_at = now()
                """, UUID.randomUUID(), organizationId, templateVersionId, sourceFingerprint,
                pgJson(mappings), actorId);
    }

    @Override
    public void updateJobStatus(UUID organizationId, UUID importJobId, String status, int progress,
                                String stage, String error) {
        jdbc.update("""
                UPDATE data.import_job SET status = ?, progress = ?, current_stage = ?, error_message = ?,
                    updated_at = now(), completed_at = CASE WHEN ? = 'COMPLETED' THEN now() ELSE completed_at END
                WHERE organization_id = ? AND id = ?
                """, status, progress, stage, error, status, organizationId, importJobId);
    }

    @Override
    public void updateSheet(SheetUpdate update) {
        jdbc.update("""
                UPDATE data.import_sheet SET selected = ?, header_rows_jsonb = ?, data_start_row = ?,
                    data_end_row = ?, confirmation_status = ?,
                    structure_jsonb = jsonb_set(jsonb_set(structure_jsonb, '{selected}', to_jsonb(?::boolean)),
                        '{dataStartRow}', to_jsonb(?::int)),
                    updated_at = now()
                WHERE import_job_id = ? AND sheet_id = ?
                """, update.selected(), pgJson(objectMapper.valueToTree(update.headerRows())), update.dataStartRow(),
                update.dataEndRow(), update.confirmationStatus(), update.selected(), update.dataStartRow(),
                update.importJobId(), update.sheetId());
        if (update.dataStartRow() != null || update.dataEndRow() != null) {
            jdbc.update("""
                    DELETE FROM data.staging_row
                    WHERE import_job_id = ?
                      AND import_sheet_id = (SELECT id FROM data.import_sheet WHERE import_job_id = ? AND sheet_id = ?)
                      AND (CAST(? AS integer) IS NULL OR source_row_number < ?)
                      AND (CAST(? AS integer) IS NULL OR source_row_number > ?)
                    """, update.importJobId(), update.importJobId(), update.sheetId(), update.dataStartRow(),
                    update.dataStartRow(), update.dataEndRow(), update.dataEndRow());
        }
    }

    @Override
    @Transactional
    public void replaceMappings(UUID organizationId, UUID importJobId, List<Mapping> mappings) {
        jdbc.update("DELETE FROM data.import_mapping WHERE import_job_id = ?", importJobId);
        for (Mapping mapping : mappings) {
            jdbc.update("""
                    INSERT INTO data.import_mapping (
                        id, import_job_id, import_sheet_id, source_column, source_header, field_code,
                        field_name, action, value_type, source_unit, standard_unit, mapping_jsonb, status
                    ) VALUES (?, ?, (SELECT id FROM data.import_sheet WHERE import_job_id = ? AND sheet_id = ?),
                              ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), importJobId, importJobId, mapping.sheetId(), mapping.sourceColumn(),
                    mapping.sourceHeader(), mapping.fieldCode(), mapping.fieldName(), mapping.action(), mapping.valueType(),
                    mapping.sourceUnit(), mapping.standardUnit(), pgJson(mapping.detail()), mapping.status());
        }
        jdbc.update("""
                UPDATE data.import_job SET status = 'VALIDATING', progress = 55, current_stage = 'VALIDATING', updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, organizationId, importJobId);
    }

    @Override
    public List<Sheet> listSheets(UUID organizationId, UUID importJobId) {
        return jdbc.query("""
                SELECT s.id, s.sheet_id, s.sheet_name, s.sheet_order, s.selected, s.header_rows_jsonb,
                       s.data_start_row, s.data_end_row, s.structure_jsonb, s.confirmation_status
                FROM data.import_sheet s JOIN data.import_job j ON j.id = s.import_job_id
                WHERE j.organization_id = ? AND j.id = ? ORDER BY s.sheet_order
                """, (rs, n) -> new Sheet(rs.getObject("id", UUID.class), rs.getString("sheet_id"),
                        rs.getString("sheet_name"), rs.getInt("sheet_order"), rs.getBoolean("selected"),
                        ints(parse(rs.getString("header_rows_jsonb"))), (Integer) rs.getObject("data_start_row"),
                        (Integer) rs.getObject("data_end_row"), parse(rs.getString("structure_jsonb")),
                        rs.getString("confirmation_status")), organizationId, importJobId);
    }

    @Override
    public List<Mapping> listMappings(UUID organizationId, UUID importJobId) {
        return jdbc.query("""
                SELECT m.id, s.sheet_id, m.source_column, m.source_header, m.field_code, m.field_name,
                       m.action, m.value_type, m.source_unit, m.standard_unit, m.mapping_jsonb, m.status
                FROM data.import_mapping m JOIN data.import_job j ON j.id = m.import_job_id
                LEFT JOIN data.import_sheet s ON s.id = m.import_sheet_id
                WHERE j.organization_id = ? AND j.id = ? ORDER BY s.sheet_order, m.source_column
                """, (rs, n) -> new Mapping(rs.getObject("id", UUID.class), rs.getString("sheet_id"),
                        rs.getString("source_column"), rs.getString("source_header"), rs.getString("field_code"),
                        rs.getString("field_name"), rs.getString("action"), rs.getString("value_type"),
                        rs.getString("source_unit"), rs.getString("standard_unit"), parse(rs.getString("mapping_jsonb")),
                        rs.getString("status")), organizationId, importJobId);
    }

    @Override
    public List<Row> listRows(UUID organizationId, UUID importJobId) {
        return jdbc.query("""
                SELECT r.id, s.sheet_id, r.source_row_number, r.raw_values_jsonb, r.normalized_values_jsonb,
                       r.corrected_values_jsonb, r.status, r.source_metadata_jsonb,
                       r.excluded, r.exclusion_reason
                FROM data.staging_row r JOIN data.import_sheet s ON s.id = r.import_sheet_id
                JOIN data.import_job j ON j.id = r.import_job_id
                WHERE j.organization_id = ? AND j.id = ? AND s.selected = true
                ORDER BY s.sheet_order, r.source_row_number
                """, (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("sheet_id"),
                        rs.getInt("source_row_number"), parse(rs.getString("raw_values_jsonb")),
                        parse(rs.getString("normalized_values_jsonb")), parse(rs.getString("corrected_values_jsonb")),
                        rs.getString("status"), parse(rs.getString("source_metadata_jsonb")),
                        rs.getBoolean("excluded"), rs.getString("exclusion_reason")), organizationId, importJobId);
    }

    @Override
    @Transactional
    public void replaceValidation(UUID organizationId, UUID importJobId, List<Row> rows, List<Issue> issues, String status) {
        jdbc.update("DELETE FROM data.import_issue WHERE import_job_id = ?", importJobId);
        for (Row row : rows) {
            jdbc.update("""
                UPDATE data.staging_row SET normalized_values_jsonb = ?, corrected_values_jsonb = ?,
                        source_metadata_jsonb = ?, status = ?, excluded = ?, exclusion_reason = ?, updated_at = now()
                    WHERE id = ? AND import_job_id = ?
                    """, pgJson(row.normalizedValues()), pgJson(row.correctedValues()), pgJson(row.sourceMetadata()),
                    row.status(), row.excluded(), row.exclusionReason(), row.id(), importJobId);
        }
        for (Issue issue : issues) {
            jdbc.update("""
                    INSERT INTO data.import_issue (
                        id, import_job_id, import_sheet_id, field_code, severity, issue_type,
                        source_row_number, source_column, source_address, message, detail_jsonb, status
                    ) VALUES (?, ?, (SELECT id FROM data.import_sheet WHERE import_job_id = ? AND sheet_id = ?),
                              ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), importJobId, importJobId, issue.sheetId(), issue.fieldCode(), issue.severity(),
                    issue.issueType(), issue.rowNumber(), issue.column(), issue.address(), issue.message(), pgJson(issue.detail()),
                    issue.status());
        }
        updateJobStatus(organizationId, importJobId, status, "WAITING_CONFIRM".equals(status) ? 85 : 55,
                "WAITING_CONFIRM".equals(status) ? "WAITING_CONFIRM" : "VALIDATING", null);
    }

    @Override
    public List<Issue> listIssues(UUID organizationId, UUID importJobId) {
        return jdbc.query("""
                SELECT i.id, s.sheet_id, i.field_code, i.severity, i.issue_type, i.source_row_number,
                       i.source_column, i.source_address, i.message, i.detail_jsonb, i.status
                FROM data.import_issue i JOIN data.import_job j ON j.id = i.import_job_id
                LEFT JOIN data.import_sheet s ON s.id = i.import_sheet_id
                WHERE j.organization_id = ? AND j.id = ? ORDER BY i.severity DESC, i.source_row_number
                """, (rs, n) -> new Issue(rs.getObject("id", UUID.class), rs.getString("sheet_id"),
                        rs.getString("field_code"), rs.getString("severity"), rs.getString("issue_type"),
                        (Integer) rs.getObject("source_row_number"), rs.getString("source_column"),
                        rs.getString("source_address"), rs.getString("message"), parse(rs.getString("detail_jsonb")),
                        rs.getString("status")), organizationId, importJobId);
    }

    @Override
    public void resolveIssue(UUID organizationId, UUID issueId, UUID actorId, String status, String reason) {
        jdbc.update("""
                UPDATE data.import_issue i SET status = ?, resolution_reason = ?, resolved_by = ?, resolved_at = now()
                WHERE i.id = ? AND EXISTS (
                    SELECT 1 FROM data.import_job j WHERE j.id = i.import_job_id AND j.organization_id = ?
                )
                """, status, reason, actorId, issueId, organizationId);
    }

    @Override
    public void updateCompatibility(UUID organizationId, UUID importJobId, String compatibilityStatus) {
        jdbc.update("UPDATE data.import_job SET compatibility_status = ?, updated_at = now() WHERE organization_id = ? AND id = ?",
                compatibilityStatus, organizationId, importJobId);
    }

    @Override
    public void saveCompatibilityReport(UUID organizationId, UUID importJobId, String compatibilityStatus, JsonNode report) {
        jdbc.update("""
                UPDATE data.import_job
                SET compatibility_status = ?, compatibility_report_jsonb = ?, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, compatibilityStatus, pgJson(report), organizationId, importJobId);
    }

    @Override
    public JsonNode findCompatibilityReport(UUID organizationId, UUID importJobId) {
        return jdbc.query("""
                SELECT compatibility_report_jsonb FROM data.import_job
                WHERE organization_id = ? AND id = ?
                """, (rs, rowNum) -> parse(rs.getString(1)), organizationId, importJobId)
                .stream().findFirst().orElseGet(objectMapper::createObjectNode);
    }

    @Override
    public void saveComponentOverride(UUID organizationId, UUID importJobId, ComponentOverride override) {
        jdbc.update("""
                INSERT INTO data.import_component_override (
                    id, organization_id, import_job_id, component_id, sheet_id,
                    source_range, reason, created_by
                ) SELECT gen_random_uuid(), ?, j.id, ?, ?, ?, ?, ?
                  FROM data.import_job j WHERE j.organization_id = ? AND j.id = ?
                ON CONFLICT (import_job_id, component_id) DO UPDATE SET
                    sheet_id = EXCLUDED.sheet_id, source_range = EXCLUDED.source_range,
                    reason = EXCLUDED.reason, created_by = EXCLUDED.created_by, updated_at = now()
                """, organizationId, override.componentId(), override.sheetId(), override.sourceRange(),
                override.reason(), override.actorId(), organizationId, importJobId);
    }

    @Override
    public List<ComponentOverride> listComponentOverrides(UUID organizationId, UUID importJobId) {
        return jdbc.query("""
                SELECT o.component_id, o.sheet_id, o.source_range, o.reason, o.created_by, o.updated_at
                FROM data.import_component_override o
                JOIN data.import_job j ON j.id = o.import_job_id
                WHERE j.organization_id = ? AND j.id = ? ORDER BY o.created_at
                """, (rs, rowNum) -> new ComponentOverride(
                rs.getString("component_id"), rs.getString("sheet_id"), rs.getString("source_range"),
                rs.getString("reason"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("updated_at").toInstant()), organizationId, importJobId);
    }

    @Override
    public void correctValue(UUID organizationId, UUID importJobId, UUID recordId, String bindingId,
                             String valuePath, JsonNode correctedValue, UUID actorId, String reason) {
        var key = jdbc.query("""
                SELECT normalized.key
                FROM data.staging_row r
                JOIN data.import_job j ON j.id = r.import_job_id
                JOIN data.import_mapping m ON m.import_job_id = r.import_job_id
                JOIN data.import_sheet s ON s.id = m.import_sheet_id AND s.id = r.import_sheet_id
                CROSS JOIN LATERAL jsonb_each(r.normalized_values_jsonb) normalized
                WHERE j.organization_id = ? AND j.id = ? AND r.id = ?
                  AND m.field_code = coalesce(normalized.value->>'fieldCode', m.field_code)
                  AND coalesce(normalized.value->>'bindingId', normalized.value->>'fieldCode', normalized.key) = ?
                  AND coalesce(normalized.value->>'valuePath', normalized.value->>'dataPath',
                               '/' || coalesce(normalized.value->>'fieldCode', normalized.key)) = ?
                LIMIT 1
                """, (rs, rowNum) -> rs.getString(1), organizationId, importJobId, recordId,
                bindingId, valuePath == null ? "" : valuePath).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("待修正值不存在"));
        jdbc.update("""
                UPDATE data.staging_row r
                SET corrected_values_jsonb = jsonb_set(r.corrected_values_jsonb, ARRAY[?]::text[],
                        coalesce(r.normalized_values_jsonb->?, '{}'::jsonb)
                            || jsonb_build_object('correctedValue', ?::jsonb), true),
                    status = 'STAGED', updated_at = now()
                FROM data.import_job j
                WHERE r.import_job_id = j.id AND j.organization_id = ? AND j.id = ? AND r.id = ?
                """, key, key, pgJson(correctedValue), organizationId, importJobId, recordId);
        jdbc.update("""
                INSERT INTO ops.audit_log (id, organization_id, actor_id, action, aggregate_type, aggregate_id, detail_jsonb)
                VALUES (gen_random_uuid(), ?, ?, 'DATA_IMPORT_VALUE_CORRECTED', 'DATA_IMPORT_JOB', ?,
                        jsonb_build_object('recordId', ?, 'bindingId', ?, 'valuePath', ?, 'reason', ?))
                """, organizationId, actorId, importJobId, recordId, bindingId, valuePath, reason);
    }

    @Override
    public void excludeRow(UUID organizationId, UUID importJobId, UUID recordId, boolean excluded,
                           UUID actorId, String reason) {
        jdbc.update("""
                UPDATE data.staging_row r SET excluded = ?, exclusion_reason = ?,
                    excluded_by = CASE WHEN ? THEN ? ELSE NULL END,
                    excluded_at = CASE WHEN ? THEN now() ELSE NULL END, updated_at = now()
                FROM data.import_job j
                WHERE r.import_job_id = j.id AND j.organization_id = ? AND j.id = ? AND r.id = ?
                """, excluded, excluded ? reason : null, excluded, actorId, excluded,
                organizationId, importJobId, recordId);
    }

    @Override
    @Transactional
    public CommitResult commit(UUID organizationId, UUID importJobId, UUID actorId, List<CommittedRow> rows) {
        var job = findJob(organizationId, importJobId).orElseThrow();
        var committedRecords = new ArrayList<CommittedRecord>();
        // A re-confirmation replaces the records of this import batch. Cascades
        // remove the field values and source anchors belonging to those records.
        jdbc.update("DELETE FROM data.data_record WHERE organization_id = ? AND import_job_id = ?",
                organizationId, importJobId);
        var recordIndex = 0;
        for (CommittedRow row : rows) {
            var recordId = UUID.randomUUID();
            var dataHash = hash(row.normalized());
            jdbc.update("""
                    INSERT INTO data.data_record (
                        id, organization_id, import_job_id, record_key, record_index,
                        sheet_id, sheet_name, source_row_number, raw_data_jsonb,
                        normalized_data_jsonb, corrected_data_jsonb, effective_data_jsonb,
                        quality_status, synthetic_key
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'VALID', ?)
                    """, recordId, organizationId, importJobId, row.recordKey(), ++recordIndex,
                    row.sheetId(), row.sheetName(), row.rowNumber(), pgJson(row.raw()),
                    pgJson(row.normalized()), pgJson(row.corrected()), pgJson(row.normalized()),
                    row.recordKey().startsWith("IMPORT:"));
            for (Anchor anchor : row.anchors()) {
                jdbc.update("""
                    INSERT INTO data.source_anchor (
                            id, record_id, import_job_id, field_code, binding_id, value_path,
                            label_path, value_source, value_status, file_id, sheet_id, sheet_name,
                            row_number, column_number, column_name, cell_address, raw_value_jsonb
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), recordId, importJobId, anchor.fieldCode(), anchor.bindingId(),
                        anchor.valuePath(), anchor.labelPath(), anchor.valueSource(), anchor.valueStatus(),
                        job.sourceFileId(), anchor.sheetId(), anchor.sheetName(), anchor.rowNumber(),
                        anchor.columnNumber(), anchor.columnName(), anchor.address(), pgJson(anchor.rawValue()));
            }
            committedRecords.add(new CommittedRecord(recordId, row.recordKey(), dataHash));
        }
        updateJobStatus(organizationId, importJobId, "COMPLETED", 100, "COMPLETED", null);
        return new CommitResult(List.copyOf(committedRecords), rows.size());
    }

    private Job mapJob(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Job(rs.getObject("id", UUID.class), rs.getObject("source_file_id", UUID.class),
                rs.getString("source_sha256"), rs.getString("source_file_name"), rs.getString("source_format"),
                rs.getObject("template_version_id", UUID.class),
                rs.getObject("category_id", UUID.class), rs.getString("status"),
                rs.getInt("progress"), rs.getString("current_stage"), rs.getString("parser_version"),
                rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                (Integer) rs.getObject("import_contract_version"), rs.getString("contract_hash"),
                rs.getString("compatibility_status"));
    }

    private List<Integer> ints(JsonNode node) {
        var result = new ArrayList<Integer>();
        if (node != null && node.isArray()) node.forEach(item -> result.add(item.asInt()));
        return List.copyOf(result);
    }

    private JsonNode parse(String value) {
        if (value == null) return objectMapper.createObjectNode();
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid JSONB", exception); }
    }

    private PGobject pgJson(JsonNode value) {
        try {
            var result = new PGobject();
            result.setType("jsonb");
            result.setValue(objectMapper.writeValueAsString(value == null ? objectMapper.createObjectNode() : value));
            return result;
        } catch (Exception exception) { throw new IllegalArgumentException("Unable to serialize JSONB", exception); }
    }

    private String hash(JsonNode value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("Unable to hash JSON", exception); }
    }

}
