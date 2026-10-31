package com.jsd.aird.kb.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Version-pinned research reads using the existing published-document authority. */
public interface ResearchKnowledgeFacade {
    record DocumentRef(UUID document_id, UUID publication_id) { }
    record SourceRef(String kind, String document_id, String version_id, String publication_id,
                     String source_node_key, String content_sha256, JsonNode anchor, String title, String url) { }
    record Candidate(String text, SourceRef source, double score) { }
    record Result(List<Candidate> candidates, Map<String, Object> trace, Integer next_offset, String next_cursor) {
        public Result(List<Candidate> candidates, Map<String, Object> trace, Integer next_offset) {
            this(candidates, trace, next_offset, null);
        }
    }
    void authorize(List<DocumentRef> documents);
    Result search(List<DocumentRef> documents, String query, int offset);
    Result discover(String query, int offset, String cursor);
    Result read(DocumentRef document, int offset, int limit);
    Object table(DocumentRef document, UUID tableId, int offset);
    void validate(SourceRef source, String excerpt);
    default SourceRef indexedSource(KnowledgeSearchFacade.SearchHit hit) {
        throw new UnsupportedOperationException("Indexed source projection is not implemented");
    }
}
