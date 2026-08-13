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
import com.fasterxml.jackson.databind.node.ArrayNode;
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

    public JdbcKnowledgeGovernanceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DuplicateMatch> exactMatches(UUID organizationId, String sha256) {
        return duplicateRows("""
                WHERE d.organization_id = ? AND v.sha256 = ?
                ORDER BY d.updated_at DESC, v.version_no DESC
                """, organizationId, sha256);
    }

    @Override
    public List<DuplicateMatch> possibleMatches(UUID organizationId, String normalizedStem, String documentType,
                                                List<UUID> objectRefIds) {
        var refs = objectRefIds == null ? List.<UUID>of() : objectRefIds;
        if (refs.isEmpty()) {
            return jdbc.query(duplicateSelect() + """
                    WHERE d.organization_id = ? AND d.document_type = ?
                    ORDER BY d.updated_at DESC, v.version_no DESC
                    """, this::mapDuplicate, organizationId, documentType);
        }
        var placeholders = String.join(",", java.util.Collections.nCopies(refs.size(), "?"));
        var sql = duplicateSelect() + """
                WHERE d.organization_id = ?
                  AND (d.document_type = ? OR EXISTS (
                    SELECT 1 FROM kb.document_relation dr
                    WHERE dr.document_id = d.id AND dr.object_ref_id IN (%s)
                  ))
                ORDER BY d.updated_at DESC, v.version_no DESC
                """.formatted(placeholders);
        var args = new ArrayList<Object>();
        args.add(organizationId); args.add(documentType); args.addAll(refs);
        return jdbc.query(sql, this::mapDuplicate, args.toArray());
    }

    private List<DuplicateMatch> duplicateRows(String where, Object... args) {
        return jdbc.query(duplicateSelect() + where, this::mapDuplicate, args);
    }

    private String duplicateSelect() {
        return """
                SELECT d.id AS document_id, v.id AS version_id, v.version_no, d.title, v.original_name,
                       d.document_type, v.sha256, d.lifecycle_status, v.review_status
                FROM kb.document d JOIN kb.document_version v ON v.document_id = d.id
                """;
    }

    private DuplicateMatch mapDuplicate(ResultSet rs, int ignored) throws SQLException {
        return new DuplicateMatch(rs.getObject("document_id", UUID.class), rs.getObject("version_id", UUID.class),
                rs.getInt("version_no"), rs.getString("title"), rs.getString("original_name"),
                rs.getString("document_type"), rs.getString("sha256"), null, 0,
                rs.getString("lifecycle_status"), rs.getString("review_status"));
    }

    @Override
    public void updateMediaConsent(UUID organizationId, UUID versionId, boolean consent, UUID actorId) {
        jdbc.update("""
                UPDATE kb.document_version v
                SET media_processing_consent = ?, media_consent_by = ?, media_consent_at = now(), updated_at = now()
                FROM kb.document d
                WHERE v.document_id = d.id AND d.organization_id = ? AND v.id = ?
                """, consent, actorId, organizationId, versionId);
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
    public void updateDraftMetadata(UUID organizationId, UUID documentId, String title, String documentType,
                                    String libraryScope, UUID categoryId) {
        jdbc.update("""
                UPDATE kb.document SET title = ?, document_type = ?, library_scope = ?, category_id = ?, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, title, documentType, libraryScope, categoryId, organizationId, documentId);
    }

    @Override
    public boolean hasMediaConsent(UUID organizationId, UUID versionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT v.media_processing_consent
                FROM kb.document_version v JOIN kb.document d ON d.id = v.document_id
                WHERE d.organization_id = ? AND v.id = ?
                """, Boolean.class, organizationId, versionId));
    }

    @Override
    @Transactional
    public ParseRunRow createParseRun(UUID organizationId, UUID documentId, UUID versionId, String status,
                                      String parserVersion, String provider, String providerTaskId,
                                      String errorMessage, JsonNode result, List<DocumentParser.TextBlock> blocks,
                                      List<ExtractedFieldWrite> fields) {
        var id = UUID.randomUUID();
        var runNo = jdbc.queryForObject("""
                SELECT coalesce(max(run_no), 0) + 1 FROM kb.document_parse_run WHERE document_version_id = ?
                """, Integer.class, versionId);
        jdbc.update("""
                INSERT INTO kb.document_parse_run (
                    id, organization_id, document_id, document_version_id, run_no, status, parser_version,
                    provider, provider_task_id, error_message, result_jsonb, started_at, finished_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), CASE WHEN ? = 'PROCESSING' THEN NULL ELSE now() END)
                """, id, organizationId, documentId, versionId, runNo, status, parserVersion, provider,
                providerTaskId, errorMessage, json(result), status);
        var blockNo = 0;
        for (var block : blocks == null ? List.<DocumentParser.TextBlock>of() : blocks) {
            var blockId = UUID.randomUUID();
            var normalized = block.content() == null ? "" : block.content().replaceAll("[\\t\\r]+", " ").strip();
            jdbc.update("""
                    INSERT INTO kb.document_parse_block (
                        id, parse_run_id, block_no, page_no, sheet_name, cell_range, paragraph_id, bbox_jsonb,
                        start_time_ms, end_time_ms, section, raw_text, normalized_text, confidence, review_status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                    """, blockId, id, blockNo++, block.pageNo(), block.sheetName(), block.cellRange(),
                    block.paragraphId(), block.bbox().isEmpty() ? null : json(objectMapper.valueToTree(block.bbox())),
                    block.startTimeMs(), block.endTimeMs(), block.section(), block.content(), normalized,
                    block.confidence());
            if (block.confidence() != null && block.confidence() < 0.80) {
                insertIssue(id, blockId, null, "LOW_BLOCK_CONFIDENCE", "WARNING", "解析文本置信度低于80%，需要人工确认");
            }
        }
        for (var field : fields == null ? List.<ExtractedFieldWrite>of() : fields) {
            var fieldId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO kb.document_extract_field (
                        id, parse_run_id, field_code, field_name, raw_value, normalized_value,
                        source_unit, standard_unit, confidence, required, conflict, candidates_jsonb
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, fieldId, id, field.code(), field.name(), field.rawValue(),
                    field.normalizedValue(), field.sourceUnit(), field.standardUnit(), field.confidence(),
                    field.required(), field.conflict(), json(field.candidates()));
            if (field.required() && (field.rawValue() == null || field.rawValue().isBlank())) {
                insertIssue(id, null, fieldId, "REQUIRED_FIELD_MISSING", "BLOCKER", "必填字段“" + field.name() + "”未识别");
            }
            if (field.conflict()) {
                insertIssue(id, null, fieldId, "FIELD_CANDIDATE_CONFLICT", "BLOCKER", "字段“" + field.name() + "”存在多个候选值");
            } else if (field.confidence() < 0.80) {
                insertIssue(id, null, fieldId, "LOW_FIELD_CONFIDENCE", "WARNING", "字段“" + field.name() + "”置信度低于80%");
            }
        }
        jdbc.update("""
                UPDATE kb.document_version
                SET review_status = 'PENDING_REVIEW', review_reason = NULL, review_revision = review_revision + 1,
                    updated_at = now() WHERE id = ?
                """, versionId);
        return new ParseRunRow(id, documentId, versionId, runNo, status, parserVersion, provider,
                providerTaskId, errorMessage, Instant.now());
    }

    @Override
    public void updateParseRunStatus(UUID organizationId, UUID parseRunId, String status, String errorMessage) {
        jdbc.update("""
                UPDATE kb.document_parse_run SET status = ?, error_message = ?, finished_at = now()
                WHERE organization_id = ? AND id = ?
                """, status, errorMessage, organizationId, parseRunId);
    }

    @Override
    public Optional<ReviewView> review(UUID organizationId, UUID documentId, UUID versionId) {
        var rows = jdbc.query("""
                SELECT d.id AS document_id, d.title, d.document_type, d.library_scope, d.category_id,
                       c.name AS category_name, d.lifecycle_status, v.id AS version_id, v.version_no,
                       v.original_name, v.content_type, v.size_bytes, v.status AS processing_status,
                       v.review_status, v.review_revision, v.media_processing_consent,
                       coalesce(v.source_info_jsonb, d.source_info_jsonb) AS source_info_jsonb,
                       r.id AS parse_run_id, r.run_no, r.status AS parse_status, r.parser_version,
                       r.provider, r.provider_task_id, r.error_message, r.created_at AS parse_created_at
                FROM kb.document d
                JOIN kb.document_version v ON v.document_id = d.id
                LEFT JOIN kb.document_category c ON c.id = d.category_id
                LEFT JOIN LATERAL (
                    SELECT * FROM kb.document_parse_run pr
                    WHERE pr.document_version_id = v.id ORDER BY pr.run_no DESC LIMIT 1
                ) r ON true
                WHERE d.organization_id = ? AND d.id = ? AND v.id = ?
                """, (rs, ignored) -> {
            var parseRunId = rs.getObject("parse_run_id", UUID.class);
            var parse = parseRunId == null ? null : new ParseRunRow(parseRunId, documentId, versionId,
                    rs.getInt("run_no"), rs.getString("parse_status"), rs.getString("parser_version"),
                    rs.getString("provider"), rs.getString("provider_task_id"), rs.getString("error_message"),
                    rs.getTimestamp("parse_created_at").toInstant());
            return new ReviewView(documentId, rs.getString("title"), rs.getString("document_type"),
                    rs.getString("library_scope"), rs.getObject("category_id", UUID.class),
                    rs.getString("category_name"), rs.getString("lifecycle_status"), versionId,
                    rs.getInt("version_no"), rs.getString("original_name"), rs.getString("content_type"),
                    rs.getLong("size_bytes"), rs.getString("processing_status"), rs.getString("review_status"),
                    rs.getInt("review_revision"), rs.getBoolean("media_processing_consent"),
                    read(rs.getString("source_info_jsonb")), parse,
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }, organizationId, documentId, versionId);
        var header = rows.stream().findFirst();
        if (header.isEmpty()) return Optional.empty();
        var value = header.get();
        var parseRunId = value.parseRun() == null ? null : value.parseRun().id();
        return Optional.of(new ReviewView(value.documentId(), value.title(), value.documentType(),
                value.libraryScope(), value.categoryId(), value.categoryName(), value.lifecycleStatus(),
                value.versionId(), value.versionNo(), value.originalName(), value.contentType(), value.size(),
                value.processingStatus(), value.reviewStatus(), value.reviewRevision(), value.mediaProcessingConsent(),
                value.sourceInfo(), value.parseRun(), parseRunId == null ? List.of() : blocks(parseRunId),
                parseRunId == null ? List.of() : fields(parseRunId),
                parseRunId == null ? List.of() : issues(parseRunId), tags(organizationId, documentId),
                relations(organizationId, documentId)));
    }

    @Override
    public List<ReviewQueueItem> reviewQueue(UUID organizationId, String status, int limit) {
        var normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return jdbc.query("""
                SELECT d.id AS document_id, d.title, d.document_type, v.id AS version_id, v.version_no,
                       v.original_name, v.status AS processing_status, v.review_status, v.review_revision,
                       c.name AS category_name, v.updated_at
                FROM kb.document d
                JOIN kb.document_version v ON v.document_id = d.id AND v.version_no = d.current_version_no
                LEFT JOIN kb.document_category c ON c.id = d.category_id
                WHERE d.organization_id = ?
                  AND (CAST(? AS text) IS NULL OR v.review_status = ?)
                  AND v.review_status IN ('PENDING_REVIEW', 'REJECTED')
                ORDER BY v.updated_at DESC LIMIT ?
                """, (rs, ignored) -> new ReviewQueueItem(rs.getObject("document_id", UUID.class),
                rs.getString("title"), rs.getString("document_type"), rs.getObject("version_id", UUID.class),
                rs.getInt("version_no"), rs.getString("original_name"), rs.getString("processing_status"),
                rs.getString("review_status"), rs.getInt("review_revision"), rs.getString("category_name"),
                rs.getTimestamp("updated_at").toInstant()), organizationId, normalized, normalized,
                Math.min(500, Math.max(1, limit)));
    }

    private List<ParseBlockView> blocks(UUID parseRunId) {
        return jdbc.query("""
                SELECT id, block_no, page_no, sheet_name, cell_range, paragraph_id, bbox_jsonb,
                       start_time_ms, end_time_ms, section, raw_text, normalized_text, confirmed_text,
                       confidence, review_status
                FROM kb.document_parse_block WHERE parse_run_id = ? ORDER BY block_no
                """, (rs, ignored) -> new ParseBlockView(rs.getObject("id", UUID.class), rs.getInt("block_no"),
                nullableInt(rs, "page_no"), rs.getString("sheet_name"), rs.getString("cell_range"),
                rs.getString("paragraph_id"), read(rs.getString("bbox_jsonb")), nullableLong(rs, "start_time_ms"),
                nullableLong(rs, "end_time_ms"), rs.getString("section"), rs.getString("raw_text"),
                rs.getString("normalized_text"), rs.getString("confirmed_text"), nullableDouble(rs, "confidence"),
                rs.getString("review_status")), parseRunId);
    }

    private List<ExtractedFieldView> fields(UUID parseRunId) {
        return jdbc.query("""
                SELECT id, field_code, field_name, raw_value, normalized_value, confirmed_value,
                       source_unit, standard_unit, confidence, required, conflict, candidates_jsonb, review_status
                FROM kb.document_extract_field WHERE parse_run_id = ? ORDER BY required DESC, field_name
                """, (rs, ignored) -> new ExtractedFieldView(rs.getObject("id", UUID.class),
                rs.getString("field_code"), rs.getString("field_name"), rs.getString("raw_value"),
                rs.getString("normalized_value"), rs.getString("confirmed_value"), rs.getString("source_unit"),
                rs.getString("standard_unit"), nullableDouble(rs, "confidence"), rs.getBoolean("required"),
                rs.getBoolean("conflict"), read(rs.getString("candidates_jsonb")),
                rs.getString("review_status")), parseRunId);
    }

    private List<ParseIssueView> issues(UUID parseRunId) {
        return jdbc.query("""
                SELECT id, parse_block_id, extract_field_id, issue_code, severity, message, status, resolution
                FROM kb.document_parse_issue WHERE parse_run_id = ?
                ORDER BY CASE severity WHEN 'BLOCKER' THEN 1 WHEN 'WARNING' THEN 2 ELSE 3 END, issue_code
                """, (rs, ignored) -> new ParseIssueView(rs.getObject("id", UUID.class),
                rs.getObject("parse_block_id", UUID.class), rs.getObject("extract_field_id", UUID.class),
                rs.getString("issue_code"), rs.getString("severity"), rs.getString("message"),
                rs.getString("status"), rs.getString("resolution")), parseRunId);
    }

    @Override
    @Transactional
    public boolean saveReview(UUID organizationId, UUID actorId, ReviewUpdate update) {
        var changed = jdbc.update("""
                UPDATE kb.document_version v SET review_revision = review_revision + 1, updated_at = now()
                FROM kb.document d WHERE v.document_id = d.id AND d.organization_id = ? AND d.id = ?
                  AND v.id = ? AND v.review_revision = ? AND v.review_status IN ('PENDING_REVIEW', 'REJECTED')
                  AND (SELECT pr.status FROM kb.document_parse_run pr
                       WHERE pr.document_version_id = v.id ORDER BY pr.run_no DESC LIMIT 1)
                      IN ('PENDING_REVIEW', 'REJECTED', 'WAITING_MEDIA_CONSENT', 'FAILED')
                """, organizationId, update.documentId(), update.versionId(), update.expectedRevision());
        if (changed == 0) return false;
        jdbc.update("""
                UPDATE kb.document SET title = ?, document_type = ?, library_scope = ?, category_id = ?, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, update.title(), update.documentType(), update.libraryScope(), update.categoryId(),
                organizationId, update.documentId());
        var parseRunId = latestParseRun(update.versionId());
        for (var block : safe(update.blocks())) {
            jdbc.update("""
                    UPDATE kb.document_parse_block SET confirmed_text = ?, review_status = ?, updated_at = now()
                    WHERE id = ? AND parse_run_id = ?
                    """, block.confirmedText(), block.reviewStatus(), block.id(), parseRunId);
            jdbc.update("""
                    UPDATE kb.document_parse_issue SET status = ?, resolution = ?, updated_at = now()
                    WHERE parse_run_id = ? AND parse_block_id = ?
                    """, resolved(block.reviewStatus()) ? "RESOLVED" : "OPEN",
                    resolved(block.reviewStatus()) ? "文本块已" + block.reviewStatus() : null, parseRunId, block.id());
        }
        for (var field : safe(update.fields())) {
            jdbc.update("""
                    UPDATE kb.document_extract_field SET confirmed_value = ?, review_status = ?, updated_at = now()
                    WHERE id = ? AND parse_run_id = ?
                    """, field.confirmedValue(), field.reviewStatus(), field.id(), parseRunId);
            jdbc.update("""
                    UPDATE kb.document_parse_issue i
                    SET status = CASE WHEN f.review_status = 'CONFIRMED'
                                           AND coalesce(nullif(btrim(f.confirmed_value), ''), nullif(btrim(f.normalized_value), ''),
                                                        nullif(btrim(f.raw_value), '')) IS NOT NULL
                                           AND (NOT f.conflict OR nullif(btrim(f.confirmed_value), '') IS NOT NULL)
                                      THEN 'RESOLVED' ELSE 'OPEN' END,
                        resolution = CASE WHEN f.review_status = 'CONFIRMED'
                                               AND coalesce(nullif(btrim(f.confirmed_value), ''), nullif(btrim(f.normalized_value), ''),
                                                            nullif(btrim(f.raw_value), '')) IS NOT NULL
                                               AND (NOT f.conflict OR nullif(btrim(f.confirmed_value), '') IS NOT NULL)
                                          THEN '字段已人工确认' ELSE NULL END,
                        updated_at = now()
                    FROM kb.document_extract_field f
                    WHERE i.parse_run_id = ? AND i.extract_field_id = ? AND f.id = i.extract_field_id
                    """, parseRunId, field.id());
        }
        replaceTags(organizationId, actorId, update.documentId(), update.tags());
        replaceRelations(organizationId, actorId, update.documentId(), update.objectRefIds());
        return true;
    }

    @Override
    @Transactional
    public PublicationRow publish(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                                  int expectedRevision) {
        var parseRunId = latestParseRun(versionId);
        var changed = jdbc.update("""
                UPDATE kb.document_version v
                SET review_status = 'PUBLISHED', review_revision = review_revision + 1,
                    reviewed_by = ?, reviewed_at = now(), review_reason = NULL, updated_at = now()
                FROM kb.document d
                WHERE v.document_id = d.id AND d.organization_id = ? AND d.id = ? AND v.id = ?
                  AND v.review_revision = ? AND v.status = 'READY'
                  AND v.review_status IN ('PENDING_REVIEW', 'REJECTED')
                """, actorId, organizationId, documentId, versionId, expectedRevision);
        if (changed == 0) return null;
        var oldVersion = jdbc.query("""
                SELECT document_version_id FROM kb.publication
                WHERE organization_id = ? AND document_id = ? AND status = 'CURRENT'
                """, (rs, ignored) -> rs.getObject(1, UUID.class), organizationId, documentId).stream().findFirst().orElse(null);
        jdbc.update("UPDATE kb.publication SET status = 'SUPERSEDED' WHERE organization_id = ? AND document_id = ? AND status = 'CURRENT'",
                organizationId, documentId);
        if (oldVersion != null && !oldVersion.equals(versionId)) {
            jdbc.update("UPDATE kb.document_version SET review_status = 'SUPERSEDED' WHERE id = ?", oldVersion);
        }
        var publicationId = UUID.randomUUID();
        var publicationNo = jdbc.queryForObject("SELECT coalesce(max(publication_no), 0) + 1 FROM kb.publication WHERE document_id = ?",
                Integer.class, documentId);
        var snapshot = objectMapper.createObjectNode();
        var document = jdbc.queryForMap("""
                SELECT d.title, d.document_type, d.library_scope, d.category_id, v.source_info_jsonb
                FROM kb.document d JOIN kb.document_version v ON v.id = ? AND v.document_id = d.id
                WHERE d.organization_id = ? AND d.id = ?
                """, versionId, organizationId, documentId);
        snapshot.put("title", String.valueOf(document.get("title")));
        snapshot.put("documentType", String.valueOf(document.get("document_type")));
        snapshot.put("libraryScope", String.valueOf(document.get("library_scope")));
        if (document.get("category_id") != null) snapshot.put("categoryId", document.get("category_id").toString());
        snapshot.set("sourceInfo", document.get("source_info_jsonb") instanceof PGobject value
                ? read(value.getValue()) : objectMapper.valueToTree(document.get("source_info_jsonb")));
        snapshot.set("tags", objectMapper.valueToTree(tags(organizationId, documentId)));
        snapshot.set("relations", objectMapper.valueToTree(relations(organizationId, documentId)));
        jdbc.update("""
                INSERT INTO kb.publication (
                    id, organization_id, document_id, document_version_id, parse_run_id, publication_no,
                    status, metadata_snapshot_jsonb, published_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'CURRENT', ?, ?)
                """, publicationId, organizationId, documentId, versionId, parseRunId, publicationNo,
                json(snapshot), actorId);
        jdbc.update("UPDATE kb.document_parse_run SET status = 'PUBLISHED', finished_at = now() WHERE id = ?", parseRunId);
        jdbc.update("""
                UPDATE kb.document SET current_publication_id = ?, status = 'READY', ai_status = 'PENDING',
                    parse_error = NULL, updated_at = now() WHERE organization_id = ? AND id = ?
                """, publicationId, organizationId, documentId);
        return new PublicationRow(publicationId, documentId, versionId, parseRunId, publicationNo,
                "CURRENT", "PENDING", Instant.now());
    }

    @Override
    @Transactional
    public boolean reject(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                          int expectedRevision, String reason) {
        var changed = jdbc.update("""
                UPDATE kb.document_version v SET review_status = 'REJECTED', review_revision = review_revision + 1,
                    review_reason = ?, reviewed_by = ?, reviewed_at = now(), updated_at = now()
                FROM kb.document d WHERE v.document_id = d.id AND d.organization_id = ? AND d.id = ?
                  AND v.id = ? AND v.review_revision = ? AND v.review_status IN ('PENDING_REVIEW', 'REJECTED')
                  AND (SELECT pr.status FROM kb.document_parse_run pr
                       WHERE pr.document_version_id = v.id ORDER BY pr.run_no DESC LIMIT 1)
                      IN ('PENDING_REVIEW', 'REJECTED', 'WAITING_MEDIA_CONSENT', 'FAILED')
                """, reason, actorId, organizationId, documentId, versionId, expectedRevision);
        if (changed > 0) jdbc.update("UPDATE kb.document_parse_run SET status = 'REJECTED' WHERE id = ?", latestParseRun(versionId));
        return changed > 0;
    }

    @Override
    public boolean reserveReparse(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                                  int expectedRevision) {
        return jdbc.update("""
                UPDATE kb.document_version v
                SET review_status = 'PENDING_REVIEW', review_revision = review_revision + 1,
                    review_reason = 'REPARSE_QUEUED', reviewed_by = ?, reviewed_at = now(), updated_at = now()
                FROM kb.document d
                WHERE v.document_id = d.id AND d.organization_id = ? AND d.id = ? AND v.id = ?
                  AND v.review_revision = ?
                  AND v.review_reason IS DISTINCT FROM 'REPARSE_QUEUED'
                  AND v.review_status IN ('PENDING_REVIEW', 'REJECTED', 'PUBLISHED', 'SUPERSEDED')
                """, actorId, organizationId, documentId, versionId, expectedRevision) > 0;
    }

    @Override
    public boolean hasPublication(UUID organizationId, UUID documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM kb.publication WHERE organization_id = ? AND document_id = ?)
                """, Boolean.class, organizationId, documentId));
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
        return jdbc.query("""
                SELECT p.id, p.document_id, p.document_version_id, p.parse_run_id, p.publication_no, p.status,
                       coalesce(g.status, 'PENDING') AS ai_status, p.published_at
                FROM kb.publication p LEFT JOIN kb.ai_usage_grant g ON g.publication_id = p.id
                WHERE p.organization_id = ? AND p.document_id = ? AND p.status = 'CURRENT'
                """, this::mapPublication, organizationId, documentId).stream().findFirst();
    }

    @Override
    public Optional<PublicationRow> currentPublicationById(UUID organizationId, UUID publicationId) {
        return jdbc.query("""
                SELECT p.id, p.document_id, p.document_version_id, p.parse_run_id, p.publication_no, p.status,
                       coalesce(g.status, 'PENDING') AS ai_status, p.published_at
                FROM kb.publication p LEFT JOIN kb.ai_usage_grant g ON g.publication_id = p.id
                WHERE p.organization_id = ? AND p.id = ? AND p.status = 'CURRENT'
                """, this::mapPublication, organizationId, publicationId).stream().findFirst();
    }

    @Override
    public List<PublicationRow> publications(UUID organizationId, UUID documentId) {
        return jdbc.query("""
                SELECT p.id, p.document_id, p.document_version_id, p.parse_run_id, p.publication_no, p.status,
                       coalesce(g.status, 'PENDING') AS ai_status, p.published_at
                FROM kb.publication p LEFT JOIN kb.ai_usage_grant g ON g.publication_id = p.id
                WHERE p.organization_id = ? AND p.document_id = ? ORDER BY p.publication_no DESC
                """, this::mapPublication, organizationId, documentId);
    }

    @Override
    public List<String> publicationTags(UUID organizationId, UUID publicationId) {
        return jdbc.query("""
                SELECT value #>> '{}' AS display_name
                FROM kb.publication p
                CROSS JOIN LATERAL jsonb_array_elements(coalesce(p.metadata_snapshot_jsonb->'tags', '[]'::jsonb)) value
                WHERE p.organization_id = ? AND p.id = ?
                ORDER BY display_name
                """, (rs, ignored) -> rs.getString(1), organizationId, publicationId);
    }

    @Override
    public List<ObjectRelation> publicationRelations(UUID organizationId, UUID publicationId) {
        return jdbc.query("""
                SELECT (value->>'id')::uuid AS id, value->>'objectType' AS object_type,
                       value->>'externalId' AS external_id, value->>'name' AS name,
                       value->>'sourceSystem' AS source_system
                FROM kb.publication p
                CROSS JOIN LATERAL jsonb_array_elements(coalesce(p.metadata_snapshot_jsonb->'relations', '[]'::jsonb)) value
                WHERE p.organization_id = ? AND p.id = ? AND value->>'id' IS NOT NULL
                ORDER BY object_type, name
                """, (rs, ignored) -> new ObjectRelation(rs.getObject("id", UUID.class), rs.getString("object_type"),
                rs.getString("external_id"), rs.getString("name"), rs.getString("source_system")),
                organizationId, publicationId);
    }

    @Override
    @Transactional
    public boolean updateAiUsage(UUID organizationId, UUID actorId, UUID publicationId, String action, String reason) {
        var status = switch (action) {
            case "APPROVE" -> "APPROVED";
            case "REVOKE" -> "REVOKED";
            case "REJECT" -> "REJECTED";
            default -> throw new IllegalArgumentException("无效AI授权动作");
        };
        var rows = jdbc.query("""
                SELECT p.document_id FROM kb.publication p JOIN kb.document d ON d.id = p.document_id
                JOIN kb.document_version v ON v.id = p.document_version_id
                WHERE p.organization_id = ? AND p.id = ? AND p.status = 'CURRENT'
                  AND d.lifecycle_status = 'ACTIVE'
                """, (rs, ignored) -> rs.getObject(1, UUID.class), organizationId, publicationId);
        if (rows.isEmpty()) return false;
        jdbc.update("""
                INSERT INTO kb.ai_usage_grant (publication_id, organization_id, status, reason, updated_by)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (publication_id) DO UPDATE SET status = EXCLUDED.status, reason = EXCLUDED.reason,
                    updated_by = EXCLUDED.updated_by, updated_at = now()
                """, publicationId, organizationId, status, reason, actorId);
        jdbc.update("UPDATE kb.document SET ai_status = ?, updated_at = now() WHERE organization_id = ? AND id = ?",
                status, organizationId, rows.getFirst());
        return true;
    }

    private PublicationRow mapPublication(ResultSet rs, int ignored) throws SQLException {
        return new PublicationRow(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("document_version_id", UUID.class), rs.getObject("parse_run_id", UUID.class),
                rs.getInt("publication_no"), rs.getString("status"), rs.getString("ai_status"),
                rs.getTimestamp("published_at").toInstant());
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
    public List<ObjectRelation> relations(UUID organizationId, UUID documentId) {
        return jdbc.query("""
                SELECT o.id, o.object_type, o.external_id, o.name, o.source_system
                FROM core.business_object_ref o JOIN kb.document_relation r ON r.object_ref_id = o.id
                JOIN kb.document d ON d.id = r.document_id
                WHERE d.organization_id = ? AND d.id = ? ORDER BY o.object_type, o.name
                """, (rs, ignored) -> new ObjectRelation(rs.getObject("id", UUID.class), rs.getString("object_type"),
                rs.getString("external_id"), rs.getString("name"), rs.getString("source_system")),
                organizationId, documentId);
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
                    SELECT ?, id FROM kb.tag WHERE organization_id = ? AND normalized_name = ?
                    ON CONFLICT DO NOTHING
                    """, documentId, organizationId, normalized);
        }
    }

    @Override
    @Transactional
    public void replaceRelations(UUID organizationId, UUID actorId, UUID documentId, List<UUID> objectRefIds) {
        jdbc.update("DELETE FROM kb.document_relation WHERE document_id = ?", documentId);
        for (var objectId : safe(objectRefIds).stream().distinct().toList()) {
            jdbc.update("""
                    INSERT INTO kb.document_relation (document_id, object_ref_id)
                    SELECT ?, id FROM core.business_object_ref
                    WHERE organization_id = ? AND id = ? AND status = 'ACTIVE'
                    ON CONFLICT DO NOTHING
                    """, documentId, organizationId, objectId);
            jdbc.update("""
                    INSERT INTO kb.knowledge_page (id, organization_id, object_ref_id, title, created_by)
                    SELECT ?, organization_id, id, name, ? FROM core.business_object_ref
                    WHERE organization_id = ? AND id = ?
                    ON CONFLICT (organization_id, object_ref_id) DO UPDATE SET updated_at = now()
                    """, UUID.randomUUID(), actorId, organizationId, objectId);
        }
    }

    @Override
    public List<PageListItem> listPages(UUID organizationId) {
        return jdbc.query(pageListSql() + " WHERE p.organization_id = ? ORDER BY p.updated_at DESC",
                this::mapPageList, organizationId);
    }

    @Override
    public Optional<PageView> page(UUID organizationId, UUID pageId) {
        var header = jdbc.query(pageListSql() + " WHERE p.organization_id = ? AND p.id = ?",
                this::mapPageList, organizationId, pageId).stream().findFirst();
        if (header.isEmpty()) return Optional.empty();
        var available = availablePageSources(organizationId, header.get().objectRefId());
        var versions = jdbc.query("""
                SELECT id, version_no, title, summary, published_at
                FROM kb.knowledge_page_version WHERE page_id = ? ORDER BY version_no DESC
                """, (rs, ignored) -> {
            var id = rs.getObject("id", UUID.class);
            return new PageVersionRow(id, rs.getInt("version_no"), rs.getString("title"), rs.getString("summary"),
                    rs.getTimestamp("published_at").toInstant(), pageVersionSources(id));
        }, pageId);
        return Optional.of(new PageView(header.get(), available, versions));
    }

    private String pageListSql() {
        return """
                SELECT p.id, p.object_ref_id, o.object_type, o.external_id, o.name AS object_name,
                       coalesce(v.title, p.title) AS title, p.title AS draft_title,
                       coalesce(v.summary, '') AS summary, p.draft_summary, p.draft_revision,
                       v.version_no AS current_version_no,
                       (SELECT count(*) FROM kb.knowledge_page_source s WHERE s.page_version_id = p.current_version_id) AS current_source_count,
                       (SELECT count(*) FROM kb.document d
                         JOIN kb.publication pub ON pub.id = d.current_publication_id AND pub.status = 'CURRENT'
                         CROSS JOIN LATERAL jsonb_array_elements(coalesce(pub.metadata_snapshot_jsonb->'relations', '[]'::jsonb)) rel
                         WHERE rel->>'id' = p.object_ref_id::text AND d.lifecycle_status = 'ACTIVE') AS available_source_count,
                       ((SELECT array_agg(s.publication_id ORDER BY s.publication_id)
                           FROM kb.knowledge_page_source s WHERE s.page_version_id = p.current_version_id)
                        IS DISTINCT FROM
                        (SELECT array_agg(pub.id ORDER BY pub.id)
                           FROM kb.document d
                           JOIN kb.publication pub ON pub.id = d.current_publication_id AND pub.status = 'CURRENT'
                           CROSS JOIN LATERAL jsonb_array_elements(coalesce(pub.metadata_snapshot_jsonb->'relations', '[]'::jsonb)) rel
                           WHERE rel->>'id' = p.object_ref_id::text AND d.lifecycle_status = 'ACTIVE')) AS has_updates,
                       p.updated_at
                FROM kb.knowledge_page p JOIN core.business_object_ref o ON o.id = p.object_ref_id
                LEFT JOIN kb.knowledge_page_version v ON v.id = p.current_version_id
                """;
    }

    private PageListItem mapPageList(ResultSet rs, int ignored) throws SQLException {
        var current = rs.getLong("current_source_count");
        var available = rs.getLong("available_source_count");
        var version = rs.getObject("current_version_no") == null ? null : rs.getInt("current_version_no");
        return new PageListItem(rs.getObject("id", UUID.class), rs.getObject("object_ref_id", UUID.class),
                rs.getString("object_type"), rs.getString("external_id"), rs.getString("object_name"),
                rs.getString("title"), rs.getString("draft_title"), rs.getString("summary"),
                rs.getString("draft_summary"),
                rs.getInt("draft_revision"), version,
                current, available, rs.getBoolean("has_updates"), rs.getTimestamp("updated_at").toInstant());
    }

    private List<PageSource> availablePageSources(UUID organizationId, UUID objectRefId) {
        return jdbc.query("""
                SELECT pub.id AS publication_id, d.id AS document_id,
                       coalesce(pub.metadata_snapshot_jsonb->>'title', d.title) AS title, v.id AS version_id,
                       v.version_no, d.lifecycle_status = 'ACTIVE' AS active, pub.published_at
                FROM kb.document d
                JOIN kb.publication pub ON pub.id = d.current_publication_id AND pub.status = 'CURRENT'
                JOIN kb.document_version v ON v.id = pub.document_version_id
                CROSS JOIN LATERAL jsonb_array_elements(coalesce(pub.metadata_snapshot_jsonb->'relations', '[]'::jsonb)) rel
                WHERE d.organization_id = ? AND rel->>'id' = ?::text AND d.lifecycle_status = 'ACTIVE'
                ORDER BY pub.published_at DESC
                """, this::mapPageSource, organizationId, objectRefId);
    }

    private List<PageSource> pageVersionSources(UUID pageVersionId) {
        return jdbc.query("""
                SELECT pub.id AS publication_id, d.id AS document_id,
                       coalesce(pub.metadata_snapshot_jsonb->>'title', d.title) AS title, v.id AS version_id,
                       v.version_no, d.lifecycle_status = 'ACTIVE' AS active, pub.published_at
                FROM kb.knowledge_page_source s JOIN kb.publication pub ON pub.id = s.publication_id
                JOIN kb.document d ON d.id = pub.document_id JOIN kb.document_version v ON v.id = pub.document_version_id
                WHERE s.page_version_id = ? ORDER BY pub.published_at DESC
                """, this::mapPageSource, pageVersionId);
    }

    private PageSource mapPageSource(ResultSet rs, int ignored) throws SQLException {
        return new PageSource(rs.getObject("publication_id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getString("title"), rs.getObject("version_id", UUID.class), rs.getInt("version_no"),
                rs.getBoolean("active"), rs.getTimestamp("published_at").toInstant());
    }

    @Override
    public boolean savePageDraft(UUID organizationId, UUID pageId, String title, String summary, int expectedRevision) {
        return jdbc.update("""
                UPDATE kb.knowledge_page SET title = ?, draft_summary = ?, draft_revision = draft_revision + 1,
                    updated_at = now() WHERE organization_id = ? AND id = ? AND draft_revision = ?
                """, title, summary, organizationId, pageId, expectedRevision) > 0;
    }

    @Override
    @Transactional
    public PageVersionRow publishPage(UUID organizationId, UUID actorId, UUID pageId, int expectedRevision) {
        var page = jdbc.query("""
                SELECT id, object_ref_id, title, draft_summary, draft_revision
                FROM kb.knowledge_page WHERE organization_id = ? AND id = ? AND draft_revision = ?
                FOR UPDATE
                """, (rs, ignored) -> new Object[]{rs.getObject("object_ref_id", UUID.class), rs.getString("title"),
                rs.getString("draft_summary")}, organizationId, pageId, expectedRevision).stream().findFirst().orElse(null);
        if (page == null) return null;
        var versionNo = jdbc.queryForObject("SELECT coalesce(max(version_no), 0) + 1 FROM kb.knowledge_page_version WHERE page_id = ?",
                Integer.class, pageId);
        var versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kb.knowledge_page_version (id, page_id, version_no, title, summary, published_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, versionId, pageId, versionNo, page[1], page[2], actorId);
        var sources = availablePageSources(organizationId, (UUID) page[0]);
        for (var source : sources) {
            jdbc.update("INSERT INTO kb.knowledge_page_source (page_version_id, publication_id) VALUES (?, ?)",
                    versionId, source.publicationId());
        }
        jdbc.update("""
                UPDATE kb.knowledge_page SET current_version_id = ?, draft_revision = draft_revision + 1,
                    updated_at = now() WHERE organization_id = ? AND id = ?
                """, versionId, organizationId, pageId);
        return new PageVersionRow(versionId, versionNo, (String) page[1], (String) page[2], Instant.now(), sources);
    }

    private UUID latestParseRun(UUID versionId) {
        return jdbc.query("""
                SELECT id FROM kb.document_parse_run WHERE document_version_id = ? ORDER BY run_no DESC LIMIT 1
                """, (rs, ignored) -> rs.getObject(1, UUID.class), versionId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("知识版本没有可审核的解析结果"));
    }

    private void insertIssue(UUID parseRunId, UUID blockId, UUID fieldId, String code,
                             String severity, String message) {
        jdbc.update("""
                INSERT INTO kb.document_parse_issue (
                    id, parse_run_id, parse_block_id, extract_field_id, issue_code, severity, message
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), parseRunId, blockId, fieldId, code, severity, message);
    }

    private boolean resolved(String status) {
        return "CONFIRMED".equals(status) || "IGNORED".equals(status);
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column); return value == null ? null : ((Number) value).intValue();
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column); return value == null ? null : ((Number) value).longValue();
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column); return value == null ? null : ((Number) value).doubleValue();
    }

    private PGobject json(JsonNode node) {
        try {
            var value = new PGobject(); value.setType("jsonb"); value.setValue(node == null ? "{}" : node.toString());
            return value;
        } catch (SQLException exception) { throw new IllegalArgumentException("JSON数据无效", exception); }
    }

    private JsonNode read(String value) {
        try { return value == null ? objectMapper.createObjectNode() : objectMapper.readTree(value); }
        catch (Exception exception) { return objectMapper.createObjectNode(); }
    }

    private <T> List<T> safe(List<T> value) { return value == null ? List.of() : value; }
}
