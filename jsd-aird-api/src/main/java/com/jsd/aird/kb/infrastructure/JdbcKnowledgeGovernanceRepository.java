package com.jsd.aird.kb.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.StructuredDocumentCodec;
import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.domain.DocumentParser;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcKnowledgeGovernanceRepository implements KnowledgeGovernanceRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final StructuredDocumentCodec documents;

    public JdbcKnowledgeGovernanceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                             StructuredDocumentCodec documents) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.documents = documents;
    }

    @Override
    public List<DuplicateMatch> exactMatches(UUID organizationId, String sha256) {
        return duplicateRows("WHERE d.organization_id = ? AND v.sha256 = ? ORDER BY d.updated_at DESC, v.version_no DESC",
                organizationId, sha256);
    }

    @Override
    public List<DuplicateMatch> possibleMatches(UUID organizationId, String normalizedStem, UUID categoryId) {
        return duplicateRows("WHERE d.organization_id = ? AND d.category_id = ? ORDER BY d.updated_at DESC, v.version_no DESC",
                organizationId, categoryId);
    }

    private List<DuplicateMatch> duplicateRows(String where, Object... args) {
        return jdbc.query("""
                SELECT d.id AS document_id, v.id AS version_id, v.version_no, d.title, v.original_name,
                       v.sha256, d.lifecycle_status, v.review_status
                FROM kb.document d JOIN kb.document_version v ON v.document_id = d.id
                """ + where, this::mapDuplicate, args);
    }

    private DuplicateMatch mapDuplicate(ResultSet rs, int ignored) throws SQLException {
        return new DuplicateMatch(rs.getObject("document_id", UUID.class), rs.getObject("version_id", UUID.class),
                rs.getInt("version_no"), rs.getString("title"), rs.getString("original_name"),
                rs.getString("sha256"), null, 0, rs.getString("lifecycle_status"), rs.getString("review_status"));
    }

    @Override
    public void updateSourceInfo(UUID organizationId, UUID documentId, UUID versionId, JsonNode sourceInfo) {
        jdbc.update("UPDATE kb.document SET source_info_jsonb = ?, updated_at = now() WHERE organization_id = ? AND id = ?",
                json(sourceInfo), organizationId, documentId);
        jdbc.update("""
                UPDATE kb.document_version v SET source_info_jsonb = ?, updated_at = now()
                FROM kb.document d WHERE v.document_id = d.id AND d.organization_id = ? AND d.id = ? AND v.id = ?
                """, json(sourceInfo), organizationId, documentId, versionId);
    }

    @Override
    public void updateDraftMetadata(UUID organizationId, UUID documentId, String title,
                                    String libraryScope, UUID categoryId) {
        jdbc.update("""
                UPDATE kb.document SET title = ?, library_scope = ?, category_id = ?, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, title, libraryScope, categoryId, organizationId, documentId);
    }

    @Override
    @Transactional
    public ParseRunRow createParseRun(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                                      String status, String parserVersion, String provider, String providerTaskId,
                                      String errorMessage, JsonNode diagnosticResult,
                                      List<DocumentParser.TextBlock> blocks,
                                      List<DocumentParser.SourceTable> sourceTables) {
        var id = UUID.randomUUID();
        var runNo = jdbc.queryForObject("SELECT coalesce(max(run_no), 0) + 1 FROM kb.document_parse_run WHERE document_version_id = ?",
                Integer.class, versionId);
        var parseStatus = "FAILED".equals(status) ? "FAILED" : "SUCCEEDED";
        var tableIds = new java.util.HashMap<Integer, UUID>();
        var enrichedBlocks = new ArrayList<>(safe(blocks));
        for (var table : safe(sourceTables)) {
            if (table.sourceBlockNo() < 0 || table.sourceBlockNo() >= enrichedBlocks.size()) continue;
            var tableId = UUID.randomUUID();
            tableIds.put(table.sourceBlockNo(), tableId);
            var block = enrichedBlocks.get(table.sourceBlockNo());
            var attributes = new java.util.LinkedHashMap<>(block.attributes());
            attributes.put("sourceTableId", tableId.toString());
            enrichedBlocks.set(table.sourceBlockNo(), new DocumentParser.TextBlock(block.pageNo(), block.section(),
                    block.content(), block.sheetName(), block.cellRange(), block.paragraphId(), block.bbox(),
                    block.startTimeMs(), block.endTimeMs(), block.confidence(), attributes));
        }
        var initial = documents.initialize(enrichedBlocks);
        jdbc.update("""
                INSERT INTO kb.document_parse_run (
                    id, organization_id, document_id, document_version_id, run_no, status, parser_version,
                    provider, provider_task_id, error_message, result_jsonb, source_document_jsonb,
                    document_schema_version, started_at, finished_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """, id, organizationId, documentId, versionId, runNo, parseStatus, parserVersion, provider,
                providerTaskId, errorMessage, json(diagnosticResult), json(initial.sourceDocument()),
                StructuredDocumentCodec.SCHEMA_VERSION);
        for (var source : initial.sourceNodes()) {
            jdbc.update("""
                    INSERT INTO kb.document_source_node (
                        source_node_key, organization_id, parse_run_id, node_no, node_type, raw_text,
                        source_anchor_jsonb, confidence_jsonb
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, source.sourceNodeKey(), organizationId, id, source.nodeNo(), source.nodeType(),
                    source.rawText(), json(source.sourceAnchor()), json(source.confidence()));
            if (source.confidence().path("textConfidence").isNumber()
                    && source.confidence().path("textConfidence").asDouble() < 0.80) {
                insertIssue(id, source.sourceNodeKey(), "LOW_TEXT_CONFIDENCE", "WARNING", "文字识别置信度较低，请重点校对");
            }
        }
        for (var table : safe(sourceTables)) {
            if (table.sourceBlockNo() < 0 || table.sourceBlockNo() >= initial.sourceNodes().size()) continue;
            var sourceNodeKey = initial.sourceNodes().get(table.sourceBlockNo()).sourceNodeKey();
            var tableId = tableIds.getOrDefault(table.sourceBlockNo(), UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO kb.document_source_table (
                        id, organization_id, parse_run_id, source_node_key, sheet_key, sheet_name,
                        row_count, column_count, non_empty_count
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, tableId, organizationId, id, sourceNodeKey, table.sheetKey(), table.sheetName(),
                    table.rowCount(), table.columnCount(), table.nonEmptyCount());
            for (var cell : table.cells()) {
                var anchor = objectMapper.createObjectNode().put("version", 1).put("kind", "sheet_range")
                        .put("sheetKey", table.sheetKey()).put("sheetName", table.sheetName())
                        .put("range", cell.cellRange());
                jdbc.update("""
                        INSERT INTO kb.document_source_table_cell (
                            source_table_id, row_no, column_no, display_value, source_anchor_jsonb
                        ) VALUES (?, ?, ?, ?, ?)
                        """, tableId, cell.rowNo(), cell.columnNo(), cell.displayValue(), json(anchor));
            }
        }
        if (!"FAILED".equals(parseStatus)) {
            var effectiveActor = actorId == null ? createdBy(documentId) : actorId;
            var basePublicationId = jdbc.query("SELECT current_publication_id FROM kb.document WHERE organization_id = ? AND id = ?",
                    (rs, ignored) -> Optional.ofNullable(rs.getObject(1, UUID.class)), organizationId, documentId)
                    .stream().flatMap(Optional::stream).findFirst().orElse(null);
            var revisionId = UUID.randomUUID();
            var revisionNo = jdbc.queryForObject("SELECT coalesce(max(revision_no), 0) + 1 FROM kb.document_review_revision WHERE document_id = ?",
                    Integer.class, documentId);
            jdbc.update("UPDATE kb.document_review_revision SET status = 'SUPERSEDED' WHERE document_version_id = ? AND status = 'DRAFT'",
                    versionId);
            jdbc.update("""
                    INSERT INTO kb.document_review_revision (
                        id, organization_id, document_id, document_version_id, parse_run_id, revision_no,
                        base_publication_id, confirmed_document_jsonb, confirmed_text, status, created_by, updated_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', 'DRAFT', ?, ?)
                    """, revisionId, organizationId, documentId, versionId, id, revisionNo,
                    basePublicationId, json(initial.confirmedDocument()), effectiveActor, effectiveActor);
            jdbc.update("""
                    UPDATE kb.document_version
                    SET review_status = 'PENDING_REVIEW', review_reason = NULL, review_revision = ?, updated_at = now()
                    WHERE id = ?
                    """, revisionNo, versionId);
        }
        return new ParseRunRow(id, documentId, versionId, runNo, parseStatus, errorMessage, Instant.now(),
                initial.sourceDocument(), StructuredDocumentCodec.SCHEMA_VERSION);
    }

    @Override
    public void updateParseRunStatus(UUID organizationId, UUID parseRunId, String status, String errorMessage) {
        var normalized = "FAILED".equals(status) ? "FAILED" : SetLike.PARSE_STATES.contains(status) ? status : "SUCCEEDED";
        jdbc.update("""
                UPDATE kb.document_parse_run SET status = ?, error_message = ?,
                    finished_at = CASE WHEN ? IN ('SUCCEEDED', 'FAILED') THEN now() ELSE NULL END
                WHERE organization_id = ? AND id = ?
                """, normalized, errorMessage, normalized, organizationId, parseRunId);
    }

    @Override
    public Optional<ReviewView> review(UUID organizationId, UUID documentId, UUID versionId) {
        var values = jdbc.query("""
                SELECT d.title, d.library_scope, d.category_id, c.name AS category_name, d.lifecycle_status,
                       v.version_no, v.file_object_id, v.original_name, v.content_type, v.size_bytes,
                       v.status AS processing_status, v.review_status,
                       coalesce(v.source_info_jsonb, d.source_info_jsonb) AS source_info_jsonb,
                       r.id AS parse_run_id, r.run_no, r.status AS parse_status, r.error_message,
                       r.created_at AS parse_created_at, r.source_document_jsonb, r.document_schema_version,
                       rr.id AS review_revision_id, rr.revision_no, rr.lock_version, rr.base_publication_id,
                       rr.confirmed_document_jsonb, rr.excluded_review_node_ids, rr.status AS revision_status,
                       rr.failure_reason, rr.updated_at AS revision_updated_at
                FROM kb.document d
                JOIN kb.document_version v ON v.document_id = d.id
                LEFT JOIN kb.document_category c ON c.id = d.category_id
                LEFT JOIN LATERAL (
                    SELECT * FROM kb.document_review_revision value
                    WHERE value.document_version_id = v.id
                    ORDER BY CASE value.status WHEN 'DRAFT' THEN 0 WHEN 'FAILED' THEN 1 ELSE 2 END,
                             value.revision_no DESC LIMIT 1
                ) rr ON true
                LEFT JOIN kb.document_parse_run r ON r.id = rr.parse_run_id
                WHERE d.organization_id = ? AND d.id = ? AND v.id = ?
                """, (rs, ignored) -> mapReview(rs, documentId, versionId), organizationId, documentId, versionId);
        if (values.isEmpty()) return Optional.empty();
        var value = values.getFirst();
        var runId = value.parseRun() == null ? null : value.parseRun().id();
        var revisionId = value.reviewRevision() == null ? null : value.reviewRevision().id();
        return Optional.of(new ReviewView(value.documentId(), value.title(), value.libraryScope(), value.categoryId(),
                value.categoryName(), value.lifecycleStatus(), value.versionId(), value.versionNo(), value.fileObjectId(),
                value.originalName(), value.contentType(), value.size(), value.processingStatus(), value.reviewStatus(),
                value.sourceInfo(), value.parseRun(), runId == null ? List.of() : sourceNodes(runId),
                value.reviewRevision(), runId == null ? List.of() : issues(runId, revisionId),
                tags(organizationId, documentId)));
    }

    @Override
    public Optional<PublishedContentView> publishedContent(UUID organizationId, UUID documentId, UUID publicationId) {
        var args = publicationId == null ? new Object[]{organizationId, documentId}
                : new Object[]{organizationId, documentId, publicationId};
        var publicationFilter = publicationId == null ? "AND p.status = 'CURRENT'" : "AND p.id = ?";
        return jdbc.query("""
                SELECT p.id, p.document_id, p.document_version_id, p.parse_run_id, p.review_revision_id,
                       p.publication_no, p.status, coalesce(g.status, 'PENDING') AS ai_status, p.published_at,
                       v.file_object_id, v.original_name, v.content_type, v.size_bytes,
                       pr.source_document_jsonb, rr.confirmed_document_jsonb, rr.excluded_review_node_ids
                FROM kb.publication p
                JOIN kb.document_version v ON v.id = p.document_version_id
                JOIN kb.document_review_revision rr ON rr.id = p.review_revision_id
                JOIN kb.document_parse_run pr ON pr.id = rr.parse_run_id
                LEFT JOIN kb.document_ai_grant g ON g.document_id = p.document_id
                WHERE p.organization_id = ? AND p.document_id = ?
                """ + publicationFilter, (rs, ignored) -> {
            var publication = mapPublication(rs, ignored);
            var sourceNodes = sourceNodes(publication.parseRunId());
            return new PublishedContentView(publication, rs.getObject("file_object_id", UUID.class),
                    rs.getString("original_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                    read(rs.getString("source_document_jsonb")), sourceNodes,
                    read(rs.getString("confirmed_document_jsonb")),
                    uuidList(rs.getString("excluded_review_node_ids")));
        }, args).stream().findFirst();
    }

    @Override
    public Optional<TableWindow> reviewTableWindow(UUID organizationId, UUID reviewRevisionId, UUID sourceTableId,
                                                   int rowOffset, int rowLimit, int columnOffset, int columnLimit) {
        return tableWindow(organizationId, reviewRevisionId, sourceTableId, rowOffset, rowLimit,
                columnOffset, columnLimit, false);
    }

    @Override
    public Optional<TableWindow> publishedTableWindow(UUID organizationId, UUID publicationId, UUID sourceTableId,
                                                      int rowOffset, int rowLimit, int columnOffset, int columnLimit) {
        var revisionId = jdbc.query("""
                SELECT review_revision_id FROM kb.publication
                WHERE organization_id = ? AND id = ?
                """, (rs, ignored) -> rs.getObject(1, UUID.class), organizationId, publicationId)
                .stream().findFirst();
        return revisionId.flatMap(id -> tableWindow(organizationId, id, sourceTableId, rowOffset, rowLimit,
                columnOffset, columnLimit, true));
    }

    private Optional<TableWindow> tableWindow(UUID organizationId, UUID reviewRevisionId, UUID sourceTableId,
                                              int rowOffset, int rowLimit, int columnOffset, int columnLimit,
                                              boolean publishedOnly) {
        var boundedRows = Math.min(200, Math.max(1, rowLimit));
        var boundedColumns = Math.min(100, Math.max(1, columnLimit));
        var publicationJoin = publishedOnly
                ? "JOIN kb.publication p ON p.review_revision_id = r.id AND p.id IS NOT NULL "
                : "";
        var metadata = jdbc.query("""
                SELECT t.id, t.sheet_key, t.sheet_name, t.row_count, t.column_count, t.non_empty_count
                FROM kb.document_source_table t
                JOIN kb.document_review_revision r ON r.parse_run_id = t.parse_run_id
                """ + publicationJoin + """
                WHERE r.organization_id = ? AND r.id = ? AND t.id = ?
                """, (rs, ignored) -> new Object[]{rs.getString("sheet_key"), rs.getString("sheet_name"),
                rs.getInt("row_count"), rs.getInt("column_count"), rs.getInt("non_empty_count")},
                organizationId, reviewRevisionId, sourceTableId).stream().findFirst();
        if (metadata.isEmpty()) return Optional.empty();
        var cells = jdbc.query("""
                SELECT c.row_no, c.column_no, coalesce(p.confirmed_value, c.display_value) AS value,
                       (p.review_revision_id IS NOT NULL) AS patched
                FROM kb.document_source_table_cell c
                LEFT JOIN kb.document_review_table_cell_patch p
                       ON p.source_table_id = c.source_table_id AND p.row_no = c.row_no
                      AND p.column_no = c.column_no AND p.review_revision_id = ?
                WHERE c.source_table_id = ? AND c.row_no >= ? AND c.row_no < ?
                  AND c.column_no >= ? AND c.column_no < ?
                ORDER BY c.row_no, c.column_no
                """, (rs, ignored) -> new TableCellView(rs.getInt("row_no"), rs.getInt("column_no"),
                rs.getString("value"), rs.getBoolean("patched")), reviewRevisionId, sourceTableId,
                Math.max(0, rowOffset), Math.max(0, rowOffset) + boundedRows,
                Math.max(0, columnOffset), Math.max(0, columnOffset) + boundedColumns);
        var states = jdbc.query("""
                SELECT row_no, excluded, header FROM kb.document_review_table_row_state
                WHERE review_revision_id = ? AND source_table_id = ?
                  AND row_no >= ? AND row_no < ? ORDER BY row_no
                """, (rs, ignored) -> new RowState(rs.getInt("row_no"), rs.getBoolean("excluded"),
                rs.getBoolean("header")), reviewRevisionId, sourceTableId, Math.max(0, rowOffset),
                Math.max(0, rowOffset) + boundedRows);
        return Optional.of(new TableWindow(sourceTableId, (String) metadata.get()[0], (String) metadata.get()[1],
                (Integer) metadata.get()[2], (Integer) metadata.get()[3], (Integer) metadata.get()[4],
                Math.max(0, rowOffset), Math.max(0, columnOffset), cells,
                states.stream().filter(RowState::excluded).map(RowState::rowNo).toList(),
                states.stream().filter(RowState::header).map(RowState::rowNo).toList()));
    }

    @Override
    @Transactional
    public boolean saveTableReview(UUID organizationId, UUID actorId, UUID reviewRevisionId,
                                   int expectedLockVersion, UUID sourceTableId,
                                   List<CellPatch> patches, List<RowState> rows) {
        if (safe(patches).size() > 20_000 || safe(rows).size() > 20_000) {
            throw new IllegalArgumentException("单次表格修订不能超过 20,000 项");
        }
        var changed = jdbc.update("""
                UPDATE kb.document_review_revision r SET lock_version = lock_version + 1,
                    updated_by = ?, updated_at = now()
                WHERE r.organization_id = ? AND r.id = ? AND r.lock_version = ? AND r.status = 'DRAFT'
                  AND EXISTS (SELECT 1 FROM kb.document_source_table t
                              WHERE t.id = ? AND t.parse_run_id = r.parse_run_id)
                """, actorId, organizationId, reviewRevisionId, expectedLockVersion, sourceTableId);
        if (changed == 0) return false;
        for (var patch : safe(patches)) {
            jdbc.update("""
                    INSERT INTO kb.document_review_table_cell_patch (
                        review_revision_id, source_table_id, row_no, column_no, confirmed_value, updated_by
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (review_revision_id, source_table_id, row_no, column_no) DO UPDATE
                    SET confirmed_value = EXCLUDED.confirmed_value, updated_by = EXCLUDED.updated_by, updated_at = now()
                    """, reviewRevisionId, sourceTableId, patch.rowNo(), patch.columnNo(), patch.value(), actorId);
        }
        for (var row : safe(rows)) {
            jdbc.update("""
                    INSERT INTO kb.document_review_table_row_state (
                        review_revision_id, source_table_id, row_no, excluded, header, updated_by
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (review_revision_id, source_table_id, row_no) DO UPDATE
                    SET excluded = EXCLUDED.excluded, header = EXCLUDED.header,
                        updated_by = EXCLUDED.updated_by, updated_at = now()
                    """, reviewRevisionId, sourceTableId, row.rowNo(), row.excluded(), row.header(), actorId);
        }
        return true;
    }

    @Override
    public List<LargeTableRow> largeTableRows(UUID organizationId, UUID reviewRevisionId) {
        return jdbc.query("""
                WITH table_header AS (
                    SELECT t.id AS source_table_id,
                           coalesce(min(s.row_no) FILTER (WHERE s.header), min(c.row_no)) AS header_row
                    FROM kb.document_source_table t
                    JOIN kb.document_review_revision r ON r.parse_run_id = t.parse_run_id
                    JOIN kb.document_source_table_cell c ON c.source_table_id = t.id
                    LEFT JOIN kb.document_review_table_row_state s
                           ON s.review_revision_id = r.id AND s.source_table_id = t.id
                    WHERE r.organization_id = ? AND r.id = ?
                    GROUP BY t.id
                ), header_cells AS (
                    SELECT c.source_table_id, c.column_no,
                           coalesce(p.confirmed_value, c.display_value) AS header_value
                    FROM kb.document_source_table_cell c
                    JOIN table_header h ON h.source_table_id = c.source_table_id AND h.header_row = c.row_no
                    LEFT JOIN kb.document_review_table_cell_patch p
                           ON p.review_revision_id = ? AND p.source_table_id = c.source_table_id
                          AND p.row_no = c.row_no AND p.column_no = c.column_no
                )
                SELECT t.id AS source_table_id, t.sheet_name, t.column_count, c.row_no,
                       string_agg(
                           coalesce(nullif(hc.header_value, ''), '第' || (c.column_no + 1) || '列')
                           || ': ' || coalesce(p.confirmed_value, c.display_value),
                           ' | ' ORDER BY c.column_no
                       ) AS projected_text
                FROM kb.document_source_table t
                JOIN kb.document_review_revision r ON r.parse_run_id = t.parse_run_id
                JOIN table_header h ON h.source_table_id = t.id
                JOIN kb.document_source_table_cell c ON c.source_table_id = t.id AND c.row_no <> h.header_row
                LEFT JOIN header_cells hc ON hc.source_table_id = c.source_table_id AND hc.column_no = c.column_no
                LEFT JOIN kb.document_review_table_cell_patch p
                       ON p.review_revision_id = r.id AND p.source_table_id = c.source_table_id
                      AND p.row_no = c.row_no AND p.column_no = c.column_no
                LEFT JOIN kb.document_review_table_row_state state
                       ON state.review_revision_id = r.id AND state.source_table_id = c.source_table_id
                      AND state.row_no = c.row_no
                WHERE r.organization_id = ? AND r.id = ? AND coalesce(state.excluded, false) = false
                GROUP BY t.id, t.sheet_name, t.column_count, c.row_no
                ORDER BY t.sheet_name, c.row_no
                """, (rs, ignored) -> {
            var rowNo = rs.getInt("row_no");
            var columns = rs.getInt("column_count");
            var range = "A" + (rowNo + 1) + ":" + columnName(Math.max(0, columns - 1)) + (rowNo + 1);
            return new LargeTableRow(rs.getObject("source_table_id", UUID.class), rs.getString("sheet_name"),
                    rowNo, range, rs.getString("projected_text"));
        }, organizationId, reviewRevisionId, reviewRevisionId, organizationId, reviewRevisionId);
    }

    private ReviewView mapReview(ResultSet rs, UUID documentId, UUID versionId) throws SQLException {
        var parseRunId = rs.getObject("parse_run_id", UUID.class);
        ParseRunRow parse = null;
        if (parseRunId != null) {
            parse = new ParseRunRow(parseRunId, documentId, versionId, rs.getInt("run_no"),
                    rs.getString("parse_status"), rs.getString("error_message"),
                    rs.getTimestamp("parse_created_at").toInstant(), read(rs.getString("source_document_jsonb")),
                    rs.getInt("document_schema_version"));
        }
        var revisionId = rs.getObject("review_revision_id", UUID.class);
        ReviewRevisionView revision = null;
        if (revisionId != null) {
            revision = new ReviewRevisionView(revisionId, parseRunId, rs.getInt("revision_no"),
                    rs.getInt("lock_version"), rs.getObject("base_publication_id", UUID.class),
                    read(rs.getString("confirmed_document_jsonb")), uuidList(rs.getString("excluded_review_node_ids")),
                    rs.getString("revision_status"), rs.getString("failure_reason"),
                    rs.getTimestamp("revision_updated_at").toInstant());
        }
        return new ReviewView(documentId, rs.getString("title"), rs.getString("library_scope"),
                rs.getObject("category_id", UUID.class), rs.getString("category_name"), rs.getString("lifecycle_status"),
                versionId, rs.getInt("version_no"), rs.getObject("file_object_id", UUID.class),
                rs.getString("original_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                rs.getString("processing_status"), rs.getString("review_status"),
                read(rs.getString("source_info_jsonb")), parse, List.of(), revision, List.of(), List.of());
    }

    @Override
    public List<ReviewQueueItem> reviewQueue(UUID organizationId, String status, int limit) {
        var normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return jdbc.query("""
                SELECT d.id AS document_id, d.title, v.id AS version_id, v.version_no, v.original_name,
                       v.status AS processing_status, v.review_status,
                       coalesce(rr.revision_no, v.review_revision) AS review_revision,
                       c.name AS category_name, coalesce(rr.updated_at, v.updated_at) AS updated_at
                FROM kb.document d
                JOIN kb.document_version v ON v.document_id = d.id AND v.version_no = d.current_version_no
                LEFT JOIN kb.document_category c ON c.id = d.category_id
                LEFT JOIN LATERAL (
                    SELECT revision_no, updated_at FROM kb.document_review_revision x
                    WHERE x.document_version_id = v.id AND x.status IN ('DRAFT', 'FAILED')
                    ORDER BY x.revision_no DESC LIMIT 1
                ) rr ON true
                WHERE d.organization_id = ? AND (CAST(? AS text) IS NULL OR v.review_status = ?)
                  AND v.review_status IN ('PENDING_REVIEW', 'REJECTED')
                ORDER BY coalesce(rr.updated_at, v.updated_at) DESC LIMIT ?
                """, (rs, ignored) -> new ReviewQueueItem(rs.getObject("document_id", UUID.class),
                rs.getString("title"), rs.getObject("version_id", UUID.class), rs.getInt("version_no"),
                rs.getString("original_name"), rs.getString("processing_status"), rs.getString("review_status"),
                rs.getInt("review_revision"), rs.getString("category_name"), rs.getTimestamp("updated_at").toInstant()),
                organizationId, normalized, normalized, Math.min(500, Math.max(1, limit)));
    }

    @Override
    @Transactional
    public boolean saveReview(UUID organizationId, UUID actorId, ReviewUpdate update) {
        var changed = jdbc.update("""
                UPDATE kb.document_review_revision r
                SET confirmed_document_jsonb = ?, excluded_review_node_ids = ?, lock_version = lock_version + 1,
                    updated_by = ?, updated_at = now(), failure_reason = NULL
                FROM kb.document d
                WHERE r.document_id = d.id AND d.organization_id = ? AND r.document_id = ?
                  AND r.document_version_id = ? AND r.id = ? AND r.lock_version = ? AND r.status = 'DRAFT'
                  AND (r.base_publication_id IS NOT DISTINCT FROM ?)
                """, json(update.confirmedDocument()), json(objectMapper.valueToTree(safe(update.excludedReviewNodeIds()))),
                actorId, organizationId, update.documentId(), update.versionId(), update.reviewRevisionId(),
                update.expectedLockVersion(), update.basePublicationId());
        if (changed == 0) return false;
        updateMetadata(organizationId, actorId, update);
        for (var action : safe(update.issueActions())) {
            jdbc.update("""
                    INSERT INTO kb.document_review_issue_state (
                        review_revision_id, parse_issue_id, status, resolution, updated_by
                    ) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (review_revision_id, parse_issue_id) DO UPDATE
                    SET status = EXCLUDED.status, resolution = EXCLUDED.resolution,
                        updated_by = EXCLUDED.updated_by, updated_at = now()
                    """, update.reviewRevisionId(), action.issueId(), action.status(), action.resolution(), actorId);
        }
        jdbc.update("UPDATE kb.document_version SET review_revision = review_revision + 1, updated_at = now() WHERE id = ?",
                update.versionId());
        return true;
    }

    @Override
    @Transactional
    public Optional<RevisionRow> createRevision(UUID organizationId, UUID actorId, UUID documentId,
                                                UUID basePublicationId) {
        var base = jdbc.query("""
                SELECT p.document_version_id, rr.parse_run_id, rr.confirmed_document_jsonb,
                       rr.excluded_review_node_ids
                FROM kb.publication p
                JOIN kb.document d ON d.current_publication_id = p.id
                JOIN kb.document_review_revision rr ON rr.id = p.review_revision_id
                WHERE p.organization_id = ? AND p.document_id = ? AND p.id = ? AND p.status = 'CURRENT'
                FOR UPDATE
                """, (rs, ignored) -> new Object[]{rs.getObject("document_version_id", UUID.class),
                rs.getObject("parse_run_id", UUID.class), read(rs.getString("confirmed_document_jsonb")),
                read(rs.getString("excluded_review_node_ids"))}, organizationId, documentId, basePublicationId)
                .stream().findFirst();
        if (base.isEmpty()) return Optional.empty();
        var existing = jdbc.query("""
                SELECT id, revision_no, lock_version FROM kb.document_review_revision
                WHERE organization_id = ? AND document_id = ? AND status = 'DRAFT' ORDER BY revision_no DESC LIMIT 1
                """, (rs, ignored) -> new RevisionRow(documentId, (UUID) base.get()[0],
                rs.getObject("id", UUID.class), rs.getInt("revision_no"), rs.getInt("lock_version")),
                organizationId, documentId).stream().findFirst();
        if (existing.isPresent()) return existing;
        var id = UUID.randomUUID();
        var revisionNo = jdbc.queryForObject("SELECT coalesce(max(revision_no), 0) + 1 FROM kb.document_review_revision WHERE document_id = ?",
                Integer.class, documentId);
        jdbc.update("""
                INSERT INTO kb.document_review_revision (
                    id, organization_id, document_id, document_version_id, parse_run_id, revision_no,
                    base_publication_id, confirmed_document_jsonb, excluded_review_node_ids,
                    confirmed_text, status, created_by, updated_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '', 'DRAFT', ?, ?)
                """, id, organizationId, documentId, base.get()[0], base.get()[1], revisionNo, basePublicationId,
                json((JsonNode) base.get()[2]), json((JsonNode) base.get()[3]), actorId, actorId);
        jdbc.update("UPDATE kb.document_version SET review_status = 'PENDING_REVIEW', review_revision = ?, updated_at = now() WHERE id = ?",
                revisionNo, base.get()[0]);
        return Optional.of(new RevisionRow(documentId, (UUID) base.get()[0], id, revisionNo, 0));
    }

    private void updateMetadata(UUID organizationId, UUID actorId, ReviewUpdate update) {
        jdbc.update("""
                UPDATE kb.document SET title = ?, library_scope = ?, category_id = ?, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, update.title(), update.libraryScope(), update.categoryId(), organizationId, update.documentId());
        replaceTags(organizationId, actorId, update.documentId(), update.tags());
    }

    @Override
    public boolean reservePublication(UUID organizationId, UUID documentId, UUID versionId, UUID reviewRevisionId,
                                      int expectedLockVersion, UUID basePublicationId, String confirmedText) {
        return jdbc.update("""
                UPDATE kb.document_review_revision r
                SET status = 'BUILDING', confirmed_text = ?, failure_reason = NULL, updated_at = now()
                FROM kb.document d
                WHERE r.document_id = d.id AND d.organization_id = ? AND d.id = ?
                  AND r.document_version_id = ? AND r.id = ? AND r.lock_version = ? AND r.status = 'DRAFT'
                  AND r.base_publication_id IS NOT DISTINCT FROM ?
                  AND d.current_publication_id IS NOT DISTINCT FROM ?
                """, confirmedText, organizationId, documentId, versionId, reviewRevisionId,
                expectedLockVersion, basePublicationId, basePublicationId) > 0;
    }

    @Override
    @Transactional
    public PublicationRow publish(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                                  UUID reviewRevisionId, int expectedLockVersion) {
        var revision = jdbc.query("""
                SELECT r.parse_run_id, r.base_publication_id
                FROM kb.document_review_revision r JOIN kb.document d ON d.id = r.document_id
                JOIN kb.document_version v ON v.id = r.document_version_id
                WHERE r.organization_id = ? AND r.document_id = ? AND r.document_version_id = ?
                  AND r.id = ? AND r.lock_version = ? AND r.status = 'BUILDING'
                  AND d.current_publication_id IS NOT DISTINCT FROM r.base_publication_id
                  AND v.status = 'READY' FOR UPDATE
                """, (rs, ignored) -> new UUID[]{rs.getObject("parse_run_id", UUID.class),
                rs.getObject("base_publication_id", UUID.class)}, organizationId, documentId, versionId,
                reviewRevisionId, expectedLockVersion).stream().findFirst();
        if (revision.isEmpty()) return null;
        var oldVersion = jdbc.query("SELECT document_version_id FROM kb.publication WHERE organization_id = ? AND document_id = ? AND status = 'CURRENT'",
                (rs, ignored) -> rs.getObject(1, UUID.class), organizationId, documentId).stream().findFirst().orElse(null);
        jdbc.update("UPDATE kb.publication SET status = 'SUPERSEDED' WHERE organization_id = ? AND document_id = ? AND status = 'CURRENT'",
                organizationId, documentId);
        jdbc.update("UPDATE kb.document_review_revision SET status = 'SUPERSEDED' WHERE organization_id = ? AND document_id = ? AND status = 'PUBLISHED'",
                organizationId, documentId);
        if (oldVersion != null && !oldVersion.equals(versionId)) {
            jdbc.update("UPDATE kb.document_version SET review_status = 'SUPERSEDED' WHERE id = ?", oldVersion);
        }
        var publicationId = UUID.randomUUID();
        var publicationNo = jdbc.queryForObject("SELECT coalesce(max(publication_no), 0) + 1 FROM kb.publication WHERE document_id = ?",
                Integer.class, documentId);
        jdbc.update("""
                INSERT INTO kb.publication (
                    id, organization_id, document_id, document_version_id, parse_run_id, review_revision_id,
                    publication_no, status, metadata_snapshot_jsonb, published_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'CURRENT', ?, ?)
                """, publicationId, organizationId, documentId, versionId, revision.get()[0], reviewRevisionId,
                publicationNo, json(publicationSnapshot(organizationId, documentId, versionId)), actorId);
        jdbc.update("""
                UPDATE kb.document_review_revision SET status = 'PUBLISHED', published_at = now(), updated_at = now()
                WHERE id = ?
                """, reviewRevisionId);
        jdbc.update("""
                UPDATE kb.document_version SET review_status = 'PUBLISHED', reviewed_by = ?, reviewed_at = now(),
                    review_reason = NULL, updated_at = now() WHERE id = ?
                """, actorId, versionId);
        jdbc.update("""
                UPDATE kb.document SET current_publication_id = ?, status = 'READY', parse_error = NULL, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, publicationId, organizationId, documentId);
        return new PublicationRow(publicationId, documentId, versionId, revision.get()[0], reviewRevisionId,
                publicationNo, "CURRENT", aiStatus(organizationId, documentId), Instant.now());
    }

    @Override
    public void failRevision(UUID organizationId, UUID reviewRevisionId, String failureReason) {
        jdbc.update("""
                UPDATE kb.document_review_revision SET status = 'FAILED', failure_reason = ?, updated_at = now()
                WHERE organization_id = ? AND id = ? AND status = 'BUILDING'
                """, failureReason, organizationId, reviewRevisionId);
    }

    private JsonNode publicationSnapshot(UUID organizationId, UUID documentId, UUID versionId) {
        var values = jdbc.queryForMap("""
                SELECT d.title, d.library_scope, d.category_id, v.source_info_jsonb
                FROM kb.document d JOIN kb.document_version v ON v.id = ? AND v.document_id = d.id
                WHERE d.organization_id = ? AND d.id = ?
                """, versionId, organizationId, documentId);
        var snapshot = objectMapper.createObjectNode();
        snapshot.put("title", String.valueOf(values.get("title")));
        snapshot.put("libraryScope", String.valueOf(values.get("library_scope")));
        if (values.get("category_id") != null) snapshot.put("categoryId", values.get("category_id").toString());
        snapshot.set("sourceInfo", values.get("source_info_jsonb") instanceof PGobject value
                ? read(value.getValue()) : objectMapper.valueToTree(values.get("source_info_jsonb")));
        snapshot.set("tags", objectMapper.valueToTree(tags(organizationId, documentId)));
        return snapshot;
    }

    @Override
    @Transactional
    public boolean reject(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                          UUID reviewRevisionId, int expectedLockVersion, String reason) {
        var changed = jdbc.update("""
                UPDATE kb.document_review_revision r SET status = 'FAILED', failure_reason = ?, updated_by = ?, updated_at = now()
                FROM kb.document d WHERE r.document_id = d.id AND d.organization_id = ? AND d.id = ?
                  AND r.document_version_id = ? AND r.id = ? AND r.lock_version = ? AND r.status = 'DRAFT'
                """, reason, actorId, organizationId, documentId, versionId, reviewRevisionId, expectedLockVersion);
        if (changed > 0) jdbc.update("""
                UPDATE kb.document_version SET review_status = 'REJECTED', review_reason = ?, reviewed_by = ?,
                    reviewed_at = now(), updated_at = now() WHERE id = ?
                """, reason, actorId, versionId);
        return changed > 0;
    }

    @Override
    public boolean reserveReparse(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                                  UUID reviewRevisionId, int expectedLockVersion) {
        return jdbc.update("""
                UPDATE kb.document_version v
                SET review_status = 'PENDING_REVIEW', review_reason = 'REPARSE_QUEUED', reviewed_by = ?,
                    reviewed_at = now(), updated_at = now()
                FROM kb.document d, kb.document_review_revision r
                WHERE v.document_id = d.id AND r.document_version_id = v.id
                  AND d.organization_id = ? AND d.id = ? AND v.id = ? AND r.id = ?
                  AND r.lock_version = ? AND r.status IN ('DRAFT', 'FAILED')
                  AND v.review_reason IS DISTINCT FROM 'REPARSE_QUEUED'
                """, actorId, organizationId, documentId, versionId, reviewRevisionId, expectedLockVersion) > 0;
    }

    @Override
    public boolean reserveReparseWithoutRevision(UUID organizationId, UUID actorId, UUID documentId, UUID versionId) {
        return jdbc.update("""
                UPDATE kb.document_version v
                SET review_status = 'PENDING_REVIEW', review_reason = 'REPARSE_QUEUED', reviewed_by = ?,
                    reviewed_at = now(), updated_at = now()
                FROM kb.document d
                WHERE v.document_id = d.id AND d.organization_id = ? AND d.id = ? AND v.id = ?
                  AND v.status IN ('FAILED', 'PENDING_PROVIDER', 'REJECTED')
                  AND v.review_reason IS DISTINCT FROM 'REPARSE_QUEUED'
                """, actorId, organizationId, documentId, versionId) > 0;
    }

    @Override
    public boolean hasPublication(UUID organizationId, UUID documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM kb.publication WHERE organization_id = ? AND document_id = ?)",
                Boolean.class, organizationId, documentId));
    }

    @Override
    public boolean updateLifecycle(UUID organizationId, UUID actorId, UUID documentId, String status, String reason) {
        var disabled = "DISABLED".equals(status);
        return jdbc.update("""
                UPDATE kb.document SET lifecycle_status = ?, disabled_by = ?, disabled_at = ?, disabled_reason = ?,
                    updated_at = now() WHERE organization_id = ? AND id = ?
                """, status, disabled ? actorId : null, disabled ? java.sql.Timestamp.from(Instant.now()) : null,
                disabled ? reason : null, organizationId, documentId) > 0;
    }

    @Override
    public Optional<PublicationRow> currentPublication(UUID organizationId, UUID documentId) {
        return publicationsQuery("WHERE p.organization_id = ? AND p.document_id = ? AND p.status = 'CURRENT'",
                organizationId, documentId).stream().findFirst();
    }

    @Override
    public Optional<PublicationRow> currentPublicationById(UUID organizationId, UUID publicationId) {
        return publicationsQuery("WHERE p.organization_id = ? AND p.id = ? AND p.status = 'CURRENT'",
                organizationId, publicationId).stream().findFirst();
    }

    @Override
    public List<PublicationRow> publications(UUID organizationId, UUID documentId) {
        return publicationsQuery("WHERE p.organization_id = ? AND p.document_id = ? ORDER BY p.publication_no DESC",
                organizationId, documentId);
    }

    private List<PublicationRow> publicationsQuery(String where, Object... args) {
        return jdbc.query("""
                SELECT p.id, p.document_id, p.document_version_id, p.parse_run_id, p.review_revision_id,
                       p.publication_no, p.status, coalesce(g.status, 'PENDING') AS ai_status, p.published_at
                FROM kb.publication p LEFT JOIN kb.document_ai_grant g ON g.document_id = p.document_id
                """ + where, this::mapPublication, args);
    }

    @Override
    public List<String> publicationTags(UUID organizationId, UUID publicationId) {
        return jdbc.query("""
                SELECT value #>> '{}' AS display_name FROM kb.publication p
                CROSS JOIN LATERAL jsonb_array_elements(coalesce(p.metadata_snapshot_jsonb->'tags', '[]'::jsonb)) value
                WHERE p.organization_id = ? AND p.id = ? ORDER BY display_name
                """, (rs, ignored) -> rs.getString(1), organizationId, publicationId);
    }

    @Override
    @Transactional
    public boolean updateAiUsage(UUID organizationId, UUID actorId, UUID documentId, String action, String reason) {
        var status = switch (action) {
            case "APPROVE" -> "APPROVED";
            case "REVOKE" -> "REVOKED";
            case "REJECT" -> "REJECTED";
            default -> throw new IllegalArgumentException("无效AI授权动作");
        };
        var exists = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM kb.document d WHERE d.organization_id = ? AND d.id = ?
                  AND d.lifecycle_status = 'ACTIVE' AND d.current_publication_id IS NOT NULL)
                """, Boolean.class, organizationId, documentId));
        if (!exists) return false;
        jdbc.update("""
                INSERT INTO kb.document_ai_grant (document_id, organization_id, status, reason, updated_by)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (document_id) DO UPDATE SET status = EXCLUDED.status, reason = EXCLUDED.reason,
                    updated_by = EXCLUDED.updated_by, updated_at = now()
                """, documentId, organizationId, status, reason, actorId);
        return true;
    }

    @Override
    public List<String> tags(UUID organizationId, UUID documentId) {
        return jdbc.query("""
                SELECT t.display_name FROM kb.tag t JOIN kb.document_tag dt ON dt.tag_id = t.id
                JOIN kb.document d ON d.id = dt.document_id
                WHERE d.organization_id = ? AND d.id = ? ORDER BY t.display_name
                """, (rs, ignored) -> rs.getString(1), organizationId, documentId);
    }

    @Override
    @Transactional
    public void replaceTags(UUID organizationId, UUID actorId, UUID documentId, List<String> tagNames) {
        jdbc.update("DELETE FROM kb.document_tag WHERE document_id = ?", documentId);
        for (var display : safe(tagNames).stream().map(String::trim).filter(value -> !value.isBlank()).distinct().toList()) {
            var normalized = display.toLowerCase(java.util.Locale.ROOT);
            jdbc.update("""
                    INSERT INTO kb.tag (id, organization_id, normalized_name, display_name, created_by)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT (organization_id, normalized_name) DO NOTHING
                    """, UUID.randomUUID(), organizationId, normalized, display, actorId);
            jdbc.update("""
                    INSERT INTO kb.document_tag (document_id, tag_id)
                    SELECT ?, id FROM kb.tag WHERE organization_id = ? AND normalized_name = ? ON CONFLICT DO NOTHING
                    """, documentId, organizationId, normalized);
        }
    }

    private List<SourceNodeView> sourceNodes(UUID parseRunId) {
        return jdbc.query("""
                SELECT source_node_key, node_no, node_type, raw_text, source_anchor_jsonb, confidence_jsonb
                FROM kb.document_source_node WHERE parse_run_id = ? ORDER BY node_no
                """, (rs, ignored) -> new SourceNodeView(rs.getObject("source_node_key", UUID.class),
                rs.getInt("node_no"), rs.getString("node_type"), rs.getString("raw_text"),
                read(rs.getString("source_anchor_jsonb")), read(rs.getString("confidence_jsonb"))), parseRunId);
    }

    private List<ParseIssueView> issues(UUID parseRunId, UUID reviewRevisionId) {
        return jdbc.query("""
                SELECT i.id, i.issue_code, i.severity, i.message,
                       coalesce(s.status, 'OPEN') AS status, s.resolution,
                       coalesce(jsonb_agg(link.source_node_key) FILTER (WHERE link.source_node_key IS NOT NULL), '[]'::jsonb) AS source_keys
                FROM kb.document_parse_issue i
                LEFT JOIN kb.document_parse_issue_source_node link ON link.parse_issue_id = i.id
                LEFT JOIN kb.document_review_issue_state s
                       ON s.parse_issue_id = i.id AND s.review_revision_id = ?
                WHERE i.parse_run_id = ?
                GROUP BY i.id, i.issue_code, i.severity, i.message, s.status, s.resolution
                ORDER BY CASE i.severity WHEN 'BLOCKER' THEN 1 WHEN 'WARNING' THEN 2 ELSE 3 END, i.issue_code
                """, (rs, ignored) -> new ParseIssueView(rs.getObject("id", UUID.class),
                uuidList(rs.getString("source_keys")), rs.getString("issue_code"), rs.getString("severity"),
                rs.getString("message"), rs.getString("status"), rs.getString("resolution")),
                reviewRevisionId, parseRunId);
    }

    private PublicationRow mapPublication(ResultSet rs, int ignored) throws SQLException {
        return new PublicationRow(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("document_version_id", UUID.class), rs.getObject("parse_run_id", UUID.class),
                rs.getObject("review_revision_id", UUID.class), rs.getInt("publication_no"), rs.getString("status"),
                rs.getString("ai_status"), rs.getTimestamp("published_at").toInstant());
    }

    private String aiStatus(UUID organizationId, UUID documentId) {
        return jdbc.query("SELECT status FROM kb.document_ai_grant WHERE organization_id = ? AND document_id = ?",
                (rs, ignored) -> rs.getString(1), organizationId, documentId).stream().findFirst().orElse("PENDING");
    }

    private void insertIssue(UUID parseRunId, UUID sourceNodeKey, String code, String severity, String message) {
        var issueId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kb.document_parse_issue (id, parse_run_id, issue_code, severity, message)
                VALUES (?, ?, ?, ?, ?)
                """, issueId, parseRunId, code, severity, message);
        jdbc.update("""
                INSERT INTO kb.document_parse_issue_source_node (parse_issue_id, source_node_key) VALUES (?, ?)
                """, issueId, sourceNodeKey);
    }

    private UUID createdBy(UUID documentId) {
        return jdbc.queryForObject("SELECT created_by FROM kb.document WHERE id = ?", UUID.class, documentId);
    }

    private String columnName(int column) {
        var value = Math.max(0, column);
        var result = new StringBuilder();
        do {
            result.insert(0, (char) ('A' + value % 26));
            value = value / 26 - 1;
        } while (value >= 0);
        return result.toString();
    }

    private List<UUID> uuidList(String value) {
        return documents.uuidList(read(value));
    }

    private PGobject json(JsonNode node) {
        try {
            var value = new PGobject();
            value.setType("jsonb");
            value.setValue(node == null ? "{}" : node.toString());
            return value;
        } catch (SQLException exception) {
            throw new IllegalArgumentException("JSON数据无效", exception);
        }
    }

    private JsonNode read(String value) {
        try { return value == null ? objectMapper.createObjectNode() : objectMapper.readTree(value); }
        catch (Exception exception) { return objectMapper.createObjectNode(); }
    }

    private <T> List<T> safe(List<T> value) { return value == null ? List.of() : value; }

    private static final class SetLike {
        private static final java.util.Set<String> PARSE_STATES = java.util.Set.of("QUEUED", "PROCESSING", "SUCCEEDED");
        private SetLike() { }
    }
}
