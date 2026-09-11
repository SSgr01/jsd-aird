package com.jsd.aird.data.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcDataSourceFileSearchRepositoryTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void searchesProjectedEffectiveValuesWithFieldMetadataAndPermissionScope() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var categoryId = UUID.randomUUID();
        var permittedCategoryId = UUID.randomUUID();
        var scope = new DataSourceFileSearchFacade.AccessScope("CATEGORY", UUID.randomUUID(),
                Set.of(permittedCategoryId));

        new JdbcDataSourceFileSearchRepository(jdbc).searchSourceFiles(UUID.randomUUID(), "密度",
                List.of(categoryId), scope, 12);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("JOIN data.data_value v")
                .contains("v.rag_eligible = true")
                .contains("v.value_source <> 'FORMULA' OR v.calculation_status = 'VALID'")
                .contains("candidate.value_path = v.value_path")
                .contains("m.mapping_jsonb->>'dataPath' = v.value_path")
                .contains("meta.field_name")
                .contains("j.category_id IN (?)")
                .doesNotContain("staging_row", "raw_values_jsonb");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void returnsProjectedFieldValueAndItsCompleteSourceAnchor() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var importJobId = UUID.randomUUID();
        var fileId = UUID.randomUUID();
        var valueId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            var rs = mock(ResultSet.class);
            when(rs.getObject("import_job_id", UUID.class)).thenReturn(importJobId);
            when(rs.getObject("source_file_id", UUID.class)).thenReturn(fileId);
            when(rs.getString("source_file_name")).thenReturn("检测报告.xlsx");
            when(rs.getString("content_type")).thenReturn("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            when(rs.getLong("size_bytes")).thenReturn(100L);
            when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-09-10T10:00:00Z")));
            when(rs.getString("record_key")).thenReturn("REC-001");
            when(rs.getString("sheet_name")).thenReturn("Sheet1");
            when(rs.getObject("source_row_number")).thenReturn(12);
            when(rs.getString("column_name")).thenReturn("C");
            when(rs.getString("cell_address")).thenReturn("C12");
            when(rs.getObject("value_id", UUID.class)).thenReturn(valueId);
            when(rs.getString("field_code")).thenReturn("density");
            when(rs.getString("field_name")).thenReturn("密度");
            when(rs.getString("field_value")).thenReturn("1.08");
            when(rs.getString("normalized_unit")).thenReturn("g/cm³");
            when(rs.getString("value_type")).thenReturn("NUMBER");
            when(rs.getDouble("score")).thenReturn(3.0);
            return List.of(mapper.mapRow(rs, 0));
        });

        var result = new JdbcDataSourceFileSearchRepository(jdbc).searchSourceFiles(UUID.randomUUID(), "REC-001",
                List.of(UUID.randomUUID()), DataSourceFileSearchFacade.AccessScope.all(), 10);

        assertThat(result).singleElement().satisfies(file -> {
            assertThat(file.importJobId()).isEqualTo(importJobId);
            assertThat(file.fileObjectId()).isEqualTo(fileId);
            assertThat(file.hits()).singleElement().satisfies(hit -> {
                assertThat(hit.hitId()).isEqualTo(valueId);
                assertThat(hit.recordKey()).isEqualTo("REC-001");
                assertThat(hit.fieldCode()).isEqualTo("density");
                assertThat(hit.fieldName()).isEqualTo("密度");
                assertThat(hit.fieldValue()).isEqualTo("1.08");
                assertThat(hit.unit()).isEqualTo("g/cm³");
                assertThat(hit.sheetName()).isEqualTo("Sheet1");
                assertThat(hit.cellAddress()).isEqualTo("C12");
            });
        });
    }

    @Test
    void emptySelectedScopeCannotReachTheDatabase() {
        var jdbc = mock(JdbcTemplate.class);
        var scope = new DataSourceFileSearchFacade.AccessScope("SELECTED", null, Set.of());

        var result = new JdbcDataSourceFileSearchRepository(jdbc).searchSourceFiles(UUID.randomUUID(), "REC-001",
                List.of(UUID.randomUUID()), scope, 10);

        assertThat(result).isEmpty();
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }
}
