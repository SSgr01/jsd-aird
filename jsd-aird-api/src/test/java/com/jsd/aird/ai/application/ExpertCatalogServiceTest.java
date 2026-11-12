package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExpertCatalogServiceTest {
    @Test
    void everyReadUsesExistingBusinessAuthorization() throws Exception {
        var authority = mock(AgentArtifactService.class);
        var service = new ExpertCatalogService(new ObjectMapper(), authority);
        doThrow(new ApiException(ApiErrorCode.PERMISSION_DENIED)).when(authority).requireUse();
        assertThrows(ApiException.class, service::list);
        assertThrows(ApiException.class, () -> service.resolve("project-assistant", "1", true));
    }

    @Test
    void releasedVersionsAreExplicitAndReturnedObjectsCannotMutateTheCatalog() throws Exception {
        var service = new ExpertCatalogService(new ObjectMapper(), mock(AgentArtifactService.class));
        var first = service.resolve("project-assistant", "1", true);
        first.put("instructions", "changed by caller");
        assertNotEquals("changed by caller", service.resolve("project-assistant", "1", true).path("instructions").asText());
        assertThrows(ApiException.class, () -> service.resolve("project-assistant", "999", true));
        assertThrows(ApiException.class, () -> service.resolve("unknown", null, true));
        assertEquals(8, service.list().path("experts").size());
        var command = new ObjectMapper().createObjectNode().put("action", "resolve").put("expert_id", "project-assistant").putNull("revision");
        assertEquals("2", service.invoke(command).path("revision").asText());
        assertFalse(service.resolve("project-assistant", "1", true).has("work_config"));
        var current = service.resolve("experiment-assistant", null, true);
        assertEquals("g", current.path("work_config").path("checks").path("unit_targets").path("mass").asText());
        assertTrue(current.path("work_config").path("checks").path("parameters").path("record_template").isNull());
        ((com.fasterxml.jackson.databind.node.ObjectNode) current.path("work_config").path("checks").path("unit_targets")).put("mass", "kg");
        assertEquals("g", service.resolve("experiment-assistant", null, true).path("work_config").path("checks").path("unit_targets").path("mass").asText());
    }
}
