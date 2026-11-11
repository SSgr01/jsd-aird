package com.jsd.aird.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.application.*;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.platform.web.IsolatedPostgres;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_DATASOURCE_URL", matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")
class LiteratureReportPersistenceTest {
    @Test void checkedReportsPersistIdempotentlyAndReceiptsStillReauthorizeSources() {
        var mapper = new ObjectMapper();
        var sources = mock(AgentSourceAuthorization.class);
        var knowledge = mock(ResearchKnowledgeFacade.class);
        var actor = new Actor(UUID.randomUUID(),UUID.randomUUID(),"test");
        ActorContext.set(actor);
        try (var database = new IsolatedPostgres(Map.of("ai.research_record","research_record"))) {
            database.table("research_record","kind text NOT NULL,id uuid PRIMARY KEY,organization_id uuid NOT NULL,owner_id uuid NOT NULL,"
                + "idempotency_key text NOT NULL,payload jsonb NOT NULL,request_payload jsonb NOT NULL,revision bigint NOT NULL DEFAULT 0,"
                + "created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now(),UNIQUE(organization_id,owner_id,kind,idempotency_key)");
            var service = new LiteratureResearchService(database.jdbc,mapper,knowledge,mock(AuthorizationService.class),sources,mock(AgentArtifactStore.class));
            var topic = database.tx.execute(status -> service.create("topic",mapper.createObjectNode().put("title","研究"),"topic-key"));
            var request = mapper.createObjectNode().put("topic_id",topic.path("id").asText()).put("title","研究简报").put("text","典型样品的实测结果。[E:e1]");
            var evidence = request.putObject("evidence");
            for (int i=0;i<50;i++) evidence.putObject("e"+i).put("text","原文结果").putObject("source").put("kind","KB_PUBLICATION");
            var key = "a".repeat(64);
            var saved = database.tx.execute(status -> service.create("report",request.deepCopy(),key));
            verify(sources,times(1)).validateEvidence(evidence);
            verify(sources,times(1)).authorize(any());
            var replay = database.tx.execute(status -> service.create("report",request.deepCopy(),key));
            assertThat(replay.path("id")).isEqualTo(saved.path("id"));
            assertThat(database.jdbc.queryForObject("SELECT count(*) FROM ai.research_record WHERE kind='report'",Integer.class)).isEqualTo(1);
            var receiptRequest = mapper.createObjectNode().put("tool","report_save").put("operation_key",key);
            assertThat(service.receipt(receiptRequest).path("result").path("id")).isEqualTo(saved.path("id"));
            assertThatThrownBy(() -> database.tx.execute(status -> service.create("report",request.deepCopy().put("text","不同正文。[E:e1]"),key)))
                .isInstanceOf(ApiException.class);
            doThrow(new IllegalStateException("revoked")).when(sources).authorize(any());
            assertThatThrownBy(() -> service.receipt(receiptRequest)).hasMessage("revoked");
            ActorContext.set(new Actor(actor.organizationId(),UUID.randomUUID(),"other"));
            assertThatThrownBy(() -> service.get("report",UUID.fromString(saved.path("id").asText()))).isInstanceOf(ApiException.class);
        } finally { ActorContext.clear(); }
    }
}
