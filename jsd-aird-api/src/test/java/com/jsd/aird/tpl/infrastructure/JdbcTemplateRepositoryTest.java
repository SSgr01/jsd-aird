package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.tpl.domain.TemplateFormat;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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
