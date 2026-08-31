package com.jsd.aird.ai.infrastructure;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ai.application.port.WebSearchProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class TavilyWebSearchProvider implements WebSearchProvider {

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final int maxResults;
    private final RestClient client;

    @Autowired
    public TavilyWebSearchProvider(
            @Value("${app.ai.tavily.enabled:false}") boolean enabled,
            @Value("${app.ai.tavily.base-url:https://api.tavily.com}") String baseUrl,
            @Value("${app.ai.tavily.api-key:}") String apiKey,
            @Value("${app.ai.tavily.timeout:5s}") Duration timeout,
            @Value("${app.ai.tavily.max-results:5}") int maxResults
    ) {
        this(enabled, baseUrl, apiKey, maxResults, createClient(timeout));
    }

    TavilyWebSearchProvider(boolean enabled, String baseUrl, String apiKey, int maxResults, RestClient client) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.maxResults = Math.min(5, Math.max(1, maxResults));
        this.client = client;
    }

    private static RestClient createClient(Duration timeout) {
        var safeTimeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(5) : timeout;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(safeTimeout);
        factory.setReadTimeout(safeTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean isAvailable() {
        return enabled && StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey);
    }

    @Override
    public SearchResponse search(SearchQuery query) {
        var started = System.nanoTime();
        if (!isAvailable()) return response("NOT_CONFIGURED", started, 0, null, "provider not configured");
        var safeQuery = query == null || query.query() == null ? ""
                : query.query().replaceAll("[\\r\\n\\t]+", " ").strip();
        if (safeQuery.isBlank() || safeQuery.length() > 300) {
            return response("INVALID_QUERY", started, 0, null, "invalid query");
        }
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("query", safeQuery);
            body.put("max_results", maxResults);
            body.put("search_depth", "advanced");
            body.put("include_answer", false);
            body.put("include_raw_content", false);
            body.put("include_images", false);
            body.put("topic", "NEWS".equalsIgnoreCase(query.topic()) ? "news" : "general");
            var timeRange = tavilyTimeRange(query.timeRange());
            if (StringUtils.hasText(timeRange)) body.put("time_range", timeRange);
            var payload = client.post().uri(baseUrl + "/search")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            var results = payload == null || !payload.path("results").isArray() ? List.<SearchResult>of()
                    : java.util.stream.StreamSupport.stream(payload.path("results").spliterator(), false)
                    .map(item -> new SearchResult(item.path("title").asText(""), item.path("url").asText(""),
                            item.path("content").asText(""), item.path("score").asDouble(0d),
                            item.path("published_date").asText("")))
                    .filter(item -> StringUtils.hasText(item.url()))
                    .toList();
            var providerMs = payload == null ? 0L : Math.max(0L,
                    Math.round(payload.path("response_time").asDouble(0d) * 1_000d));
            return new SearchResponse(results.isEmpty() ? "EMPTY" : "SUCCEEDED", results,
                    elapsedMs(started), providerMs, 200, "");
        } catch (RestClientResponseException exception) {
            return response("HTTP_ERROR", started, 0, exception.getStatusCode().value(),
                    "HTTP_" + exception.getStatusCode().value());
        } catch (Exception exception) {
            return response("PROVIDER_ERROR", started, 0, null, exception.getClass().getSimpleName());
        }
    }

    private SearchResponse response(String status, long started, long providerMs, Integer httpStatus, String error) {
        return new SearchResponse(status, List.of(), elapsedMs(started), providerMs, httpStatus, error);
    }

    private String tavilyTimeRange(String value) {
        if (!StringUtils.hasText(value)) return "";
        return switch (value.strip().toUpperCase(java.util.Locale.ROOT)) {
            case "DAY" -> "day";
            case "WEEK" -> "week";
            case "MONTH" -> "month";
            case "YEAR" -> "year";
            default -> "";
        };
    }

    private long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }
}
