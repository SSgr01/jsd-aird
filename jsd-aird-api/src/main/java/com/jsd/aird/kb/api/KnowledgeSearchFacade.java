package com.jsd.aird.kb.api;

import java.util.List;
import java.util.UUID;

public interface KnowledgeSearchFacade {

    List<SearchHit> search(UUID organizationId, String query, boolean aiOnly, int limit);

    SearchResult search(SearchRequest request);

    record SearchRequest(UUID organizationId, String query, boolean aiOnly, int limit,
                         List<UUID> scopeIds, List<String> queryVariants) {
        public SearchRequest {
            scopeIds = scopeIds == null ? List.of() : List.copyOf(scopeIds);
            queryVariants = queryVariants == null ? List.of() : List.copyOf(queryVariants);
        }
    }

    record SearchResult(List<SearchHit> hits, RetrievalTrace trace) { }

    record RetrievalTrace(String strategy, int bm25Candidates, int vectorCandidates, int mergedCandidates,
                          List<String> fallbacks) { }

    record SearchHit(
            UUID chunkId,
            UUID documentId,
            UUID versionId,
            String title,
            String originalName,
            Integer pageNo,
            String section,
            String content,
            double score,
            double retrievalScore,
            double rrfScore,
            double rerankScore,
            String sourceType,
            UUID scopeId,
            Integer rowNumber,
            String fieldCode,
            String sourceLocator
    ) {
        public SearchHit(UUID chunkId, UUID documentId, UUID versionId, String title, String originalName,
                         Integer pageNo, String section, String content, double score) {
            this(chunkId, documentId, versionId, title, originalName, pageNo, section, content, score,
                    score, score, score, "KNOWLEDGE_CHUNK", null, null, null, null);
        }

        public SearchHit withScores(double retrieval, double rrf, double rerank) {
            return new SearchHit(chunkId, documentId, versionId, title, originalName, pageNo, section, content,
                    rerank, retrieval, rrf, rerank, sourceType, scopeId, rowNumber, fieldCode, sourceLocator);
        }
    }
}
