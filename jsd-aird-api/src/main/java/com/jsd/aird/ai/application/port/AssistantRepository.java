package com.jsd.aird.ai.application.port;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public interface AssistantRepository {

    void insertConversation(UUID id, UUID organizationId, String title, UUID actorId);

    boolean conversationExists(UUID organizationId, UUID conversationId);

    void insertMessage(UUID conversationId, String role, String content, JsonNode citations);

    default void insertMessage(UUID conversationId, String role, String content, JsonNode citations,
                               JsonNode queryPlan, JsonNode retrievalTrace) {
        insertMessage(conversationId, role, content, citations);
    }

    /** Inserts a message and returns its id when the backing store supports it. */
    default UUID insertMessageReturningId(UUID conversationId, String role, String content, JsonNode citations,
                                          JsonNode queryPlan, JsonNode retrievalTrace) {
        insertMessage(conversationId, role, content, citations, queryPlan, retrievalTrace);
        return null;
    }

    /** Allows the caller to append post-insert timings to the persisted trace. */
    default void updateMessageRetrievalTrace(UUID messageId, JsonNode retrievalTrace) {
    }

    List<MessageRow> recentMessages(UUID organizationId, UUID conversationId, int limit);

    default ConversationMeta conversation(UUID organizationId, UUID conversationId) {
        return new ConversationMeta(conversationId, "", null, null, 0, null);
    }

    default List<ConversationMeta> listConversations(UUID organizationId, int limit) {
        return List.of();
    }

    default void updateTitle(UUID organizationId, UUID conversationId, String title, String source) {
    }

    default void updateSummary(UUID organizationId, UUID conversationId, String summary, String version,
                               int tokenCount, UUID lastMessageId) {
    }

    default void renameOrDelete(UUID organizationId, UUID conversationId, String title, boolean delete) {
    }

    void insertCallAudit(UUID organizationId, UUID actorId, UUID conversationId, String requestKind,
                          String model, String promptVersion, String requestHash, String responseHash,
                          int inputTokens, int outputTokens, int totalTokens, String status, String errorMessage);

    record MessageRow(UUID id, String role, String content, JsonNode citations) {
        public MessageRow(String role, String content) {
            this(null, role, content, null);
        }
    }

    record ConversationMeta(UUID id, String title, String summary, String titleSource, int summaryTokenCount,
                            UUID lastSummarizedMessageId) { }
}
