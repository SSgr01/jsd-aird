package com.jsd.aird.kb.api;

import java.util.List;
import java.util.Optional;

public interface KnowledgeEmbeddingFacade {

    enum QueryTask { KNOWLEDGE, OPERATION_MANUAL }

    /** Raw document embedding. Query instructions must never be applied here. */
    Optional<String> embedVector(String text);

    default List<Optional<String>> embedVectors(List<String> texts) {
        if (texts == null) return List.of();
        return texts.stream().map(this::embedVector).toList();
    }

    default Optional<String> embedQueryVector(String query, QueryTask task) {
        return embedVector(query);
    }

    default List<Optional<String>> embedQueryVectors(List<String> queries, QueryTask task) {
        if (queries == null) return List.of();
        return queries.stream().map(query -> embedQueryVector(query, task)).toList();
    }

    /** Trusted startup policy only; included in retrieval/cache fingerprints. */
    default java.util.Map<String, Object> queryPolicy() {
        return java.util.Map.of("profile", "none", "version", "plain-v1");
    }
}
