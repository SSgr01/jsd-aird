package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.application.port.AssistantRepository;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/** Public HTTP projection. Native dsh owns model selection, tools and conversation execution. */
@Service
public class AssistantService {
    private final AssistantRepository repository;
    private final ObjectMapper mapper;
    private final HarnessQaClient harness;
    private final RagRetrievalService retrieval;
    private final AgentSourceAuthorization sources;
    private final ExecutorService executor;

    public AssistantService(AssistantRepository repository, ObjectMapper mapper, HarnessQaClient harness,
            RagRetrievalService retrieval, AgentSourceAuthorization sources,
            @Qualifier("aiStreamExecutor") ExecutorService executor) {
        this.repository = repository; this.mapper = mapper; this.harness = harness;
        this.retrieval = retrieval; this.sources = sources; this.executor = executor;
    }

    public SseEmitter stream(AskCommand command) {
        if (command == null || !StringUtils.hasText(command.question()) || command.question().length() > 12000)
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "问题不能为空且不能超过 12000 字符");
        if ((command.replaceFromMessageId() == null) != (command.expectedLastMessageId() == null))
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "编辑重发必须同时提供目标消息和会话最新消息");
        var actor = ActorContext.required();
        if (command.conversationId() != null) conversation(command.conversationId());
        if (!harness.enabled()) throw new ApiException(ApiErrorCode.AI_MODEL_NOT_CONFIGURED);
        // Editing via the retained HTTP endpoint starts a new native task. It
        // never pretends that truncating a Java projection rewinds dsh history.
        var conversationId = command.conversationId() == null || command.replaceFromMessageId() != null
            ? UUID.randomUUID() : command.conversationId();
        if (!conversationId.equals(command.conversationId())) repository.insertConversation(conversationId,
            actor.organizationId(), command.question().substring(0, Math.min(80, command.question().length())), actor.userId());
        var messageId = repository.insertMessageReturningId(conversationId, "USER", command.question().strip(),
            mapper.createArrayNode(), mapper.createObjectNode(), mapper.createObjectNode());
        var traceId = RequestIdHolder.currentOrUnknown();
        var emitter = new SseEmitter(20L * 60L * 1000L);
        var subscription = new AtomicReference<Disposable>();
        emitter.onCompletion(() -> { var active = subscription.get(); if (active != null) active.dispose(); });
        send(emitter, "meta", Map.of("conversationId", conversationId, "traceId", traceId));
        send(emitter, "context", Map.of("conversationId", conversationId, "userMessageId", messageId));
        executor.submit(() -> {
            var answer = new StringBuilder(); var usage = new int[2]; var sequence = new int[1];
            var citations = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>(mapper.createArrayNode());
            var active = harness.stream(actor, conversationId, messageId, command.question(),
                command.knowledgeCategoryIds(), command.dataCategoryIds(), command.webSearchEnabled()).subscribe(chunk -> {
                    if (chunk.reset()) answer.setLength(0);
                    answer.append(chunk.text());
                    if (chunk.citations() != null && chunk.citations().isArray()) citations.set(chunk.citations());
                    usage[0] += chunk.inputTokens(); usage[1] += chunk.outputTokens();
                    if (!chunk.text().isEmpty() || chunk.reset()) send(emitter, "token", Map.of(
                        "sequence", sequence[0]++, "delta", chunk.text(), "replace", chunk.reset(), "traceId", traceId));
                }, error -> {
                    var code = error instanceof ApiException failure ? failure.errorCode() : ApiErrorCode.AI_PROVIDER_UNAVAILABLE;
                    send(emitter, "error", Map.of("code", code.code(), "message", error instanceof ApiException
                        ? error.getMessage() : "任务执行服务暂不可用，请稍后重试", "traceId", traceId));
                    emitter.complete();
                }, () -> {
                    try {
                        var text = answer.toString();
                        var assistantId = repository.insertMessageReturningId(conversationId, "ASSISTANT", text,
                            citations.get(), mapper.createObjectNode(), mapper.createObjectNode().put("engine", "dsh-v1"));
                        send(emitter, "done", new AssistantResponse(conversationId, messageId, assistantId, text,
                            mapper.convertValue(citations.get(), new com.fasterxml.jackson.core.type.TypeReference<List<Citation>>() {}),
                            false, traceId, new Usage(usage[0], usage[1], usage[0] + usage[1])));
                    } finally { emitter.complete(); }
                });
            subscription.set(active);
        });
        return emitter;
    }

    public ConversationView conversation(UUID conversationId) {
        var actor = ActorContext.required();
        if (!repository.conversationOwned(actor.organizationId(), conversationId, actor.userId()))
            throw new ApiException(ApiErrorCode.NOT_FOUND, "会话不存在");
        var messages = repository.recentMessages(actor.organizationId(), conversationId, 100);
        for (var message : messages) if (message.citations() != null) sources.authorizeHistoryCitations(message.citations());
        return new ConversationView(conversationId, messages);
    }

    public boolean conversationExists(UUID conversationId) {
        var actor = ActorContext.required();
        return repository.conversationOwned(actor.organizationId(), conversationId, actor.userId());
    }

    public List<AssistantRepository.ConversationMeta> listConversations() {
        var actor = ActorContext.required();
        return repository.listOwnedConversations(actor.organizationId(), actor.userId(), 100).stream().filter(item -> {
            try { conversation(item.id()); return true; } catch (ApiException denied) { return false; }
        }).toList();
    }

    public Capabilities capabilities() { return new Capabilities(retrieval.webSearchAvailable()); }

    private void send(SseEmitter emitter, String event, Object value) {
        try { emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(value))); }
        catch (Exception disconnected) { emitter.complete(); }
    }

    public record AskCommand(UUID conversationId, UUID replaceFromMessageId, UUID expectedLastMessageId,
                             String question,
                             List<UUID> knowledgeCategoryIds, List<UUID> dataCategoryIds,
                             boolean webSearchEnabled) {
        public AskCommand {
            knowledgeCategoryIds = knowledgeCategoryIds == null ? List.of() : List.copyOf(knowledgeCategoryIds);
            dataCategoryIds = dataCategoryIds == null ? List.of() : List.copyOf(dataCategoryIds);
        }
    }
    public record AssistantResponse(UUID conversationId, UUID userMessageId, UUID assistantMessageId,
                                    String answer, List<Citation> citations,
                                    boolean usedWebSearch, String traceId, Usage usage) { }
    public record Citation(String sourceType, String chunkId, String documentId, String versionId, String fileObjectId,
                           String importJobId, Integer rowNumber, String title, String originalName, Integer pageNo,
                           String section, String snippet, double retrievalScore, double rrfScore, double rerankScore,
                           String sourceLocator, com.fasterxml.jackson.databind.JsonNode anchor,
                           List<com.fasterxml.jackson.databind.JsonNode> anchors, List<UUID> reviewNodeIds,
                           List<UUID> sourceNodeKeys, String url, String siteName, String publishedAt,
                           String fetchedAt, String contentHash, String sourceOwner, String recognitionMode,
                           UUID experimentId, UUID experimentVersionId, String evidenceId, com.fasterxml.jackson.databind.JsonNode source) {
        public Citation(String sourceType, String chunkId, String documentId, String versionId, String fileObjectId,
                           String importJobId, Integer rowNumber, String title, String originalName, Integer pageNo,
                           String section, String snippet, double retrievalScore, double rrfScore, double rerankScore,
                           String sourceLocator, com.fasterxml.jackson.databind.JsonNode anchor,
                           List<com.fasterxml.jackson.databind.JsonNode> anchors, List<UUID> reviewNodeIds,
                           List<UUID> sourceNodeKeys, String url, String siteName, String publishedAt,
                           String fetchedAt, String contentHash, String sourceOwner, String recognitionMode,
                           UUID experimentId, UUID experimentVersionId) {
            this(sourceType, chunkId, documentId, versionId, fileObjectId, importJobId, rowNumber, title, originalName, pageNo, section, snippet, retrievalScore, rrfScore, rerankScore, sourceLocator, anchor, anchors, reviewNodeIds, sourceNodeKeys, url, siteName, publishedAt, fetchedAt, contentHash, sourceOwner, recognitionMode, experimentId, experimentVersionId, null, null);
        }

        Citation withEvidence(com.fasterxml.jackson.databind.JsonNode value) {
            return new Citation(sourceType, chunkId, documentId, versionId, fileObjectId, importJobId, rowNumber, title, originalName, pageNo, section, snippet, retrievalScore, rrfScore, rerankScore, sourceLocator, anchor, anchors, reviewNodeIds, sourceNodeKeys, url, siteName, publishedAt, fetchedAt, contentHash, sourceOwner, recognitionMode, experimentId, experimentVersionId, com.jsd.aird.ai.api.EvidenceProtocol.evidenceId(value), value);
        }

        public Citation(String sourceType, String chunkId, String documentId, String versionId, String fileObjectId,
                        String importJobId, Integer rowNumber, String title, String originalName, Integer pageNo,
                        String section, String snippet, double retrievalScore, double rrfScore, double rerankScore,
                        String sourceLocator, com.fasterxml.jackson.databind.JsonNode anchor,
                        List<com.fasterxml.jackson.databind.JsonNode> anchors, List<UUID> reviewNodeIds,
                        List<UUID> sourceNodeKeys, String url, String siteName, String publishedAt,
                        String fetchedAt, String contentHash) {
            this(sourceType, chunkId, documentId, versionId, fileObjectId, importJobId, rowNumber, title,
                    originalName, pageNo, section, snippet, retrievalScore, rrfScore, rerankScore, sourceLocator,
                    anchor, anchors, reviewNodeIds, sourceNodeKeys, url, siteName, publishedAt, fetchedAt,
                    contentHash, null, null, null, null);
        }

        public Citation(String chunkId, String documentId, String versionId, String title, String originalName,
                        Integer pageNo, String section, String snippet, double score) {
            this("KNOWLEDGE_CHUNK", chunkId, documentId, versionId, null, null, null, title, originalName, pageNo,
                    section, snippet, score, score, score, null, null, List.of(), List.of(), List.of(),
                    null, null, null, null, null);
        }
    }
    public record Usage(int inputTokens, int outputTokens, int totalTokens) { }
    public record Capabilities(boolean webSearchAvailable) { }
    public record ConversationView(UUID conversationId, List<AssistantRepository.MessageRow> messages) { }
}
