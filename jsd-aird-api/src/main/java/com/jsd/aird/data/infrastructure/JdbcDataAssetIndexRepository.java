package com.jsd.aird.data.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.api.DataAssetSearchFacade;
import com.jsd.aird.data.application.port.DataAssetIndexRepository;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade;
import org.springframework.beans.factory.ObjectProvider;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDataAssetIndexRepository implements DataAssetIndexRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<KnowledgeEmbeddingFacade> embeddings;

    public JdbcDataAssetIndexRepository(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                        ObjectProvider<KnowledgeEmbeddingFacade> embeddings) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.embeddings = embeddings;
    }

    @Override
    public List<DataAssetSearchFacade.DataHit> search(UUID organizationId, String query, List<UUID> scopeIds, int limit) {
        return search(organizationId, query, scopeIds, List.of(), limit);
    }

    @Override
    public List<DataAssetSearchFacade.DataHit> search(UUID organizationId, String query, List<UUID> scopeIds,
                                                      List<UUID> categoryIds, int limit) {
        var provider = embeddings.getIfAvailable();
        var vector = provider == null ? null : provider.embedVector(query).orElse(null);
        return searchHybrid(organizationId, query, vector, vectorDimension(vector), scopeIds, categoryIds, limit);
    }

    @Override
    public List<DataAssetSearchFacade.DataHit> searchHybrid(UUID organizationId, String query, String queryVector,
                                                            int vectorDimension, List<UUID> scopeIds,
                                                            List<UUID> categoryIds, int limit) {
        var scopeClause = scopeClause(scopeIds);
        var categoryClause = categoryClause(categoryIds);
        var vectorEnabled = queryVector != null && !queryVector.isBlank() && vectorDimension > 0;
        var vectorScore = vectorEnabled
                ? "CASE WHEN i.embedding IS NOT NULL AND vector_dims(i.embedding) = ? "
                + "THEN GREATEST(0, 1 - (i.embedding <=> CAST(? AS vector))) ELSE 0 END"
                : "0";
        var sql = """
                SELECT i.id, i.scope_id, i.asset_id, i.revision_id, i.row_number, i.field_code,
                       a.display_name, i.content,
                       (0.58 * GREATEST(ts_rank_cd(i.search_vector, plainto_tsquery('simple', ?)), 0)
                        + 0.12 * CASE WHEN i.content ILIKE '%%' || ? || '%%' THEN 1 ELSE 0 END
                       + 0.30 * (%s)) AS score,
                       'data-asset/' || i.asset_id || '/revision/' || i.revision_id
                           || coalesce('/record/' || nullif(i.source_jsonb->>'recordId', ''), '')
                           || coalesce('/field/' || i.field_code, '')
                           || coalesce('/sheet/' || nullif(i.source_jsonb->>'sheetName', ''), '')
                           || coalesce('/cell/' || nullif(i.source_jsonb->>'cellAddress', ''), '') AS source_locator
                FROM ai.data_asset_index_entry i
                JOIN data.data_asset a ON a.id = i.asset_id
                JOIN data.data_asset_revision r ON r.id = i.revision_id
                WHERE i.organization_id = ?
                  %s
                  AND a.status = 'ACTIVE'
                  AND r.publication_status = 'PUBLISHED'
                  AND (
                      i.search_vector @@ plainto_tsquery('simple', ?)
                      OR i.content ILIKE '%%' || ? || '%%'
                      OR EXISTS (
                          SELECT 1
                          FROM regexp_split_to_table(?, '\\s+') AS token
                          WHERE length(token) >= 2
                            AND i.content ILIKE '%%' || token || '%%'
                      )
                      OR (%s)
                  )
                  %s
                ORDER BY score DESC, i.created_at DESC LIMIT ?
                """.formatted(vectorScore, categoryClause,
                vectorEnabled ? "i.embedding IS NOT NULL AND vector_dims(i.embedding) = ?" : "false",
                scopeClause);
        var args = new ArrayList<Object>();
        args.add(query);
        args.add(query);
        if (vectorEnabled) { args.add(vectorDimension); args.add(queryVector); }
        args.add(organizationId);
        if (categoryIds != null) args.addAll(categoryIds);
        args.add(query);
        args.add(query);
        args.add(query);
        if (vectorEnabled) args.add(vectorDimension);
        if (scopeIds != null) args.addAll(scopeIds);
        args.add(limit);
        return jdbc.query(sql, (rs, rowNum) -> new DataAssetSearchFacade.DataHit(
                rs.getObject("id", UUID.class), rs.getObject("scope_id", UUID.class),
                rs.getObject("asset_id", UUID.class), rs.getObject("revision_id", UUID.class),
                (Integer) rs.getObject("row_number"), rs.getString("field_code"),
                rs.getString("display_name"), rs.getString("content"), rs.getDouble("score"),
                rs.getString("source_locator")), args.toArray());
    }

    @Override
    @Transactional
    public int indexPublished(UUID organizationId, List<UUID> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) return 0;
        int indexed = 0;
        for (var assetId : assetIds) {
            var asset = jdbc.query("""
                    SELECT id, display_name, current_revision_id
                    FROM data.data_asset
                    WHERE organization_id = ? AND id = ? AND status = 'ACTIVE'
                    """, (rs, row) -> new AssetRow(rs.getObject("id", UUID.class), rs.getString("display_name"),
                    rs.getObject("current_revision_id", UUID.class)), organizationId, assetId).stream().findFirst();
            if (asset.isEmpty() || asset.get().revisionId() == null) continue;
            var row = asset.get();
            var scopeId = UUID.nameUUIDFromBytes((organizationId + ":DATA_ASSET:" + row.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("""
                    INSERT INTO ai.ai_scope (id, organization_id, scope_type, external_id, name, status, metadata_jsonb)
                    VALUES (?, ?, 'DATA_ASSET', ?, ?, 'ACTIVE', '{}'::jsonb)
                    ON CONFLICT (organization_id, scope_type, external_id)
                    DO UPDATE SET name = EXCLUDED.name, status = 'ACTIVE', updated_at = now()
                    """, scopeId, organizationId, row.id().toString(),
                    row.name() == null || row.name().isBlank() ? row.id().toString() : row.name());
            jdbc.update("""
                    INSERT INTO ai.ai_scope_resource (scope_id, resource_type, resource_id, relation_type)
                    VALUES (?, 'DATA_ASSET', ?, 'IN_SCOPE')
                    ON CONFLICT DO NOTHING
                    """, scopeId, row.id());
            jdbc.update("DELETE FROM ai.data_asset_index_entry WHERE organization_id = ? AND asset_id = ?",
                    organizationId, row.id());
            indexed += jdbc.update("""
                    INSERT INTO ai.data_asset_index_entry (
                        id, organization_id, scope_id, asset_id, revision_id, row_number, field_code,
                        content, source_jsonb, token_length
                    )
                    SELECT gen_random_uuid(), a.organization_id, ?, a.id, r.id,
                           coalesce(sa.row_number, dr.source_row_number),
                           v.field_code || coalesce(':' || v.data_path, ''),
                           concat_ws(E'\n', '资产：' || coalesce(a.display_name, a.asset_key),
                               '记录：' || dr.record_key,
                               '字段：' || coalesce(v.label_path, v.data_path, v.field_code),
                               CASE WHEN dr.effective_data_jsonb->'dimensions' IS NOT NULL
                                    THEN '维度：' || (dr.effective_data_jsonb->'dimensions')::text END,
                               CASE WHEN sa.cell_address IS NOT NULL
                                    THEN '来源：' || coalesce(sa.sheet_name, sa.sheet_id, '') || '!' || sa.cell_address END,
                               '值：' || coalesce(v.value_text, v.value_jsonb::text)
                                   || coalesce(' ' || v.normalized_unit, '')),
                           jsonb_strip_nulls(jsonb_build_object(
                               'templateVersionId', r.template_version_id,
                               'recordId', dr.id, 'recordKey', dr.record_key,
                               'labelPath', v.label_path, 'dataPath', v.data_path, 'value', v.value_jsonb,
                               'bindingId', v.binding_id, 'valuePath', v.value_path,
                               'valueSource', v.value_source, 'calculationStatus', v.calculation_status,
                               'sourceAnchorId', v.source_anchor_id, 'sourceFileId', sa.file_id,
                               'sheetId', sa.sheet_id, 'sheetName', sa.sheet_name,
                               'rowNumber', sa.row_number, 'columnName', sa.column_name,
                               'cellAddress', sa.cell_address)),
                           length(concat_ws(' ', a.display_name, v.data_path, v.value_text, v.normalized_unit))
                    FROM data.data_value v
                    JOIN data.data_record dr ON dr.id = v.record_id
                    JOIN data.data_asset_revision r ON r.id = dr.revision_id
                    JOIN data.data_asset a ON a.id = r.asset_id
                    LEFT JOIN data.source_anchor sa ON sa.id = v.source_anchor_id
                    WHERE a.organization_id = ? AND a.id = ? AND a.status = 'ACTIVE'
                      AND r.id = a.current_revision_id AND r.publication_status = 'PUBLISHED'
                      AND v.rag_eligible
                      AND (v.value_source <> 'FORMULA' OR v.calculation_status = 'VALID')
                    ON CONFLICT (revision_id, row_number, field_code) DO UPDATE
                    SET content = EXCLUDED.content, source_jsonb = EXCLUDED.source_jsonb,
                        token_length = EXCLUDED.token_length, created_at = now()
                    """, scopeId, organizationId, row.id());
            indexed += jdbc.update("""
                    INSERT INTO ai.data_asset_index_entry (
                        id, organization_id, scope_id, asset_id, revision_id, row_number, field_code,
                        content, source_jsonb, token_length
                    )
                    SELECT gen_random_uuid(), a.organization_id, ?, a.id, r.id, anchor.row_number, fields.key,
                           coalesce(fields.value->>'normalizedValue', fields.value->>'rawValue', fields.value::text),
                           fields.value, length(coalesce(fields.value->>'normalizedValue', fields.value::text))
                    FROM data.data_asset a
                    JOIN data.data_asset_revision r ON r.id = a.current_revision_id
                    CROSS JOIN LATERAL jsonb_each(r.normalized_data_jsonb) fields
                    LEFT JOIN LATERAL (
                        SELECT s.row_number
                        FROM data.source_anchor s
                        WHERE s.asset_revision_id = r.id AND s.field_code = fields.key
                        ORDER BY s.row_number NULLS LAST
                        LIMIT 1
                    ) anchor ON true
                    WHERE a.organization_id = ? AND a.id = ? AND a.status = 'ACTIVE'
                      AND r.publication_status = 'PUBLISHED'
                      AND NOT EXISTS (
                          SELECT 1 FROM data.data_record dr WHERE dr.revision_id = r.id
                      )
                    ON CONFLICT (revision_id, row_number, field_code) DO UPDATE
                    SET content = EXCLUDED.content, source_jsonb = EXCLUDED.source_jsonb,
                        token_length = EXCLUDED.token_length, created_at = now()
                    """, scopeId, organizationId, row.id());
            indexEmbeddings(organizationId, row.id());
        }
        return indexed;
    }

    private void indexEmbeddings(UUID organizationId, UUID assetId) {
        var provider = embeddings.getIfAvailable();
        if (provider == null) return;
        var entries = jdbc.query("""
                SELECT i.id, i.content
                FROM ai.data_asset_index_entry i
                JOIN data.data_asset_revision r ON r.id = i.revision_id
                JOIN data.data_asset a ON a.id = r.asset_id
                WHERE i.organization_id = ? AND a.id = ?
                  AND r.id = a.current_revision_id AND r.publication_status = 'PUBLISHED'
                  AND i.embedding IS NULL
                """, (rs, rowNum) -> new IndexEntry(rs.getObject("id", UUID.class), rs.getString("content")),
                organizationId, assetId);
        for (var entry : entries) {
            try {
                provider.embedVector(entry.content()).ifPresent(vector -> jdbc.update(
                        "UPDATE ai.data_asset_index_entry SET embedding = CAST(? AS vector) WHERE id = ? AND organization_id = ?",
                        vector, entry.id(), organizationId));
            } catch (RuntimeException ignored) {
                // A vector provider or dimension mismatch must not make lexical data search unavailable.
            }
        }
    }

    private String scopeClause(List<UUID> scopeIds) {
        if (scopeIds == null || scopeIds.isEmpty()) return "";
        return " AND i.scope_id IN (" + String.join(",", java.util.Collections.nCopies(scopeIds.size(), "?")) + ")\n";
    }

    private String categoryClause(List<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) return "";
        return " AND a.category_id IN (" + String.join(",", java.util.Collections.nCopies(categoryIds.size(), "?")) + ")\n";
    }

    private record AssetRow(UUID id, String name, UUID revisionId) {
    }

    private int vectorDimension(String vector) {
        if (vector == null || vector.isBlank()) return 0;
        var value = vector.strip();
        if (!value.startsWith("[") || !value.endsWith("]")) return 0;
        var body = value.substring(1, value.length() - 1).strip();
        return body.isBlank() ? 0 : body.split(",").length;
    }

    private record IndexEntry(UUID id, String content) {
    }
}
