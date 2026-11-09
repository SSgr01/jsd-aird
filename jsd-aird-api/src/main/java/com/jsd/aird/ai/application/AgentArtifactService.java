package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.api.FileStorageFacade;
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
public class AgentArtifactService {
    private final AgentArtifactStore records;
    private final FileStorageFacade storage;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentSourceAuthorization sources;

    public AgentArtifactService(AgentArtifactStore records, FileStorageFacade storage, JdbcTemplate jdbc, ObjectMapper mapper, AgentSourceAuthorization sources) {
        this.records = records; this.storage = storage; this.jdbc = jdbc; this.mapper = mapper;
        this.sources = sources;
    }

    public void requireUse() { records.requireUse(); }

    @Transactional
    public ObjectNode save(ObjectNode request) {
        records.requireUse();
        var topicId = request.hasNonNull("topic_id") ? UUID.fromString(request.path("topic_id").asText()) : null;
        var topic = topicId == null ? mapper.createObjectNode() : records.topic(topicId);
        var sourceRefs = request.has("source_refs") ? request.path("source_refs") : mapper.createArrayNode();
        sources.authorize(sourceRefs);
        if (topicId == null) {
            UUID.fromString(request.path("conversation_id").asText());
            UUID.fromString(request.path("run_id").asText());
        }
        var actor = ActorContext.required();
        var key = request.path("key").asText();
        var name = request.path("name").asText();
        if (!key.matches("[a-f0-9]{64}")) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var format = AgentArtifactFormats.resolve(name);
        byte[] content;
        try { content = Base64.getDecoder().decode(request.path("base64").asText()); }
        catch (IllegalArgumentException error) { throw new ApiException(ApiErrorCode.BAD_REQUEST); }
        if (content.length > 10 * 1024 * 1024) throw new ApiException(ApiErrorCode.BAD_REQUEST, "成果文件超过 10 MiB");
        String sha;
        try { sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception error) { throw new IllegalStateException(error); }
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,27))", actor.organizationId() + ":" + actor.userId() + ":" + key);
        var existing = jdbc.queryForList("SELECT id FROM ai.research_record WHERE kind='artifact' AND organization_id=? AND owner_id=? AND idempotency_key=?", UUID.class, actor.organizationId(), actor.userId(), key);
        if (!existing.isEmpty()) {
            var record = records.get(existing.getFirst());
            if (!sha.equals(record.path("sha256").asText()) || !name.equals(record.path("name").asText())
                    || !Objects.equals(topicId == null ? null : topicId.toString(), record.path("topic_id").asText(null))
                    || !Objects.equals(request.path("conversation_id").asText(null), record.path("conversation_id").asText(null))
                    || !Objects.equals(request.path("run_id").asText(null), record.path("run_id").asText(null))
                    || !sourceRefs.equals(record.has("source_refs") ? record.path("source_refs") : mapper.createArrayNode())
                    || !request.path("execution_receipt").equals(record.path("execution_receipt"))
                    || !(request.has("source_quotes")?request.path("source_quotes"):mapper.createArrayNode()).equals(record.has("source_quotes")?record.path("source_quotes"):mapper.createArrayNode()))
                throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            return record;
        }
        var file = storage.stageFile(name, format.contentType(), format.storageKind(), new ByteArrayInputStream(content));
        var metadata = mapper.createObjectNode().put("run_id", request.path("run_id").asText())
            .put("name", name).put("file_id", file.fileId().toString()).put("sha256", sha).put("bytes", content.length)
            .put("status", "AI_GENERATED_UNVERIFIED");
        if (topicId != null) metadata.put("topic_id", topicId.toString());
        if (request.hasNonNull("conversation_id")) metadata.put("conversation_id", request.path("conversation_id").asText());
        metadata.set("source_refs", sourceRefs.deepCopy());
        metadata.set("source_quotes",request.has("source_quotes")?request.path("source_quotes").deepCopy():mapper.createArrayNode());
        if(request.hasNonNull("execution_receipt")) metadata.set("execution_receipt",request.path("execution_receipt").deepCopy());
        metadata.set("documents", topic.has("documents") ? topic.path("documents").deepCopy() : mapper.createArrayNode());
        var saved = records.create(metadata, key);
        storage.activate(file.fileId());
        return saved;
    }

    public ObjectNode provenance(UUID id) throws Exception {
        try(var file=open(id)) {
            var artifact=records.get(id);
            var result=mapper.createObjectNode().put("artifact_id",id.toString()).put("sha256",artifact.path("sha256").asText())
                .put("verification","AI_GENERATED_UNVERIFIED");
            for(var key:List.of("source_refs","source_quotes","execution_receipt")) if(artifact.has(key)) result.set(key,artifact.path(key).deepCopy());
            return result;
        }
    }

    public FileStorageFacade.StoredFile open(UUID id) {
        var artifact = records.get(id);
        if (artifact.has("source_refs")) sources.requireExport(artifact.path("source_refs"));
        return storage.open(ActorContext.required().organizationId(), UUID.fromString(artifact.path("file_id").asText()));
    }
}
