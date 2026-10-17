package com.jsd.aird.ai.application.port;

import com.jsd.aird.ai.application.operation.OperationManual.Chunk;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface OperationManualIndexRepository {
    boolean ready(IndexKey key);
    /** Returns false when another instance owns the version lock. */
    boolean synchronize(IndexKey key, List<Chunk> chunks, Vectorizer vectorizer);
    void failed(IndexKey key, String reason);
    List<VectorHit> search(IndexKey key, String queryVector, Set<String> allowedSectionIds, int limit);

    record IndexKey(String manualVersion, String model, int dimension) { }
    record VectorHit(String chunkId, double score) { }
    @FunctionalInterface
    interface Vectorizer { List<Optional<String>> embed(List<String> texts); }
}
