package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.application.port.RerankerProvider;
import com.jsd.aird.ai.application.port.WebSearchProvider;
import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@Timeout(10)
class RagWebSearchCoordinationTest {

    @Test
    void startsWebBeforeInternalRetrievalCompletesAndAllowsExternalOnlyEvidence() throws Exception {
        var webStarted = new CountDownLatch(1);
        var internalFinished = new CountDownLatch(1);
        var knowledge = new EmptyKnowledgeSearch() {
            @Override
            public SearchResult search(SearchRequest request) {
                try {
                    assertThat(webStarted.await(1, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                internalFinished.countDown();
                return emptyResult();
            }
        };
        WebSearchProvider web = new WebSearchProvider() {
            @Override public boolean isAvailable() { return true; }
            @Override public SearchResponse search(SearchQuery query) {
                webStarted.countDown();
                try {
                    assertThat(internalFinished.await(1, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return new SearchResponse("SUCCEEDED", List.of(new SearchResult("Public",
                        "https://example.com/public", "external evidence", 0.9, "")), 20, 10, 200, "");
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var service = new RagRetrievalService(knowledge, emptyDataSearch(), rewriteFallback(), noReranker(), web,
                    executor, Duration.ofSeconds(2), 6);

            var result = service.retrieve(UUID.fromString("00000000-0000-0000-0000-000000000001"), "A-186", List.of(),
                    List.of(UUID.fromString("00000000-0000-0000-0000-000000000002")), List.of(), true, true);

            assertThat(result.knowledgeHits()).isEmpty();
            assertThat(result.webHits()).singleElement()
                    .extracting(RagRetrievalService.WebHit::url).isEqualTo("https://example.com/public");
            assertThat(result.trace().web().status()).isEqualTo("SUCCEEDED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void disabledWebSearchDoesNotInspectOrCallProvider() {
        var calls = new AtomicInteger();
        WebSearchProvider web = new WebSearchProvider() {
            @Override public boolean isAvailable() { calls.incrementAndGet(); return true; }
            @Override public SearchResponse search(SearchQuery query) {
                calls.incrementAndGet();
                return new SearchResponse("EMPTY", List.of(), 0, 0, 200, "");
            }
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var service = new RagRetrievalService(new EmptyKnowledgeSearch(), emptyDataSearch(), rewriteFallback(),
                    noReranker(), web, executor, Duration.ofSeconds(1), 6);

            var result = service.retrieve(UUID.fromString("00000000-0000-0000-0000-000000000003"), "question", List.of(),
                    List.of(), List.of(), true, false);

            assertThat(result.trace().web().status()).isEqualTo("SKIPPED");
            assertThat(calls).hasValue(0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void prioritizesPublicAuthoritySourcesAheadOfThirdPartyResults() {
        WebSearchProvider web = new WebSearchProvider() {
            @Override public boolean isAvailable() { return true; }
            @Override public SearchResponse search(SearchQuery query) {
                return new SearchResponse("SUCCEEDED", List.of(
                        new SearchResult("Third-party directory", "https://catalog.example.com/a-186",
                                "Third-party A-186 catalog entry 3388-04-3", 0.99, ""),
                        new SearchResult("Government reference database", "https://webbook.nist.gov/a-186",
                                "Government A-186 reference record 3388-04-3", 0.70, "")
                ), 20, 10, 200, "");
            }
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var service = new RagRetrievalService(new EmptyKnowledgeSearch(), emptyDataSearch(), rewriteFallback(),
                    noReranker(), web, executor, Duration.ofSeconds(1), 6);

            var result = service.retrieve(UUID.fromString("00000000-0000-0000-0000-000000000004"), "A-186", List.of(),
                    List.of(), List.of(), true, true);

            assertThat(result.webHits()).extracting(RagRetrievalService.WebHit::url).containsExactly(
                    "https://webbook.nist.gov/a-186", "https://catalog.example.com/a-186");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void classifiesAuthorityDomainsWithoutProductOrManufacturerLists() {
        assertThat(RagRetrievalService.webAuthorityTier("https://webbook.nist.gov/cgi/cbook.cgi")).isEqualTo(3);
        assertThat(RagRetrievalService.webAuthorityTier("https://www.gov.cn/policy")).isEqualTo(3);
        assertThat(RagRetrievalService.webAuthorityTier("https://materials.example.edu/paper")).isEqualTo(2);
        assertThat(RagRetrievalService.webAuthorityTier("https://www.drugfuture.com/toxic/a-186")).isZero();
    }

    @Test
    void removesMirroredWebResultsWithIdenticalEvidenceContent() {
        WebSearchProvider web = new WebSearchProvider() {
            @Override public boolean isAvailable() { return true; }
            @Override public SearchResponse search(SearchQuery query) {
                return new SearchResponse("SUCCEEDED", List.of(
                        new SearchResult("Marketplace mobile", "https://m.marketplace.example/a-186",
                                "A-186 chemical name CAS 3388-04-3", 0.95, ""),
                        new SearchResult("Marketplace vendor", "https://vendor.marketplace.example/a-186",
                                "A-186 chemical name CAS 3388-04-3", 0.90, "")
                ), 20, 10, 200, "");
            }
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var service = new RagRetrievalService(new EmptyKnowledgeSearch(), emptyDataSearch(), rewriteFallback(),
                    noReranker(), web, executor, Duration.ofSeconds(1), 6);

            var result = service.retrieve(UUID.fromString("00000000-0000-0000-0000-000000000005"), "A-186", List.of(),
                    List.of(), List.of(), true, true);

            assertThat(result.webHits()).singleElement()
                    .extracting(RagRetrievalService.WebHit::url).isEqualTo("https://m.marketplace.example/a-186");
        } finally {
            executor.shutdownNow();
        }
    }

    private QueryRewriteService rewriteFallback() {
        var factory = new StaticListableBeanFactory();
        return new QueryRewriteService(factory.getBeanProvider(ChatClient.Builder.class),
                new AiJsonParser(new ObjectMapper()), Executors.newSingleThreadExecutor(), false, "",
                Duration.ofSeconds(1), 128);
    }

    private DataSourceFileSearchFacade emptyDataSearch() {
        return (organizationId, query, categoryIds, limit) -> List.of();
    }

    private RerankerProvider noReranker() {
        return new RerankerProvider() {
            @Override public boolean isConfigured() { return false; }
            @Override public RerankOutcome rerank(String query, List<RankCandidate> candidates) {
                return RerankOutcome.skipped("NOT_CONFIGURED", candidates.size());
            }
        };
    }

    private static class EmptyKnowledgeSearch implements KnowledgeSearchFacade {
        @Override public List<SearchHit> search(UUID organizationId, String query, boolean aiOnly, int limit) {
            return List.of();
        }

        @Override public SearchResult search(SearchRequest request) {
            return emptyResult();
        }

        protected SearchResult emptyResult() {
            return new SearchResult(List.of(), new RetrievalTrace("EMPTY", 0, 0, 0, List.of()));
        }
    }
}
