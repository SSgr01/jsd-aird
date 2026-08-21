package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeFileSearchFacade;
import org.junit.jupiter.api.Test;

class FileSearchServiceTest {

    @Test
    void identifierSearchDoesNotReturnAnUnrelatedFullTextHit() {
        var knowledge = mock(KnowledgeFileSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var organizationId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        when(knowledge.searchFiles(any(), any(), anyList(), anyList(), anyInt())).thenReturn(List.of(
                new KnowledgeFileSearchFacade.FileMatch(UUID.randomUUID(), documentId, versionId,
                        "UA-3131 产品资料", "UA-3131 TDS.pdf", "application/pdf", 100, 1, List.of(),
                        Instant.now(), List.of(new KnowledgeFileSearchFacade.Hit(UUID.randomUUID(),
                                "UA-3131 的相关资料", 0.9, 1, null, null, null, List.of(), null, null, "正文")))
        ));

        var result = new FileSearchService(knowledge, data).search(organizationId,
                new FileSearchService.SearchCommand("UA-1117", 20, List.of(), List.of(), List.of()));

        assertThat(result.files()).isEmpty();
    }

    @Test
    void identifierSearchMarksExactFileNameHit() {
        var knowledge = mock(KnowledgeFileSearchFacade.class);
        var data = mock(DataSourceFileSearchFacade.class);
        var organizationId = UUID.randomUUID();
        when(knowledge.searchFiles(any(), any(), anyList(), anyList(), anyInt())).thenReturn(List.of(
                new KnowledgeFileSearchFacade.FileMatch(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "UA-1117", "UA-1117", "application/pdf", 100, 1, List.of(), Instant.now(),
                        List.of(new KnowledgeFileSearchFacade.Hit(UUID.randomUUID(), "产品资料", 0.4,
                                1, null, null, null, List.of(), null, null, "正文")))
        ));

        var result = new FileSearchService(knowledge, data).search(organizationId,
                new FileSearchService.SearchCommand("UA-1117", 20, List.of(), List.of(), List.of()));

        assertThat(result.files()).singleElement().satisfies(file -> {
            assertThat(file.originalName()).isEqualTo("UA-1117");
            assertThat(file.matchType()).isEqualTo("EXACT_FILENAME");
            assertThat(file.matchedFields()).contains("文件名");
        });
    }
}
