package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.mdm.api.AgentProjectFacade;
import com.jsd.aird.rnd.api.ResearchExperimentFacade;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchActivityServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private final AgentSourceAuthorization sources=mock(AgentSourceAuthorization.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ResearchExperimentFacade experiments=mock(ResearchExperimentFacade.class);
    private final ResearchActivityService service=new ResearchActivityService(jdbc,mock(AgentProjectFacade.class),sources,json,experiments);
    @org.junit.jupiter.api.AfterEach void clear(){ActorContext.clear();}
    @Test void literatureConclusionsMustUseAnAuthorizedExactSourceReference() {
        var args=json.createObjectNode();var ref=json.createObjectNode().put("document_id","source-a").put("version_id","v1");
        args.putArray("source_refs").add(ref);
        var item=args.putArray("research_basis").addObject();item.set("source_ref",ref.deepCopy());
        for(var key:java.util.List.of("variables","conditions","metrics","pending_measurements"))item.putArray(key).add("项目记录");
        for(var key:java.util.List.of("conclusion","applicability","project_differences"))item.put(key,"以原文条件为适用边界");
        service.check(args);verify(sources).authorize(args.path("source_refs"));
        item.withObject("source_ref").put("version_id","v2");
        assertThatThrownBy(()->service.check(args)).isInstanceOf(ApiException.class).hasMessageContaining("已读取的出处");
    }
    @Test void omittedProjectCanBeRecoveredFromTheSelectedExperimentVersionsWithoutRewritingHistory() throws Exception {
        var actor=new Actor(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),"research-owner");ActorContext.set(actor);
        var run=java.util.UUID.randomUUID();var experiment=java.util.UUID.randomUUID();var version=java.util.UUID.randomUUID();var project=java.util.UUID.randomUUID();
        var request=json.createObjectNode();request.putArray("rows").addObject().put("sourceExperimentId",experiment.toString()).put("versionId",version.toString());
        when(jdbc.queryForList(anyString(),eq(String.class),eq(actor.organizationId()),eq(actor.userId()),eq(run))).thenReturn(java.util.List.of(request.toString()));
        when(experiments.record(actor.organizationId(),experiment,version)).thenReturn(json.createObjectNode().put("projectId",project.toString()));
        assertThat(service.authorizedContext("MODEL_TRAINING",run).path("projectId").asText()).isEqualTo(project.toString());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        service.bind("MODEL_TRAINING",run,json.createObjectNode());
        verify(jdbc).update(anyString(),eq(actor.organizationId()),eq("MODEL_TRAINING"),eq(run),eq(project),anyString(),anyString(),eq(actor.userId()));
    }
    @Test void mixedProjectsDoNotAcquireAnInferredProject() {
        var actor=new Actor(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),"research-owner");ActorContext.set(actor);
        var run=java.util.UUID.randomUUID();var request=json.createObjectNode();var rows=request.putArray("rows");
        for(int i=0;i<2;i++) {
            var experiment=java.util.UUID.randomUUID();var version=java.util.UUID.randomUUID();
            rows.addObject().put("sourceExperimentId",experiment.toString()).put("versionId",version.toString());
            when(experiments.record(actor.organizationId(),experiment,version)).thenReturn(json.createObjectNode().put("projectId",java.util.UUID.randomUUID().toString()));
        }
        when(jdbc.queryForList(anyString(),eq(String.class),eq(actor.organizationId()),eq(actor.userId()),eq(run))).thenReturn(java.util.List.of(request.toString()));
        assertThat(service.authorizedContext("MODEL_TRAINING",run).has("projectId")).isFalse();
    }
}
