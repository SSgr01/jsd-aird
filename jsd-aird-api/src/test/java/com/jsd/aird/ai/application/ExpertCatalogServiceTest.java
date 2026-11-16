package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExpertCatalogServiceTest {
    @Test void everyReadUsesExistingBusinessAuthorization() throws Exception {
        var authority = mock(AgentArtifactService.class);
        var service = new ExpertCatalogService(new ObjectMapper(), authority);
        doThrow(new ApiException(ApiErrorCode.PERMISSION_DENIED)).when(authority).requireUse();
        assertThrows(ApiException.class, service::list);
        assertThrows(ApiException.class, () -> service.resolve("project-assistant", true));
    }

    @Test void everyExpertHasOneCurrentDefinitionWithoutVersionHistory() throws Exception {
        var service = new ExpertCatalogService(new ObjectMapper(), mock(AgentArtifactService.class));
        var rows = service.list().path("experts");
        assertEquals(8, rows.size());
        var ids = new java.util.HashSet<String>();
        for (var row : rows) {
            assertTrue(ids.add(row.path("id").asText()));
            assertFalse(row.has("versions"));
            assertFalse(row.has("current_revision"));
            assertFalse(row.has("revision"));
            assertFalse(row.path("instructions").asText().isBlank());
            assertTrue(row.has("work_config"));
        }
        var experiment = service.resolve("experiment-assistant", true);
        assertTrue(experiment.path("skills").toString().contains("jsd-experiment-converter"));
        assertTrue(experiment.path("instructions").asText().contains("folder_conversion"));
        assertThrows(ApiException.class, () -> service.resolve("unknown", true));
    }

    @Test void returnedObjectsCannotMutateTheCurrentCatalog() throws Exception {
        var service = new ExpertCatalogService(new ObjectMapper(), mock(AgentArtifactService.class));
        var first = service.resolve("project-assistant", true);
        first.put("instructions", "changed by caller");
        assertNotEquals("changed by caller", service.resolve("project-assistant", true).path("instructions").asText());
        var current = service.resolve("experiment-assistant", true);
        ((ObjectNode) current.path("work_config").path("checks").path("unit_targets")).put("mass", "kg");
        assertEquals("g", service.resolve("experiment-assistant", true).path("work_config").path("checks").path("unit_targets").path("mass").asText());
    }

    @Test void oldLocalRequestsStillResolveTheCurrentExpert() throws Exception {
        var mapper = new ObjectMapper();
        var service = new ExpertCatalogService(mapper, mock(AgentArtifactService.class));
        var command = mapper.createObjectNode().put("action", "resolve").put("expert_id", "experiment-assistant").put("revision", "4");
        assertEquals(service.resolve("experiment-assistant", true), service.invoke(command));
        assertFalse(service.invoke(command).has("revision"));
    }
}
