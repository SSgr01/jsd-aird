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
    @Test void distantTableRowsKeepTheirOwnHeadersAcrossPagesAndReplay() {
        var mapper = new ObjectMapper(); var repo = mock(KnowledgeRepository.class);
        var governance = mock(KnowledgeGovernanceService.class); var authorization = mock(AuthorizationService.class);
        var codec = spy(new StructuredDocumentCodec(mapper));
        var ref = new ResearchKnowledgeFacade.DocumentRef(UUID.randomUUID(), UUID.randomUUID());
        var view = mock(KnowledgeGovernanceRepository.PublishedContentView.class);
        var publication = mock(KnowledgeGovernanceRepository.PublicationRow.class);
        when(publication.documentId()).thenReturn(ref.document_id()); when(publication.versionId()).thenReturn(UUID.randomUUID());
        when(view.publication()).thenReturn(publication); when(view.originalName()).thenReturn("study.pdf");
        when(view.sourceNodes()).thenReturn(List.of()); when(view.excludedReviewNodeIds()).thenReturn(List.of());
        var document = mapper.createObjectNode().put("type", "doc"); var content = document.putArray("content");
        var rowKeys = new ArrayList<String>();
        for (int tableNo = 0; tableNo < 2; tableNo++) {
            var table = content.addObject().put("type", "table").putArray("content");
            for (int index = 0; index < 7; index++) {
                var id = UUID.randomUUID().toString(); rowKeys.add(id);
                var row = table.addObject().put("type", "tableRow");
                // Identical parser group/coordinates must not join two tables.
                var attrs = row.putObject("attrs").put("reviewNodeId", id).put("tableGroup", "reused-group").put("pageNo", 6);
                attrs.putArray("sourceNodeKeys").add(UUID.randomUUID().toString());
                var cells = row.putArray("content");
                for (String value : index == 0 ? List.of("mol% A", "Hardness A", "wt% B", "Hardness B") : List.of("1", "2H", "2.2", "H")) {
                    cells.addObject().put("type", index == 0 ? "tableHeader" : "tableCell").putArray("content")
                        .addObject().put("type", "paragraph").putArray("content").addObject().put("type", "text").put("text", value);
                }
            }
        }
        when(view.confirmedDocument()).thenReturn(document);
        when(governance.publishedContent(ref.document_id(),ref.publication_id())).thenReturn(view);
        when(repo.isAiApproved(any(),eq(ref.document_id()))).thenReturn(true);
        var service = new ResearchKnowledgeService(repo,governance,codec,(com.jsd.aird.kb.api.KnowledgeSearchFacade)null,authorization,mapper);
        ActorContext.set(new Actor(UUID.randomUUID(),UUID.randomUUID(),"test"));
        try {
            var row = service.read(ref,6,1).candidates().getFirst();
            assertThat(row.text()).isEqualTo("1 | 2H | 2.2 | H");
            assertThat(row.table_context().path("header_node_keys").get(0).asText()).isEqualTo(rowKeys.getFirst());
            var first = service.context(row.source(),0,0,0);
            assertThat(first.candidates().getFirst().source().source_node_key()).isEqualTo(rowKeys.getFirst());
            assertThat(first.next_offset()).isEqualTo(5);
            var last = service.context(row.source(),0,0,5);
            assertThat(last.candidates()).hasSize(2);
            assertThat(last.candidates().getLast().source()).isEqualTo(row.source());
            assertThat(last.next_offset()).isNull();
            var replay = service.replay(List.of(new ResearchKnowledgeFacade.Handle(row.source(),1,6,null))).getFirst();
            assertThat(replay.table_context()).isEqualTo(row.table_context());
            assertThat(replay.source()).isEqualTo(row.source());
            var firstRows = service.read(ref,0,2).candidates();
            clearInvocations(governance,codec);
            service.validateAll(firstRows.stream().map(candidate -> new ResearchKnowledgeFacade.SourceExcerpt(candidate.source(),candidate.text())).toList());
            verify(governance,times(1)).publishedContent(ref.document_id(),ref.publication_id());
            verify(codec,times(1)).project(any(),any());
            assertThatThrownBy(() -> service.validateAll(List.of(
                new ResearchKnowledgeFacade.SourceExcerpt(firstRows.get(0).source(),firstRows.get(0).text()),
                new ResearchKnowledgeFacade.SourceExcerpt(row.source(),"different row"))))
                .isInstanceOf(com.jsd.aird.shared.error.ApiException.class);
            var wrongVersion = new ResearchKnowledgeFacade.SourceRef(row.source().kind(),row.source().document_id(),UUID.randomUUID().toString(),
                row.source().publication_id(),row.source().source_node_key(),row.source().content_sha256(),row.source().anchor(),row.source().title(),null);
            assertThatThrownBy(() -> service.validateAll(List.of(new ResearchKnowledgeFacade.SourceExcerpt(wrongVersion,row.text()))))
                .isInstanceOf(com.jsd.aird.shared.error.ApiException.class);
            when(view.excludedReviewNodeIds()).thenReturn(List.of(UUID.fromString(rowKeys.getFirst())));
            assertThat(service.read(ref,5,1).candidates().getFirst().table_context().path("header_node_keys")).isEmpty();
            doThrow(new IllegalStateException("revoked")).when(authorization).require(any());
            assertThatThrownBy(()->service.context(row.source(),0,0,0)).hasMessage("revoked");
            assertThatThrownBy(()->service.validateAll(List.of(new ResearchKnowledgeFacade.SourceExcerpt(row.source(),row.text())))).hasMessage("revoked");
        } finally {ActorContext.clear();}
    }

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
