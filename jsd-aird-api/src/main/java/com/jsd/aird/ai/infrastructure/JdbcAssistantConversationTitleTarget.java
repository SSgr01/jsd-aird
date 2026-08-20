package com.jsd.aird.ai.infrastructure;

import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.ops.application.port.ConversationTitleTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAssistantConversationTitleTarget implements ConversationTitleTarget {

    private final JdbcTemplate jdbc;

    public JdbcAssistantConversationTitleTarget(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String targetType() {
        return "AI_QA";
    }

    @Override
    public Optional<Target> find(UUID organizationId, UUID conversationId) {
        return jdbc.query("""
                SELECT c.id, c.organization_id, c.created_by, c.title, c.title_source,
                       (SELECT m.content FROM ai.assistant_message m
                        WHERE m.conversation_id = c.id AND m.role = 'USER'
                        ORDER BY m.created_at ASC LIMIT 1) AS first_question
                FROM ai.assistant_conversation c
                WHERE c.organization_id = ? AND c.id = ?
                """, (rs, rowNum) -> new Target(rs.getObject("organization_id", UUID.class),
                        rs.getObject("created_by", UUID.class), rs.getObject("id", UUID.class),
                        rs.getString("title"), rs.getString("title_source"), rs.getString("first_question")),
                organizationId, conversationId).stream().findFirst();
    }

    @Override
    public boolean updateAutomaticTitle(UUID organizationId, UUID conversationId, String title) {
        return jdbc.update("""
                UPDATE ai.assistant_conversation
                SET title = ?, title_source = 'MODEL', updated_at = now()
                WHERE organization_id = ? AND id = ? AND title_source IN ('FIRST_QUESTION', 'MODEL')
                """, title, organizationId, conversationId) > 0;
    }
}
