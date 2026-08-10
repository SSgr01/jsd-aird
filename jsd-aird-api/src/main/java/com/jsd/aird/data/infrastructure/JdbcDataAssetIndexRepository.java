package com.jsd.aird.data.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.api.DataAssetSearchFacade;
import com.jsd.aird.data.application.port.DataAssetIndexRepository;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDataAssetIndexRepository implements DataAssetIndexRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcDataAssetIndexRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DataAssetSearchFacade.DataHit> search(UUID organizationId, String query, List<UUID> scopeIds, int limit) {
        return search(organizationId, query, scopeIds, List.of(), limit);
    }

    @Override
    public List<DataAssetSearchFacade.DataHit> search(UUID organizationId, String query, List<UUID> scopeIds,
                                                      List<UUID> categoryIds, int limit) {
        var scopeClause = scopeClause(scopeIds);
        var categoryClause = categoryClause(categoryIds);
        var sql = """
                SELECT i.id, i.scope_id, i.asset_id, i.revision_id, i.row_number, i.field_code,
                       a.display_name, i.content,
                       GREATEST(ts_rank_cd(i.search_vector, plainto_tsquery('simple', ?)), 0.2) AS score,
                       'data-asset/' || i.asset_id || '/revision/' || i.revision_id
                           || coalesce('/row/' || i.row_number, '') || coalesce('/field/' || i.field_code, '') AS source_locator
                FROM ai.data_asset_index_entry i
                JOIN data.data_asset a ON a.id = i.asset_id
                JOIN data.data_asset_revision r ON r.id = i.revision_id
                WHERE i.organization_id = ?
                  """ + categoryClause + """
                  AND a.status = 'ACTIVE'
                  AND r.publication_status = 'PUBLISHED'
                  AND (i.search_vector @@ plainto_tsquery('simple', ?) OR i.content ILIKE '%' || ? || '%')
                """ + scopeClause + " ORDER BY score DESC, i.created_at DESC LIMIT ?";
        var args = new ArrayList<Object>();
        args.add(query);
        args.add(organizationId);
        if (categoryIds != null) args.addAll(categoryIds);
        args.add(query);
        args.add(query);
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
                    ON CONFLICT (revision_id, row_number, field_code) DO UPDATE
                    SET content = EXCLUDED.content, source_jsonb = EXCLUDED.source_jsonb,
                        token_length = EXCLUDED.token_length, created_at = now()
                    """, scopeId, organizationId, row.id());
        }
        return indexed;
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
}
