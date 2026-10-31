package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.kb.api.ResearchKnowledgeFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiteratureResearchService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ResearchKnowledgeFacade knowledge;
    private final AuthorizationService authorization;
    private final AgentSourceAuthorization sources;
    private static final Set<String> KINDS = Set.of("topic", "annotation", "card", "report", "bibliography", "artifact");

    public LiteratureResearchService(JdbcTemplate jdbc, ObjectMapper mapper, ResearchKnowledgeFacade knowledge, AuthorizationService authorization, AgentSourceAuthorization sources) {
        this.jdbc = jdbc; this.mapper = mapper; this.knowledge = knowledge; this.authorization = authorization;
        this.sources = sources;
    }

    public void requireUse() {
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "ai.use", "AI", null, "READ"));
    }

    public ObjectNode get(String kind, UUID id) {
        requireUse();
        if (!KINDS.contains(kind)) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var actor = ActorContext.required();
        var record = jdbc.query("SELECT id,revision,payload,created_at,updated_at FROM ai.research_record WHERE kind=? AND id=? AND organization_id=? AND owner_id=?",
            (rs, index) -> {
                try {
                    var value = (ObjectNode) mapper.readTree(rs.getString("payload"));
                    value.put("id", rs.getString("id")).put("revision", rs.getLong("revision"));
                    value.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    value.put("updated_at", rs.getTimestamp("updated_at").toInstant().toString());
                    return value;
                } catch (Exception error) { throw new IllegalStateException(error); }
            }, kind, id, actor.organizationId(), actor.userId()).stream().findFirst()
            .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if ("topic".equals(kind)) knowledge.authorize(documents(record));
        else {
            if (!"artifact".equals(kind) || record.hasNonNull("topic_id"))
                get("topic", UUID.fromString(record.path("topic_id").asText()));
            if ("card".equals(kind)) for (var field : record.path("fields")) authorizeSource(field.path("source"));
            if ("report".equals(kind)) for (var evidence : record.path("evidence")) authorizeSource(evidence.path("source"));
            if ("artifact".equals(kind)) {
                knowledge.authorize(documents(record));
                if (record.has("source_refs")) sources.authorize(record.path("source_refs"));
            }
            if ("annotation".equals(kind) && record.hasNonNull("source")) authorizeSource(record.path("source"));
        }
        return record;
    }

    public List<ObjectNode> list(String kind, UUID topicId) {
        requireUse();
        if (!KINDS.contains(kind)) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var actor = ActorContext.required();
        if (topicId != null) get("topic", topicId);
        var sql = "SELECT id FROM ai.research_record WHERE kind=? AND organization_id=? AND owner_id=?"
            + (topicId == null ? "" : " AND payload->>'topic_id'=?") + " ORDER BY updated_at DESC LIMIT 200";
        var args = new ArrayList<Object>(List.of(kind, actor.organizationId(), actor.userId()));
        if (topicId != null) args.add(topicId.toString());
        return jdbc.query(sql, (rs, index) -> UUID.fromString(rs.getString("id")), args.toArray()).stream().map(id -> get(kind, id)).toList();
    }

    public List<ResearchKnowledgeFacade.DocumentRef> documents(ObjectNode topic) {
        var result = new ArrayList<ResearchKnowledgeFacade.DocumentRef>();
        for (var document : topic.path("documents")) result.add(mapper.convertValue(document, ResearchKnowledgeFacade.DocumentRef.class));
        return List.copyOf(result);
    }

    @Transactional
    public ObjectNode create(String kind, ObjectNode payload, String key) {
        requireUse();
        if (!KINDS.contains(kind) || key == null || key.isBlank() || key.length() > 128 || payload.toString().length() > 1000000)
            throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var actor = ActorContext.required();
        var request = payload.deepCopy();
        request.remove(List.of("id", "revision", "created_at", "updated_at"));
        if ("topic".equals(kind)) {
            if (payload.path("title").asText().isBlank()) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "研究主题需要标题");
            if (!payload.has("documents")) payload.set("documents", mapper.createArrayNode());
            if (!payload.has("notes")) payload.set("notes", mapper.createArrayNode());
            if (!payload.has("scope_mode")) payload.put("scope_mode", "SELECTED");
            if (!Set.of("SELECTED", "AUTOMATIC").contains(payload.path("scope_mode").asText()))
                throw new ApiException(ApiErrorCode.BAD_REQUEST);
            knowledge.authorize(documents(payload));
        } else if ("artifact".equals(kind) && !payload.hasNonNull("topic_id")) {
            // Generic outputs retain owned run/conversation provenance, without
            // inventing a research topic. Only the internal tool can create artifacts.
            UUID.fromString(payload.path("conversation_id").asText());
            UUID.fromString(payload.path("run_id").asText());
            sources.authorize(payload.path("source_refs"));
        } else {
            var topic = get("topic", UUID.fromString(payload.path("topic_id").asText()));
            knowledge.authorize(documents(topic));
        }
        if ("card".equals(kind)) {
            if (!payload.path("fields").isArray() || payload.path("fields").isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_ERROR);
            if (payload.path("instance").asText().isBlank() || payload.path("fields").size() > 100)
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR);
            var names = new HashSet<String>();
            var versions = new HashSet<String>();
            for (var field : payload.path("fields")) {
                if (!(field instanceof ObjectNode) || field.path("name").asText().isBlank() || !names.add(field.path("name").asText())
                    || field.path("excerpt").asText().isBlank()) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "证据字段需要唯一名称与原文摘录");
                var source = mapper.convertValue(field.path("source"), ResearchKnowledgeFacade.SourceRef.class);
                versions.add(source.document_id() + ":" + source.publication_id() + ":" + source.version_id());
                knowledge.validate(source, field.path("excerpt").asText());
                ((ObjectNode) field).put("status", "AI_EXTRACTED");
                ((ObjectNode) field).remove(List.of("verified_by", "verified_at"));
            }
            if (versions.size() != 1) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "一张实例证据卡只能对应同一文献发布版本");
            payload.set("history", mapper.createArrayNode());
        }
        if ("annotation".equals(kind) && payload.hasNonNull("source"))
            knowledge.validate(mapper.convertValue(payload.path("source"), ResearchKnowledgeFacade.SourceRef.class), payload.path("excerpt").asText(""));
        if ("report".equals(kind)) {
            payload.put("status", "CANDIDATE_PENDING_REVIEW");
            for (var evidence : payload.path("evidence"))
                knowledge.validate(mapper.convertValue(evidence.path("source"), ResearchKnowledgeFacade.SourceRef.class), evidence.path("text").asText());
            var matcher = java.util.regex.Pattern.compile("\\[E:([a-zA-Z0-9_-]+)\\]").matcher(payload.path("text").asText());
            if (!payload.path("evidence").isObject() || payload.path("evidence").isEmpty() || !matcher.find())
                throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "研究报告至少需要一处原文证据引用；提纲请保存为笔记");
            matcher.reset();
            while (matcher.find()) if (!payload.path("evidence").has(matcher.group(1))) throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "报告包含无效引用");
        }
        payload.remove(List.of("id", "revision", "created_at", "updated_at"));
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO ai.research_record(kind,id,organization_id,owner_id,idempotency_key,payload,request_payload) VALUES(?,?,?,?,?,?::jsonb,?::jsonb) "
            + "ON CONFLICT(organization_id,owner_id,kind,idempotency_key) DO NOTHING", kind, id, actor.organizationId(), actor.userId(), key, payload.toString(), request.toString());
        var saved = jdbc.queryForObject("SELECT id FROM ai.research_record WHERE kind=? AND organization_id=? AND owner_id=? AND idempotency_key=?",
            UUID.class, kind, actor.organizationId(), actor.userId(), key);
        var result = get(kind, saved);
        var sameRequest = jdbc.queryForObject("SELECT request_payload=?::jsonb FROM ai.research_record WHERE kind=? AND id=? AND organization_id=? AND owner_id=?",
            Boolean.class, request.toString(), kind, saved, actor.organizationId(), actor.userId());
        if (!Boolean.TRUE.equals(sameRequest)) throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
        return result;
    }

    @Transactional
    public ObjectNode updateTopic(UUID id, ObjectNode patch, long revision) {
        var topic = get("topic", id);
        if (patch.has("title")) topic.put("title", patch.path("title").asText());
        if (patch.has("goal")) topic.put("goal", patch.path("goal").asText());
        if (patch.has("scope_mode")) {
            if (!Set.of("SELECTED", "AUTOMATIC").contains(patch.path("scope_mode").asText())) throw new ApiException(ApiErrorCode.BAD_REQUEST);
            topic.set("scope_mode", patch.get("scope_mode"));
        }
        if (patch.has("documents")) { topic.set("documents", patch.get("documents")); knowledge.authorize(documents(topic)); }
        return save("topic", id, topic, revision);
    }

    @Transactional
    public ObjectNode note(UUID topicId, String text, String key) {
        // Notes are immutable owned annotations, so replay never appends twice.
        return create("annotation", mapper.createObjectNode().put("topic_id", topicId.toString()).put("text", text).put("kind", "RESEARCH_NOTE"), key);
    }

    @Transactional
    public ObjectNode verify(UUID id, long revision, String name, String status) {
        if (!Set.of("EXTRACTION_VERIFIED", "REJECTED").contains(status)) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var card = get("card", id);
        var found = false;
        for (var field : card.path("fields")) if (name.equals(field.path("name").asText())) {
            knowledge.validate(mapper.convertValue(field.path("source"), ResearchKnowledgeFacade.SourceRef.class), field.path("excerpt").asText());
            var before = field.deepCopy();
            ((ObjectNode) field).put("status", status).put("verified_by", ActorContext.required().userId().toString())
                .put("verified_at", java.time.Instant.now().toString());
            var history = (com.fasterxml.jackson.databind.node.ArrayNode) card.withArray("history");
            var entry = mapper.createObjectNode().put("actor_id", ActorContext.required().userId().toString())
                .put("at", java.time.Instant.now().toString()).put("field", name).put("status", status);
            entry.set("before", before); entry.set("after", field.deepCopy()); history.add(entry);
            found = true;
        }
        if (!found) throw new ApiException(ApiErrorCode.NOT_FOUND, "证据字段不存在");
        return save("card", id, card, revision);
    }

    private ObjectNode save(String kind, UUID id, ObjectNode value, long revision) {
        var actor = ActorContext.required();
        value.remove(List.of("id", "revision", "created_at", "updated_at"));
        var changed = jdbc.update("UPDATE ai.research_record SET payload=?::jsonb,revision=revision+1,updated_at=now() "
            + "WHERE kind=? AND id=? AND organization_id=? AND owner_id=? AND revision=?", value.toString(), kind, id, actor.organizationId(), actor.userId(), revision);
        if (changed != 1) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        return get(kind, id);
    }

    private void authorizeSource(JsonNode source) {
        knowledge.authorize(List.of(new ResearchKnowledgeFacade.DocumentRef(
            UUID.fromString(source.path("document_id").asText()), UUID.fromString(source.path("publication_id").asText()))));
    }
}
