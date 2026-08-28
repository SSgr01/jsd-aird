package com.jsd.aird.kb.api;

import java.util.List;
import java.util.Optional;

public interface KnowledgeEmbeddingFacade {

    Optional<String> embedVector(String text);

    default List<Optional<String>> embedVectors(List<String> texts) {
        if (texts == null) return List.of();
        return texts.stream().map(this::embedVector).toList();
    }
}
