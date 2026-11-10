package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentBriefingHistoryTest {
    private final ObjectMapper json = new ObjectMapper();
    @SuppressWarnings("unchecked")
    @Test void oldPeriodsRecheckCurrentAccessAndVersionButDoNotRevalidateMutableBody() {
        var business = mock(BusinessDataService.class);
        ObjectProvider<BusinessDataService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(business);
        var service = new AgentSourceAuthorization(null, null, null, null, null);
        service.businessServices(provider, null);
        var source = json.createObjectNode().put("kind", "BUSINESS_RECORD").put("resource_type", "EXPERIMENT")
            .put("resource_id", "experiment").put("version_id", "version").put("content_sha256", "old");
        when(business.invoke(eq("business_read"), any())).thenReturn(json.createObjectNode().set("candidates", json.createArrayNode().add(json.createObjectNode())));
        service.authorizeBriefingHistory(json.createArrayNode().add(source).add(source.deepCopy()));
        verify(business, times(1)).invoke(eq("business_read"), argThat(args -> {
            var ref = args.path("ref");
            return ref.path("id").asText().equals("experiment") && ref.path("version_id").asText().equals("version")
                && !ref.has("revision") && !ref.has("content_hash");
        }));
        when(business.invoke(eq("business_read"), any())).thenThrow(new ApiException(ApiErrorCode.DATA_SCOPE_DENIED));
        assertThatThrownBy(() -> service.authorizeBriefingHistory(json.createArrayNode().add(source)))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.DATA_SCOPE_DENIED));
    }
}
