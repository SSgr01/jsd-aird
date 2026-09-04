package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.application.StructuredDocumentCodec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcKnowledgeGovernanceRepositoryTest {

    @Test
    void reviewQueueUsesLatestRevisionAndExcludesBuildingItems() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new JdbcKnowledgeGovernanceRepository(
                jdbc, mock(ObjectMapper.class), mock(StructuredDocumentCodec.class));

        repository.reviewQueue(UUID.randomUUID(), null, 100);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class));
        assertThat(sql.getValue()).contains(
                "rr.status AS review_revision_status",
                "ORDER BY x.revision_no DESC LIMIT 1",
                "rr.status IS DISTINCT FROM 'BUILDING'");
    }

    @Test
    void revokeChecksExistingGrantWithoutRequiringActiveLifecycle() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(Boolean.class), any(Object[].class)))
                .thenReturn(Boolean.TRUE);
        var repository = new JdbcKnowledgeGovernanceRepository(
                jdbc, mock(ObjectMapper.class), mock(StructuredDocumentCodec.class));

        var organizationId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        repository.updateAiUsage(organizationId, UUID.randomUUID(), documentId, "REVOKE", "disabled by operator");

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(Boolean.class), any(Object[].class));
        assertThat(sql.getValue()).contains("JOIN kb.document_ai_grant")
                .doesNotContain("lifecycle_status = 'ACTIVE'")
                .doesNotContain("current_publication_id IS NOT NULL");
    }
}
