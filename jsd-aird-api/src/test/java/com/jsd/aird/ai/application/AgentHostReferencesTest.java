package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.mdm.api.AgentProjectFacade;
import com.jsd.aird.rnd.api.AgentExperimentFacade;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.shared.error.ApiException;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentHostReferencesTest {
    final ObjectMapper mapper = new ObjectMapper();
    final ResearchKnowledgeFacade knowledge = mock(ResearchKnowledgeFacade.class);
    final AgentProjectFacade projects = mock(AgentProjectFacade.class);
    final AgentExperimentFacade experiments = mock(AgentExperimentFacade.class);
    final AgentHostService host = new AgentHostService(mock(LiteratureResearchService.class), knowledge, projects,
        mock(AgentSourceAuthorization.class), mock(AuthorizationService.class), mapper, mock(PrivateResearchSourceService.class));
    final UUID doc = UUID.randomUUID(), pub = UUID.randomUUID();
    @BeforeEach void setup() { ActorContext.set(new Actor(UUID.randomUUID(),UUID.randomUUID(),"test","USER"));host.experiments(experiments); }
    @AfterEach void clear() { ActorContext.clear(); }

    @Test void referenceDoesNotRestrictSearchAndExplicitFilterDoes() {
        var request = mapper.createObjectNode().put("profile","agent");
        request.putArray("context_refs").addObject().put("kind","KB_PUBLICATION").put("resource_id",doc.toString()).put("publication_id",pub.toString());
        assertThat(host.context(request).path("scope_mode").asText()).isEqualTo("AUTOMATIC");
        request.putObject("retrieval_scope").putArray("knowledge_documents").add(mapper.valueToTree(new ResearchKnowledgeFacade.DocumentRef(doc,pub)));
        assertThat(host.context(request).path("scope_mode").asText()).isEqualTo("SELECTED");
        assertThat(host.context(mapper.createObjectNode().put("profile","agent")).path("scope_mode").asText()).isEqualTo("AUTOMATIC");
        verify(knowledge,atLeastOnce()).authorize(List.of(new ResearchKnowledgeFacade.DocumentRef(doc,pub)));
    }

    @Test void projectVersionChangeDoesNotSilentlyOpenCurrentRecord() {
        var result=mapper.createObjectNode();
        result.putArray("candidates").addObject().put("text","current").putObject("source").put("version_id","2");
        when(projects.read(eq("get_project_snapshot"),any())).thenReturn(result);
        var args=mapper.createObjectNode();
        args.putObject("ref").put("kind","PROJECT").put("resource_id",doc.toString()).put("version_id","1");
        assertThatThrownBy(()->host.contextResource(args)).isInstanceOf(ApiException.class);
    }

    @Test void experimentReadUsesExactVersionWithoutRequiringProject() {
        var result=mapper.createObjectNode();
        result.putArray("candidates").addObject().put("text","experiment").putObject("source").put("version_id",pub.toString());
        when(experiments.read(eq("get_experiment_record"),any())).thenReturn(result);
        var args=mapper.createObjectNode();
        args.putObject("ref").put("kind","EXPERIMENT").put("resource_id",doc.toString()).put("version_id",pub.toString());
        host.contextResource(args);
        verify(experiments).read(eq("get_experiment_record"),argThat(value->value.path("version_id").asText().equals(pub.toString())&&!value.has("project_id")));
    }
}
