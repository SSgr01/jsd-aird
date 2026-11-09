package com.jsd.aird.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.*;
import com.jsd.aird.kb.application.*;
import com.jsd.aird.kb.application.port.*;
import com.jsd.aird.kb.domain.LexicalAnalyzer;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.shared.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ResearchNavigationTest {
    @Test void historicalSearchAndContextStayPinnedAndReauthorize() {
        var mapper=new ObjectMapper();
        var repo=mock(KnowledgeRepository.class); var governance=mock(KnowledgeGovernanceService.class);
        var codec=mock(StructuredDocumentCodec.class); var analyzer=mock(LexicalAnalyzer.class);
        var authorization=mock(AuthorizationService.class);
        var provider=mock(ObjectProvider.class);
        var ref=new ResearchKnowledgeFacade.DocumentRef(UUID.randomUUID(),UUID.randomUUID());
        var view=mock(KnowledgeGovernanceRepository.PublishedContentView.class);
        var publication=mock(KnowledgeGovernanceRepository.PublicationRow.class);
        when(publication.documentId()).thenReturn(ref.document_id()); when(publication.versionId()).thenReturn(UUID.randomUUID());
        when(view.publication()).thenReturn(publication); when(view.originalName()).thenReturn("study.pdf");
        when(view.confirmedDocument()).thenReturn(mapper.createObjectNode()); when(view.excludedReviewNodeIds()).thenReturn(List.of());
        when(view.sourceNodes()).thenReturn(List.of());
        when(governance.publishedContent(ref.document_id(),ref.publication_id())).thenReturn(view);
        when(repo.isAiApproved(any(),eq(ref.document_id()))).thenReturn(true);
        var current=mock(KnowledgeRepository.DocumentRow.class);
        when(current.currentPublicationId()).thenReturn(UUID.randomUUID());
        when(repo.findDocument(any(),eq(ref.document_id()))).thenReturn(Optional.of(current));
        var wrong=mock(KnowledgeRepository.SearchRow.class);
        when(wrong.documentId()).thenReturn(ref.document_id());when(wrong.chunkId()).thenReturn(UUID.randomUUID());
        when(wrong.versionId()).thenReturn(UUID.randomUUID());when(wrong.content()).thenReturn("Current-only wrong value");
        when(repo.bm25Search(any(),anyList(),eq(true),anyList(),anySet(),eq(48))).thenReturn(List.of(wrong));
        when(analyzer.analyzeCompatibleQuery(any())).thenReturn(List.of());
        var nodes=new ArrayList<StructuredDocumentCodec.ProjectedNode>();
        for(int i=0;i<5;i++) nodes.add(new StructuredDocumentCodec.ProjectedNode(UUID.randomUUID(),List.of(UUID.randomUUID()),i==2?"Frequency sweep 0.1 to 100 rad/s":i==1?"Frequency analysis":"Neighbor "+i,"paragraph",List.of(),Map.of()));
        when(codec.project(any(),any())).thenReturn(new StructuredDocumentCodec.Projection("",nodes));
        var service=new ResearchKnowledgeService(repo,governance,codec,analyzer,provider,authorization,mapper,1024);
        ActorContext.set(new Actor(UUID.randomUUID(),UUID.randomUUID(),"synthetic","USER"));
        try {
            var all=service.search(List.of(ref),"frequency sweep",0,"all");
            assertThat(all.candidates()).hasSize(1); assertThat(all.candidates().getFirst().node_offset()).isEqualTo(2);
            assertThat(all.candidates().getFirst().source().publication_id()).isEqualTo(ref.publication_id().toString());
            assertThat(service.search(List.of(ref),"frequency sweep",0,"any").candidates()).hasSize(2);
            assertThat(service.search(List.of(ref),"frequency sweep",0,"phrase").candidates()).hasSize(1);
            var context=service.context(all.candidates().getFirst().source(),1,1,0);
            assertThat(context.candidates()).hasSize(3);
            assertThat(context.candidates().stream().filter(c->"MATCH".equals(c.role())).count()).isEqualTo(1);
            assertThat(context.trace().get("fulltext_coverage")).isEqualTo(false);
            verify(repo, never()).bm25Search(any(),anyList(),anyBoolean(),anyList(),anySet(),anyInt());
            verifyNoInteractions(provider);
            var pinnedVersion=publication.versionId();
            when(repo.researchDocuments(any(),eq(0),eq(501))).thenReturn(List.of(new KnowledgeRepository.ResearchDocument(ref.document_id(),ref.publication_id(),pinnedVersion,"study.pdf")));
            when(authorization.check(any())).thenReturn(new com.jsd.aird.iam.api.PermissionDecision(true,"knowledge.view","ALLOW","ALL","test"));
            var window=service.discoverRecall("frequency",null,"auto",new ResearchKnowledgeFacade.RecallOptions(true,true,true,48));
            service.validateWindow(null,(String)window.trace().get("window_revision"));
            when(repo.researchDocuments(any(),eq(0),eq(501))).thenReturn(List.of(new KnowledgeRepository.ResearchDocument(ref.document_id(),UUID.randomUUID(),pinnedVersion,"study.pdf")));
            assertThatThrownBy(()->service.validateWindow(null,(String)window.trace().get("window_revision"))).hasMessageContaining("SNAPSHOT_CHANGED");
            doThrow(new IllegalStateException("revoked")).when(authorization).require(any());
            assertThatThrownBy(()->service.context(all.candidates().getFirst().source(),1,1,0)).hasMessage("revoked");
        } finally {ActorContext.clear();}
    }
}
