package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owned Agent outputs share the existing record table, independent of literature workflows. */
@Service
public class AgentArtifactStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuthorizationService authorization;
    private final ResearchKnowledgeFacade knowledge;
    private final AgentSourceAuthorization sources;

    public AgentArtifactStore(JdbcTemplate jdbc, ObjectMapper mapper, AuthorizationService authorization,
            ResearchKnowledgeFacade knowledge, AgentSourceAuthorization sources) {
        this.jdbc = jdbc; this.mapper = mapper; this.authorization = authorization;
        this.knowledge = knowledge; this.sources = sources;
    }

    public void requireUse() {
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "ai.use", "AI", null, "READ"));
    }

    private ObjectNode owned(String kind, UUID id) {
        requireUse();
        var actor = ActorContext.required();
        return jdbc.query("SELECT id,revision,payload,created_at,updated_at FROM ai.research_record "
            + "WHERE kind=? AND id=? AND organization_id=? AND owner_id=?", (rs, index) -> {
                try {
                    var value = (ObjectNode) mapper.readTree(rs.getString("payload"));
                    value.put("id", rs.getString("id")).put("revision", rs.getLong("revision"));
                    value.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    value.put("updated_at", rs.getTimestamp("updated_at").toInstant().toString());
                    return value;
                } catch (Exception error) { throw new IllegalStateException(error); }
            }, kind, id, actor.organizationId(), actor.userId()).stream().findFirst()
            .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    private void authorizeDocuments(ObjectNode record) {
        var documents = new java.util.ArrayList<ResearchKnowledgeFacade.DocumentRef>();
        for (var doc : record.path("documents"))
            documents.add(mapper.convertValue(doc, ResearchKnowledgeFacade.DocumentRef.class));
        knowledge.authorize(documents);
    }

    public ObjectNode topic(UUID id) {
        var topic = owned("topic", id);
        authorizeDocuments(topic);
        return topic;
    }

    public ObjectNode get(UUID id) {
        var record = owned("artifact", id);
        if (record.hasNonNull("topic_id")) topic(UUID.fromString(record.path("topic_id").asText()));
        authorizeDocuments(record);
        if (record.has("source_refs")) sources.authorize(record.path("source_refs"));
        return record;
    }

    @Transactional
    public ObjectNode create(ObjectNode payload, String key) {
        requireUse();
        if (key == null || key.isBlank() || key.length() > 128 || payload.toString().length() > 1000000)
            throw new ApiException(ApiErrorCode.BAD_REQUEST);
        if (payload.hasNonNull("topic_id")) topic(UUID.fromString(payload.path("topic_id").asText()));
        else {
            UUID.fromString(payload.path("conversation_id").asText());
            UUID.fromString(payload.path("run_id").asText());
        }
        authorizeDocuments(payload);
        sources.authorize(payload.path("source_refs"));
        var actor = ActorContext.required();
        var request = payload.deepCopy();
        request.remove(List.of("id", "revision", "created_at", "updated_at"));
        jdbc.update("INSERT INTO ai.research_record(kind,id,organization_id,owner_id,idempotency_key,payload,request_payload) "
            + "VALUES('artifact',?,?,?,?,?::jsonb,?::jsonb) ON CONFLICT(organization_id,owner_id,kind,idempotency_key) DO NOTHING",
            UUID.randomUUID(), actor.organizationId(), actor.userId(), key, request.toString(), request.toString());
        var saved = jdbc.queryForObject("SELECT id FROM ai.research_record WHERE kind='artifact' AND organization_id=? "
            + "AND owner_id=? AND idempotency_key=?", UUID.class, actor.organizationId(), actor.userId(), key);
        var result = get(saved);
        var same = jdbc.queryForObject("SELECT request_payload=?::jsonb FROM ai.research_record "
            + "WHERE kind='artifact' AND id=? AND organization_id=? AND owner_id=?",
            Boolean.class, request.toString(), saved, actor.organizationId(), actor.userId());
        if (!Boolean.TRUE.equals(same)) throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
        return result;
    }
}
