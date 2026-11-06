package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StructuredAgentDataTest {
    final ObjectMapper json = new ObjectMapper();
    final RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
    final DataSearchAuthorizationService access = mock(DataSearchAuthorizationService.class);
    final AgentSourceAuthorization sources = mock(AgentSourceAuthorization.class);
    final DataSourceFileSearchFacade data = mock(DataSourceFileSearchFacade.class);
    final SharedRetrievalService service = new SharedRetrievalService(pipeline,access,null,null,sources,json);
    final Actor actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "synthetic");
    final UUID file = UUID.randomUUID(), owner = UUID.randomUUID();

    @BeforeEach void setup() {
        ActorContext.set(actor); service.data(data);
        when(access.requireRetrievalScopes(actor)).thenReturn(DataSourceFileSearchFacade.RetrievalAccessScope.all());
    }
    @AfterEach void clear() { ActorContext.clear(); }

    DataSourceFileSearchFacade.SourceFileHit hit(String field, String value, String unit) {
        return new DataSourceFileSearchFacade.SourceFileHit(UUID.randomUUID(),file,owner,2,field,"synthetic.xlsx",
            "记录A " + field + "=" + value + " " + unit,1.0,"DATA_CENTER:Sheet:A2","A",field,field,value,unit,"NUMBER","Sheet","A2");
    }

    @Test void exactReaderPreservesZeroAndContextSourcesWithoutAnotherModelQuery() {
        var target = hit("强度","0","MPa"); var condition = hit("温度","25","°C");
        var detail = new DataSourceFileSearchFacade.DataDetailResult(DataSourceFileSearchFacade.DataQueryMode.RECORD_DETAIL,
            DataSourceFileSearchFacade.DataResolution.RESOLVED,file,owner,"synthetic.xlsx",1,2,List.of(target),false,List.of(),false,
            List.of(new DataSourceFileSearchFacade.RecordContext("A","Sheet",List.of(target),List.of(condition),false,false,"测试条件不完整")),"VALUE");
        when(data.queryDetails(eq(actor.organizationId()),any(),eq(List.of()),any(DataSourceFileSearchFacade.RetrievalAccessScope.class))).thenReturn(detail);
        var args = json.createObjectNode().put("file_name","synthetic.xlsx"); args.putArray("record_terms").add("A");
        var result = service.structured(args);
        assertThat(result.path("candidates").size()).isEqualTo(2);
        assertThat(result.path("candidates").get(0).path("value").asText()).isEqualTo("0");
        assertThat(result.path("candidates").get(0).path("unit").asText()).isEqualTo("MPa");
        assertThat(result.path("candidates").get(1).path("source").path("source_node_key").asText()).isEqualTo(condition.hitId().toString());
        assertThat(result.path("records").get(0).path("comparable").asBoolean()).isFalse();
        verify(sources,times(2)).one(any()); verifyNoInteractions(pipeline);
        doThrow(new ApiException(ApiErrorCode.PERMISSION_DENIED)).when(sources).one(any());
        assertThatThrownBy(()->service.structured(args)).isInstanceOf(ApiException.class);
    }

    @Test void ambiguousFilesRemainUnresolvedAndDoNotInventRecordResults() {
        var detail = new DataSourceFileSearchFacade.DataDetailResult(DataSourceFileSearchFacade.DataQueryMode.RECORD_DETAIL,
            DataSourceFileSearchFacade.DataResolution.AMBIGUOUS,null,null,"",0,0,List.of(),false,
            List.of(new DataSourceFileSearchFacade.DataFileCandidate("A.xlsx"),new DataSourceFileSearchFacade.DataFileCandidate("B.xlsx")),false);
        when(data.queryDetails(eq(actor.organizationId()),any(),eq(List.of()),any(DataSourceFileSearchFacade.RetrievalAccessScope.class))).thenReturn(detail);
        var result = service.structured(json.createObjectNode());
        assertThat(result.path("needs_input").asBoolean()).isTrue();
        assertThat(result.path("candidates").isEmpty()).isTrue();
        assertThat(result.path("file_candidates").size()).isEqualTo(2);
        verifyNoInteractions(pipeline,sources);
    }
}
