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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class OpenAiCompatibleEmbeddingFacade implements KnowledgeEmbeddingFacade {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingFacade.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimension;
    private final RestClient client;
    private final ObjectProvider<EmbeddingModel> springModels;

    public OpenAiCompatibleEmbeddingFacade(
            @Value("${app.ai.embedding.base-url:}") String baseUrl,
            @Value("${app.ai.embedding.api-key:}") String apiKey,
            @Value("${app.ai.embedding.model:}") String model,
            @Value("${app.ai.embedding.dimension:1024}") int dimension,
            @Value("${app.ai.embedding.timeout:30s}") Duration timeout,
            ObjectProvider<EmbeddingModel> springModels
    ) {
        this.baseUrl = strip(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        this.dimension = dimension;
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
                if (matchesDimension(values)) return Optional.of(toVector(values));
                if (values != null && values.length > 0) {
                    log.warn("Embedding provider returned dimension {}, expected {} for model {}",
                            values.length, dimension, model);
                }
            } catch (Exception ignored) {
                // Continue with the explicitly configured compatible endpoint below.
            }
        }
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey) || !StringUtils.hasText(model)) return Optional.empty();
        try {
            var request = client.post().uri(baseUrl + "/embeddings")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json");
            if (!apiKey.isBlank()) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            var response = request.body(new EmbeddingRequest(model, text, dimension > 0 ? dimension : null, "float")).retrieve()
                    .body(JsonNode.class);
            var values = response == null ? null : response.path("data").path(0).path("embedding");
            if (values == null || !values.isArray() || values.isEmpty()) return Optional.empty();
            if (dimension > 0 && values.size() != dimension) {
                log.warn("Embedding endpoint returned dimension {}, expected {} for model {}",
                        values.size(), dimension, model);
                return Optional.empty();
            }
            var joiner = new StringJoiner(", ", "[", "]");
            values.forEach(value -> joiner.add(value.asText()));
            return Optional.of(joiner.toString());
        } catch (Exception exception) {
            // Vector search is optional. Full-text search remains available and no provider is silently swapped.
            log.warn("Embedding request failed for model {}: {}", model, exception.getMessage());
            return Optional.empty();
        }
    }

    private String toVector(float[] values) {
        var joiner = new StringJoiner(", ", "[", "]");
        for (var value : values) joiner.add(Float.toString(value));
        return joiner.toString();
    }

    private boolean matchesDimension(float[] values) {
        return values != null && values.length > 0 && (dimension <= 0 || values.length == dimension);
    }

    private String strip(String value) {
        return value == null ? "" : value.strip().replaceAll("/+$", "");
    }

    private record EmbeddingRequest(String model, String input, Integer dimensions, String encoding_format) { }
}
