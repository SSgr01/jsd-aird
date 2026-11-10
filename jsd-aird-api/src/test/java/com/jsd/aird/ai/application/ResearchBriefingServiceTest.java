package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.mdm.api.AgentProjectFacade;
import com.jsd.aird.rnd.api.AgentExperimentFacade;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResearchBriefingServiceTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final AgentProjectFacade projects = mock(AgentProjectFacade.class);
    final AgentExperimentFacade experiments = mock(AgentExperimentFacade.class);
    final ResearchBriefingService service = new ResearchBriefingService(projects,experiments,json);

    @Test void weeklyTaskIndexGoesThroughDomainFacadeWithExactWindowAndPinnedReference() {
        var result = json.createObjectNode();
        result.putArray("candidates").addObject().putObject("source").put("resource_id","task-one")
            .put("version_id","8").put("project_id","project-one").put("content_sha256","fixed-body");
        when(projects.read(eq("briefing_records"),any())).thenAnswer(call->{
            ObjectNode args=call.getArgument(1);
            assertThat(args.path("record_type").asText()).isEqualTo("TASK");
            assertThat(args.path("updated_from").asText()).isEqualTo("2026-01-04T16:00:00Z");
            return result;
        });
        var output = service.context(json.createObjectNode().put("task_type","research-weekly").put("section","TASK")
            .put("window_start","2026-01-04T16:00:00Z").put("window_end","2026-01-05T01:00:00Z"));
        assertThat(output.path("candidates").path(0).path("ref").path("revision").asText()).isEqualTo("8");
        assertThat(output.path("candidates").path(0).path("ref").path("content_hash").asText()).isEqualTo("fixed-body");
        verifyNoInteractions(experiments);
    }

    @Test void independentExperimentsAreNotGivenAnImplicitProjectFilter() {
        when(experiments.read(eq("search_experiments"),any())).thenAnswer(call->{
            ObjectNode args=call.getArgument(1);assertThat(args.hasNonNull("project_id")).isFalse();
            var result=json.createObjectNode();result.putArray("candidates");return result;
        });
        service.context(json.createObjectNode().put("task_type","experiment-review").put("section","EXPERIMENT")
            .put("window_start","2026-01-01T00:00:00Z").put("window_end",Instant.now().toString()));
        verifyNoInteractions(projects);
    }
}
