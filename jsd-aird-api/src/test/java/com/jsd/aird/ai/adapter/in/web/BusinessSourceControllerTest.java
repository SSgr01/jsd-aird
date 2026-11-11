package com.jsd.aird.ai.adapter.in.web;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ai.application.*;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessSourceControllerTest {
    private final ObjectMapper json=new ObjectMapper();
    private final BusinessDataService data=mock(BusinessDataService.class);
    private final BusinessComputeService compute=mock(BusinessComputeService.class);
    private final AuthorizationService authorization=mock(AuthorizationService.class);
    private final BusinessSourceController controller=new BusinessSourceController(data,compute,authorization,json);
    private final UUID id=UUID.randomUUID();
    @BeforeEach void setup(){ActorContext.set(new Actor(UUID.randomUUID(),UUID.randomUUID(),"isolated-source-acceptance"));}
    @AfterEach void clear(){ActorContext.clear();}
    private ObjectNode response(){
        var result=json.createObjectNode().put("snapshot_hash","current");
        result.putArray("candidates").addObject().put("text","固定版本原记录：0 MPa").putObject("source").put("version_id","current").putObject("anchor").put("path","/quality/records/"+id);
        return result;
    }
    @Test void recordLinkCannotSubstituteAnotherObjectAndRetainsPinnedRevision(){
        String ref=json.createObjectNode().put("type","QUALITY_RECORD").put("id",id.toString()).put("revision","v1").toString();
        assertThatThrownBy(()->controller.record("QUALITY_COA",id,ref)).isInstanceOf(ApiException.class);
        verifyNoInteractions(data);
        when(data.invoke(eq("business_read"),any())).thenReturn(response());
        var result=controller.record("QUALITY_RECORD",id,ref);
        assertThat(result.getBody()).contains("0 MPa","/quality/records/");
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(data).invoke(eq("business_read"),argThat(a->a.path("ref").path("revision").asText().equals("v1")));
    }
    @Test void queryAndComputeLinksRejectChangedSnapshots(){
        when(data.invoke(eq("business_query"),any())).thenReturn(response());when(compute.result("PREDICTION",id)).thenReturn(response());
        assertThatThrownBy(()->controller.query("{\"type\":\"QUALITY_RECORD\"}","old"))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.errorCode()).isEqualTo(ApiErrorCode.VERSION_CONFLICT));
        assertThatThrownBy(()->controller.compute("PREDICTION",id,"old")).isInstanceOf(ApiException.class);
        assertThat(controller.compute("PREDICTION",id,"current").getBody()).contains("固定版本");
    }
    @Test void revocationStopsOriginalReadsBeforeCallingAnyBusinessService(){
        doThrow(new ApiException(ApiErrorCode.PERMISSION_DENIED)).when(authorization).require(any());
        assertThatThrownBy(()->controller.query("{}","hash")).isInstanceOf(ApiException.class);
        verifyNoInteractions(data,compute);
    }
}
