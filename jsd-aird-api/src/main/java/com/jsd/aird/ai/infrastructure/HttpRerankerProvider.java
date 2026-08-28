package com.jsd.aird.ai.infrastructure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ai.application.port.RerankerProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class HttpRerankerProvider implements RerankerProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpRerankerProvider.class);
    private static final int TOP_N = 12;
    private static final String INSTRUCT = "Given a user question, retrieve passages that directly answer every requested fact.";

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final RestClient client;

    public HttpRerankerProvider(
            @Value("${app.ai.reranker.endpoint:}") String endpoint,
            @Value("${app.ai.reranker.api-key:}") String apiKey,
            @Value("${app.ai.reranker.model:}") String model,
            @Value("${app.ai.reranker.timeout:8s}") Duration timeout
    ) {
        this.endpoint = endpoint == null ? "" : endpoint.strip();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(endpoint) && StringUtils.hasText(apiKey) && StringUtils.hasText(model);
    }

    @Override
    public RerankOutcome rerank(String query, List<RankCandidate> candidates) {
        var safeCandidates = candidates == null ? List.<RankCandidate>of() : candidates.stream().limit(30).toList();
        if (!isConfigured()) return RerankOutcome.skipped("NOT_CONFIGURED", safeCandidates.size());
        if (safeCandidates.isEmpty()) return RerankOutcome.skipped("EMPTY_INPUT", 0);
        var started = System.nanoTime();
        try {
            var request = client.post().uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(apiKey)) request.header("Authorization", "Bearer " + apiKey);
            var documents = safeCandidates.stream().map(RankCandidate::content).toList();
            var response = request.body(new RerankRequest(model, query, documents, TOP_N, INSTRUCT))
                    .retrieve().body(JsonNode.class);
            var results = response == null ? null : response.path("output").path("results");
            if (results == null || !results.isArray()) {
                return outcome(List.of(), "INVALID_RESPONSE", 200, "results is not an array", started,
                        safeCandidates.size());
            }
            var ranked = new ArrayList<RankedDocument>();
            int position = 0;
            for (var item : results) {
                var index = item.has("index") ? item.path("index").asInt(-1) : -1;
                if (index < 0 || index >= safeCandidates.size()) continue;
                ranked.add(new RankedDocument(safeCandidates.get(index).id(),
                        item.path("relevance_score").asDouble(item.path("score").asDouble(0)), position++, "http", model));
            }
            return outcome(ranked, ranked.isEmpty() ? "EMPTY_RESPONSE" : "SUCCEEDED", 200, "", started,
                    safeCandidates.size());
        } catch (RestClientResponseException exception) {
            log.warn("Reranker HTTP {} for model {}: {}", exception.getStatusCode().value(), model, exception.getMessage());
            return outcome(List.of(), "HTTP_ERROR", exception.getStatusCode().value(), exception.getMessage(), started,
                    safeCandidates.size());
        } catch (Exception exception) {
            log.warn("Reranker request failed for model {}: {}", model, exception.getMessage());
            return outcome(List.of(), "FAILED", null, exception.getMessage(), started, safeCandidates.size());
        }
    }

    private RerankOutcome outcome(List<RankedDocument> documents, String status, Integer httpStatus,
                                  String error, long started, int inputCount) {
        var elapsed = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        var safeError = error == null ? "" : error.strip();
        if (safeError.length() > 500) safeError = safeError.substring(0, 500);
        return new RerankOutcome(documents, status, "http", model, httpStatus, safeError, elapsed,
                inputCount, documents.size());
    }

    private record RerankRequest(String model, RerankInput input, RerankParameters parameters) {
        private RerankRequest(String model, String query, List<String> documents, int topN, String instruct) {
            this(model, new RerankInput(query, documents), new RerankParameters(topN, instruct));
        }
    }

    private record RerankInput(String query, List<String> documents) { }

    private record RerankParameters(int top_n, String instruct) { }
}
