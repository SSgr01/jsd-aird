package com.jsd.aird.ai.infrastructure;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ai.application.port.AssistantWebTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class TavilySearchTool implements AssistantWebTool {

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final int maxResults;
    private final RestClient client;

    public TavilySearchTool(
            @Value("${app.ai.tavily.enabled:false}") boolean enabled,
            @Value("${app.ai.tavily.base-url:https://api.tavily.com}") String baseUrl,
            @Value("${app.ai.tavily.api-key:}") String apiKey,
            @Value("${app.ai.tavily.timeout:15s}") Duration timeout,
            @Value("${app.ai.tavily.max-results:5}") int maxResults
    ) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.maxResults = Math.min(10, Math.max(1, maxResults));
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return enabled && StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey);
    }

    @Override
    public Object toolObject() {
        return this;
    }

    @Tool(name = "tavily_web_search", description = "Search approved public web information through the Tavily gateway. Use only for current or public information; never send private document content as the query.")
    public SearchResult search(@ToolParam(description = "A short public-web search query without confidential business data") String query) {
        if (!isConfigured()) return new SearchResult(false, "Tavily 未配置", List.of());
        var safeQuery = query == null ? "" : query.replaceAll("[\\r\\n\\t]", " ").strip();
        if (safeQuery.isBlank() || safeQuery.length() > 300) return new SearchResult(false, "联网检索问题不合法", List.of());
        try {
            var response = client.post().uri(baseUrl + "/search")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(new TavilyRequest(apiKey, safeQuery, maxResults, "advanced", false))
                    .retrieve().body(JsonNode.class);
            var results = response == null || !response.path("results").isArray() ? List.<WebResult>of()
                    : java.util.stream.StreamSupport.stream(response.path("results").spliterator(), false)
                    .map(item -> new WebResult(item.path("title").asText(""), item.path("url").asText(""),
                            item.path("content").asText("")))
                    .filter(item -> StringUtils.hasText(item.url()))
                    .toList();
            return new SearchResult(true, "", results);
        } catch (Exception exception) {
            return new SearchResult(false, "联网检索暂时不可用", List.of());
        }
    }

    public record SearchResult(boolean success, String message, List<WebResult> results) { }
    public record WebResult(String title, String url, String content) { }
    private record TavilyRequest(String api_key, String query, int max_results, String search_depth,
                                 boolean include_answer) { }
}
