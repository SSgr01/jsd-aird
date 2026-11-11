package com.jsd.aird.kb.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import com.jsd.aird.kb.application.port.KnowledgeRepository;
import com.jsd.aird.kb.domain.LexicalAnalyzer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscoveryQueryTest {
    @Test void titlesInNaturalQuestionsRemainSeparateDiscoveryTerms() {
        var question = "请比较《甲材料的制备》与《乙材料的测试》中给出的条件，不补造缺项。";
        assertThat(KnowledgeService.discoveryQueries(question))
            .containsExactly("甲材料的制备", "乙材料的测试", question);
        assertThat(KnowledgeService.discoveryQueries("10.1234/example.2026"))
            .containsExactly("10.1234/example.2026");
        assertThat(KnowledgeService.discoveryQueries(null)).isEmpty();
    }

    @Test void documentDiscoverySurvivesBroadParagraphRecall() {
        var repository = mock(KnowledgeRepository.class);
        var analyzer = mock(LexicalAnalyzer.class);
        var org = UUID.randomUUID();
        var titleMatch = row("Exact title", UUID.randomUUID());
        var tagMatch = row("Published material tag", UUID.randomUUID());
        var unrelatedDoc = UUID.randomUUID();
        var broad = IntStream.range(0, 30).mapToObj(i -> row("Broad paragraph " + i, unrelatedDoc)).toList();
        when(repository.fullTextSearch(eq(org), anyString(), eq(true), anyList(), eq(48))).thenReturn(broad);
        when(repository.searchPublishedMetadata(eq(org), anyList(), eq(true), anyList(), isNull(), anyInt()))
            .thenReturn(List.of(titleMatch));
        when(repository.searchPublishedTags(eq(org), anyList(), eq(true), anyList(), isNull(), anyInt()))
            .thenReturn(List.of(tagMatch));
        var service = new KnowledgeService(repository, null, null, null, null, new ObjectMapper(), null,
            List.of(), null, null, List.of(), "", 1024, Duration.ofMinutes(1), null, analyzer, Runnable::run);
        var request = new KnowledgeSearchFacade.SearchRequest(org, "material", true, 10,
            List.of(), List.of(), List.of(), List.of(), "material",
            new KnowledgeSearchFacade.RecallOptions(true, false, true));

        var result = service.search(request);

        assertThat(result.hits().subList(0, 2)).extracting(KnowledgeSearchFacade.SearchHit::documentId)
            .containsExactlyInAnyOrder(titleMatch.documentId(), tagMatch.documentId());
    }

    private static KnowledgeRepository.SearchRow row(String title, UUID document) {
        return new KnowledgeRepository.SearchRow(UUID.randomUUID(), document, UUID.randomUUID(), title,
            "original.pdf", 1, "", "Matching paragraph", 1);
    }
}
