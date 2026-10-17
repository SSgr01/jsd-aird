package com.jsd.aird.ai.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.application.operation.OperationManual.Chunk;
import com.jsd.aird.ai.application.port.OperationManualIndexRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcOperationManualIndexRepository implements OperationManualIndexRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;

    public JdbcOperationManualIndexRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.mapper = mapper;
    }

    @Override
    public boolean ready(IndexKey key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM ai.operation_manual_index
                WHERE manual_version = ? AND embedding_model = ? AND embedding_dimension = ? AND status = 'READY')
                """, Boolean.class, key.manualVersion(), key.model(), key.dimension()));
    }

    @Override
    public boolean synchronize(IndexKey key, List<Chunk> chunks, Vectorizer vectorizer) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var lockName = "operation-manual:" + key.manualVersion() + ":" + key.model() + ":" + key.dimension();
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, lockName))) return false;
            if (ready(key)) return true;
            jdbc.update("""
                    INSERT INTO ai.operation_manual_index(manual_version, embedding_model, embedding_dimension, status)
                    VALUES (?, ?, ?, 'BUILDING')
                    ON CONFLICT (manual_version, embedding_model, embedding_dimension)
                    DO UPDATE SET status = 'BUILDING', error_message = NULL, updated_at = now()
                    """, key.manualVersion(), key.model(), key.dimension());
            var reused = new HashMap<String, String>();
            jdbc.query("""
                    SELECT DISTINCT ON (content_hash) content_hash, embedding::text AS vector
                    FROM ai.operation_manual_chunk WHERE embedding_model = ? AND embedding_dimension = ?
                    ORDER BY content_hash, updated_at DESC
                    """, row -> { reused.put(row.getString("content_hash"), row.getString("vector")); },
                    key.model(), key.dimension());
            var missing = chunks.stream().filter(chunk -> !reused.containsKey(chunk.contentHash())).toList();
            for (int offset = 0; offset < missing.size(); offset += 16) {
                var batch = missing.subList(offset, Math.min(offset + 16, missing.size()));
                var vectors = vectorizer.embed(batch.stream().map(Chunk::embeddingText).toList());
                if (vectors == null || vectors.size() != batch.size()) throw new IllegalStateException("手册向量批次不完整");
                for (int i = 0; i < batch.size(); i++) {
                    var vector = vectors.get(i).orElseThrow(() -> new IllegalStateException("手册向量缺失"));
                    validateVector(vector, key.dimension());
                    reused.put(batch.get(i).contentHash(), vector);
                }
            }
            jdbc.update("DELETE FROM ai.operation_manual_chunk WHERE manual_version = ? AND embedding_model = ? AND embedding_dimension = ?",
                    key.manualVersion(), key.model(), key.dimension());
            jdbc.batchUpdate("""
                    INSERT INTO ai.operation_manual_chunk(manual_version, embedding_model, embedding_dimension,
                        chunk_id, section_id, content, content_hash, embedding)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector))
                    """, chunks, 50, (statement, chunk) -> {
                statement.setString(1, key.manualVersion());
                statement.setString(2, key.model());
                statement.setInt(3, key.dimension());
                statement.setString(4, chunk.id());
                statement.setString(5, chunk.sectionId());
                statement.setString(6, chunk.content());
                statement.setString(7, chunk.contentHash());
                statement.setString(8, reused.get(chunk.contentHash()));
            });
            jdbc.update("""
                    UPDATE ai.operation_manual_index SET status = 'READY', chunk_count = ?, error_message = NULL, updated_at = now()
                    WHERE manual_version = ? AND embedding_model = ? AND embedding_dimension = ?
                    """, chunks.size(), key.manualVersion(), key.model(), key.dimension());
            return true;
        }));
    }

    private void validateVector(String vector, int dimension) {
        try {
            var values = mapper.readTree(vector);
            if (!values.isArray() || values.size() != dimension) throw new IllegalArgumentException("手册向量维度不匹配");
            boolean nonzero = false;
            for (var value : values) {
                if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw new IllegalArgumentException("手册向量包含无效数值");
                nonzero |= value.doubleValue() != 0;
            }
            if (!nonzero) throw new IllegalArgumentException("手册向量不能为零向量");
        } catch (java.io.IOException exception) { throw new IllegalArgumentException("手册向量格式无效", exception); }
    }

    @Override
    public void failed(IndexKey key, String reason) {
        jdbc.update("""
                INSERT INTO ai.operation_manual_index(manual_version, embedding_model, embedding_dimension, status, error_message)
                VALUES (?, ?, ?, 'FAILED', ?)
                ON CONFLICT (manual_version, embedding_model, embedding_dimension)
                DO UPDATE SET status = 'FAILED', error_message = EXCLUDED.error_message, updated_at = now()
                WHERE operation_manual_index.status <> 'READY'
                """, key.manualVersion(), key.model(), key.dimension(), reason);
    }

    @Override
    public List<VectorHit> search(IndexKey key, String vector, Set<String> allowedSections, int limit) {
        if (allowedSections.isEmpty()) return List.of();
        validateVector(vector, key.dimension());
        var placeholders = String.join(",", java.util.Collections.nCopies(allowedSections.size(), "?"));
        var arguments = new ArrayList<Object>();
        arguments.add(vector);
        arguments.add(key.manualVersion());
        arguments.add(key.model());
        arguments.add(key.dimension());
        arguments.addAll(allowedSections);
        arguments.add(vector);
        arguments.add(Math.min(20, Math.max(1, limit)));
        return jdbc.query("""
                SELECT c.chunk_id, 1 - (c.embedding <=> CAST(? AS vector)) AS score
                FROM ai.operation_manual_chunk c
                JOIN ai.operation_manual_index i USING (manual_version, embedding_model, embedding_dimension)
                WHERE c.manual_version = ? AND c.embedding_model = ? AND c.embedding_dimension = ? AND i.status = 'READY'
                AND c.section_id IN (
                """ + placeholders + ") ORDER BY c.embedding <=> CAST(? AS vector), c.chunk_id LIMIT ?",
                (row, number) -> new VectorHit(row.getString("chunk_id"), row.getDouble("score")), arguments.toArray());
    }
}
