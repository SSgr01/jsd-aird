package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.kb.api.*;
import com.jsd.aird.kb.application.port.*;
import com.jsd.aird.kb.application.port.ParseRepairStore.Repair;
import com.jsd.aird.ops.application.port.*;
import com.jsd.aird.shared.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ParseRepairServiceTest {
    @Test void reusesOldZipWithoutRelabellingTierOrChangingDraftPublicationOrIndex() {
        var json=new ObjectMapper(); var store=mock(ParseRepairStore.class); var knowledge=mock(KnowledgeRepository.class);
        var governance=mock(KnowledgeGovernanceRepository.class); var auth=mock(AuthorizationService.class);
        var async=mock(OpsAsyncFacade.class); var files=mock(FileStorageFacade.class); var precise=mock(PreciseDocumentParser.class);
        var parser=mock(DocumentParser.class); var scanner=mock(FileSafetyScanner.class);
        var transactions=mock(PlatformTransactionManager.class); when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        UUID org=UUID.randomUUID(), actor=UUID.randomUUID(), doc=UUID.randomUUID(), version=UUID.randomUUID(), id=UUID.randomUUID(), zip=UUID.randomUUID();
        var row=new Repair(id,org,doc,version,actor,"key","QUEUED",null,null,json.createObjectNode(),null,UUID.randomUUID(),UUID.randomUUID(),7);
        when(store.find(org,id)).thenReturn(Optional.of(row));
        var source=mock(KnowledgeRepository.VersionRow.class);
        when(source.documentId()).thenReturn(doc); when(source.originalName()).thenReturn("paper.pdf"); when(source.contentType()).thenReturn("application/pdf");
        when(knowledge.findVersion(org,version)).thenReturn(Optional.of(source));
        var document=mock(KnowledgeRepository.DocumentRow.class); when(document.currentVersionId()).thenReturn(version); when(document.lifecycleStatus()).thenReturn("ACTIVE");
        when(knowledge.findDocument(org,doc)).thenReturn(Optional.of(document));
        when(store.previousResult(org,version)).thenReturn(Optional.of(json.createObjectNode().put("resultFileId",zip.toString()).put("tier","standard")));
        when(files.open(org,zip)).thenReturn(new com.jsd.aird.ops.api.FileStorageFacade.StoredFile(zip,"result.zip","application/zip",0,"hash",new java.io.ByteArrayInputStream(new byte[0])));
        var parsed=new DocumentParser.ParsedDocument(List.of(new DocumentParser.TextBlock(9,"listItem","20. Complete reference")),
                "v3","old-task",Map.of("tier","standard","repairSource","EXISTING_ZIP"));
        when(precise.adaptArchive(any(),eq("paper.pdf"),eq(zip),any(),anyMap())).thenReturn(parsed);
        var run=mock(KnowledgeGovernanceRepository.ParseRunRow.class); UUID runId=UUID.randomUUID(); when(run.id()).thenReturn(runId);
        when(governance.createCandidateParseRun(eq(org),eq(actor),eq(doc),eq(version),any(),any())).thenReturn(run);
        var service=new ParseRepairService(store,knowledge,governance,auth,async,files,json,List.of(parser),precise,scanner,transactions);
        service.handle(json.createObjectNode().put("organizationId",org.toString()).put("repairId",id.toString()));
        var result=ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(store).ready(eq(org),eq(id),eq(runId),result.capture());
        assertThat(result.getValue().path("metadata").path("tier").asText()).isEqualTo("standard");
        verify(governance,never()).createParseRun(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verifyNoInteractions(parser,scanner);
        verify(async,never()).enqueue(any(),any(),any(),any(),anyInt());
        // Adopting an outdated candidate refuses to overwrite a concurrent human edit/publication.
        var ready=new Repair(id,org,doc,version,actor,"key","READY",null,runId,json.valueToTree(parsed),null,row.basePublicationId(),row.baseRevisionId(),7);
        when(store.find(org,id)).thenReturn(Optional.of(ready)); when(store.lockUnchanged(ready)).thenReturn(false);
        ActorContext.set(new Actor(org,actor,"tester"));
        try { assertThatThrownBy(() -> service.adopt(id)).hasMessageContaining("变化"); }
        finally { ActorContext.clear(); }
        verify(governance,never()).createParseRun(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }
}
