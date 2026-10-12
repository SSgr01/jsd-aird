package com.jsd.aird.data.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcDataCategoryRepositoryTest {

    @Test
    void categoryCountersAndFacetsOnlyCountDataCenterSources() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new JdbcDataCategoryRepository(jdbc);
        var organizationId = UUID.randomUUID();

        repository.list(organizationId);
        repository.facets(organizationId);
        repository.find(organizationId, UUID.randomUUID());

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(3)).query(sql.capture(),
                any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
        assertThat(String.join("\n", sql.getAllValues())).contains("j.source_owner = 'DATA_CENTER'");
        assertThat(sql.getAllValues().get(1)).contains(
                "total_source_count", "uncategorized_source_count", "WHERE organization_id = ? AND source_owner = 'DATA_CENTER'");
        assertThat(sql.getAllValues()).allSatisfy(statement -> assertThat(statement)
                .contains("organization_id = ?"));
    }
}
