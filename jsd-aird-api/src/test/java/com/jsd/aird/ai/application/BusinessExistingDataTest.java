package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.mdm.api.AgentProjectFacade;
import com.jsd.aird.rnd.api.AgentExperimentFacade;
import com.jsd.aird.shared.error.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessExistingDataTest {
    private final ObjectMapper json=new ObjectMapper();
    private final AgentProjectFacade projects=mock(AgentProjectFacade.class);
    private final AgentExperimentFacade experiments=mock(AgentExperimentFacade.class);
    private final SharedRetrievalService retrieval=mock(SharedRetrievalService.class);
    private final BusinessExistingData service=new BusinessExistingData(projects,experiments,retrieval,json);
    private static UUID id(long n){return new UUID(0,n);}
    private ObjectNode object(String text)throws Exception{return (ObjectNode)json.readTree(text);}
    private ObjectNode candidate(long n){
        var source=json.createObjectNode().put("resource_id",id(n).toString()).put("version_id","1");
        return json.createObjectNode().put("text","隔离项目"+n).set("source",source);
    }
    @Test void nativePagesSupportWithinPageOffsetsWithoutSkippingOrRepeatingRecords()throws Exception{
        when(projects.read(eq("search_projects"),any())).thenAnswer(call->{
            ObjectNode request=call.getArgument(1);int offset=request.path("offset").asInt();
            var result=json.createObjectNode();var rows=result.putArray("candidates");
            for(int i=offset;i<Math.min(31,offset+20);i++)rows.add(candidate(i+1));
            if(offset+20<31)result.put("next_offset",offset+20);return result;
        });
        var result=service.invoke("business_search",object("{\"type\":\"PROJECT\",\"offset\":19,\"limit\":3}"));
        assertThat(result.path("candidates").size()).isEqualTo(3);
        for(int i=0;i<3;i++)assertThat(result.path("candidates").path(i).path("ref").path("id").asText()).isEqualTo(id(i+20).toString());
        assertThat(result.path("next_offset").asInt()).isEqualTo(22);
        verify(projects).read(eq("search_projects"),argThat(a->a.path("offset").asInt()==0));
        verify(projects).read(eq("search_projects"),argThat(a->a.path("offset").asInt()==20));
    }
    @Test void unsupportedFiltersAreRejectedBeforeCallingExistingReaders()throws Exception{
        assertThatThrownBy(()->service.invoke("business_search",object("{\"type\":\"PROJECT\",\"filters\":{\"status\":\"ACTIVE\"}}"))).hasMessageContaining("不支持");
        assertThatThrownBy(()->service.invoke("business_query",object("{\"type\":\"PROJECT\"}"))).isInstanceOf(ApiException.class);
        verifyNoInteractions(projects,experiments,retrieval);
    }
    @Test void projectMaterialsReturnRequiredAndAllowedFiltersWithoutReadingRecords() throws Exception {
        for (String request : List.of("{\"type\":\"MATERIAL\",\"query\":\"UA-1117\"}", "{\"type\":\"MATERIAL\"}")) {
            assertThatThrownBy(() -> service.invoke("business_search", object(request)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    var detail = json.valueToTree(error.detail());
                    assertThat(detail.path("code").asText()).isEqualTo("INVALID_TOOL_ARGUMENTS");
                    assertThat(detail.path("required_filters").toString()).isEqualTo("[\"project_id\"]");
                    assertThat(detail.path("allowed_filters").toString()).isEqualTo("[\"project_id\"]");
                    assertThat(detail.path("path").path(0).asText()).isEqualTo("filters");
                    assertThat(detail.path("rule").asText()).isIn("required", "unsupported_filter");
                    assertThat(detail.toString()).doesNotContain("UA-1117");
                });
        }
        verifyNoInteractions(projects, experiments, retrieval);
    }
    @Test void structuredSearchUsesActualQueryTermsAndRechecksTheExactSourceOnRead()throws Exception{
        when(retrieval.structured(any())).thenAnswer(call->{
            var result=json.createObjectNode();var source=json.createObjectNode().put("resource_id",id(1).toString()).put("version_id",id(2).toString())
                .put("source_node_key",id(3).toString()).put("content_sha256","a".repeat(64)).put("title","隔离研发.xlsx");
            result.putArray("candidates").add(json.createObjectNode().put("record_key","A01").put("field","温度").put("text","25 °C").set("source",source));return result;
        });
        var result=service.invoke("business_search",object("{\"type\":\"STRUCTURED_DATA\",\"query\":\"A01\",\"filters\":{\"file_name\":\"隔离研发.xlsx\"}}"));
        verify(retrieval).structured(argThat(a->a.path("record_terms").path(0).asText().equals("A01")));
        var ref=(ObjectNode)result.path("candidates").path(0).path("ref");
        var read=service.invoke("business_read",json.createObjectNode().set("ref",ref));
        assertThat(read.path("candidates").path(0).path("text").asText()).isEqualTo("25 °C");
        ref.put("content_hash","b".repeat(64));
        assertThatThrownBy(()->service.invoke("business_read",json.createObjectNode().set("ref",ref))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.errorCode()).isEqualTo(ApiErrorCode.VERSION_CONFLICT));
    }
    @Test void experimentReadRetainsTheReturnedVersionAndDetailedPageOptions()throws Exception{
        when(experiments.read(eq("get_experiment_record"),any())).thenReturn(object("{\"candidates\":[{\"text\":\"样品A零值\",\"source\":{\"resource_id\":\""+id(1)+"\",\"version_id\":\""+id(2)+"\"}}]}"));
        var args=object("{\"ref\":{\"type\":\"EXPERIMENT\",\"id\":\""+id(1)+"\",\"version_id\":\""+id(2)+"\"},\"section\":\"testResults\",\"sample_key\":\"A\",\"offset\":3,\"limit\":4}");
        service.invoke("business_read",args);
        verify(experiments).read(eq("get_experiment_record"),argThat(a->a.path("version_id").asText().equals(id(2).toString())&&a.path("offset").asInt()==3&&a.path("limit").asInt()==4&&a.path("sample_key").asText().equals("A")));
    }
    @Test void draftChangesWithinTheSameVersionInvalidateTheFrozenRead()throws Exception{
        var returned=candidate(1);((ObjectNode)returned.path("source")).put("version_id",id(2).toString()).putObject("anchor").put("version_hash","changed");
        when(experiments.read(eq("get_experiment_record"),any())).thenReturn(json.createObjectNode().set("candidates",json.createArrayNode().add(returned)));
        var ref=json.createObjectNode().put("type","EXPERIMENT").put("id",id(1).toString()).put("version_id",id(2).toString()).put("version_hash","first");
        assertThatThrownBy(()->service.invoke("business_read",json.createObjectNode().set("ref",ref)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.errorCode()).isEqualTo(ApiErrorCode.VERSION_CONFLICT));
        ref.put("version_hash","changed");
        assertThat(service.invoke("business_read",json.createObjectNode().set("ref",ref)).path("candidates").path(0).path("ref").path("version_hash").asText()).isEqualTo("changed");
    }
}
