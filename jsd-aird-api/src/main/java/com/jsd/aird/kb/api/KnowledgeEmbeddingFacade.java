package com.jsd.aird.kb.api;

import java.util.Optional;

public interface KnowledgeEmbeddingFacade {

    Optional<String> embedVector(String text);
}
