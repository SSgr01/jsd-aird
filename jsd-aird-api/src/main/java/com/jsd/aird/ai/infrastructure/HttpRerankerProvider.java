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
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Component
public class HttpRerankerProvider implements RerankerProvider {

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final RestClient client;

    public HttpRerankerProvider(
            @Value("${app.ai.reranker.base-url:}") String baseUrl,
            @Value("${app.ai.reranker.api-key:}") String apiKey,
            @Value("${app.ai.reranker.model:}") String model,
            @Value("${app.ai.reranker.timeout:8s}") Duration timeout
    ) {
        this.endpoint = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(endpoint) && StringUtils.hasText(model);
    }

    @Override
    public List<RankedDocument> rerank(String query, List<RankCandidate> candidates) {
        if (!isConfigured() || candidates == null || candidates.isEmpty()) return List.of();
        try {
            var request = client.post().uri(endpoint.endsWith("/rerank") ? endpoint : endpoint + "/rerank")
                    .contentType(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(apiKey)) request.header("Authorization", "Bearer " + apiKey);
            var documents = candidates.stream().limit(30).map(RankCandidate::content).toList();
            var response = request.body(new RerankRequest(model, query, documents, true)).retrieve().body(JsonNode.class);
            var results = response == null ? null : response.path("results");
            if (results == null || !results.isArray()) return List.of();
            var ranked = new ArrayList<RankedDocument>();
            int position = 0;
            for (var item : results) {
                var index = item.has("index") ? item.path("index").asInt(-1) : -1;
                if (index < 0 || index >= candidates.size()) continue;
                ranked.add(new RankedDocument(candidates.get(index).id(),
                        item.path("relevance_score").asDouble(item.path("score").asDouble(0)), position++, "http", model));
            }
            return ranked;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private record RerankRequest(String model, String query, List<String> documents, boolean return_documents) {
    }
}
