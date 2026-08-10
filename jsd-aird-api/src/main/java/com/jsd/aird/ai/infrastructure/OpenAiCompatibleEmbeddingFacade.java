package com.jsd.aird.ai.infrastructure;

import java.time.Duration;
import java.util.Optional;
import java.util.StringJoiner;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.kb.api.KnowledgeEmbeddingFacade;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Component
public class OpenAiCompatibleEmbeddingFacade implements KnowledgeEmbeddingFacade {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final RestClient client;
    private final ObjectProvider<EmbeddingModel> springModels;

    public OpenAiCompatibleEmbeddingFacade(
            @Value("${app.ai.embedding.base-url:}") String baseUrl,
            @Value("${app.ai.embedding.api-key:}") String apiKey,
            @Value("${app.ai.embedding.model:}") String model,
            @Value("${app.ai.embedding.timeout:30s}") Duration timeout,
            ObjectProvider<EmbeddingModel> springModels
    ) {
        this.baseUrl = strip(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        this.springModels = springModels;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Optional<String> embedVector(String text) {
        if (!StringUtils.hasText(text)) return Optional.empty();
        var springModel = springModels.getIfAvailable();
        if (springModel != null) {
            try {
                var values = springModel.embed(text);
                if (values != null && values.length > 0) return Optional.of(toVector(values));
            } catch (Exception ignored) {
                // Continue with the explicitly configured compatible endpoint below.
            }
        }
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey) || !StringUtils.hasText(model)) return Optional.empty();
        try {
            var request = client.post().uri(baseUrl + "/embeddings")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json");
            if (!apiKey.isBlank()) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            var response = request.body(new EmbeddingRequest(model, text)).retrieve().body(JsonNode.class);
            var values = response == null ? null : response.path("data").path(0).path("embedding");
            if (values == null || !values.isArray() || values.isEmpty()) return Optional.empty();
            var joiner = new StringJoiner(", ", "[", "]");
            values.forEach(value -> joiner.add(value.asText()));
            return Optional.of(joiner.toString());
        } catch (Exception ignored) {
            // Vector search is optional. Full-text search remains available and no provider is silently swapped.
            return Optional.empty();
        }
    }

    private String toVector(float[] values) {
        var joiner = new StringJoiner(", ", "[", "]");
        for (var value : values) joiner.add(Float.toString(value));
        return joiner.toString();
    }

    private String strip(String value) {
        return value == null ? "" : value.strip().replaceAll("/+$", "");
    }

    private record EmbeddingRequest(String model, String input) { }
}
