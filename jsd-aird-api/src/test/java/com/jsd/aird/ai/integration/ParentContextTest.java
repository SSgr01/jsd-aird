package com.jsd.aird.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.*;
import com.jsd.aird.kb.application.*;
import com.jsd.aird.kb.application.port.*;
import com.jsd.aird.kb.domain.LexicalAnalyzer;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.shared.evidence.EvidenceProtocol;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ParentContextTest {
    @Test void batchDeduplicatesParentsAndValidatesExactUnicodeSpansWithoutIncreasingCoverage() {
        var mapper=new ObjectMapper();var repo=mock(KnowledgeRepository.class);var governance=mock(KnowledgeGovernanceService.class);
        var auth=mock(AuthorizationService.class);var embeddings=mock(ObjectProvider.class);
        var service=new ResearchKnowledgeService(repo,governance,mock(StructuredDocumentCodec.class),mock(LexicalAnalyzer.class),embeddings,auth,mapper,1024);
        var org=UUID.randomUUID();var doc=UUID.randomUUID();var pub=UUID.randomUUID();var version=UUID.randomUUID();var revision=UUID.randomUUID();
        var parentId=UUID.randomUUID();var child1=UUID.randomUUID();var child2=UUID.randomUUID();
        var body="Results: tensile strength 12 kPa";
        var parentText="Conditions: 25 °C, 24 h.\n"+"🧪".repeat(550)+body+"\n"+"尾".repeat(4000);
        var childText="Section\n\n"+body;
        var row1=new KnowledgeRepository.ParentChunk(child1,parentId,doc,version,pub,revision,"study.pdf",parentText,childText);
        var row2=new KnowledgeRepository.ParentChunk(child2,parentId,doc,version,pub,revision,"study.pdf",parentText,"Section\n\nother result");
        when(repo.loadParents(eq(org),anyList())).thenReturn(List.of(row1,row2));when(repo.parentAtPublication(org,parentId,pub)).thenReturn(Optional.of(row1));
        when(repo.isAiApproved(org,doc)).thenReturn(true);
        var view=mock(KnowledgeGovernanceRepository.PublishedContentView.class);var publication=mock(KnowledgeGovernanceRepository.PublicationRow.class);
        when(view.publication()).thenReturn(publication);when(publication.documentId()).thenReturn(doc);when(publication.versionId()).thenReturn(version);
        when(governance.publishedContent(doc,pub)).thenReturn(view);
        var c1=new ResearchKnowledgeFacade.SourceRef("KB_PUBLICATION",doc.toString(),version.toString(),pub.toString(),child1.toString(),EvidenceProtocol.sha(childText),mapper.createObjectNode().put("chunkId",child1.toString()),"study.pdf",null);
        var c2=new ResearchKnowledgeFacade.SourceRef("KB_PUBLICATION",doc.toString(),version.toString(),pub.toString(),child2.toString(),EvidenceProtocol.sha(row2.childContent()),mapper.createObjectNode().put("chunkId",child2.toString()),"study.pdf",null);
        ActorContext.set(new Actor(org,UUID.randomUUID(),"test","USER"));
        try {
            var result=service.parents(List.of(c1,c2),3,2000,6000);
            assertThat(result.candidates()).isEmpty();assertThat(result.context_candidates()).hasSize(1);
            verify(repo,times(1)).loadParents(eq(org),argThat(refs->refs.size()==2));
            var parent=result.context_candidates().getFirst();var anchor=parent.source().anchor();
            int start=anchor.path("span_start").asInt(),end=anchor.path("span_end").asInt();
            assertThat(parent.text()).isEqualTo(parentText.substring(parentText.offsetByCodePoints(0,start),parentText.offsetByCodePoints(0,end)));
            assertThat(parent.text().codePointCount(0,parent.text().length())).isEqualTo(2000);
            assertThat(parent.source().content_sha256()).isEqualTo(EvidenceProtocol.sha(parent.text())).isNotEqualTo(c1.content_sha256());
            assertThat(anchor.path("parent_content_sha256").asText()).isEqualTo(EvidenceProtocol.sha(parentText));
            assertThat(result.trace().get("fulltext_coverage")).isEqualTo(false);
            var links=(Map<?,?>)result.trace().get("parent_links");assertThat((List<?>)links.values().iterator().next()).hasSize(2);
            var childHit=new KnowledgeSearchFacade.SearchHit(child1,doc,version,"study.pdf","study.pdf",1,"Results",childText,1);
            var qa=new com.jsd.aird.ai.application.RagRetrievalService.Retrieval(null,List.of(childHit),List.of(),null,List.of(),null,
                List.of(parent),Map.of(child1,c1),Map.of());
            var pipeline=new com.jsd.aird.ai.application.RetrievalPipeline(null,new com.jsd.aird.ai.application.ContextCompressionService());
            var compressed=pipeline.compress(qa,18000);
            assertThat(compressed.text()).contains("PARENT_CONTEXT",parent.text(),body);
            assertThat(compressed.characterCount()).isLessThanOrEqualTo(18000);
            var shared=new com.jsd.aird.ai.application.SharedRetrievalService(pipeline,null,service,null,null,mapper);
            var candidates=shared.preparedCandidates(qa,compressed.evidenceRefs());
            assertThat(candidates.size()).isEqualTo(2);
            assertThat(candidates.get(1).path("evidence_id").asText()).isNotEqualTo(candidates.get(0).path("evidence_id").asText());
            service.validate(parent.source(),body);
            var next=service.parentContext(parent.source(),end,2000);assertThat(next.candidates().getFirst().node_offset()).isEqualTo(end);
            var forged=new ResearchKnowledgeFacade.SourceRef("KB_PUBLICATION",doc.toString(),version.toString(),pub.toString(),parentId.toString(),"invalid",anchor,"study.pdf",null);
            assertThatThrownBy(()->service.validate(forged,"")).hasMessageContaining("哈希");
            when(repo.loadParents(eq(org),anyList())).thenReturn(List.of(new KnowledgeRepository.ParentChunk(child1,parentId,UUID.randomUUID(),version,pub,revision,"study.pdf",parentText,childText)));
            assertThatThrownBy(()->service.parents(List.of(c1),3,2000,6000)).hasMessageContaining("来源版本");
            doThrow(new IllegalStateException("revoked")).when(auth).require(any());
            assertThatThrownBy(()->service.parentContext(parent.source(),0,2000)).hasMessage("revoked");
            verifyNoInteractions(embeddings);
        } finally {ActorContext.clear();}
    }
}
