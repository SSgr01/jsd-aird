package com.jsd.aird.ai.application;

import com.jsd.aird.ai.api.EvidenceProtocol;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Startup-only, trusted configuration. Model tool arguments cannot override these policies. */
@Component
public final class RetrievalPolicy {
    public record Profile(boolean rewrite, boolean rerank, boolean bm25, boolean vector, boolean metadata) { }
    public final Profile qa, research;
    public final int rrfK, rerankTop, pageSize, parentCount, parentChars, parentBudget;
    public final boolean parents;
    private String dependencies="standalone";
    @org.springframework.beans.factory.annotation.Autowired
    void dependencies(@Value("${app.ai.embedding.model:}") String embedding,
            @Value("${app.ai.embedding.dimension:1024}") int dimension,
            @Value("${app.ai.reranker.model:}") String reranker,
            @Value("${app.ai.reranker.timeout:8s}") String rerankTimeout,
            @Value("${app.ai.query-rewrite.model:}") String rewriteModel,
            @Value("${app.ai.query-rewrite.timeout:8s}") String rewriteTimeout,
            @Value("${app.ai.retrieval.data-max-hits:32}") int dataLimit) {
        dependencies=EvidenceProtocol.sha(String.join("\n",embedding,Integer.toString(dimension),reranker,rerankTimeout,rewriteModel,rewriteTimeout,Integer.toString(dataLimit)));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public RetrievalPolicy(
        @Value("${app.ai.retrieval.profiles.qa.rewrite-enabled:${app.ai.query-rewrite.enabled:true}}") boolean qaRewrite,
        @Value("${app.ai.retrieval.profiles.research.rewrite-enabled:false}") boolean researchRewrite,
        @Value("${app.ai.retrieval.profiles.qa.rerank-enabled:true}") boolean qaRerank,
        @Value("${app.ai.retrieval.profiles.research.rerank-enabled:true}") boolean researchRerank,
        @Value("${app.ai.retrieval.profiles.qa.bm25-enabled:true}") boolean qaBm25,
        @Value("${app.ai.retrieval.profiles.qa.vector-enabled:true}") boolean qaVector,
        @Value("${app.ai.retrieval.profiles.qa.metadata-enabled:true}") boolean qaMetadata,
        @Value("${app.ai.retrieval.profiles.research.bm25-enabled:true}") boolean researchBm25,
        @Value("${app.ai.retrieval.profiles.research.vector-enabled:true}") boolean researchVector,
        @Value("${app.ai.retrieval.profiles.research.metadata-enabled:true}") boolean researchMetadata,
        @Value("${app.ai.retrieval.rrf-k:60}") int rrfK,
        @Value("${app.ai.retrieval.rerank-top:30}") int rerankTop,
        @Value("${app.ai.retrieval.research-page-size:12}") int pageSize,
        @Value("${app.ai.retrieval.parent.enabled:true}") boolean parents,
        @Value("${app.ai.retrieval.parent.max-count:3}") int parentCount,
        @Value("${app.ai.retrieval.parent.preview-chars:2000}") int parentChars,
        @Value("${app.ai.retrieval.parent.max-chars:6000}") int parentBudget) {
        if(rrfK<1||rerankTop<1||rerankTop>100||pageSize<1||pageSize>30||parentCount<0||parentCount>10||parentChars<1||parentChars>6000||parentBudget<0||parentBudget>18000)
            throw new IllegalArgumentException("Invalid retrieval policy limits");
        qa=new Profile(qaRewrite,qaRerank,qaBm25,qaVector,qaMetadata);
        research=new Profile(researchRewrite,researchRerank,researchBm25,researchVector,researchMetadata);
        this.rrfK=rrfK;this.rerankTop=rerankTop;this.pageSize=pageSize;this.parents=parents;
        this.parentCount=parentCount;this.parentChars=parentChars;this.parentBudget=parentBudget;
    }
    public RetrievalPolicy() { this(true,false,true,true,true,true,true,true,true,true,60,30,12,true,3,2000,6000); }
    public Map<String,Object> describe() {
        var values=new TreeMap<String,Object>();
        values.put("qa",qa);values.put("research",research);values.put("rrf_k",rrfK);values.put("rerank_top",rerankTop);
        values.put("page_size",pageSize);values.put("parent_enabled",parents);values.put("parent_count",parentCount);
        values.put("parent_chars",parentChars);values.put("parent_budget",parentBudget);
        values.put("dependencies",dependencies);
        return values;
    }
    public String fingerprint() {
        try { return EvidenceProtocol.sha(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(describe())); }
        catch(Exception error) {throw new IllegalStateException(error);}
    }
}
