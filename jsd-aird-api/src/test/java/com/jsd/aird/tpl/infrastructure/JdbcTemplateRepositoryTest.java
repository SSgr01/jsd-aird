package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.application.port.TemplateRepository;
import com.jsd.aird.tpl.domain.TemplateFormat;
import com.jsd.aird.tpl.domain.TemplateStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcTemplateRepositoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void writesJsonNullRepeatAxisAsSqlNullAndDefaultsRepeatingMappingsToRows() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var repository = new JdbcTemplateRepository(jdbcTemplate, objectMapper);
        var mappings = objectMapper.createArrayNode()
                .add(binding("scalar", "SCALAR", null, true))
                .add(binding("repeat", "REPEAT_REGION", null, false))
                .add(binding("column", "REPEAT_FIELD", "COLUMN", false));

        repository.replaceMappings(UUID.randomUUID(), TemplateFormat.XLSX, mappings);

        verify(jdbcTemplate).batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>argThat(batch -> {
            assertThat(batch).hasSize(3);
            assertThat(batch.get(0)[4]).isNull();
            assertThat(batch.get(0)[5]).isNull();
            assertThat(batch.get(0)[7]).isNull();
            assertThat(batch.get(0)[11]).isNull();
            assertThat(batch.get(1)[11]).isEqualTo("ROW");
            assertThat(batch.get(2)[11]).isEqualTo("COLUMN");
            return true;
        }));
    }

    @Test
    void rejectsAnInvalidRepeatAxisBeforeBatchInsert() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var repository = new JdbcTemplateRepository(jdbcTemplate, objectMapper);
        var mappings = objectMapper.createArrayNode()
                .add(binding("invalid", "REPEAT_REGION", "DIAGONAL", false));

        assertThatThrownBy(() -> repository.replaceMappings(UUID.randomUUID(), TemplateFormat.XLSX, mappings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeatAxis");
    }

    @Test
    void buildsFacetCountsFromTheSameNonCategoryFiltersAndIncludesEmptyCategories() {
        var organizationId = UUID.randomUUID();
        var creatorId = UUID.randomUUID();
        var categoryId = UUID.randomUUID();
        var updatedFrom = Instant.parse("2026-08-01T00:00:00Z");
        var updatedTo = Instant.parse("2026-08-13T23:59:59Z");

        try (var construction = mockConstruction(NamedParameterJdbcTemplate.class)) {
            var repository = new JdbcTemplateRepository(mock(JdbcTemplate.class), objectMapper);
            var namedJdbcTemplate = construction.constructed().get(0);
            when(namedJdbcTemplate.query(
                    anyString(),
                    any(MapSqlParameterSource.class),
                    org.mockito.ArgumentMatchers.<RowMapper<TemplateRepository.TemplateCategoryCount>>any()
            )).thenReturn(List.of(
                    new TemplateRepository.TemplateCategoryCount(categoryId, 5),
                    new TemplateRepository.TemplateCategoryCount(UUID.randomUUID(), 0),
                    new TemplateRepository.TemplateCategoryCount(null, 89)
            ));

            var summary = repository.findTemplateFacets(new TemplateRepository.TemplateFacetQuery(
                    organizationId, "word", TemplateFormat.DOCX, TemplateStatus.PUBLISHED,
                    creatorId, updatedFrom, updatedTo));

            assertThat(summary.totalCount()).isEqualTo(94);
            assertThat(summary.uncategorizedCount()).isEqualTo(89);
            assertThat(summary.categoryCounts()).hasSize(2)
                    .extracting(TemplateRepository.TemplateCategoryCount::count)
                    .containsExactly(5L, 0L);
            verify(namedJdbcTemplate).query(
                    org.mockito.ArgumentMatchers.argThat((String sql) ->
                            sql.contains("WHERE lt.version_id IS NOT NULL")
                                    && sql.contains("lower(lt.name) LIKE")
                                    && sql.contains("lt.format = :format")
                                    && sql.contains("lt.lifecycle_status = :status")
                                    && sql.contains("lt.created_by = :createdBy")
                                    && sql.contains("lt.effective_updated_at >= :updatedFrom")
                                    && sql.contains("lt.effective_updated_at <= :updatedTo")
                                    && sql.contains("LEFT JOIN category_counts")
                                    && !sql.contains(":categoryId")),
                    org.mockito.ArgumentMatchers.argThat((MapSqlParameterSource parameters) ->
                            organizationId.equals(parameters.getValue("organizationId"))
                                    && "%word%".equals(parameters.getValue("keywordPattern"))
                                    && "DOCX".equals(parameters.getValue("format"))
                                    && "PUBLISHED".equals(parameters.getValue("status"))
                                    && creatorId.equals(parameters.getValue("createdBy"))),
                    org.mockito.ArgumentMatchers.<RowMapper<TemplateRepository.TemplateCategoryCount>>any()
            );
        }
    }

    private ObjectNode binding(String id, String mappingKind, String repeatAxis, boolean nullableValues) {
        var binding = objectMapper.createObjectNode();
        binding.put("bindingId", id)
                .put("fieldId", UUID.randomUUID().toString())
                .put("dataPath", "/recognized/" + id)
                .put("role", "REPEAT_REGION".equals(mappingKind) ? "REPEAT_REGION" : "FIELD")
                .put("mappingKind", mappingKind)
                .put("locatorType", "CELL_RANGE");
        binding.set("locator", objectMapper.createObjectNode().put("address", "A1"));
        if (repeatAxis != null) binding.put("repeatAxis", repeatAxis);
        if (nullableValues) {
            binding.putNull("parentBindingId");
            binding.putNull("markerId");
            binding.putNull("fieldCode");
            binding.putNull("repeatAxis");
        }
        return binding;
    }
}
