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
                    source_format, template_version_id, target_data_type, status,
                    duplicate_override, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?)
                """, job.id(), job.organizationId(), job.sourceFileId(), job.sourceSha256(), job.sourceFileName(),
                job.sourceFormat(), job.templateVersionId(), job.targetDataType(), job.duplicateOverride(), job.actorId());
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
                       template_version_id, target_data_type, status, progress, current_stage,
                       parser_version, error_message, created_at, updated_at
                FROM data.import_job WHERE organization_id = ? AND id = ?
                """, this::mapJob, organizationId, importJobId).stream().findFirst();
    }

    @Override
    public Optional<Job> findJobForUpdate(UUID organizationId, UUID importJobId) {
        return jdbc.query("""
                SELECT id, source_file_id, source_sha256, source_file_name, source_format,
                       template_version_id, target_data_type, status, progress, current_stage,
                       parser_version, error_message, created_at, updated_at
                FROM data.import_job WHERE organization_id = ? AND id = ? FOR UPDATE
                """, this::mapJob, organizationId, importJobId).stream().findFirst();
    }

    @Override
    public Optional<Job> findCompletedDuplicate(UUID organizationId, String sha256, UUID templateVersionId) {
        return jdbc.query("""
                SELECT id, source_file_id, source_sha256, source_file_name, source_format,
                       template_version_id, target_data_type, status, progress, current_stage,
                       parser_version, error_message, created_at, updated_at
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
                        normalized_values_jsonb, corrected_values_jsonb, row_hash, status
                    ) VALUES (?, ?, (SELECT id FROM data.import_sheet WHERE import_job_id = ? AND sheet_id = ?),
                              ?, ?, ?, ?, ?, ?)
                    """, row.id() == null ? UUID.randomUUID() : row.id(), importJobId, importJobId, row.sheetId(),
                    row.rowNumber(), pgJson(row.rawValues()), pgJson(row.normalizedValues()), pgJson(row.correctedValues()),
                    hash(row.rawValues()), row.status());
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
                      AND (? IS NULL OR source_row_number < ?)
                      AND (? IS NULL OR source_row_number > ?)
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
                       r.corrected_values_jsonb, r.status
                FROM data.staging_row r JOIN data.import_sheet s ON s.id = r.import_sheet_id
                JOIN data.import_job j ON j.id = r.import_job_id
                WHERE j.organization_id = ? AND j.id = ? AND s.selected = true
                ORDER BY s.sheet_order, r.source_row_number
                """, (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("sheet_id"),
                        rs.getInt("source_row_number"), parse(rs.getString("raw_values_jsonb")),
                        parse(rs.getString("normalized_values_jsonb")), parse(rs.getString("corrected_values_jsonb")),
                        rs.getString("status")), organizationId, importJobId);
    }

    @Override
    @Transactional
    public void replaceValidation(UUID organizationId, UUID importJobId, List<Row> rows, List<Issue> issues, String status) {
        jdbc.update("DELETE FROM data.import_issue WHERE import_job_id = ?", importJobId);
        for (Row row : rows) {
            jdbc.update("""
                    UPDATE data.staging_row SET normalized_values_jsonb = ?, corrected_values_jsonb = ?, status = ?, updated_at = now()
                    WHERE id = ? AND import_job_id = ?
                    """, pgJson(row.normalizedValues()), pgJson(row.correctedValues()), row.status(), row.id(), importJobId);
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
    public void resolveIssue(UUID organizationId, UUID issueId, UUID actorId, String status) {
        jdbc.update("""
                UPDATE data.import_issue i SET status = ?, resolved_by = ?, resolved_at = now()
                WHERE i.id = ? AND EXISTS (
                    SELECT 1 FROM data.import_job j WHERE j.id = i.import_job_id AND j.organization_id = ?
                )
                """, status, actorId, issueId, organizationId);
    }

    @Override
    @Transactional
    public CommitResult commit(UUID organizationId, UUID importJobId, UUID actorId, List<CommittedRow> rows) {
        var job = findJob(organizationId, importJobId).orElseThrow();
        var committedAssets = new ArrayList<CommittedAsset>();
        for (CommittedRow row : rows) {
            var existing = jdbc.query("""
                    SELECT id, current_revision_id FROM data.data_asset
                    WHERE organization_id = ? AND target_data_type = ? AND asset_key = ? FOR UPDATE
                    """, (rs, n) -> new ExistingAsset(rs.getObject("id", UUID.class), rs.getObject("current_revision_id", UUID.class)),
                    organizationId, job.targetDataType(), row.assetKey()).stream().findFirst();
            UUID assetId;
            if (existing.isEmpty()) {
                assetId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO data.data_asset (id, organization_id, target_data_type, asset_key, display_name, created_by)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, assetId, organizationId, job.targetDataType(), row.assetKey(), row.displayName(), actorId);
            } else assetId = existing.get().id();
            int revisionNo = jdbc.queryForObject("SELECT coalesce(max(revision_no), 0) + 1 FROM data.data_asset_revision WHERE asset_id = ?",
                    Integer.class, assetId);
            var revisionId = UUID.randomUUID();
            var dataHash = hash(row.normalized());
            jdbc.update("""
                    INSERT INTO data.data_asset_revision (
                        id, asset_id, revision_no, import_job_id, template_version_id,
                        raw_data_jsonb, normalized_data_jsonb, corrected_data_jsonb, data_hash, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, revisionId, assetId, revisionNo, importJobId, job.templateVersionId(), pgJson(row.raw()),
                    pgJson(row.normalized()), pgJson(row.corrected()), dataHash, actorId);
            for (Anchor anchor : row.anchors()) {
                jdbc.update("""
                        INSERT INTO data.source_anchor (
                            id, asset_revision_id, import_job_id, field_code, file_id, sheet_id, sheet_name,
                            row_number, column_number, column_name, cell_address, raw_value_jsonb
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), revisionId, importJobId, anchor.fieldCode(), job.sourceFileId(),
                        anchor.sheetId(), anchor.sheetName(), anchor.rowNumber(), anchor.columnNumber(), anchor.columnName(),
                        anchor.address(), pgJson(anchor.rawValue()));
            }
            jdbc.update("UPDATE data.data_asset SET current_revision_id = ?, display_name = ?, updated_at = now() WHERE id = ?",
                    revisionId, row.displayName(), assetId);
            committedAssets.add(new CommittedAsset(assetId, revisionId, revisionNo, dataHash));
        }
        updateJobStatus(organizationId, importJobId, "COMPLETED", 100, "COMPLETED", null);
        return new CommitResult(List.copyOf(committedAssets), rows.size());
    }

    @Override
    public List<Asset> listAssets(UUID organizationId, String targetDataType, String keyword) {
        return jdbc.query("""
                SELECT id, target_data_type, asset_key, display_name, current_revision_id, status, updated_at
                FROM data.data_asset
                WHERE organization_id = ?
                  AND (? IS NULL OR target_data_type = ?)
                  AND (? IS NULL OR lower(coalesce(display_name, '') || asset_key) LIKE lower(?))
                ORDER BY updated_at DESC
                """, (rs, n) -> new Asset(rs.getObject("id", UUID.class), rs.getString("target_data_type"),
                        rs.getString("asset_key"), rs.getString("display_name"), rs.getObject("current_revision_id", UUID.class),
                        rs.getString("status"), rs.getTimestamp("updated_at").toInstant()), organizationId,
                targetDataType, targetDataType, keyword, keyword == null ? null : "%" + keyword + "%");
    }

    @Override
    public Optional<AssetDetail> findAsset(UUID organizationId, UUID assetId) {
        return jdbc.query("""
                SELECT a.id, a.target_data_type, a.asset_key, a.display_name, a.current_revision_id, a.status,
                       r.raw_data_jsonb, r.normalized_data_jsonb, r.corrected_data_jsonb, r.import_job_id,
                       r.template_version_id, r.revision_no, j.source_sha256, a.updated_at
                FROM data.data_asset a LEFT JOIN data.data_asset_revision r ON r.id = a.current_revision_id
                     LEFT JOIN data.import_job j ON j.id = r.import_job_id
                WHERE a.organization_id = ? AND a.id = ?
                """, (rs, n) -> new AssetDetail(rs.getObject("id", UUID.class), rs.getString("target_data_type"),
                        rs.getString("asset_key"), rs.getString("display_name"), rs.getObject("current_revision_id", UUID.class),
                        rs.getString("status"), parse(rs.getString("raw_data_jsonb")), parse(rs.getString("normalized_data_jsonb")),
                        parse(rs.getString("corrected_data_jsonb")), rs.getObject("import_job_id", UUID.class),
                        rs.getObject("template_version_id", UUID.class), (Integer) rs.getObject("revision_no"),
                        rs.getString("source_sha256"), rs.getTimestamp("updated_at").toInstant()),
                organizationId, assetId).stream().findFirst();
    }

    @Override
    public List<Revision> listRevisions(UUID organizationId, UUID assetId) {
        return jdbc.query("""
                SELECT r.id, r.revision_no, r.import_job_id, r.template_version_id, r.data_hash, r.created_at
                FROM data.data_asset_revision r JOIN data.data_asset a ON a.id = r.asset_id
                WHERE a.organization_id = ? AND a.id = ? ORDER BY r.revision_no DESC
                """, (rs, n) -> new Revision(rs.getObject("id", UUID.class), rs.getInt("revision_no"),
                        rs.getObject("import_job_id", UUID.class), rs.getObject("template_version_id", UUID.class),
                        rs.getString("data_hash"), rs.getTimestamp("created_at").toInstant()), organizationId, assetId);
    }

    @Override
    public List<SourceAnchor> listSourceAnchors(UUID organizationId, UUID assetId) {
        return jdbc.query("""
                SELECT s.id, s.asset_revision_id, s.field_code, s.file_id, s.sheet_id, s.sheet_name,
                       s.row_number, s.column_number, s.column_name, s.cell_address, s.raw_value_jsonb
                FROM data.source_anchor s JOIN data.data_asset_revision r ON r.id = s.asset_revision_id
                JOIN data.data_asset a ON a.id = r.asset_id
                WHERE a.organization_id = ? AND a.id = ? ORDER BY r.revision_no DESC, s.row_number, s.column_number
                """, (rs, n) -> new SourceAnchor(rs.getObject("id", UUID.class), rs.getObject("asset_revision_id", UUID.class),
                        rs.getString("field_code"), rs.getObject("file_id", UUID.class), rs.getString("sheet_id"),
                        rs.getString("sheet_name"), (Integer) rs.getObject("row_number"), (Integer) rs.getObject("column_number"),
                        rs.getString("column_name"), rs.getString("cell_address"), parse(rs.getString("raw_value_jsonb"))),
                organizationId, assetId);
    }

    private Job mapJob(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Job(rs.getObject("id", UUID.class), rs.getObject("source_file_id", UUID.class),
                rs.getString("source_sha256"), rs.getString("source_file_name"), rs.getString("source_format"),
                rs.getObject("template_version_id", UUID.class), rs.getString("target_data_type"), rs.getString("status"),
                rs.getInt("progress"), rs.getString("current_stage"), rs.getString("parser_version"),
                rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
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

    private record ExistingAsset(UUID id, UUID currentRevisionId) {}
}
