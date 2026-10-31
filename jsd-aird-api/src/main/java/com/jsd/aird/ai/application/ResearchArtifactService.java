package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns durable outputs; a disposable Workspace never becomes a file authority. */
@Service
public class ResearchArtifactService {
    private final LiteratureResearchService research;
    private final FileStorageFacade storage;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentSourceAuthorization sources;

    public ResearchArtifactService(LiteratureResearchService research, FileStorageFacade storage, JdbcTemplate jdbc, ObjectMapper mapper, AgentSourceAuthorization sources) {
        this.research = research; this.storage = storage; this.jdbc = jdbc; this.mapper = mapper;
        this.sources = sources;
    }

    @Transactional
    public ObjectNode save(ObjectNode request) {
        research.requireUse();
        var topicId = request.hasNonNull("topic_id") ? UUID.fromString(request.path("topic_id").asText()) : null;
        var topic = topicId == null ? mapper.createObjectNode() : research.get("topic", topicId);
        var sourceRefs = request.has("source_refs") ? request.path("source_refs") : mapper.createArrayNode();
        sources.authorize(sourceRefs);
        if (topicId == null) {
            UUID.fromString(request.path("conversation_id").asText());
            UUID.fromString(request.path("run_id").asText());
        }
        var actor = ActorContext.required();
        var key = request.path("key").asText();
        var name = request.path("name").asText();
        if (!key.matches("[a-f0-9]{64}") || name.length() > 180 || name.chars().anyMatch(Character::isISOControl)
            || !name.matches("[^/\\\\\r\n:]+\\.(?i:json|csv|txt|md|xlsx|docx|png|pdf)"))
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的分析产物文件名");
        byte[] content;
        try { content = Base64.getDecoder().decode(request.path("base64").asText()); }
        catch (IllegalArgumentException error) { throw new ApiException(ApiErrorCode.BAD_REQUEST); }
        if (content.length > 10 * 1024 * 1024) throw new ApiException(ApiErrorCode.BAD_REQUEST, "分析产物超过 10 MiB");
        String sha;
        try { sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception error) { throw new IllegalStateException(error); }
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,27))", actor.organizationId() + ":" + actor.userId() + ":" + key);
        var existing = jdbc.queryForList("SELECT id FROM ai.research_record WHERE kind='artifact' AND organization_id=? AND owner_id=? AND idempotency_key=?", UUID.class, actor.organizationId(), actor.userId(), key);
        if (!existing.isEmpty()) {
            var record = research.get("artifact", existing.getFirst());
            if (!sha.equals(record.path("sha256").asText()) || !name.equals(record.path("name").asText())
                    || !Objects.equals(topicId == null ? null : topicId.toString(), record.path("topic_id").asText(null))
                    || !Objects.equals(request.path("conversation_id").asText(null), record.path("conversation_id").asText(null))
                    || !Objects.equals(request.path("run_id").asText(null), record.path("run_id").asText(null))
                    || !sourceRefs.equals(record.has("source_refs") ? record.path("source_refs") : mapper.createArrayNode()))
                throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            return record;
        }
        var lower = name.toLowerCase(Locale.ROOT);
        var kind = lower.endsWith(".xlsx") || lower.endsWith(".docx") ? "OFFICE" : lower.endsWith(".pdf") ? "DATA_SOURCE" : "RESEARCH_ARTIFACT";
        var file = storage.stageFile(name, "application/octet-stream", kind, new ByteArrayInputStream(content));
        var metadata = mapper.createObjectNode().put("run_id", request.path("run_id").asText())
            .put("name", name).put("file_id", file.fileId().toString()).put("sha256", sha).put("bytes", content.length)
            .put("status", "AI_GENERATED_UNVERIFIED");
        if (topicId != null) metadata.put("topic_id", topicId.toString());
        if (request.hasNonNull("conversation_id")) metadata.put("conversation_id", request.path("conversation_id").asText());
        metadata.set("source_refs", sourceRefs.deepCopy());
        metadata.set("documents", topic.has("documents") ? topic.path("documents").deepCopy() : mapper.createArrayNode());
        var saved = research.create("artifact", metadata, key);
        storage.activate(file.fileId());
        return saved;
    }

    public FileStorageFacade.StoredFile open(UUID id) {
        var artifact = research.get("artifact", id);
        if (artifact.has("source_refs")) sources.requireExport(artifact.path("source_refs"));
        return storage.open(ActorContext.required().organizationId(), UUID.fromString(artifact.path("file_id").asText()));
    }
}
