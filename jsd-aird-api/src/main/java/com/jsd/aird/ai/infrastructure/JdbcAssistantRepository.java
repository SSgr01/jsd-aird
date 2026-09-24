package com.jsd.aird.ai.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.ai.application.port.AssistantRepository;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAssistantRepository implements AssistantRepository {

    private final JdbcTemplate jdbc;

    public JdbcAssistantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertConversation(UUID id, UUID organizationId, String title, UUID actorId) {
        jdbc.update("""
                INSERT INTO ai.assistant_conversation (id, organization_id, title, created_by)
                VALUES (?, ?, ?, ?)
                """, id, organizationId, title, actorId);
    }

    @Override
    public boolean conversationExists(UUID organizationId, UUID conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM ai.assistant_conversation WHERE organization_id = ? AND id = ?)
                """, Boolean.class, organizationId, conversationId));
    }

    @Override
    public void insertMessage(UUID conversationId, String role, String content, JsonNode citations) {
        jdbc.update("""
                INSERT INTO ai.assistant_message (id, conversation_id, role, content, citations_jsonb)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), conversationId, role, content, json(citations));
        jdbc.update("UPDATE ai.assistant_conversation SET updated_at = now() WHERE id = ?", conversationId);
    }

    @Override
    public void insertMessage(UUID conversationId, String role, String content, JsonNode citations,
                              JsonNode queryPlan, JsonNode retrievalTrace) {
        insertMessageReturningId(conversationId, role, content, citations, queryPlan, retrievalTrace);
    }

    @Override
    public UUID insertMessageReturningId(UUID conversationId, String role, String content, JsonNode citations,
                                         JsonNode queryPlan, JsonNode retrievalTrace) {
        var messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai.assistant_message (
                    id, conversation_id, role, content, citations_jsonb, query_plan_jsonb, retrieval_trace_jsonb
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, messageId, conversationId, role, content, json(citations),
                json(queryPlan), json(retrievalTrace));
        jdbc.update("UPDATE ai.assistant_conversation SET updated_at = now() WHERE id = ?", conversationId);
        return messageId;
    }

    @Override
    public void updateMessageRetrievalTrace(UUID messageId, JsonNode retrievalTrace) {
        jdbc.update("UPDATE ai.assistant_message SET retrieval_trace_jsonb = ? WHERE id = ?",
                json(retrievalTrace), messageId);
    }

    @Override
    public ConversationRewriteResult replaceQuestion(UUID organizationId, UUID conversationId, UUID targetMessageId,
                                                     UUID expectedLastMessageId, String replacementQuestion) {
        var titleSource = jdbc.query("""
                SELECT title_source
                FROM ai.assistant_conversation
                WHERE organization_id = ? AND id = ?
                FOR UPDATE
                """, (rs, rowNum) -> rs.getString("title_source"), organizationId, conversationId)
                .stream().findFirst();
        if (titleSource.isEmpty()) {
            return ConversationRewriteResult.rejected(ConversationRewriteStatus.CONVERSATION_NOT_FOUND);
        }

        record OrderedMessage(UUID id, String role) { }
        var ordered = jdbc.query("""
                SELECT id, role
                FROM ai.assistant_message
                WHERE conversation_id = ?
                ORDER BY created_at, id
                """, (rs, rowNum) -> new OrderedMessage(rs.getObject("id", UUID.class), rs.getString("role")),
                conversationId);
        if (ordered.isEmpty() || expectedLastMessageId == null
                || !expectedLastMessageId.equals(ordered.getLast().id())) {
            return ConversationRewriteResult.rejected(ConversationRewriteStatus.STALE_CONVERSATION);
        }

        var targetIndex = -1;
        for (var index = 0; index < ordered.size(); index++) {
            if (targetMessageId.equals(ordered.get(index).id())) {
                targetIndex = index;
                break;
            }
        }
        if (targetIndex < 0) {
            return ConversationRewriteResult.rejected(ConversationRewriteStatus.MESSAGE_NOT_FOUND);
        }
        if (!"USER".equals(ordered.get(targetIndex).role())) {
            return ConversationRewriteResult.rejected(ConversationRewriteStatus.TARGET_NOT_USER);
        }

        var firstQuestionReplaced = ordered.subList(0, targetIndex).stream()
                .noneMatch(message -> "USER".equals(message.role()));
        var deleted = ordered.subList(targetIndex, ordered.size());
        jdbc.batchUpdate("DELETE FROM ai.assistant_message WHERE conversation_id = ? AND id = ?",
                deleted.stream().map(message -> new Object[]{conversationId, message.id()}).toList());

        var automaticTitleReset = firstQuestionReplaced && !"USER".equalsIgnoreCase(titleSource.get());
        var replacementTitle = replacementQuestion.substring(0, Math.min(80, replacementQuestion.length()));
        jdbc.update("""
                UPDATE ai.assistant_conversation
                SET summary = NULL,
                    summary_version = NULL,
                    summary_token_count = 0,
                    last_summarized_message_id = NULL,
                    title = CASE WHEN ? THEN ? ELSE title END,
                    title_source = CASE WHEN ? THEN 'FIRST_QUESTION' ELSE title_source END,
                    updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, automaticTitleReset, replacementTitle, automaticTitleReset, organizationId, conversationId);
        var replacementMessageId = insertMessageReturningId(conversationId, "USER", replacementQuestion,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        return new ConversationRewriteResult(ConversationRewriteStatus.UPDATED, replacementMessageId,
                deleted.size(), firstQuestionReplaced, automaticTitleReset);
    }

    @Override
    public List<MessageRow> recentMessages(UUID organizationId, UUID conversationId, int limit) {
        return jdbc.query("""
                SELECT m.id, m.role, m.content, m.citations_jsonb
                FROM ai.assistant_message m
                JOIN ai.assistant_conversation c ON c.id = m.conversation_id
                WHERE c.organization_id = ? AND c.id = ?
                ORDER BY m.created_at DESC, m.id DESC
                LIMIT ?
                """, (rs, rowNum) -> new MessageRow(rs.getObject("id", UUID.class), rs.getString("role"), rs.getString("content"),
                        readJson(rs.getString("citations_jsonb"))),
                organizationId, conversationId, limit).reversed();
    }

    @Override
    public Optional<String> recentDataFileMention(UUID organizationId, UUID conversationId) {
        return jdbc.query("""
                SELECT source_text
                FROM (
                    SELECT m.created_at, 1 AS source_priority, m.content AS source_text
                    FROM ai.assistant_message m
                    JOIN ai.assistant_conversation c ON c.id = m.conversation_id
                    WHERE c.organization_id = ? AND c.id = ? AND m.role = 'USER'
                      AND m.content ~* '\\.(xlsx|xls|csv)([》”"[:space:]，。；;：:]|$)'
                    UNION ALL
                    SELECT m.created_at, 2 AS source_priority, citation ->> 'originalName' AS source_text
                    FROM ai.assistant_message m
                    JOIN ai.assistant_conversation c ON c.id = m.conversation_id
                    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(m.citations_jsonb, '[]'::jsonb)) citation
                    WHERE c.organization_id = ? AND c.id = ? AND m.role = 'ASSISTANT'
                      AND citation ->> 'sourceType' = 'DATA_SOURCE_FILE'
                      AND citation ->> 'originalName' ~* '\\.(xlsx|xls|csv)$'
                ) context_file
                ORDER BY created_at DESC, source_priority DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getString("source_text"),
                organizationId, conversationId, organizationId, conversationId)
                .stream().filter(org.springframework.util.StringUtils::hasText).findFirst();
    }

    @Override
    public ConversationMeta conversation(UUID organizationId, UUID conversationId) {
        return jdbc.query("""
                SELECT id, title, summary, title_source, summary_token_count,
                       last_summarized_message_id
                FROM ai.assistant_conversation
                WHERE organization_id = ? AND id = ?
                """, (rs, rowNum) -> new ConversationMeta(
                rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("summary"),
                rs.getString("title_source"), rs.getInt("summary_token_count"),
                rs.getObject("last_summarized_message_id", UUID.class)),
                organizationId, conversationId).stream().findFirst().orElse(null);
    }

    @Override
    public List<ConversationMeta> listConversations(UUID organizationId, int limit) {
        return jdbc.query("""
                SELECT id, title, summary, title_source, summary_token_count,
                       last_summarized_message_id
                FROM ai.assistant_conversation
                WHERE organization_id = ?
                ORDER BY updated_at DESC
                LIMIT ?
                """, (rs, rowNum) -> new ConversationMeta(
                rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("summary"),
                rs.getString("title_source"), rs.getInt("summary_token_count"),
                rs.getObject("last_summarized_message_id", UUID.class)),
                organizationId, Math.min(100, Math.max(1, limit)));
    }

    @Override
    public void updateTitle(UUID organizationId, UUID conversationId, String title, String source) {
        jdbc.update("UPDATE ai.assistant_conversation SET title = ?, title_source = ?, updated_at = now() WHERE organization_id = ? AND id = ?",
                title, source, organizationId, conversationId);
    }

    @Override
    public void updateSummary(UUID organizationId, UUID conversationId, String summary, String version,
                              int tokenCount, UUID lastMessageId) {
        jdbc.update("""
                UPDATE ai.assistant_conversation
                SET summary = ?, summary_version = ?, summary_token_count = ?, last_summarized_message_id = ?, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """, summary, version, tokenCount, lastMessageId, organizationId, conversationId);
    }

    @Override
    public void renameOrDelete(UUID organizationId, UUID conversationId, String title, boolean delete) {
        if (delete) {
            jdbc.update("DELETE FROM ai.assistant_conversation WHERE organization_id = ? AND id = ?", organizationId, conversationId);
        } else {
            updateTitle(organizationId, conversationId, title, "USER");
        }
    }

    @Override
    public void insertCallAudit(UUID organizationId, UUID actorId, UUID conversationId, String requestKind,
                                String model, String promptVersion, String requestHash, String responseHash,
                                int inputTokens, int outputTokens, int totalTokens, String status, String errorMessage) {
        jdbc.update("""
                INSERT INTO ai.ai_call_audit (
                    id, organization_id, actor_id, conversation_id, request_kind, model, prompt_version,
                    request_sha256, response_sha256, input_tokens, output_tokens, total_tokens, status, error_message
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), organizationId, actorId, conversationId, requestKind, model, promptVersion,
                requestHash, responseHash, inputTokens, outputTokens, totalTokens, status, errorMessage);
    }

    private PGobject json(JsonNode value) {
        try {
            var json = new PGobject();
            json.setType("jsonb");
            json.setValue(value == null ? "[]" : value.toString());
            return json;
        } catch (Exception exception) {
            throw new IllegalArgumentException("AI 消息结构序列化失败", exception);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return value == null ? null : new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
        } catch (Exception exception) {
            return null;
        }
    }
}
