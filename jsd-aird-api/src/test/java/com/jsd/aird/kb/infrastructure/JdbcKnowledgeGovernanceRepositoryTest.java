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
    void imageWithoutCoordinatesStoresEmptyArrayInsteadOfInvalidJson() {
        var jdbc = mock(JdbcTemplate.class);
        var json = new ObjectMapper();
        var repository = new JdbcKnowledgeGovernanceRepository(jdbc, json, mock(StructuredDocumentCodec.class));
        var anchor = json.createObjectNode()
                .put("resultFileId", UUID.randomUUID().toString())
                .put("assetFileId", UUID.randomUUID().toString())
                .put("resultEntryPath", "images/presentation.png")
                .put("page", 1);
        var diagnostics = json.createObjectNode();
        diagnostics.putArray("resultAssets");
        var source = new StructuredDocumentCodec.SourceNodeDraft(
                UUID.randomUUID(), 0, "image", "[图片]", anchor, json.createObjectNode());

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(repository, "persistResultAssets",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), diagnostics, java.util.List.of(source));

        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("UPDATE kb.document_result_asset");
        assertThat(((org.postgresql.util.PGobject) args.getValue()[1]).getValue()).isEqualTo("[]");
    }

    @Test
    void missingJsonNodeSerializesAsJsonNull() {
        var repository = new JdbcKnowledgeGovernanceRepository(
                mock(JdbcTemplate.class), new ObjectMapper(), mock(StructuredDocumentCodec.class));
        org.postgresql.util.PGobject value = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                repository, "json", com.fasterxml.jackson.databind.node.MissingNode.getInstance());
        assertThat(value.getValue()).isEqualTo("null");
    }

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
    void publishedContentDoesNotLoadTheUnusedSourceDocumentSnapshot() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(java.util.List.of());
        var repository = new JdbcKnowledgeGovernanceRepository(
                jdbc, mock(ObjectMapper.class), mock(StructuredDocumentCodec.class));

        repository.publishedContent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class));
        assertThat(sql.getValue())
                .contains("rr.confirmed_document_jsonb")
                .doesNotContain("source_document_jsonb")
                .doesNotContain("JOIN kb.document_parse_run");
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
