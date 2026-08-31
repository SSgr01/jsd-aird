package com.jsd.aird.ai.application.port;

import java.util.List;

public interface WebSearchProvider {

    boolean isAvailable();

    SearchResponse search(SearchQuery query);

    record SearchQuery(String query, String topic, String timeRange) { }

    record SearchResponse(String status, List<SearchResult> results, long elapsedMs,
                          long providerResponseMs, Integer httpStatus, String error) {
        public SearchResponse {
            results = results == null ? List.of() : List.copyOf(results);
            status = status == null ? "PROVIDER_ERROR" : status;
            error = error == null ? "" : error;
        }
    }

    record SearchResult(String title, String url, String content, double score, String publishedAt) { }
}
