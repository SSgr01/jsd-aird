package com.jsd.aird.kb.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Version-pinned research reads using the existing published-document authority. */
public interface ResearchKnowledgeFacade {
    record DocumentRef(UUID document_id, UUID publication_id) { }
    record SourceRef(String kind, String document_id, String version_id, String publication_id,
                     String source_node_key, String content_sha256, JsonNode anchor, String title, String url,
                     @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String resource_type,
                     @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String resource_id) {
        public SourceRef(String kind,String document,String version,String publication,String node,String hash,JsonNode anchor,String title,String url) {
            this(kind,document,version,publication,node,hash,anchor,title,url,null,null);
        }
    }
    record Candidate(String text, SourceRef source, double score, Integer node_offset, String role,
                     @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) JsonNode table_context) {
        public Candidate(String text, SourceRef source, double score, Integer node_offset, String role) {
            this(text,source,score,node_offset,role,null);
        }
        public Candidate(String text, SourceRef source, double score) { this(text,source,score,null,null); }
    }
    record Handle(SourceRef source, double score, Integer node_offset, String role) { }
    record RecallChannel(String name, double weight, List<Candidate> candidates) { }
    record Recall(List<RecallChannel> channels, Map<String,Object> trace, String next_cursor) { }
    record RecallOptions(boolean bm25, boolean vector, boolean metadata, int limit) { }
    record Result(List<Candidate> candidates, Map<String, Object> trace, Integer next_offset, String next_cursor,
                  List<Candidate> context_candidates, List<Handle> ranking_snapshot, List<JsonNode> document_metadata) {
        public Result(List<Candidate> candidates, Map<String,Object> trace, Integer offset, String cursor,
                      List<Candidate> context, List<Handle> snapshot) {
            this(candidates, trace, offset, cursor, context, snapshot, List.of());
        }
        public Result(List<Candidate> candidates,Map<String,Object> trace,Integer offset,String cursor) {
            this(candidates,trace,offset,cursor,List.of(),List.of());
        }
        public Result(List<Candidate> candidates, Map<String, Object> trace, Integer next_offset) {
            this(candidates, trace, next_offset, null);
        }
    }
    void authorize(List<DocumentRef> documents);
    default void authorizeHistoricalVersion(UUID documentId, UUID versionId) { throw new UnsupportedOperationException("Historical authority unavailable"); }
    /** Accepted bibliography only, pinned to each evidence source's revision; never a full-text substitute. */
    default List<JsonNode> documentMetadata(List<SourceRef> sources) { return List.of(); }
    /** Null means an explicitly authorized ALL scope; an empty set denies all documents. */
    default java.util.Set<UUID> authorizedSearchDocuments() { throw new UnsupportedOperationException("Search authority unavailable"); }
    default Recall recall(List<DocumentRef> refs,String query,String mode,RecallOptions options) {
        var result=search(refs,query,0,mode);
        return new Recall(List.of(new RecallChannel("COMPAT",1,result.candidates())),result.trace(),result.next_cursor());
    }
    default Recall discoverRecall(String query,String cursor,String mode,RecallOptions options) {
        var result=discover(query,0,cursor,mode);
        return new Recall(List.of(new RecallChannel("COMPAT",1,result.candidates())),result.trace(),result.next_cursor());
    }
    default List<Candidate> replay(List<Handle> handles) { throw new UnsupportedOperationException(); }
    default void validateWindow(String cursor,String revision) { throw new UnsupportedOperationException("Window authority unavailable"); }
    default Result parents(List<SourceRef> children,int count,int chars,int budget) { return new Result(List.of(),Map.of("parent_status","UNAVAILABLE"),null); }
    default Result parentContext(SourceRef source,int cursor,int chars) { return new Result(List.of(),Map.of("parent_status","NOT_APPLICABLE"),null); }
    Result search(List<DocumentRef> documents, String query, int offset);
    Result discover(String query, int offset, String cursor);
    default Result discover(String query,int offset,String cursor,String mode) { return discover(query,offset,cursor); }
    default Result search(List<DocumentRef> refs,String query,int offset,String mode) { return search(refs,query,offset); }
    Result read(DocumentRef document, int offset, int limit);
    JsonNode outline(DocumentRef document,int offset);
    Result context(SourceRef source, int before, int after, int cursor);
    Object table(DocumentRef document, UUID tableId, int offset);
    void validate(SourceRef source, String excerpt);
    /** Authorized original figure or PDF page for a fixed evidence source. */
    default JsonNode image(SourceRef source, String view) { throw new UnsupportedOperationException("Source images unavailable"); }
    record SourceExcerpt(SourceRef source, String excerpt) { }
    /** One request-local authority snapshot per publication; every excerpt still checks its own identity. */
    default void validateAll(List<SourceExcerpt> excerpts) {
        for (var excerpt : excerpts) validate(excerpt.source(), excerpt.excerpt());
    }
    default SourceRef indexedSource(KnowledgeSearchFacade.SearchHit hit) {
        throw new UnsupportedOperationException("Indexed source projection is not implemented");
    }
    default Map<UUID,SourceRef> indexedSources(List<KnowledgeSearchFacade.SearchHit> hits) {
        var result=new java.util.LinkedHashMap<UUID,SourceRef>();
        for(var hit:hits)result.put(hit.chunkId(),indexedSource(hit));
        return result;
    }
}
