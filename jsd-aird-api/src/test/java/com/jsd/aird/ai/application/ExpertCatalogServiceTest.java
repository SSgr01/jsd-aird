package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExpertCatalogServiceTest {
    @Test void mergedDataExpertKeepsBothIdentitiesAndTaskSpecificChecks() throws Exception {
        var service = new ExpertCatalogService(new ObjectMapper(), mock(AgentArtifactService.class));
        var current = service.resolve("experiment-assistant", true);
        assertEquals("数据整理助理", current.path("name").asText());
        assertEquals(current, service.resolve("data-assistant", true));
        assertEquals(current, service.resolve("data-assistant", false));
        var skills = new java.util.HashSet<String>();
        current.path("skills").forEach(skill -> skills.add(skill.asText()));
        assertEquals(java.util.Set.of("record-validation", "experiment-comparison",
                "jsd-experiment-converter", "table-preparation"), skills);
        assertTrue(current.path("work_config").path("checks").path("required_fields").isEmpty());
        assertTrue(current.path("work_config").path("checks").path("identity_fields").isEmpty());
        for (var expert : service.list().path("experts"))
            assertNotEquals("data-assistant", expert.path("id").asText());
    }
    @Test void everyReadUsesExistingBusinessAuthorization() throws Exception {
        var authority = mock(AgentArtifactService.class);
        var service = new ExpertCatalogService(new ObjectMapper(), authority);
        doThrow(new ApiException(ApiErrorCode.PERMISSION_DENIED)).when(authority).requireUse();
        assertThrows(ApiException.class, service::list);
        assertThrows(ApiException.class, () -> service.resolve("project-assistant", true));
        assertThrows(ApiException.class, () -> service.resolve("technical-assistant", true));
        assertThrows(ApiException.class, () -> service.resolve("technical-assistant", false));
    }

    @Test void everyExpertHasOneCurrentDefinitionWithoutVersionHistory() throws Exception {
        var service = new ExpertCatalogService(new ObjectMapper(), mock(AgentArtifactService.class));
        var rows = service.list().path("experts");
        assertEquals(7, rows.size());
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
        var spectrum = service.resolve("spectrum-assistant", true);
        assertEquals("图谱分析助理", spectrum.path("name").asText());
        assertTrue(spectrum.path("skills").toString().contains("spectrum-analysis"));
        assertThrows(ApiException.class, () -> service.resolve("unknown", true));
    }

    @Test void returnedObjectsCannotMutateTheCurrentCatalog() throws Exception {
        var service = new ExpertCatalogService(new ObjectMapper(), mock(AgentArtifactService.class));
        var first = service.resolve("project-assistant", true);
        first.put("instructions", "changed by caller");
        assertNotEquals("changed by caller", service.resolve("project-assistant", true).path("instructions").asText());
        var current = service.resolve("experiment-assistant", true);
        ((ObjectNode) current.path("work_config").path("checks").path("unit_targets")).put("mass", "kg");
        assertFalse(service.resolve("experiment-assistant", true).path("work_config").path("checks").path("unit_targets").has("mass"));
    }

    @Test void oldLocalRequestsStillResolveTheCurrentExpert() throws Exception {
        var mapper = new ObjectMapper();
        var service = new ExpertCatalogService(mapper, mock(AgentArtifactService.class));
        var command = mapper.createObjectNode().put("action", "resolve").put("expert_id", "experiment-assistant").put("revision", "4");
        assertEquals(service.resolve("experiment-assistant", true), service.invoke(command));
        assertFalse(service.invoke(command).has("revision"));
    }

    @Test void mergedResearchExpertResolvesOldIdentityWithoutAnExtraCatalogEntry() throws Exception {
        var service = new ExpertCatalogService(new ObjectMapper(), mock(AgentArtifactService.class));
        var current = service.resolve("materials-advisor", true);
        assertEquals("材料研发助理", current.path("name").asText());
        assertEquals(current, service.resolve("technical-assistant", true));
        assertEquals(current, service.resolve("technical-assistant", false));
        var skills = new java.util.HashSet<String>();
        current.path("skills").forEach(skill -> skills.add(skill.asText()));
        assertEquals(java.util.Set.of("literature-review", "technical-comparison", "formulation-analysis",
                "experiment-comparison", "research-design"), skills);
        for (var expert : service.list().path("experts"))
            assertNotEquals("technical-assistant", expert.path("id").asText());
        current.put("name", "caller mutation");
        assertEquals("材料研发助理", service.resolve("technical-assistant", true).path("name").asText());
    }

    @Test void legacyIdentityCannotBypassDisabledCanonicalExpert() throws Exception {
        var mapper = spy(new ObjectMapper());
        doAnswer(invocation -> {
            var catalog = (com.fasterxml.jackson.databind.JsonNode) invocation.callRealMethod();
            for (var expert : catalog.path("experts"))
                if (expert.path("id").asText().equals("materials-advisor"))
                    ((ObjectNode) expert).put("enabled", false);
            return catalog;
        }).when(mapper).readTree(any(java.io.InputStream.class));
        var service = new ExpertCatalogService(mapper, mock(AgentArtifactService.class));
        assertThrows(ApiException.class, () -> service.resolve("materials-advisor", true));
        assertThrows(ApiException.class, () -> service.resolve("technical-assistant", true));
        assertFalse(service.resolve("technical-assistant", false).path("enabled").asBoolean());
    }
}
