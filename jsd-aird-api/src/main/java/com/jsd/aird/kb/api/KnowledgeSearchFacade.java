package com.jsd.aird.kb.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public interface KnowledgeSearchFacade {

    List<SearchHit> search(UUID organizationId, String query, boolean aiOnly, int limit);

    SearchResult search(SearchRequest request);

    record SearchRequest(UUID organizationId, String query, boolean aiOnly, int limit,
                          List<UUID> categoryIds, List<String> queryVariants,
                          List<SearchTerm> retrievalTerms, List<RequiredFact> requiredFacts,
                          String originalQuery) {
        public SearchRequest {
            categoryIds = categoryIds == null ? List.of() : List.copyOf(categoryIds);
            queryVariants = queryVariants == null ? List.of() : List.copyOf(queryVariants);
            retrievalTerms = retrievalTerms == null ? List.of() : List.copyOf(retrievalTerms);
            requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
            originalQuery = originalQuery == null ? query : originalQuery;
        }

        public SearchRequest(UUID organizationId, String query, boolean aiOnly, int limit,
                             List<UUID> categoryIds, List<String> queryVariants) {
            this(organizationId, query, aiOnly, limit, categoryIds, queryVariants,
                    List.of(), List.of(), query);
        }
    }

    record SearchTerm(String text, List<String> aliases, String kind) {
        public SearchTerm {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    record RequiredFact(String label, String retrievalQuery) { }

    record SearchResult(List<SearchHit> hits, RetrievalTrace trace, List<FactCandidateSet> factCandidates) {
        public SearchResult {
            hits = hits == null ? List.of() : List.copyOf(hits);
            factCandidates = factCandidates == null ? List.of() : List.copyOf(factCandidates);
        }

        public SearchResult(List<SearchHit> hits, RetrievalTrace trace) {
            this(hits, trace, List.of());
        }
    }

    record FactCandidateSet(String label, String retrievalQuery, List<UUID> chunkIds) {
        public FactCandidateSet {
            chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
        }
    }

    record RetrievalTrace(String strategy, int bm25Candidates, int vectorCandidates, int mergedCandidates,
                          int phraseCandidates, List<String> fallbacks, List<QueryTrace> queries) {
        public RetrievalTrace {
            fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
            queries = queries == null ? List.of() : List.copyOf(queries);
        }

        public RetrievalTrace(String strategy, int bm25Candidates, int vectorCandidates, int mergedCandidates,
                              List<String> fallbacks) {
            this(strategy, bm25Candidates, vectorCandidates, mergedCandidates, 0, fallbacks, List.of());
        }
    }

    record QueryTrace(String query, String channel, String status, int resultCount, long elapsedMs,
                      String error, List<UUID> topChunkIds) {
        public QueryTrace {
            topChunkIds = topChunkIds == null ? List.of() : List.copyOf(topChunkIds);
        }
    }

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
            Integer rowNumber,
            String fieldCode,
            String sourceLocator,
            Integer chunkNo,
            JsonNode anchor,
            List<JsonNode> anchors,
            List<UUID> reviewNodeIds,
            List<UUID> sourceNodeKeys
    ) {
        public SearchHit(UUID chunkId, UUID documentId, UUID versionId, String title, String originalName,
                         Integer pageNo, String section, String content, double score) {
            this(chunkId, documentId, versionId, title, originalName, pageNo, section, content, score,
                    score, score, score, "KNOWLEDGE_CHUNK", null, null, null, -1,
                    null, List.of(), List.of(), List.of());
        }

        public SearchHit withScores(double retrieval, double rrf, double rerank) {
            return new SearchHit(chunkId, documentId, versionId, title, originalName, pageNo, section, content,
                    rerank, retrieval, rrf, rerank, sourceType, rowNumber, fieldCode, sourceLocator, chunkNo,
                    anchor, anchors, reviewNodeIds, sourceNodeKeys);
        }
    }
}
