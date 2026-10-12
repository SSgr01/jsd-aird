package com.jsd.aird.data.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.data.application.port.DataCategoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDataCategoryRepository implements DataCategoryRepository {

    private final JdbcTemplate jdbc;

    public JdbcDataCategoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Category> list(UUID organizationId) {
        return jdbc.query("""
                SELECT c.id, c.name, c.description, c.sort_order, count(DISTINCT j.id) AS source_count
                FROM data.data_category c
                LEFT JOIN data.import_job j ON j.category_id = c.id AND j.organization_id = c.organization_id
                    AND j.source_owner = 'DATA_CENTER'
                WHERE c.organization_id = ?
                GROUP BY c.id, c.name, c.description, c.sort_order, c.created_at
                ORDER BY c.sort_order, c.created_at
                """, this::map, organizationId);
    }

    @Override
    public DataSourceFacets facets(UUID organizationId) {
        var rows = jdbc.query("""
                WITH totals AS (
                    SELECT count(*) AS total_source_count,
                           count(*) FILTER (WHERE category_id IS NULL) AS uncategorized_source_count
                    FROM data.import_job
                    WHERE organization_id = ? AND source_owner = 'DATA_CENTER'
                )
                SELECT totals.total_source_count, totals.uncategorized_source_count,
                       c.id AS category_id, c.name, c.description, c.sort_order, c.created_at,
                       count(DISTINCT j.id) AS source_count
                FROM totals
                LEFT JOIN data.data_category c ON c.organization_id = ?
                LEFT JOIN data.import_job j ON j.organization_id = ? AND j.source_owner = 'DATA_CENTER'
                    AND j.category_id = c.id
                GROUP BY totals.total_source_count, totals.uncategorized_source_count,
                         c.id, c.name, c.description, c.sort_order, c.created_at
                ORDER BY c.sort_order NULLS LAST, c.created_at NULLS LAST
                """, (rs, rowNum) -> new FacetRow(
                rs.getLong("total_source_count"), rs.getLong("uncategorized_source_count"),
                rs.getObject("category_id", UUID.class), rs.getString("name"), rs.getString("description"),
                (Integer) rs.getObject("sort_order"), rs.getLong("source_count")), organizationId, organizationId, organizationId);
        var categories = rows.stream().filter(row -> row.categoryId() != null)
                .map(row -> new Category(row.categoryId(), row.name(), row.description(), row.sortOrder(), row.sourceCount()))
                .toList();
        var totals = rows.isEmpty() ? new FacetRow(0, 0, null, null, null, null, 0) : rows.getFirst();
        return new DataSourceFacets(categories, totals.totalSourceCount(), totals.uncategorizedSourceCount());
    }

    @Override
    public Optional<Category> find(UUID organizationId, UUID categoryId) {
        return jdbc.query("""
                SELECT c.id, c.name, c.description, c.sort_order, count(DISTINCT j.id) AS source_count
                FROM data.data_category c
                LEFT JOIN data.import_job j ON j.category_id = c.id AND j.organization_id = c.organization_id
                    AND j.source_owner = 'DATA_CENTER'
                WHERE c.organization_id = ? AND c.id = ?
                GROUP BY c.id, c.name, c.description, c.sort_order, c.created_at
                """, this::map, organizationId, categoryId).stream().findFirst();
    }

    @Override
    public Category create(UUID organizationId, UUID actorId, String name, String description) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO data.data_category (id, organization_id, name, description, sort_order, created_by)
                VALUES (?, ?, ?, ?, coalesce((SELECT max(sort_order) + 1 FROM data.data_category WHERE organization_id = ?), 1), ?)
                """, id, organizationId, name, description, organizationId, actorId);
        return find(organizationId, id).orElseThrow();
    }

    @Override
    public Category rename(UUID organizationId, UUID categoryId, String name, String description) {
        jdbc.update("UPDATE data.data_category SET name = ?, description = ?, updated_at = now() WHERE organization_id = ? AND id = ?",
                name, description, organizationId, categoryId);
        return find(organizationId, categoryId).orElseThrow();
    }

    @Override
    @Transactional
    public void delete(UUID organizationId, UUID categoryId, UUID replacementCategoryId) {
        if (replacementCategoryId != null) {
            jdbc.update("UPDATE data.import_job SET category_id = ? WHERE organization_id = ? AND category_id = ?",
                    replacementCategoryId, organizationId, categoryId);
        } else if (jdbc.queryForObject("SELECT count(*) FROM data.import_job WHERE organization_id = ? AND category_id = ?",
                Long.class, organizationId, categoryId) > 0) {
            throw new IllegalStateException("分类仍有来源文件，请先选择替代分类");
        }
        jdbc.update("DELETE FROM data.data_category WHERE organization_id = ? AND id = ?", organizationId, categoryId);
    }

    private Category map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Category(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("description"), rs.getInt("sort_order"), rs.getLong("source_count"));
    }

    private record FacetRow(long totalSourceCount, long uncategorizedSourceCount, UUID categoryId,
                            String name, String description, Integer sortOrder, long sourceCount) { }
}
