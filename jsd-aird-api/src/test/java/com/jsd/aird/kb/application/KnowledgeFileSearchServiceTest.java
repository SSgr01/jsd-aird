package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.kb.application.port.KnowledgeGovernanceRepository;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.domain.LexicalAnalyzer;
import org.junit.jupiter.api.Test;

class KnowledgeFileSearchServiceTest {

    @Test
    void findsPublishedDocumentByExactTitleWhenNoBodySearchChannelMatches() {
        var documents = mock(KnowledgeRepository.class);
        var governance = mock(KnowledgeGovernanceRepository.class);
        var organizationId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var publicationId = UUID.randomUUID();
        var fileId = UUID.randomUUID();
        var metadataHit = new KnowledgeRepository.SearchRow(UUID.randomUUID(), documentId, versionId,
                "火箭推进剂安全规范", "NASA-Spec-1009.pdf", null, "正文", "允许的推进剂储存条件。", 12.0);
        when(documents.bm25Search(any(), any(), anyBoolean(), any(), any(), anyInt())).thenReturn(List.of());
        when(documents.fullTextSearch(any(), any(), anyBoolean(), any(), any(), anyInt())).thenReturn(List.of());
        when(documents.searchPublishedMetadata(eq(organizationId), eq(List.of("NASA-Spec-1009.pdf")),
                eq(false), anyList(), isNull(), anyInt())).thenReturn(List.of(metadataHit));
        when(documents.findVersion(organizationId, versionId)).thenReturn(Optional.of(
                new KnowledgeRepository.VersionRow(versionId, documentId, 3, fileId, "NASA-Spec-1009.pdf",
                        "application/pdf", 1024, "b".repeat(64), "READY", "poi", null,
                        "PUBLISHED", 2, "AUTO", false, null, "LOCAL", "{}")));
        when(documents.findDocument(organizationId, documentId)).thenReturn(Optional.of(
                new KnowledgeRepository.DocumentRow(documentId, organizationId, "草稿标题", "READY", "SAFE",
                        "PENDING", 3, versionId, "NASA-Spec-1009.pdf", "application/pdf", 1024,
                        "b".repeat(64), null, Instant.now(), Instant.now(), "INTERNAL", UUID.randomUUID(),
                        "SPEC", "ACTIVE", "PUBLISHED", "PUBLISHED", 2, publicationId, 1)));
        when(governance.currentPublication(organizationId, documentId)).thenReturn(Optional.of(
                new KnowledgeGovernanceRepository.PublicationRow(publicationId, documentId, versionId,
                        UUID.randomUUID(), UUID.randomUUID(), 3, "CURRENT", "APPROVED", Instant.now())));
        when(governance.publicationTags(organizationId, publicationId)).thenReturn(List.of("NASA"));
        when(documents.findChunkAnchor(any(), any())).thenReturn(Optional.empty());

        var files = new KnowledgeFileSearchService(documents, governance, testAnalyzer())
                .searchFiles(organizationId, "NASA-Spec-1009.pdf", List.of(), 10);

        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.fileObjectId()).isEqualTo(fileId);
            assertThat(file.fileVersionId()).isEqualTo(versionId);
            assertThat(file.title()).isEqualTo("火箭推进剂安全规范");
            assertThat(file.hits()).singleElement().satisfies(hit ->
                    assertThat(hit.snippet()).isEqualTo("允许的推进剂储存条件。"));
        });
    }

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
                        "READY", "poi", null, "PUBLISHED", 3, "AUTO", false, null, "LOCAL", "{}")));
        when(documents.findDocument(organizationId, documentId)).thenReturn(Optional.of(
                new KnowledgeRepository.DocumentRow(documentId, organizationId, "草稿标题", "READY",
                        "SAFE", "PENDING", 2, versionId, "coa.xlsx", "application/octet-stream", 1024,
                        "a".repeat(64), null, Instant.now(), Instant.now(), "INTERNAL", UUID.randomUUID(),
                        "COA", "ACTIVE", "PUBLISHED", "PUBLISHED", 3, publicationId, 2)));
        when(governance.currentPublication(organizationId, documentId)).thenReturn(Optional.of(
                new KnowledgeGovernanceRepository.PublicationRow(publicationId, documentId, versionId,
                        UUID.randomUUID(), UUID.randomUUID(), 2, "CURRENT", "PENDING", Instant.now())));
        when(governance.publicationTags(organizationId, publicationId)).thenReturn(List.of("COA", "放行"));
        when(documents.findChunkAnchor(any(), any())).thenReturn(Optional.empty());

        var files = new KnowledgeFileSearchService(documents, governance, testAnalyzer())
                .searchFiles(organizationId, "LOT-1", List.of(), 20);

        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.fileObjectId()).isEqualTo(fileId);
            assertThat(file.title()).isEqualTo("已发布标题");
            assertThat(file.hits()).hasSize(2);
            assertThat(file.tags()).containsExactly("COA", "放行");
        });
    }

    @Test
    void selectsThreeTermsWithHighestCorpusInverseDocumentFrequency() {
        var documents = mock(KnowledgeRepository.class);
        var governance = mock(KnowledgeGovernanceRepository.class);
        var analyzer = testAnalyzer();
        var query = "common rare valuable vital";
        var families = analyzer.analyzeCompatibleQuery(query);
        when(documents.termDiscrimination(any(), anyList(), anyList(), isNull())).thenReturn(List.of(
                new KnowledgeRepository.TermDiscrimination(families.get(0).ordinal(), 1.0),
                new KnowledgeRepository.TermDiscrimination(families.get(1).ordinal(), 3.0),
                new KnowledgeRepository.TermDiscrimination(families.get(2).ordinal(), 5.0),
                new KnowledgeRepository.TermDiscrimination(families.get(3).ordinal(), 4.0)));

        var terms = new KnowledgeFileSearchService(documents, governance, analyzer)
                .discriminativeTerms(UUID.randomUUID(), query, List.of(), null);

        assertThat(terms).containsExactly(families.get(2).alternatives().getFirst().term(),
                families.get(3).alternatives().getFirst().term(),
                families.get(1).alternatives().getFirst().term());
    }

    private LexicalAnalyzer testAnalyzer() {
        return new LexicalAnalyzer() {
            @Override public String version() { return "material-smartcn-v2"; }
            @Override public Analysis analyzeDocument(String text) { return analyze(text); }
            @Override public Analysis analyzeQuery(String text) { return analyze(text); }
            private Analysis analyze(String text) {
                var frequencies = new LinkedHashMap<String, Integer>();
                for (var term : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}-]+")) {
                    if (!term.isBlank()) frequencies.merge(term, 1, Integer::sum);
                }
                return new Analysis(frequencies,
                        frequencies.values().stream().mapToInt(Integer::intValue).sum());
            }
        };
    }
}
