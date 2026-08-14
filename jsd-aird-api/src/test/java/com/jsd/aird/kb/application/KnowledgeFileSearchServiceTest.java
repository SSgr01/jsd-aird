package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import org.junit.jupiter.api.Test;

class KnowledgeFileSearchServiceTest {

    @Test
    void groupsSeveralChunkHitsIntoCurrentPublishedSourceFile() {
        var documents = mock(KnowledgeRepository.class);
        var governance = mock(KnowledgeGovernanceRepository.class);
        var organizationId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var publicationId = UUID.randomUUID();
        var fileId = UUID.randomUUID();
        var first = new KnowledgeRepository.SearchRow(UUID.randomUUID(), documentId, versionId,
                "已发布标题", "coa.xlsx", null, "Sheet1", "批号 LOT-1", 2.0);
        var second = new KnowledgeRepository.SearchRow(UUID.randomUUID(), documentId, versionId,
                "已发布标题", "coa.xlsx", null, "Sheet1", "固含量 42%", 1.5);
        when(documents.bm25Search(any(), any(), anyBoolean(), any(), any(), anyInt()))
                .thenReturn(List.of(first, second));
        when(documents.fullTextSearch(any(), any(), anyBoolean(), any(), any(), anyInt()))
                .thenReturn(List.of(second));
        when(documents.findVersion(organizationId, versionId)).thenReturn(Optional.of(
                new KnowledgeRepository.VersionRow(versionId, documentId, 2, fileId, "coa.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 1024, "a".repeat(64),
                        "READY", "poi", null, "PUBLISHED", 3)));
        when(documents.findDocument(organizationId, documentId)).thenReturn(Optional.of(
                new KnowledgeRepository.DocumentRow(documentId, organizationId, "草稿标题", "READY",
                        "SAFE", "PENDING", 2, versionId, "coa.xlsx", "application/octet-stream", 1024,
                        "a".repeat(64), null, Instant.now(), Instant.now(), "INTERNAL", UUID.randomUUID(),
                        "COA", "ACTIVE", "PUBLISHED", 3, publicationId, 2)));
        when(governance.currentPublication(organizationId, documentId)).thenReturn(Optional.of(
                new KnowledgeGovernanceRepository.PublicationRow(publicationId, documentId, versionId,
                        UUID.randomUUID(), UUID.randomUUID(), 2, "CURRENT", "PENDING", Instant.now())));
        when(governance.publicationTags(organizationId, publicationId)).thenReturn(List.of("COA", "放行"));
        when(documents.findChunkAnchor(any(), any())).thenReturn(Optional.empty());

        var files = new KnowledgeFileSearchService(documents, governance)
                .searchFiles(organizationId, "LOT-1", List.of(), List.of(), 20);

        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.fileObjectId()).isEqualTo(fileId);
            assertThat(file.title()).isEqualTo("已发布标题");
            assertThat(file.hits()).hasSize(2);
            assertThat(file.tags()).containsExactly("COA", "放行");
        });
    }
}
