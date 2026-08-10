package com.jsd.aird.ai.infrastructure;

import java.util.List;
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
    public void insertConversation(UUID id, UUID organizationId, String title, UUID actorId, JsonNode scopeSnapshot) {
        jdbc.update("""
                INSERT INTO ai.assistant_conversation (id, organization_id, title, title_source, scope_snapshot_jsonb, created_by)
                VALUES (?, ?, ?, 'FIRST_QUESTION', ?, ?)
                """, id, organizationId, title, json(scopeSnapshot), actorId);
    }

    @Override
    public boolean conversationExists(UUID organizationId, UUID conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM ai.assistant_conversation WHERE organization_id = ? AND id = ?)
                """, Boolean.class, organizationId, conversationId));
    }

    @Override
    public void insertMessage(UUID conversationId, String role, String content, JsonNode citations, JsonNode warnings) {
        jdbc.update("""
                INSERT INTO ai.assistant_message (id, conversation_id, role, content, citations_jsonb, warnings_jsonb)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), conversationId, role, content, json(citations), json(warnings));
        jdbc.update("UPDATE ai.assistant_conversation SET updated_at = now() WHERE id = ?", conversationId);
    }

    @Override
    public void insertMessage(UUID conversationId, String role, String content, JsonNode citations, JsonNode warnings,
                              JsonNode queryPlan, JsonNode retrievalTrace) {
        jdbc.update("""
                INSERT INTO ai.assistant_message (
                    id, conversation_id, role, content, citations_jsonb, warnings_jsonb, query_plan_jsonb, retrieval_trace_jsonb
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), conversationId, role, content, json(citations), json(warnings),
                json(queryPlan), json(retrievalTrace));
        jdbc.update("UPDATE ai.assistant_conversation SET updated_at = now() WHERE id = ?", conversationId);
    }

    @Override
    public List<MessageRow> recentMessages(UUID organizationId, UUID conversationId, int limit) {
        return jdbc.query("""
                SELECT m.id, m.role, m.content, m.citations_jsonb, m.warnings_jsonb
                FROM ai.assistant_message m
                JOIN ai.assistant_conversation c ON c.id = m.conversation_id
                WHERE c.organization_id = ? AND c.id = ?
                ORDER BY m.created_at DESC
                LIMIT ?
                """, (rs, rowNum) -> new MessageRow(rs.getObject("id", UUID.class), rs.getString("role"), rs.getString("content"),
                        readJson(rs.getString("citations_jsonb")), readJson(rs.getString("warnings_jsonb"))),
                organizationId, conversationId, limit).reversed();
    }

    @Override
    public ConversationMeta conversation(UUID organizationId, UUID conversationId) {
        return jdbc.query("""
                SELECT id, title, summary, title_source, summary_token_count,
                       last_summarized_message_id, scope_snapshot_jsonb
                FROM ai.assistant_conversation
                WHERE organization_id = ? AND id = ?
                """, (rs, rowNum) -> new ConversationMeta(
                rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("summary"),
                rs.getString("title_source"), rs.getInt("summary_token_count"),
                rs.getObject("last_summarized_message_id", UUID.class), readJson(rs.getString("scope_snapshot_jsonb"))),
                organizationId, conversationId).stream().findFirst().orElse(null);
    }

    @Override
    public List<ConversationMeta> listConversations(UUID organizationId, int limit) {
        return jdbc.query("""
                SELECT id, title, summary, title_source, summary_token_count,
                       last_summarized_message_id, scope_snapshot_jsonb
                FROM ai.assistant_conversation
                WHERE organization_id = ?
                ORDER BY updated_at DESC
                LIMIT ?
                """, (rs, rowNum) -> new ConversationMeta(
                rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("summary"),
                rs.getString("title_source"), rs.getInt("summary_token_count"),
                rs.getObject("last_summarized_message_id", UUID.class), readJson(rs.getString("scope_snapshot_jsonb"))),
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
    public void updateScopeSnapshot(UUID organizationId, UUID conversationId, JsonNode scopeSnapshot) {
        jdbc.update("UPDATE ai.assistant_conversation SET scope_snapshot_jsonb = ?, updated_at = now() WHERE organization_id = ? AND id = ?",
                json(scopeSnapshot), organizationId, conversationId);
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
