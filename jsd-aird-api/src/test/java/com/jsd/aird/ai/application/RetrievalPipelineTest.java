package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.application.port.RerankerProvider;
import com.jsd.aird.kb.api.*;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.shared.error.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class RetrievalPipelineTest {
    final ObjectMapper mapper=new ObjectMapper();
    final UUID doc=UUID.randomUUID(), pub=UUID.randomUUID(), version=UUID.randomUUID();
    final QueryRewriteService rewrite=mock(QueryRewriteService.class);
    final RerankerProvider ranker=mock(RerankerProvider.class);
    final ResearchKnowledgeFacade knowledge=mock(ResearchKnowledgeFacade.class);

    ResearchKnowledgeFacade.Candidate candidate(int i) {
        var source=new ResearchKnowledgeFacade.SourceRef("KB_PUBLICATION",doc.toString(),version.toString(),pub.toString(),UUID.randomUUID().toString(),
            com.jsd.aird.ai.api.EvidenceProtocol.sha("value "+i),mapper.createObjectNode(),"study.pdf",null);
        return new ResearchKnowledgeFacade.Candidate("value "+i,source,100-i,i,"MATCH");
    }
    @Test void ranksWholeWindowBeforePagingAndReplaysWithoutRecallRewriteOrRerank() {
        var values=new ArrayList<ResearchKnowledgeFacade.Candidate>();for(int i=0;i<30;i++)values.add(candidate(i));
        var scope=mapper.createObjectNode().put("scope_mode","SELECTED");scope.putArray("documents").add(mapper.valueToTree(new ResearchKnowledgeFacade.DocumentRef(doc,pub)));
        when(knowledge.recall(anyList(),anyString(),eq("auto"),any())).thenReturn(new ResearchKnowledgeFacade.Recall(
            List.of(new ResearchKnowledgeFacade.RecallChannel("BM25",1,values)),Map.of("literal_only",false),null));
        when(knowledge.parents(anyList(),anyInt(),anyInt(),anyInt())).thenReturn(new ResearchKnowledgeFacade.Result(List.of(),Map.of("parent_status","MISSING"),null));
        when(knowledge.replay(anyList())).thenAnswer(call->{
            List<ResearchKnowledgeFacade.Handle> handles=call.getArgument(0);
            return handles.stream().map(h->values.stream().filter(c->c.source().equals(h.source())).findFirst().orElseThrow()).toList();
        });
        when(ranker.isConfigured()).thenReturn(true);
        when(ranker.rerank(anyString(),anyList())).thenAnswer(call->{
            List<RerankerProvider.RankCandidate> input=call.getArgument(1);
            return new RerankerProvider.RerankOutcome(List.of(new RerankerProvider.RankedDocument(input.get(20).id(),.01,0,"test","test")),"SUCCEEDED","test","test",200,"",1,input.size(),1);
        });
        var pipeline=new RetrievalPipeline(new RagRetrievalService(null,null,rewrite,ranker),new ContextCompressionService());pipeline.sources(knowledge,null);
        ActorContext.set(new Actor(UUID.randomUUID(),UUID.randomUUID(),"test","USER"));
        try {
            var args=mapper.createObjectNode().put("query","frequency");
            var first=pipeline.research(scope,args,false);
            assertThat(first.path("candidates").get(0).path("node_offset").asInt()).isEqualTo(20);
            assertThat(first.path("candidates").size()).isEqualTo(12);
            assertThat(first.path("ranking_snapshot").path("handles").get(0).has("text")).isFalse();
            args.put("offset",12).set("ranking_snapshot",first.path("ranking_snapshot"));
            var second=pipeline.research(scope,args,false);
            var ids=new HashSet<String>();first.path("candidates").forEach(c->ids.add(c.path("source").path("source_node_key").asText()));
            second.path("candidates").forEach(c->assertThat(ids.add(c.path("source").path("source_node_key").asText())).isTrue());
            assertThat(second.path("trace").path("rerank_calls").asInt()).isZero();
            verify(knowledge,times(1)).recall(anyList(),anyString(),anyString(),any());verify(ranker,times(1)).rerank(anyString(),anyList());verifyNoInteractions(rewrite);
            ((com.fasterxml.jackson.databind.node.ObjectNode)args.path("ranking_snapshot")).put("policy_hash","stale");
            assertThatThrownBy(()->pipeline.research(scope,args,false)).isInstanceOf(ApiException.class).hasMessageContaining("SNAPSHOT_CHANGED");
        } finally {ActorContext.clear();}
    }

    @Test void exactLexicalSearchSkipsModelsAndKeepsAllResultsBeyondTopK() {
        var values=new ArrayList<ResearchKnowledgeFacade.Candidate>();for(int i=0;i<80;i++)values.add(candidate(i));
        when(knowledge.recall(anyList(),anyString(),eq("phrase"),any())).thenReturn(new ResearchKnowledgeFacade.Recall(
            List.of(new ResearchKnowledgeFacade.RecallChannel("CANONICAL",1,values)),Map.of("literal_only",true),null));
        when(knowledge.parents(anyList(),anyInt(),anyInt(),anyInt())).thenReturn(new ResearchKnowledgeFacade.Result(List.of(),Map.of(),null));
        var pipeline=new RetrievalPipeline(new RagRetrievalService(null,null,rewrite,ranker),new ContextCompressionService());pipeline.sources(knowledge,null);
        var scope=mapper.createObjectNode().put("scope_mode","SELECTED");scope.putArray("documents").add(mapper.valueToTree(new ResearchKnowledgeFacade.DocumentRef(doc,pub)));
        ActorContext.set(new Actor(UUID.randomUUID(),UUID.randomUUID(),"test","USER"));
        try {
            var result=pipeline.research(scope,mapper.createObjectNode().put("query","0.1 rad/s").put("match_mode","phrase").put("offset",72),false);
            assertThat(result.path("candidates").size()).isEqualTo(8);assertThat(result.path("next_offset").isNull()).isTrue();
            assertThat(result.path("trace").path("candidate_count").asInt()).isEqualTo(80);verifyNoInteractions(rewrite,ranker);
        } finally {ActorContext.clear();}
    }

    @Test void rejectsUnauthorizedQaScopeBeforeQueryModelsOrRecall() {
        var pipeline=new RetrievalPipeline(new RagRetrievalService(null,null,rewrite,ranker),new ContextCompressionService());pipeline.sources(knowledge,null);
        when(knowledge.authorizedSearchDocuments()).thenThrow(new ApiException(com.jsd.aird.shared.error.ApiErrorCode.PERMISSION_DENIED));
        assertThatThrownBy(()->pipeline.retrieve(UUID.randomUUID(),"question",List.of(),List.of(UUID.randomUUID()),List.of(),false,
            com.jsd.aird.data.api.DataSourceFileSearchFacade.RetrievalAccessScope.denied())).isInstanceOf(ApiException.class);
        verifyNoInteractions(rewrite,ranker);
    }

    @Test void nativeAgentQueriesNeverUseTheSeparatelyConfiguredRewriteModel() {
        var kb = mock(KnowledgeSearchFacade.class);
        var data = mock(com.jsd.aird.data.api.DataSourceFileSearchFacade.class);
        when(kb.search(any(KnowledgeSearchFacade.SearchRequest.class))).thenReturn(
            new KnowledgeSearchFacade.SearchResult(List.of(), new KnowledgeSearchFacade.RetrievalTrace("test", 0, 0, 0, List.of())));
        var query = "目标材料的测试条件";
        var plan = new QueryRewriteService.QueryPlan(query, query, List.of(), List.of(), List.of(), Map.of(), "", List.of());
        when(rewrite.original(eq(query), anyList(), eq(false))).thenReturn(new QueryRewriteService.Result(plan, "ORIGINAL", "", false));
        var pipeline = new RetrievalPipeline(new RagRetrievalService(kb, data, rewrite, ranker), new ContextCompressionService());
        for (var categories : List.of(List.<UUID>of(), List.of(UUID.randomUUID()))) {
            var result = pipeline.retrieveAgentQuery(UUID.randomUUID(), query, List.of(), categories, List.of(), false,
                com.jsd.aird.data.api.DataSourceFileSearchFacade.RetrievalAccessScope.denied());
            assertThat(result.knowledgeHits()).isEmpty();
            assertThat(result.trace().rewriteStatus()).isEqualTo("ORIGINAL");
        }
        verify(rewrite, times(2)).original(eq(query), anyList(), eq(false));
        verify(rewrite, never()).rewrite(anyString(), anyList(), anyBoolean());
    }
}
