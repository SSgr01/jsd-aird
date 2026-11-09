package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AgentArtifactStoreTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ObjectMapper mapper = new ObjectMapper();
    final AuthorizationService authorization = mock(AuthorizationService.class);
    final ResearchKnowledgeFacade knowledge = mock(ResearchKnowledgeFacade.class);
    final AgentSourceAuthorization sources = mock(AgentSourceAuthorization.class);
    final AgentArtifactStore store = new AgentArtifactStore(jdbc, mapper, authorization, knowledge, sources);
    final Actor actor = new Actor(UUID.randomUUID(), UUID.randomUUID(), "test");
    final Map<String, ObjectNode> rows = new HashMap<>();

    @BeforeEach @SuppressWarnings("unchecked") void ownedRows() {
        ActorContext.set(actor);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenAnswer(call -> {
            assertThat((String) call.getArgument(0)).contains("organization_id=? AND owner_id=?");
            assertThat((Object) call.getArgument(4)).isEqualTo(actor.organizationId());
            assertThat((Object) call.getArgument(5)).isEqualTo(actor.userId());
            var row = rows.get(call.getArgument(2) + ":" + call.getArgument(3));
            return row == null ? List.of() : List.of(row.deepCopy());
        });
    }

    @AfterEach void clear() { ActorContext.clear(); }

    @Test void genericOwnedArtifactNeedsNoTopic() {
        var id = UUID.randomUUID();
        var file = mapper.createObjectNode().put("name", "data.csv");
        file.putArray("source_refs");
        rows.put("artifact:" + id, file);
        assertThat(store.get(id).path("name").asText()).isEqualTo("data.csv");
        verify(sources).authorize(file.path("source_refs"));
        verify(knowledge).authorize(List.of());
    }

    @Test void historicalTopicAndDocumentAuthorizationIsRepeated() {
        var id = UUID.randomUUID(); var topicId = UUID.randomUUID();
        var documentId = UUID.randomUUID(); var publicationId = UUID.randomUUID();
        var file = mapper.createObjectNode().put("topic_id", topicId.toString());
        file.putArray("source_refs").addObject().put("kind", "KB_PUBLICATION");
        var topic = mapper.createObjectNode();
        topic.putArray("documents").addObject().put("document_id", documentId.toString())
            .put("publication_id", publicationId.toString());
        rows.put("artifact:" + id, file); rows.put("topic:" + topicId, topic);
        store.get(id);
        verify(knowledge).authorize(List.of(new ResearchKnowledgeFacade.DocumentRef(documentId, publicationId)));
        doThrow(new IllegalStateException("revoked")).when(knowledge).authorize(anyList());
        assertThatThrownBy(() -> store.get(id)).hasMessage("revoked");
    }

    @Test void missingOwnedRecordDoesNotLeakMetadata() {
        assertThatThrownBy(() -> store.get(UUID.randomUUID())).isInstanceOf(ApiException.class);
        verifyNoInteractions(sources, knowledge);
    }

    @Test void revokedAiPermissionStopsBeforeReadingRecords() {
        doThrow(new IllegalStateException("ai permission revoked")).when(authorization).require(any());
        assertThatThrownBy(() -> store.get(UUID.randomUUID())).hasMessage("ai permission revoked");
        verifyNoInteractions(jdbc);
    }
}
