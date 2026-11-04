package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.kb.application.port.*;
import com.jsd.aird.kb.application.port.BibliographyRepository.Record;
import com.jsd.aird.shared.evidence.EvidenceProtocol;
import com.jsd.aird.shared.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BibliographyEvidenceTest {
    @Test void metadataRevisionsDoNotInvalidateHistoricalEvidenceAndRevocationAlwaysWins() {
        var json=new ObjectMapper();var kb=mock(KnowledgeRepository.class);var governance=mock(KnowledgeGovernanceService.class);
        var auth=mock(AuthorizationService.class);var bibliography=mock(BibliographyRepository.class);
        var service=new ResearchKnowledgeService(kb,governance,mock(StructuredDocumentCodec.class),null,auth,json);service.bibliography(bibliography);
        UUID org=UUID.randomUUID(),doc=UUID.randomUUID(),version=UUID.randomUUID(),pub=UUID.randomUUID(),parent=UUID.randomUUID(),child=UUID.randomUUID();
        var row=new KnowledgeRepository.ParentChunk(child,parent,doc,version,pub,UUID.randomUUID(),"original.pdf","Original text.","Original text.");
        when(kb.parentAtPublication(org,parent,pub)).thenReturn(Optional.of(row));when(kb.isAiApproved(org,doc)).thenReturn(true);
        var view=mock(KnowledgeGovernanceRepository.PublishedContentView.class);var publication=mock(KnowledgeGovernanceRepository.PublicationRow.class);
        when(view.publication()).thenReturn(publication);when(publication.documentId()).thenReturn(doc);when(publication.versionId()).thenReturn(version);when(governance.publishedContent(doc,pub)).thenReturn(view);
        var rev1=new Record(doc,version,1,"ACCEPTED","PAPER",json.createObjectNode().put("title","Verified title"),json.createObjectNode(),json.createArrayNode(),json.createArrayNode(),null,null,null);
        var rev2=new Record(doc,version,2,"ACCEPTED","PAPER",json.createObjectNode().put("title","Updated title"),rev1.provenance(),rev1.candidates(),rev1.manualFields(),null,null,null);
        when(bibliography.find(org,version,1)).thenReturn(Optional.of(rev1));when(bibliography.find(org,version,null)).thenReturn(Optional.of(rev2));
        var anchor=json.createObjectNode().put("parent_chunk_id",parent.toString()).put("parent_content_sha256",EvidenceProtocol.sha(row.content()))
            .put("span_start",0).put("span_end",14).put("offset_unit","unicode_codepoint");
        var legacy=new ResearchKnowledgeFacade.SourceRef("KB_PUBLICATION",doc.toString(),version.toString(),pub.toString(),parent.toString(),EvidenceProtocol.sha(row.content()),anchor,"original.pdf",null);
        var pinned=new ResearchKnowledgeFacade.SourceRef("KB_PUBLICATION",doc.toString(),version.toString(),pub.toString(),parent.toString(),EvidenceProtocol.sha(row.content()),anchor.deepCopy().put("bibliography_revision",1),"Verified title",null);
        ActorContext.set(new Actor(org,UUID.randomUUID(),"test"));
        try {
            service.validate(legacy,"Original");service.validate(pinned,"Original");
            var forged=new ResearchKnowledgeFacade.SourceRef("KB_PUBLICATION",doc.toString(),version.toString(),pub.toString(),parent.toString(),EvidenceProtocol.sha(row.content()),pinned.anchor(),"Updated title",null);
            assertThatThrownBy(()->service.validate(forged,"")).isInstanceOf(RuntimeException.class);
            doThrow(new IllegalStateException("permission revoked")).when(auth).require(any());
            assertThatThrownBy(()->service.validate(pinned,"Original")).hasMessage("permission revoked");
        } finally {ActorContext.clear();}
    }
}
