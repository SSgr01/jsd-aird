package com.jsd.aird.ai.adapter.in.web;

import java.util.UUID;
import java.util.List;

import com.jsd.aird.ai.application.AssistantService;
import com.jsd.aird.ai.application.ConversationMemoryService;
import com.jsd.aird.ai.application.RagRetrievalService;
import com.jsd.aird.ai.application.port.AssistantRepository;
import com.jsd.aird.kb.api.KnowledgeScopeFacade;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.security.ActorContext;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final AssistantService assistant;
    private final ConversationMemoryService memory;
    private final RagRetrievalService rag;
    private final KnowledgeScopeFacade scopes;

    public AssistantController(AssistantService assistant, ConversationMemoryService memory,
                               RagRetrievalService rag, KnowledgeScopeFacade scopes) {
        this.assistant = assistant;
        this.memory = memory;
        this.rag = rag;
        this.scopes = scopes;
    }

    @PostMapping("/qa")
    public ApiResponse<AssistantService.AssistantResponse> qa(@RequestBody QaRequest request) {
        return success(assistant.ask(request.command()));
    }

    @PostMapping(value = "/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody QaRequest request) {
        return assistant.stream(request.command());
    }

    @GetMapping("/conversations/{id}")
    public ApiResponse<AssistantService.ConversationView> conversation(@PathVariable UUID id) {
        return success(assistant.conversation(id));
    }

    @PostMapping("/file-search")
    public ApiResponse<?> fileSearch(@RequestBody FileSearchRequest request) {
        var actor = ActorContext.required();
        return success(rag.retrieve(actor.organizationId(), request.query(), List.of(), request.scopeIds(),
                request.scopeTypes(), request.knowledgeCategoryIds(), request.dataCategoryIds(),
                !Boolean.FALSE.equals(request.aiOnly())));
    }

    @GetMapping("/scopes")
    public ApiResponse<List<KnowledgeScopeFacade.ScopeView>> scopes(
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) String keyword) {
        var actor = ActorContext.required();
        return success(scopes.list(actor.organizationId(), scopeType, keyword));
    }

    @PostMapping("/scopes")
    public ApiResponse<KnowledgeScopeFacade.ScopeView> createScope(@RequestBody ScopeRequest request) {
        var actor = ActorContext.required();
        return success(scopes.create(actor.organizationId(), actor.userId(), new KnowledgeScopeFacade.CreateScope(
                request.scopeType(), request.externalId(), request.name(), request.metadata())));
    }

    @GetMapping("/scopes/{id}/resources")
    public ApiResponse<List<KnowledgeScopeFacade.ScopeResource>> scopeResources(@PathVariable UUID id) {
        return success(scopes.resources(ActorContext.required().organizationId(), id));
    }

    @PostMapping("/scopes/{id}/resources")
    public ApiResponse<Void> attachScopeResource(@PathVariable UUID id, @RequestBody ResourceRequest request) {
        scopes.attach(ActorContext.required().organizationId(), id,
                new KnowledgeScopeFacade.AttachResource(request.resourceType(), request.resourceId(), request.relationType()));
        return success(null);
    }

    @GetMapping("/conversations")
    public ApiResponse<List<AssistantRepository.ConversationMeta>> conversations() {
        return success(memory.list(ActorContext.required().organizationId(), 100));
    }

    @PatchMapping("/conversations/{id}")
    public ApiResponse<Void> rename(@PathVariable UUID id, @RequestBody RenameRequest request) {
        var actor = ActorContext.required();
        requireConversation(id);
        memory.rename(actor.organizationId(), id, request.title());
        return success(null);
    }

    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        requireConversation(id);
        memory.delete(ActorContext.required().organizationId(), id);
        return success(null);
    }

    @PostMapping("/conversations/{id}/summarize")
    public ApiResponse<Void> summarize(@PathVariable UUID id) {
        requireConversation(id);
        memory.summarize(ActorContext.required().organizationId(), id);
        return success(null);
    }

    private void requireConversation(UUID id) {
        if (!assistant.conversationExists(id)) {
            throw new com.jsd.aird.shared.error.ApiException(com.jsd.aird.shared.error.ApiErrorCode.NOT_FOUND, "会话不存在");
        }
    }

    private <T> ApiResponse<T> success(T value) {
        return ResponseFactory.success(value, RequestIdHolder.currentOrUnknown());
    }

    public record QaRequest(UUID conversationId, @Size(min = 1, max = 3000) String question,
                            List<UUID> scopeIds, List<String> scopeTypes, List<UUID> knowledgeCategoryIds,
                            List<UUID> dataCategoryIds) {
        AssistantService.AskCommand command() {
            return new AssistantService.AskCommand(conversationId, question, scopeIds, scopeTypes,
                    knowledgeCategoryIds, dataCategoryIds);
        }
    }
    public record FileSearchRequest(@Size(min = 1, max = 1000) String query, Boolean aiOnly, int limit,
                                    List<UUID> scopeIds, List<String> scopeTypes, List<UUID> knowledgeCategoryIds,
                                    List<UUID> dataCategoryIds) {
        public int safeLimit() { return limit <= 0 ? 20 : Math.min(50, limit); }
    }
    public record ScopeRequest(String scopeType, String externalId, String name,
                               com.fasterxml.jackson.databind.JsonNode metadata) { }
    public record ResourceRequest(String resourceType, UUID resourceId, String relationType) { }
    public record RenameRequest(@Size(min = 1, max = 80) String title) { }
}
