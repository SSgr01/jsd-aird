package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.kb.api.PrivateDocumentParsingFacade;
import com.jsd.aird.ops.api.FileStorageFacade;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable owned source versions; never publishes, embeds or indexes the uploaded material. */
@Service
public class PrivateResearchSourceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final FileStorageFacade files;
    private final PrivateDocumentParsingFacade parsing;
    private final AuthorizationService authorization;
    private final TransactionTemplate transactions;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon().name("private-source-", 0).factory());

    public PrivateResearchSourceService(JdbcTemplate jdbc, ObjectMapper mapper, FileStorageFacade files,
            PrivateDocumentParsingFacade parsing, AuthorizationService authorization) {
        this.jdbc = jdbc; this.mapper = mapper; this.files = files; this.parsing = parsing; this.authorization = authorization;
        this.transactions = new TransactionTemplate(new JdbcTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }

    private void requireUse() {
        var actor = ActorContext.required();
        authorization.require(new PermissionCheck(actor.organizationId(), actor.userId(), "ai.use", "AI", null, "READ"));
    }

    public ObjectNode get(UUID id) {
        requireUse();
        var actor = ActorContext.required();
        // Bound pending parsing after process loss; no automatic replay or duplicate external parser call.
        jdbc.update("UPDATE ai.agent_private_source SET payload=jsonb_set(jsonb_set(payload,'{status}','\"FAILED\"'),"
            + "'{error}','\"PRIVATE_SOURCE_PARSE_INTERRUPTED\"'),updated_at=now() WHERE id=? AND organization_id=? AND owner_id=?"
            + " AND payload->>'status'='PARSING' AND updated_at < now()-interval '30 minutes'",
            id, actor.organizationId(), actor.userId());
        return jdbc.query("SELECT payload FROM ai.agent_private_source WHERE id=? AND organization_id=? AND owner_id=?",
            (rs, row) -> { try { return (ObjectNode) mapper.readTree(rs.getString(1)); }
                catch (Exception error) { throw new IllegalStateException(error); } }, id, actor.organizationId(), actor.userId())
            .stream().findFirst().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    public ObjectNode upload(ObjectNode args) {
        requireUse();
        var actor = ActorContext.required();
        var conversationId = UUID.fromString(args.path("conversation_id").asText());
        var name = args.path("name").asText();
        var type = args.path("content_type").asText("application/octet-stream");
        var key = args.path("key").asText();
        if (!key.matches("[a-zA-Z0-9:_-]{1,128}") || name.isBlank() || name.length() > 180
                || name.matches(".*[/\\\\:].*") || name.chars().anyMatch(Character::isISOControl))
            throw new ApiException(ApiErrorCode.BAD_REQUEST);
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(args.path("base64").asText()); }
        catch (IllegalArgumentException error) { throw new ApiException(ApiErrorCode.BAD_REQUEST); }
        if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var provenance = args.has("provenance") ? args.path("provenance") : mapper.createObjectNode();
        var hash = sha(conversationId + ":" + name + ":" + type + ":" + sha(bytes) + ":" + provenance);
        var created = new java.util.concurrent.atomic.AtomicBoolean();
        var value = transactions.execute(transaction -> {
            // Serialize reservations across API workers, including different idempotency keys for one owner.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", String.class,
                "private-source:" + actor.organizationId() + ":" + actor.userId());
            var existing = jdbc.queryForList("SELECT id,request_hash FROM ai.agent_private_source WHERE organization_id=? AND owner_id=? AND idempotency_key=?",
                actor.organizationId(), actor.userId(), key);
            if (!existing.isEmpty()) {
                if (!hash.equals(existing.getFirst().get("request_hash"))) throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
                return metadata((UUID) existing.getFirst().get("id"));
            }
            var count = jdbc.queryForObject("SELECT count(*) FROM ai.agent_private_source WHERE organization_id=? AND owner_id=? AND conversation_id=?",
                Integer.class, actor.organizationId(), actor.userId(), conversationId);
            if (count != null && count >= 20) throw new ApiException(ApiErrorCode.BAD_REQUEST, "单个会话最多二十份私有资料");
            var staged = files.stageFile(name, type, "KNOWLEDGE_DOCUMENT", new ByteArrayInputStream(bytes));
            var id = UUID.randomUUID();
            var reserved = mapper.createObjectNode().put("id", id.toString()).put("conversation_id", conversationId.toString())
                .put("version_id", staged.fileId().toString()).put("name", name).put("content_type", type)
                .put("sha256", sha(bytes)).put("bytes", bytes.length).put("status", "PARSING")
                .put("kind", provenance.hasNonNull("pmcid") ? "EXTERNAL_FULLTEXT" : "RUN_UPLOAD");
            reserved.set("provenance", provenance.deepCopy());
            int inserted = jdbc.update("INSERT INTO ai.agent_private_source(id,organization_id,owner_id,conversation_id,version_id,idempotency_key,request_hash,payload) "
                + "VALUES(?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(organization_id,owner_id,idempotency_key) DO NOTHING",
                id, actor.organizationId(), actor.userId(), conversationId, staged.fileId(), key, hash, reserved.toString());
            if (inserted != 1) throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            files.activate(staged.fileId());
            created.set(true);
            return reserved;
        });
        if (!created.get()) return value;
        var id = UUID.fromString(value.path("id").asText());
        var versionId = UUID.fromString(value.path("version_id").asText());
        var result = value.deepCopy();
        try {
            executor.execute(() -> {
                ActorContext.set(actor);
                try {
                    var parsed = parsing.parse(actor.organizationId(), actor.userId(), versionId, name, type, bytes.length,
                        new ByteArrayInputStream(bytes));
                    value.set("parsed", parsed); value.put("status", "READY");
                } catch (Exception error) { value.put("status", "FAILED").put("error", "PRIVATE_SOURCE_PARSE_FAILED"); }
                finally {
                    try {
                        jdbc.update("UPDATE ai.agent_private_source SET payload=?::jsonb,updated_at=now() WHERE id=? AND organization_id=? AND owner_id=? AND payload->>'status'='PARSING'",
                            value.toString(), id, actor.organizationId(), actor.userId());
                    } finally { ActorContext.clear(); }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            value.put("status", "FAILED").put("error", "PRIVATE_SOURCE_CAPACITY_EXCEEDED");
            jdbc.update("UPDATE ai.agent_private_source SET payload=?::jsonb,updated_at=now() WHERE id=?", value.toString(), id);
            return value;
        }
        return result;
    }

    public ObjectNode metadata(UUID id) {
        var value = get(id); value.remove("parsed"); return value;
    }

    public ObjectNode read(ObjectNode args) { return navigate(args, "read"); }
    public ObjectNode search(ObjectNode args) { return navigate(args, "search"); }
    public ObjectNode outline(ObjectNode args) { return navigate(args, "outline"); }

    public ObjectNode recall(ObjectNode args) {return navigate(args,"search",true);}
    public com.fasterxml.jackson.databind.node.ArrayNode replay(ObjectNode args) {
        var result=mapper.createArrayNode();
        if(!args.path("handles").isArray()||args.path("handles").size()>30)throw new ApiException(ApiErrorCode.BAD_REQUEST);
        for(var handle:args.path("handles")) {
            var ref=handle.path("source");validate(ref,"");
            var value=get(UUID.fromString(ref.path("document_id").asText()));
            if(!value.path("conversation_id").asText().equals(args.path("conversation_id").asText()))throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED);
            for(var node:value.path("parsed").path("nodes"))if(node.path("key").asText().equals(ref.path("source_node_key").asText())) {
                var item=result.addObject();item.put("text",node.path("text").asText());item.set("source",ref);item.put("node_offset",handle.path("node_offset").asInt());item.put("score",handle.path("score").asDouble());
            }
        }
        return result;
    }

    private ObjectNode navigate(ObjectNode args,String mode) {return navigate(args,mode,false);}
    private ObjectNode navigate(ObjectNode args, String mode, boolean all) {
        var source = get(UUID.fromString(args.path("document_id").asText()));
        if (!source.path("conversation_id").asText().equals(args.path("conversation_id").asText()))
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        if (!"READY".equals(source.path("status").asText())) throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "私有资料尚未解析完成");
        var nodes = source.path("parsed").path("nodes");
        int offset = args.path("offset").asInt();
        if (offset < 0 || offset > nodes.size()) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var result = mapper.createObjectNode();
        var candidates = result.putArray("candidates");
        var query = args.path("query").asText("").strip().toLowerCase(java.util.Locale.ROOT);
        if ("read".equals(mode) && !query.isBlank()) throw new ApiException(ApiErrorCode.BAD_REQUEST, "顺序阅读不接受query，请使用原文搜索工具");
        var matchMode = args.path("match_mode").asText("auto");
        if ("outline".equals(mode)) return PrivateDocumentNavigation.outline(mapper, nodes, offset);
        var indices = new java.util.ArrayList<Integer>();
        if ("search".equals(mode)) {
            com.jsd.aird.kb.api.ResearchTextMatch.terms(query);
            for(int n=0;n<nodes.size();n++) if(com.jsd.aird.kb.api.ResearchTextMatch.score(nodes.get(n).path("text").asText(),query,matchMode)>=0) indices.add(n);
            indices.sort(java.util.Comparator.comparingDouble((Integer n)->com.jsd.aird.kb.api.ResearchTextMatch.score(nodes.get(n).path("text").asText(),query,matchMode)).reversed().thenComparingInt(Integer::intValue));
        } else for(int n=0;n<nodes.size();n++) indices.add(n);
        if(offset>indices.size()) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        int end = all ? indices.size() : Math.min(indices.size(), offset + Math.min(12,Math.max(1,args.path("limit").asInt(12))));
        for (int i = offset; i < end; i++) {
            var node = nodes.get(indices.get(i));
            var candidate = mapper.createObjectNode().put("text", node.path("text").asText());
            var ref = candidate.putObject("source").put("kind", source.path("kind").asText())
                .put("document_id", source.path("id").asText()).put("version_id", source.path("version_id").asText())
                .put("publication_id", source.path("version_id").asText()).put("source_node_key", node.path("key").asText())
                .put("resource_type", "CONVERSATION").put("resource_id", source.path("conversation_id").asText())
                .put("title", source.path("name").asText()).put("content_sha256", sha(node.path("text").asText()));
            ref.set("anchor", node.path("anchor"));
            if (source.path("provenance").hasNonNull("source_url")) ref.put("url", source.path("provenance").path("source_url").asText());
            candidate.put("node_offset", indices.get(i));
            candidates.add(candidate);

        }
        result.putObject("trace").put("total_nodes", nodes.size()).put("offset", offset).put("scanned_until", end).put("mode", mode).put("match_mode", matchMode).put("candidate_count", indices.size()).put("fulltext_coverage", "read".equals(mode));
        if (end < indices.size()) result.put("next_offset", end); else result.putNull("next_offset");
        return result;
    }

    public ObjectNode context(ObjectNode args) {
        var source=get(UUID.fromString(args.path("document_id").asText()));
        if(!source.path("conversation_id").asText().equals(args.path("conversation_id").asText())) throw new ApiException(ApiErrorCode.NOT_FOUND);
        int before=args.path("before").asInt(1),after=args.path("after").asInt(1);
        if(before<0||before>2||after<0||after>2) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        var nodes=source.path("parsed").path("nodes"); int hit=-1;
        for(int i=0;i<nodes.size();i++) if(nodes.get(i).path("key").asText().equals(args.path("source_node_key").asText())) hit=i;
        if(hit<0) throw new ApiException(ApiErrorCode.NOT_FOUND);
        int start=Math.max(0,hit-before),end=Math.min(nodes.size(),hit+after+1);
        int cursor=args.path("cursor").asInt();
        if(cursor<0||cursor>end-start) throw new ApiException(ApiErrorCode.BAD_REQUEST);
        if(cursor==end-start) { var empty=mapper.createObjectNode();empty.putArray("candidates");empty.putNull("next_offset");empty.putObject("trace").put("fulltext_coverage",false);return empty; }
        var request=args.deepCopy().put("offset",start+cursor).put("limit",end-start-cursor); request.remove("query");
        var result=read(request); result.putNull("next_offset");
        ((ObjectNode)result.path("trace")).put("fulltext_coverage",false);
        for(var candidate:result.path("candidates")) ((ObjectNode)candidate).put("role",candidate.path("node_offset").asInt()==hit?"MATCH":"NEIGHBOR");
        return result;
    }

    public void validate(JsonNode reference, String excerpt) {
        validate(reference, excerpt, get(UUID.fromString(reference.path("document_id").asText())));
    }

    public void authorizeAll(JsonNode references) {
        // One request may carry hundreds of nodes from two immutable files. Load each owned
        // file once, but still verify every node/hash/anchor; never retain this map across requests.
        var documents = new java.util.HashMap<UUID, ObjectNode>();
        for (var reference : references) {
            if (!Set.of("RUN_UPLOAD", "EXTERNAL_FULLTEXT").contains(reference.path("kind").asText())) continue;
            var id = UUID.fromString(reference.path("document_id").asText());
            validate(reference, null, documents.computeIfAbsent(id, this::get));
        }
    }

    private void validate(JsonNode reference, String excerpt, ObjectNode source) {
        if (!"READY".equals(source.path("status").asText())
                || !source.path("conversation_id").asText().equals(reference.path("resource_id").asText())
                || !source.path("version_id").asText().equals(reference.path("version_id").asText())
                || !source.path("version_id").asText().equals(reference.path("publication_id").asText())
                || !source.path("kind").asText().equals(reference.path("kind").asText()))
            throw new ApiException(ApiErrorCode.DATA_SCOPE_DENIED);
        var node = java.util.stream.StreamSupport.stream(source.path("parsed").path("nodes").spliterator(), false)
            .filter(item -> item.path("key").asText().equals(reference.path("source_node_key").asText())).findFirst()
            .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (!com.jsd.aird.kb.api.SourceAnchorComparison.same(node.path("anchor"), reference.path("anchor"))
                || !source.path("name").asText().equals(reference.path("title").asText())
                || !java.util.Objects.equals(source.path("provenance").path("source_url").asText(null), reference.path("url").asText(null))
                || !sha(node.path("text").asText()).equals(reference.path("content_sha256").asText())
                || excerpt != null && !node.path("text").asText().contains(excerpt))
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
    }

    public FileStorageFacade.StoredFile open(UUID id) {
        var source = get(id);
        return files.open(ActorContext.required().organizationId(), UUID.fromString(source.path("version_id").asText()));
    }

    private static String sha(String text) { return sha(text.getBytes(StandardCharsets.UTF_8)); }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
