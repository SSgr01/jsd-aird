package com.jsd.aird.kb.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.kb.application.port.KnowledgeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcKnowledgeRepository implements KnowledgeRepository {

    private final JdbcTemplate jdbc;

    public JdbcKnowledgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertDocument(NewDocument document) {
        jdbc.update("""
                INSERT INTO kb.document (id, organization_id, title, library_scope, category_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, document.id(), document.organizationId(), document.title(),
                document.scope(), document.categoryId(), document.actorId());
    }

    @Override
    public void insertVersion(NewVersion version) {
        jdbc.update("""
                INSERT INTO kb.document_version (
                    id, document_id, version_no, file_object_id, original_name,
                    content_type, size_bytes, sha256, ocr_mode, allow_agent_fallback
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, version.id(), version.documentId(), version.versionNo(), version.fileObjectId(),
                version.originalName(), version.contentType(), version.size(), version.sha256(),
                version.ocrMode(), version.allowAgentFallback());
    }

    @Override
    public Optional<DocumentRow> findDocument(UUID organizationId, UUID documentId) {
        return jdbc.query(documentQuery("WHERE d.organization_id = ? AND d.id = ?"), this::mapDocument,
                organizationId, documentId).stream().findFirst();
    }

    @Override
    public List<DocumentRow> listDocuments(UUID organizationId, String keyword, String status, String aiStatus,
                                           String scope, UUID categoryId, String lifecycleStatus, String reviewStatus,
                                           int page, int size) {
        return listDocuments(organizationId, keyword, status, aiStatus, scope, categoryId,
                lifecycleStatus, reviewStatus, null, page, size);
    }

    @Override
    public List<DocumentRow> listDocuments(UUID organizationId, String keyword, String status, String aiStatus,
                                           String scope, UUID categoryId, String lifecycleStatus, String reviewStatus,
                                           java.util.Set<UUID> allowedDocumentIds, int page, int size) {
        if (allowedDocumentIds != null && allowedDocumentIds.isEmpty()) return List.of();
        var normalizedKeyword = blankToNull(keyword);
        var normalizedStatus = blankToNull(status);
        var normalizedAiStatus = blankToNull(aiStatus);
        var projectClause = documentIdClause(allowedDocumentIds);
        var sql = documentQuery("""
                WHERE d.organization_id = ?
                  AND (CAST(? AS text) IS NULL OR d.title ILIKE '%' || ? || '%' OR v.original_name ILIKE '%' || ? || '%')
                  AND (CAST(? AS text) IS NULL OR d.status = ?)
                  AND (CAST(? AS text) IS NULL OR coalesce(g.status, 'PENDING') = ?)
                  AND (CAST(? AS text) IS NULL OR d.library_scope = ?)
                  AND (CAST(? AS uuid) IS NULL OR d.category_id = ?)
                  AND (CAST(? AS text) IS NULL OR d.lifecycle_status = ?)
                  AND (CAST(? AS text) IS NULL OR v.review_status = ?)
                """ + projectClause + """
                ORDER BY d.updated_at DESC
                LIMIT ? OFFSET ?
                """);
        var args = new java.util.ArrayList<Object>(java.util.Arrays.asList(organizationId, normalizedKeyword, normalizedKeyword,
                normalizedKeyword, normalizedStatus, normalizedStatus, normalizedAiStatus, normalizedAiStatus,
                blankToNull(scope), blankToNull(scope), categoryId, categoryId,
                blankToNull(lifecycleStatus), blankToNull(lifecycleStatus),
                blankToNull(reviewStatus), blankToNull(reviewStatus)));
        if (allowedDocumentIds != null) args.addAll(allowedDocumentIds);
        args.add(size);
        args.add(Math.max(0, page - 1) * size);
        return jdbc.query(sql, this::mapDocument, args.toArray());
    }

    @Override
    public long countDocuments(UUID organizationId, String keyword, String status, String aiStatus,
                               String scope, UUID categoryId, String lifecycleStatus, String reviewStatus) {
        return countDocuments(organizationId, keyword, status, aiStatus, scope, categoryId,
                lifecycleStatus, reviewStatus, null);
    }

    @Override
    public long countDocuments(UUID organizationId, String keyword, String status, String aiStatus,
                               String scope, UUID categoryId, String lifecycleStatus, String reviewStatus,
                               java.util.Set<UUID> allowedDocumentIds) {
        if (allowedDocumentIds != null && allowedDocumentIds.isEmpty()) return 0;
        var normalizedKeyword = blankToNull(keyword);
        var normalizedStatus = blankToNull(status);
        var normalizedAiStatus = blankToNull(aiStatus);
        var args = new java.util.ArrayList<Object>(java.util.Arrays.asList(organizationId, normalizedKeyword, normalizedKeyword,
                normalizedKeyword, normalizedStatus, normalizedStatus, normalizedAiStatus, normalizedAiStatus,
                blankToNull(scope), blankToNull(scope), categoryId, categoryId,
                blankToNull(lifecycleStatus), blankToNull(lifecycleStatus),
                blankToNull(reviewStatus), blankToNull(reviewStatus)));
        if (allowedDocumentIds != null) args.addAll(allowedDocumentIds);
        return jdbc.queryForObject("""
                SELECT count(*) FROM kb.document d
                JOIN kb.document_version v ON v.document_id = d.id AND v.version_no = d.current_version_no
                LEFT JOIN kb.document_ai_grant g ON g.document_id = d.id
                WHERE d.organization_id = ?
                  AND (CAST(? AS text) IS NULL OR d.title ILIKE '%' || ? || '%' OR v.original_name ILIKE '%' || ? || '%')
                  AND (CAST(? AS text) IS NULL OR d.status = ?)
                  AND (CAST(? AS text) IS NULL OR coalesce(g.status, 'PENDING') = ?)
                  AND (CAST(? AS text) IS NULL OR d.library_scope = ?)
                  AND (CAST(? AS uuid) IS NULL OR d.category_id = ?)
                  AND (CAST(? AS text) IS NULL OR d.lifecycle_status = ?)
                  AND (CAST(? AS text) IS NULL OR v.review_status = ?)
                """ + documentIdClause(allowedDocumentIds), Long.class, args.toArray());
    }

    private String documentIdClause(java.util.Set<UUID> allowedDocumentIds) {
        if (allowedDocumentIds == null) return "";
        return " AND d.id IN (" + String.join(",", java.util.Collections.nCopies(allowedDocumentIds.size(), "?")) + ")\n";
    }

    @Override
    public List<CategoryRow> listCategories(UUID organizationId, String scope) {
        var normalizedScope = blankToNull(scope);
        return jdbc.query("""
                SELECT c.id, c.scope, c.name, c.description, c.sort_order, count(d.id) AS document_count
                FROM kb.document_category c
                LEFT JOIN kb.document d ON d.category_id = c.id
                    AND d.organization_id = c.organization_id
                WHERE c.organization_id = ? AND (CAST(? AS text) IS NULL OR c.scope = ?)
                GROUP BY c.id, c.scope, c.name, c.description, c.sort_order, c.created_at
                ORDER BY c.sort_order, c.created_at
                """, (rs, n) -> new CategoryRow(rs.getObject("id", UUID.class), rs.getString("scope"),
                        rs.getString("name"), rs.getString("description"), rs.getInt("sort_order"), rs.getLong("document_count")),
                organizationId, normalizedScope, normalizedScope);
    }

    @Override
    public Optional<CategoryRow> findCategory(UUID organizationId, UUID categoryId) {
        return jdbc.query("""
                SELECT c.id, c.scope, c.name, c.description, c.sort_order, count(d.id) AS document_count
                FROM kb.document_category c
                LEFT JOIN kb.document d ON d.category_id = c.id AND d.organization_id = c.organization_id
                WHERE c.organization_id = ? AND c.id = ?
                GROUP BY c.id, c.scope, c.name, c.description, c.sort_order, c.created_at
                """, (rs, n) -> new CategoryRow(rs.getObject("id", UUID.class), rs.getString("scope"),
                        rs.getString("name"), rs.getString("description"), rs.getInt("sort_order"), rs.getLong("document_count")),
                organizationId, categoryId).stream().findFirst();
    }

    @Override
    public Optional<CategoryRow> findDefaultCategory(UUID organizationId, String scope) {
        return jdbc.query("""
                SELECT c.id, c.scope, c.name, c.description, c.sort_order, count(d.id) AS document_count
                FROM kb.document_category c
                LEFT JOIN kb.document d ON d.category_id = c.id AND d.organization_id = c.organization_id
                WHERE c.organization_id = ? AND c.scope = ? AND c.name = '未分类'
                GROUP BY c.id, c.scope, c.name, c.description, c.sort_order, c.created_at
                """, (rs, n) -> new CategoryRow(rs.getObject("id", UUID.class), rs.getString("scope"),
                        rs.getString("name"), rs.getString("description"), rs.getInt("sort_order"), rs.getLong("document_count")),
                organizationId, scope).stream().findFirst();
    }

    @Override
    public CategoryRow createCategory(UUID organizationId, UUID actorId, String scope, String name, String description) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kb.document_category (id, organization_id, scope, name, description, sort_order, created_by)
                VALUES (?, ?, ?, ?, ?, coalesce((SELECT max(sort_order) + 1 FROM kb.document_category WHERE organization_id = ? AND scope = ?), 1), ?)
                """, id, organizationId, scope, name, description, organizationId, scope, actorId);
        return findCategory(organizationId, id).orElseThrow();
    }

    @Override
    public CategoryRow renameCategory(UUID organizationId, UUID categoryId, String name, String description) {
        jdbc.update("UPDATE kb.document_category SET name = ?, description = ?, updated_at = now() WHERE organization_id = ? AND id = ?",
                name, description, organizationId, categoryId);
        return findCategory(organizationId, categoryId).orElseThrow();
    }

    @Override
    @Transactional
    public void deleteCategory(UUID organizationId, UUID categoryId, UUID replacementCategoryId) {
        if (replacementCategoryId != null) {
            jdbc.update("""
                    UPDATE kb.document SET category_id = ?
                    WHERE organization_id = ? AND category_id = ?
                    """, replacementCategoryId, organizationId, categoryId);
        } else if (jdbc.queryForObject("SELECT count(*) FROM kb.document WHERE organization_id = ? AND category_id = ?",
                Long.class, organizationId, categoryId) > 0) {
            throw new IllegalStateException("分类仍有文档，请先选择替代分类");
        }
        jdbc.update("DELETE FROM kb.document_category WHERE organization_id = ? AND id = ?", organizationId, categoryId);
    }

    @Override
    public void assignCategory(UUID organizationId, UUID documentId, UUID categoryId) {
        jdbc.update("""
                UPDATE kb.document d SET category_id = ?, library_scope = c.scope, updated_at = now()
                FROM kb.document_category c
                WHERE d.organization_id = ? AND d.id = ? AND c.organization_id = d.organization_id AND c.id = ?
                """, categoryId, organizationId, documentId, categoryId);
    }

    @Override
    public void renameDocument(UUID organizationId, UUID documentId, String title) {
        jdbc.update("UPDATE kb.document SET title = ?, updated_at = now() WHERE organization_id = ? AND id = ?",
                title, organizationId, documentId);
    }

    @Override
    public void deleteDocument(UUID organizationId, UUID documentId) {
        jdbc.update("DELETE FROM kb.document WHERE organization_id = ? AND id = ?", organizationId, documentId);
    }

    @Override
    public Optional<VersionRow> findVersion(UUID organizationId, UUID versionId) {
        return jdbc.query("""
                SELECT v.id, v.document_id, v.version_no, v.file_object_id, v.original_name,
                       v.content_type, v.size_bytes, v.sha256, v.status, v.parser_version, v.error_message,
                       v.review_status, v.review_revision, v.ocr_mode, v.allow_agent_fallback,
                       v.effective_ocr, v.parser_mode, v.parser_metadata_jsonb
                FROM kb.document_version v
                JOIN kb.document d ON d.id = v.document_id
                WHERE d.organization_id = ? AND v.id = ?
                """, (rs, rowNum) -> new VersionRow(
                rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getInt("version_no"),
                rs.getObject("file_object_id", UUID.class), rs.getString("original_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("status"), rs.getString("parser_version"), rs.getString("error_message"),
                rs.getString("review_status"), rs.getInt("review_revision"), rs.getString("ocr_mode"),
                rs.getBoolean("allow_agent_fallback"), (Boolean) rs.getObject("effective_ocr"),
                rs.getString("parser_mode"), rs.getString("parser_metadata_jsonb")
        ), organizationId, versionId).stream().findFirst();
    }

    @Override
    public List<VersionRow> listVersions(UUID organizationId, UUID documentId) {
        return jdbc.query("""
                SELECT v.id, v.document_id, v.version_no, v.file_object_id, v.original_name,
                       v.content_type, v.size_bytes, v.sha256, v.status, v.parser_version, v.error_message,
                       v.review_status, v.review_revision, v.ocr_mode, v.allow_agent_fallback,
                       v.effective_ocr, v.parser_mode, v.parser_metadata_jsonb
                FROM kb.document_version v
                JOIN kb.document d ON d.id = v.document_id
                WHERE d.organization_id = ? AND d.id = ?
                ORDER BY v.version_no DESC
                """, (rs, rowNum) -> new VersionRow(
                rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getInt("version_no"),
                rs.getObject("file_object_id", UUID.class), rs.getString("original_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("status"), rs.getString("parser_version"), rs.getString("error_message"),
                rs.getString("review_status"), rs.getInt("review_revision"), rs.getString("ocr_mode"),
                rs.getBoolean("allow_agent_fallback"), (Boolean) rs.getObject("effective_ocr"),
                rs.getString("parser_mode"), rs.getString("parser_metadata_jsonb")
        ), organizationId, documentId);
    }

    @Override
    public void updateVersionParsingPolicy(UUID organizationId, UUID versionId, String ocrMode,
                                           boolean allowAgentFallback) {
        jdbc.update("""
                UPDATE kb.document_version v
                SET ocr_mode = ?, allow_agent_fallback = ?, effective_ocr = NULL,
                    parser_mode = NULL, parser_metadata_jsonb = '{}'::jsonb, updated_at = now()
                FROM kb.document d
                WHERE d.id = v.document_id AND d.organization_id = ? AND v.id = ?
                """, ocrMode, allowAgentFallback, organizationId, versionId);
    }

    @Override
    public void updateVersionParseOutcome(UUID organizationId, UUID versionId, Boolean effectiveOcr,
                                          String parserMode, String parserMetadataJson) {
        jdbc.update("""
                UPDATE kb.document_version v
                SET effective_ocr = ?, parser_mode = ?, parser_metadata_jsonb = CAST(? AS jsonb), updated_at = now()
                FROM kb.document d
                WHERE d.id = v.document_id AND d.organization_id = ? AND v.id = ?
                """, effectiveOcr, parserMode, parserMetadataJson == null ? "{}" : parserMetadataJson,
                organizationId, versionId);
    }

    @Override
    public void updateCurrentVersion(UUID organizationId, UUID documentId, int versionNo) {
        jdbc.update("""
                UPDATE kb.document
                SET current_version_no = ?, status = 'QUEUED', scan_status = 'PENDING',
                    parse_error = NULL, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, versionNo, organizationId, documentId);
    }

    @Override
    public void updateProcessing(UUID documentId, UUID versionId) {
        jdbc.update("UPDATE kb.document SET status = 'PROCESSING', parse_error = NULL, updated_at = now() WHERE id = ?",
                documentId);
        jdbc.update("UPDATE kb.document_version SET status = 'PROCESSING', error_message = NULL, updated_at = now() WHERE id = ?",
                versionId);
    }

    @Override
    public void updateScanStatus(UUID documentId, String scanStatus) {
        jdbc.update("UPDATE kb.document SET scan_status = ?, updated_at = now() WHERE id = ?", scanStatus, documentId);
    }

    @Override
    public Optional<ChunkAnchorRow> findChunkAnchor(UUID organizationId, UUID chunkId) {
        return jdbc.query("""
                SELECT c.page_no, c.sheet_name, c.cell_range, c.paragraph_id, c.bbox_jsonb,
                       c.start_time_ms, c.end_time_ms, c.section, c.source_anchor_jsonb,
                       c.source_anchors_jsonb, c.review_node_ids_jsonb, c.source_node_keys_jsonb
                FROM kb.document_chunk c JOIN kb.document d ON d.id = c.document_id
                WHERE d.organization_id = ? AND c.id = ?
                """, (rs, ignored) -> new ChunkAnchorRow((Integer) rs.getObject("page_no"), rs.getString("sheet_name"),
                rs.getString("cell_range"), rs.getString("paragraph_id"), parseDoubles(rs.getString("bbox_jsonb")),
                (Long) rs.getObject("start_time_ms"), (Long) rs.getObject("end_time_ms"), rs.getString("section"),
                rs.getString("source_anchor_jsonb"), rs.getString("source_anchors_jsonb"),
                parseUuids(rs.getString("review_node_ids_jsonb")), parseUuids(rs.getString("source_node_keys_jsonb"))),
                organizationId, chunkId).stream().findFirst();
    }

    @Override
    @Transactional
    public void replaceChunks(UUID documentId, UUID versionId, UUID reviewRevisionId, List<ChunkWrite> chunks) {
        jdbc.update("DELETE FROM kb.document_chunk WHERE review_revision_id = ?", reviewRevisionId);
        insertChunks(documentId, versionId, reviewRevisionId, chunks);
    }

    private void insertChunks(UUID documentId, UUID versionId, UUID reviewRevisionId, List<ChunkWrite> chunks) {
        var parseRunId = jdbc.queryForObject(
                "SELECT parse_run_id FROM kb.document_review_revision WHERE id = ?", UUID.class, reviewRevisionId);
        var ids = new java.util.LinkedHashMap<String, UUID>();
        for (var chunk : chunks) ids.put(chunk.chunkKey(), UUID.randomUUID());
        for (var chunk : chunks) {
            var chunkId = ids.get(chunk.chunkKey());
            var effectiveParentId = chunk.parentKey() == null ? null : ids.get(chunk.parentKey());
            if (chunk.parentKey() != null && effectiveParentId == null) {
                throw new IllegalArgumentException("Chunk 父块键不存在：" + chunk.parentKey());
            }
            jdbc.update("""
                    INSERT INTO kb.document_chunk (
                        id, document_id, document_version_id, parse_run_id, review_revision_id,
                        chunk_no, page_no, section, content, embedding,
                        parent_chunk_id, token_length, analyzer_version, embedding_model, sheet_name, cell_range,
                        paragraph_id, bbox_jsonb, start_time_ms, end_time_ms, chunk_role, heading_path_jsonb,
                        review_node_ids_jsonb, source_node_keys_jsonb, source_anchor_jsonb,
                        source_anchors_jsonb, relations_jsonb, model_token_length
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?,
                              ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb),
                              CAST(? AS jsonb), CAST(? AS jsonb), ?)
                    """, chunkId, documentId, versionId, parseRunId, reviewRevisionId,
                    chunk.chunkNo(), chunk.pageNo(), chunk.section(),
                    chunk.content(), chunk.vector(), effectiveParentId, chunk.tokenLength(), chunk.analyzerVersion(),
                    chunk.embeddingModel(), chunk.sheetName(), chunk.cellRange(), chunk.paragraphId(),
                    chunk.bbox() == null || chunk.bbox().isEmpty() ? null : chunk.bbox().toString(),
                    chunk.startTimeMs(), chunk.endTimeMs(), chunk.chunkRole(), jsonStrings(chunk.headingPath()),
                    jsonUuids(chunk.reviewNodeIds()), jsonUuids(chunk.sourceNodeKeys()), chunk.sourceAnchorJson(),
                    chunk.sourceAnchorsJson(), chunk.relationsJson(), chunk.modelTokenLength());
            if (!"CHILD".equals(chunk.chunkRole())) continue;
            for (var term : chunk.terms() == null ? List.<TermFrequency>of() : chunk.terms()) {
                jdbc.update("""
                        INSERT INTO kb.chunk_term (chunk_id, term, term_frequency)
                        VALUES (?, ?, ?)
                        ON CONFLICT (chunk_id, term) DO UPDATE SET term_frequency = EXCLUDED.term_frequency
                        """, chunkId, term.term(), term.frequency());
            }
        }
    }

    @Override
    public void rebuildTermStats(UUID organizationId) {
        jdbc.update("DELETE FROM kb.term_stat WHERE organization_id = ?", organizationId);
        jdbc.update("""
                WITH corpus AS (
                    SELECT c.analyzer_version, count(*)::int AS document_count,
                           coalesce(avg(c.token_length), 0)::double precision AS average_document_length
                    FROM kb.document_chunk c
                    JOIN kb.document d ON d.id = c.document_id
                    JOIN kb.document_version v ON v.id = c.document_version_id
                    JOIN ops.file_object f ON f.id = v.file_object_id AND f.organization_id = d.organization_id AND f.status <> 'DELETED'
                    JOIN kb.publication p ON p.id = d.current_publication_id
                        AND p.document_version_id = c.document_version_id AND p.review_revision_id = c.review_revision_id
                        AND p.status = 'CURRENT'
                    WHERE d.organization_id = ? AND d.lifecycle_status = 'ACTIVE' AND c.chunk_role = 'CHILD'
                    GROUP BY c.analyzer_version
                ), stats AS (
                    SELECT c.analyzer_version, t.term, count(DISTINCT t.chunk_id)::int AS document_frequency
                    FROM kb.chunk_term t
                    JOIN kb.document_chunk c ON c.id = t.chunk_id
                    JOIN kb.document d ON d.id = c.document_id
                    JOIN kb.document_version v ON v.id = c.document_version_id
                    JOIN ops.file_object f ON f.id = v.file_object_id AND f.organization_id = d.organization_id AND f.status <> 'DELETED'
                    JOIN kb.publication p ON p.id = d.current_publication_id
                        AND p.document_version_id = c.document_version_id AND p.review_revision_id = c.review_revision_id
                        AND p.status = 'CURRENT'
                    WHERE d.organization_id = ? AND d.lifecycle_status = 'ACTIVE' AND c.chunk_role = 'CHILD'
                    GROUP BY c.analyzer_version, t.term
                )
                INSERT INTO kb.term_stat (organization_id, term, document_frequency, document_count,
                                          average_document_length, analyzer_version)
                SELECT ?, stats.term, stats.document_frequency, corpus.document_count,
                       corpus.average_document_length, stats.analyzer_version
                FROM stats JOIN corpus USING (analyzer_version)
                """, organizationId, organizationId, organizationId);
    }

    @Override
    public void startProcessingStep(UUID organizationId, UUID documentId, UUID versionId, UUID ownerId,
                                    String stepKey, String provider, String model, String inputSha256) {
        var reviewStep = isReviewStep(stepKey);
        var parseRunId = reviewStep ? null : ownerId;
        var reviewRevisionId = reviewStep ? ownerId : null;
        jdbc.update("""
                INSERT INTO kb.document_processing_step (
                    id, organization_id, document_id, document_version_id, parse_run_id, review_revision_id,
                    step_key, status, progress,
                    attempt, provider, model, input_sha256, started_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING', 0, 0, ?, ?, ?, now(), now())
                ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), organizationId, documentId, versionId, parseRunId, reviewRevisionId, stepKey,
                provider, model, inputSha256);
        jdbc.update("""
                UPDATE kb.document_processing_step
                SET status = 'RUNNING', progress = 0, attempt = attempt + 1, provider = ?, model = ?,
                    input_sha256 = ?, error_message = NULL, started_at = now(), finished_at = NULL, updated_at = now()
                WHERE organization_id = ? AND document_version_id = ? AND step_key = ?
                  AND parse_run_id IS NOT DISTINCT FROM ? AND review_revision_id IS NOT DISTINCT FROM ?
                """, provider, model, inputSha256, organizationId, versionId, stepKey,
                parseRunId, reviewRevisionId);
    }

    @Override
    public void finishProcessingStep(UUID organizationId, UUID versionId, UUID ownerId, String stepKey,
                                     String status, String outputSha256, String errorMessage) {
        var reviewStep = isReviewStep(stepKey);
        var parseRunId = reviewStep ? null : ownerId;
        var reviewRevisionId = reviewStep ? ownerId : null;
        jdbc.update("""
                UPDATE kb.document_processing_step
                SET status = ?, progress = CASE WHEN ? = 'SUCCEEDED' THEN 100 ELSE progress END,
                    output_sha256 = ?, error_message = ?, finished_at = now(), updated_at = now()
                WHERE organization_id = ? AND document_version_id = ? AND step_key = ?
                  AND parse_run_id IS NOT DISTINCT FROM ? AND review_revision_id IS NOT DISTINCT FROM ?
                """, status, status, outputSha256, errorMessage == null ? null : truncate(errorMessage),
                organizationId, versionId, stepKey, parseRunId, reviewRevisionId);
    }

    @Override
    public void attachProcessingSteps(UUID organizationId, UUID versionId, UUID parseRunId) {
        jdbc.update("""
                UPDATE kb.document_processing_step SET parse_run_id = ?, updated_at = now()
                WHERE organization_id = ? AND document_version_id = ? AND parse_run_id IS NULL
                """, parseRunId, organizationId, versionId);
    }

    @Override
    public void markReady(UUID documentId, UUID versionId, String parserVersion, String textSha256) {
        jdbc.update("""
                UPDATE kb.document_version
                SET status = 'READY', parser_version = ?, extracted_text_sha256 = ?, error_message = NULL, updated_at = now()
                WHERE id = ?
                """, parserVersion, textSha256, versionId);
        jdbc.update("""
                UPDATE kb.document
                SET status = 'READY', parse_error = NULL, updated_at = now()
                WHERE id = ?
                """, documentId);
    }

    @Override
    public void markFailed(UUID documentId, UUID versionId, String status, String error) {
        jdbc.update("""
                UPDATE kb.document_version
                SET status = ?, error_message = ?,
                    review_reason = CASE WHEN review_reason = 'REPARSE_QUEUED' THEN NULL ELSE review_reason END,
                    updated_at = now()
                WHERE id = ?
                """,
                status, truncate(error), versionId);
        jdbc.update("UPDATE kb.document SET status = ?, parse_error = ?, updated_at = now() WHERE id = ?",
                status, truncate(error), documentId);
    }

    @Override
    public boolean isAiApproved(UUID organizationId, UUID documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM kb.document_ai_grant
                              WHERE organization_id = ? AND document_id = ? AND status = 'APPROVED')
                """, Boolean.class, organizationId, documentId));
    }

    @Override
    public List<ChunkEmbeddingRow> chunksForEmbedding(UUID organizationId, UUID documentId, UUID reviewRevisionId) {
        return jdbc.query("""
                SELECT c.id, c.content FROM kb.document_chunk c
                JOIN kb.document d ON d.id = c.document_id
                WHERE d.organization_id = ? AND c.document_id = ? AND c.review_revision_id = ?
                  AND c.chunk_role = 'CHILD'
                ORDER BY c.chunk_no
                """, (rs, ignored) -> new ChunkEmbeddingRow(rs.getObject("id", UUID.class), rs.getString("content")),
                organizationId, documentId, reviewRevisionId);
    }

    @Override
    public void updateChunkEmbedding(UUID organizationId, UUID chunkId, String vector, String embeddingModel) {
        jdbc.update("""
                UPDATE kb.document_chunk c SET embedding = CAST(? AS vector), embedding_model = ?
                FROM kb.document d WHERE d.id = c.document_id AND d.organization_id = ? AND c.id = ?
                  AND EXISTS (SELECT 1 FROM kb.document_ai_grant g
                              WHERE g.document_id = d.id AND g.status = 'APPROVED')
                """, vector, embeddingModel, organizationId, chunkId);
    }

    @Override
    public void clearDocumentEmbeddings(UUID organizationId, UUID documentId) {
        jdbc.update("""
                UPDATE kb.document_chunk c SET embedding = NULL, embedding_model = NULL
                FROM kb.document d WHERE d.id = c.document_id AND d.organization_id = ? AND d.id = ?
                """, organizationId, documentId);
    }

    @Override
    public void cancelPendingVectorJobs(UUID organizationId, UUID documentId) {
        jdbc.update("""
                UPDATE ops.async_job SET status = 'CANCELLED', lease_owner = NULL, lease_expires_at = NULL,
                    finished_at = now(), updated_at = now()
                WHERE organization_id = ? AND job_type = 'KB_BUILD_KNOWLEDGE_VECTOR' AND status = 'READY'
                  AND payload_jsonb->>'documentId' = ?
                """, organizationId, documentId.toString());
    }

    @Override
    public List<SearchRow> fullTextSearch(UUID organizationId, String query, boolean aiOnly, int limit) {
        return fullTextSearch(organizationId, query, aiOnly, List.of(), limit);
    }

    @Override
    public List<SearchRow> fullTextSearch(UUID organizationId, String query, boolean aiOnly,
                                          List<UUID> categoryIds, int limit) {
        return fullTextSearch(organizationId, query, aiOnly, categoryIds, null, limit);
    }

    @Override
    public List<SearchRow> fullTextSearch(UUID organizationId, String query, boolean aiOnly,
                                          List<UUID> categoryIds, java.util.Set<UUID> allowedDocumentIds, int limit) {
        if (allowedDocumentIds != null && allowedDocumentIds.isEmpty()) return List.of();
        var aiClause = aiOnly ? "AND EXISTS (SELECT 1 FROM kb.document_ai_grant g WHERE g.document_id = d.id AND g.status = 'APPROVED')" : "";
        var sql = """
                SELECT c.id, c.document_id, c.document_version_id,
                       coalesce(p.metadata_snapshot_jsonb->>'title', d.title) AS title, v.original_name,
                       c.page_no, c.section, c.content, c.chunk_no,
                       GREATEST(ts_rank_cd(c.search_vector, plainto_tsquery('simple', ?)),
                                similarity(c.content, ?),
                                CASE WHEN c.content ILIKE '%' || ? || '%' THEN 0.8 ELSE 0 END) AS score
                FROM kb.document_chunk c
                JOIN kb.document d ON d.id = c.document_id
                JOIN kb.document_version v ON v.id = c.document_version_id
                JOIN ops.file_object f ON f.id = v.file_object_id AND f.organization_id = d.organization_id AND f.status <> 'DELETED'
                JOIN kb.publication p ON p.id = d.current_publication_id
                    AND p.document_version_id = c.document_version_id AND p.review_revision_id = c.review_revision_id
                    AND p.status = 'CURRENT'
                WHERE d.organization_id = ? AND d.lifecycle_status = 'ACTIVE' AND c.chunk_role = 'CHILD'
                """ + aiClause + """
                """ + categoryClause(categoryIds) + """
                """ + documentClause(allowedDocumentIds) + """
                  AND (c.search_vector @@ plainto_tsquery('simple', ?)
                       OR c.content ILIKE '%' || ? || '%')
                ORDER BY score DESC, c.created_at DESC LIMIT ?
                """;
        var args = new java.util.ArrayList<Object>();
        args.add(query); args.add(query); args.add(query); args.add(organizationId);
        if (categoryIds != null) args.addAll(categoryIds);
        if (allowedDocumentIds != null) args.addAll(allowedDocumentIds);
        args.add(query); args.add(query);
        args.add(limit);
        return jdbc.query(sql, this::mapSearch, args.toArray());
    }

    @Override
    public List<SearchRow> phraseSearch(UUID organizationId, List<String> phrases, boolean aiOnly,
                                       List<UUID> categoryIds, int limit) {
        var values = phrases == null ? List.<String>of() : phrases.stream()
                .filter(org.springframework.util.StringUtils::hasText).map(String::strip).distinct().limit(48).toList();
        if (values.isEmpty()) return List.of();
        var aiClause = aiOnly
                ? "AND EXISTS (SELECT 1 FROM kb.document_ai_grant g WHERE g.document_id = d.id AND g.status = 'APPROVED')"
                : "";
        var sql = """
                WITH phrases AS (
                    SELECT DISTINCT phrase FROM unnest(?::text[]) AS p(phrase)
                    WHERE length(phrase) >= 2
                ), ranked AS (
                    SELECT c.id, c.document_id, c.document_version_id,
                           coalesce(published.metadata_snapshot_jsonb->>'title', d.title) AS title, v.original_name,
                           c.page_no, c.section, c.content, c.chunk_no,
                           max(GREATEST(similarity(lower(c.content), lower(phrases.phrase)),
                               CASE WHEN c.content ILIKE '%' || phrases.phrase || '%' THEN 0.9 ELSE 0 END)) AS score
                    FROM kb.document_chunk c
                    JOIN kb.document d ON d.id = c.document_id
                    JOIN kb.document_version v ON v.id = c.document_version_id
                    JOIN ops.file_object f ON f.id = v.file_object_id AND f.organization_id = d.organization_id AND f.status <> 'DELETED'
                    JOIN kb.publication published ON published.id = d.current_publication_id
                        AND published.document_version_id = c.document_version_id
                        AND published.review_revision_id = c.review_revision_id AND published.status = 'CURRENT'
                    JOIN phrases ON c.content ILIKE '%' || phrases.phrase || '%'
                        OR similarity(lower(c.content), lower(phrases.phrase)) >= 0.18
                    WHERE d.organization_id = ? AND d.lifecycle_status = 'ACTIVE' AND c.chunk_role = 'CHILD'
                """ + aiClause + categoryClause(categoryIds) + """
                    GROUP BY c.id, c.document_id, c.document_version_id,
                             coalesce(published.metadata_snapshot_jsonb->>'title', d.title), v.original_name,
                             c.page_no, c.section, c.content, c.chunk_no
                )
                SELECT id, document_id, document_version_id, title, original_name, page_no, section, content, score, chunk_no
                FROM ranked ORDER BY score DESC LIMIT ?
                """;
        var args = new java.util.ArrayList<Object>();
        args.add(values.toArray(String[]::new));
        args.add(organizationId);
        if (categoryIds != null) args.addAll(categoryIds);
        args.add(limit);
        return jdbc.query(sql, this::mapSearch, args.toArray());
    }

    @Override
    public List<SearchRow> bm25Search(UUID organizationId, List<AnalyzedTerm> terms, boolean aiOnly,
                                      List<UUID> categoryIds, int limit) {
        return bm25Search(organizationId, terms, aiOnly, categoryIds, null, limit);
    }

    @Override
    public List<SearchRow> bm25Search(UUID organizationId, List<AnalyzedTerm> terms, boolean aiOnly,
                                      List<UUID> categoryIds, java.util.Set<UUID> allowedDocumentIds, int limit) {
        if (terms == null || terms.isEmpty()) return List.of();
        if (allowedDocumentIds != null && allowedDocumentIds.isEmpty()) return List.of();
        var aiClause = aiOnly ? "AND EXISTS (SELECT 1 FROM kb.document_ai_grant g WHERE g.document_id = d.id AND g.status = 'APPROVED')" : "";
        var sql = """
                WITH query_terms AS (
                    SELECT * FROM unnest(?::text[], ?::text[]) AS q(analyzer_version, term)
                ), ranked AS (
                    SELECT c.id, c.document_id, c.document_version_id,
                           coalesce(p.metadata_snapshot_jsonb->>'title', d.title) AS title, v.original_name,
                           c.page_no, c.section, c.content, c.chunk_no,
                           sum(
                               ln(((s.document_count - s.document_frequency + 0.5) / (s.document_frequency + 0.5)) + 1)
                               * ((t.term_frequency * 2.2) /
                                  (t.term_frequency + 1.2 * (1 - 0.75 + 0.75 * c.token_length /
                                  nullif(s.average_document_length, 0))))
                           ) AS score
                    FROM kb.chunk_term t
                    JOIN kb.document_chunk c ON c.id = t.chunk_id
                    JOIN query_terms q ON q.term = t.term AND q.analyzer_version = c.analyzer_version
                    JOIN kb.term_stat s ON s.organization_id = ? AND s.term = t.term
                        AND s.analyzer_version = c.analyzer_version
                    JOIN kb.document d ON d.id = c.document_id
                    JOIN kb.document_version v ON v.id = c.document_version_id
                    JOIN ops.file_object f ON f.id = v.file_object_id AND f.organization_id = d.organization_id AND f.status <> 'DELETED'
                    JOIN kb.publication p ON p.id = d.current_publication_id
                        AND p.document_version_id = c.document_version_id AND p.review_revision_id = c.review_revision_id
                        AND p.status = 'CURRENT'
                    WHERE d.organization_id = ? AND d.lifecycle_status = 'ACTIVE' AND c.chunk_role = 'CHILD'
                """ + aiClause + categoryClause(categoryIds) + documentClause(allowedDocumentIds) + """
                    GROUP BY c.id, c.document_id, c.document_version_id,
                             coalesce(p.metadata_snapshot_jsonb->>'title', d.title), v.original_name,
                             c.page_no, c.section, c.content, c.chunk_no
                )
                SELECT id, document_id, document_version_id, title, original_name, page_no, section, content, score, chunk_no
                FROM ranked ORDER BY score DESC LIMIT ?
                """;
        var args = new java.util.ArrayList<Object>();
        args.add(terms.stream().map(AnalyzedTerm::analyzerVersion).toArray(String[]::new));
        args.add(terms.stream().map(AnalyzedTerm::term).toArray(String[]::new));
        args.add(organizationId);
        args.add(organizationId);
        if (categoryIds != null) args.addAll(categoryIds);
        if (allowedDocumentIds != null) args.addAll(allowedDocumentIds);
        args.add(limit);
        return jdbc.query(sql, this::mapSearch, args.toArray());
    }

    @Override
    public List<SearchRow> vectorSearch(UUID organizationId, String vector, boolean aiOnly, int limit) {
        return vectorSearch(organizationId, vector, aiOnly, List.of(), limit, 0);
    }

    @Override
    public List<SearchRow> vectorSearch(UUID organizationId, String vector, boolean aiOnly,
                                       List<UUID> categoryIds, int limit) {
        return vectorSearch(organizationId, vector, aiOnly, categoryIds, limit, 0);
    }

    @Override
    public List<SearchRow> vectorSearch(UUID organizationId, String vector, boolean aiOnly,
                                       List<UUID> categoryIds, int limit, int dimension) {
        var aiClause = aiOnly ? "AND EXISTS (SELECT 1 FROM kb.document_ai_grant g WHERE g.document_id = d.id AND g.status = 'APPROVED')" : "";
        var dimensionClause = dimension > 0 ? " AND vector_dims(c.embedding) = ?\n" : "";
        var sql = """
                SELECT c.id, c.document_id, c.document_version_id,
                       coalesce(p.metadata_snapshot_jsonb->>'title', d.title) AS title, v.original_name,
                       c.page_no, c.section, c.content, c.chunk_no,
                       (1 - (c.embedding <=> CAST(? AS vector))) AS score
                FROM kb.document_chunk c
                JOIN kb.document d ON d.id = c.document_id
                JOIN kb.document_version v ON v.id = c.document_version_id
                JOIN ops.file_object f ON f.id = v.file_object_id AND f.organization_id = d.organization_id AND f.status <> 'DELETED'
                JOIN kb.publication p ON p.id = d.current_publication_id
                    AND p.document_version_id = c.document_version_id AND p.review_revision_id = c.review_revision_id
                    AND p.status = 'CURRENT'
                WHERE d.organization_id = ? AND d.lifecycle_status = 'ACTIVE'
                """ + aiClause + categoryClause(categoryIds) + " AND c.chunk_role = 'CHILD' AND c.embedding IS NOT NULL\n"
                + dimensionClause
                + "ORDER BY c.embedding <=> CAST(? AS vector) LIMIT ?";
        var args = new java.util.ArrayList<Object>();
        args.add(vector); args.add(organizationId);
        if (categoryIds != null) args.addAll(categoryIds);
        if (dimension > 0) args.add(dimension);
        args.add(vector); args.add(limit);
        return jdbc.query(sql, this::mapSearch, args.toArray());
    }

    private String documentQuery(String where) {
        return """
                SELECT d.id, d.organization_id, d.title, d.status, d.scan_status,
                       coalesce(g.status, 'PENDING') AS ai_status, d.current_version_no, v.id AS current_version_id,
                       v.original_name, v.content_type, v.size_bytes, v.sha256,
                       d.parse_error, d.created_at, d.updated_at, d.library_scope,
                       d.category_id, c.name AS category_name, d.lifecycle_status,
                       v.review_status, v.review_revision, d.current_publication_id,
                       p.publication_no AS current_publication_no
                FROM kb.document d
                JOIN kb.document_version v ON v.document_id = d.id AND v.version_no = d.current_version_no
                LEFT JOIN kb.document_category c ON c.id = d.category_id
                LEFT JOIN kb.publication p ON p.id = d.current_publication_id AND p.status = 'CURRENT'
                LEFT JOIN kb.document_ai_grant g ON g.document_id = d.id
                """ + where;
    }

    private DocumentRow mapDocument(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DocumentRow(
                rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class), rs.getString("title"),
                rs.getString("status"), rs.getString("scan_status"),
                rs.getString("ai_status"), rs.getInt("current_version_no"),
                rs.getObject("current_version_id", UUID.class), rs.getString("original_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("parse_error"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                rs.getString("library_scope"), rs.getObject("category_id", UUID.class), rs.getString("category_name"),
                rs.getString("lifecycle_status"), rs.getString("review_status"), rs.getInt("review_revision"),
                rs.getObject("current_publication_id", UUID.class),
                rs.getObject("current_publication_no") == null ? null : rs.getInt("current_publication_no")
        );
    }

    private SearchRow mapSearch(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SearchRow(
                rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("document_version_id", UUID.class), rs.getString("title"),
                rs.getString("original_name"), (Integer) rs.getObject("page_no"), rs.getString("section"),
                rs.getString("content"), rs.getDouble("score"), rs.getInt("chunk_no")
        );
    }

    private java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private List<Double> parseDoubles(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            var value = json.replace("[", "").replace("]", "").trim();
            if (value.isBlank()) return List.of();
            return java.util.Arrays.stream(value.split(",")).map(String::trim).map(Double::parseDouble).toList();
        } catch (RuntimeException exception) { return List.of(); }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<UUID> parseUuids(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return java.util.Arrays.stream(json.replace("[", "").replace("]", "").replace("\"", "")
                            .split(","))
                    .map(String::trim).filter(value -> !value.isBlank()).map(UUID::fromString).toList();
        } catch (RuntimeException exception) { return List.of(); }
    }

    private String jsonUuids(List<UUID> values) {
        if (values == null || values.isEmpty()) return "[]";
        return values.stream().map(value -> "\"" + value + "\"")
                .reduce((left, right) -> left + "," + right).map(value -> "[" + value + "]").orElse("[]");
    }

    private String jsonStrings(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        return values.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .reduce((left, right) -> left + "," + right).map(value -> "[" + value + "]").orElse("[]");
    }

    private String categoryClause(List<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) return "";
        var placeholders = String.join(",", java.util.Collections.nCopies(categoryIds.size(), "?"));
        return " AND coalesce(nullif(p.metadata_snapshot_jsonb->>'categoryId', '')::uuid,"
                + " nullif(p.metadata_snapshot_jsonb->>'category_id', '')::uuid, d.category_id) IN ("
                + placeholders + ")\n";
    }

    private String documentClause(java.util.Set<UUID> documentIds) {
        if (documentIds == null) return "";
        var placeholders = String.join(",", java.util.Collections.nCopies(documentIds.size(), "?"));
        return " AND d.id IN (" + placeholders + ")\n";
    }

    private boolean isReviewStep(String stepKey) {
        return "CHUNK".equals(stepKey) || "BM25_INDEX".equals(stepKey) || "VECTOR_INDEX".equals(stepKey);
    }

    private String truncate(String value) {
        if (value == null) return "处理失败";
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
