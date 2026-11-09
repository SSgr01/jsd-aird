package com.jsd.aird.ai.application;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.adapter.in.web.EvidenceAccessController;
import org.junit.jupiter.api.Test;

class EvidenceAccessTest {
    @Test void sourceDisplayChecksPermissionAndPinnedAuthorityOnEveryOpening() throws Exception {
        var sources = mock(AgentSourceAuthorization.class);
        var research = mock(LiteratureResearchService.class);
        var controller = new EvidenceAccessController(sources, research);
        var request = new ObjectMapper().readTree("{\"sources\":[{\"kind\":\"KB_PUBLICATION\"}]}");
        controller.authorize(request);
        controller.authorize(request);
        verify(research, times(2)).requireUse();
        verify(sources, times(2)).authorize(request.path("sources"));
        verify(sources, times(2)).validate(request.path("sources").get(0), "");
        doThrow(new IllegalStateException("revoked")).when(research).requireUse();
        assertThatThrownBy(() -> controller.authorize(request)).hasMessage("revoked");
        verify(sources, times(2)).authorize(any());
    }
}
