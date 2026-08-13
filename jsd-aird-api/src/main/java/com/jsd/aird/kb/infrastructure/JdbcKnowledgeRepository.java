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
                INSERT INTO kb.document (id, organization_id, title, document_type, library_scope, category_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, document.id(), document.organizationId(), document.title(), document.documentType(),
                document.scope(), document.categoryId(), document.actorId());
    }

    @Override
    public void insertVersion(NewVersion version) {
        jdbc.update("""
                INSERT INTO kb.document_version (
                    id, document_id, version_no, file_object_id, original_name,
                    content_type, size_bytes, sha256
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, version.id(), version.documentId(), version.versionNo(), version.fileObjectId(),
                version.originalName(), version.contentType(), version.size(), version.sha256());
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
        var normalizedKeyword = blankToNull(keyword);
        var normalizedStatus = blankToNull(status);
        var normalizedAiStatus = blankToNull(aiStatus);
        var sql = documentQuery("""
                WHERE d.organization_id = ?
                  AND (CAST(? AS text) IS NULL OR d.title ILIKE '%' || ? || '%' OR v.original_name ILIKE '%' || ? || '%')
                  AND (CAST(? AS text) IS NULL OR d.status = ?)
                  AND (CAST(? AS text) IS NULL OR d.ai_status = ?)
                  AND (CAST(? AS text) IS NULL OR d.library_scope = ?)
                  AND (CAST(? AS uuid) IS NULL OR d.category_id = ?)
                  AND (CAST(? AS text) IS NULL OR d.lifecycle_status = ?)
                  AND (CAST(? AS text) IS NULL OR v.review_status = ?)
                ORDER BY d.updated_at DESC
                LIMIT ? OFFSET ?
                """);
        return jdbc.query(sql, this::mapDocument, organizationId, normalizedKeyword, normalizedKeyword,
                normalizedKeyword, normalizedStatus, normalizedStatus, normalizedAiStatus, normalizedAiStatus,
                blankToNull(scope), blankToNull(scope), categoryId, categoryId,
                blankToNull(lifecycleStatus), blankToNull(lifecycleStatus),
                blankToNull(reviewStatus), blankToNull(reviewStatus),
                size, Math.max(0, page - 1) * size);
    }

    @Override
    public long countDocuments(UUID organizationId, String keyword, String status, String aiStatus,
                               String scope, UUID categoryId, String lifecycleStatus, String reviewStatus) {
        var normalizedKeyword = blankToNull(keyword);
        var normalizedStatus = blankToNull(status);
        var normalizedAiStatus = blankToNull(aiStatus);
        return jdbc.queryForObject("""
                SELECT count(*) FROM kb.document d
                JOIN kb.document_version v ON v.document_id = d.id AND v.version_no = d.current_version_no
                WHERE d.organization_id = ?
                  AND (CAST(? AS text) IS NULL OR d.title ILIKE '%' || ? || '%' OR v.original_name ILIKE '%' || ? || '%')
                  AND (CAST(? AS text) IS NULL OR d.status = ?)
                  AND (CAST(? AS text) IS NULL OR d.ai_status = ?)
                  AND (CAST(? AS text) IS NULL OR d.library_scope = ?)
                  AND (CAST(? AS uuid) IS NULL OR d.category_id = ?)
                  AND (CAST(? AS text) IS NULL OR d.lifecycle_status = ?)
                  AND (CAST(? AS text) IS NULL OR v.review_status = ?)
                """, Long.class, organizationId, normalizedKeyword, normalizedKeyword, normalizedKeyword,
                normalizedStatus, normalizedStatus, normalizedAiStatus, normalizedAiStatus,
                blankToNull(scope), blankToNull(scope), categoryId, categoryId,
                blankToNull(lifecycleStatus), blankToNull(lifecycleStatus),
                blankToNull(reviewStatus), blankToNull(reviewStatus));
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
                       v.review_status, v.review_revision, v.media_processing_consent
                FROM kb.document_version v
                JOIN kb.document d ON d.id = v.document_id
                WHERE d.organization_id = ? AND v.id = ?
                """, (rs, rowNum) -> new VersionRow(
                rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getInt("version_no"),
                rs.getObject("file_object_id", UUID.class), rs.getString("original_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("status"), rs.getString("parser_version"), rs.getString("error_message"),
                rs.getString("review_status"), rs.getInt("review_revision"), rs.getBoolean("media_processing_consent")
        ), organizationId, versionId).stream().findFirst();
    }

    @Override
    public List<VersionRow> listVersions(UUID organizationId, UUID documentId) {
        return jdbc.query("""
                SELECT v.id, v.document_id, v.version_no, v.file_object_id, v.original_name,
                       v.content_type, v.size_bytes, v.sha256, v.status, v.parser_version, v.error_message,
                       v.review_status, v.review_revision, v.media_processing_consent
                FROM kb.document_version v
                JOIN kb.document d ON d.id = v.document_id
                WHERE d.organization_id = ? AND d.id = ?
                ORDER BY v.version_no DESC
                """, (rs, rowNum) -> new VersionRow(
                rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getInt("version_no"),
                rs.getObject("file_object_id", UUID.class), rs.getString("original_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("status"), rs.getString("parser_version"), rs.getString("error_message"),
                rs.getString("review_status"), rs.getInt("review_revision"), rs.getBoolean("media_processing_consent")
        ), organizationId, documentId);
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
    @Transactional
    public void replaceChunks(UUID documentId, UUID versionId, List<ChunkWrite> chunks) {
        jdbc.update("DELETE FROM kb.document_chunk WHERE document_version_id = ?", versionId);
        insertChunks(documentId, versionId, null, chunks);
    }

    @Override
    public Optional<ChunkAnchorRow> findChunkAnchor(UUID organizationId, UUID chunkId) {
        return jdbc.query("""
                SELECT c.page_no, c.sheet_name, c.cell_range, c.paragraph_id, c.bbox_jsonb,
                       c.start_time_ms, c.end_time_ms, c.section
                FROM kb.document_chunk c JOIN kb.document d ON d.id = c.document_id
                WHERE d.organization_id = ? AND c.id = ?
                """, (rs, ignored) -> new ChunkAnchorRow((Integer) rs.getObject("page_no"), rs.getString("sheet_name"),
                rs.getString("cell_range"), rs.getString("paragraph_id"), parseDoubles(rs.getString("bbox_jsonb")),
                (Long) rs.getObject("start_time_ms"), (Long) rs.getObject("end_time_ms"), rs.getString("section")),
                organizationId, chunkId).stream().findFirst();
    }

    @Override
    @Transactional
    public void replaceChunks(UUID documentId, UUID versionId, UUID parseRunId, List<ChunkWrite> chunks) {
        jdbc.update("DELETE FROM kb.document_chunk WHERE parse_run_id = ?", parseRunId);
        insertChunks(documentId, versionId, parseRunId, chunks);
    }

    private void insertChunks(UUID documentId, UUID versionId, UUID parseRunId, List<ChunkWrite> chunks) {
        UUID parentChunkId = null;
        for (var chunk : chunks) {
            var chunkId = UUID.randomUUID();
            var effectiveParentId = chunk.parentChunkId() == null ? parentChunkId : chunk.parentChunkId();
            jdbc.update("""
                    INSERT INTO kb.document_chunk (
                        id, document_id, document_version_id, parse_run_id, chunk_no, page_no, section, content, embedding,
                        parent_chunk_id, token_length, analyzer_version, embedding_model, sheet_name, cell_range,
                        paragraph_id, bbox_jsonb, start_time_ms, end_time_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                    """, chunkId, documentId, versionId, parseRunId, chunk.chunkNo(), chunk.pageNo(), chunk.section(),
                    chunk.content(), chunk.vector(), effectiveParentId, chunk.tokenLength(), chunk.analyzerVersion(),
                    chunk.embeddingModel(), chunk.sheetName(), chunk.cellRange(), chunk.paragraphId(),
                    chunk.bbox() == null || chunk.bbox().isEmpty() ? null : chunk.bbox().toString(),
                    chunk.startTimeMs(), chunk.endTimeMs());
            for (var term : chunk.terms()) {
                jdbc.update("""
                        INSERT INTO kb.chunk_term (chunk_id, term, term_frequency)
                        VALUES (?, ?, ?)
                        ON CONFLICT (chunk_id, term) DO UPDATE SET term_frequency = EXCLUDED.term_frequency
                        """, chunkId, term.term(), term.frequency());
            }
            if (parentChunkId == null) parentChunkId = chunkId;
        }
    }

    @Override
    @Transactional
    public void updateReviewedChunks(UUID parseRunId, List<ReviewedChunk> chunks) {
        for (var chunk : chunks) {
            var ids = jdbc.query("""
                    UPDATE kb.document_chunk SET content = ?, token_length = ?, embedding = NULL, embedding_model = NULL
                    WHERE parse_run_id = ? AND chunk_no = ? RETURNING id
                    """, (rs, ignored) -> rs.getObject(1, UUID.class), chunk.content(),
                    chunk.terms().stream().mapToInt(term -> term.frequency()).sum(), parseRunId, chunk.chunkNo());
            if (ids.isEmpty()) continue;
            var chunkId = ids.getFirst();
            jdbc.update("DELETE FROM kb.chunk_term WHERE chunk_id = ?", chunkId);
            for (var term : chunk.terms()) {
                jdbc.update("INSERT INTO kb.chunk_term (chunk_id, term, term_frequency) VALUES (?, ?, ?)",
                        chunkId, term.term(), term.frequency());
            }
        }
    }

    @Override
    public void rebuildTermStats(UUID organizationId) {
        jdbc.update("DELETE FROM kb.term_stat WHERE organization_id = ?", organizationId);
        jdbc.update("""
                WITH corpus AS (
                    SELECT count(*)::int AS document_count,
                           coalesce(avg(c.token_length), 0)::double precision AS average_document_length
                    FROM kb.document_chunk c
                    JOIN kb.document d ON d.id = c.document_id
                    JOIN kb.document_version v ON v.id = c.document_version_id
                    JOIN ops.file_object f ON f.id = v.file_object_id AND f.organization_id = d.organization_id AND f.status <> 'DELETED'
                    JOIN kb.publication p ON p.id = d.current_publication_id
                        AND p.document_version_id = c.document_version_id AND p.parse_run_id = c.parse_run_id
                        AND p.status = 'CURRENT'
                    WHERE d.organization_id = ? AND d.lifecycle_status = 'ACTIVE'
                ), stats AS (
                    SELECT t.term, count(DISTINCT t.chunk_id)::int AS document_frequency
                    FROM kb.chunk_term t
                    JOIN kb.document_chunk c ON c.id = t.chunk_id
                    JOIN kb.document d ON d.id = c.document_id
                    JOIN kb.document_version v ON v.id = c.document_version_id
                    JOIN ops.file_object f ON f.id = v.file_object_id AND f.organization_id = d.organization_id AND f.status <> 'DELETED'
                    JOIN kb.publication p ON p.id = d.current_publication_id
                        AND p.document_version_id = c.document_version_id AND p.parse_run_id = c.parse_run_id
                        AND p.status = 'CURRENT'
                    WHERE d.organization_id = ? AND d.lifecycle_status = 'ACTIVE'
                    GROUP BY t.term
                )
                INSERT INTO kb.term_stat (organization_id, term, document_frequency, document_count, average_document_length)
                SELECT ?, stats.term, stats.document_frequency, corpus.document_count, corpus.average_document_length
                FROM stats CROSS JOIN corpus
                """, organizationId, organizationId, organizationId);
    }

    @Override
    public void startProcessingStep(UUID organizationId, UUID documentId, UUID versionId, String stepKey,
                                    String provider, String model, String inputSha256) {
        jdbc.update("""
                INSERT INTO kb.document_processing_step (
                    id, organization_id, document_id, document_version_id, step_key, status, progress,
                    attempt, provider, model, input_sha256, started_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'RUNNING', 0, 1, ?, ?, ?, now(), now())
                ON CONFLICT (document_version_id, step_key) DO UPDATE SET
                    status = 'RUNNING', progress = 0, attempt = kb.document_processing_step.attempt + 1,
                    provider = EXCLUDED.provider, model = EXCLUDED.model, input_sha256 = EXCLUDED.input_sha256,
                    error_message = NULL, started_at = now(), finished_at = NULL, updated_at = now()
                """, UUID.randomUUID(), organizationId, documentId, versionId, stepKey, provider, model, inputSha256);
    }

    @Override
    public void finishProcessingStep(UUID organizationId, UUID versionId, String stepKey, String status,
                                     String outputSha256, String errorMessage) {
        jdbc.update("""
                UPDATE kb.document_processing_step
                SET status = ?, progress = CASE WHEN ? = 'SUCCEEDED' THEN 100 ELSE progress END,
                    output_sha256 = ?, error_message = ?, finished_at = now(), updated_at = now()
                WHERE organization_id = ? AND document_version_id = ? AND step_key = ?
                """, status, status, outputSha256, errorMessage == null ? null : truncate(errorMessage), organizationId, versionId, stepKey);
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
        jdbc.update("UPDATE kb.document_version SET status = ?, error_message = ?, updated_at = now() WHERE id = ?",
                status, truncate(error), versionId);
        jdbc.update("UPDATE kb.document SET status = ?, parse_error = ?, updated_at = now() WHERE id = ?",
                status, truncate(error), documentId);
    }

    @Override
    public void updateAiStatus(UUID organizationId, UUID documentId, String aiStatus) {
        jdbc.update("""
                UPDATE kb.document SET ai_status = ?, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, aiStatus, organizationId, documentId);
    }

    @Override
    public List<SearchRow> fullTextSearch(UUID organizationId, String query, boolean aiOnly, int limit) {
        return fullTextSearch(organizationId, query, aiOnly, List.of(), List.of(), limit);
    }

    @Override
    public List<SearchRow> fullTextSearch(UUID organizationId, String query, boolean aiOnly, List<UUID> scopeIds,
                                          List<UUID> categoryIds, int limit) {
        var aiClause = aiOnly ? "AND EXISTS (SELECT 1 FROM kb.ai_usage_grant g WHERE g.publication_id = p.id AND g.status = 'APPROVED')" : "";
        var sql = """
                SELECT c.id, c.document_id, c.document_version_id,
                       coalesce(p.metadata_snapshot_jsonb->>'title', d.title) AS title, v.original_name,
                       c.page_no, c.section, c.content,
                       GREATEST(ts_rank_cd(c.search_vector, plainto_tsquery('simple', ?)), 0.4) AS score
                FROM kb.document_chunk c
                JOIN kb.document d ON d.id = c.document_id
                JOIN kb.document_version v ON v.id = c.document_version_id
                JOIN ops.file_object f ON f.id = v.file_object_id AND f.organization_id = d.organization_id AND f.status <> 'DELETED'
                JOIN kb.publication p ON p.id = d.current_publication_id
                    AND p.document_version_id = c.document_version_id AND p.parse_run_id = c.parse_run_id
                    AND p.status = 'CURRENT'
                WHERE d.organization_id = ? AND d.lifecycle_status = 'ACTIVE'
                """ + aiClause + """
                """ + categoryClause(categoryIds) + """
                  AND (c.search_vector @@ plainto_tsquery('simple', ?)
                       OR c.content ILIKE '%' || ? || '%')
                """ + scopeClause(scopeIds, "d.id", "c.document_version_id") + " ORDER BY score DESC, c.created_at DESC LIMIT ?";
        var args = new java.util.ArrayList<Object>();
        args.add(query); args.add(organizationId);
        if (categoryIds != null) args.addAll(categoryIds);
        args.add(query); args.add(query);
        if (scopeIds != null) args.addAll(scopeIds);
        args.add(limit);
        return jdbc.query(sql, this::mapSearch, args.toArray());
    }

    @Override
    public List<SearchRow> bm25Search(UUID organizationId, List<String> terms, boolean aiOnly, List<UUID> scopeIds,
                                      List<UUID> categoryIds, int limit) {
        if (terms == null || terms.isEmpty()) return List.of();
        var scope = scopeClause(scopeIds, "d.id", "c.document_version_id");
        var aiClause = aiOnly ? "AND EXISTS (SELECT 1 FROM kb.ai_usage_grant g WHERE g.publication_id = p.id AND g.status = 'APPROVED')" : "";
        var sql = """
                WITH query_terms AS (SELECT unnest(?::text[]) AS term), ranked AS (
                    SELECT c.id, c.document_id, c.document_version_id,
                           coalesce(p.metadata_snapshot_jsonb->>'title', d.title) AS title, v.original_name,
                           c.page_no, c.section, c.content,
                           sum(
                               ln(((s.document_count - s.document_frequency + 0.5) / (s.document_frequency + 0.5)) + 1)
                               * ((t.term_frequency * 2.2) /
                                  (t.term_frequency + 1.2 * (1 - 0.75 + 0.75 * c.token_length /
                                  nullif(s.average_document_length, 0))))
                           ) AS score
                    FROM kb.chunk_term t
                    JOIN query_terms q ON q.term = t.term
                    JOIN kb.term_stat s ON s.organization_id = ? AND s.term = t.term
                    JOIN kb.document_chunk c ON c.id = t.chunk_id
                    JOIN kb.document d ON d.id = c.document_id
                    JOIN kb.document_version v ON v.id = c.document_version_id
                    JOIN ops.file_object f ON f.id = v.file_object_id AND f.organization_id = d.organization_id AND f.status <> 'DELETED'
                    JOIN kb.publication p ON p.id = d.current_publication_id
                        AND p.document_version_id = c.document_version_id AND p.parse_run_id = c.parse_run_id
                        AND p.status = 'CURRENT'
                    WHERE d.organization_id = ? AND d.lifecycle_status = 'ACTIVE'
                """ + aiClause + categoryClause(categoryIds) + scope + """
                    GROUP BY c.id, c.document_id, c.document_version_id,
                             coalesce(p.metadata_snapshot_jsonb->>'title', d.title), v.original_name,
                             c.page_no, c.section, c.content
                )
                SELECT id, document_id, document_version_id, title, original_name, page_no, section, content, score
                FROM ranked ORDER BY score DESC LIMIT ?
                """;
        var args = new java.util.ArrayList<Object>();
        args.add(terms.toArray(String[]::new));
        args.add(organizationId);
        args.add(organizationId);
        if (categoryIds != null) args.addAll(categoryIds);
        if (scopeIds != null) args.addAll(scopeIds);
        args.add(limit);
        return jdbc.query(sql, this::mapSearch, args.toArray());
    }

    @Override
    public List<SearchRow> vectorSearch(UUID organizationId, String vector, boolean aiOnly, int limit) {
        return vectorSearch(organizationId, vector, aiOnly, List.of(), List.of(), limit, 0);
    }

    @Override
    public List<SearchRow> vectorSearch(UUID organizationId, String vector, boolean aiOnly, List<UUID> scopeIds,
                                        List<UUID> categoryIds, int limit) {
        return vectorSearch(organizationId, vector, aiOnly, scopeIds, categoryIds, limit, 0);
    }

    @Override
    public List<SearchRow> vectorSearch(UUID organizationId, String vector, boolean aiOnly, List<UUID> scopeIds,
                                        List<UUID> categoryIds, int limit, int dimension) {
        var aiClause = aiOnly ? "AND EXISTS (SELECT 1 FROM kb.ai_usage_grant g WHERE g.publication_id = p.id AND g.status = 'APPROVED')" : "";
        var dimensionClause = dimension > 0 ? " AND vector_dims(c.embedding) = ?\n" : "";
        var sql = """
                SELECT c.id, c.document_id, c.document_version_id,
                       coalesce(p.metadata_snapshot_jsonb->>'title', d.title) AS title, v.original_name,
                       c.page_no, c.section, c.content,
                       (1 - (c.embedding <=> CAST(? AS vector))) AS score
                FROM kb.document_chunk c
                JOIN kb.document d ON d.id = c.document_id
                JOIN kb.document_version v ON v.id = c.document_version_id
                JOIN ops.file_object f ON f.id = v.file_object_id AND f.organization_id = d.organization_id AND f.status <> 'DELETED'
                JOIN kb.publication p ON p.id = d.current_publication_id
                    AND p.document_version_id = c.document_version_id AND p.parse_run_id = c.parse_run_id
                    AND p.status = 'CURRENT'
                WHERE d.organization_id = ? AND d.lifecycle_status = 'ACTIVE'
                """ + aiClause + categoryClause(categoryIds) + " AND c.embedding IS NOT NULL\n"
                + dimensionClause
                + scopeClause(scopeIds, "d.id", "c.document_version_id")
                + "ORDER BY c.embedding <=> CAST(? AS vector) LIMIT ?";
        var args = new java.util.ArrayList<Object>();
        args.add(vector); args.add(organizationId);
        if (categoryIds != null) args.addAll(categoryIds);
        if (dimension > 0) args.add(dimension);
        if (scopeIds != null) args.addAll(scopeIds);
        args.add(vector); args.add(limit);
        return jdbc.query(sql, this::mapSearch, args.toArray());
    }

    private String documentQuery(String where) {
        return """
                SELECT d.id, d.organization_id, d.title, d.document_type, d.status, d.scan_status,
                       d.ai_status, d.current_version_no, v.id AS current_version_id,
                       v.original_name, v.content_type, v.size_bytes, v.sha256,
                       d.parse_error, d.created_at, d.updated_at, d.library_scope,
                       d.category_id, c.name AS category_name, d.lifecycle_status,
                       v.review_status, v.review_revision, d.current_publication_id,
                       p.publication_no AS current_publication_no
                FROM kb.document d
                JOIN kb.document_version v ON v.document_id = d.id AND v.version_no = d.current_version_no
                LEFT JOIN kb.document_category c ON c.id = d.category_id
                LEFT JOIN kb.publication p ON p.id = d.current_publication_id AND p.status = 'CURRENT'
                """ + where;
    }

    private DocumentRow mapDocument(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DocumentRow(
                rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class), rs.getString("title"),
                rs.getString("document_type"), rs.getString("status"), rs.getString("scan_status"),
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
                rs.getString("content"), rs.getDouble("score")
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

    private String scopeClause(List<UUID> scopeIds, String documentColumn, String versionColumn) {
        if (scopeIds == null || scopeIds.isEmpty()) return "";
        var placeholders = String.join(",", java.util.Collections.nCopies(scopeIds.size(), "?"));
        return " AND EXISTS (SELECT 1 FROM ai.ai_scope_resource sr WHERE sr.scope_id IN (" + placeholders + ")"
                + " AND ((sr.resource_type = 'KNOWLEDGE_DOCUMENT' AND sr.resource_id = " + documentColumn + ")"
                + " OR (sr.resource_type = 'KNOWLEDGE_VERSION' AND sr.resource_id = " + versionColumn + ")))\n";
    }

    private String categoryClause(List<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) return "";
        var placeholders = String.join(",", java.util.Collections.nCopies(categoryIds.size(), "?"));
        return " AND coalesce(nullif(p.metadata_snapshot_jsonb->>'categoryId', '')::uuid,"
                + " nullif(p.metadata_snapshot_jsonb->>'category_id', '')::uuid, d.category_id) IN ("
                + placeholders + ")\n";
    }

    private String truncate(String value) {
        if (value == null) return "处理失败";
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
