package com.jsd.aird.kb.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.kb.application.port.*;
import com.jsd.aird.shared.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentMetadataTest {
    final ObjectMapper json = new ObjectMapper();
    final UUID org=UUID.randomUUID(), doc=UUID.randomUUID(), version=UUID.randomUUID(), pub=UUID.randomUUID();
    final KnowledgeRepository kb=mock(KnowledgeRepository.class);
    final KnowledgeGovernanceService governance=mock(KnowledgeGovernanceService.class);
    final AuthorizationService auth=mock(AuthorizationService.class);
    final BibliographyRepository books=mock(BibliographyRepository.class);
    final com.jsd.aird.kb.api.KnowledgeSearchFacade recall=mock(com.jsd.aird.kb.api.KnowledgeSearchFacade.class);

    ResearchKnowledgeService service() {
        var service=new ResearchKnowledgeService(kb,governance,mock(StructuredDocumentCodec.class),recall,auth,json);
        service.bibliography(books);
        var view=mock(KnowledgeGovernanceRepository.PublishedContentView.class);
        var publication=mock(KnowledgeGovernanceRepository.PublicationRow.class);
        when(view.publication()).thenReturn(publication); when(publication.documentId()).thenReturn(doc);
        when(publication.versionId()).thenReturn(version); when(governance.publishedContent(doc,pub)).thenReturn(view);
        when(kb.isAiApproved(org,doc)).thenReturn(true);
        return service;
    }
    ResearchKnowledgeFacade.SourceRef source(Integer revision) {
        var anchor=json.createObjectNode();if(revision!=null)anchor.put("bibliography_revision",revision);
        return new ResearchKnowledgeFacade.SourceRef("KB_PUBLICATION",doc.toString(),version.toString(),pub.toString(),
            UUID.randomUUID().toString(),"a".repeat(64),anchor,"original.pdf",null);
    }
    @Test void batchesDeduplicatesAndReadsThePinnedAcceptedRevisionWithoutChangingEvidence() {
        var service=service();var source=source(2);var key=new BibliographyRepository.Lookup(version,2);
        var accepted=json.createObjectNode().put("title","Verified title").put("venue","Journal").put("abstract","Bibliographic abstract");
        accepted.putArray("authors").addObject().put("name","Actual Author");
        var record=new BibliographyRepository.Record(doc,version,2,"PARTIAL","PAPER",accepted,json.createObjectNode(),
            json.createArrayNode().addObject().put("title","Unaccepted candidate"),json.createArrayNode(),null,null,null);
        when(books.findAll(org,List.of(key))).thenReturn(Map.of(key,record));
        ActorContext.set(new Actor(org,UUID.randomUUID(),"test"));
        try {
            var result=service.documentMetadata(List.of(source,source(2)));
            assertThat(result).hasSize(1);assertThat(result.getFirst().path("title").asText()).isEqualTo("Verified title");
            assertThat(result.getFirst().path("authors").get(0).path("name").asText()).isEqualTo("Actual Author");
            assertThat(result.getFirst().path("bibliography_revision").asInt()).isEqualTo(2);
            assertThat(result.getFirst().toString()).doesNotContain("Unaccepted candidate");
            assertThat(source.title()).isEqualTo("original.pdf");assertThat(source.content_sha256()).isEqualTo("a".repeat(64));
            verify(books).findAll(org,List.of(key)); verify(books,never()).find(any(),any(),any());
            assertThat(service.documentMetadata(List.of(source(null))).getFirst().has("authors")).isFalse();
            assertThatThrownBy(()->service.documentMetadata(List.of(source,source(3)))).isInstanceOf(RuntimeException.class);
        } finally {ActorContext.clear();}
    }
    @Test void revocationAndVersionMismatchFailBeforeBibliographyIsRead() {
        var service=service();ActorContext.set(new Actor(org,UUID.randomUUID(),"test"));
        try {
            when(kb.isAiApproved(org,doc)).thenReturn(false);
            assertThatThrownBy(()->service.documentMetadata(List.of(source(1)))).isInstanceOf(RuntimeException.class);
            verifyNoInteractions(books);
            when(kb.isAiApproved(org,doc)).thenReturn(true);
            doThrow(new IllegalStateException("revoked")).when(auth).require(any());
            assertThatThrownBy(()->service.documentMetadata(List.of(source(1)))).hasMessage("revoked");
            verifyNoInteractions(books);
        } finally {ActorContext.clear();}
    }
    @Test void lightweightMetadataRecallHydratesAuthoritativeAnchorsBeforeIssuingReusableHandles() {
        var service=service();var chunk=UUID.randomUUID();var sourceNode=UUID.randomUUID();
        var document=mock(KnowledgeRepository.DocumentRow.class);when(document.currentPublicationId()).thenReturn(pub);
        when(kb.findDocument(org,doc)).thenReturn(Optional.of(document));
        var anchor=new KnowledgeRepository.ChunkAnchorRow(1,null,null,null,List.of(),null,null,null,
            "{\"page\":1}","[]",List.of(),List.of(sourceNode));
        var row=new KnowledgeRepository.SearchRow(chunk,doc,version,"original.pdf","original.pdf",1,null,"Original text",1,0,anchor);
        when(kb.loadSearchRows(org,List.of(chunk))).thenReturn(List.of(row));when(kb.findChunkAnchor(org,chunk)).thenReturn(Optional.of(anchor));
        var light=new com.jsd.aird.kb.api.KnowledgeSearchFacade.SearchHit(chunk,doc,version,"original.pdf","original.pdf",1,null,"Original text",1);
        when(recall.recall(any())).thenReturn(new com.jsd.aird.kb.api.KnowledgeSearchFacade.RecallResult(
            List.of(new com.jsd.aird.kb.api.KnowledgeSearchFacade.RecallChannel("METADATA","query",1,List.of(light))),
            new com.jsd.aird.kb.api.KnowledgeSearchFacade.RetrievalTrace("test",0,0,1,List.of()),List.of()));
        ActorContext.set(new Actor(org,UUID.randomUUID(),"test"));
        try {
            var result=service.recall(List.of(new ResearchKnowledgeFacade.DocumentRef(doc,pub)),"query","auto",new ResearchKnowledgeFacade.RecallOptions(false,false,true,20));
            var candidate=result.channels().getFirst().candidates().getFirst();
            assertThat(candidate.source().anchor().path("sourceNodeKeys").get(0).asText()).isEqualTo(sourceNode.toString());
            assertThat(candidate.source().anchor().path("page").asInt()).isEqualTo(1);
            service.validate(candidate.source(),"Original text");
        } finally {ActorContext.clear();}
    }
}
