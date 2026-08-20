package com.jsd.aird.spc.infrastructure;

import java.util.Optional;
import java.util.UUID;

import com.jsd.aird.ops.application.port.ConversationTitleTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcSpectrumConversationTitleTarget implements ConversationTitleTarget {

    private final JdbcTemplate jdbc;

    public JdbcSpectrumConversationTitleTarget(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String targetType() {
        return "SPC_CHAT";
    }

    @Override
    public Optional<Target> find(UUID organizationId, UUID conversationId) {
        return jdbc.query("""
                SELECT s.id, s.organization_id, s.created_by, s.title, s.title_source,
                       (SELECT m.content FROM spc.chat_message m
                        WHERE m.session_id = s.id AND m.role = 'USER'
                        ORDER BY m.created_at ASC LIMIT 1) AS first_question
                FROM spc.chat_session s
                WHERE s.organization_id = ? AND s.id = ?
                """, (rs, rowNum) -> new Target(rs.getObject("organization_id", UUID.class),
                        rs.getObject("created_by", UUID.class), rs.getObject("id", UUID.class),
                        rs.getString("title"), rs.getString("title_source"), rs.getString("first_question")),
                organizationId, conversationId).stream().findFirst();
    }

    @Override
    public boolean updateAutomaticTitle(UUID organizationId, UUID conversationId, String title) {
        return jdbc.update("""
                UPDATE spc.chat_session
                SET title = ?, title_source = 'MODEL', updated_at = now()
                WHERE organization_id = ? AND id = ? AND title_source IN ('FIRST_QUESTION', 'MODEL')
                """, title, organizationId, conversationId) > 0;
    }
}
