package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jsd.aird.core.api.ProjectResourceFacade;
import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeFileSearchFacade;
import com.jsd.aird.shared.security.Actor;
import org.junit.jupiter.api.Test;

class FileSearchServiceTest {

    @Test
    void identifierSearchDoesNotReturnAnUnrelatedFullTextHit() {
        var knowledge = mock(KnowledgeFileSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var projects = mock(ProjectResourceFacade.class);
        var organizationId = UUID.randomUUID();
        var actor = new Actor(organizationId, UUID.randomUUID(), "tester");
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        when(knowledge.searchFiles(any(), any(), anyList(), any(), anyInt())).thenReturn(List.of(
                new KnowledgeFileSearchFacade.FileMatch(UUID.randomUUID(), documentId, versionId,
                        "UA-3131 产品资料", "UA-3131 TDS.pdf", "application/pdf", 100, 1, List.of(),
                        Instant.now(), List.of(new KnowledgeFileSearchFacade.Hit(UUID.randomUUID(),
                                "UA-3131 的相关资料", 0.9, 1, null, null, null, List.of(), null, null, "正文",
                                null, List.of(), List.of(), List.of())), List.of())
        ));
        when(projects.links(any(), any(), anyCollection())).thenReturn(Map.of());

        var result = new FileSearchService(knowledge, data, projects).search(actor,
                new FileSearchService.SearchCommand("UA-1117", 20, List.of(), List.of(), null));

        assertThat(result.files()).isEmpty();
    }

    @Test
    void identifierSearchMarksExactFileNameHit() {
        var knowledge = mock(KnowledgeFileSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var projects = mock(ProjectResourceFacade.class);
        var organizationId = UUID.randomUUID();
        var actor = new Actor(organizationId, UUID.randomUUID(), "tester");
        when(knowledge.searchFiles(any(), any(), anyList(), any(), anyInt())).thenReturn(List.of(
                new KnowledgeFileSearchFacade.FileMatch(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "UA-1117", "UA-1117", "application/pdf", 100, 1, List.of(), Instant.now(),
                        List.of(new KnowledgeFileSearchFacade.Hit(UUID.randomUUID(), "产品资料", 0.4,
                                1, null, null, null, List.of(), null, null, "正文", null,
                                List.of(), List.of(), List.of())), List.of())
        ));
        when(projects.links(any(), any(), anyCollection())).thenReturn(Map.of());

        var result = new FileSearchService(knowledge, data, projects).search(actor,
                new FileSearchService.SearchCommand("UA-1117", 20, List.of(), List.of(), null));

        assertThat(result.files()).singleElement().satisfies(file -> {
            assertThat(file.originalName()).isEqualTo("UA-1117");
            assertThat(file.matchType()).isEqualTo("EXACT_FILENAME");
            assertThat(file.matchedFields()).contains("文件名");
        });
    }

    @Test
    void nonIdentifierWithoutMatchEvidenceIsDropped() {
        var knowledge = mock(KnowledgeFileSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var projects = mock(ProjectResourceFacade.class);
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "tester");
        when(knowledge.searchFiles(any(), any(), anyList(), any(), anyInt())).thenReturn(List.of(
                new KnowledgeFileSearchFacade.FileMatch(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "聚氨酯说明", "说明书.pdf", "application/pdf", 100, 1, List.of(), Instant.now(),
                        List.of(new KnowledgeFileSearchFacade.Hit(UUID.randomUUID(), "常规产品资料", 0.1,
                                1, null, null, null, List.of(), null, null, "正文", null,
                                List.of(), List.of(), List.of())), List.of())
        ));
        when(projects.links(any(), any(), anyCollection())).thenReturn(Map.of());

        var result = new FileSearchService(knowledge, data, projects).search(actor,
                new FileSearchService.SearchCommand("绝对不存在关键词", 20, List.of(), List.of(), null));

        assertThat(result.files()).isEmpty();
    }

    @Test
    void multiTermSearchRequiresTwoOfTheThreeMostDiscriminativeTerms() {
        var knowledge = mock(KnowledgeFileSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var projects = mock(ProjectResourceFacade.class);
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "tester");
        when(knowledge.discriminativeTerms(any(), any(), anyList(), any()))
                .thenReturn(List.of("聚氨酯", "粘接", "耐热"));
        when(knowledge.searchFiles(any(), any(), anyList(), any(), anyInt())).thenReturn(List.of(
                knowledgeFile("两项命中", "聚氨酯胶黏剂具有良好的粘接强度。"),
                knowledgeFile("单项命中", "耐热材料的基本介绍。")));
        when(projects.links(any(), any(), anyCollection())).thenReturn(Map.of());

        var result = new FileSearchService(knowledge, data, projects).search(actor,
                new FileSearchService.SearchCommand("耐热 聚氨酯 粘接", 20, List.of(), List.of(), null));

        assertThat(result.files()).singleElement().satisfies(file ->
                assertThat(file.title()).isEqualTo("两项命中"));
    }

    @Test
    void userSeparatedQueryTermsArePreservedWhenAnalyzerDropsAChineseCompound() {
        var knowledge = mock(KnowledgeFileSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var projects = mock(ProjectResourceFacade.class);
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "tester");
        when(knowledge.discriminativeTerms(any(), any(), anyList(), any()))
                .thenReturn(List.of("树脂"));
        when(knowledge.searchFiles(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                knowledgeFile("普通树脂说明", "树脂的普通说明。"),
                knowledgeFile("耐候树脂说明", "树脂具有耐候性。")));
        when(projects.links(any(), any(), anyCollection())).thenReturn(Map.of());

        var result = new FileSearchService(knowledge, data, projects).search(actor,
                new FileSearchService.SearchCommand("树脂 耐候", 20, List.of(), List.of(), null));

        assertThat(result.files()).singleElement().satisfies(file ->
                assertThat(file.title()).isEqualTo("耐候树脂说明"));
    }

    @Test
    void aSingleEffectiveQueryTermCanStillReturnContentMatches() {
        var knowledge = mock(KnowledgeFileSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var projects = mock(ProjectResourceFacade.class);
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "tester");
        when(knowledge.discriminativeTerms(any(), any(), anyList(), any()))
                .thenReturn(List.of("聚氨酯"));
        when(knowledge.searchFiles(any(), any(), anyList(), any(), anyInt())).thenReturn(List.of(
                knowledgeFile("聚氨酯说明", "聚氨酯可用于耐磨涂层。")));
        when(projects.links(any(), any(), anyCollection())).thenReturn(Map.of());

        var result = new FileSearchService(knowledge, data, projects).search(actor,
                new FileSearchService.SearchCommand("聚氨酯", 20, List.of(), List.of(), null));

        assertThat(result.files()).singleElement().satisfies(file ->
                assertThat(file.matchType()).isEqualTo("CONTENT"));
    }

    @Test
    void exactFilenameStillBypassesTheMultiTermContentGate() {
        var knowledge = mock(KnowledgeFileSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var projects = mock(ProjectResourceFacade.class);
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "tester");
        var query = "耐热 聚氨酯 粘接";
        when(knowledge.discriminativeTerms(any(), any(), anyList(), any()))
                .thenReturn(List.of("聚氨酯", "粘接", "耐热"));
        when(knowledge.searchFiles(any(), any(), anyList(), any(), anyInt())).thenReturn(List.of(
                knowledgeFile(query, "这份文件内容没有检索词。")));
        when(projects.links(any(), any(), anyCollection())).thenReturn(Map.of());

        var result = new FileSearchService(knowledge, data, projects).search(actor,
                new FileSearchService.SearchCommand(query, 20, List.of(), List.of(), null));

        assertThat(result.files()).singleElement().satisfies(file ->
                assertThat(file.matchType()).isEqualTo("EXACT_FILENAME"));
    }

    @Test
    void fullFilenameIncludingExtensionReturnsOnlyTheExactFile() {
        var knowledge = mock(KnowledgeFileSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var projects = mock(ProjectResourceFacade.class);
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "tester");
        var query = "介孔碳生物材料.pdf";
        when(knowledge.discriminativeTerms(any(), any(), anyList(), any()))
                .thenReturn(List.of("材料"));
        when(knowledge.searchFiles(any(), any(), anyList(), any(), anyInt())).thenReturn(List.of(
                knowledgeFile("介孔碳生物材料", "这份正文没有完整文件名。"),
                knowledgeFile("自修复材料说明", "材料的常规概述。")));
        when(projects.links(any(), any(), anyCollection())).thenReturn(Map.of());

        var result = new FileSearchService(knowledge, data, projects).search(actor,
                new FileSearchService.SearchCommand(query, 20, List.of(), List.of(), null));

        assertThat(result.files()).singleElement().satisfies(file -> {
            assertThat(file.title()).isEqualTo("介孔碳生物材料");
            assertThat(file.originalName()).isEqualTo("介孔碳生物材料.pdf");
            assertThat(file.matchType()).isEqualTo("EXACT_FILENAME");
        });
    }

    @Test
    void appliesTheSameCoreTermGateToDataCenterFiles() {
        var knowledge = mock(KnowledgeFileSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var projects = mock(ProjectResourceFacade.class);
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "tester");
        var categoryId = UUID.randomUUID();
        when(knowledge.discriminativeTerms(any(), any(), anyList(), any()))
                .thenReturn(List.of("聚氨酯", "粘接", "耐热"));
        when(knowledge.searchFiles(any(), any(), anyList(), any(), anyInt())).thenReturn(List.of());
        when(data.searchSourceFiles(any(), anyString(), anyList(),
                any(DataSourceFileSearchFacade.RetrievalAccessScope.class), anyInt())).thenReturn(List.of(
                dataFile("two-terms.xlsx", "聚氨酯胶黏剂具有良好的粘接强度。"),
                dataFile("one-term.xlsx", "耐热材料的基本介绍。")));
        when(projects.links(any(), any(), anyCollection())).thenReturn(Map.of());

        var result = new FileSearchService(knowledge, data, projects).search(actor,
                new FileSearchService.SearchCommand("耐热 聚氨酯 粘接", 20, List.of(),
                        List.of(categoryId), null));

        assertThat(result.files()).singleElement().satisfies(file ->
                assertThat(file.originalName()).isEqualTo("two-terms.xlsx"));
    }

    private KnowledgeFileSearchFacade.FileMatch knowledgeFile(String title, String snippet) {
        var documentId = UUID.randomUUID();
        var hit = new KnowledgeFileSearchFacade.Hit(UUID.randomUUID(), snippet, 0.5, 1,
                null, null, null, List.of(), null, null, "正文", null, List.of(), List.of(), List.of());
        return new KnowledgeFileSearchFacade.FileMatch(UUID.randomUUID(), documentId, UUID.randomUUID(),
                title, title + ".pdf", "application/pdf", 100, 1, List.of(), Instant.now(), List.of(hit), List.of());
    }

    private DataSourceFileSearchFacade.SourceFileMatch dataFile(String name, String snippet) {
        var hit = new DataSourceFileSearchFacade.Hit(UUID.randomUUID(), snippet, 0.5,
                "Sheet1", 1, "内容", "A1");
        return new DataSourceFileSearchFacade.SourceFileMatch(UUID.randomUUID(), UUID.randomUUID(), name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 100, Instant.now(),
                List.of(hit), List.of(), "DATA_CENTER", "TEMPLATE_GUIDED", null, null);
    }
}
