package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.kb.api.ScholarlyLiteratureFacade;
import com.jsd.aird.kb.application.port.*;
import com.jsd.aird.kb.application.port.BibliographyRepository.Record;
import com.jsd.aird.ops.application.port.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class BibliographyOcrRecoveryTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final UUID org=UUID.randomUUID(),actor=UUID.randomUUID(),doc=UUID.randomUUID(),version=UUID.randomUUID(),fileId=UUID.randomUUID();
    private final BibliographyRepository repo=mock(BibliographyRepository.class);
    private final KnowledgeRepository kb=mock(KnowledgeRepository.class);
    private final FileStorageFacade files=mock(FileStorageFacade.class);
    private final AuthorizationService auth=mock(AuthorizationService.class);
    private final ScholarlyLiteratureFacade scholarly=mock(ScholarlyLiteratureFacade.class);
    private SourceMetadataExtractionService fallback;
    private Record previous;

    private byte[] pdf(String title,String doi) throws Exception {
        try(var pdf=new PDDocument();var output=new ByteArrayOutputStream()) {
            var page=new PDPage();pdf.addPage(page);
            try(var stream=new PDPageContentStream(pdf,page)) {
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(40,740);
                for(var line:List.of(title,"Jane Smith","COMMUNICATION","DOI: "+doi,"Abstract","An experimental study.")) {
                    stream.showText(line);stream.newLineAtOffset(0,-20);
                }
                stream.endText();
            }
            pdf.save(output);return output.toByteArray();
        }
    }
    private Record process(String originalTitle,String originalDoi) throws Exception {
        return process(originalTitle,originalDoi,"Jane Smith");
    }
    private Record process(String originalTitle,String originalDoi,String author) throws Exception {
        return process(originalTitle,originalDoi,author,"");
    }
    private Record process(String originalTitle,String originalDoi,String author,String ocrFooter) throws Exception {
        return process(originalTitle,originalDoi,author,ocrFooter,"");
    }
    private Record process(String originalTitle,String originalDoi,String author,String ocrFooter,String prefix) throws Exception {
        var task=new BibliographyRepository.Task(UUID.randomUUID(),org,actor,Instant.now());
        var item=new BibliographyRepository.Item(UUID.randomUUID(),task.id(),doc,version,"PENDING",0,0,null,null);
        when(repo.task(org,actor,task.id())).thenReturn(Optional.of(task));when(repo.item(task.id(),item.id())).thenReturn(Optional.of(item));
        int revision=previous==null?0:previous.revision();
        when(repo.find(org,version,null)).thenReturn(Optional.ofNullable(previous));when(repo.save(eq(org),eq(actor),any(),eq(revision))).thenReturn(true);
        String ocr=prefix+"Research Article\nFlexible Gel Devices CH3NH3Pb|3\nJane Smith\nDOI: 10.1234/polymer\nAbstract\nAn experimental study."+ocrFooter;
        when(repo.source(org,doc,version)).thenReturn(new BibliographyRepository.Source(UUID.randomUUID(),"parsed-hash",
            List.of(new BibliographyRepository.Page(ocr,json.createObjectNode().put("page",1),"source-1","heading"))));
        var row=mock(KnowledgeRepository.VersionRow.class);when(row.id()).thenReturn(version);when(row.documentId()).thenReturn(doc);
        when(row.status()).thenReturn("READY");when(row.fileObjectId()).thenReturn(fileId);when(row.contentType()).thenReturn("application/pdf");
        var document=mock(KnowledgeRepository.DocumentRow.class);when(document.currentVersionId()).thenReturn(version);when(document.lifecycleStatus()).thenReturn("ACTIVE");
        when(kb.findVersion(org,version)).thenReturn(Optional.of(row));when(kb.findDocument(org,doc)).thenReturn(Optional.of(document));
        when(auth.check(any())).thenReturn(new PermissionDecision(true,"test","ALLOW","ALL","TEST"));
        var result=json.createObjectNode();result.putArray("results").addObject().put("id","https://openalex.org/W123")
            .put("title","Flexible Gel Devices CH3NH3PbI3").put("doi","10.1234/polymer").putArray("authors").addObject().put("name",author);
        when(scholarly.lookup(any())).thenReturn(result);
        var bytes=pdf(originalTitle,originalDoi);
        when(files.open(org,fileId)).thenReturn(new FileStorageFacade.StoredFile(fileId,"paper.pdf","application/pdf",bytes.length,"original-file-hash",new ByteArrayInputStream(bytes)));
        var transactions=mock(PlatformTransactionManager.class);when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var service=new BibliographyService(repo,kb,new BibliographyExtractor(json),scholarly,auth,mock(OpsAsyncFacade.class),mock(AuditLogFacade.class),files,json,transactions);
        if(fallback!=null)service.metadataModel(fallback);
        service.process(json.createObjectNode().put("organizationId",org.toString()).put("actorId",actor.toString()).put("taskId",task.id().toString())
            .put("itemId",item.id().toString()).put("generation",0),null);
        var captured=ArgumentCaptor.forClass(Record.class);verify(repo).save(eq(org),eq(actor),captured.capture(),eq(revision));return captured.getValue();
    }
    @Test void recoversAnOcrTitleFromTheSameAuthorizedOriginalPdfAndRecordsItsLocation() throws Exception {
        var record=process("Flexible Gel Devices CH3NH3PbI3","10.1234/polymer");
        assertThat(record.status()).isEqualTo("ACCEPTED");
        var location=record.provenance().path("title").path("localVerification");
        assertThat(location.path("source").asText()).isEqualTo("original_pdf_text");
        assertThat(location.path("anchor").path("page").asInt()).isEqualTo(1);
        assertThat(location.path("anchor").path("fileObjectId").asText()).isEqualTo(fileId.toString());
        verify(scholarly,times(1)).lookup(any());
    }
    @Test void doesNotAcceptAnotherTitleJustBecauseDoiMatches() throws Exception {
        assertThat(process("Another Completely Different Material","10.1234/polymer").status()).isEqualTo("NEEDS_REVIEW");
    }
    @Test void doesNotCombineOneFilesTitleWithAnotherDoi() throws Exception {
        assertThat(process("Flexible Gel Devices CH3NH3PbI3","10.1234/another").status()).isEqualTo("NEEDS_REVIEW");
    }
    @Test void requiresAuthorSupportInTheOriginalWhenTheCandidateListsAuthors() throws Exception {
        assertThat(process("Flexible Gel Devices CH3NH3PbI3","10.1234/polymer","Someone Else").status()).isEqualTo("NEEDS_REVIEW");
    }
    @Test void resolvesConflictingOcrDoiReadingsFromTheOriginalBeforeLookingUpACandidate() throws Exception {
        var record=process("Flexible Gel Devices CH3NH3PbI3","10.1234/polymer","Jane Smith","\nDOI: 10.1234/po1ymer");
        assertThat(record.status()).isEqualTo("ACCEPTED");
        assertThat(record.provenance().path("doi").path("localVerification").path("source").asText()).isEqualTo("original_pdf_text");
        verify(scholarly).lookup(argThat(q->"10.1234/polymer".equals(q.path("query").asText())));
    }
    @Test void revokedAccessPreventsBothExternalLookupAndOpeningTheOriginal() {
        doThrow(new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.OPERATION_FORBIDDEN)).when(auth).require(any());
        assertThatThrownBy(()->process("Flexible Gel Devices CH3NH3PbI3","10.1234/polymer")).isInstanceOf(com.jsd.aird.shared.error.ApiException.class);
        verifyNoInteractions(files,scholarly);
    }
    @ParameterizedTest
    @ValueSource(strings={"材料研发会议纪要","产品说明书：树脂A","Technical Data Sheet: Resin A"})
    void businessDocumentsDoNotQueryExternalPapersOrReopenOriginals(String title) throws Exception {
        var record=process("Flexible Gel Devices CH3NH3PbI3","10.1234/polymer","Jane Smith","","# "+title+"\n引用资料：\n");
        assertThat(record.documentType()).isEqualTo("INTERNAL");
        assertThat(record.status()).isEqualTo("NEEDS_REVIEW");
        assertThat(record.accepted().path("title").asText()).isEqualTo(title);
        assertThat(record.accepted().has("doi")).isFalse();
        verifyNoInteractions(scholarly,files);
    }
    @Test void verifiedBibliographySkipsTheModelEntirely() throws Exception {
        fallback=mock(SourceMetadataExtractionService.class);when(fallback.enabled()).thenReturn(true);
        assertThat(process("Flexible Gel Devices CH3NH3PbI3","10.1234/polymer").status()).isEqualTo("ACCEPTED");
        verify(fallback,never()).extract(any(),any(),any(),anyBoolean());
    }
    @Test void incompleteOriginalMetadataUsesTheModelButPreservesManualEditsAndItsReceipt() throws Exception {
        fallback=mock(SourceMetadataExtractionService.class);when(fallback.enabled()).thenReturn(true);
        var fields=json.createObjectNode().put("title","Model title");fields.putArray("extra_fields").addObject().put("label","检验标准").put("value","ABC-123");
        var origins=json.createObjectNode();origins.set("title",json.createObjectNode().put("source","model_extraction_from_original"));
        origins.set("extra_fields",json.createObjectNode().put("source","model_extraction_from_original"));
        when(fallback.extract(eq(org),any(),any(),eq(true))).thenReturn(new SourceMetadataExtractionService.Result(fields,origins,"SUCCEEDED",true));
        previous=new Record(doc,version,3,"NEEDS_REVIEW","UNKNOWN",json.createObjectNode().put("title","Manually edited title"),
            json.createObjectNode().set("title",json.createObjectNode().put("source","manual")),json.createArrayNode(),json.createArrayNode().add("title"),null,null,null);
        var saved=process("Different original title","10.1234/another");
        assertThat(saved.accepted().path("title").asText()).isEqualTo("Manually edited title");
        assertThat(saved.accepted().path("extra_fields").get(0).path("value").asText()).isEqualTo("ABC-123");
        assertThat(saved.provenance().path("_model_extraction").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(saved.status()).isEqualTo("NEEDS_REVIEW");verify(fallback).extract(eq(org),any(),any(),eq(true));
    }
    @Test void manuallyConfirmedDocumentsDoNotSpendAnotherModelCall() throws Exception {
        fallback=mock(SourceMetadataExtractionService.class);when(fallback.enabled()).thenReturn(true);
        previous=new Record(doc,version,1,"ACCEPTED","INTERNAL",json.createObjectNode().put("title","Confirmed title"),json.createObjectNode(),json.createArrayNode(),json.createArrayNode().add("title").add("document_type"),null,null,null);
        process("Different original title","10.1234/another");verify(fallback,never()).extract(any(),any(),any(),anyBoolean());
    }
    @Test void manualConfirmationStillSkipsTheModelAfterASeparateTaskMarksTheRecordForReview() throws Exception {
        fallback=mock(SourceMetadataExtractionService.class);when(fallback.enabled()).thenReturn(true);
        previous=new Record(doc,version,1,"NEEDS_REVIEW","INTERNAL",json.createObjectNode().put("title","Confirmed title"),json.createObjectNode(),json.createArrayNode(),json.createArrayNode().add("title").add("document_type"),null,null,null);
        process("Different original title","10.1234/another");verify(fallback,never()).extract(any(),any(),any(),anyBoolean());
    }
}
