package com.jsd.aird.kb.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        return duplicateRows("WHERE d.organization_id = ? AND v.sha256 = ? ORDER BY d.updated_at DESC, v.version_no DESC",
                organizationId, sha256);
    }

    @Override
    public List<DuplicateMatch> possibleMatches(UUID organizationId, String normalizedStem, UUID categoryId) {
        return duplicateRows("""
                WHERE d.organization_id = ? AND d.category_id = ?
                ORDER BY d.updated_at DESC, v.version_no DESC
                """, organizationId, categoryId);
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
    public ParseRunRow createParseRun(UUID organizationId, UUID documentId, UUID versionId, String status,
                                      String parserVersion, String provider, String providerTaskId,
                                      String errorMessage, JsonNode result, List<DocumentParser.TextBlock> blocks) {
        var id = UUID.randomUUID();
        var runNo = jdbc.queryForObject("SELECT coalesce(max(run_no), 0) + 1 FROM kb.document_parse_run WHERE document_version_id = ?",
                Integer.class, versionId);
        jdbc.update("""
                INSERT INTO kb.document_parse_run (
                    id, organization_id, document_id, document_version_id, run_no, status, parser_version,
                    provider, provider_task_id, error_message, result_jsonb, started_at, finished_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), CASE WHEN ? IN ('PROCESSING', 'PENDING_REVIEW', 'INDEXING') THEN NULL ELSE now() END)
                """, id, organizationId, documentId, versionId, runNo, status, parserVersion, provider,
                providerTaskId, errorMessage, json(result), status);
        var blockNo = 0;
        for (var block : safe(blocks)) {
            var blockId = UUID.randomUUID();
            var raw = block.content() == null ? "" : block.content();
            var normalized = raw.replaceAll("[\\t\\r]+", " ").strip();
            jdbc.update("""
                    INSERT INTO kb.document_parse_block (
                        id, parse_run_id, block_no, page_no, sheet_name, cell_range, paragraph_id, bbox_jsonb,
                        start_time_ms, end_time_ms, section, raw_text, normalized_text, confidence, review_status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                    """, blockId, id, blockNo++, block.pageNo(), block.sheetName(), block.cellRange(),
                    block.paragraphId(), block.bbox().isEmpty() ? null : json(objectMapper.valueToTree(block.bbox())),
                    block.startTimeMs(), block.endTimeMs(), block.section(), raw, normalized, block.confidence());
            if (block.confidence() != null && block.confidence() < 0.80) {
                insertIssue(id, blockId, "LOW_BLOCK_CONFIDENCE", "WARNING", "识别置信度较低，请重点校对");
            }
        }
        jdbc.update("""
                UPDATE kb.document_version
                SET review_status = 'PENDING_REVIEW', review_reason = NULL, review_revision = review_revision + 1,
                    updated_at = now() WHERE id = ?
                """, versionId);
        return new ParseRunRow(id, documentId, versionId, runNo, status, errorMessage, Instant.now());
    }

    @Override
    public void updateParseRunStatus(UUID organizationId, UUID parseRunId, String status, String errorMessage) {
        jdbc.update("""
                UPDATE kb.document_parse_run SET status = ?, error_message = ?,
                    finished_at = CASE WHEN ? IN ('PUBLISHED', 'REJECTED', 'FAILED') THEN now() ELSE NULL END
                WHERE organization_id = ? AND id = ?
                """, status, errorMessage, status, organizationId, parseRunId);
    }

    @Override
    public Optional<ReviewView> review(UUID organizationId, UUID documentId, UUID versionId) {
        var rows = jdbc.query("""
                SELECT d.title, d.library_scope, d.category_id, c.name AS category_name, d.lifecycle_status,
                       v.version_no, v.original_name, v.content_type, v.size_bytes, v.status AS processing_status,
                       v.review_status, v.review_revision, coalesce(v.source_info_jsonb, d.source_info_jsonb) AS source_info_jsonb,
                       r.id AS parse_run_id, r.run_no, r.status AS parse_status, r.error_message,
                       r.created_at AS parse_created_at
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
                    rs.getInt("run_no"), rs.getString("parse_status"), rs.getString("error_message"),
                    rs.getTimestamp("parse_created_at").toInstant());
            return new ReviewView(documentId, rs.getString("title"), rs.getString("library_scope"),
                    rs.getObject("category_id", UUID.class), rs.getString("category_name"),
                    rs.getString("lifecycle_status"), versionId, rs.getInt("version_no"),
                    rs.getString("original_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                    rs.getString("processing_status"), rs.getString("review_status"), rs.getInt("review_revision"),
                    read(rs.getString("source_info_jsonb")), parse, List.of(), List.of(), List.of());
        }, organizationId, documentId, versionId);
        if (rows.isEmpty()) return Optional.empty();
        var value = rows.getFirst();
        var runId = value.parseRun() == null ? null : value.parseRun().id();
        return Optional.of(new ReviewView(value.documentId(), value.title(), value.libraryScope(), value.categoryId(),
                value.categoryName(), value.lifecycleStatus(), value.versionId(), value.versionNo(),
                value.originalName(), value.contentType(), value.size(), value.processingStatus(), value.reviewStatus(),
                value.reviewRevision(), value.sourceInfo(), value.parseRun(), runId == null ? List.of() : blocks(runId),
                runId == null ? List.of() : issues(runId), tags(organizationId, documentId)));
    }

    @Override
    public List<ReviewQueueItem> reviewQueue(UUID organizationId, String status, int limit) {
        var normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return jdbc.query("""
                SELECT d.id AS document_id, d.title, v.id AS version_id, v.version_no, v.original_name,
                       v.status AS processing_status, v.review_status, v.review_revision,
                       c.name AS category_name, v.updated_at
                FROM kb.document d
                JOIN kb.document_version v ON v.document_id = d.id AND v.version_no = d.current_version_no
                LEFT JOIN kb.document_category c ON c.id = d.category_id
                WHERE d.organization_id = ? AND (CAST(? AS text) IS NULL OR v.review_status = ?)
                  AND v.review_status IN ('PENDING_REVIEW', 'REJECTED')
                ORDER BY v.updated_at DESC LIMIT ?
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
                UPDATE kb.document_version v SET review_revision = review_revision + 1, updated_at = now()
                FROM kb.document d WHERE v.document_id = d.id AND d.organization_id = ? AND d.id = ?
                  AND v.id = ? AND v.review_revision = ? AND v.review_status IN ('PENDING_REVIEW', 'REJECTED')
                  AND (SELECT pr.status FROM kb.document_parse_run pr
                       WHERE pr.document_version_id = v.id ORDER BY pr.run_no DESC LIMIT 1)
                      IN ('PENDING_REVIEW', 'REJECTED', 'FAILED')
                """, organizationId, update.documentId(), update.versionId(), update.expectedRevision());
        if (changed == 0) return false;
        updateMetadataAndBlocks(organizationId, actorId, update, latestParseRun(update.versionId()));
        return true;
    }

    @Override
    @Transactional
    public Optional<RevisionRow> createRevision(UUID organizationId, UUID actorId, UUID documentId,
                                                UUID basePublicationId, int expectedRevision,
                                                ReviewUpdate update) {
        var base = jdbc.query("""
                SELECT p.document_version_id, p.parse_run_id
                FROM kb.publication p JOIN kb.document d ON d.current_publication_id = p.id
                JOIN kb.document_version v ON v.id = p.document_version_id
                WHERE p.organization_id = ? AND p.document_id = ? AND p.id = ? AND p.status = 'CURRENT'
                  AND v.review_revision = ?
                FOR UPDATE
                """, (rs, ignored) -> new UUID[]{rs.getObject("document_version_id", UUID.class),
                rs.getObject("parse_run_id", UUID.class)}, organizationId, documentId, basePublicationId,
                expectedRevision).stream().findFirst();
        if (base.isEmpty() || !base.get()[0].equals(update.versionId())) return Optional.empty();

        var submitted = safe(update.blocks()).stream().collect(Collectors.toMap(BlockUpdate::id, Function.identity()));
        var sourceParseRunId = latestParseRun(update.versionId());
        var sourceBlocks = blocks(sourceParseRunId);
        var parseRunId = UUID.randomUUID();
        var runNo = jdbc.queryForObject("SELECT coalesce(max(run_no), 0) + 1 FROM kb.document_parse_run WHERE document_version_id = ?",
                Integer.class, update.versionId());
        var result = objectMapper.createObjectNode().put("basePublicationId", basePublicationId.toString())
                .put("sourceParseRunId", sourceParseRunId.toString()).put("revision", true);
        jdbc.update("""
                INSERT INTO kb.document_parse_run (
                    id, organization_id, document_id, document_version_id, run_no, status, result_jsonb, started_at
                ) VALUES (?, ?, ?, ?, ?, 'INDEXING', ?, now())
                """, parseRunId, organizationId, documentId, update.versionId(), runNo, json(result));
        for (var source : sourceBlocks) {
            var change = submitted.get(source.id());
            var confirmed = change == null ? effectiveText(source) : change.confirmedText();
            var reviewStatus = change == null ? "CONFIRMED" : change.reviewStatus();
            jdbc.update("""
                    INSERT INTO kb.document_parse_block (
                        id, parse_run_id, block_no, page_no, sheet_name, cell_range, paragraph_id, bbox_jsonb,
                        start_time_ms, end_time_ms, section, raw_text, normalized_text, confirmed_text,
                        confidence, review_status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), parseRunId, source.blockNo(), source.pageNo(), source.sheetName(),
                    source.cellRange(), source.paragraphId(), source.bbox().isEmpty() ? null : json(source.bbox()),
                    source.startTimeMs(), source.endTimeMs(), source.section(), source.rawText(),
                    source.normalizedText(), confirmed, source.confidence(), reviewStatus);
        }
        var changed = jdbc.update("""
                UPDATE kb.document_version SET review_status = 'PENDING_REVIEW', review_reason = NULL,
                    review_revision = review_revision + 1, updated_at = now()
                WHERE id = ? AND review_revision = ?
                """, update.versionId(), expectedRevision);
        if (changed == 0) return Optional.empty();
        return Optional.of(new RevisionRow(documentId, update.versionId(), parseRunId, expectedRevision + 1));
    }

    private void updateMetadataAndBlocks(UUID organizationId, UUID actorId, ReviewUpdate update, UUID parseRunId) {
        updateMetadata(organizationId, actorId, update);
        for (var block : safe(update.blocks())) {
            jdbc.update("""
                    UPDATE kb.document_parse_block SET confirmed_text = ?, review_status = ?, updated_at = now()
                    WHERE id = ? AND parse_run_id = ?
                    """, block.confirmedText(), block.reviewStatus(), block.id(), parseRunId);
            jdbc.update("""
                    UPDATE kb.document_parse_issue SET status = ?, resolution = ?, updated_at = now()
                    WHERE parse_run_id = ? AND parse_block_id = ?
                    """, resolved(block.reviewStatus()) ? "RESOLVED" : "OPEN",
                    resolved(block.reviewStatus()) ? "识别文本已人工处理" : null, parseRunId, block.id());
        }
    }

    private void updateMetadata(UUID organizationId, UUID actorId, ReviewUpdate update) {
        jdbc.update("""
                UPDATE kb.document SET title = ?, library_scope = ?, category_id = ?, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, update.title(), update.libraryScope(), update.categoryId(), organizationId, update.documentId());
        replaceTags(organizationId, actorId, update.documentId(), update.tags());
    }

    @Override
    public boolean reservePublication(UUID organizationId, UUID documentId, UUID versionId, UUID parseRunId,
                                      int expectedRevision) {
        return jdbc.update("""
                UPDATE kb.document_parse_run r SET status = 'INDEXING', error_message = NULL, finished_at = NULL
                FROM kb.document_version v, kb.document d
                WHERE r.id = ? AND r.document_version_id = v.id AND v.document_id = d.id
                  AND d.organization_id = ? AND d.id = ? AND v.id = ? AND v.review_revision = ?
                  AND v.review_status IN ('PENDING_REVIEW', 'REJECTED')
                  AND r.status IN ('PENDING_REVIEW', 'REJECTED', 'FAILED')
                """, parseRunId, organizationId, documentId, versionId, expectedRevision) > 0;
    }

    @Override
    @Transactional
    public PublicationRow publish(UUID organizationId, UUID actorId, UUID documentId, UUID versionId,
                                  UUID parseRunId, int expectedRevision) {
        var changed = jdbc.update("""
                UPDATE kb.document_version v
                SET review_status = 'PUBLISHED', review_revision = review_revision + 1,
                    reviewed_by = ?, reviewed_at = now(), review_reason = NULL, updated_at = now()
                FROM kb.document d, kb.document_parse_run r
                WHERE v.document_id = d.id AND r.document_version_id = v.id AND r.id = ?
                  AND r.status = 'INDEXING' AND d.organization_id = ? AND d.id = ? AND v.id = ?
                  AND v.review_revision = ? AND v.status = 'READY'
                  AND v.review_status IN ('PENDING_REVIEW', 'REJECTED')
                """, actorId, parseRunId, organizationId, documentId, versionId, expectedRevision);
        if (changed == 0) return null;
        var oldVersion = jdbc.query("SELECT document_version_id FROM kb.publication WHERE organization_id = ? AND document_id = ? AND status = 'CURRENT'",
                (rs, ignored) -> rs.getObject(1, UUID.class), organizationId, documentId).stream().findFirst().orElse(null);
        jdbc.update("UPDATE kb.publication SET status = 'SUPERSEDED' WHERE organization_id = ? AND document_id = ? AND status = 'CURRENT'",
                organizationId, documentId);
        if (oldVersion != null && !oldVersion.equals(versionId)) {
            jdbc.update("UPDATE kb.document_version SET review_status = 'SUPERSEDED' WHERE id = ?", oldVersion);
        }
        var publicationId = UUID.randomUUID();
        var publicationNo = jdbc.queryForObject("SELECT coalesce(max(publication_no), 0) + 1 FROM kb.publication WHERE document_id = ?",
                Integer.class, documentId);
        var snapshot = publicationSnapshot(organizationId, documentId, versionId);
        jdbc.update("""
                INSERT INTO kb.publication (
                    id, organization_id, document_id, document_version_id, parse_run_id, publication_no,
                    status, metadata_snapshot_jsonb, published_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'CURRENT', ?, ?)
                """, publicationId, organizationId, documentId, versionId, parseRunId, publicationNo,
                json(snapshot), actorId);
        jdbc.update("UPDATE kb.document_parse_run SET status = 'PUBLISHED', finished_at = now() WHERE id = ?", parseRunId);
        jdbc.update("""
                UPDATE kb.document SET current_publication_id = ?, status = 'READY', parse_error = NULL, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, publicationId, organizationId, documentId);
        var aiStatus = aiStatus(organizationId, documentId);
        return new PublicationRow(publicationId, documentId, versionId, parseRunId, publicationNo,
                "CURRENT", aiStatus, Instant.now());
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
                          int expectedRevision, String reason) {
        var changed = jdbc.update("""
                UPDATE kb.document_version v SET review_status = 'REJECTED', review_revision = review_revision + 1,
                    review_reason = ?, reviewed_by = ?, reviewed_at = now(), updated_at = now()
                FROM kb.document d WHERE v.document_id = d.id AND d.organization_id = ? AND d.id = ?
                  AND v.id = ? AND v.review_revision = ? AND v.review_status IN ('PENDING_REVIEW', 'REJECTED')
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
                  AND v.review_revision = ? AND v.review_reason IS DISTINCT FROM 'REPARSE_QUEUED'
                """, actorId, organizationId, documentId, versionId, expectedRevision) > 0;
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
                SELECT p.id, p.document_id, p.document_version_id, p.parse_run_id, p.publication_no, p.status,
                       coalesce(g.status, 'PENDING') AS ai_status, p.published_at
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

    private List<ParseIssueView> issues(UUID parseRunId) {
        return jdbc.query("""
                SELECT id, parse_block_id, issue_code, severity, message, status, resolution
                FROM kb.document_parse_issue WHERE parse_run_id = ?
                ORDER BY CASE severity WHEN 'BLOCKER' THEN 1 WHEN 'WARNING' THEN 2 ELSE 3 END, issue_code
                """, (rs, ignored) -> new ParseIssueView(rs.getObject("id", UUID.class),
                rs.getObject("parse_block_id", UUID.class), rs.getString("issue_code"), rs.getString("severity"),
                rs.getString("message"), rs.getString("status"), rs.getString("resolution")), parseRunId);
    }

    private PublicationRow mapPublication(ResultSet rs, int ignored) throws SQLException {
        return new PublicationRow(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("document_version_id", UUID.class), rs.getObject("parse_run_id", UUID.class),
                rs.getInt("publication_no"), rs.getString("status"), rs.getString("ai_status"),
                rs.getTimestamp("published_at").toInstant());
    }

    private String aiStatus(UUID organizationId, UUID documentId) {
        return jdbc.query("SELECT status FROM kb.document_ai_grant WHERE organization_id = ? AND document_id = ?",
                (rs, ignored) -> rs.getString(1), organizationId, documentId).stream().findFirst().orElse("PENDING");
    }

    private UUID latestParseRun(UUID versionId) {
        return jdbc.query("SELECT id FROM kb.document_parse_run WHERE document_version_id = ? ORDER BY run_no DESC LIMIT 1",
                (rs, ignored) -> rs.getObject(1, UUID.class), versionId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("知识版本没有可审核的解析结果"));
    }

    private void insertIssue(UUID parseRunId, UUID blockId, String code, String severity, String message) {
        jdbc.update("""
                INSERT INTO kb.document_parse_issue (id, parse_run_id, parse_block_id, issue_code, severity, message)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), parseRunId, blockId, code, severity, message);
    }

    private String effectiveText(ParseBlockView block) {
        if (block.confirmedText() != null) return block.confirmedText();
        return block.normalizedText() == null ? "" : block.normalizedText();
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
