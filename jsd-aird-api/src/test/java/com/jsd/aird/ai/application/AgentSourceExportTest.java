package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.iam.api.*;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.mdm.api.AgentProjectFacade;
import com.jsd.aird.shared.security.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.mockito.Mockito.*;

class AgentSourceExportTest {
    final ObjectMapper mapper = new ObjectMapper();
    final AuthorizationService authorization = mock(AuthorizationService.class);
    final AgentSourceAuthorization sources = spy(new AgentSourceAuthorization(authorization,
        mock(ResearchKnowledgeFacade.class), mock(AgentProjectFacade.class),
        mock(DataSourceFileSearchFacade.class), mock(PrivateResearchSourceService.class)));

    @BeforeEach void setup() {
        ActorContext.set(new Actor(UUID.randomUUID(), UUID.randomUUID(), "chenlin"));
        // Read authorization has its own coverage. Here the contract is which
        // additional permission an already-readable legacy source must require.
        doNothing().when(sources).authorize(any());
    }
    @AfterEach void clear() { ActorContext.clear(); }

    @ParameterizedTest
    @CsvSource({"BUSINESS_RECORD,PROJECT,project.list.export,PROJECT",
        "BUSINESS_RECORD,EXPERIMENT,eln.view.export,EXPERIMENT",
        "DATA_SOURCE_FILE,EXPERIMENT,eln.view.export,EXPERIMENT",
        "DATA_SOURCE_FILE,DATA,data.export,DATA"})
    void legacySourcesUseThePageExportPermission(String kind, String type, String permission, String resource) {
        var id = UUID.randomUUID();
        var source = mapper.createObjectNode().put("kind", kind).put("resource_type", type)
            .put("resource_id", id.toString()).put("project_id", id.toString());
        sources.requireExport(mapper.createArrayNode().add(source));
        var actor = ActorContext.required();
        verify(authorization).require(new PermissionCheck(actor.organizationId(), actor.userId(), permission, resource, id, "READ"));
        verifyNoMoreInteractions(authorization);
    }
}
