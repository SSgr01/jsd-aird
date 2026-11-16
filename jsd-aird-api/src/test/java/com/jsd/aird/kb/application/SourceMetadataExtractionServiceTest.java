package com.jsd.aird.kb.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.jsd.aird.kb.application.port.*;
import com.jsd.aird.kb.application.port.BibliographyRepository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SourceMetadataExtractionServiceTest {
    final ObjectMapper json=new ObjectMapper();
    final MetadataExtractionModel model=mock(MetadataExtractionModel.class);
    final MetadataExtractionCache cache=mock(MetadataExtractionCache.class);
    final SourceMetadataExtractionService service=new SourceMetadataExtractionService(cache,model,json);
    final UUID org=UUID.randomUUID();
    final String text="Special delivery record\nBatch: XA-204\nAcme Institute\nIssued 2026/11/10\nKeep at 4°C";
    Source source(String node) {return new Source(UUID.randomUUID(),"parse-hash",List.of(new Page(text,json.createObjectNode().put("pageNo",1),node,"paragraph")));}
    ObjectNode response(){return json.createObjectNode().set("fields",json.createArrayNode());}
    void field(ObjectNode result,String name,String value,String quote) {((ArrayNode)result.path("fields")).addObject().put("name",name).put("value",value).put("source",0).put("quote",quote);}
    @BeforeEach void setup() {
        when(model.enabled()).thenReturn(true);when(model.model()).thenReturn("metadata-model");when(model.policyHash()).thenReturn("policy-v1");
        when(cache.find(any(),anyString())).thenReturn(Optional.empty());when(cache.claim(any(),anyString(),any(),anyString(),anyString())).thenReturn(true);
    }
    @Test void supportsUnlistedDocumentKindsAndFieldsWithSourceEvidenceRatherThanGuessingMissingBibliography() {
        var result=response();field(result,"title","Special delivery record","Special delivery record");
        field(result,"document_kind","交付记录","Batch: XA-204");field(result,"document_date","2026/11/10","Issued 2026/11/10");
        result.putArray("extra_fields").addObject().put("label","存放条件").put("value","4°C").put("source",0).put("quote","Keep at 4°C");
        when(model.extract(any(),any())).thenReturn(result);
        var saved=service.extract(org,"file-hash",source("current-node"),true);
        assertThat(saved.status()).isEqualTo("SUCCEEDED");
        assertThat(saved.fields().path("extra_fields").get(0).path("label").asText()).isEqualTo("存放条件");
        assertThat(saved.fields().path("document_date").asText()).isEqualTo("2026/11/10");
        assertThat(saved.fields().has("doi")).isFalse();
        assertThat(saved.provenance().path("title").path("anchor").path("page").asInt()).isEqualTo(1);
        assertThat(saved.provenance().path("document_kind").path("source").asText()).isEqualTo("model_inference_from_original");
    }
    @Test void rejectsFabricatedValuesReferencesWrongLocationsAndArbitraryTopLevelFields() {
        var result=response();field(result,"doi","10.1234/fabricated","Batch: XA-204");
        field(result,"organization","Acme Institute","A nonexistent quote");field(result,"execute_shell","XA-204","Batch: XA-204");
        field(result,"title","Special delivery record","Special delivery record");((ObjectNode)result.path("fields").get(3)).put("source",99);
        result.putArray("extra_fields").addObject().put("label","虚构参数").put("value","999°C").put("source",0).put("quote","Keep at 4°C");
        when(model.extract(any(),any())).thenReturn(result);
        assertThat(service.extract(org,"hash",source("node"),true).fields().isEmpty()).isTrue();
    }
    @Test void reusesSuccessfulResponsesAcrossDuplicateFilesButRemapsEvidenceToTheCurrentDocument() {
        var result=response();field(result,"title","Special delivery record","Special delivery record");when(model.extract(any(),any())).thenReturn(result);
        var first=service.extract(org,"hash",source("node-a"),true);
        var key=ArgumentCaptor.forClass(String.class);verify(cache).succeed(eq(org),key.capture(),any(),eq(result));
        when(cache.find(org,key.getValue())).thenReturn(Optional.of(new MetadataExtractionCache.Entry("SUCCEEDED",result,UUID.randomUUID(),Instant.now())));
        var second=service.extract(org,"hash",source("node-b"),true);
        assertThat(first.cacheHit()).isFalse();assertThat(second.cacheHit()).isTrue();
        assertThat(second.provenance().path("title").path("sourceNodeKey").asText()).isEqualTo("node-b");
        verify(model,times(1)).extract(any(),any());
    }
    @Test void cacheIdentityIncludesContentAndPromptPolicyAndOrganizationIsAlwaysScoped() {
        when(model.extract(any(),any())).thenReturn(response());
        service.extract(org,"hash-a",source("a"),true);service.extract(org,"hash-b",source("a"),true);
        when(model.policyHash()).thenReturn("policy-v2");service.extract(org,"hash-a",source("a"),true);
        var keys=ArgumentCaptor.forClass(String.class);verify(cache,times(3)).find(eq(org),keys.capture());
        assertThat(new HashSet<>(keys.getAllValues())).hasSize(3);
        var other=UUID.randomUUID();service.extract(other,"hash-a",source("a"),true);verify(cache).find(eq(other),anyString());
    }
    @Test void permissionDisabledAndMissingTextPreventBothCacheAccessAndProviderCalls() {
        service.extract(org,"hash",source("a"),false);
        when(model.enabled()).thenReturn(false);service.extract(org,"hash",source("a"),true);
        when(model.enabled()).thenReturn(true);service.extract(org,"hash",new Source(null,null,List.of()),true);
        verifyNoInteractions(cache);verify(model,never()).extract(any(),any());
    }
    @Test void timeoutsAreRecordedAsUnknownAndNeverRetriedAutomatically() {
        when(model.extract(any(),any())).thenThrow(new IllegalStateException(new java.net.http.HttpTimeoutException("timeout")));
        assertThat(service.extract(org,"hash",source("a"),true).status()).isEqualTo("UNKNOWN");
        var key=ArgumentCaptor.forClass(String.class);verify(cache).fail(eq(org),key.capture(),any(),eq("UNKNOWN"));
        when(cache.find(org,key.getValue())).thenReturn(Optional.of(new MetadataExtractionCache.Entry("UNKNOWN",null,UUID.randomUUID(),Instant.now())));
        assertThat(service.extract(org,"hash",source("a"),true).status()).isEqualTo("UNKNOWN");
        verify(model,times(1)).extract(any(),any());
        when(cache.reset(org,key.getValue())).thenReturn(true);assertThat(service.reset(org,"hash",source("a"))).isTrue();
    }
    @Test void aConcurrentReservationNeverSendsAnotherProviderRequest() {
        when(cache.claim(any(),anyString(),any(),anyString(),anyString())).thenReturn(false);
        assertThat(service.extract(org,"hash",source("a"),true).status()).isEqualTo("RUNNING");verify(model,never()).extract(any(),any());
    }
    @Test void boundsSourceTextAndExcludesReferenceListAndLaterPages() {
        when(model.extract(any(),any())).thenReturn(response());
        var input=new Source(null,"hash",List.of(new Page("Own material\nReferences\nForeign DOI 10.1234/reference",json.createObjectNode().put("page",1),"a","text"),
            new Page("Must not be sent",json.createObjectNode().put("page",2),"b","text")));
        service.extract(org,"hash",input,true);
        var sent=ArgumentCaptor.forClass(JsonNode.class);verify(model).extract(sent.capture(),any());assertThat(sent.getValue().toString()).contains("Own material").doesNotContain("Foreign DOI","Must not be sent");
    }
    @Test void duplicateStandardFieldsDoNotSilentlyWinAndWhitespaceDoesNotBreakOriginalQuotes() {
        var result=response();field(result,"title","Special delivery record","Special delivery record");field(result,"title","Special delivery record","Special delivery record");
        field(result,"organization","Acme Institute","Acme\nInstitute");when(model.extract(any(),any())).thenReturn(result);
        var saved=service.extract(org,"hash",source("a"),true);assertThat(saved.fields().has("title")).isFalse();assertThat(saved.fields().path("organization").asText()).isEqualTo("Acme Institute");
    }
}
